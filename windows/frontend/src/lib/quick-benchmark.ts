import { Channel, invoke } from "@tauri-apps/api/core";
import { useSyncExternalStore } from "react";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";

export type QuickBackendId = "cpu" | "vulkan";
export type QuickBenchmarkProgress = { backend: QuickBackendId; completed: number; total: number };

export type CpuRuntimeTuning = {
    tuning_version?: number;
    decode_threads: number;
    batch_threads?: number | null;
    direct_model_first: boolean;
    model_id: string;
    topology_signature: string;
};

export type QuickBackendResult = {
    backend: QuickBackendId;
    avg_latency_ms: number | null;
    p95_latency_ms: number | null;
    error: string | null;
};

export type QuickBenchmarkResult = {
    model_id: string;
    vulkan_gpu_layers: number;
    case_count: number;
    results: QuickBackendResult[];
    recommended: QuickBackendId | null;
    cpu_tuning?: CpuRuntimeTuning | null;
};

export const BACKEND_LABELS: Record<string, string> = {
    cpu: "CPU",
    vulkan: "Vulkan",
};

const RECOMMENDATION_STORAGE_KEY = "zerocon-quick-benchmark-candidates-v1";
const resultEvents = new EventTarget();
type Activity = "idle" | "measuring" | "applying";
type Outcome = "measured" | "applied" | "pending" | "unchanged" | "stale" | "failed";
type RunResult = { result: QuickBenchmarkResult; outcome: Outcome };
type RuntimeResult = { backend: QuickBackendId; applied: boolean };
let activity: Activity = "idle";
let running: Promise<RunResult> | undefined;
let pendingRuntime = false;

function setActivity(next: Activity) {
    activity = next;
    resultEvents.dispatchEvent(new Event("activity"));
}

const subscribeActivity = (listener: () => void) => {
    resultEvents.addEventListener("activity", listener);
    return () => resultEvents.removeEventListener("activity", listener);
};
const getActivity = () => activity;
export const useQuickBenchmarkActivity = () => useSyncExternalStore(subscribeActivity, getActivity);

export const loadQuickBenchmarkRecommendation = (): QuickBenchmarkResult | null => {
    try {
        const stored = localStorage.getItem(RECOMMENDATION_STORAGE_KEY);
        if (!stored) return null;
        const parsed = JSON.parse(stored) as QuickBenchmarkResult;
        return parsed && typeof parsed === "object" && Array.isArray(parsed.results)
            ? parsed
            : null;
    } catch {
        return null;
    }
};

const saveQuickBenchmarkResult = (result: QuickBenchmarkResult): void => {
    try {
        localStorage.setItem(RECOMMENDATION_STORAGE_KEY, JSON.stringify(result));
    } catch {
        // 保存できなくても測定は継続できる。
    }
    resultEvents.dispatchEvent(new CustomEvent("result", { detail: result }));
};

export const subscribeQuickBenchmarkResult = (listener: (result: QuickBenchmarkResult) => void) => {
    const handleResult = (event: Event) => listener((event as CustomEvent<QuickBenchmarkResult>).detail);
    resultEvents.addEventListener("result", handleResult);
    return () => resultEvents.removeEventListener("result", handleResult);
};

export const subscribeQuickBenchmarkRuntime = (listener: (result: RuntimeResult) => void) => {
    const handleRuntime = (event: Event) => listener((event as CustomEvent<RuntimeResult>).detail);
    resultEvents.addEventListener("runtime", handleRuntime);
    return () => resultEvents.removeEventListener("runtime", handleRuntime);
};

export const hasPendingQuickBenchmarkRuntime = () => pendingRuntime;
export const clearPendingQuickBenchmarkRuntime = () => { pendingRuntime = false; };

async function executeBenchmark(applyRecommendation: boolean, onProgress?: (progress: QuickBenchmarkProgress) => void): Promise<RunResult> {
    const initial = (await getConfigAfterPendingUpdates()).zenzai;
    const activeModel = (await invoke<{ active_id: string }>("get_active_model")).active_id;
    const progress = onProgress ? new Channel<QuickBenchmarkProgress>() : undefined;
    if (progress && onProgress) progress.onmessage = onProgress;
    const result = await invoke<QuickBenchmarkResult>("run_quick_benchmark", { progress });
    saveQuickBenchmarkResult(result);
    const backend = applyRecommendation ? result.recommended : null;
    const cpuTuning = result.cpu_tuning ?? null;
    if (!backend && !cpuTuning) return { result, outcome: "measured" };

    setActivity("applying");
    let outcome: Outcome = "stale";
    let changed = false;
    let appliedBackend: QuickBackendId = initial.backend;
    const saved = await saveConfigWithToast((config) => {
        const current = config.zenzai;
        if (current.backend !== initial.backend || current.model !== initial.model
            || Math.min(current.gpu_layers ?? 99, 99) !== result.vulkan_gpu_layers
            || activeModel !== result.model_id) return false;
        const backendChanged = Boolean(backend && current.backend !== backend);
        const tuningChanged = Boolean(
            cpuTuning && JSON.stringify(current.cpu_runtime_tuning ?? null) !== JSON.stringify(cpuTuning),
        );
        if (!backendChanged && !tuningChanged) {
            outcome = "unchanged";
            return false;
        }
        if (backendChanged && backend) {
            current.backend = backend;
            appliedBackend = backend;
        }
        if (tuningChanged && cpuTuning) {
            current.cpu_runtime_tuning = cpuTuning;
        }
        changed = true;
    }, "推奨バックエンドの保存に失敗しました", (response) => {
        if (!changed || !response.saved) return;
        outcome = response.server_applied ? "applied" : "pending";
        pendingRuntime = !response.server_applied;
        if (backend && response.config.zenzai.backend === appliedBackend) {
            resultEvents.dispatchEvent(new CustomEvent<RuntimeResult>("runtime", {
                detail: { backend: appliedBackend, applied: response.server_applied },
            }));
        }
    });
    return { result, outcome: saved && (!changed || outcome !== "stale") ? outcome : "failed" };
}

export const runQuickBenchmark = (applyRecommendation = false, onProgress?: (progress: QuickBenchmarkProgress) => void) => {
    if (running) return running;
    setActivity("measuring");
    running = executeBenchmark(applyRecommendation, onProgress).finally(() => {
        running = undefined;
        setActivity("idle");
    });
    return running;
};

export const quickBenchmarkSummary = (result: QuickBenchmarkResult): string => {
    if (result.recommended) return `推奨バックエンドは ${BACKEND_LABELS[result.recommended]} です。`;
    if (result.results.every((backend) => backend.avg_latency_ms !== null) && result.results.length === 2) {
        return "平均レイテンシは同程度です。現在のバックエンドを継続できます。";
    }
    return "推奨バックエンドを判定できませんでした。";
};

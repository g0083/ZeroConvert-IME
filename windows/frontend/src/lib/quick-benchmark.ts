import { invoke } from "@tauri-apps/api/core";

export type QuickBackendId = "cpu" | "vulkan";

export type QuickBackendResult = {
    backend: QuickBackendId;
    ok: boolean;
    avg_latency_ms: number | null;
    error: string | null;
};

export type QuickBenchmarkResult = {
    results: QuickBackendResult[];
    recommended: QuickBackendId | null;
};

export const BACKEND_LABELS: Record<string, string> = {
    cpu: "CPU",
    vulkan: "Vulkan",
};

const RECOMMENDATION_STORAGE_KEY = "zerocon-quick-benchmark-recommendation-v1";

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

export const saveQuickBenchmarkResult = (result: QuickBenchmarkResult): void => {
    try {
        localStorage.setItem(RECOMMENDATION_STORAGE_KEY, JSON.stringify(result));
    } catch {
        // 保存できなくても測定は継続できる。
    }
};

export const runQuickBenchmark = () => invoke<QuickBenchmarkResult>("run_quick_benchmark");

export const quickBenchmarkRecommendationMessage = (result: QuickBenchmarkResult): string => {
    if (!result.recommended) {
        return "クイックベンチマークの結果、推奨バックエンドを判定できませんでした。";
    }
    const label = BACKEND_LABELS[result.recommended] ?? result.recommended;
    return `推奨バックエンドは ${label} です（実測レイテンシが最も短いバックエンド）。ZeroCon AI設定のバックエンドから切り替えられます。`;
};

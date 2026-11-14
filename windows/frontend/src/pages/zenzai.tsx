import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { SettingsRow, SettingsSection } from "@/components/settings-section";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";
import { createDebouncedSaver, type ConfigSaveState } from "@/lib/config-save-controller.js";
import { BACKEND_LABELS, loadQuickBenchmarkRecommendation, runQuickBenchmark, saveQuickBenchmarkResult, type QuickBackendId, type QuickBenchmarkResult } from "@/lib/quick-benchmark";

const PROFILE_SAVE_DELAY_MS = 500;

type ModelInfo = {
    id: string;
    name: string;
    filename: string;
    size_mb: number;
    description: string;
    is_active: boolean;
    available: boolean;
};

type ModelListResponse = {
    active_id: string;
    models: ModelInfo[];
};

export const Zenzai = () => {
    const [value, setValue] = useState({ enable: false, profile: "", backend: "", gpu_layers: 99 });
    const [capability, setCapability] = useState({ cpu: false, vulkan: false });
    const [capabilityError, setCapabilityError] = useState(false);
    const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [runtimeApplying, setRuntimeApplying] = useState(false);
    const [runtimePending, setRuntimePending] = useState(false);
    const [profileSaveState, setProfileSaveState] = useState<ConfigSaveState>("saved");
    const [activeModelId, setActiveModelId] = useState<string>("");
    const [models, setModels] = useState<ModelInfo[]>([]);
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);
    const savedRecommendation = useMemo(() => loadQuickBenchmarkRecommendation(), []);
    const [quickBenchmark, setQuickBenchmark] = useState<QuickBenchmarkResult | null>(savedRecommendation);
    const [recommendedBackend, setRecommendedBackend] = useState<QuickBackendId | null>(savedRecommendation?.recommended ?? null);
    const [quickBenchmarkRunning, setQuickBenchmarkRunning] = useState(false);
    const profileEdited = useRef(false);
    const profileSaver = useMemo(() => createDebouncedSaver<string, any>({
        delayMs: PROFILE_SAVE_DELAY_MS,
        onStateChange: setProfileSaveState,
        save: (profile) => saveConfigWithToast((config) => { config.zenzai.profile = profile; }),
    }), []);

    useEffect(() => {
        let cancelled = false;
        profileSaver.resume();
        setLoadState("loading");
        getConfigAfterPendingUpdates().then((data) => {
            if (cancelled) return;
            const zenzai = data.zenzai;
            if (!zenzai || typeof zenzai.enable !== "boolean" || typeof zenzai.profile !== "string" || typeof zenzai.backend !== "string") {
                throw new Error("ZeroCon AIの設定が不正です");
            }
            if (!zenzai.enable) {
                void saveConfigWithToast((config) => { config.zenzai.enable = true; });
            }
            const normalizedBackend = zenzai.backend === "cpu" ? "cpu" : "vulkan";
            setValue((previous) => ({
                enable: true,
                profile: profileEdited.current ? previous.profile : zenzai.profile,
                backend: normalizedBackend,
                gpu_layers: zenzai.gpu_layers ?? 99,
            }));
            setLoadState("ready");
        }).catch(() => { if (!cancelled) setLoadState("error"); });
        setCapabilityError(false);
        invoke<typeof capability>("check_capability").then((data) => {
            if (!cancelled) setCapability(data);
        }).catch(() => { if (!cancelled) setCapabilityError(true); });

        // Fetch active model
        invoke<ModelListResponse>("get_active_model").then((data) => {
            if (!cancelled) {
                setActiveModelId(data.active_id);
                setModels(data.models);
            }
        }).catch((err) => {
            console.error("Failed to fetch active model:", err);
        });

        return () => {
            cancelled = true;
            void profileSaver.dispose();
        };
    }, [profileSaver, loadAttempt]);

    const handleProfileChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
        const profile = event.target.value;
        profileEdited.current = true;
        setValue((previous) => ({ ...previous, profile }));
        profileSaver.schedule(profile);
    };
    const saveRuntime = async (update: { backend?: string; gpu_layers?: number }) => {
        setRuntimeApplying(true);
        try {
            const data = await saveConfigWithToast((config) => {
                Object.assign(config.zenzai, update);
            }, "推論設定の更新に失敗しました", (result) => {
                setRuntimePending(!result.server_applied);
                if (result.server_applied) toast.success("推論設定を適用しました。補正・先読みは数秒で切り替わります。");
            });
            if (data) setValue((previous) => ({ ...previous, backend: data.zenzai.backend, gpu_layers: data.zenzai.gpu_layers ?? 99 }));
        } finally {
            setRuntimeApplying(false);
        }
    };

    const retryRuntime = async () => {
        setRuntimeApplying(true);
        try {
            await invoke("restart_server");
            setRuntimePending(false);
            toast.success("IMEサーバーを再起動して設定を適用しました");
        } catch (error) {
            toast.error(`IMEサーバーを再起動できませんでした: ${error}`);
        } finally {
            setRuntimeApplying(false);
        }
    };

    const handleBackendChange = async (backend: string) => {
        if (backend === value.backend || runtimeApplying) return;
        try {
            await saveRuntime({ backend });
        } catch (error) { toast.error(`バックエンドを変更できませんでした: ${error}`); }
    };

    const handleQuickBenchmark = async () => {
        if (quickBenchmarkRunning) return;
        setQuickBenchmarkRunning(true);
        setQuickBenchmark(null);
        try {
            const result = await runQuickBenchmark();
            setQuickBenchmark(result);
            saveQuickBenchmarkResult(result);
            setRecommendedBackend(result.recommended);
        } catch (error) {
            toast.error(`クイックベンチマークを実行できませんでした: ${error}`);
        } finally {
            setQuickBenchmarkRunning(false);
        }
    };

    const handleModelChange = async (newModelId: string) => {
        if (newModelId === activeModelId || isSwitchingModel) return;
        setIsSwitchingModel(true);
        const toastId = toast.loading("モデルを切り替え中... (サーバー再起動)");
        try {
            await invoke("switch_model", { modelId: newModelId });
            setActiveModelId(newModelId);
            toast.success(`モデルを ${models.find((model) => model.id === newModelId)?.name ?? newModelId} に切り替えました`, { id: toastId });
        } catch (err) {
            toast.error(`モデル切り替えに失敗しました: ${err}`, { id: toastId });
        } finally {
            setIsSwitchingModel(false);
        }
    };

    return (
        <SettingsSection title="ZeroCon AI">
            {loadState !== "ready" ? <div className="p-4 space-y-2" role="status">
                <p className={loadState === "error" ? "text-sm text-destructive" : "settings-description"}>
                    {loadState === "error" ? "ZeroCon AIの設定を読み込めませんでした。" : "設定を読み込んでいます…"}
                </p>
                {loadState === "error" && <Button variant="outline" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>再試行</Button>}
            </div> : <>
                <SettingsRow label="利用モデル" description="かな漢字変換・内蔵のタイポ補正・文の先読みに使うZeroCon Modelを選びます。" htmlFor="zenzai-model">
                    <Select value={activeModelId} disabled={runtimeApplying || isSwitchingModel || models.length === 0} onValueChange={(modelId) => void handleModelChange(modelId)}>
                        <SelectTrigger id="zenzai-model" className="w-[320px]">
                            <SelectValue placeholder="モデルを選択" />
                        </SelectTrigger>
                        <SelectContent>
                            {models.map((model) => <SelectItem key={model.id} value={model.id} disabled={!model.available}>
                                <div className="flex flex-col py-0.5">
                                    <span className="font-medium text-foreground">{model.name}</span>
                                    <span className="text-[11px] text-muted-foreground">{model.available ? `${model.size_mb.toFixed(1)} MB · ${model.description}` : "モデル未配置"}</span>
                                </div>
                            </SelectItem>)}
                        </SelectContent>
                    </Select>
                </SettingsRow>

                <SettingsRow label="バックエンド" description="漢字変換・タイポ補正・先読みで共用します。変更時はIMEサーバーを自動で再起動します。Windowsの再起動は不要です。入力中の文字を確定してから変更してください。" htmlFor="zenzai-backend">
                    <Select value={value.backend} disabled={capabilityError || runtimeApplying || isSwitchingModel} onValueChange={(backend) => void handleBackendChange(backend)}>
                        <SelectTrigger id="zenzai-backend"><SelectValue placeholder="バックエンドを選択" /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="cpu" disabled={!capability.cpu}>{recommendedBackend === "cpu" ? "CPU (推奨)" : "CPU"}{!capability.cpu && " — AVX 対応 CPU が必要"}</SelectItem>
                            <SelectItem value="vulkan" disabled={!capability.vulkan}>{recommendedBackend === "vulkan" ? "Vulkan (推奨)" : "Vulkan"}{!capability.vulkan && " — このPCでは未対応"}</SelectItem>
                        </SelectContent>
                    </Select>
                </SettingsRow>
                {capabilityError && <p className="px-4 pb-3 text-xs text-destructive" role="alert">バックエンドの対応状況を取得できませんでした。</p>}
                <SettingsRow label="クイックベンチマーク" description="CPUとVulkanで同じ推論を実測し、完了まで数十秒かかります。レイテンシが短い方を推奨バックエンドとして表示します。" htmlFor="zenzai-quick-benchmark">
                    <Button id="zenzai-quick-benchmark" variant="outline" disabled={quickBenchmarkRunning || runtimeApplying || isSwitchingModel} onClick={() => void handleQuickBenchmark()}>
                        {quickBenchmarkRunning ? "測定中…" : "実行"}
                    </Button>
                </SettingsRow>
                {quickBenchmark && !quickBenchmarkRunning && <div className="px-4 pb-4 space-y-1" role="status" aria-live="polite">
                    {quickBenchmark.results.map((result) => (
                        <p key={result.backend} className="settings-description">
                            {BACKEND_LABELS[result.backend] ?? result.backend}: {result.ok && result.avg_latency_ms !== null ? `平均 ${result.avg_latency_ms.toFixed(0)} ms` : result.error ?? "測定できませんでした"}
                        </p>
                    ))}
                    {quickBenchmark.recommended && <p className="text-xs font-medium text-foreground">推奨: {BACKEND_LABELS[quickBenchmark.recommended] ?? quickBenchmark.recommended}（実測レイテンシが最も短いバックエンド）</p>}
                </div>}
                {value.backend === "cpu" && <p className="settings-description px-4 pb-3">
                    CPUバックエンドには AVX 対応 CPU が必要です。未対応の場合は標準変換を使います。
                </p>}
                <SettingsRow label="GPUに載せる層数" description="少なくすると残りの層の重みをRAMに置き、その層をCPUで処理します。0層ではCPUで推論するため、CPU負荷が増え、遅くなる場合があります。Vulkanでは0層でも作業領域などにVRAMを使用する場合があります。漢字変換・内蔵タイポ補正・先読みで共用します。" htmlFor="zenzai-gpu-layers">
                    <Select value={String(value.gpu_layers)} disabled={value.backend === "cpu" || runtimeApplying || isSwitchingModel} onValueChange={(layers) => void saveRuntime({ gpu_layers: Number(layers) })}>
                        <SelectTrigger id="zenzai-gpu-layers"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="99">全層（速度優先・既定）</SelectItem>
                            <SelectItem value="16">最大16層</SelectItem>
                            <SelectItem value="8">最大8層</SelectItem>
                            <SelectItem value="4">最大4層（RAM優先）</SelectItem>
                            <SelectItem value="0">0層（CPUで推論）</SelectItem>
                            {![0, 4, 8, 16, 99].includes(value.gpu_layers) && <SelectItem value={String(value.gpu_layers)}>最大{value.gpu_layers}層</SelectItem>}
                        </SelectContent>
                    </Select>
                </SettingsRow>
                {runtimeApplying && <p className="settings-description p-4" role="status">IMEサーバーを再起動して設定を適用しています…</p>}
                {runtimePending && <div className="p-4 space-y-2" role="status">
                    <p className="settings-description">設定は保存済みですが、IMEへの適用が完了していません。</p>
                    <Button variant="outline" disabled={runtimeApplying} onClick={() => void retryRuntime()}>IMEサーバーを再起動して再試行</Button>
                </div>}
                <SettingsRow label="変換プロファイル" description="名前・専門分野などを変換に反映します。" htmlFor="zenzai-profile">
                    <div className="w-full space-y-2">
                        <Textarea id="zenzai-profile" placeholder="例）山田太郎、数学科の学生。" value={value.profile}
                            aria-invalid={profileSaveState === "error"}
                            aria-describedby={profileSaveState === "error" ? "zenzai-profile-error" : undefined}
                            onChange={handleProfileChange} onBlur={() => { void profileSaver.flush(); }} />
                        {profileSaveState === "error" && <p id="zenzai-profile-error" className="text-xs text-destructive" role="alert">
                            保存できませんでした。入力内容は保持されています。
                        </p>}
                    </div>
                </SettingsRow>
            </>}
        </SettingsSection>
    );
};

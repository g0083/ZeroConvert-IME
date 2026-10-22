import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { SettingsRow, SettingsSection } from "@/components/settings-section";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";
import { createDebouncedSaver, type ConfigSaveState } from "@/lib/config-save-controller.js";

const PROFILE_SAVE_DELAY_MS = 500;

type ModelInfo = {
    id: string;
    name: string;
    filename: string;
    size_mb: number;
    description: string;
    is_active: boolean;
};

type ModelListResponse = {
    active_id: string;
    models: ModelInfo[];
};

export const Zenzai = () => {
    const [value, setValue] = useState({ enable: false, profile: "", backend: "" });
    const [capability, setCapability] = useState({ cpu: false, cuda: false, vulkan: false });
    const [capabilityError, setCapabilityError] = useState(false);
    const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [restartRequired, setRestartRequired] = useState(false);
    const [profileSaveState, setProfileSaveState] = useState<ConfigSaveState>("saved");
    const [activeModelId, setActiveModelId] = useState<string>("iq4xs");
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);
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
            setValue((previous) => ({
                enable: true,
                profile: profileEdited.current ? previous.profile : zenzai.profile,
                backend: zenzai.backend,
            }));
            setLoadState("ready");
        }).catch(() => { if (!cancelled) setLoadState("error"); });
        setCapabilityError(false);
        invoke<typeof capability>("check_capability").then((data) => {
            if (!cancelled) setCapability(data);
        }).catch(() => { if (!cancelled) setCapabilityError(true); });

        // Fetch active model
        invoke<ModelListResponse>("get_active_model").then((data) => {
            if (!cancelled && data?.active_id) {
                setActiveModelId(data.active_id);
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
    const handleBackendChange = async (backend: string) => {
        if (backend === value.backend) return;
        const data = await saveConfigWithToast((config) => { config.zenzai.backend = backend; });
        if (data) {
            setValue((previous) => ({ ...previous, backend: data.zenzai.backend }));
            setRestartRequired(true);
            toast("バックエンドが変更されました", {
                description: "変更を適用するには、PCを再起動してください",
                duration: 10000,
            });
        }
    };

    const handleModelChange = async (newModelId: string) => {
        if (newModelId === activeModelId || isSwitchingModel) return;
        setIsSwitchingModel(true);
        const toastId = toast.loading("モデルを切り替え中... (サーバー再起動)");
        try {
            await invoke("switch_model", { modelId: newModelId });
            setActiveModelId(newModelId);
            toast.success(`モデルを ${newModelId === "q4km" ? "ZeroCon Model v3.2 Standard (Q4_K_M)" : "ZeroCon Model v3.2 Fast (IQ4_XS)"} に切り替えました！`, { id: toastId });
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
                <SettingsRow label="利用モデル" description="ZeroCon AI の推論に使用するニューラル言語モデルを選択します。" htmlFor="zenzai-model">
                    <Select value={activeModelId} disabled={isSwitchingModel} onValueChange={(modelId) => void handleModelChange(modelId)}>
                        <SelectTrigger id="zenzai-model" className="w-[320px]">
                            <SelectValue placeholder="モデルを選択" />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="iq4xs">
                                <div className="flex flex-col py-0.5">
                                    <span className="font-medium text-foreground">ZeroCon Model v3.2 Fast (IQ4_XS) — 52.6MB</span>
                                    <span className="text-[11px] text-muted-foreground">超高速・軽量 (~800 tok/s)</span>
                                </div>
                            </SelectItem>
                            <SelectItem value="q4km">
                                <div className="flex flex-col py-0.5">
                                    <span className="font-medium text-foreground">ZeroCon Model v3.2 Standard (Q4_K_M) — 61.3MB</span>
                                    <span className="text-[11px] text-muted-foreground">高精度・標準 (~750 tok/s)</span>
                                </div>
                            </SelectItem>
                        </SelectContent>
                    </Select>
                </SettingsRow>

                <SettingsRow label="バックエンド" htmlFor="zenzai-backend">
                    <Select value={value.backend} disabled={capabilityError} onValueChange={(backend) => void handleBackendChange(backend)}>
                        <SelectTrigger id="zenzai-backend"><SelectValue placeholder="バックエンドを選択" /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="cpu" disabled={!capability.cpu}>CPU (非推奨){!capability.cpu && " — AVX 対応 CPU が必要"}</SelectItem>
                            <SelectItem value="cuda" disabled={!capability.cuda}>CUDA (NVIDIA GPU){!capability.cuda && " — CUDA Toolkit 12 が必要"}</SelectItem>
                            <SelectItem value="vulkan" disabled={!capability.vulkan}>Vulkan{!capability.vulkan && " — このPCでは未対応"}</SelectItem>
                        </SelectContent>
                    </Select>
                </SettingsRow>
                {capabilityError && <p className="px-4 pb-3 text-xs text-destructive" role="alert">バックエンドの対応状況を取得できませんでした。</p>}
                {value.backend === "cpu" && <p className="settings-description px-4 pb-3">
                    CPUバックエンドには AVX 対応 CPU が必要です。未対応の場合は標準変換を使います。
                </p>}
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
                {restartRequired && <p className="settings-description p-4" role="status">バックエンドの変更を適用するには、PCを再起動してください。</p>}
            </>}
        </SettingsSection>
    );
};

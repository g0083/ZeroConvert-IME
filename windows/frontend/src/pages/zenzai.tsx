import { useEffect, useMemo, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter, AlertDialogCancel, AlertDialogAction } from "@/components/ui/alert-dialog";
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
    available: boolean;
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
    const [activeModelId, setActiveModelId] = useState<string>("");
    const [models, setModels] = useState<ModelInfo[]>([]);
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);
    const [cudaDialog, setCudaDialog] = useState(false);
    const [cudaBytes, setCudaBytes] = useState(0);
    const [cudaInstalling, setCudaInstalling] = useState(false);
    const [cudaProgress, setCudaProgress] = useState(0);
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
            if (zenzai.backend === "cuda") {
                void invoke<{ installed: boolean; download_bytes: number }>("cuda_backend_status").then((status) => {
                    if (!cancelled && !status.installed) {
                        setCudaBytes(status.download_bytes);
                        setCudaDialog(true);
                    }
                }).catch((error) => { if (!cancelled) toast.error(`CUDAの導入状態を確認できませんでした: ${error}`); });
            }
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
    const saveBackend = async (backend: string) => {
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

    const handleBackendChange = async (backend: string) => {
        if ((backend === value.backend && backend !== "cuda") || cudaInstalling) return;
        try {
            if (backend === "cuda") {
                const status = await invoke<{ installed: boolean; download_bytes: number }>("cuda_backend_status");
                if (!status.installed) {
                    setCudaBytes(status.download_bytes);
                    setCudaDialog(true);
                    return;
                }
            }
            await saveBackend(backend);
        } catch (error) { toast.error(`バックエンドを変更できませんでした: ${error}`); }
    };
    const installCuda = async () => {
        setCudaInstalling(true);
        setCudaProgress(0);
        let unlisten: (() => void) | undefined;
        try {
            unlisten = await listen<{ received: number; total: number }>("cuda-download-progress", ({ payload }) => {
                setCudaProgress(Math.min(100, Math.round(payload.received / payload.total * 100)));
            });
            await invoke("install_cuda_backend", { confirmed: true });
            await saveBackend("cuda");
            toast.success("CUDAバックエンドを導入しました");
        } catch (error) {
            toast.error(`CUDAの導入に失敗しました。バックエンド設定は維持されます: ${error}`);
        } finally {
            unlisten?.();
            setCudaInstalling(false);
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
            <AlertDialog open={cudaDialog} onOpenChange={setCudaDialog}>
                <AlertDialogContent>
                    <AlertDialogHeader>
                        <AlertDialogTitle>CUDAバックエンドをダウンロードしますか？</AlertDialogTitle>
                        <AlertDialogDescription>
                            NVIDIA GPU用のバックエンドとCUDAランタイム（約{Math.ceil(cudaBytes / 1_000_000)} MB）をインターネットから取得します。
                            NVIDIAドライバーが必要です。ダウンロード完了後にCUDAを有効にします。
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogCancel>キャンセル</AlertDialogCancel>
                        <AlertDialogAction onClick={() => void installCuda()}>ダウンロードして使う</AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
            {cudaInstalling && <p className="settings-description p-4" role="status" aria-live="polite">
                {cudaProgress < 100 ? `CUDAをダウンロード中… ${cudaProgress}%` : "CUDAを検証・展開しています…"}
            </p>}
            {loadState !== "ready" ? <div className="p-4 space-y-2" role="status">
                <p className={loadState === "error" ? "text-sm text-destructive" : "settings-description"}>
                    {loadState === "error" ? "ZeroCon AIの設定を読み込めませんでした。" : "設定を読み込んでいます…"}
                </p>
                {loadState === "error" && <Button variant="outline" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>再試行</Button>}
            </div> : <>
                <SettingsRow label="利用モデル" description="漢字変換と先読みで使うモデルを選択します。タイポ補正には独自モデル corecctor（ZeroCon Model Corrector）を使います。" htmlFor="zenzai-model">
                    <Select value={activeModelId} disabled={isSwitchingModel || models.length === 0} onValueChange={(modelId) => void handleModelChange(modelId)}>
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

                <SettingsRow label="バックエンド" description="漢字変換とZeroCon先読みで共用します。既定はVulkanです。" htmlFor="zenzai-backend">
                    <Select value={value.backend} disabled={capabilityError || cudaInstalling} onValueChange={(backend) => void handleBackendChange(backend)}>
                        <SelectTrigger id="zenzai-backend"><SelectValue placeholder="バックエンドを選択" /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="cpu" disabled={!capability.cpu}>CPU (非推奨){!capability.cpu && " — AVX 対応 CPU が必要"}</SelectItem>
                            <SelectItem value="cuda" disabled={!capability.cuda}>CUDA (NVIDIA GPU){!capability.cuda && " — NVIDIAドライバーが必要"}</SelectItem>
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

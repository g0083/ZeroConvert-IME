import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";

export type TranslationModelStatus = {
    installed: boolean;
    filename: string;
    path: string | null;
    download_url: string;
    size_bytes: number | null;
    sha256: string;
};

export const getTranslationModelStatus = (modelId?: string) =>
    invoke<TranslationModelStatus>("translation_model_status", { modelId });

export const downloadTranslationModel = async (
    modelId: string,
    onProgress?: (percent: number) => void,
): Promise<TranslationModelStatus> => {
    const unlisten = await listen<{ received: number; total: number }>(
        "translation-model-download-progress",
        ({ payload }) => {
            if (payload.total > 0) {
                onProgress?.(Math.min(100, Math.round((payload.received / payload.total) * 100)));
            }
        },
    );
    try {
        return await invoke<TranslationModelStatus>("translation_model_download", { modelId });
    } finally {
        unlisten();
    }
};

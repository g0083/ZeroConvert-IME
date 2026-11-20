import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";

export type ConversionModelStatus = {
    installed: boolean;
    filename: string;
    path: string | null;
    download_url: string;
    size_bytes: number | null;
    sha256: string;
};

export const getConversionModelStatus = (modelId: string) =>
    invoke<ConversionModelStatus>("conversion_model_status", { modelId });

/**
 * Downloads a converter model on demand (not bundled with the installer) and
 * verifies size + SHA-256. Progress events are shared by every model download.
 */
export const downloadConversionModel = async (
    modelId: string,
    onProgress?: (percent: number) => void,
): Promise<ConversionModelStatus> => {
    const unlisten = await listen<{ received: number; total: number }>(
        "conversion-model-download-progress",
        ({ payload }) => {
            if (payload.total > 0) {
                onProgress?.(Math.min(100, Math.round((payload.received / payload.total) * 100)));
            }
        },
    );
    try {
        return await invoke<ConversionModelStatus>("conversion_model_download", { modelId });
    } finally {
        unlisten();
    }
};

export const cancelConversionModelDownload = () =>
    invoke<void>("conversion_model_cancel");

export const deleteConversionModel = (modelId: string) =>
    invoke<ConversionModelStatus>("conversion_model_delete", { modelId });

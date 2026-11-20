import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";

export type EnglishDictionaryStatus = {
    installed: boolean;
    path: string | null;
    words: number;
    download_url: string;
    size_bytes: number;
    sha256: string;
    license: string;
    attribution: string;
};

export const getEnglishDictionaryStatus = () =>
    invoke<EnglishDictionaryStatus>("english_dictionary_status");

/**
 * Downloads the pinned upstream word list and filters it on this machine.
 * The raw frequency data stays CC BY-SA 4.0; nothing is bundled in the installer.
 */
export const downloadEnglishDictionary = async (
    onProgress?: (percent: number) => void,
): Promise<EnglishDictionaryStatus> => {
    const unlisten = await listen<{ received: number; total: number }>(
        "english-dictionary-download-progress",
        ({ payload }) => {
            if (payload.total > 0) {
                onProgress?.(Math.min(100, Math.round((payload.received / payload.total) * 100)));
            }
        },
    );
    try {
        return await invoke<EnglishDictionaryStatus>("english_dictionary_download");
    } finally {
        unlisten();
    }
};

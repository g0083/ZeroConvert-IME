import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import type { ConversionModelStatus } from "@/lib/conversion-model";
import type { EnglishDictionaryStatus } from "@/lib/english-dictionary";

/** 日本語辞書一式（azooKey標準辞書＋追加辞書）。インストーラーには同梱しない。 */
export type DictionaryStatus = {
    installed: boolean;
    path: string | null;
    download_url: string;
    size_bytes: number;
    files: number;
    sha256: string;
};

export type SetupAssetsStatus = {
    model: ConversionModelStatus;
    dictionary: DictionaryStatus;
    english: EnglishDictionaryStatus;
};

export type SetupAssetId = "model" | "dictionary" | "english";

/** 初回起動の準備ダイアログが一度に問い合わせる3点セットの状態。 */
export const getSetupAssetsStatus = () =>
    invoke<SetupAssetsStatus>("setup_assets_status");

export const getDictionaryStatus = () =>
    invoke<DictionaryStatus>("dictionary_status");

/**
 * 辞書zipを取得し、サイズ＋SHA-256を検証してから展開する。
 * 進捗はダウンロード（bytes）と展開（ファイル数）の両方で通知される。
 */
export const downloadDictionary = async (
    onProgress?: (percent: number) => void,
): Promise<DictionaryStatus> => {
    const unlisten = await listen<{ received: number; total: number; phase: string }>(
        "dictionary-download-progress",
        ({ payload }) => {
            if (payload.total > 0) {
                onProgress?.(Math.min(100, Math.round((payload.received / payload.total) * 100)));
            }
        },
    );
    try {
        return await invoke<DictionaryStatus>("dictionary_download");
    } finally {
        unlisten();
    }
};

export const cancelDictionaryDownload = () => invoke<void>("dictionary_cancel");

/** 取得した辞書を変換サーバーに読み直させる。 */
export const reloadDictionaryInServer = () => invoke<void>("restart_server");

import { invoke } from "@tauri-apps/api/core";

import type { DictionaryExportFormat } from "@/lib/dictionary-file.js";

export type UserDictionaryFile = { path: string; contents: string };

/** 保存ダイアログを開き、内容を書き出す。取り消した場合は null。 */
export const exportUserDictionaryFile = (
    contents: string,
    defaultFileName: string,
    format: DictionaryExportFormat,
) =>
    invoke<string | null>("user_dictionary_export_file", {
        request: { contents, defaultFileName, format },
    });

/** 読み込みダイアログを開き、選んだファイルの内容を返す。取り消した場合は null。 */
export const importUserDictionaryFile = () =>
    invoke<UserDictionaryFile | null>("user_dictionary_import_file");

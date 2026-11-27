import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { Copy, Download, Plus, Save, Search, Trash2, Upload } from "lucide-react";

import { UnsavedChangesGuard } from "@/components/unsaved-changes-guard";
import {
    AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
    AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
    AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";
import {
    dictionaryFileName,
    exportableDictionaryEntries,
    mergeDictionaryEntries,
    parseDictionaryFile,
    replaceDictionaryEntries,
    serializeDictionaryJson,
    serializeDictionaryTsv,
    type DictionaryExportFormat,
    type ParsedDictionaryFile,
} from "@/lib/dictionary-file.js";
import {
    DICTIONARY_POS,
    MAX_ENTRIES,
    normalizeDictionaryEntries,
    validateDictionaryEntries,
    isDictionaryDirty,
    type DictionaryEntry,
} from "@/lib/dictionary-entries.js";
import { exportUserDictionaryFile, importUserDictionaryFile } from "@/lib/user-dictionary-file";

const PAGE_SIZE = 100;
const EXPORT_FORMAT_DESCRIPTIONS: Record<DictionaryExportFormat, string> = {
    json: "Windows と Android の間で品詞をそのまま引き継げます。",
    tsv: "Google 日本語入力 / Mozc の品詞名で書き出します。サ変名詞は「名詞サ変」になります。",
    msime: "Microsoft IME の品詞名で書き出します。組織は「固有名詞」、記号・顔文字は「顔文字」になります。",
};

type ImportPreview = {
    error: string | null;
    parsed: ParsedDictionaryFile | null;
    mode: "merge" | "replace";
    resulting: DictionaryEntry[];
    added: number;
    duplicates: number;
    overflow: number;
};

export const Dictionary = () => {
    const [entries, setEntries] = useState<DictionaryEntry[]>([]);
    const [persisted, setPersisted] = useState<DictionaryEntry[] | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [loadFailed, setLoadFailed] = useState(false);
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [isSaving, setIsSaving] = useState(false);
    const savingRef = useRef(false);
    const [validationAttempted, setValidationAttempted] = useState(false);
    const [searchQuery, setSearchQuery] = useState("");
    const [page, setPage] = useState(0);
    const [importOpen, setImportOpen] = useState(false);
    const [importSource, setImportSource] = useState<{ name: string; text: string } | null>(null);
    const [importMode, setImportMode] = useState<"merge" | "replace">("merge");
    const [isPickingFile, setIsPickingFile] = useState(false);
    const [exportOpen, setExportOpen] = useState(false);
    const [exportFormat, setExportFormat] = useState<DictionaryExportFormat>("json");
    const [isExporting, setIsExporting] = useState(false);
    const [pendingFocus, setPendingFocus] = useState<{
        index: number;
        field: keyof DictionaryEntry;
        end?: boolean;
    } | null>(null);
    const listRef = useRef<HTMLDivElement>(null);
    const inputRefs = useRef<Array<Partial<Record<keyof DictionaryEntry, HTMLInputElement | HTMLSelectElement | null>>>>([]);
    const savedView = useRef<{
        element: HTMLElement | null;
        selectionStart: number | null;
        selectionEnd: number | null;
        top: number;
        left: number;
    } | null>(null);
    const validation = useMemo(() => validateDictionaryEntries(entries), [entries]);
    const dirty = useMemo(() => isDictionaryDirty(entries, persisted), [entries, persisted]);
    const disabled = isLoading || loadFailed || persisted === null || isSaving;
    const filteredRows = useMemo(() => {
        const query = searchQuery.trim().toLowerCase();
        const indexed = entries.map((entry, index) => ({ entry, index }));
        if (!query) return indexed;
        return indexed.filter(({ entry }) =>
            entry.reading.toLowerCase().includes(query) || entry.word.toLowerCase().includes(query)
        );
    }, [entries, searchQuery]);
    const pageCount = Math.max(1, Math.ceil(filteredRows.length / PAGE_SIZE));
    const visiblePage = Math.min(page, pageCount - 1);
    const visibleRows = filteredRows.slice(visiblePage * PAGE_SIZE, (visiblePage + 1) * PAGE_SIZE);
    // 書き出しは保存時と同じく前後の空白を除いた行を使い、未入力の行は含めない。
    const exportRows = useMemo(() => exportableDictionaryEntries(validation.entries, exportFormat), [validation, exportFormat]);
    const importPreview = useMemo<ImportPreview | null>(() => {
        if (!importSource) return null;
        try {
            const parsed = parseDictionaryFile(importSource.text);
            if (importMode === "replace") {
                const replaced = replaceDictionaryEntries(parsed.entries);
                return {
                    error: null, parsed, mode: "replace",
                    resulting: replaced.entries,
                    added: replaced.entries.length,
                    duplicates: replaced.duplicates,
                    overflow: replaced.overflow,
                };
            }
            const merged = mergeDictionaryEntries(entries, parsed.entries);
            return {
                error: null, parsed, mode: "merge",
                resulting: merged.entries,
                added: merged.added,
                duplicates: merged.duplicates,
                overflow: merged.overflow,
            };
        } catch (error) {
            return {
                error: error instanceof Error ? error.message : "読み込めませんでした。",
                parsed: null, mode: importMode,
                resulting: [], added: 0, duplicates: 0, overflow: 0,
            };
        }
    }, [importSource, importMode, entries]);

    const showPage = (index: number) => {
        setPage(index);
        if (listRef.current) listRef.current.scrollTop = 0;
    };

    useEffect(() => {
        let active = true;
        setIsLoading(true);
        setLoadFailed(false);
        getConfigAfterPendingUpdates()
            .then((data) => {
                if (!active) return;
                const loaded = normalizeDictionaryEntries(data.user_dictionary?.entries);
                setEntries(loaded);
                setPersisted(loaded);
            })
            .catch(() => {
                if (active) setLoadFailed(true);
            })
            .finally(() => {
                if (active) setIsLoading(false);
            });
        return () => { active = false; };
    }, [loadAttempt]);

    useLayoutEffect(() => {
        if (!pendingFocus) return;
        const input = inputRefs.current[pendingFocus.index]?.[pendingFocus.field];
        const list = listRef.current;
        if (input && list) {
            input.focus({ preventScroll: true });
            if (pendingFocus.end) {
                list.scrollTop = list.scrollHeight;
            } else {
                const bounds = input.getBoundingClientRect();
                const viewport = list.getBoundingClientRect();
                const headerHeight = list.querySelector("thead")?.getBoundingClientRect().height ?? 0;
                if (bounds.top < viewport.top + headerHeight) {
                    list.scrollTop += bounds.top - viewport.top - headerHeight;
                } else if (bounds.bottom > viewport.bottom) {
                    list.scrollTop += bounds.bottom - viewport.bottom;
                }
            }
        }
        setPendingFocus(null);
    }, [pendingFocus]);

    useLayoutEffect(() => {
        if (isSaving || !savedView.current) return;
        const view = savedView.current;
        savedView.current = null;
        if (view.element?.isConnected &&
            (document.activeElement === document.body || document.activeElement === view.element)) {
            view.element.focus({ preventScroll: true });
            if (view.element instanceof HTMLInputElement) {
                view.element.setSelectionRange(view.selectionStart, view.selectionEnd);
            }
        }
        if (listRef.current) {
            listRef.current.scrollTop = view.top;
            listRef.current.scrollLeft = view.left;
        }
    }, [isSaving]);

    const setEntryValue = (index: number, key: keyof DictionaryEntry, value: string) => {
        if (disabled || savingRef.current) return;
        setEntries((prev) => prev.map((entry, row) => row === index ? { ...entry, [key]: value } : entry));
    };

    const addEntry = () => {
        if (disabled || savingRef.current || entries.length >= MAX_ENTRIES) return;
        setSearchQuery("");
        setEntries((prev) => [...prev, { reading: "", word: "", pos: "common_noun" }]);
        showPage(Math.floor(entries.length / PAGE_SIZE));
        setPendingFocus({ index: entries.length, field: "reading", end: true });
    };

    const copyAsTsv = async () => {
        const lines = entries
            .filter((entry) => entry.reading.trim() || entry.word.trim())
            .map((entry) => `${entry.reading}\t${entry.word}\t${entry.pos}`);
        if (lines.length === 0) {
            toast("コピーできる登録語がありません");
            return;
        }
        try {
            await navigator.clipboard.writeText(lines.join("\n"));
            toast(`${lines.length}件をTSV形式でコピーしました`);
        } catch {
            toast("クリップボードへのコピーに失敗しました");
        }
    };

    const closeImportDialog = (open: boolean) => {
        setImportOpen(open);
        if (!open) {
            setImportSource(null);
            setImportMode("merge");
        }
    };

    const pickImportFile = async () => {
        setIsPickingFile(true);
        try {
            const file = await importUserDictionaryFile();
            if (file) setImportSource({ name: file.path, text: file.contents });
        } catch (error) {
            toast("ファイルを開けませんでした", { description: String(error) });
        } finally {
            setIsPickingFile(false);
        }
    };

    const applyImport = () => {
        if (!importPreview || importPreview.error || importPreview.added === 0) return;
        setEntries(importPreview.resulting);
        setSearchQuery("");
        showPage(importPreview.mode === "replace" ? 0 : Math.floor(entries.length / PAGE_SIZE));
        const message = importPreview.mode === "replace"
            ? `${importPreview.resulting.length}件で置き換えました（「保存」で反映されます）`
            : `${importPreview.added}件を追加しました（「保存」で反映されます）`;
        closeImportDialog(false);
        toast(message);
    };

    const exportDictionary = async () => {
        if (exportRows.length === 0) return;
        setIsExporting(true);
        try {
            const contents = exportFormat === "json"
                ? serializeDictionaryJson(exportRows)
                : serializeDictionaryTsv(exportRows, exportFormat);
            const path = await exportUserDictionaryFile(contents, dictionaryFileName(exportFormat), exportFormat);
            if (!path) return;
            setExportOpen(false);
            toast(`${exportRows.length}件を書き出しました`, { description: path, duration: 8000 });
        } catch (error) {
            toast("書き出しに失敗しました", { description: String(error) });
        } finally {
            setIsExporting(false);
        }
    };

    const describeImport = (preview: ImportPreview) => {
        const read = preview.parsed?.entries.length ?? 0;
        const skipped = preview.parsed?.skipped ?? 0;
        const parts = [`読み取り ${read}件`];
        parts.push(preview.mode === "replace" ? `置き換え後 ${preview.resulting.length}件` : `追加 ${preview.added}件`);
        if (preview.duplicates > 0) parts.push(`重複 ${preview.duplicates}件を除外`);
        if (preview.overflow > 0) parts.push(`上限超過 ${preview.overflow}件`);
        if (skipped > 0) parts.push(`使えない行 ${skipped}件`);
        if (preview.parsed?.mapped) parts.push(`未対応の品詞 ${preview.parsed.mapped}件は一般名詞として登録`);
        return parts.join(" / ");
    };

    const removeEntry = (index: number) => {
        if (disabled || savingRef.current) return;
        setEntries((prev) => prev.filter((_, rowIndex) => rowIndex !== index));
    };

    const saveEntries = async () => {
        if (disabled || savingRef.current) return;
        setValidationAttempted(true);
        const invalidIndex = validation.errors.findIndex((error) => error.reading || error.word || error.pos);
        if (invalidIndex !== -1) {
            setSearchQuery("");
            showPage(Math.floor(invalidIndex / PAGE_SIZE));
            setPendingFocus({
                index: invalidIndex,
                field: validation.errors[invalidIndex].reading ? "reading" : validation.errors[invalidIndex].word ? "word" : "pos",
            });
            return;
        }

        savingRef.current = true;
        const element = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        savedView.current = {
            element,
            selectionStart: element instanceof HTMLInputElement ? element.selectionStart : null,
            selectionEnd: element instanceof HTMLInputElement ? element.selectionEnd : null,
            top: listRef.current?.scrollTop ?? 0,
            left: listRef.current?.scrollLeft ?? 0,
        };
        setIsSaving(true);
        try {
            const config = await saveConfigWithToast((config) => {
                config.user_dictionary = config.user_dictionary ?? {};
                config.user_dictionary.entries = validation.entries;
            }, "ユーザ辞書の保存に失敗しました");
            if (!config) return;
            const saved = normalizeDictionaryEntries(config.user_dictionary?.entries);
            setEntries(saved);
            setPersisted(saved);
            setValidationAttempted(false);
            toast("ユーザ辞書を保存しました");
        } catch (_error) {
            toast("ユーザ辞書の保存に失敗しました");
        } finally {
            savingRef.current = false;
            setIsSaving(false);
        }
    };

    return (
        <div className="h-full min-h-0 flex flex-col">
            <UnsavedChangesGuard dirty={dirty} />
            <div className="mb-2 flex shrink-0 items-center gap-1.5">
                <div className="relative min-w-0 flex-1">
                    <Search className="pointer-events-none absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                    <Input
                        value={searchQuery}
                        onChange={(event) => { setSearchQuery(event.target.value); showPage(0); }}
                        placeholder="読み・単語を検索…"
                        aria-label="辞書を検索"
                        className="h-8 pl-8 text-xs"
                        disabled={isLoading || loadFailed}
                    />
                </div>
                <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    disabled={disabled || entries.length === 0}
                    onClick={() => void copyAsTsv()}
                    aria-label="TSV形式でクリップボードにコピー"
                    title="TSV形式でクリップボードにコピー"
                >
                    <Copy className="h-3.5 w-3.5" />
                </Button>
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-8 shrink-0 px-2 text-xs"
                    disabled={disabled || entries.length === 0}
                    onClick={() => setExportOpen(true)}
                    title="ユーザー辞書をファイルに書き出す"
                >
                    <Download className="h-3.5 w-3.5" />
                    書き出し
                </Button>
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-8 shrink-0 px-2 text-xs"
                    disabled={disabled}
                    onClick={() => setImportOpen(true)}
                    title="ユーザー辞書ファイルを読み込む"
                >
                    <Upload className="h-3.5 w-3.5" />
                    読み込み
                </Button>
            </div>
            <div ref={listRef} className="min-h-0 flex-1 overflow-auto rounded-md border">
                {isLoading ? (
                    <p className="settings-description p-4" role="status">読み込み中…</p>
                ) : loadFailed ? (
                    <div className="space-y-3 p-4">
                        <p className="text-sm text-destructive" role="alert">辞書設定の読み込みに失敗しました。</p>
                        <Button variant="secondary" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>再試行</Button>
                    </div>
                ) : entries.length === 0 ? (
                    <p className="settings-description p-4">登録がありません。「行を追加」から登録できます。</p>
                ) : filteredRows.length === 0 ? (
                    <p className="settings-description p-4">「{searchQuery}」に一致する登録はありません。</p>
                ) : (
                    <table className="w-full table-fixed text-sm">
                        <colgroup>
                            <col />
                            <col />
                            <col className="w-44" />
                            <col className="w-14" />
                        </colgroup>
                        <thead className="sticky top-0 z-10 bg-background text-left text-xs text-muted-foreground">
                            <tr>
                                <th scope="col" className="px-3 py-2 font-medium">読み</th>
                                <th scope="col" className="px-3 py-2 font-medium">単語</th>
                                <th scope="col" className="px-3 py-2 font-medium">品詞</th>
                                <th scope="col" className="px-2 py-2 text-center font-medium">削除</th>
                            </tr>
                        </thead>
                        <tbody>
                            {visibleRows.map(({ entry, index }) => (
                                <tr key={`row-${index}`} className="border-t">
                                    {(["reading", "word"] as const).map((field) => {
                                        const error = validationAttempted ? validation.errors[index][field] : undefined;
                                        const errorId = `dictionary-${index}-${field}-error`;
                                        const label = field === "reading" ? "読み" : "単語";
                                        return (
                                            <td key={field} className="px-3 py-2 align-top">
                                                <Input
                                                    ref={(element) => {
                                                        inputRefs.current[index] ??= {};
                                                        inputRefs.current[index][field] = element;
                                                    }}
                                                    value={entry[field]}
                                                    placeholder={field === "reading" ? "よみ" : "単語"}
                                                    aria-label={`${index + 1} 行目の${label}`}
                                                    aria-invalid={Boolean(error)}
                                                    aria-describedby={error ? errorId : undefined}
                                                    disabled={disabled}
                                                    onChange={(event) => setEntryValue(index, field, event.target.value)}
                                                />
                                                {error && <p id={errorId} className="mt-1 text-xs text-destructive">{error}</p>}
                                            </td>
                                        );
                                    })}
                                    <td className="px-3 py-2 align-top">
                                        <select
                                            ref={(element) => {
                                                inputRefs.current[index] ??= {};
                                                inputRefs.current[index].pos = element;
                                            }}
                                            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                                            aria-label={`${index + 1} 行目の品詞`}
                                            aria-invalid={validationAttempted && Boolean(validation.errors[index].pos)}
                                            value={entry.pos}
                                            disabled={disabled}
                                            onChange={(event) => setEntryValue(index, "pos", event.target.value)}
                                        >
                                            {Object.entries(DICTIONARY_POS).map(([key, label]) => (
                                                <option key={key} value={key}>{label}</option>
                                            ))}
                                        </select>
                                        {validationAttempted && validation.errors[index].pos && (
                                            <p className="mt-1 text-xs text-destructive">{validation.errors[index].pos}</p>
                                        )}
                                    </td>
                                    <td className="px-2 py-2 text-center align-top">
                                        <Button
                                            variant="ghost"
                                            size="icon"
                                            onClick={() => removeEntry(index)}
                                            disabled={disabled}
                                            aria-label={`${index + 1} 行目を削除`}
                                        >
                                            <Trash2 className="h-4 w-4" />
                                        </Button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
            <div className="settings-actions flex shrink-0 items-center gap-3 pt-3">
                <span className="text-sm tabular-nums text-muted-foreground">{entries.length.toLocaleString()} / {MAX_ENTRIES.toLocaleString()}</span>
                <span className="settings-description" role="status">{isSaving ? "保存中" : dirty ? "未保存の変更" : ""}</span>
                {pageCount > 1 && (
                    <nav aria-label="辞書のページ" className="flex items-center gap-2">
                        <Button variant="outline" size="sm" disabled={disabled || visiblePage === 0}
                            onClick={() => showPage(visiblePage - 1)}>前へ</Button>
                        <span className="text-xs tabular-nums">{visiblePage + 1} / {pageCount}</span>
                        <Button variant="outline" size="sm" disabled={disabled || visiblePage + 1 >= pageCount}
                            onClick={() => showPage(visiblePage + 1)}>次へ</Button>
                    </nav>
                )}
                <div className="ml-auto flex gap-2">
                    <Button variant="secondary" onClick={addEntry} disabled={disabled || entries.length >= MAX_ENTRIES}>
                        <Plus className="h-4 w-4" />
                        行を追加
                    </Button>
                    <Button onClick={saveEntries} disabled={disabled}>
                        <Save className="h-4 w-4" />
                        保存
                    </Button>
                </div>
            </div>
            <AlertDialog open={exportOpen} onOpenChange={setExportOpen}>
                <AlertDialogContent>
                    <AlertDialogHeader>
                        <AlertDialogTitle>ユーザー辞書を書き出す</AlertDialogTitle>
                        <AlertDialogDescription>
                            {exportRows.length.toLocaleString()}件の読み・単語・品詞を書き出します。移行先の IME に合わせて形式を選んでください。
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <div className="space-y-2 py-1">
                        <label htmlFor="dictionary-export-format" className="text-xs text-muted-foreground">形式</label>
                        <select
                            id="dictionary-export-format"
                            value={exportFormat}
                            onChange={(event) => setExportFormat(event.target.value as DictionaryExportFormat)}
                            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                        >
                            <option value="json">ZeroConvert JSON（品詞を保持）</option>
                            <option value="tsv">Google 日本語入力 / Mozc（UTF-8）</option>
                            <option value="msime">Microsoft IME（UTF-16）</option>
                        </select>
                        <p className="text-xs text-muted-foreground">
                            {EXPORT_FORMAT_DESCRIPTIONS[exportFormat]}
                        </p>
                        {exportRows.length !== entries.length && (
                            <p className="text-xs text-muted-foreground">
                                この形式で表現できない行や未入力の行を {entries.length - exportRows.length} 件除外します。
                            </p>
                        )}
                    </div>
                    <AlertDialogFooter>
                        <AlertDialogCancel>キャンセル</AlertDialogCancel>
                        <Button
                            onClick={() => void exportDictionary()}
                            disabled={isExporting || exportRows.length === 0}
                        >
                            {isExporting ? "書き出し中…" : "書き出す"}
                        </Button>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
            <AlertDialog open={importOpen} onOpenChange={closeImportDialog}>
                <AlertDialogContent>
                    <AlertDialogHeader>
                        <AlertDialogTitle>ユーザー辞書を読み込む</AlertDialogTitle>
                        <AlertDialogDescription>
                            ZeroConvert JSON や、Google 日本語入力 / Mozc、Microsoft IME、ATOK のテキスト辞書から、読み・単語・品詞を取り込みます。
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <div className="space-y-3 py-1">
                        <p className="text-xs text-muted-foreground">
                            他の IME の品詞名は ZeroConvert の品詞に変換します。対応する品詞が見つからない場合は一般名詞として扱います。「保存」で辞書に反映されます。
                        </p>
                        <div className="flex flex-wrap items-center gap-2">
                            <Button
                                type="button"
                                variant="outline"
                                size="sm"
                                onClick={() => void pickImportFile()}
                                disabled={isPickingFile || isSaving}
                            >
                                <Upload className="h-3.5 w-3.5" />
                                {isPickingFile ? "選択中…" : "ファイルを選択…"}
                            </Button>
                            {importSource && (
                                <span className="text-xs break-all text-muted-foreground">{importSource.name}</span>
                            )}
                        </div>
                        <textarea
                            value={importSource?.text ?? ""}
                            onChange={(event) => {
                                const text = event.target.value;
                                setImportSource(text ? { name: "貼り付けたテキスト", text } : null);
                            }}
                            placeholder={"ぜろこん\tZeroCon\nめーるあどれす\texample@example.com"}
                            rows={4}
                            className="w-full rounded-md border bg-background p-2 font-mono text-xs outline-none focus:border-ring"
                            aria-label="読み込む内容"
                        />
                        <div className="space-y-1">
                            <label htmlFor="dictionary-import-mode" className="text-xs text-muted-foreground">取り込み方法</label>
                            <select
                                id="dictionary-import-mode"
                                value={importMode}
                                onChange={(event) => setImportMode(event.target.value === "replace" ? "replace" : "merge")}
                                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                            >
                                <option value="merge">追加する（登録済みの読み・単語はスキップ）</option>
                                <option value="replace">置き換える（現在の登録をすべて削除）</option>
                            </select>
                        </div>
                        {importPreview?.error && (
                            <p className="text-xs text-destructive" role="alert">{importPreview.error}</p>
                        )}
                        {importPreview && !importPreview.error && (
                            <p className="text-xs text-muted-foreground" role="status">{describeImport(importPreview)}</p>
                        )}
                    </div>
                    <AlertDialogFooter>
                        <AlertDialogCancel>キャンセル</AlertDialogCancel>
                        <AlertDialogAction
                            onClick={applyImport}
                            disabled={!importPreview || Boolean(importPreview.error) || importPreview.added === 0}
                        >
                            {importMode === "replace" ? "置き換える" : "追加"}
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
        </div>
    );
};

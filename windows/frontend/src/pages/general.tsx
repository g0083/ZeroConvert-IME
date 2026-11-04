import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
    AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
    AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
    AlertDialogTitle, AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { SettingsRow, SettingsSection } from "@/components/settings-section";
import { DebugSettings } from "@/components/debug-settings";
import { RomajiEditor, normalizeRomajiRows, type RomajiRow } from "@/components/romaji-editor";
import { Zenzai } from "@/pages/zenzai";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";

type WidthMode = "half" | "full";
type LearningMode = "enabled" | "read_only" | "disabled";
type ReconversionKey = "convert" | "shift_convert" | "space" | "win_slash" | "disabled";
type ClipboardConfigState = {
    trigger_key: string;
    retention_hours: number;
};
const DEFAULT_CLIPBOARD_CONFIG: ClipboardConfigState = {
    trigger_key: "ctrl_b",
    retention_hours: 1,
};
type GeneralConfigState = {
    punctuation_style: string;
    symbol_style: string;
    space_input: string;
    numpad_input: string;
    punctuation_commit: boolean;
    punctuation_commit_punctuation: boolean;
    punctuation_commit_exclamation: boolean;
    punctuation_commit_question: boolean;
    show_candidate_window_after_space: boolean;
    show_live_conversion_reading: boolean;
    live_conversion_reading_vertical_adjustment: number;
    jev_conversion: boolean;
    predictive_conversion: boolean;
    experimental_ai_assist: boolean;
    experimental_ai_engine: string;
    auto_commit_live_conversion: boolean;
    auto_commit_rewind_on_backspace: boolean;
};
type CharacterWidthGroupsState = Record<
    "alphabet" | "number" | "bracket" | "comma_period" | "middle_dot_corner_bracket" |
    "quote" | "colon_semicolon" | "hash_group" | "tilde" | "math_symbol" | "question_exclamation",
    WidthMode
>;

const DEFAULT_GENERAL_CONFIG: GeneralConfigState = {
    punctuation_style: "touten_kuten",
    symbol_style: "corner_bracket_middle_dot",
    space_input: "always_half",
    numpad_input: "direct_input",
    punctuation_commit: false,
    punctuation_commit_punctuation: true,
    punctuation_commit_exclamation: true,
    punctuation_commit_question: true,
    show_candidate_window_after_space: false,
    show_live_conversion_reading: true,
    live_conversion_reading_vertical_adjustment: 4,
    jev_conversion: false,
    predictive_conversion: false,
    experimental_ai_assist: true,
    experimental_ai_engine: "embedded",
    auto_commit_live_conversion: true,
    auto_commit_rewind_on_backspace: false,
};
const LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MIN = -12;
const LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MAX = 12;
const clampLiveConversionReadingVerticalAdjustment = (value: unknown): number => {
    const number = typeof value === "number" ? value
        : typeof value === "string" ? Number.parseInt(value, 10) : Number.NaN;
    return Number.isFinite(number)
        ? Math.min(LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MAX,
            Math.max(LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MIN, Math.round(number)))
        : DEFAULT_GENERAL_CONFIG.live_conversion_reading_vertical_adjustment;
};
const DEFAULT_WIDTH_GROUPS: CharacterWidthGroupsState = {
    alphabet: "half", number: "half", bracket: "full", comma_period: "full",
    middle_dot_corner_bracket: "full", quote: "full", colon_semicolon: "full",
    hash_group: "half", tilde: "full", math_symbol: "full", question_exclamation: "full",
};
const PUNCTUATION_OPTIONS = [
    { value: "touten_kuten", label: "、。" },
    { value: "fullwidth_comma_fullwidth_period", label: "，．" },
    { value: "touten_fullwidth_period", label: "、．" },
    { value: "fullwidth_comma_kuten", label: "，。" },
];
const SYMBOL_OPTIONS = [
    { value: "corner_bracket_middle_dot", label: "「」・" },
    { value: "square_bracket_backslash", label: "［］／" },
    { value: "corner_bracket_backslash", label: "「」／" },
    { value: "square_bracket_middle_dot", label: "［］・" },
];
const SPACE_OPTIONS = [
    { value: "always_half", label: "常に半角" },
    { value: "follow_input_mode", label: "入力モードに従う" },
];
const NUMPAD_OPTIONS = [
    { value: "direct_input", label: "直接入力" },
    { value: "always_half", label: "常に半角" },
    { value: "follow_input_mode", label: "入力モードに従う" },
];
const RECONVERSION_KEY_OPTIONS: { value: ReconversionKey; label: string }[] = [
    { value: "convert", label: "変換" },
    { value: "shift_convert", label: "Shift + 変換" },
    { value: "space", label: "Space" },
    { value: "win_slash", label: "Win + /" },
    { value: "disabled", label: "無効" },
];
const normalizeReconversionKey = (value: unknown): ReconversionKey =>
    RECONVERSION_KEY_OPTIONS.some((option) => option.value === value)
        ? value as ReconversionKey : "win_slash";
const LEARNING_MODE_OPTIONS: { value: LearningMode; label: string }[] = [
    { value: "enabled", label: "学習して履歴を使う" },
    { value: "read_only", label: "既存の履歴のみ使う" },
    { value: "disabled", label: "学習も履歴の使用もしない" },
];
const WIDTH_OPTIONS = [
    { value: "half", label: "半角" },
    { value: "full", label: "全角" },
];
const WIDTH_ROWS: { key: keyof CharacterWidthGroupsState; label: string }[] = [
    { key: "number", label: "数字" },
    { key: "bracket", label: "() {} []" },
    { key: "comma_period", label: "、。" },
    { key: "middle_dot_corner_bracket", label: "･ ｢｣" },
    { key: "quote", label: "\" '" },
    { key: "colon_semicolon", label: ": ;" },
    { key: "hash_group", label: "# % & @ $ ^ _ | ` \\" },
    { key: "tilde", label: "~" },
    { key: "math_symbol", label: "< > = + - / *" },
    { key: "question_exclamation", label: "? !" },
];
const normalizeGeneralConfig = (value?: Record<string, unknown>): GeneralConfigState => {
    const next = { ...DEFAULT_GENERAL_CONFIG };
    for (const key of Object.keys(next) as (keyof GeneralConfigState)[]) {
        if (typeof value?.[key] === typeof next[key]) {
            Object.assign(next, { [key]: value![key] });
        }
    }
    if (next.space_input === "always_full") next.space_input = "follow_input_mode";
    if (!["direct_input", "always_half", "follow_input_mode"].includes(next.numpad_input)) {
        next.numpad_input = DEFAULT_GENERAL_CONFIG.numpad_input;
    }
    next.live_conversion_reading_vertical_adjustment =
        clampLiveConversionReadingVerticalAdjustment(value?.live_conversion_reading_vertical_adjustment);
    return next;
};
const normalizeLearningMode = (value: unknown): LearningMode =>
    value === "enabled" || value === "read_only" || value === "disabled" ? value : "enabled";
const normalizeWidthGroups = (value?: Record<string, unknown>): CharacterWidthGroupsState => {
    const next = { ...DEFAULT_WIDTH_GROUPS };
    for (const key of Object.keys(next) as (keyof CharacterWidthGroupsState)[]) {
        if (value?.[key] === "half" || value?.[key] === "full") next[key] = value[key];
    }
    return next;
};

export const General = ({ page }: { page: "input" | "conversion" | "keys" | "advanced" }) => {
    const [shortcutValue, setShortcutValue] = useState({
        ctrlSpaceToggle: true, altBackquoteToggle: true, eisuToggle: false,
        reconversionKey: "win_slash" as ReconversionKey,
    });
    const [clipboardValue, setClipboardValue] = useState(DEFAULT_CLIPBOARD_CONFIG);
    const [generalValue, setGeneralValue] = useState(DEFAULT_GENERAL_CONFIG);
    const [widthGroups, setWidthGroups] = useState(DEFAULT_WIDTH_GROUPS);
    const [learningMode, setLearningMode] = useState<LearningMode>("enabled");
    const [romajiRows, setRomajiRows] = useState<RomajiRow[]>([]);
    const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
    const [loadAttempt, setLoadAttempt] = useState(0);
    const liveConversionReadingAdjustmentSaveRef = useRef<{
        saving: boolean; pendingValue: number | null;
    }>({ saving: false, pendingValue: null });

    useEffect(() => {
        let cancelled = false;
        setLoadState("loading");
        getConfigAfterPendingUpdates().then((data) => {
            if (cancelled) return;
            const shortcuts = data.shortcuts ?? {};
            setShortcutValue({
                ctrlSpaceToggle: shortcuts.ctrl_space_toggle ?? true,
                altBackquoteToggle: shortcuts.alt_backquote_toggle ?? true,
                eisuToggle: shortcuts.eisu_toggle ?? false,
                reconversionKey: normalizeReconversionKey(shortcuts.reconversion_key),
            });
            const clipboard = data.clipboard ?? {};
            setClipboardValue({
                trigger_key: clipboard.trigger_key ?? "ctrl_b",
                retention_hours: typeof clipboard.retention_hours === "number" ? clipboard.retention_hours : 1,
            });
            setGeneralValue(normalizeGeneralConfig(data.general));
            setLearningMode(normalizeLearningMode(data.learning?.mode));
            setWidthGroups(normalizeWidthGroups(data.character_width?.groups));
            setRomajiRows(normalizeRomajiRows(data.romaji_table?.rows));
            setLoadState("ready");
        }).catch(() => { if (!cancelled) setLoadState("error"); });
        return () => { cancelled = true; };
    }, [page, loadAttempt]);

    const updateGeneralConfig = async <K extends keyof GeneralConfigState>(key: K, nextValue: GeneralConfigState[K]) => {
        const data = await saveConfigWithToast((config) => {
            config.general = config.general ?? {};
            config.general[key] = nextValue;
        });
        if (data) setGeneralValue(normalizeGeneralConfig(data.general));
    };
    const updateClipboardConfig = async <K extends keyof ClipboardConfigState>(key: K, nextValue: ClipboardConfigState[K]) => {
        const data = await saveConfigWithToast((config) => {
            config.clipboard = config.clipboard ?? {};
            config.clipboard[key] = nextValue;
        });
        if (data?.clipboard) {
            setClipboardValue({
                trigger_key: data.clipboard.trigger_key ?? "ctrl_b",
                retention_hours: typeof data.clipboard.retention_hours === "number" ? data.clipboard.retention_hours : 1,
            });
        }
    };
    const updateShortcut = async (key: string, nextValue: boolean | ReconversionKey) => {
        const data = await saveConfigWithToast((config) => {
            config.shortcuts = config.shortcuts ?? {};
            config.shortcuts[key] = nextValue;
        });
        if (data) {
            setShortcutValue({
                ctrlSpaceToggle: data.shortcuts.ctrl_space_toggle,
                altBackquoteToggle: data.shortcuts.alt_backquote_toggle,
                eisuToggle: data.shortcuts.eisu_toggle,
                reconversionKey: normalizeReconversionKey(data.shortcuts.reconversion_key),
            });
        }
    };
    const updateWidthGroup = async (key: keyof CharacterWidthGroupsState, nextValue: WidthMode) => {
        const data = await saveConfigWithToast((config) => {
            config.character_width = config.character_width ?? {};
            config.character_width.groups = config.character_width.groups ?? {};
            config.character_width.groups[key] = nextValue;
        });
        if (data) setWidthGroups(normalizeWidthGroups(data.character_width?.groups));
    };
    const updateLearningMode = async (nextValue: LearningMode) => {
        const data = await saveConfigWithToast((config) => {
            config.learning = config.learning ?? {};
            config.learning.mode = nextValue;
        });
        if (data) setLearningMode(normalizeLearningMode(data.learning?.mode));
    };
    const resetLearningHistory = async () => {
        try {
            const result = await invoke<{ reset: boolean; server_applied: boolean; message?: string | null }>("reset_learning_history");
            if (result.reset && result.server_applied) toast("学習履歴を削除しました");
            else toast("学習履歴を削除できませんでした", { description: result.message ?? undefined });
        } catch (error) {
            toast("学習履歴を削除できませんでした", { description: String(error) });
        }
    };
    const flushLiveConversionReadingVerticalAdjustmentSave = async () => {
        const saveState = liveConversionReadingAdjustmentSaveRef.current;
        if (saveState.saving) return;
        saveState.saving = true;
        try {
            while (saveState.pendingValue !== null) {
                const nextValue = saveState.pendingValue;
                saveState.pendingValue = null;
                const data = await saveConfigWithToast((config) => {
                    config.general = config.general ?? {};
                    config.general.live_conversion_reading_vertical_adjustment = nextValue;
                });
                if (data && saveState.pendingValue === null) setGeneralValue(normalizeGeneralConfig(data.general));
            }
        } finally { saveState.saving = false; }
    };
    const updateLiveConversionReadingVerticalAdjustment = (nextValue: number) => {
        liveConversionReadingAdjustmentSaveRef.current.pendingValue = clampLiveConversionReadingVerticalAdjustment(nextValue);
        void flushLiveConversionReadingVerticalAdjustmentSave();
    };

    if (loadState !== "ready") return (
        <div className="settings-content" role="status">
            <p className={loadState === "error" ? "text-sm text-destructive" : "settings-description"}>
                {loadState === "error" ? "設定を読み込めませんでした。" : "設定を読み込んでいます…"}
            </p>
            {loadState === "error" && <Button variant="outline" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>再試行</Button>}
        </div>
    );

    return (
        <div className="settings-content">
            {page === "input" && <>
                <SettingsSection title="文字の入力">
                    {([
                        ["punctuation_style", "句読点", PUNCTUATION_OPTIONS],
                        ["symbol_style", "記号", SYMBOL_OPTIONS],
                        ["space_input", "スペース", SPACE_OPTIONS],
                        ["numpad_input", "テンキー", NUMPAD_OPTIONS],
                    ] as const).map(([key, label, options]) => (
                        <SettingsRow key={key} label={label} htmlFor={key}>
                            <Select value={generalValue[key]} onValueChange={(value) => void updateGeneralConfig(key, value)}>
                                <SelectTrigger id={key}><SelectValue /></SelectTrigger>
                                <SelectContent>{options.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}</SelectContent>
                            </Select>
                        </SettingsRow>
                    ))}
                </SettingsSection>
                <SettingsSection title="半角・全角">
                    {WIDTH_ROWS.map(({ key, label }) => (
                        <SettingsRow key={key} label={label} htmlFor={`width-${key}`}>
                            <Select value={widthGroups[key]} onValueChange={(value: WidthMode) => void updateWidthGroup(key, value)}>
                                <SelectTrigger id={`width-${key}`}><SelectValue /></SelectTrigger>
                                <SelectContent>{WIDTH_OPTIONS.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}</SelectContent>
                            </Select>
                        </SettingsRow>
                    ))}
                </SettingsSection>
            </>}
            {page === "conversion" && <>
                <Zenzai />
                <SettingsSection title="確定">
                    <SettingsRow label="長文の自動確定（ストリーミング確定）" description="長い文を入力中、先頭側の変換が安定した時点で自動的に確定し、続けて快適に入力できるようにします。" htmlFor="auto-commit-live-conversion">
                        <Switch id="auto-commit-live-conversion" checked={generalValue.auto_commit_live_conversion} onCheckedChange={(value) => void updateGeneralConfig("auto_commit_live_conversion", value)} />
                    </SettingsRow>
                    {generalValue.auto_commit_live_conversion && <div className="settings-subgroup">
                        <SettingsRow label="自動確定直後のBackspaceで未確定に戻す" description="自動確定された直後にBackspaceキーを押した際、確定を取り消して未確定の入力状態に戻します。" htmlFor="auto-commit-rewind-on-backspace">
                            <Switch id="auto-commit-rewind-on-backspace" checked={generalValue.auto_commit_rewind_on_backspace} onCheckedChange={(value) => void updateGeneralConfig("auto_commit_rewind_on_backspace", value)} />
                        </SettingsRow>
                    </div>}
                    <SettingsRow label="記号入力で変換を確定" htmlFor="punctuation-commit">
                        <Switch id="punctuation-commit" checked={generalValue.punctuation_commit} onCheckedChange={(value) => void updateGeneralConfig("punctuation_commit", value)} />
                    </SettingsRow>
                    {generalValue.punctuation_commit && <div className="settings-subgroup">
                        {([
                            ["punctuation_commit_punctuation", "句読点"],
                            ["punctuation_commit_exclamation", "！"],
                            ["punctuation_commit_question", "？"],
                        ] as const).map(([key, label]) => (
                            <SettingsRow key={key} label={label} htmlFor={key}>
                                <Switch id={key} checked={generalValue[key]} onCheckedChange={(value) => void updateGeneralConfig(key, value)} />
                            </SettingsRow>
                        ))}
                    </div>}
                </SettingsSection>
                <SettingsSection title="候補と読みの表示">
                    <SettingsRow label="Space / Tab まで候補を非表示" htmlFor="candidate-delay">
                        <Switch id="candidate-delay" checked={generalValue.show_candidate_window_after_space} onCheckedChange={(value) => void updateGeneralConfig("show_candidate_window_after_space", value)} />
                    </SettingsRow>
                    <SettingsRow label="予測変換" description="入力途中の読みから単語・フレーズの予測変換候補を提示します（デフォルト: オフ）" htmlFor="predictive-conversion">
                        <Switch id="predictive-conversion" checked={generalValue.predictive_conversion} onCheckedChange={(value) => void updateGeneralConfig("predictive_conversion", value)} />
                    </SettingsRow>
                    <SettingsRow label="ライブ変換中の読みを表示" htmlFor="live-reading">
                        <Switch id="live-reading" checked={generalValue.show_live_conversion_reading} onCheckedChange={(value) => void updateGeneralConfig("show_live_conversion_reading", value)} />
                    </SettingsRow>
                    {generalValue.show_live_conversion_reading && <div className="settings-subgroup">
                        <SettingsRow label="読み表示の高さ" htmlFor="reading-height">
                            <div className="w-full space-y-1">
                                <div className="flex items-center gap-2 text-xs">
                                    <span>下</span>
                                    <input id="reading-height" type="range" min={LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MIN} max={LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_MAX} step={1}
                                        value={generalValue.live_conversion_reading_vertical_adjustment} className="min-w-0 w-full accent-primary"
                                        aria-valuetext={`${generalValue.live_conversion_reading_vertical_adjustment}px`}
                                        onChange={(event) => {
                                            const nextValue = clampLiveConversionReadingVerticalAdjustment(event.currentTarget.value);
                                            setGeneralValue((current) => ({ ...current, live_conversion_reading_vertical_adjustment: nextValue }));
                                        }}
                                        onPointerUp={(event) => updateLiveConversionReadingVerticalAdjustment(event.currentTarget.valueAsNumber)}
                                        onKeyUp={(event) => {
                                            if (["ArrowDown", "ArrowLeft", "ArrowRight", "ArrowUp", "End", "Home", "PageDown", "PageUp"].includes(event.key)) {
                                                updateLiveConversionReadingVerticalAdjustment(event.currentTarget.valueAsNumber);
                                            }
                                        }}
                                        onBlur={(event) => updateLiveConversionReadingVerticalAdjustment(event.currentTarget.valueAsNumber)} />
                                    <span>上</span>
                                </div>
                                <p className="text-right text-xs tabular-nums">{generalValue.live_conversion_reading_vertical_adjustment > 0 ? "+" : ""}{generalValue.live_conversion_reading_vertical_adjustment}px</p>
                            </div>
                        </SettingsRow>
                    </div>}
                </SettingsSection>
                <SettingsSection title="変換学習">
                    <SettingsRow label="学習と履歴の使用" htmlFor="learning-mode">
                        <Select value={learningMode} onValueChange={(value: LearningMode) => void updateLearningMode(value)}>
                            <SelectTrigger id="learning-mode"><SelectValue /></SelectTrigger>
                            <SelectContent>{LEARNING_MODE_OPTIONS.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}</SelectContent>
                        </Select>
                    </SettingsRow>
                    <SettingsRow label="学習履歴">
                        <AlertDialog>
                            <AlertDialogTrigger asChild><Button variant="outline">履歴を削除</Button></AlertDialogTrigger>
                            <AlertDialogContent>
                                <AlertDialogHeader>
                                    <AlertDialogTitle>学習履歴を削除しますか？</AlertDialogTitle>
                                    <AlertDialogDescription>これまでの変換学習データを削除します。この操作は取り消せません。</AlertDialogDescription>
                                </AlertDialogHeader>
                                <AlertDialogFooter>
                                    <AlertDialogCancel>キャンセル</AlertDialogCancel>
                                    <AlertDialogAction onClick={() => void resetLearningHistory()}>削除</AlertDialogAction>
                                </AlertDialogFooter>
                            </AlertDialogContent>
                        </AlertDialog>
                    </SettingsRow>
                </SettingsSection>
            </>}
            {page === "keys" && <>
                <SettingsSection title="クリップボード">
                    <SettingsRow label="起動キー" description="画面上のどこからでもクリップボード履歴を呼び出すショートカットキーを設定します。" htmlFor="clipboard-trigger-key">
                        <Select value={clipboardValue.trigger_key} onValueChange={(value) => void updateClipboardConfig("trigger_key", value)}>
                            <SelectTrigger id="clipboard-trigger-key"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="ctrl_b">Ctrl + B (デフォルト)</SelectItem>
                                <SelectItem value="ctrl_shift_v">Ctrl + Shift + V</SelectItem>
                                <SelectItem value="alt_v">Alt + V</SelectItem>
                                <SelectItem value="ctrl_q">Ctrl + Q</SelectItem>
                                <SelectItem value="disabled">無効</SelectItem>
                            </SelectContent>
                        </Select>
                    </SettingsRow>
                    <SettingsRow label="履歴の保持時間" description="未固定のクリップボード履歴を保持する期間です。ピン留めした項目は常に保持されます。" htmlFor="clipboard-retention-hours">
                        <Select value={String(clipboardValue.retention_hours)} onValueChange={(value) => void updateClipboardConfig("retention_hours", Number(value))}>
                            <SelectTrigger id="clipboard-retention-hours"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="1">1時間 (デフォルト)</SelectItem>
                                <SelectItem value="3">3時間</SelectItem>
                                <SelectItem value="24">24時間</SelectItem>
                                <SelectItem value="0">無制限 (消去しない)</SelectItem>
                            </SelectContent>
                        </Select>
                    </SettingsRow>
                </SettingsSection>
                <SettingsSection title="英数・かな切り替え">
                    {([
                        ["ctrl_space_toggle", "ctrlSpaceToggle", "Ctrl + Space"],
                        ["alt_backquote_toggle", "altBackquoteToggle", "Alt + `"],
                        ["eisu_toggle", "eisuToggle", "英数 (CapsLock)"],
                    ] as const).map(([key, stateKey, label]) => (
                        <SettingsRow key={key} label={label} htmlFor={key}>
                            <Switch id={key} checked={shortcutValue[stateKey]} onCheckedChange={(value) => void updateShortcut(key, value)} />
                        </SettingsRow>
                    ))}
                </SettingsSection>
                <SettingsSection title="再変換">
                    <SettingsRow label="再変換キー" description="選択した文章はSpaceでも再変換できます。「無効」の場合はSpaceによる再変換も停止します。" htmlFor="reconversion-key">
                        <Select value={shortcutValue.reconversionKey} onValueChange={(value: ReconversionKey) => void updateShortcut("reconversion_key", value)}>
                            <SelectTrigger id="reconversion-key"><SelectValue /></SelectTrigger>
                            <SelectContent>{RECONVERSION_KEY_OPTIONS.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}</SelectContent>
                        </Select>
                    </SettingsRow>
                </SettingsSection>
                <SettingsSection title="ローマ字入力">
                    <SettingsRow label="ローマ字テーブル" description={`${romajiRows.length} 件`}>
                        <RomajiEditor rows={romajiRows} onSaved={setRomajiRows} />
                    </SettingsRow>
                </SettingsSection>
            </>}
            {page === "advanced" && <>
                <SettingsSection title="タイポ補正と先読み">
                    <SettingsRow label="タイポ補正モデル" description="Nano v4 メインモデルに統合。読み候補の尤度判定・表層リランキング・漢字変換を一気通貫で高速処理します。">
                        <span className="text-sm font-medium">ZeroCon Model Nano v4（メインモデル統合）</span>
                    </SettingsRow>
                    <SettingsRow label="タイポ補正・先読みを使う" description="Nano v4 メインモデルによる統合タイポ補正と続きの先読みを有効にします。補正・先読み候補はTabキーで採用できます。" htmlFor="ai-assist">
                        <Switch id="ai-assist" checked={generalValue.experimental_ai_assist} onCheckedChange={(value) => void updateGeneralConfig("experimental_ai_assist", value)} />
                    </SettingsRow>
                    {generalValue.experimental_ai_assist && (
                        <>
                            <SettingsRow label="タイポ補正の実行方法" description="通常は内蔵推論エンジンが自動処理します。LM Studioは開発者向けの接続先です。" htmlFor="ai-engine">
                                <Select value={generalValue.experimental_ai_engine || "embedded"} onValueChange={(value) => void updateGeneralConfig("experimental_ai_engine", value)}>
                                    <SelectTrigger id="ai-engine" className="w-64"><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="embedded">内蔵推論エンジン (推奨・メインモデル統合)</SelectItem>
                                        <SelectItem value="lm_studio">LM Studio (検証・デバッグ用)</SelectItem>
                                    </SelectContent>
                                </Select>
                            </SettingsRow>
                            <SettingsRow label="先読みモデル" description="漢字変換と同じバックエンドを使用します（既定: Vulkan）。バックエンドは漢字変換の設定から変更できます。">
                                <span className="text-sm">ZeroCon Model</span>
                            </SettingsRow>
                        </>
                    )}
                </SettingsSection>
                <DebugSettings />
            </>}
        </div>
    );
};

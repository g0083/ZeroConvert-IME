import { useState, useMemo, useRef, useEffect } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { getConfigAfterPendingUpdates } from "@/lib/config";
import { BACKEND_LABELS, useQuickBenchmarkActivity, type QuickBackendId, type QuickBenchmarkProgress } from "@/lib/quick-benchmark";
import { useBlocker } from "react-router";
import { toast } from "sonner";
import rawBenchmarkData from "@/data/benchmark840.json";
import rawTypoData from "@/data/typo_benchmark450.json";
import rawPredictiveData from "@/data/predictive_testset.json";
import type { LucideIcon } from "lucide-react";
import { 
    Play, 
    Square, 
    RotateCcw, 
    Zap, 
    CheckCircle2, 
    XCircle, 
    Clock, 
    Search, 
    SlidersHorizontal,
    Layers,
    Check,
    AlertTriangle,
    ShieldCheck,
    X
} from "lucide-react";
import {
    AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
    AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog";

function BenchmarkDepartureGuard({ running, quick }: { running: boolean; quick: boolean }) {
    const blocker = useBlocker(running);
    return (
        <AlertDialog open={blocker.state === "blocked"}>
            <AlertDialogContent
                onEscapeKeyDown={(event) => {
                    event.preventDefault();
                    if (blocker.state === "blocked") blocker.reset();
                }}
            >
                <AlertDialogHeader>
                    <AlertDialogTitle>{quick ? "計測中に画面を移動しますか？" : "ベンチマークを中断しますか？"}</AlertDialogTitle>
                    <AlertDialogDescription>
                        {quick ? "計測は完了まで続きます。移動するとこの画面の結果表示は破棄されます。" : "実行中の進捗と結果は破棄されます。続行するにはキャンセルしてください。"}
                    </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                    <AlertDialogCancel onClick={() => {
                        if (blocker.state === "blocked") blocker.reset();
                    }}>
                        実行を続ける
                    </AlertDialogCancel>
                    <AlertDialogAction onClick={() => {
                        if (blocker.state === "blocked") blocker.proceed();
                    }}>
                        {quick ? "移動する" : "中断して移動"}
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
}

export type BenchmarkItem = {
    id: number;
    subset: "basic" | "hard" | "realistic" | "colloquial" | "additional_dict";
    subset_label: string;
    category: string;
    left_context: string;
    target: string;
    expected: string;
    acceptable?: string[];
    forbidden_examples?: string[];
    focus?: string;
};

export type ItemResult = {
    id: number;
    top1: string;
    candidates: string[];
    is_correct: boolean;
    latency_ms: number;
    nbest_ms?: number;
};

const CONVERSION_SCOPES = [
    { id: "model", label: "モデル推論", description: "読みからモデルで直接生成" },
    { id: "candidates", label: "共通候補生成", description: "標準辞書の N-best 追加と候補の順位付けを含む" },
    { id: "quick", label: "クイック検証（84問）", description: "共通候補生成を各問3回測定し、中央値の回の結果を表示" },
] as const;
type ConversionMode = typeof CONVERSION_SCOPES[number]["id"];
type ConversionScope = Exclude<ConversionMode, "quick">;
type RunKey = ConversionMode | "typo" | "predictive";
type BenchmarkRuntime = { backend: QuickBackendId; gpu_layers: number };
type QuickConversionResult = BenchmarkRuntime & { rows: ItemResult[]; elapsed_ms: number };
const emptyConversionResults = (): Record<ConversionMode, Record<number, ItemResult>> => ({ model: {}, candidates: {}, quick: {} });

function backendText({ backend, gpu_layers }: BenchmarkRuntime) {
    if (backend === "cpu") return BACKEND_LABELS.cpu;
    return `Vulkan（${gpu_layers === 99 ? "GPU全層" : `GPU ${gpu_layers}層`}）`;
}

export type TypoBenchmarkItem = {
    id: number;
    category: string;
    typo_type: string;
    typo_type_label: string;
    is_clean: boolean;
    context: string;
    input_reading: string;
    target_reading: string;
    expected: string;
    acceptable?: string[];
    input_mode?: string;
    origin?: string;
    source_subset?: string;
};

export type TypoItemResult = {
    id: number;
    main_output: string;
    moshikashite_output?: string | null;
    correction_reading?: string | null;
    status: "exact_match" | "variant_match" | "clean_preserved" | "false_positive" | "missed";
    latency_ms: number;
};

export type PredictiveBenchmarkItem = {
    id: number;
    category: string;
    context: string;
    partial_input: string;
    expected_continuation: string;
    acceptable_continuations?: string[];
    full_sentence: string;
    origin?: string;
};

export type PredictiveItemResult = {
    id: number;
    prediction: string | null;
    is_match: boolean;
    latency_ms: number;
};

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

const BENCHMARK_ITEMS: BenchmarkItem[] = rawBenchmarkData as BenchmarkItem[];
const TYPO_ITEMS: TypoBenchmarkItem[] = rawTypoData as TypoBenchmarkItem[];
const PREDICTIVE_ITEMS: PredictiveBenchmarkItem[] = rawPredictiveData as PredictiveBenchmarkItem[];

function formatExecutionTime(ms: number): string {
    return `${(ms / 1000).toFixed(2)} 秒`;
}

/** 最近傍順位の分位数 (Rust latency_percentiles と同一)。 */
function latencyPercentiles(latencies: number[]): { p50: number; p95: number; p99: number } {
    if (latencies.length === 0) return { p50: 0, p95: 0, p99: 0 };
    const sorted = [...latencies].sort((a, b) => a - b);
    const at = (p: number) => {
        const rank = Math.ceil((p / 100) * sorted.length);
        return sorted[Math.min(Math.max(rank - 1, 0), sorted.length - 1)];
    };
    return { p50: at(50), p95: at(95), p99: at(99) };
}

// 共通ベンチマーク正本 benchmarks/common (840問)。subset_label の件数は正本 manifest と一致させる。
const SUBSETS = [
    { id: "all", label: "全840問 (230+250+160+100+100)" },
    { id: "basic", label: "基本・文脈 (230問)" },
    { id: "hard", label: "高難度文脈 (250問)" },
    { id: "realistic", label: "実IME (160問)" },
    { id: "colloquial", label: "口語 (100問)" },
    { id: "additional_dict", label: "追加辞書 (100問)" },
];

const TYPO_FILTER_TYPES = [
    { id: "all", label: "すべて (450問)" },
    { id: "typo_only", label: "タイポ誤りのみ (255問)" },
    { id: "clean_only", label: "正常入力・FP検証 (195問)" },
    { id: "derived_clean", label: "増強 clean (150問)" },
    { id: "geminate", label: "促音 っ (25問)" },
    { id: "nasal", label: "撥音 ん (25問)" },
    { id: "transposition", label: "順序入替・転置 (40問)" },
    { id: "adjacent", label: "QWERTY隣接 (60問)" },
    { id: "missing_vowel", label: "母音抜け (50問)" },
    { id: "flick", label: "フリック (40問)" },
    { id: "prolonged", label: "長音 (15問)" },
];

// --- 表示部スピ子の共通部品 タブ間の見た目を揃える。
function KpiCard({
    label,
    value,
    sub,
    icon: Icon,
    iconClassName,
    valueClassName,
}: {
    label: string;
    value: string;
    sub: string;
    icon?: LucideIcon;
    iconClassName?: string;
    valueClassName?: string;
}) {
    return (
        <div className="flex flex-col gap-1 rounded-xl border border-border/70 bg-card/60 p-4 shadow-xs">
            <div className="flex items-center justify-between gap-2">
                <span className="truncate text-[11px] font-medium text-muted-foreground">{label}</span>
                {Icon ? <Icon className={`size-3.5 shrink-0 ${iconClassName ?? "text-muted-foreground"}`} aria-hidden /> : null}
            </div>
            <div className={`truncate text-2xl font-bold tabular-nums tracking-tight text-foreground ${valueClassName ?? ""}`} title={value}>
                {value}
            </div>
            <div className="truncate text-[11px] text-muted-foreground" title={sub}>{sub}</div>
        </div>
    );
}

function FilterPill({
    active,
    onClick,
    children,
    label,
}: {
    active: boolean;
    onClick: () => void;
    children: React.ReactNode;
    label: string;
}) {
    return (
        <button
            type="button"
            aria-label={label}
            aria-pressed={active}
            onClick={onClick}
            className={`shrink-0 rounded-md px-2.5 py-1 text-xs transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 cursor-pointer ${
                active
                    ? "bg-card font-semibold text-foreground shadow-2xs"
                    : "text-muted-foreground hover:text-foreground"
            }`}
        >
            {children}
        </button>
    );
}

function SearchField({
    value,
    onChange,
    placeholder,
}: {
    value: string;
    onChange: (v: string) => void;
    placeholder: string;
}) {
    return (
        <div className="relative flex-1 sm:w-56 sm:flex-none">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <input
                type="search"
                aria-label="検索"
                placeholder={placeholder}
                value={value}
                onChange={(e) => onChange(e.target.value)}
                className="w-full rounded-lg border border-border bg-card py-1.5 pl-8 pr-8 text-xs placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-primary"
            />
            {value ? (
                <button
                    type="button"
                    aria-label="検索をクリア"
                    onClick={() => onChange("")}
                    className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring cursor-pointer"
                >
                    <X className="size-3.5" aria-hidden />
                </button>
            ) : null}
        </div>
    );
}

const SUBSET_BADGE: Record<string, string> = {
    basic: "bg-sky-500/10 border-sky-500/30 text-sky-700 dark:text-sky-300",
    hard: "bg-violet-500/10 border-violet-500/30 text-violet-700 dark:text-violet-300",
    realistic: "bg-emerald-500/10 border-emerald-500/30 text-emerald-700 dark:text-emerald-300",
    colloquial: "bg-amber-500/10 border-amber-500/30 text-amber-700 dark:text-amber-300",
    additional_dict: "bg-rose-500/10 border-rose-500/30 text-rose-700 dark:text-rose-300",
};

function SubsetBadge({ subset }: { subset: BenchmarkItem["subset"] }) {
    const label = subset === "basic" ? "基本230" : subset === "hard" ? "高難度250" : subset === "realistic" ? "実IME160" : subset === "colloquial" ? "口語100" : "追加辞書100";
    return (
        <span className={`inline-flex whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-medium ${SUBSET_BADGE[subset] ?? "border-border text-muted-foreground"}`}>
            {label}
        </span>
    );
}

const TABLE_HEAD = "border-b border-border/70 bg-muted/40 font-medium text-muted-foreground";
const TABLE_HEAD_CELL = "sticky top-0 bg-muted/40 p-3";

function EmptyRow({ colSpan, message }: { colSpan: number; message: string }) {
    return (
        <tr>
            <td colSpan={colSpan} className="p-8 text-center text-xs text-muted-foreground">
                {message}
            </td>
        </tr>
    );
}

export function Benchmark() {
    const [benchmarkTab, setBenchmarkTab] = useState<"zenzai" | "typo" | "predictive">("zenzai");

    // --- かな漢字変換 (840問) State ---
    const [conversionScope, setConversionScope] = useState<ConversionMode>("model");
    const [conversionResults, setConversionResults] = useState(emptyConversionResults);
    const results = conversionResults[conversionScope];
    const quickResults = conversionResults.quick;
    const [isRunning, setIsRunning] = useState(false);
    const [quickProgress, setQuickProgress] = useState<QuickBenchmarkProgress | null>(null);
    const sharedQuickRunning = useQuickBenchmarkActivity() !== "idle";
    const [selectedSubset, setSelectedSubset] = useState<string>("all");
    const [selectedCategory, setSelectedCategory] = useState("すべて");
    const [showErrorsOnly, setShowErrorsOnly] = useState(false);
    const [searchQuery, setSearchQuery] = useState("");

    // --- タイポ補正 (450問) State ---
    const [typoResults, setTypoResults] = useState<Record<number, TypoItemResult>>({});
    const [isTypoRunning, setIsTypoRunning] = useState(false);
    const [selectedTypoFilter, setSelectedTypoFilter] = useState("all");
    const [showTypoErrorsOnly, setShowTypoErrorsOnly] = useState(false);
    const [typoSearchQuery, setTypoSearchQuery] = useState("");

    // --- 先読み (30問) State --- Android と同一データで判定する。
    const [predictiveResults, setPredictiveResults] = useState<Record<number, PredictiveItemResult>>({});
    const [isPredictiveRunning, setIsPredictiveRunning] = useState(false);

    // --- モデル情報 ---
    const [activeModelId, setActiveModelId] = useState<string>("");
    const [models, setModels] = useState<ModelInfo[]>([]);
    const activeModel = models.find((model) => model.id === activeModelId);
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);
    const [configuredRuntime, setConfiguredRuntime] = useState<BenchmarkRuntime | null>(null);
    const [backendOverride, setBackendOverride] = useState<QuickBackendId | null>(null);
    const [capability, setCapability] = useState<Record<QuickBackendId, boolean> | null>(null);
    const benchmarkBackend = backendOverride ?? configuredRuntime?.backend;

    const stopSignalRef = useRef(false);
    const stopTypoSignalRef = useRef(false);
    const stopPredictiveSignalRef = useRef(false);

    useEffect(() => {
        return () => {
            stopSignalRef.current = true;
            stopTypoSignalRef.current = true;
            stopPredictiveSignalRef.current = true;
        };
    }, []);

    useEffect(() => {
        getConfigAfterPendingUpdates().then(({ zenzai }) => {
            setConfiguredRuntime({ backend: zenzai.backend === "cpu" ? "cpu" : "vulkan", gpu_layers: Math.min(zenzai.gpu_layers ?? 99, 99) });
        }).catch((error) => toast.error(`バックエンド設定を取得できませんでした: ${error}`));
        invoke<Record<QuickBackendId, boolean>>("check_capability").then(setCapability).catch(() => {});
    }, []);

    useEffect(() => {
        invoke<ModelListResponse>("get_active_model").then((data) => {
            setActiveModelId(data.active_id);
            setModels(data.models);
        }).catch((err) => {
            console.error("Failed to load active model in benchmark:", err);
        });
    }, []);

    const handleSwitchModel = async (newModelId: string) => {
        if (newModelId === activeModelId || isSwitchingModel || isRunning || isTypoRunning || isPredictiveRunning) return;
        setIsSwitchingModel(true);
        const toastId = toast.loading("モデル切り替え中... (サーバー再起動)");
        try {
            await invoke("switch_model", { modelId: newModelId });
            setActiveModelId(newModelId);
            setConversionResults(emptyConversionResults());
            setWallMs({});
            setRunRuntimes({});
            setTypoResults({});
            setPredictiveResults({});
            toast.success(`モデルを ${models.find((model) => model.id === newModelId)?.name ?? newModelId} に切り替えました`, { id: toastId });
        } catch (err) {
            toast.error(`モデル切り替えに失敗しました: ${err}`, { id: toastId });
        } finally {
            setIsSwitchingModel(false);
        }
    };

    // --- かな漢字変換: フィルタリング ---
    const subsetItems = useMemo(() => {
        if (conversionScope === "quick") return BENCHMARK_ITEMS.filter((item) => quickResults[item.id]);
        if (selectedSubset === "all") return BENCHMARK_ITEMS;
        return BENCHMARK_ITEMS.filter((item) => item.subset === selectedSubset);
    }, [conversionScope, quickResults, selectedSubset]);

    const availableCategories = useMemo(() => {
        return ["すべて", ...Array.from(new Set(subsetItems.map((item) => item.category).filter(Boolean)))];
    }, [subsetItems]);

    const filteredItems = useMemo(() => {
        return subsetItems.filter((item) => {
            if (selectedCategory !== "すべて" && item.category !== selectedCategory) {
                return false;
            }
            const res = results[item.id];
            if (showErrorsOnly && res && res.is_correct) {
                return false;
            }
            if (showErrorsOnly && !res) {
                return false;
            }
            if (searchQuery.trim()) {
                const q = searchQuery.toLowerCase();
                const inContext = item.left_context.toLowerCase().includes(q);
                const inTarget = item.target.toLowerCase().includes(q);
                const inExpected = item.expected.toLowerCase().includes(q);
                const inPrediction = res?.top1?.toLowerCase().includes(q) ?? false;
                if (!inContext && !inTarget && !inExpected && !inPrediction) {
                    return false;
                }
            }
            return true;
        });
    }, [subsetItems, selectedCategory, showErrorsOnly, searchQuery, results]);

    // かな漢字変換集計
    const evaluatedList = useMemo(() => {
        return subsetItems.map((item) => results[item.id]).filter((r): r is ItemResult => Boolean(r));
    }, [subsetItems, results]);
    const evaluatedCount = evaluatedList.length;
    const correctCount = evaluatedList.filter((r) => r.is_correct).length;
    const overallAccuracy = evaluatedCount > 0 ? ((correctCount / evaluatedCount) * 100).toFixed(1) : "0.0";
    const avgLatency = evaluatedCount > 0
        ? (evaluatedList.reduce((acc, r) => acc + r.latency_ms, 0) / evaluatedCount).toFixed(1)
        : "0.0";
    const latencyP = useMemo(
        () => latencyPercentiles(evaluatedList.map((r) => r.latency_ms)),
        [evaluatedList],
    );

    const [wallMs, setWallMs] = useState<Partial<Record<RunKey, number>>>({});
    const [runRuntimes, setRunRuntimes] = useState<Partial<Record<RunKey, BenchmarkRuntime>>>({});
    const busyRef = useRef(false);

    const runCases = async <Row extends { id: number }>(
        kind: "conversion" | "typo" | "predictive",
        items: { id: number }[],
        stopped: () => boolean,
        receive: (rows: Record<number, Row>) => void,
        scope: ConversionScope = "model",
    ) => {
        const wallKey = kind === "conversion" ? scope : kind;
        setWallMs((previous) => ({ ...previous, [wallKey]: undefined }));
        setRunRuntimes((previous) => ({ ...previous, [wallKey]: undefined }));
        const session = await invoke<BenchmarkRuntime & { id: number; batch_size: number }>("start_benchmark", {
            scope: kind === "typo" ? "typo" : scope,
            backend: benchmarkBackend,
        });
        setRunRuntimes((previous) => ({ ...previous, [wallKey]: session }));
        try {
            for (let offset = 0; offset < items.length && !stopped(); offset += session.batch_size) {
                const cases = items.slice(offset, offset + session.batch_size).map((item) => ({ ...item, kind }));
                const rows = await invoke<Row[]>("run_benchmark_batch", { id: session.id, cases });
                receive(Object.fromEntries(rows.map((row) => [row.id, row])));
            }
        } finally {
            const elapsed = await invoke<number>("finish_benchmark", { id: session.id });
            setWallMs((previous) => ({ ...previous, [wallKey]: elapsed }));
        }
    };

    const handleRunBenchmark = async (itemsToRun: BenchmarkItem[]) => {
        if (busyRef.current || sharedQuickRunning || !benchmarkBackend || (conversionScope !== "quick" && itemsToRun.length === 0)) return;
        busyRef.current = true;
        setConversionResults((previous) => ({ ...previous, [conversionScope]: {} }));
        setIsRunning(true);
        stopSignalRef.current = false;
        try {
            if (conversionScope === "quick") {
                setQuickProgress(null);
                setWallMs((previous) => ({ ...previous, quick: undefined }));
                setRunRuntimes((previous) => ({ ...previous, quick: undefined }));
                const progress = new Channel<QuickBenchmarkProgress>();
                progress.onmessage = setQuickProgress;
                const measured = await invoke<QuickConversionResult>("run_quick_conversion_benchmark", { backend: benchmarkBackend, progress })
                    .finally(() => { progress.onmessage = () => {}; });
                setConversionResults((previous) => ({ ...previous, quick: Object.fromEntries(measured.rows.map((row) => [row.id, row])) }));
                setWallMs((previous) => ({ ...previous, quick: measured.elapsed_ms }));
                setRunRuntimes((previous) => ({ ...previous, quick: measured }));
                return;
            }
            await runCases<ItemResult>("conversion", itemsToRun, () => stopSignalRef.current, (rows) =>
                setConversionResults((previous) => ({
                    ...previous, [conversionScope]: { ...previous[conversionScope], ...rows },
                })), conversionScope);
        } catch (error) {
            toast.error(`実行エラー: ${error}`);
        } finally {
            busyRef.current = false;
            setIsRunning(false);
        }
    };

    // --- タイポ補正: フィルタリング & 判定 ---
    const filteredTypoItems = useMemo(() => {
        return TYPO_ITEMS.filter((item) => {
            if (selectedTypoFilter === "typo_only" && item.is_clean) return false;
            if (selectedTypoFilter === "clean_only" && !item.is_clean) return false;
            if (selectedTypoFilter === "derived_clean" && item.origin !== "derived-clean") return false;
            if (!["all", "typo_only", "clean_only", "derived_clean"].includes(selectedTypoFilter) && item.typo_type !== selectedTypoFilter) {
                return false;
            }
            const res = typoResults[item.id];
            if (showTypoErrorsOnly && res) {
                if (res.status === "exact_match" || res.status === "variant_match" || res.status === "clean_preserved") {
                    return false;
                }
            }
            if (showTypoErrorsOnly && !res) {
                return false;
            }
            if (typoSearchQuery.trim()) {
                const q = typoSearchQuery.toLowerCase();
                const inCtx = item.context.toLowerCase().includes(q);
                const inInput = item.input_reading.toLowerCase().includes(q);
                const inTarget = item.target_reading.toLowerCase().includes(q);
                const inExp = item.expected.toLowerCase().includes(q);
                if (!inCtx && !inInput && !inTarget && !inExp) return false;
            }
            return true;
        });
    }, [selectedTypoFilter, showTypoErrorsOnly, typoSearchQuery, typoResults]);

    // タイポ集計
    const typoEvaluatedList = useMemo(() => Object.values(typoResults), [typoResults]);
    const typoEvaluatedCount = typoEvaluatedList.length;
    const typoOverallCorrect = typoEvaluatedList.filter(({ status }) =>
        status === "exact_match" || status === "variant_match" || status === "clean_preserved",
    ).length;
    
    // タイポ問題（is_clean=false）の集計
    const typoCasesTested = typoEvaluatedList.filter((r) => {
        const item = TYPO_ITEMS.find((it) => it.id === r.id);
        return item && !item.is_clean;
    });
    const exactMatches = typoCasesTested.filter((r) => r.status === "exact_match").length;
    const variantMatches = typoCasesTested.filter((r) => r.status === "variant_match").length;
    const typoAccuracyExact = typoCasesTested.length > 0 ? ((exactMatches / typoCasesTested.length) * 100).toFixed(1) : "0.0";
    const typoAccuracyEffective = typoCasesTested.length > 0 ? (((exactMatches + variantMatches) / typoCasesTested.length) * 100).toFixed(1) : "0.0";

    // 正常入力（is_clean=true）のFP集計
    const cleanCasesTested = typoEvaluatedList.filter((r) => {
        const item = TYPO_ITEMS.find((it) => it.id === r.id);
        return item && item.is_clean;
    });
    const falsePositives = cleanCasesTested.filter((r) => r.status === "false_positive").length;
    const fpRate = cleanCasesTested.length > 0 ? ((falsePositives / cleanCasesTested.length) * 100).toFixed(1) : "0.0";

    const avgTypoLatency = typoEvaluatedCount > 0
        ? (typoEvaluatedList.reduce((acc, r) => acc + r.latency_ms, 0) / typoEvaluatedCount).toFixed(1)
        : "0.0";
    const typoLatencyP = useMemo(
        () => latencyPercentiles(typoEvaluatedList.map((r) => r.latency_ms)),
        [typoEvaluatedList],
    );

    const handleRunTypoBenchmark = async (itemsToRun: TypoBenchmarkItem[]) => {
        if (busyRef.current || itemsToRun.length === 0) return;
        busyRef.current = true;
        setTypoResults({});
        setIsTypoRunning(true);
        stopTypoSignalRef.current = false;
        try {
            await runCases<TypoItemResult>("typo", itemsToRun, () => stopTypoSignalRef.current, (rows) =>
                setTypoResults((previous) => ({ ...previous, ...rows })));
        } catch (error) {
            toast.error(`実行エラー: ${error}`);
        } finally {
            busyRef.current = false;
            setIsTypoRunning(false);
        }
    };

    const predictiveEvaluatedList = useMemo(() => Object.values(predictiveResults), [predictiveResults]);
    const predictiveCorrect = predictiveEvaluatedList.filter((r) => r.is_match).length;
    const predictiveAccuracy = predictiveEvaluatedList.length > 0
        ? ((predictiveCorrect / predictiveEvaluatedList.length) * 100).toFixed(1)
        : "0.0";
    const predictiveLatencyP = useMemo(
        () => latencyPercentiles(predictiveEvaluatedList.map((r) => r.latency_ms)),
        [predictiveEvaluatedList],
    );

    const handleRunPredictiveBenchmark = async (itemsToRun: PredictiveBenchmarkItem[]) => {
        if (busyRef.current || itemsToRun.length === 0) return;
        busyRef.current = true;
        setPredictiveResults({});
        setIsPredictiveRunning(true);
        stopPredictiveSignalRef.current = false;
        try {
            await runCases<PredictiveItemResult>("predictive", itemsToRun, () => stopPredictiveSignalRef.current, (rows) =>
                setPredictiveResults((previous) => ({ ...previous, ...rows })));
        } catch (error) {
            toast.error(`実行エラー: ${error}`);
        } finally {
            busyRef.current = false;
            setIsPredictiveRunning(false);
        }
    };

    const anyRunning = isRunning || isTypoRunning || isPredictiveRunning;
    const visibleRunKey = benchmarkTab === "zenzai" ? conversionScope : benchmarkTab;
    const lastRunMs = wallMs[visibleRunKey];
    const lastRuntime = runRuntimes[visibleRunKey];
    const runDisabled = anyRunning || sharedQuickRunning || isSwitchingModel || !benchmarkBackend || capability?.[benchmarkBackend] === false;
    const caseCount = conversionScope === "quick" ? 84 : subsetItems.length;
    const tabDefs = [
        { id: "zenzai" as const, icon: Zap, label: "かな漢字変換", meta: "840問" },
        { id: "typo" as const, icon: Layers, label: "タイポ補正", meta: "450問" },
        { id: "predictive" as const, icon: Zap, label: "先読み", meta: "30問" },
    ];

    return (
        <div className="mx-auto max-w-7xl space-y-6 pb-12">
            <BenchmarkDepartureGuard running={anyRunning} quick={isRunning && conversionScope === "quick"} />
            {/* Tab Selector */}
            <div role="tablist" aria-label="ベンチマーク種別" className="flex flex-wrap items-center gap-2 border-b border-border/60 pb-3">
                {tabDefs.map(({ id, icon: TabIcon, label, meta }) => {
                    const active = benchmarkTab === id;
                    return (
                        <button
                            key={id}
                            type="button"
                            role="tab"
                            aria-selected={active}
                            disabled={anyRunning}
                            onClick={() => setBenchmarkTab(id)}
                            className={`inline-flex items-center gap-2 rounded-lg px-3.5 py-2 text-sm font-semibold transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 enabled:cursor-pointer disabled:cursor-not-allowed disabled:opacity-50 ${
                                active
                                    ? "bg-primary text-primary-foreground shadow-sm"
                                    : "bg-muted text-muted-foreground hover:text-foreground"
                            }`}
                        >
                            <TabIcon className="size-4" aria-hidden />
                            <span>{label}</span>
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${active ? "bg-primary-foreground/20" : "bg-card/70 text-muted-foreground"}`}>{meta}</span>
                        </button>
                    );
                })}
            </div>

            <section aria-label="測定設定" className="grid gap-3 rounded-xl border border-border bg-card/60 p-4 sm:grid-cols-2">
                <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium" htmlFor="benchmark-model">
                    使用モデル
                    <select id="benchmark-model" value={activeModelId} disabled={anyRunning || sharedQuickRunning || isSwitchingModel || models.length === 0}
                        className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm disabled:opacity-60" onChange={(event) => void handleSwitchModel(event.target.value)}>
                        {!activeModel && <option value="">モデルを選択</option>}
                        {models.map((model) => <option key={model.id} value={model.id} disabled={!model.available}>{model.name}{model.available ? "" : "（未配置）"}</option>)}
                    </select>
                </label>
                <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium" htmlFor="benchmark-backend">
                    ベンチマーク用バックエンド
                    <select id="benchmark-backend" value={benchmarkBackend ?? ""} disabled={anyRunning || sharedQuickRunning || isSwitchingModel || !configuredRuntime}
                        className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm disabled:opacity-60"
                        onChange={(event) => setBackendOverride(event.target.value === configuredRuntime?.backend ? null : event.target.value as QuickBackendId)}>
                        {!configuredRuntime && <option value="">設定を読み込み中</option>}
                        <option value="cpu" disabled={capability?.cpu === false}>CPU{configuredRuntime?.backend === "cpu" ? "（IME設定）" : ""}</option>
                        <option value="vulkan" disabled={capability?.vulkan === false}>Vulkan{configuredRuntime?.backend === "vulkan" ? "（IME設定）" : ""}</option>
                    </select>
                    <span className="text-xs font-normal text-muted-foreground">
                        この画面内のみ。{benchmarkBackend && configuredRuntime && backendText({ ...configuredRuntime, backend: benchmarkBackend })}
                    </span>
                </label>
            </section>

            {lastRuntime && <div className="flex flex-wrap gap-x-5 gap-y-1 rounded-md border border-border/70 bg-muted/30 px-3 py-2 text-xs">
                <span>この結果の使用バックエンド <strong className="ml-1">{backendText(lastRuntime)}</strong></span>
                {lastRunMs !== undefined && <span className="text-muted-foreground">実行時間 {formatExecutionTime(lastRunMs)}</span>}
            </div>}

            {/* TAB 1: かな漢字変換 (840問) */}
            {benchmarkTab === "zenzai" && (
                <div role="tabpanel" aria-label="かな漢字変換ベンチマーク" className="space-y-6">
                    <div className="space-y-3 rounded-xl border border-border/70 bg-card/60 p-4">
                        <fieldset disabled={anyRunning || isSwitchingModel} className="space-y-2">
                            <legend className="text-sm font-medium">計測方法</legend>
                            <div className="flex flex-wrap gap-x-4 gap-y-2">
                                {CONVERSION_SCOPES.map((scope) => (
                                    <label key={scope.id} className="inline-flex items-center gap-2 text-sm">
                                        <input type="radio" name="conversion-benchmark-scope" value={scope.id}
                                            checked={conversionScope === scope.id} onChange={() => {
                                                setConversionScope(scope.id);
                                                setSelectedCategory("すべて");
                                                if (conversionScope === "quick" || scope.id === "quick") {
                                                    setSearchQuery("");
                                                    setShowErrorsOnly(false);
                                                }
                                            }} />
                                        {scope.label}
                                    </label>
                                ))}
                            </div>
                            <p className="text-xs text-muted-foreground">{CONVERSION_SCOPES.find((scope) => scope.id === conversionScope)?.description}</p>
                        </fieldset>
                        {conversionScope !== "quick" && (
                            <fieldset disabled={isRunning} className="flex flex-wrap items-center gap-1 rounded-lg bg-muted/60 p-1">
                                <legend className="sr-only">問題セット</legend>
                                {SUBSETS.map((subset) => (
                                    <FilterPill key={subset.id} active={selectedSubset === subset.id} label={subset.label} onClick={() => { setSelectedSubset(subset.id); setSelectedCategory("すべて"); }}>
                                        {subset.label}
                                    </FilterPill>
                                ))}
                            </fieldset>
                        )}
                        <div className="flex flex-wrap items-center gap-2">
                            {!isRunning ? (
                                <button type="button" disabled={runDisabled} onClick={() => void handleRunBenchmark(subsetItems)}
                                    className="inline-flex items-center gap-1.5 rounded-lg bg-foreground px-4 py-2 text-xs font-semibold text-background enabled:cursor-pointer enabled:hover:opacity-90 disabled:opacity-50">
                                    <Play className="size-3.5 fill-current" aria-hidden /> 計測開始（{caseCount}問）
                                </button>
                            ) : conversionScope === "quick" ? (
                                <div role="status" className="flex flex-wrap items-center gap-3 text-xs">
                                    <span>クイック検証を計測中</span>
                                    <progress className="h-2 w-28 accent-primary" aria-label="クイック検証の進捗" max={quickProgress?.total ?? 1} value={quickProgress?.completed} />
                                </div>
                            ) : (
                                <button type="button" onClick={() => { stopSignalRef.current = true; }}
                                    className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg bg-destructive px-4 py-2 text-xs font-semibold text-white">
                                    <Square className="size-3.5 fill-current" aria-hidden /> テスト停止
                                </button>
                            )}
                            {evaluatedCount > 0 && !isRunning && (
                                <button type="button" onClick={() => {
                                    setConversionResults((previous) => ({ ...previous, [conversionScope]: {} }));
                                    setWallMs((previous) => ({ ...previous, [conversionScope]: undefined }));
                                    setRunRuntimes((previous) => ({ ...previous, [conversionScope]: undefined }));
                                }} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-2 text-xs text-muted-foreground hover:text-foreground">
                                    <RotateCcw className="size-3.5" aria-hidden /> 結果リセット
                                </button>
                            )}
                        </div>
                    </div>

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                        <KpiCard label="正解率 (Top 1)" value={evaluatedCount > 0 ? `${overallAccuracy}%` : "—"} sub={evaluatedCount > 0 ? `${correctCount} / ${evaluatedCount} 問 正解` : "未評価"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="平均レイテンシ" value={evaluatedCount > 0 ? `${avgLatency} ms` : "—"} sub={evaluatedCount > 0 ? `p50 ${latencyP.p50.toFixed(1)} / p95 ${latencyP.p95.toFixed(1)} / p99 ${latencyP.p99.toFixed(1)} ms` : "未評価"} icon={Clock} iconClassName="text-sky-500" />
                        <KpiCard label="評価進捗" value={`${evaluatedCount} / ${caseCount}`} sub={evaluatedCount === caseCount ? "計測完了" : isRunning ? "計測中" : "評価した問題数"} icon={Layers} />
                        {conversionScope !== "model" && <KpiCard label="N-best追加の平均" value={evaluatedCount > 0 ? `${(evaluatedList.reduce((sum, row) => sum + (row.nbest_ms ?? 0), 0) / evaluatedCount).toFixed(1)} ms` : "—"} sub="標準辞書から候補を追加" icon={SlidersHorizontal} />}
                    </div>

                    <div className="flex flex-wrap items-center justify-between gap-3">
                        <label htmlFor="conversion-category" className="flex items-center gap-2 text-xs font-medium">
                            カテゴリ
                            <select id="conversion-category" value={selectedCategory} onChange={(event) => setSelectedCategory(event.target.value)}
                                className="max-w-52 rounded-md border border-input bg-background px-2 py-1.5 text-xs">
                                {availableCategories.map((category) => <option key={category}>{category}</option>)}
                            </select>
                        </label>
                        <div className="flex items-center gap-2">
                            <SearchField value={searchQuery} onChange={setSearchQuery} placeholder="文脈・読み・期待表記..." />
                            <button
                                type="button"
                                aria-pressed={showErrorsOnly}
                                onClick={() => setShowErrorsOnly(!showErrorsOnly)}
                                className={`inline-flex shrink-0 cursor-pointer items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                                    showErrorsOnly
                                        ? "border-destructive/30 bg-destructive/10 font-medium text-destructive"
                                        : "border-border text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                <XCircle className="size-3.5" aria-hidden />
                                不正解のみ
                            </button>
                        </div>
                    </div>

                    <section aria-labelledby="conversion-result-title" className="overflow-hidden rounded-xl border border-border/70 bg-card/60">
                        <h3 id="conversion-result-title" className="border-b border-border/60 px-3 py-2.5 text-sm font-semibold">問題ごとの結果</h3>
                        <div className="max-h-[640px] overflow-auto">
                            <table className="w-full min-w-[540px] table-fixed border-collapse text-left text-xs">
                                <thead className={TABLE_HEAD}>
                                    <tr>
                                        <th scope="col" className={TABLE_HEAD_CELL}>入力読み</th>
                                        <th scope="col" className={TABLE_HEAD_CELL}>期待表記（正解）</th>
                                        <th scope="col" className={TABLE_HEAD_CELL}>変換結果</th>
                                        <th scope="col" className={TABLE_HEAD_CELL + " w-20 text-center"}>判定</th>
                                        <th scope="col" className={TABLE_HEAD_CELL + " w-20 text-right"}>遅延</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/50">
                                    {filteredItems.length === 0 ? (
                                        <EmptyRow colSpan={5} message={conversionScope === "quick" && evaluatedCount === 0 ? "計測完了後に対象問題の結果が表示されます" : "条件に一致する問題はありません"} />
                                    ) : filteredItems.slice(0, 100).map((item) => {
                                        const res = results[item.id];
                                        return (
                                            <tr key={item.id} className="align-top hover:bg-muted/30">
                                                <td className="break-words p-3">
                                                    <div className="font-medium text-foreground">{item.target}</div>
                                                    <details className="mt-1 text-muted-foreground">
                                                        <summary className="w-fit cursor-pointer rounded underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">詳細</summary>
                                                        <dl className="mt-2 space-y-1.5">
                                                            <div><dt className="sr-only">問題番号とセット</dt><dd>#{item.id} <SubsetBadge subset={item.subset} /></dd></div>
                                                            <div><dt className="font-medium text-foreground">カテゴリ</dt><dd>{item.category}</dd></div>
                                                            <div><dt className="font-medium text-foreground">前文</dt><dd>{item.left_context || "なし"}</dd></div>
                                                            {item.acceptable?.length ? <div><dt className="font-medium text-foreground">許容表記</dt><dd>{item.acceptable.join(" / ")}</dd></div> : null}
                                                            {res?.candidates.length ? <div><dt className="font-medium text-foreground">候補一覧</dt><dd>{res.candidates.join(" / ")}</dd></div> : null}
                                                            {res?.nbest_ms !== undefined && <div><dt className="font-medium text-foreground">N-best追加</dt><dd>{res.nbest_ms.toFixed(1)} ms</dd></div>}
                                                        </dl>
                                                    </details>
                                                </td>
                                                <td className="break-words p-3 font-medium text-foreground">{item.expected}</td>
                                                <td className={"break-words p-3 " + (res?.is_correct === false ? "font-semibold text-destructive" : "text-foreground")}>
                                                    {res ? res.top1 || "候補なし" : <span className="text-muted-foreground">—</span>}
                                                </td>
                                                <td className="p-3 text-center">
                                                    {res ? (
                                                        res.is_correct ? (
                                                            <span className="inline-flex items-center gap-1 whitespace-nowrap text-emerald-600 dark:text-emerald-400"><CheckCircle2 className="size-3.5" aria-hidden />正解</span>
                                                        ) : (
                                                            <span className="inline-flex items-center gap-1 whitespace-nowrap text-destructive"><XCircle className="size-3.5" aria-hidden />不正解</span>
                                                        )
                                                    ) : <span className="text-muted-foreground">—</span>}
                                                </td>
                                                <td className="p-3 text-right tabular-nums text-muted-foreground">
                                                    {res ? res.latency_ms.toFixed(1) + " ms" : "—"}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                        {filteredItems.length > 100 && (
                            <div className="border-t border-border/50 bg-muted/20 p-2 text-center text-xs text-muted-foreground">
                                最初の100件を表示中（絞り込み後 {filteredItems.length} 件）
                            </div>
                        )}
                    </section>
                </div>
            )}

            {/* TAB 2: タイポ補正 (450問) */}
            {benchmarkTab === "typo" && (
                <div role="tabpanel" aria-label="タイポ補正ベンチマーク" className="space-y-6">
                    <p className="text-sm text-muted-foreground">Windowsの候補生成と補正を、現在のタイポ補正設定で測ります。450問は入力読みから評価し、個人の学習履歴は使いません。</p>
                    {/* KPI Cards */}
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5 lg:gap-4">
                        <KpiCard label="全体成功率" value={typoEvaluatedCount > 0 ? `${(100 * typoOverallCorrect / typoEvaluatedCount).toFixed(1)}%` : "—"} sub={typoEvaluatedCount > 0 ? `${typoOverallCorrect} / ${typoEvaluatedCount} 問（正常保持を含む）` : "Androidと同じ集計"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="タイポ正解率（完全一致）" value={typoCasesTested.length > 0 ? `${typoAccuracyExact}%` : "—"} sub={typoCasesTested.length > 0 ? `${exactMatches} / ${typoCasesTested.length} 問（実質 ${typoAccuracyEffective}%）` : "未評価"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="正常入力の誤補正 (FP)" value={cleanCasesTested.length > 0 ? `${falsePositives} 件` : "—"} sub={cleanCasesTested.length > 0 ? `${cleanCasesTested.length - falsePositives} / ${cleanCasesTested.length} 保持（誤補正率 ${fpRate}%）` : "195問で検証"} icon={ShieldCheck} iconClassName={falsePositives > 0 ? "text-rose-500" : "text-sky-500"} valueClassName={falsePositives > 0 ? "text-destructive" : undefined} />
                        <KpiCard label="平均レイテンシ" value={typoEvaluatedCount > 0 ? `${avgTypoLatency} ms` : "—"} sub={typoEvaluatedCount > 0 ? `p50 ${typoLatencyP.p50.toFixed(0)} / p95 ${typoLatencyP.p95.toFixed(0)} / p99 ${typoLatencyP.p99.toFixed(0)} ms` : "1文あたりの実効処理時間"} icon={Clock} iconClassName="text-amber-500" />
                        <KpiCard label="評価進捗" value={`${typoEvaluatedCount} / ${TYPO_ITEMS.length}`} sub={typoEvaluatedCount === TYPO_ITEMS.length ? "全450件評価完了" : "450件ベンチマーク準備完了"} icon={Layers} />
                    </div>

                    {/* Action Bar */}
                    <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-border/60 bg-muted/30">
                        <div className="flex flex-wrap items-center gap-2">
                            {!isTypoRunning ? (
                                <>
                                    <button
                                        type="button"
                                        disabled={runDisabled}
                                        onClick={() => handleRunTypoBenchmark(filteredTypoItems)}
                                        className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-foreground text-background shadow-xs hover:opacity-90 transition-opacity cursor-pointer"
                                    >
                                        <Play className="size-3.5 fill-current" />
                                        タイポベンチマーク開始 ({filteredTypoItems.length}件)
                                    </button>
                                    <button
                                        type="button"
                                        disabled={runDisabled}
                                        onClick={() => handleRunTypoBenchmark(filteredTypoItems.slice(0, 15))}
                                        className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border border-border bg-card hover:bg-muted/60 transition-colors cursor-pointer"
                                    >
                                        <Zap className="size-3.5 text-amber-500" />
                                        タイポ補正の先頭15件
                                    </button>
                                </>
                            ) : (
                                <button
                                    type="button"
                                    onClick={() => { stopTypoSignalRef.current = true; }}
                                    className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-rose-600 text-white shadow-xs hover:bg-rose-700 transition-colors cursor-pointer"
                                >
                                    <Square className="size-3.5 fill-current" />
                                    テスト停止
                                </button>
                            )}

                            {typoEvaluatedCount > 0 && !isTypoRunning && (
                                <button
                                    type="button"
                                    onClick={() => { setTypoResults({}); setWallMs((previous) => ({ ...previous, typo: undefined })); setRunRuntimes((previous) => ({ ...previous, typo: undefined })); }}
                                    className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
                                >
                                    <RotateCcw className="size-3.5" />
                                    結果リセット
                                </button>
                            )}
                        </div>

                        <div className="flex max-w-full flex-wrap items-center gap-1 rounded-lg border border-border/70 bg-muted p-1 text-xs">
                            {TYPO_FILTER_TYPES.map((ft) => (
                                <FilterPill key={ft.id} active={selectedTypoFilter === ft.id} label={ft.label} onClick={() => setSelectedTypoFilter(ft.id)}>
                                    {ft.label}
                                </FilterPill>
                            ))}
                        </div>
                    </div>

                    {/* Filter & Search Bar */}
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                        <p className="text-xs text-muted-foreground">{filteredTypoItems.length} / {TYPO_ITEMS.length} 件を表示中（種別は上のフィルタで切替）</p>
                        <div className="flex items-center gap-2">
                            <SearchField value={typoSearchQuery} onChange={setTypoSearchQuery} placeholder="文脈・入力・期待表記..." />
                            <button
                                type="button"
                                aria-pressed={showTypoErrorsOnly}
                                onClick={() => setShowTypoErrorsOnly(!showTypoErrorsOnly)}
                                className={`inline-flex shrink-0 cursor-pointer items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                                    showTypoErrorsOnly
                                        ? "border-destructive/30 bg-destructive/10 font-medium text-destructive"
                                        : "border-border text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                <XCircle className="size-3.5" />
                                不正解/FPのみ
                            </button>
                        </div>
                    </div>

                    {/* Typo Results Table */}
                    <div className="overflow-hidden rounded-xl border border-border/70 bg-card/60 shadow-xs">
                        <div className="max-h-[640px] overflow-auto">
                            <table className="w-full min-w-[880px] border-collapse text-left text-xs">
                                <thead className={TABLE_HEAD}>
                                    <tr>
                                        <th className={`${TABLE_HEAD_CELL} w-12 text-center`}>#</th>
                                        <th className={`${TABLE_HEAD_CELL} w-36`}>種別</th>
                                        <th className={`${TABLE_HEAD_CELL} min-w-[160px]`}>前文</th>
                                        <th className={`${TABLE_HEAD_CELL} w-32`}>入力読み</th>
                                        <th className={`${TABLE_HEAD_CELL} w-32`}>期待表記（正解）</th>
                                        <th className={`${TABLE_HEAD_CELL} min-w-[140px]`}>補正出力</th>
                                        <th className={`${TABLE_HEAD_CELL} w-20 text-right`}>遅延</th>
                                        <th className={`${TABLE_HEAD_CELL} w-28 text-center`}>判定</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/50">
                                    {filteredTypoItems.length === 0 ? (
                                        <EmptyRow colSpan={8} message="条件に一致する問題はありません" />
                                    ) : filteredTypoItems.slice(0, 100).map((item) => {
                                        const res = typoResults[item.id];
                                        return (
                                            <tr key={item.id} className="hover:bg-muted/30 transition-colors">
                                                <td className="p-3 text-center text-muted-foreground font-mono">{item.id}</td>
                                                <td className="p-3">
                                                    <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium border ${
                                                        item.is_clean
                                                            ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-700 dark:text-emerald-300"
                                                            : "bg-amber-500/10 border-amber-500/30 text-amber-700 dark:text-amber-300"
                                                    }`}>
                                                        {item.typo_type_label}
                                                    </span>
                                                </td>
                                                <td className="p-3 text-muted-foreground max-w-xs truncate" title={item.context}>
                                                    {item.context || <span className="opacity-40">なし</span>}
                                                </td>
                                                <td className="p-3 font-semibold text-foreground">{item.input_reading}</td>
                                                <td className="p-3 font-medium text-foreground">{item.expected}</td>
                                                <td className="p-3 font-medium">
                                                    {res ? (
                                                        res.moshikashite_output ? (
                                                            <span className="px-2 py-0.5 rounded-md bg-amber-500/10 border border-amber-500/30 text-amber-700 dark:text-amber-300 font-semibold">
                                                                💡 {res.moshikashite_output}
                                                            </span>
                                                        ) : (
                                                            <span className="text-foreground">{res.main_output}</span>
                                                        )
                                                    ) : (
                                                        <span className="text-muted-foreground/30">—</span>
                                                    )}
                                                    {res?.correction_reading && <p className="mt-1 text-[11px] text-muted-foreground">補正後の読み {res.correction_reading}</p>}
                                                    {res?.moshikashite_output && res.main_output !== res.moshikashite_output && <p className="mt-1 text-[11px] text-muted-foreground">通常候補 {res.main_output}</p>}
                                                </td>
                                                <td className="p-3 text-right font-mono text-[11px] text-muted-foreground">
                                                    {res ? `${res.latency_ms.toFixed(0)} ms` : "—"}
                                                </td>
                                                <td className="p-3 text-center">
                                                    {res ? (
                                                        res.status === "exact_match" ? (
                                                            <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-medium text-[11px]">
                                                                <CheckCircle2 className="size-3.5" /> 完全一致
                                                            </span>
                                                        ) : res.status === "variant_match" ? (
                                                            <span className="inline-flex items-center gap-1 text-sky-600 dark:text-sky-400 font-medium text-[11px]">
                                                                <Check className="size-3.5" /> 実質正解
                                                            </span>
                                                        ) : res.status === "clean_preserved" ? (
                                                            <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-medium text-[11px]">
                                                                <ShieldCheck className="size-3.5" /> 正常保持
                                                            </span>
                                                        ) : res.status === "false_positive" ? (
                                                            <span className="inline-flex items-center gap-1 text-rose-600 dark:text-rose-400 font-bold text-[11px]">
                                                                <AlertTriangle className="size-3.5" /> 誤補正(FP)
                                                            </span>
                                                        ) : (
                                                            <span className="inline-flex items-center gap-1 text-rose-500 font-medium text-[11px]">
                                                                <XCircle className="size-3.5" /> 不正解
                                                            </span>
                                                        )
                                                    ) : (
                                                        <span className="text-muted-foreground/30 text-[11px]">—</span>
                                                    )}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                        {filteredTypoItems.length > 100 && (
                            <div className="p-2 text-center text-xs text-muted-foreground border-t bg-muted/20">
                                最初の100件を表示中（全 {filteredTypoItems.length} 件）
                            </div>
                        )}
                    </div>
                </div>
            )}
            {benchmarkTab === "predictive" && (
                <div role="tabpanel" aria-label="先読みベンチマーク" className="space-y-6">
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 lg:gap-4">
                        <KpiCard label="継続一致率" value={predictiveEvaluatedList.length > 0 ? `${predictiveAccuracy}%` : "—"} sub={predictiveEvaluatedList.length > 0 ? `${predictiveCorrect} / ${predictiveEvaluatedList.length} 問 命中` : "未評価"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="平均レイテンシ" value={predictiveEvaluatedList.length > 0 ? `${(predictiveEvaluatedList.reduce((a, r) => a + r.latency_ms, 0) / predictiveEvaluatedList.length).toFixed(1)} ms` : "—"} sub={predictiveEvaluatedList.length > 0 ? `p50 ${predictiveLatencyP.p50.toFixed(0)} / p95 ${predictiveLatencyP.p95.toFixed(0)} / p99 ${predictiveLatencyP.p99.toFixed(0)} ms` : "1文あたりの実効処理時間"} icon={Clock} iconClassName="text-sky-500" />
                        <KpiCard label="評価進捗" value={`${predictiveEvaluatedList.length} / ${PREDICTIVE_ITEMS.length}`} sub={predictiveEvaluatedList.length === PREDICTIVE_ITEMS.length ? "全30問評価完了" : "30問ベンチマーク準備完了"} icon={Layers} />
                    </div>
                    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border/60 bg-muted/30 p-3.5">
                        {!isPredictiveRunning ? (
                            <button type="button" disabled={runDisabled} onClick={() => handleRunPredictiveBenchmark(PREDICTIVE_ITEMS)} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg bg-foreground px-4 py-2 text-xs font-semibold text-background shadow-xs transition-opacity hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                                <Play className="size-3.5 fill-current" aria-hidden /> 先読みベンチマーク開始 ({PREDICTIVE_ITEMS.length}問)
                            </button>
                        ) : (
                            <button type="button" onClick={() => { stopPredictiveSignalRef.current = true; }} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg bg-destructive px-4 py-2 text-xs font-semibold text-white shadow-xs transition-colors hover:bg-destructive/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                                <Square className="size-3.5 fill-current" aria-hidden /> 中断
                            </button>
                        )}
                        {predictiveEvaluatedList.length > 0 && !isPredictiveRunning && (
                            <button type="button" onClick={() => { setPredictiveResults({}); setWallMs((previous) => ({ ...previous, predictive: undefined })); setRunRuntimes((previous) => ({ ...previous, predictive: undefined })); }} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                                <RotateCcw className="size-3.5" aria-hidden /> 結果リセット
                            </button>
                        )}
                    </div>
                    <div className="overflow-hidden rounded-xl border border-border/70 bg-card/60 shadow-xs">
                        <div className="max-h-[640px] overflow-auto">
                            <table className="w-full min-w-[760px] border-collapse text-left text-xs">
                                <thead className={TABLE_HEAD}>
                                    <tr>
                                        <th className={`${TABLE_HEAD_CELL} w-12 text-center`}>#</th>
                                        <th className={`${TABLE_HEAD_CELL} w-32`}>カテゴリ</th>
                                        <th className={`${TABLE_HEAD_CELL} w-40`}>部分入力</th>
                                        <th className={`${TABLE_HEAD_CELL} min-w-[200px]`}>予測（完全文）</th>
                                        <th className={`${TABLE_HEAD_CELL} w-40`}>正解継続</th>
                                        <th className={`${TABLE_HEAD_CELL} w-20 text-right`}>遅延</th>
                                        <th className={`${TABLE_HEAD_CELL} w-24 text-center`}>判定</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/30">
                                    {PREDICTIVE_ITEMS.map((item) => {
                                        const res = predictiveResults[item.id];
                                        return (
                                            <tr key={item.id} className="hover:bg-muted/30 transition-colors">
                                                <td className="p-3 text-center text-muted-foreground font-mono">{item.id}</td>
                                                <td className="p-3 text-muted-foreground font-medium">{item.category}</td>
                                                <td className="p-3 font-semibold text-foreground">{item.partial_input}</td>
                                                <td className="p-3 font-medium text-foreground">{res?.prediction ?? <span className="text-muted-foreground/30">—</span>}</td>
                                                <td className="p-3 text-muted-foreground">{item.expected_continuation}</td>
                                                <td className="p-3 text-right font-mono text-[11px] text-muted-foreground">{res ? `${res.latency_ms.toFixed(0)} ms` : "—"}</td>
                                                <td className="p-3 text-center">
                                                    {res ? (res.is_match ? (<span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-medium text-[11px]"><CheckCircle2 className="size-3.5" /> 命中</span>) : (<span className="inline-flex items-center gap-1 text-rose-500 font-medium text-[11px]"><XCircle className="size-3.5" /> 不一致</span>)) : (<span className="text-muted-foreground/30 text-[11px]">—</span>)}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

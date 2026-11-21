import { useState, useMemo, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useBlocker } from "react-router";
import { toast } from "sonner";
import rawBenchmarkData from "@/data/benchmark740.json";
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
    Cpu,
    Check,
    AlertTriangle,
    ShieldCheck,
    Filter,
    X
} from "lucide-react";
import { ZeroConvertLogo } from "@/components/zeroconvert-logo";
import {
    AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
    AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog";

function BenchmarkDepartureGuard({ running }: { running: boolean }) {
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
                    <AlertDialogTitle>ベンチマークを中断しますか？</AlertDialogTitle>
                    <AlertDialogDescription>
                        実行中の進捗と結果は破棄されます。続行するにはキャンセルしてください。
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
                        中断して移動
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
}

export type BenchmarkItem = {
    id: number;
    subset: "basic" | "hard" | "realistic" | "colloquial";
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
};

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
    predictive_output?: string | null;
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

// --- 共通ベンチマーク採点ミラー (Rust benchmark.rs と同一契約、parity_cases で照合) ---
// TS 側は表示用に複写する。結果（status）の正本は Rust の score_typo。
function normalizeWhitespace(s: string): string {
    return s.replace(/[\s\u3000]+/g, "");
}
function cleanScoringInput(s: string): string {
    return s.trim().replace(/\r/g, "").replace(/\n/g, "");
}
function matchesExact(candidate: string, expected: string, acceptable: string[] = []): boolean {
    const cand = cleanScoringInput(candidate);
    const exp = expected.trim();
    if (cand === exp || acceptable.some((a) => a.trim() === cand)) return true;
    const normCand = normalizeWhitespace(cand);
    if (normCand === normalizeWhitespace(exp)) return true;
    return acceptable.some((a) => normCand === normalizeWhitespace(a.trim()));
}
function matchesVariant(candidate: string, expected: string): boolean {
    const cand = cleanScoringInput(candidate);
    const exp = expected.trim();
    if (!cand || !exp) return false;
    return cand.includes(exp) || exp.includes(cand);
}
function scoreTypoCommon(
    item: TypoBenchmarkItem,
    mainOutput: string,
    suggestion: string | null | undefined,
): TypoItemResult["status"] {
    const acceptable = item.acceptable ?? [];
    if (item.is_clean) {
        if (suggestion == null) return "clean_preserved";
        const sugg = cleanScoringInput(suggestion);
        if (matchesExact(sugg, item.expected, acceptable)) return "clean_preserved";
        if (sugg === cleanScoringInput(mainOutput)) return "clean_preserved";
        if (normalizeWhitespace(sugg) === normalizeWhitespace(item.input_reading)) return "clean_preserved";
        return "false_positive";
    }
    const suggExact = suggestion != null ? matchesExact(suggestion, item.expected, acceptable) : false;
    const mainExact = matchesExact(mainOutput, item.expected, acceptable);
    if (suggExact || mainExact) return "exact_match";
    let variant = false;
    if (suggestion != null) variant = variant || matchesVariant(suggestion, item.expected);
    variant = variant || matchesVariant(mainOutput, item.expected);
    if (variant) return "variant_match";
    return "missed";
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

// 共通ベンチマーク正本 benchmarks/common (740問)。subset_label の件数は正本 manifest と一致させる。
const SUBSETS = [
    { id: "all", label: "全740問 (230+250+160+100)" },
    { id: "basic", label: "基本・文脈 (230問)" },
    { id: "hard", label: "高難度文脈 (250問)" },
    { id: "realistic", label: "実IME (160問)" },
    { id: "colloquial", label: "口語 (100問)" },
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
};

function SubsetBadge({ subset }: { subset: BenchmarkItem["subset"] }) {
    const label = subset === "basic" ? "基本230" : subset === "hard" ? "高難度250" : subset === "realistic" ? "実IME160" : "口語100";
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

    // --- かな漢字変換 (740問) State ---
    const [results, setResults] = useState<Record<number, ItemResult>>({});
    const [isRunning, setIsRunning] = useState(false);
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
            setResults({});
            setTypoResults({});
            toast.success(`モデルを ${models.find((model) => model.id === newModelId)?.name ?? newModelId} に切り替えました`, { id: toastId });
        } catch (err) {
            toast.error(`モデル切り替えに失敗しました: ${err}`, { id: toastId });
        } finally {
            setIsSwitchingModel(false);
        }
    };

    // --- かな漢字変換: フィルタリング ---
    const subsetItems = useMemo(() => {
        if (selectedSubset === "all") return BENCHMARK_ITEMS;
        return BENCHMARK_ITEMS.filter((item) => item.subset === selectedSubset);
    }, [selectedSubset]);

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

    const runItem = async (item: BenchmarkItem): Promise<ItemResult> => {
        try {
            const effectiveContext = item.left_context === "なし" ? "" : item.left_context;
            const res = await invoke<ItemResult>("run_benchmark_item", {
                id: item.id,
                context: effectiveContext,
                target: item.target,
                expected: item.expected,
                acceptable: item.acceptable || [],
                forbiddenExamples: item.forbidden_examples || []
            });
            return res;
        } catch (err) {
            return {
                id: item.id,
                top1: `(エラー: ${err})`,
                candidates: [],
                is_correct: false,
                latency_ms: 0
            };
        }
    };

    const handleRunBenchmark = async (itemsToRun: BenchmarkItem[]) => {
        if (isRunning) return;
        setIsRunning(true);
        stopSignalRef.current = false;

        for (const item of itemsToRun) {
            if (stopSignalRef.current) break;
            const res = await runItem(item);
            setResults((prev) => ({ ...prev, [item.id]: res }));
        }

        setIsRunning(false);
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
        if (isTypoRunning) return;
        setIsTypoRunning(true);
        stopTypoSignalRef.current = false;

        const toastId = toast.loading(`メインモデル統合タイポ補正ベンチマーク実行中...`);
        try {
            for (const item of itemsToRun) {
                if (stopTypoSignalRef.current) break;
                
                const res = await invoke<{
                    id: number;
                    input: string;
                    main_output: string;
                    moshikashite_output?: string | null;
                    predictive_output?: string | null;
                    main_latency_ms: number;
                    assist_latency_ms: number;
                    total_latency_ms: number;
                }>("run_ai_assist_benchmark_item", {
                    id: item.id,
                    context: item.context,
                    input: item.input_reading,
                });

                // 判定ロジックは共通エンジン (benchmark.rs) と同一契約のミラー。
                const status = scoreTypoCommon(item, res.main_output, res.moshikashite_output ?? null);

                setTypoResults((prev) => ({
                    ...prev,
                    [item.id]: {
                        id: item.id,
                        main_output: res.main_output,
                        moshikashite_output: res.moshikashite_output,
                        predictive_output: res.predictive_output,
                        status,
                        latency_ms: res.total_latency_ms,
                    }
                }));
            }
            toast.success("タイポ補正ベンチマーク完了！", { id: toastId });
        } catch (err) {
            toast.error(`実行エラー: ${err}`, { id: toastId });
        } finally {
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
        if (isPredictiveRunning) return;
        setIsPredictiveRunning(true);
        stopPredictiveSignalRef.current = false;
        const toastId = toast.loading("先読みベンチマーク実行中...");
        try {
            for (const item of itemsToRun) {
                if (stopPredictiveSignalRef.current) break;
                const res = await invoke<{ id: number; prediction: string | null; is_match: boolean; latency_ms: number }>(
                    "run_predictive_benchmark_item",
                    {
                        id: item.id,
                        context: item.context,
                        partialInput: item.partial_input,
                        fullSentence: item.full_sentence,
                        expectedContinuation: item.expected_continuation,
                        acceptableContinuations: item.acceptable_continuations ?? [],
                    },
                );
                setPredictiveResults((prev) => ({
                    ...prev,
                    [item.id]: {
                        id: item.id,
                        prediction: res.prediction,
                        is_match: res.is_match,
                        latency_ms: res.latency_ms,
                    },
                }));
            }
            toast.success("先読みベンチマーク完了！", { id: toastId });
        } catch (err) {
            toast.error(`実行エラー: ${err}`, { id: toastId });
        } finally {
            setIsPredictiveRunning(false);
        }
    };

    const anyRunning = isRunning || isTypoRunning || isPredictiveRunning;
    const tabDefs = [
        { id: "zenzai" as const, icon: Zap, label: "かな漢字変換", meta: "740問" },
        { id: "typo" as const, icon: Layers, label: "タイポ補正", meta: "450問" },
        { id: "predictive" as const, icon: Zap, label: "先読み", meta: "30問" },
    ];

    return (
        <div className="mx-auto max-w-7xl space-y-6 pb-12">
            <BenchmarkDepartureGuard running={anyRunning} />
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

            {/* Header / Intro */}
            <div className="flex flex-col gap-3 border-b border-border/60 pb-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="min-w-0">
                    <h2 className="flex items-center gap-2.5 text-xl font-semibold tracking-tight text-foreground">
                        <ZeroConvertLogo className="size-5 shrink-0 rounded-sm shadow-2xs" />
                        {benchmarkTab === "zenzai"
                            ? "かな漢字変換ベンチマーク（740問）"
                            : benchmarkTab === "predictive"
                              ? "先読みベンチマーク（30問）"
                              : "タイポ補正ベンチマーク（450問）"}
                    </h2>
                    <p className="mt-1 max-w-2xl text-xs leading-relaxed text-muted-foreground">
                        {benchmarkTab === "zenzai"
                            ? "基本230問＋高難度250問＋実IME160問＋口語100問。Nano v4.1 直結推論の正解率とレイテンシ（p50/p95/p99）を検証します。"
                            : benchmarkTab === "predictive"
                              ? "Android と同一の30問で先読みの継続一致率とレイテンシを検証します。判定は共通エンジン（benchmark.rs）と同一です。"
                              : "メインモデル統合タイポ補正255件と、正常入力を誤補正しないこと（195件＝旧45＋増強150）を実機測定します。"}
                    </p>
                </div>

                {/* Model Selector Pill */}
                <fieldset className="shrink-0 self-start lg:self-auto">
                    <legend className="sr-only">対象モデル</legend>
                    <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-border/70 bg-muted/60 p-1">
                    {models.map((model) => (
                        <button
                            key={model.id}
                            type="button"
                            aria-pressed={activeModelId === model.id}
                            disabled={isSwitchingModel || anyRunning || !model.available}
                            onClick={() => handleSwitchModel(model.id)}
                            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring enabled:cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 ${
                                activeModelId === model.id
                                    ? "border border-border/80 bg-card font-semibold text-foreground shadow-xs"
                                    : "text-muted-foreground hover:text-foreground"
                            }`}
                        >
                            <Cpu className="size-3.5" aria-hidden />
                            <span>{model.name}</span>
                            <span className="rounded bg-muted px-1 py-0.5 text-[10px] text-muted-foreground">
                                {model.available ? `${model.size_mb.toFixed(1)}MB` : "未配置"}
                            </span>
                            {activeModelId === model.id && <Check className="size-3 text-emerald-500 stroke-[2.5]" aria-hidden />}
                        </button>
                    ))}
                    </div>
                </fieldset>
            </div>

            {/* TAB 1: かな漢字変換 (740問) */}
            {benchmarkTab === "zenzai" && (
                <div role="tabpanel" aria-label="かな漢字変換ベンチマーク" className="space-y-6">
                    {/* Metric KPI Cards */}
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:gap-4">
                        <KpiCard label="正解率 (Top 1)" value={evaluatedCount > 0 ? `${overallAccuracy}%` : "—"} sub={evaluatedCount > 0 ? `${correctCount} / ${evaluatedCount} 問 正解` : "未評価"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="平均レイテンシ" value={evaluatedCount > 0 ? `${avgLatency} ms` : "—"} sub={evaluatedCount > 0 ? `p50 ${latencyP.p50.toFixed(0)} / p95 ${latencyP.p95.toFixed(0)} / p99 ${latencyP.p99.toFixed(0)} ms` : "1文あたりの実効処理時間"} icon={Clock} iconClassName="text-sky-500" />
                        <KpiCard label={`評価進捗（${SUBSETS.find((s) => s.id === selectedSubset)?.label ?? ""}）`} value={`${evaluatedCount} / ${subsetItems.length}`} sub={evaluatedCount === subsetItems.length ? "対象セット検証完了" : "テスト準備完了"} icon={Layers} />
                        <KpiCard label="選択モデル" value={activeModel?.name ?? "未選択"} sub={activeModel ? `${activeModel.size_mb.toFixed(1)} MB` : "モデルを選択してください"} icon={SlidersHorizontal} valueClassName="text-base" />
                    </div>

                    {/* Action Bar / Controls */}
                    <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-border/60 bg-muted/30">
                        <div className="flex flex-wrap items-center gap-2">
                            {!isRunning ? (
                                <>
                                    <button
                                        type="button"
                                        onClick={() => handleRunBenchmark(subsetItems)}
                                        className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-foreground text-background shadow-xs hover:opacity-90 transition-opacity cursor-pointer"
                                    >
                                        <Play className="size-3.5 fill-current" />
                                        {SUBSETS.find((s) => s.id === selectedSubset)?.label} 開始 ({subsetItems.length}問)
                                    </button>
                                    <button
                                        type="button"
                                        onClick={() => handleRunBenchmark(subsetItems.slice(0, 10))}
                                        className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border border-border bg-card hover:bg-muted/60 transition-colors cursor-pointer"
                                    >
                                        <Zap className="size-3.5 text-amber-500" />
                                        クイック検証 (10件)
                                    </button>
                                </>
                            ) : (
                                <button
                                    type="button"
                                    onClick={() => { stopSignalRef.current = true; setIsRunning(false); }}
                                    className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-rose-600 text-white shadow-xs hover:bg-rose-700 transition-colors cursor-pointer"
                                >
                                    <Square className="size-3.5 fill-current" />
                                    テスト停止
                                </button>
                            )}

                            {evaluatedCount > 0 && !isRunning && (
                                <button
                                    type="button"
                                    onClick={() => setResults({})}
                                    className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
                                >
                                    <RotateCcw className="size-3.5" />
                                    結果リセット
                                </button>
                            )}
                        </div>

                        <div className="flex flex-wrap items-center gap-1 rounded-lg border border-border/70 bg-muted p-1 text-xs">
                            {SUBSETS.map((subset) => (
                                <FilterPill key={subset.id} active={selectedSubset === subset.id} label={subset.label} onClick={() => { setSelectedSubset(subset.id); setSelectedCategory("すべて"); }}>
                                    {subset.label}
                                </FilterPill>
                            ))}
                        </div>
                    </div>

                    {/* Filter & Search Bar */}
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <div className="flex items-start gap-2 sm:items-center">
                            <span className="flex shrink-0 items-center gap-1 pt-1.5 text-xs font-medium text-muted-foreground sm:pt-0">
                                <Filter className="size-3.5" aria-hidden />
                                カテゴリ
                            </span>
                            <div className="flex max-w-full gap-1.5 overflow-x-auto pb-1">
                                {availableCategories.map((cat) => (
                                    <button
                                        key={cat}
                                        type="button"
                                        aria-pressed={selectedCategory === cat}
                                        onClick={() => setSelectedCategory(cat)}
                                        className={`shrink-0 rounded-lg px-2.5 py-1 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring cursor-pointer ${
                                            selectedCategory === cat
                                                ? "bg-primary font-medium text-primary-foreground shadow-2xs"
                                                : "border border-border/40 bg-muted/80 text-muted-foreground hover:text-foreground"
                                        }`}
                                    >
                                        {cat}
                                    </button>
                                ))}
                            </div>
                        </div>

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

                    {/* Results Table */}
                    <div className="overflow-hidden rounded-xl border border-border/70 bg-card/60 shadow-xs">
                        <div className="max-h-[640px] overflow-auto">
                            <table className="w-full min-w-[900px] border-collapse text-left text-xs">
                                <thead className={TABLE_HEAD}>
                                    <tr>
                                        <th className={`${TABLE_HEAD_CELL} w-12 text-center`}>#</th>
                                        <th className={`${TABLE_HEAD_CELL} w-28`}>セット</th>
                                        <th className={`${TABLE_HEAD_CELL} w-28`}>カテゴリ</th>
                                        <th className={`${TABLE_HEAD_CELL} min-w-[180px]`}>前文</th>
                                        <th className={`${TABLE_HEAD_CELL} w-28`}>入力読み</th>
                                        <th className={`${TABLE_HEAD_CELL} w-32`}>期待表記（正解）</th>
                                        <th className={`${TABLE_HEAD_CELL} min-w-[140px]`}>モデル予測</th>
                                        <th className={`${TABLE_HEAD_CELL} w-20 text-right`}>遅延</th>
                                        <th className={`${TABLE_HEAD_CELL} w-20 text-center`}>判定</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/50">
                                    {filteredItems.length === 0 ? (
                                        <EmptyRow colSpan={9} message="条件に一致する問題はありません" />
                                    ) : filteredItems.slice(0, 100).map((item) => {
                                        const res = results[item.id];
                                        return (
                                            <tr key={item.id} className="hover:bg-muted/30 transition-colors">
                                                <td className="p-3 text-center font-mono text-muted-foreground">{item.id}</td>
                                                <td className="p-3"><SubsetBadge subset={item.subset} /></td>
                                                <td className="p-3 text-muted-foreground font-medium">{item.category}</td>
                                                <td className="p-3 text-muted-foreground max-w-xs truncate" title={item.left_context}>
                                                    {item.left_context || <span className="opacity-40">なし</span>}
                                                </td>
                                                <td className="p-3 font-semibold text-foreground">{item.target}</td>
                                                <td className="p-3 font-medium text-foreground">{item.expected}</td>
                                                <td className="p-3">
                                                    {res ? (
                                                        <span className={res.is_correct ? "font-medium text-foreground" : "font-semibold text-destructive"}>{res.top1}</span>
                                                    ) : (
                                                        <span className="text-muted-foreground/30">—</span>
                                                    )}
                                                </td>
                                                <td className="p-3 text-right font-mono text-[11px] tabular-nums text-muted-foreground">
                                                    {res ? `${res.latency_ms.toFixed(0)} ms` : "—"}
                                                </td>
                                                <td className="p-3 text-center">
                                                    {res ? (
                                                        res.is_correct ? (
                                                            <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400"><CheckCircle2 className="size-4" aria-hidden />正解</span>
                                                        ) : (
                                                            <span className="inline-flex items-center gap-1 text-rose-500"><XCircle className="size-4" aria-hidden />不正解</span>
                                                        )
                                                    ) : (
                                                        <span className="text-[11px] text-muted-foreground/30">—</span>
                                                    )}
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
                    </div>
                </div>
            )}

            {/* TAB 2: タイポ補正 (450問) */}
            {benchmarkTab === "typo" && (
                <div role="tabpanel" aria-label="タイポ補正ベンチマーク" className="space-y-6">
                    {/* KPI Cards */}
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:gap-4">
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
                                        onClick={() => handleRunTypoBenchmark(filteredTypoItems)}
                                        className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-foreground text-background shadow-xs hover:opacity-90 transition-opacity cursor-pointer"
                                    >
                                        <Play className="size-3.5 fill-current" />
                                        タイポベンチマーク開始 ({filteredTypoItems.length}件)
                                    </button>
                                    <button
                                        type="button"
                                        onClick={() => handleRunTypoBenchmark(filteredTypoItems.slice(0, 15))}
                                        className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border border-border bg-card hover:bg-muted/60 transition-colors cursor-pointer"
                                    >
                                        <Zap className="size-3.5 text-amber-500" />
                                        クイック検証 (15件)
                                    </button>
                                </>
                            ) : (
                                <button
                                    type="button"
                                    onClick={() => { stopTypoSignalRef.current = true; setIsTypoRunning(false); }}
                                    className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-rose-600 text-white shadow-xs hover:bg-rose-700 transition-colors cursor-pointer"
                                >
                                    <Square className="size-3.5 fill-current" />
                                    テスト停止
                                </button>
                            )}

                            {typoEvaluatedCount > 0 && !isTypoRunning && (
                                <button
                                    type="button"
                                    onClick={() => setTypoResults({})}
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
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:gap-4">
                        <KpiCard label="継続一致率" value={predictiveEvaluatedList.length > 0 ? `${predictiveAccuracy}%` : "—"} sub={predictiveEvaluatedList.length > 0 ? `${predictiveCorrect} / ${predictiveEvaluatedList.length} 問 命中` : "未評価"} icon={CheckCircle2} iconClassName="text-emerald-500" />
                        <KpiCard label="平均レイテンシ" value={predictiveEvaluatedList.length > 0 ? `${(predictiveEvaluatedList.reduce((a, r) => a + r.latency_ms, 0) / predictiveEvaluatedList.length).toFixed(1)} ms` : "—"} sub={predictiveEvaluatedList.length > 0 ? `p50 ${predictiveLatencyP.p50.toFixed(0)} / p95 ${predictiveLatencyP.p95.toFixed(0)} / p99 ${predictiveLatencyP.p99.toFixed(0)} ms` : "1文あたりの実効処理時間"} icon={Clock} iconClassName="text-sky-500" />
                        <KpiCard label="評価進捗" value={`${predictiveEvaluatedList.length} / ${PREDICTIVE_ITEMS.length}`} sub={predictiveEvaluatedList.length === PREDICTIVE_ITEMS.length ? "全30問評価完了" : "30問ベンチマーク準備完了"} icon={Layers} />
                        <KpiCard label="選択モデル" value={activeModel?.name ?? "未選択"} sub={activeModel ? `${activeModel.size_mb.toFixed(1)} MB` : "モデルを選択してください"} icon={SlidersHorizontal} valueClassName="text-base" />
                    </div>
                    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border/60 bg-muted/30 p-3.5">
                        {!isPredictiveRunning ? (
                            <button type="button" onClick={() => handleRunPredictiveBenchmark(PREDICTIVE_ITEMS)} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg bg-foreground px-4 py-2 text-xs font-semibold text-background shadow-xs transition-opacity hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                                <Play className="size-3.5 fill-current" aria-hidden /> 先読みベンチマーク開始 ({PREDICTIVE_ITEMS.length}問)
                            </button>
                        ) : (
                            <button type="button" onClick={() => { stopPredictiveSignalRef.current = true; }} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg bg-destructive px-4 py-2 text-xs font-semibold text-white shadow-xs transition-colors hover:bg-destructive/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                                <Square className="size-3.5 fill-current" aria-hidden /> 中断
                            </button>
                        )}
                        {predictiveEvaluatedList.length > 0 && !isPredictiveRunning && (
                            <button type="button" onClick={() => setPredictiveResults({})} className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
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

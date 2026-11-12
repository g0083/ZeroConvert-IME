import { useState, useMemo, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useBlocker } from "react-router";
import { toast } from "sonner";
import rawBenchmarkData from "@/data/benchmark480.json";
import rawTypoData from "@/data/typo_benchmark300.json";
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
    Filter
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
    subset: "basic" | "hard";
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
};

export type TypoItemResult = {
    id: number;
    main_output: string;
    moshikashite_output?: string | null;
    predictive_output?: string | null;
    status: "exact_match" | "variant_match" | "clean_preserved" | "false_positive" | "missed";
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

const SUBSETS = [
    { id: "all", label: "全480問 (基本230 + 高難度250)" },
    { id: "basic", label: "基本・文脈 (230問)" },
    { id: "hard", label: "高難度文脈 (250問)" },
];

const TYPO_FILTER_TYPES = [
    { id: "all", label: "すべて (300問)" },
    { id: "typo_only", label: "タイポ誤りのみ (255問)" },
    { id: "clean_only", label: "正常入力・FP検証 (45問)" },
    { id: "geminate", label: "促音 っ (25問)" },
    { id: "nasal", label: "撥音 ん (25問)" },
    { id: "transposition", label: "順序入替・転置 (40問)" },
    { id: "adjacent", label: "QWERTY隣接 (60問)" },
    { id: "missing_vowel", label: "母音抜け (50問)" },
    { id: "flick", label: "フリック (40問)" },
    { id: "prolonged", label: "長音 (15問)" },
];

export function Benchmark() {
    const [benchmarkTab, setBenchmarkTab] = useState<"zenzai" | "typo">("zenzai");

    // --- かな漢字変換 (480問) State ---
    const [results, setResults] = useState<Record<number, ItemResult>>({});
    const [isRunning, setIsRunning] = useState(false);
    const [selectedSubset, setSelectedSubset] = useState<string>("all");
    const [selectedCategory, setSelectedCategory] = useState("すべて");
    const [showErrorsOnly, setShowErrorsOnly] = useState(false);
    const [searchQuery, setSearchQuery] = useState("");

    // --- タイポ補正 (300問) State ---
    const [typoResults, setTypoResults] = useState<Record<number, TypoItemResult>>({});
    const [isTypoRunning, setIsTypoRunning] = useState(false);
    const [selectedTypoFilter, setSelectedTypoFilter] = useState("all");
    const [showTypoErrorsOnly, setShowTypoErrorsOnly] = useState(false);
    const [typoSearchQuery, setTypoSearchQuery] = useState("");

    // --- モデル情報 ---
    const [activeModelId, setActiveModelId] = useState<string>("");
    const [models, setModels] = useState<ModelInfo[]>([]);
    const activeModel = models.find((model) => model.id === activeModelId);
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);

    const stopSignalRef = useRef(false);
    const stopTypoSignalRef = useRef(false);

    useEffect(() => {
        return () => {
            stopSignalRef.current = true;
            stopTypoSignalRef.current = true;
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
        if (newModelId === activeModelId || isSwitchingModel || isRunning || isTypoRunning) return;
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
            if (!["all", "typo_only", "clean_only"].includes(selectedTypoFilter) && item.typo_type !== selectedTypoFilter) {
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

                // 判定ロジック
                let status: TypoItemResult["status"];
                const output = res.moshikashite_output || res.main_output;
                
                if (item.is_clean) {
                    // 正常入力の場合: 元の意図を破壊した余計な補正を行ったら False Positive
                    if (res.moshikashite_output && res.moshikashite_output !== item.expected && res.moshikashite_output !== res.main_output) {
                        status = "false_positive";
                    } else {
                        status = "clean_preserved";
                    }
                } else {
                    // タイポ入力の場合:
                    if (output === item.expected || res.main_output === item.expected || (res.moshikashite_output && res.moshikashite_output === item.expected)) {
                        status = "exact_match";
                    } else if (
                        output.includes(item.expected) || 
                        item.expected.includes(output) ||
                        res.main_output.replace(/\s/g, "") === item.expected.replace(/\s/g, "")
                    ) {
                        status = "variant_match";
                    } else {
                        status = "missed";
                    }
                }

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

    return (
        <div className="space-y-6 max-w-5xl mx-auto pb-12">
            <BenchmarkDepartureGuard running={isRunning || isTypoRunning} />
            {/* Tab Selector */}
            <div className="flex items-center gap-2 border-b pb-3">
                <button
                    type="button"
                    onClick={() => setBenchmarkTab("zenzai")}
                    className={`px-4 py-2 text-sm font-semibold rounded-lg transition-all cursor-pointer flex items-center gap-2 ${
                        benchmarkTab === "zenzai"
                            ? "bg-primary text-primary-foreground shadow-sm"
                            : "bg-muted text-muted-foreground hover:text-foreground"
                    }`}
                >
                    <Zap className="size-4" />
                    かな漢字変換 (480問: 基本230 + 高難度250)
                </button>
                <button
                    type="button"
                    onClick={() => setBenchmarkTab("typo")}
                    className={`px-4 py-2 text-sm font-semibold rounded-lg transition-all cursor-pointer flex items-center gap-2 ${
                        benchmarkTab === "typo"
                            ? "bg-primary text-primary-foreground shadow-sm"
                            : "bg-muted text-muted-foreground hover:text-foreground"
                    }`}
                >
                    <Layers className="size-4" />
                    メインモデル統合タイポ補正 (300問 / FP検証)
                </button>
            </div>

            {/* Header / Intro */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
                <div>
                    <h2 className="text-xl font-semibold tracking-tight text-foreground flex items-center gap-2.5">
                        <ZeroConvertLogo className="size-5 rounded-sm shadow-2xs" />
                        {benchmarkTab === "zenzai" 
                            ? "ZeroCon Model かな漢字変換ベンチマーク (480問)" 
                            : "メインモデル統合タイポ補正ベンチマーク (300問)"}
                    </h2>
                    <p className="text-xs text-muted-foreground mt-0.5">
                        {benchmarkTab === "zenzai"
                            ? "基本・文脈230問＋高難度文脈250問の計480問で、Nano v4 モデル直結推論の正解率とレイテンシを直接検証します。"
                            : "Nano v4 メインモデル統合タイポ補正（促音・撥音・転置・QWERTY隣接・母音抜け等255件）と正常入力誤補正抑止（45件）を実機測定します。"}
                    </p>
                </div>

                {/* Model Selector Pill */}
                <div className="flex items-center gap-1.5 p-1 rounded-xl bg-muted/60 border border-border/70 self-start sm:self-auto">
                    {models.map((model) => (
                        <button
                            key={model.id}
                            type="button"
                            disabled={isSwitchingModel || isRunning || isTypoRunning || !model.available}
                            onClick={() => handleSwitchModel(model.id)}
                            className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-all cursor-pointer ${
                                activeModelId === model.id
                                    ? "bg-card text-foreground shadow-xs font-semibold border border-border/80"
                                    : "text-muted-foreground hover:text-foreground"
                            }`}
                        >
                            <Cpu className="size-3.5" />
                            <span>{model.name}</span>
                            <span className="text-[10px] text-muted-foreground px-1 py-0.2 rounded bg-muted">
                                {model.available ? `${model.size_mb.toFixed(1)}MB` : "未配置"}
                            </span>
                            {activeModelId === model.id && <Check className="size-3 text-emerald-500 stroke-[2.5]" />}
                        </button>
                    ))}
                </div>
            </div>

            {/* TAB 1: かな漢字変換 (480問) */}
            {benchmarkTab === "zenzai" && (
                <>
                    {/* Metric KPI Cards */}
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>正解率 (Top 1 Accuracy)</span>
                                <CheckCircle2 className="size-3.5 text-emerald-500" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground">
                                {evaluatedCount > 0 ? `${overallAccuracy}%` : "—"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {evaluatedCount > 0 ? `${correctCount} / ${evaluatedCount} 問 正解` : "未評価"}
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>平均推論レイテンシ</span>
                                <Clock className="size-3.5 text-sky-500" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground">
                                {evaluatedCount > 0 ? `${avgLatency} ms` : "—"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                1文あたりの実効処理時間
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>評価進捗 ({SUBSETS.find((s) => s.id === selectedSubset)?.label})</span>
                                <Layers className="size-3.5 text-muted-foreground" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground">
                                {evaluatedCount} / {subsetItems.length}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {evaluatedCount === subsetItems.length ? "対象セット検証完了" : "テスト準備完了"}
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>選択モデル</span>
                                <SlidersHorizontal className="size-3.5 text-muted-foreground" />
                            </div>
                            <div className="text-lg font-bold tracking-tight text-foreground truncate">
                                {activeModel?.name ?? "未選択"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {activeModel ? `${activeModel.size_mb.toFixed(1)} MB` : "モデルを選択してください"}
                            </div>
                        </div>
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

                        {/* Subset Selector Pill */}
                        <div className="flex items-center gap-1 bg-muted p-1 rounded-lg border border-border/70 text-xs">
                            {SUBSETS.map((subset) => (
                                <button
                                    key={subset.id}
                                    type="button"
                                    onClick={() => {
                                        setSelectedSubset(subset.id);
                                        setSelectedCategory("すべて");
                                    }}
                                    className={`px-2.5 py-1 rounded-md font-medium transition-all cursor-pointer ${
                                        selectedSubset === subset.id
                                            ? "bg-card text-foreground shadow-2xs font-semibold"
                                            : "text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {subset.label}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Filter & Search Bar */}
                    <div className="flex flex-col sm:flex-row gap-3 items-stretch sm:items-center justify-between">
                        <div className="flex items-center gap-2 overflow-x-auto pb-1 sm:pb-0">
                            <span className="text-xs text-muted-foreground font-medium flex items-center gap-1 shrink-0">
                                <Filter className="size-3.5" />
                                カテゴリ:
                            </span>
                            <div className="flex gap-1.5 overflow-x-auto">
                                {availableCategories.slice(0, 8).map((cat) => (
                                    <button
                                        key={cat}
                                        type="button"
                                        onClick={() => setSelectedCategory(cat)}
                                        className={`px-2.5 py-1 text-xs rounded-lg transition-colors cursor-pointer shrink-0 ${
                                            selectedCategory === cat
                                                ? "bg-primary text-primary-foreground font-medium shadow-2xs"
                                                : "bg-muted/80 text-muted-foreground hover:text-foreground border border-border/40"
                                        }`}
                                    >
                                        {cat}
                                    </button>
                                ))}
                            </div>
                        </div>

                        <div className="flex items-center gap-2">
                            <div className="relative flex-1 sm:w-56">
                                <Search className="size-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
                                <input
                                    type="text"
                                    placeholder="文脈・読み・期待表記..."
                                    value={searchQuery}
                                    onChange={(e) => setSearchQuery(e.target.value)}
                                    className="w-full pl-8 pr-3 py-1.5 text-xs rounded-lg border border-border bg-card placeholder:text-muted-foreground/60 focus:outline-hidden focus:ring-1 focus:ring-primary"
                                />
                            </div>
                            <button
                                type="button"
                                onClick={() => setShowErrorsOnly(!showErrorsOnly)}
                                className={`px-2.5 py-1.5 text-xs rounded-lg border transition-colors cursor-pointer flex items-center gap-1.5 shrink-0 ${
                                    showErrorsOnly
                                        ? "bg-destructive/10 border-destructive/30 text-destructive font-medium"
                                        : "border-border text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                <XCircle className="size-3.5" />
                                不正解のみ
                            </button>
                        </div>
                    </div>

                    {/* Results Table */}
                    <div className="rounded-xl border border-border/70 overflow-hidden bg-card/60 shadow-xs">
                        <div className="overflow-x-auto">
                            <table className="w-full text-left text-xs border-collapse">
                                <thead>
                                    <tr className="border-b border-border/70 bg-muted/40 font-medium text-muted-foreground">
                                        <th className="p-3 w-12 text-center">#</th>
                                        <th className="p-3 w-24">セット</th>
                                        <th className="p-3 w-28">カテゴリ</th>
                                        <th className="p-3">文脈 (左側)</th>
                                        <th className="p-3">入力読み</th>
                                        <th className="p-3">期待表記 (正解)</th>
                                        <th className="p-3">モデル予測</th>
                                        <th className="p-3 w-20 text-right">遅延</th>
                                        <th className="p-3 w-20 text-center">判定</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/50">
                                    {filteredItems.slice(0, 100).map((item) => {
                                        const res = results[item.id];
                                        return (
                                            <tr key={item.id} className="hover:bg-muted/30 transition-colors">
                                                <td className="p-3 text-center text-muted-foreground font-mono">{item.id}</td>
                                                <td className="p-3">
                                                    <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium border ${
                                                        item.subset === "basic"
                                                            ? "bg-sky-500/10 border-sky-500/30 text-sky-700 dark:text-sky-300"
                                                            : "bg-purple-500/10 border-purple-500/30 text-purple-700 dark:text-purple-300"
                                                    }`}>
                                                        {item.subset === "basic" ? "基本230" : "高難度250"}
                                                    </span>
                                                </td>
                                                <td className="p-3 text-muted-foreground font-medium">{item.category}</td>
                                                <td className="p-3 text-muted-foreground max-w-xs truncate" title={item.left_context}>
                                                    {item.left_context || <span className="opacity-40">なし</span>}
                                                </td>
                                                <td className="p-3 font-semibold text-foreground">{item.target}</td>
                                                <td className="p-3 font-medium text-foreground">{item.expected}</td>
                                                <td className="p-3 font-medium">
                                                    {res ? (
                                                        <span className={res.is_correct ? "text-foreground" : "text-destructive font-semibold"}>
                                                            {res.top1}
                                                        </span>
                                                    ) : (
                                                        <span className="text-muted-foreground/30">—</span>
                                                    )}
                                                </td>
                                                <td className="p-3 text-right font-mono text-[11px] text-muted-foreground">
                                                    {res ? `${res.latency_ms.toFixed(0)} ms` : "—"}
                                                </td>
                                                <td className="p-3 text-center">
                                                    {res ? (
                                                        res.is_correct ? (
                                                            <CheckCircle2 className="size-4 text-emerald-500 mx-auto" />
                                                        ) : (
                                                            <XCircle className="size-4 text-rose-500 mx-auto" />
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
                        {filteredItems.length > 100 && (
                            <div className="p-2 text-center text-xs text-muted-foreground border-t bg-muted/20">
                                最初の100件を表示中（全 {filteredItems.length} 件）
                            </div>
                        )}
                    </div>
                </>
            )}

            {/* TAB 2: タイポ補正 (300問) */}
            {benchmarkTab === "typo" && (
                <div className="space-y-6">
                    {/* KPI Cards */}
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>タイポ正解率 (完全一致)</span>
                                <CheckCircle2 className="size-3.5 text-emerald-500" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground">
                                {typoCasesTested.length > 0 ? `${typoAccuracyExact}%` : "—"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {typoCasesTested.length > 0 ? `${exactMatches} / ${typoCasesTested.length} 問 (実質: ${typoAccuracyEffective}%)` : "未評価"}
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>正常入力 誤補正 (FP)</span>
                                <ShieldCheck className="size-3.5 text-sky-500" />
                            </div>
                            <div className={`text-2xl font-bold tracking-tight ${falsePositives > 0 ? "text-destructive" : "text-foreground"}`}>
                                {cleanCasesTested.length > 0 ? `${falsePositives} 件` : "—"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {cleanCasesTested.length > 0 ? `${cleanCasesTested.length - falsePositives} / ${cleanCasesTested.length} 正常保持 (誤補正率 ${fpRate}%)` : "45問のクリーン入力で検証"}
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>平均推論レイテンシ</span>
                                <Clock className="size-3.5 text-amber-500" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground font-mono">
                                {typoEvaluatedCount > 0 ? `${avgTypoLatency} ms` : "—"}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                メインモデル統合推論の実効遅延
                            </div>
                        </div>

                        <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                            <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                                <span>評価進捗</span>
                                <Layers className="size-3.5 text-muted-foreground" />
                            </div>
                            <div className="text-2xl font-bold tracking-tight text-foreground font-mono">
                                {typoEvaluatedCount} / {TYPO_ITEMS.length}
                            </div>
                            <div className="text-[11px] text-muted-foreground">
                                {typoEvaluatedCount === TYPO_ITEMS.length ? "全300件評価完了" : "300件ベンチマーク準備完了"}
                            </div>
                        </div>
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

                        {/* Filter Pill */}
                        <div className="flex items-center gap-1 bg-muted p-1 rounded-lg border border-border/70 text-xs overflow-x-auto max-w-full">
                            {TYPO_FILTER_TYPES.slice(0, 3).map((ft) => (
                                <button
                                    key={ft.id}
                                    type="button"
                                    onClick={() => setSelectedTypoFilter(ft.id)}
                                    className={`px-2.5 py-1 rounded-md font-medium transition-all cursor-pointer whitespace-nowrap ${
                                        selectedTypoFilter === ft.id
                                            ? "bg-card text-foreground shadow-2xs font-semibold"
                                            : "text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {ft.label}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Filter & Search Bar */}
                    <div className="flex flex-col sm:flex-row gap-3 items-stretch sm:items-center justify-between">
                        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0">
                            <span className="text-xs text-muted-foreground font-medium shrink-0">詳細種別:</span>
                            {TYPO_FILTER_TYPES.slice(3).map((ft) => (
                                <button
                                    key={ft.id}
                                    type="button"
                                    onClick={() => setSelectedTypoFilter(ft.id)}
                                    className={`px-2 py-0.5 text-xs rounded-md transition-colors cursor-pointer shrink-0 ${
                                        selectedTypoFilter === ft.id
                                            ? "bg-primary text-primary-foreground font-medium"
                                            : "bg-muted text-muted-foreground hover:text-foreground"
                                    }`}
                                >
                                    {ft.label}
                                </button>
                            ))}
                        </div>

                        <div className="flex items-center gap-2">
                            <div className="relative flex-1 sm:w-56">
                                <Search className="size-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
                                <input
                                    type="text"
                                    placeholder="文脈・入力・期待表記..."
                                    value={typoSearchQuery}
                                    onChange={(e) => setTypoSearchQuery(e.target.value)}
                                    className="w-full pl-8 pr-3 py-1.5 text-xs rounded-lg border border-border bg-card placeholder:text-muted-foreground/60 focus:outline-hidden focus:ring-1 focus:ring-primary"
                                />
                            </div>
                            <button
                                type="button"
                                onClick={() => setShowTypoErrorsOnly(!showTypoErrorsOnly)}
                                className={`px-2.5 py-1.5 text-xs rounded-lg border transition-colors cursor-pointer flex items-center gap-1.5 shrink-0 ${
                                    showTypoErrorsOnly
                                        ? "bg-destructive/10 border-destructive/30 text-destructive font-medium"
                                        : "border-border text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                <XCircle className="size-3.5" />
                                不正解/FPのみ
                            </button>
                        </div>
                    </div>

                    {/* Typo Results Table */}
                    <div className="rounded-xl border border-border/70 overflow-hidden bg-card/60 shadow-xs">
                        <div className="overflow-x-auto">
                            <table className="w-full text-left text-xs border-collapse">
                                <thead>
                                    <tr className="border-b border-border/70 bg-muted/40 font-medium text-muted-foreground">
                                        <th className="p-3 w-12 text-center">#</th>
                                        <th className="p-3 w-32">種別</th>
                                        <th className="p-3">文脈 (前文)</th>
                                        <th className="p-3">入力読み (タイポ)</th>
                                        <th className="p-3">期待表記 (正解)</th>
                                        <th className="p-3">補正出力</th>
                                        <th className="p-3 w-20 text-right">遅延</th>
                                        <th className="p-3 w-28 text-center">判定</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border/50">
                                    {filteredTypoItems.slice(0, 100).map((item) => {
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
        </div>
    );
}

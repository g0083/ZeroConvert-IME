import React, { useState, useMemo, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import rawBenchmarkData from "@/data/benchmark230.json";
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
    Check
} from "lucide-react";
import { ZeroConvertLogo } from "@/components/zeroconvert-logo";

export type BenchmarkItem = {
    id: number;
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

const ALL_CATEGORIES = [
    "すべて",
    ...Array.from(new Set(BENCHMARK_ITEMS.map((item) => item.category).filter(Boolean)))
];

export type AiAssistItemResult = {
    id: number;
    input: string;
    main_output: string;
    moshikashite_output?: string | null;
    predictive_output?: string | null;
    main_latency_ms: number;
    assist_latency_ms: number;
    total_latency_ms: number;
};

const AI_ASSIST_CASES = [
    { id: 1, category: "誤入力補正", context: "仕事がやっと終わった。同僚に", input: "おつくれさま", expected: "お疲れ様" },
    { id: 2, category: "誤入力補正", context: "お昼のチャットで、", input: "きんにちは", expected: "こんにちは" },
    { id: 3, category: "誤入力補正", context: "サポート宛のメールで、", input: "ありがとうごづいます", expected: "ありがとうございます" },
    { id: 4, category: "定型文先読み", context: "先ほど資料を添付いたしましたので、", input: "ご確認のほど", expected: "よろしくお願いいたします。" },
    { id: 5, category: "定型文先読み", context: "取引先へのメール冒頭で、", input: "いつもお世話に", expected: "なっております。" },
    { id: 6, category: "日常会話先読み", context: "業務終了の挨拶で、", input: "お疲れ様", expected: "でした。" },
    { id: 7, category: "自由文先読み", context: "GitHubの修正について、", input: "プルリクエストを", expected: "作成しました。" },
    { id: 8, category: "自由文先読み", context: "外は雨が激しく降っているので、", input: "傘を持って", expected: "行くのが良いです" },
    { id: 9, category: "通常入力", context: "学校の先生から", input: "しゅくだいがでた", expected: "宿題が出た" },
    { id: 10, category: "通常入力", context: "今日は天気が良いので、", input: "さんぽにいく", expected: "散歩に行く" },
    { id: 11, category: "複合誤字補正", context: "朝出社して、同僚に", input: "おあようございます", expected: "おはようございます" },
];

export function Benchmark() {
    const [benchmarkTab, setBenchmarkTab] = useState<"zenzai" | "ai_assist">("zenzai");
    const [aiAssistResults, setAiAssistResults] = useState<Record<number, AiAssistItemResult>>({});
    const [isAiAssistRunning, setIsAiAssistRunning] = useState(false);
    const stopAiAssistSignalRef = useRef(false);

    const [results, setResults] = useState<Record<number, ItemResult>>({});
    const [isRunning, setIsRunning] = useState(false);
    const [progressCount, setProgressCount] = useState(0);
    const [selectedCategory, setSelectedCategory] = useState("すべて");
    const [showErrorsOnly, setShowErrorsOnly] = useState(false);
    const [searchQuery, setSearchQuery] = useState("");
    const [expandedRows, setExpandedRows] = useState<Record<number, boolean>>({});
    const [activeModelId, setActiveModelId] = useState<string>("");
    const [models, setModels] = useState<ModelInfo[]>([]);
    const activeModel = models.find((model) => model.id === activeModelId);
    const [isSwitchingModel, setIsSwitchingModel] = useState(false);
    const stopSignalRef = useRef(false);

    // Cancel benchmark if user navigates away
    useEffect(() => {
        return () => {
            stopSignalRef.current = true;
        };
    }, []);

    // Load active model info
    useEffect(() => {
        invoke<ModelListResponse>("get_active_model").then((data) => {
            setActiveModelId(data.active_id);
            setModels(data.models);
        }).catch((err) => {
            console.error("Failed to load active model in benchmark:", err);
        });
    }, []);

    const handleSwitchModel = async (newModelId: string) => {
        if (newModelId === activeModelId || isSwitchingModel || isRunning || isAiAssistRunning) return;
        setIsSwitchingModel(true);
        const toastId = toast.loading("モデル切り替え中... (サーバー再起動)");
        try {
            await invoke("switch_model", { modelId: newModelId });
            setActiveModelId(newModelId);
            setResults({});
            setProgressCount(0);
            toast.success(`モデルを ${models.find((model) => model.id === newModelId)?.name ?? newModelId} に切り替えました`, { id: toastId });
        } catch (err) {
            toast.error(`モデル切り替えに失敗しました: ${err}`, { id: toastId });
        } finally {
            setIsSwitchingModel(false);
        }
    };

    // Filter items
    const filteredItems = useMemo(() => {
        return BENCHMARK_ITEMS.filter((item) => {
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
    }, [selectedCategory, showErrorsOnly, searchQuery, results]);

    // Statistics
    const evaluatedList = Object.values(results);
    const evaluatedCount = evaluatedList.length;
    const correctCount = evaluatedList.filter((r) => r.is_correct).length;
    const overallAccuracy = evaluatedCount > 0 ? ((correctCount / evaluatedCount) * 100).toFixed(1) : "0.0";
    const avgLatency = evaluatedCount > 0 
        ? (evaluatedList.reduce((acc, r) => acc + r.latency_ms, 0) / evaluatedCount).toFixed(1) 
        : "0.0";

    // Category accuracy map
    const categoryStats = useMemo(() => {
        const stats: Record<string, { total: number; tested: number; correct: number }> = {};
        for (const cat of ALL_CATEGORIES) {
            stats[cat] = { total: 0, tested: 0, correct: 0 };
        }
        for (const item of BENCHMARK_ITEMS) {
            stats["すべて"].total++;
            const cat = item.category || "その他";
            if (!stats[cat]) {
                stats[cat] = { total: 0, tested: 0, correct: 0 };
            }
            stats[cat].total++;
            const res = results[item.id];
            if (res) {
                stats["すべて"].tested++;
                stats[cat].tested++;
                if (res.is_correct) {
                    stats["すべて"].correct++;
                    stats[cat].correct++;
                }
            }
        }
        return stats;
    }, [results]);

    const runItem = async (item: BenchmarkItem): Promise<ItemResult> => {
        try {
            // Strip placeholder 'なし' context to match standalone infill benchmark
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

    const handleRunAiAssist = async () => {
        if (isAiAssistRunning) return;
        setIsAiAssistRunning(true);
        stopAiAssistSignalRef.current = false;
        setAiAssistResults({});

        const toastId = toast.loading(`AIアシスト ベンチマーク実行中 (ZeroCon)...`);
        try {
            for (const item of AI_ASSIST_CASES) {
                if (stopAiAssistSignalRef.current) break;
                const res = await invoke<AiAssistItemResult>("run_ai_assist_benchmark_item", {
                    id: item.id,
                    context: item.context,
                    input: item.input,
                });
                setAiAssistResults((prev) => ({ ...prev, [item.id]: res }));
            }
            toast.success("AIアシスト ベンチマーク完了！", { id: toastId });
        } catch (err) {
            toast.error(`実行エラー: ${err}`, { id: toastId });
        } finally {
            setIsAiAssistRunning(false);
        }
    };

    const handleRunBenchmark = async (itemsToRun: BenchmarkItem[]) => {
        if (isRunning) return;
        setIsRunning(true);
        stopSignalRef.current = false;

        let count = 0;
        for (const item of itemsToRun) {
            if (stopSignalRef.current) break;
            const res = await runItem(item);
            setResults((prev) => ({ ...prev, [item.id]: res }));
            count++;
            setProgressCount(count);
        }

        setIsRunning(false);
    };

    const handleStop = () => {
        stopSignalRef.current = true;
        setIsRunning(false);
    };

    const handleReset = () => {
        stopSignalRef.current = true;
        setIsRunning(false);
        setResults({});
        setProgressCount(0);
    };

    const toggleRow = (id: number) => {
        setExpandedRows((prev) => ({ ...prev, [id]: !prev[id] }));
    };

    return (
        <div className="space-y-6 max-w-5xl mx-auto pb-12">
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
                    ZeroCon AI かな漢字変換 (230問)
                </button>
                <button
                    type="button"
                    onClick={() => setBenchmarkTab("ai_assist")}
                    className={`px-4 py-2 text-sm font-semibold rounded-lg transition-all cursor-pointer flex items-center gap-2 ${
                        benchmarkTab === "ai_assist"
                            ? "bg-primary text-primary-foreground shadow-sm"
                            : "bg-muted text-muted-foreground hover:text-foreground"
                    }`}
                >
                    <Layers className="size-4" />
                    💡 もしかして & 🔮 先読みレイテンシ (実機測定)
                </button>
            </div>

            {/* Header / Intro */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
                <div>
                    <h2 className="text-xl font-semibold tracking-tight text-foreground flex items-center gap-2.5">
                        <ZeroConvertLogo className="size-5 rounded-sm shadow-2xs" />
                        {benchmarkTab === "zenzai" ? "AIモデル直接推論ベンチマーク" : "AIアシスト (💡もしかして / 🔮先読み) レイテンシ測定"}
                    </h2>
                    <p className="text-xs text-muted-foreground mt-0.5">
                        {benchmarkTab === "zenzai"
                            ? "230 問の難関評価データセットで純粋なAIモデル直結推論の変換精度とGPUレイテンシを直接検証します。"
                            : "ZeroCon Modelによる変換・先読みと、規則による補正を測定します。このベンチマークは corecctor（ZeroCon Model Corrector）の補正精度・応答時間を測定しません。"}
                    </p>
                </div>

                {/* Model Selector Pill */}
                <div className="flex items-center gap-1.5 p-1 rounded-xl bg-muted/60 border border-border/70 self-start sm:self-auto">
                    {models.map((model) => <button
                        key={model.id}
                        type="button"
                        disabled={isSwitchingModel || isRunning || isAiAssistRunning || !model.available}
                        onClick={() => handleSwitchModel(model.id)}
                        className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-all cursor-pointer ${
                            activeModelId === model.id
                                ? "bg-card text-foreground shadow-xs font-semibold border border-border/80"
                                : "text-muted-foreground hover:text-foreground"
                        }`}
                    >
                        <Cpu className="size-3.5" />
                        <span>{model.name}</span>
                        <span className="text-[10px] text-muted-foreground px-1 py-0.2 rounded bg-muted">{model.available ? `${model.size_mb.toFixed(1)}MB` : "未配置"}</span>
                        {activeModelId === model.id && <Check className="size-3 text-emerald-500 stroke-[2.5]" />}
                    </button>)}
                </div>
            </div>

            {benchmarkTab === "zenzai" ? (
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
                        <span>評価進捗</span>
                        <Layers className="size-3.5 text-muted-foreground" />
                    </div>
                    <div className="text-2xl font-bold tracking-tight text-foreground">
                        {evaluatedCount} / {BENCHMARK_ITEMS.length}
                    </div>
                    <div className="text-[11px] text-muted-foreground">
                        {evaluatedCount === BENCHMARK_ITEMS.length ? "全件検証完了" : "テスト準備完了"}
                    </div>
                </div>

                <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                    <div className="text-[11px] font-medium text-muted-foreground flex items-center justify-between">
                        <span>選択モデル</span>
                        <SlidersHorizontal className="size-3.5 text-muted-foreground" />
                    </div>
                    <div className="text-lg font-bold tracking-tight text-foreground truncate">
                        {activeModel?.name ?? "選択肢外のモデル"}
                    </div>
                    <div className="text-[11px] text-muted-foreground">
                        {activeModel ? `${activeModel.size_mb.toFixed(1)} MB` : "Standard または Nano を選択してください"}
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
                                onClick={() => handleRunBenchmark(BENCHMARK_ITEMS)}
                                className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-foreground text-background shadow-xs hover:opacity-90 transition-opacity cursor-pointer"
                            >
                                <Play className="size-3.5 fill-current" />
                                全 230 件ベンチマーク開始
                            </button>
                            <button
                                type="button"
                                onClick={() => handleRunBenchmark(BENCHMARK_ITEMS.slice(0, 10))}
                                className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border border-border bg-card hover:bg-muted/60 transition-colors cursor-pointer"
                            >
                                <Zap className="size-3.5 text-amber-500" />
                                クイック検証 (10件)
                            </button>
                        </>
                    ) : (
                        <button
                            type="button"
                            onClick={handleStop}
                            className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold rounded-lg bg-rose-600 text-white shadow-xs hover:bg-rose-700 transition-colors cursor-pointer"
                        >
                            <Square className="size-3.5 fill-current" />
                            テスト停止
                        </button>
                    )}

                    {evaluatedCount > 0 && !isRunning && (
                        <button
                            type="button"
                            onClick={handleReset}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
                        >
                            <RotateCcw className="size-3.5" />
                            結果リセット
                        </button>
                    )}
                </div>

                <div className="flex items-center gap-3">
                    <label className="flex items-center gap-1.5 text-xs text-muted-foreground cursor-pointer select-none">
                        <input
                            type="checkbox"
                            checked={showErrorsOnly}
                            onChange={(e) => setShowErrorsOnly(e.target.checked)}
                            className="rounded border-border text-foreground focus:ring-1 focus:ring-ring"
                        />
                        <span>不正解のみ表示</span>
                    </label>

                    <div className="relative">
                        <Search className="size-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
                        <input
                            type="text"
                            placeholder="検索..."
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            className="pl-8 pr-3 py-1.5 text-xs rounded-lg border border-border bg-card text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring w-36 sm:w-48"
                        />
                    </div>
                </div>
            </div>

            {/* Progress Bar when running */}
            {isRunning && (
                <div className="space-y-1.5">
                    <div className="flex justify-between text-xs text-muted-foreground">
                        <span>ベンチマーク実行中...</span>
                        <span>{progressCount} / {BENCHMARK_ITEMS.length}</span>
                    </div>
                    <div className="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                        <div 
                            className="h-full bg-foreground transition-all duration-150"
                            style={{ width: `${(progressCount / BENCHMARK_ITEMS.length) * 100}%` }}
                        />
                    </div>
                </div>
            )}

            {/* Category Filter Pills */}
            <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                {ALL_CATEGORIES.map((cat) => {
                    const st = categoryStats[cat];
                    const isSelected = selectedCategory === cat;
                    const catAcc = st && st.tested > 0 ? `${((st.correct / st.tested) * 100).toFixed(0)}%` : null;

                    return (
                        <button
                            key={cat}
                            type="button"
                            onClick={() => setSelectedCategory(cat)}
                            className={`px-3 py-1 rounded-lg text-xs font-medium whitespace-nowrap transition-colors cursor-pointer flex items-center gap-1.5 ${
                                isSelected
                                    ? "bg-foreground text-background"
                                    : "bg-muted/50 text-muted-foreground hover:bg-muted hover:text-foreground border border-border/40"
                            }`}
                        >
                            <span>{cat}</span>
                            {catAcc && (
                                <span className={`text-[10px] px-1 py-0.2 rounded font-semibold ${
                                    isSelected 
                                        ? "bg-background/20 text-background" 
                                        : "bg-muted text-foreground"
                                }`}>
                                    {catAcc}
                                </span>
                            )}
                        </button>
                    );
                })}
            </div>

            {/* Benchmark Table */}
            <div className="rounded-xl border border-border/70 overflow-hidden bg-card/60 shadow-xs">
                <div className="overflow-x-auto">
                    <table className="w-full text-left text-xs border-collapse">
                        <thead>
                            <tr className="border-b border-border/70 bg-muted/40 font-medium text-muted-foreground">
                                <th className="p-3 w-12 text-center">#</th>
                                <th className="p-3 w-28">カテゴリ</th>
                                <th className="p-3">入力文脈 (前文)</th>
                                <th className="p-3">読み (ひらがな)</th>
                                <th className="p-3">期待される変換</th>
                                <th className="p-3">モデル予測 (Top 1)</th>
                                <th className="p-3 w-20 text-center">判定</th>
                                <th className="p-3 w-20 text-right">遅延</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border/50">
                            {filteredItems.map((item) => {
                                const res = results[item.id];
                                const isExpanded = !!expandedRows[item.id];

                                return (
                                    <React.Fragment key={item.id}>
                                        <tr 
                                            onClick={() => toggleRow(item.id)}
                                            className="hover:bg-muted/30 transition-colors cursor-pointer group"
                                        >
                                            <td className="p-3 text-center text-muted-foreground font-mono">
                                                {item.id}
                                            </td>
                                            <td className="p-3 text-muted-foreground truncate max-w-[120px]">
                                                {item.category}
                                            </td>
                                            <td className="p-3 text-foreground/80 font-normal">
                                                {item.left_context === "なし" ? (
                                                    <span className="text-muted-foreground/60 italic text-[11px]">— なし —</span>
                                                ) : (
                                                    item.left_context
                                                )}
                                            </td>
                                            <td className="p-3 font-mono font-medium text-foreground">
                                                {item.target}
                                            </td>
                                            <td className="p-3 font-medium text-emerald-600 dark:text-emerald-400">
                                                {item.expected}
                                            </td>
                                            <td className="p-3 font-medium">
                                                {res ? (
                                                    <span className={res.is_correct ? "text-foreground" : "text-rose-500 line-through"}>
                                                        {res.top1}
                                                    </span>
                                                ) : (
                                                    <span className="text-muted-foreground/40 font-mono text-[11px]">—</span>
                                                )}
                                            </td>
                                            <td className="p-3 text-center">
                                                {res ? (
                                                    res.is_correct ? (
                                                        <span className="inline-flex items-center gap-1 text-[11px] text-emerald-500 font-semibold">
                                                            <CheckCircle2 className="size-3.5" /> 正解
                                                        </span>
                                                    ) : (
                                                        <span className="inline-flex items-center gap-1 text-[11px] text-rose-500 font-semibold">
                                                            <XCircle className="size-3.5" /> 誤り
                                                        </span>
                                                    )
                                                ) : (
                                                    <span className="text-muted-foreground/40 text-[11px]">未</span>
                                                )}
                                            </td>
                                            <td className="p-3 text-right font-mono text-muted-foreground">
                                                {res ? `${res.latency_ms.toFixed(1)}ms` : "—"}
                                            </td>
                                        </tr>

                                        {/* Expanded Candidates Drawer */}
                                        {isExpanded && res && (
                                            <tr className="bg-muted/20">
                                                <td colSpan={8} className="p-3 border-t border-dashed border-border/60">
                                                    <div className="space-y-1.5 pl-6">
                                                        <div className="text-[11px] font-semibold text-muted-foreground">
                                                            生成された候補一覧 ({res.candidates.length} 件):
                                                        </div>
                                                        <div className="flex flex-wrap gap-1.5">
                                                            {res.candidates.map((cand, idx) => (
                                                                <span
                                                                    key={idx}
                                                                    className={`px-2 py-0.5 rounded text-xs border font-mono ${
                                                                        idx === 0 
                                                                            ? "bg-foreground/10 border-foreground/30 font-semibold text-foreground"
                                                                            : "bg-card border-border/70 text-muted-foreground"
                                                                    }`}
                                                                >
                                                                    {idx + 1}. {cand}
                                                                </span>
                                                            ))}
                                                        </div>
                                                    </div>
                                                </td>
                                            </tr>
                                        )}
                                    </React.Fragment>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            </div>
            </>
            ) : (
            /* AI Assist Benchmark Tab Content */
            <div className="space-y-6">
                {/* Controls Bar */}
                <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                    <div className="flex items-center gap-3">
                        <span className="text-xs font-semibold text-muted-foreground">先読みモデル:</span>
                        <span className="text-sm font-medium">ZeroCon Model</span>
                    </div>

                    <div className="flex items-center gap-2">
                        {isAiAssistRunning ? (
                            <button
                                type="button"
                                onClick={() => { stopAiAssistSignalRef.current = true; }}
                                className="px-4 py-2 bg-destructive text-destructive-foreground hover:bg-destructive/90 rounded-lg text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer shadow-xs"
                            >
                                <Square className="size-3.5 fill-current" />
                                停止
                            </button>
                        ) : (
                            <button
                                type="button"
                                onClick={handleRunAiAssist}
                                className="px-4 py-2 bg-primary text-primary-foreground hover:bg-primary/90 rounded-lg text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer shadow-xs"
                            >
                                <Play className="size-3.5 fill-current" />
                                ベンチマーク実行 (ZeroCon)
                            </button>
                        )}
                        <button
                            type="button"
                            disabled={isAiAssistRunning}
                            onClick={() => setAiAssistResults({})}
                            className="p-2 border border-border/80 hover:bg-muted text-muted-foreground rounded-lg transition-colors cursor-pointer"
                            title="結果リセット"
                        >
                            <RotateCcw className="size-3.5" />
                        </button>
                    </div>
                </div>

                {/* KPI Cards */}
                {(() => {
                    const resList = Object.values(aiAssistResults);
                    const tested = resList.length;
                    const avgMain = tested > 0 ? (resList.reduce((acc, r) => acc + r.main_latency_ms, 0) / tested) : 0;
                    const avgAssist = tested > 0 ? (resList.reduce((acc, r) => acc + r.assist_latency_ms, 0) / tested) : 0;
                    const avgTotal = tested > 0 ? (resList.reduce((acc, r) => acc + r.total_latency_ms, 0) / tested) : 0;

                    return (
                        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
                            <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                                <div className="text-[11px] font-medium text-muted-foreground">進捗 / 測定数</div>
                                <div className="text-xl font-bold font-mono">{tested} / {AI_ASSIST_CASES.length} 問</div>
                            </div>
                            <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                                <div className="text-[11px] font-medium text-muted-foreground">平均メイン変換遅延</div>
                                <div className="text-xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
                                    {tested > 0 ? `${avgMain.toFixed(1)} ms` : "—"}
                                </div>
                            </div>
                            <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                                <div className="text-[11px] font-medium text-muted-foreground">平均アシスト処理時間</div>
                                <div className="text-xl font-bold font-mono text-amber-600 dark:text-amber-400">
                                    {tested > 0 ? `${avgAssist.toFixed(1)} ms` : "—"}
                                </div>
                            </div>
                            <div className="p-4 rounded-xl border border-border/70 bg-card/60 shadow-xs space-y-1">
                                <div className="text-[11px] font-medium text-muted-foreground">平均総レイテンシ</div>
                                <div className="text-xl font-bold font-mono text-indigo-600 dark:text-indigo-400">
                                    {tested > 0 ? `${avgTotal.toFixed(1)} ms` : "—"}
                                </div>
                            </div>
                        </div>
                    );
                })()}

                {/* Results Table */}
                <div className="rounded-xl border border-border/70 overflow-hidden bg-card/60 shadow-xs">
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-xs border-collapse">
                            <thead>
                                <tr className="border-b border-border/70 bg-muted/40 font-medium text-muted-foreground">
                                    <th className="p-3 w-12 text-center">#</th>
                                    <th className="p-3 w-28">カテゴリ</th>
                                    <th className="p-3">入力文字列</th>
                                    <th className="p-3">第1候補 (入力通り)</th>
                                    <th className="p-3">💡 もしかして (誤字補正)</th>
                                    <th className="p-3">🔮 先読み (予測補完)</th>
                                    <th className="p-3 w-28 text-right">遅延 (主 / 予 / 総)</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border/50">
                                {AI_ASSIST_CASES.map((item) => {
                                    const res = aiAssistResults[item.id];
                                    return (
                                        <tr key={item.id} className="hover:bg-muted/30 transition-colors">
                                            <td className="p-3 text-center text-muted-foreground font-mono">{item.id}</td>
                                            <td className="p-3 text-muted-foreground font-medium">{item.category}</td>
                                            <td className="p-3 font-semibold text-foreground">{item.input}</td>
                                            <td className="p-3 font-mono font-medium text-foreground">
                                                {res ? res.main_output : "—"}
                                            </td>
                                            <td className="p-3">
                                                {res?.moshikashite_output ? (
                                                    <span className="px-2 py-0.5 rounded-md bg-amber-500/10 border border-amber-500/30 text-amber-700 dark:text-amber-300 font-semibold text-xs">
                                                        💡 {res.moshikashite_output}
                                                    </span>
                                                ) : (
                                                    <span className="text-muted-foreground/30 text-[11px]">—</span>
                                                )}
                                            </td>
                                            <td className="p-3">
                                                {res?.predictive_output ? (
                                                    <span className="px-2 py-0.5 rounded-md bg-indigo-500/10 border border-indigo-500/30 text-indigo-700 dark:text-indigo-300 font-semibold text-xs">
                                                        🔮 {res.predictive_output}
                                                    </span>
                                                ) : (
                                                    <span className="text-muted-foreground/30 text-[11px]">—</span>
                                                )}
                                            </td>
                                            <td className="p-3 text-right font-mono text-[11px] text-muted-foreground whitespace-nowrap">
                                                {res ? (
                                                    <span>
                                                        <span className="text-emerald-600 dark:text-emerald-400">{res.main_latency_ms.toFixed(0)}</span> /{" "}
                                                        <span className="text-amber-600 dark:text-amber-400">{res.assist_latency_ms.toFixed(0)}</span> /{" "}
                                                        <span className="font-semibold text-foreground">{res.total_latency_ms.toFixed(0)}ms</span>
                                                    </span>
                                                ) : "—"}
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

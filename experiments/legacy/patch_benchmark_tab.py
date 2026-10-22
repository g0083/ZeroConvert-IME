# -*- coding: utf-8 -*-
import sys

with open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 型定義とケース定義
target_header = """export function Benchmark() {
    const [results, setResults] = useState<Record<number, ItemResult>>({});"""

replacement_header = """export type AiAssistItemResult = {
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
];

export function Benchmark() {
    const [benchmarkTab, setBenchmarkTab] = useState<"zenzai" | "ai_assist">("zenzai");
    const [aiAssistModel, setAiAssistModel] = useState<string>("230m_q4km");
    const [aiAssistResults, setAiAssistResults] = useState<Record<number, AiAssistItemResult>>({});
    const [isAiAssistRunning, setIsAiAssistRunning] = useState(false);
    const stopAiAssistSignalRef = useRef(false);

    const [results, setResults] = useState<Record<number, ItemResult>>({});"""

assert target_header in content, "target_header not found"
content = content.replace(target_header, replacement_header, 1)

# 2. handleRunAiAssist を追加
target_handle = """    const handleRunBenchmark = async (itemsToRun: BenchmarkItem[]) => {"""

replacement_handle = """    const handleRunAiAssist = async () => {
        if (isAiAssistRunning) return;
        setIsAiAssistRunning(true);
        stopAiAssistSignalRef.current = false;
        setAiAssistResults({});

        const toastId = toast.loading(`AIアシスト ベンチマーク実行中 (${aiAssistModel})...`);
        try {
            for (const item of AI_ASSIST_CASES) {
                if (stopAiAssistSignalRef.current) break;
                const res = await invoke<AiAssistItemResult>("run_ai_assist_benchmark_item", {
                    id: item.id,
                    context: item.context,
                    input: item.input,
                    modelKey: aiAssistModel,
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

    const handleRunBenchmark = async (itemsToRun: BenchmarkItem[]) => {"""

assert target_handle in content, "target_handle not found"
content = content.replace(target_handle, replacement_handle, 1)

# 3. UIのタブスイッチを追加（ヘッダー部分）
target_ui_header = """            {/* Header / Intro */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b pb-4">
                <div>
                    <h2 className="text-xl font-semibold tracking-tight text-foreground flex items-center gap-2.5">
                        <ZeroConvertLogo className="size-5 rounded-sm shadow-2xs" />
                        AIモデル直接推論ベンチマーク
                    </h2>
                    <p className="text-xs text-muted-foreground mt-0.5">
                        230 問の難関評価データセットで純粋なAIモデル直結推論の変換精度とGPUレイテンシを直接検証します（辞書干渉なし・Android版同基準）。
                    </p>
                </div>"""

replacement_ui_header = """            {/* Tab Selector */}
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
                            : "メイン変換は入力通りを100%維持しつつ、AIによる誤字補正とLFM先読みの応答速度をリアルタイム測定します。"}
                    </p>
                </div>"""

assert target_ui_header in content, "target_ui_header not found"
content = content.replace(target_ui_header, replacement_ui_header, 1)

with open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("Part 2 patched successfully!")

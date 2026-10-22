# -*- coding: utf-8 -*-
import sys

content = open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'r', encoding='utf-8').read()

# タブとAIアシスト状態を追加
target_header = """export function Benchmark() {"""

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
    const stopAiAssistSignalRef = useRef(false);"""

assert target_header in content, "target_header not found"
content = content.replace(target_header, replacement_header, 1)

# AIアシスト実行関数を追加
target_run_all = """    const handleRunAll = async () => {"""

replacement_run_all = """    const handleRunAiAssist = async () => {
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

    const handleRunAll = async () => {"""

assert target_run_all in content, "target_run_all not found"
content = content.replace(target_run_all, replacement_run_all, 1)

with open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("Part 1 patched!")

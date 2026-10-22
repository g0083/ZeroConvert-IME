# -*- coding: utf-8 -*-
with open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# benchmarkTab === "zenzai" と "ai_assist" の分岐を作成
target_render_start = """            {/* Metric KPI Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">"""

replacement_render_start = """            {benchmarkTab === "zenzai" ? (
            <>
            {/* Metric KPI Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">"""

target_render_end = """                    </table>
                </div>
            </div>
        </div>
    );
}"""

replacement_render_end = """                    </table>
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
                        <div className="flex items-center gap-1.5 p-1 rounded-lg bg-muted border border-border">
                            <button
                                type="button"
                                disabled={isAiAssistRunning}
                                onClick={() => setAiAssistModel("230m_q4km")}
                                className={`px-3 py-1 text-xs rounded-md font-medium transition-all cursor-pointer ${
                                    aiAssistModel === "230m_q4km"
                                        ? "bg-card text-foreground shadow-xs font-semibold"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                LFM 230M (Q4_K_M)
                            </button>
                            <button
                                type="button"
                                disabled={isAiAssistRunning}
                                onClick={() => setAiAssistModel("350m_q4km")}
                                className={`px-3 py-1 text-xs rounded-md font-medium transition-all cursor-pointer ${
                                    aiAssistModel === "350m_q4km"
                                        ? "bg-card text-foreground shadow-xs font-semibold"
                                        : "text-muted-foreground hover:text-foreground"
                                }`}
                            >
                                LFM 350M (Q4_K_M)
                            </button>
                        </div>
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
                                ベンチマーク実行 ({aiAssistModel})
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
                                <div className="text-[11px] font-medium text-muted-foreground">平均先読みオーバーヘッド</div>
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
}"""

assert target_render_start in content, "target_render_start not found"
assert target_render_end in content, "target_render_end not found"

content = content.replace(target_render_start, replacement_render_start, 1)
content = content.replace(target_render_end, replacement_render_end, 1)

with open('scratch/batao_src/frontend/src/pages/benchmark.tsx', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("Benchmark view patched successfully!")

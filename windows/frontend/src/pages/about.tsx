import { ShieldCheck, Zap } from "lucide-react";
import { SettingsRow, SettingsSection } from "@/components/settings-section";
import { ZeroConvertLogo } from "@/components/zeroconvert-logo";

export const About = () => {
    return (
        <div className="settings-content space-y-6 max-w-4xl pb-8">
            <div className="flex items-center gap-4 p-5 rounded-2xl border border-border/80 bg-card/60 shadow-xs">
                <ZeroConvertLogo className="size-14 rounded-xl shadow-xs shrink-0" />
                <div className="space-y-1">
                    <h2 className="text-lg font-semibold tracking-tight text-foreground flex items-center gap-2">
                        ZeroCon IME (Windows)
                        <span className="text-[10px] font-mono font-medium px-2 py-0.5 rounded-full bg-muted text-muted-foreground border border-border/60">
                            v0.2.2
                        </span>
                    </h2>
                    <p className="text-xs text-muted-foreground">
                        ZeroCon Model Nano v4 によるかな漢字変換・先読みと、メインモデル統合による高速タイポ補正
                    </p>
                </div>
            </div>

            <SettingsSection title="搭載モデルと動作環境">
                <SettingsRow label="かな漢字変換・先読みモデル" description="Nano v4：省メモリのIQ4_NL_emb8（18.7 MiB）と高精度のQ8_0（27.7 MiB）。タイポ補正・先読みも1モデルで統合処理">
                    <span className="text-xs font-mono font-medium text-foreground bg-muted/60 px-2.5 py-1 rounded-md border border-border/50">
                        ZeroCon Model Nano v4 IQ4_emb8 / Q8
                    </span>
                </SettingsRow>
                <SettingsRow label="タイポ補正アーキテクチャ" description="独立Corrector常駐を撤廃し、Nano v4 内部で読み尤度判定・表層リランキング・漢字変換を一気通貫処理（正解率78.0% / 実質86.7%、CPU p50: 2.3ms）">
                    <span className="text-sm font-medium text-foreground">メインモデル統合タイポ補正 (v0.2.2)</span>
                </SettingsRow>
                <SettingsRow label="推論バックエンド" description="設定とPCの対応状況に応じて選択">
                    <div className="flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                        <Zap className="size-3.5" />
                        <span>CPU / Vulkan / NVIDIA CUDA</span>
                    </div>
                </SettingsRow>
                <SettingsRow label="通信アーキテクチャ" description="プロセス間高速通信">
                    <span className="text-xs text-muted-foreground">Windows Named Pipe (LOCAL) gRPC</span>
                </SettingsRow>
                <SettingsRow label="入力フレームワーク" description="TSF (Text Services Framework)">
                    <span className="text-xs text-muted-foreground">64-bit / 32-bit TSF TIP サポート</span>
                </SettingsRow>
            </SettingsSection>

            <SettingsSection title="オープンソースライセンスと謝辞">
                <div className="p-4 space-y-3 text-xs text-muted-foreground leading-relaxed">
                    <div className="flex items-center gap-2 text-foreground font-medium">
                        <ShieldCheck className="size-4 text-emerald-500" />
                        <span>利用しているプロジェクトとモデル</span>
                    </div>
                    <p>
                        ZeroCon IME は、以下のオープンソースソフトウェア・言語モデル・辞書データの成果物を活用し、独自の高速化・ZeroCon AI 直結推論・ストリーミング自動確定・UIリデザインを施した派生ソフトウェアです。
                    </p>
                </div>
                <SettingsRow label="azooKey-Windows / AzooKeyKanaKanjiConverter" description="Copyright (c) ensan-hcl / batao9">
                    <span className="text-xs font-mono text-muted-foreground">MIT License</span>
                </SettingsRow>
                <SettingsRow label="Zenz / zenz-v3.2 (ZeroCon Model ベースモデル)" description="Copyright (c) Miwa Keita (ensan-hcl)">
                    <span className="text-xs font-mono text-muted-foreground">Apache-2.0</span>
                </SettingsRow>
                <SettingsRow label="zenz-v3.1-xsmall（corecctor のベースモデル）" description="Miwa Keita (ensan-hcl)。独自の追加学習・Q4_K_M量子化を施した ZeroCon Model Corrector も同ライセンスで配布します。">
                    <span className="text-xs font-mono text-muted-foreground">CC BY-SA 4.0</span>
                </SettingsRow>
                <SettingsRow label="Dictionary (かな漢字変換辞書データ)" description="azooKey Dictionary / Mozc / IPAdic">
                    <span className="text-xs font-mono text-muted-foreground">Apache-2.0 / BSD-3-Clause</span>
                </SettingsRow>
                <SettingsRow label="llama.cpp (GGML / GGUF 推論ランタイム)" description="Georgi Gerganov et al.">
                    <span className="text-xs font-mono text-muted-foreground">MIT License</span>
                </SettingsRow>
                <SettingsRow label="ライセンス全文">
                    <span className="text-xs font-mono text-muted-foreground">THIRD_PARTY_LICENSES.md</span>
                </SettingsRow>
            </SettingsSection>
        </div>
    );
};

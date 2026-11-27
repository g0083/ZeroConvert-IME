import { useState } from "react";
import { ShieldCheck, Zap } from "lucide-react";
import licenses from "../../../../licenses/third-party.json";
import useTermsText from "../../../deployment/USE-TERMS.txt?raw";
import apacheText from "../../../deployment/LICENSE-Apache-2.0.txt?raw";
import swiftText from "../../../deployment/LICENSE-Swift.txt?raw";
import unicodeText from "../../../deployment/LICENSE-Unicode.txt?raw";
import thirdPartyText from "../../../deployment/THIRD_PARTY_LICENSES.md?raw";
import rustText from "../../../deployment/THIRD_PARTY_RUST.md?raw";
import frontendText from "../../../deployment/THIRD_PARTY_FRONTEND.md?raw";
import sudachiLegalText from "../../../../data/dictionary/AdditionalDictionaryNotices/LEGAL?raw";
import wikidataText from "../../../../data/dictionary/AdditionalDictionaryNotices/Wikidata.txt?raw";
import { Button } from "@/components/ui/button";
import { SettingsRow, SettingsSection } from "@/components/settings-section";
import { useUpdates } from "@/components/update-provider";
import { ZeroConvertLogo } from "@/components/zeroconvert-logo";

const fullTexts: Record<string, { label: string; text: string }> = {
    "sudachidict-legal": { label: "SudachiDict / UniDic / NEologd（上流の LEGAL）", text: sudachiLegalText },
    "wikidata-cc0": { label: "Wikidata（CC0 1.0・出典）", text: wikidataText },
    apache: { label: "Apache License 2.0（全文）", text: apacheText },
    swift: { label: "Swift Runtime Exception（全文）", text: swiftText },
    unicode: { label: "Unicode License V3（全文）", text: unicodeText },
    "third-party": { label: "THIRD_PARTY_LICENSES.md（全文）", text: thirdPartyText },
    rust: { label: "Rust クレート一覧（476 クレート）", text: rustText },
    frontend: { label: "フロントエンド依存一覧（95 パッケージ）", text: frontendText },
    mit: { label: "MIT License（azooKey / llama.cpp）", text: thirdPartyText },
    mozc: { label: "Mozc 辞書データ・補正規則（BSD-3-Clause）", text: thirdPartyText },
    llvm: { label: "LLVM（Apache-2.0）", text: apacheText },
};

export const About = () => {
    const { version, result, status, error, check, start } = useUpdates();
    const [selectedFullText, setSelectedFullText] = useState<string>("apache");
    const busy = status === "checking" || status === "starting";
    const currentVersion = version ?? result?.current_version ?? null;
    return (
        <div className="settings-content space-y-6 max-w-4xl pb-8">
            <div className="flex items-center gap-4 p-5 rounded-2xl border border-border/80 bg-card/60 shadow-xs">
                <ZeroConvertLogo className="size-14 rounded-xl shadow-xs shrink-0" />
                <div className="space-y-1">
                    <h2 className="text-lg font-semibold tracking-tight text-foreground flex items-center gap-2">
                        ZeroCon IME (Windows)
                        <span className="text-[10px] font-mono font-medium px-2 py-0.5 rounded-full bg-muted text-muted-foreground border border-border/60">
                            {currentVersion ? `v${currentVersion}` : "バージョン不明"}
                        </span>
                    </h2>
                    <p className="text-xs text-muted-foreground">
                        ZeroCon Model v4.2b によるかな漢字変換・先読みと、メインモデル統合による高速タイポ補正
                    </p>
                </div>
            </div>

            <SettingsSection title="アップデート">
                <SettingsRow
                    label="バージョンと更新"
                    description="起動時に新しいバージョンを確認します。アップデートの完了後、アプリを再起動すると適用されます。"
                >
                    <div className="settings-actions justify-end">
                        <Button variant="outline" onClick={() => void check()} disabled={busy || status === "launched"}>
                            {status === "checking" ? "確認中…" : "更新を確認"}
                        </Button>
                        {result?.update_available && (
                            <Button onClick={() => void start()} disabled={busy || status === "launched"}>
                                {status === "starting" ? "アップデートを開始中…" : status === "launched" ? "開始済み" : "アップデート"}
                            </Button>
                        )}
                    </div>
                </SettingsRow>
                {(error || status === "launched" || result) && (
                    <p className={`px-4 pb-3 text-xs ${error ? "text-destructive" : "settings-description"}`} role={error ? "alert" : "status"}>
                        {error ? error
                            : status === "launched" ? "アップデートを開始しました。完了までお待ちください。"
                            : result?.update_available ? `新しいバージョン v${result.latest_version} を利用できます。`
                            : "最新版を利用中です。"}
                    </p>
                )}
            </SettingsSection>

            <SettingsSection title="搭載モデルと動作環境">
                <SettingsRow label="かな漢字変換・先読みモデル" description="Standard v4.2b（53.9 MiB / 94.4 MiB）と Nano v4.2b（15.6 MiB / 24.6 MiB）を使用できます。利用モデルは「変換と候補」で選べます。">
                    <span className="text-xs font-mono font-medium text-foreground bg-muted/60 px-2.5 py-1 rounded-md border border-border/50">
                        ZeroCon Model v4.2b IQ4_emb8 / Q8
                    </span>
                </SettingsRow>
                <SettingsRow label="タイポ補正アーキテクチャ" description="独立Corrector常駐を撤廃し、ZeroCon Model v4.2b 内部で読み尤度判定・表層リランキング・漢字変換を一気通貫処理（正解率78.0% / 実質86.7%、CPU p50: 2.3ms）">
                    <span className="text-sm font-medium text-foreground">メインモデル統合タイポ補正</span>
                </SettingsRow>
                <SettingsRow label="推論バックエンド" description="設定とPCの対応状況に応じて選択">
                    <div className="flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                        <Zap className="size-3.5" />
                        <span>CPU / Vulkan</span>
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
                {licenses.entries
                    .filter((entry) => entry.platforms.includes("windows"))
                    .map((entry) => (
                        <SettingsRow
                            key={entry.id}
                            label={entry.title}
                            description={entry.notes?.windows ?? entry.note ?? entry.credit}
                        >
                            {entry.license ? (
                                <span className="text-xs font-mono text-muted-foreground">{entry.license}</span>
                            ) : null}
                        </SettingsRow>
                    ))}
            </SettingsSection>

            <SettingsSection title="利用規約">
                <div className="p-4 space-y-3">
                    <p className="text-xs text-muted-foreground leading-relaxed">
                        ZeroCon IME は個人・非商業利用に限り無償で提供されます。商用組み込み・再配布・ZeroCon Model Nano の重み・学習データの再利用は許諾しません。インストール先の USE-TERMS.txt にも同じ文面を同梱しています。
                    </p>
                    <div className="max-h-72 overflow-y-auto rounded-md border border-border/50 bg-muted/40 p-3 text-xs leading-relaxed whitespace-pre-wrap">
                        {useTermsText}
                    </div>
                </div>
            </SettingsSection>

            <SettingsSection title="ライセンス全文">
                <div className="p-4 space-y-3">
                    <p className="text-xs text-muted-foreground leading-relaxed">
                        同梱しているライセンス本文を表示できます。選択すると下に全文が表示されます。
                    </p>
                    <div className="flex flex-wrap gap-2">
                        {licenses.fullTexts
                            .filter((item) => item.windowsFile && fullTexts[item.id])
                            .map((item) => (
                                <Button
                                    key={item.id}
                                    variant={selectedFullText === item.id ? "secondary" : "outline"}
                                    size="sm"
                                    onClick={() => setSelectedFullText(item.id)}
                                >
                                    {fullTexts[item.id].label}
                                </Button>
                            ))}
                    </div>
                    <div className="max-h-96 overflow-y-auto rounded-md border border-border/50 bg-muted/40 p-3 text-xs leading-relaxed whitespace-pre-wrap font-mono">
                        {fullTexts[selectedFullText]?.text ?? ""}
                    </div>
                </div>
            </SettingsSection>

        </div>
    );
};

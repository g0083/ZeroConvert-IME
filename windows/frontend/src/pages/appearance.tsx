import { Check } from "lucide-react";
import { useTheme } from "@/components/theme-provider";
import { colorThemes, getColorTheme, type ColorThemeId } from "@/lib/themes";

export function Appearance() {
    const { appearance, loading, saving, saveTheme } = useTheme();
    const ime = getColorTheme(appearance.ime_theme);
    return <div className="settings-content">
        <p className="settings-description">設定画面とIMEの配色を、それぞれ選べます。変更は自動保存され、IMEには約1秒で反映されます。</p>
        {([
            ["frontend_theme", "設定画面", "メニュー、背景、ボタンの配色"],
            ["ime_theme", "IME", "変換候補、読み・アシスト表示、入力モードの配色"],
        ] as const).map(([target, title, description]) => <section key={target} className="space-y-3" aria-label={title}>
            <div><h2 className="text-sm font-semibold">{title}</h2><p className="settings-description">{description}</p></div>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
                {colorThemes.map((theme) => <button key={theme.id} type="button"
                    aria-pressed={appearance[target] === theme.id}
                    disabled={loading || saving}
                    onClick={() => void saveTheme(target, theme.id as ColorThemeId)}
                    className="rounded-xl border p-3 text-left text-foreground transition-colors hover:border-primary focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-60 aria-pressed:border-primary aria-pressed:ring-1 aria-pressed:ring-primary">
                    <div className="mb-3 flex h-16 gap-2 rounded-md border p-2" style={{ background: theme.background, borderColor: theme.border }} aria-hidden="true">
                        <div className="w-5 rounded-sm" style={{ background: theme.surface }} />
                        <div className="flex-1 space-y-2 py-1"><div className="h-2 w-3/4 rounded" style={{ background: theme.muted }} /><div className="h-5 rounded" style={{ background: theme.selection, color: theme.accent }}><span className="px-1 text-xs">あ 日本語</span></div></div>
                    </div>
                    <span className="flex items-center justify-between gap-1 text-sm"><span>{theme.label}</span>{appearance[target] === theme.id && <Check className="size-4 shrink-0 text-primary" aria-label="選択中" />}</span>
                </button>)}
            </div>
        </section>)}
        <section className="space-y-3" aria-label="IMEプレビュー">
            <h2 className="text-sm font-semibold">IMEプレビュー</h2>
            <div className="flex flex-wrap items-start gap-4 rounded-xl border p-5" style={{ background: ime.background, color: ime.text, borderColor: ime.border }}>
                <div className="min-w-48 rounded-xl border p-2" style={{ background: ime.surface, borderColor: ime.border }}>
                    {["日本語", "にほんご", "ニホンゴ"].map((text, i) => <div key={text} className="rounded-md px-3 py-2 text-sm" style={{ background: i === 0 ? ime.selection : undefined }}><span className="mr-3 text-xs" style={{ color: ime.muted }}>{i + 1}</span>{text}</div>)}
                </div>
                <div className="space-y-3"><div className="rounded-full border px-3 py-1" style={{ background: ime.surface, borderColor: ime.border }}>にほんご <span className="rounded px-2 text-sm" style={{ background: ime.selection, color: ime.accent }}>🔮 を入力 Tab</span></div><div className="inline-flex size-12 items-center justify-center rounded-lg border text-xl" style={{ background: ime.surface, borderColor: ime.border }}>あ</div></div>
            </div>
        </section>
    </div>;
}

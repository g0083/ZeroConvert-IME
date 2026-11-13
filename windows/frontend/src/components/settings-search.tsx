import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { Search } from "lucide-react";
import { searchSettings } from "@/lib/settings-index";
import { setPendingSettingsTarget } from "@/lib/settings-search-target";

export const OPEN_SETTINGS_SEARCH_EVENT = "zerocon:open-settings-search";

export function openSettingsSearch() {
    window.dispatchEvent(new CustomEvent(OPEN_SETTINGS_SEARCH_EVENT));
}

/**
 * Ctrl+K で設定を横断検索する。選択した項目のページへ移動し、
 * data-settings-label を目印に該当行までスクロールして一時的に強調する。
 */
export function SettingsSearch() {
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [active, setActive] = useState(0);
    const navigate = useNavigate();
    const results = useMemo(() => searchSettings(query), [query]);

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
                event.preventDefault();
                setOpen((value) => !value);
                setQuery("");
                setActive(0);
            } else if (event.key === "Escape" && open) {
                event.preventDefault();
                setOpen(false);
            }
        };
        const onOpen = () => {
            setOpen(true);
            setQuery("");
            setActive(0);
        };
        window.addEventListener("keydown", onKeyDown);
        window.addEventListener(OPEN_SETTINGS_SEARCH_EVENT, onOpen);
        return () => {
            window.removeEventListener("keydown", onKeyDown);
            window.removeEventListener(OPEN_SETTINGS_SEARCH_EVENT, onOpen);
        };
    }, [open]);

    if (!open) return null;

    const choose = (index: number) => {
        const entry = results[index];
        if (!entry) return;
        setOpen(false);
        setPendingSettingsTarget({ label: entry.label, section: entry.section });
        void navigate(entry.url);
    };
    return (
        <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-24 backdrop-blur-[2px]" role="presentation" onMouseDown={() => setOpen(false)}>
            <div role="dialog" aria-modal="true" aria-label="設定を検索" className="w-full max-w-xl overflow-hidden rounded-xl border bg-card shadow-2xl" onMouseDown={(event) => event.stopPropagation()}>
                <div className="flex items-center gap-2 border-b px-3 py-2">
                    <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <input
                        autoFocus
                        value={query}
                        onChange={(event) => { setQuery(event.target.value); setActive(0); }}
                        onKeyDown={(event) => {
                            if (event.key === "ArrowDown") { event.preventDefault(); setActive((value) => Math.min(value + 1, results.length - 1)); }
                            else if (event.key === "ArrowUp") { event.preventDefault(); setActive((value) => Math.max(value - 1, 0)); }
                            else if (event.key === "Enter") { event.preventDefault(); choose(active); }
                        }}
                        placeholder="設定名・説明・別名で検索"
                        aria-label="設定を検索"
                        className="h-9 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                    />
                    <kbd className="shrink-0 rounded border px-1.5 py-0.5 text-[10px] text-muted-foreground">Esc</kbd>
                </div>
                <ul className="max-h-80 overflow-y-auto p-1" role="listbox" aria-label="検索結果">
                    {results.length === 0 ? (
                        <li className="px-3 py-6 text-center text-sm text-muted-foreground">
                            {query.trim() ? "一致する設定がありません" : "設定名の一部を入力してください"}
                        </li>
                    ) : results.map((entry, index) => (
                        <li key={`${entry.url}-${entry.label}`} role="option" aria-selected={index === active}>
                            <button
                                type="button"
                                onMouseEnter={() => setActive(index)}
                                onClick={() => choose(index)}
                                className={`flex w-full items-center justify-between gap-3 rounded-md px-3 py-2 text-left text-sm ${index === active ? "bg-accent text-accent-foreground" : ""}`}
                            >
                                <span className="min-w-0 truncate">{entry.label}</span>
                                <span className="shrink-0 text-[11px] text-muted-foreground">{entry.page} › {entry.section}</span>
                            </button>
                        </li>
                    ))}
                </ul>
            </div>
        </div>
    );
}

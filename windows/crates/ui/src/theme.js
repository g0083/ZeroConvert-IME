(() => {
    const palettes = __COLOR_PALETTES__;
    let current = __INITIAL_THEME__;
    function apply() {
        const root = document.documentElement;
        if (!root) return;
        const theme = palettes.find((item) => item.id === current) || palettes[0];
        root.dataset.colorTheme = theme.id;
        root.style.colorScheme = theme.mode;
        for (const name of ["background", "surface", "selection", "text", "muted", "accent", "onAccent", "border"])
            root.style.setProperty(`--ime-${name}`, theme[name]);
    }
    window.setColorTheme = (id) => { current = id; apply(); };
    function install() {
        const style = document.createElement("style");
        style.textContent = `
            :root body, :root main { color: var(--ime-text); }
            :root main { background: var(--ime-surface); border-color: var(--ime-border); }
            :root li { color: var(--ime-text); }
            :root li::before { color: var(--ime-muted); background: var(--ime-selection); }
            :root li[data-selected] { background: var(--ime-selection); color: var(--ime-text); box-shadow: inset 0 0 0 1px var(--ime-border); }
            :root li[data-selected]::before { background: var(--ime-accent); color: var(--ime-onAccent); }
            :root footer { color: var(--ime-muted); border-color: var(--ime-border); }
            :root footer span { color: var(--ime-muted) !important; }
            :root ol::-webkit-scrollbar-thumb { background: var(--ime-border); }
            :root .ai-assist-pill, :root .ai-assist-tab { background: var(--ime-selection); color: var(--ime-accent); border-color: var(--ime-border); }
            :root main::after { background: var(--ime-border); }
        `;
        document.head.appendChild(style);
        apply();
    }
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install, { once: true });
    else install();
})();

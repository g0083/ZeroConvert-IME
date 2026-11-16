"""Render the checked-in Windows candidate WebView with a browser IPC adapter."""

import json
from pathlib import Path
import sys


def main():
    root = Path(__file__).resolve().parent.parent
    ui = root / "windows/crates/ui/src"
    implementation = (ui / "candidate.rs").read_text(encoding="utf-8")
    html = implementation.split('fn candidate_html() -> String {', 1)[1].split('r##"', 1)[1].split('"##', 1)[0]
    html = html.replace("__CANDIDATE_SCROLLER_SCRIPT__", (ui / "candidate_scroller.js").read_text(encoding="utf-8"))
    theme = (ui / "theme.js").read_text(encoding="utf-8")
    palettes = (root / "windows/crates/shared/themes.json").read_text(encoding="utf-8")
    settings = json.loads((root / "windows/deployment/settings.json").read_text(encoding="utf-8"))
    theme = theme.replace("__COLOR_PALETTES__", palettes)
    theme = theme.replace("__INITIAL_THEME__", json.dumps(settings["appearance"]["ime_theme"]))
    host = '''
        window.ipc = { postMessage(message) {
            const data = JSON.parse(message);
            if (data.type === 'select' || data.type === 'forget') {
                data.revision = window.zeroconRows?.revision;
                data.id = window.zeroconRows?.ids[data.index];
            }
            parent.postMessage(JSON.stringify(data), location.origin);
        } };
        document.addEventListener('contextmenu', event => {
            event.preventDefault();
            const index = event.target.closest('li.candidate-item')?.dataset.index;
            if (!window.zeroconRows?.forget[index]) event.stopImmediatePropagation();
        }, { capture: true });
        document.addEventListener('pointerdown', event => event.preventDefault(), { capture: true });
    '''
    html = html.replace("<head>", "<head><meta charset='utf-8'><script>" + host + theme + "</script>", 1)
    html = html.replace("Tab:補正 · 右×2:削除", "Space:候補切替 · Enter:確定")
    Path(sys.argv[1]).write_text("<!doctype html>\n" + html, encoding="utf-8")


if __name__ == "__main__":
    main()

"""Render the checked-in Windows reading WebView with a browser IPC adapter."""

from pathlib import Path
import sys


def main():
    ui = Path(__file__).resolve().parents[1] / "windows/crates/ui/src"
    implementation = (ui / "ruby.rs").read_text(encoding="utf-8")
    html = implementation.split('fn create_ruby_webview', 1)[1].split('r##"', 1)[1].split('"##', 1)[0]
    if "__CORRECTION_DIFF_SCRIPT__" in html:
        diff = (ui / "correction_diff.js").read_text(encoding="utf-8")
        html = html.replace("__CORRECTION_DIFF_SCRIPT__", diff)
    host = """
        window.ipc = { postMessage(message) {
            const data = JSON.parse(message);
            parent.postMessage(JSON.stringify({ ...data, requestId: currentRequestId }), location.origin);
        } };
        document.addEventListener('pointerdown', event => event.preventDefault(), { capture: true });
    """
    transparent_style = "<link rel='stylesheet' href='./ime-theme.css'><style>html, body { background: transparent !important; }</style>"
    html = html.replace("<head>", "<head><meta charset='utf-8'>" + transparent_style + "<script>" + host + "</script>", 1)
    html = html.replace("(maxOverlayWidth - horizontalBox", "(Math.min(maxOverlayWidth, parent.innerWidth - 16) - horizontalBox", 1)
    Path(sys.argv[1]).write_text("<!doctype html>\n" + html, encoding="utf-8")


if __name__ == "__main__":
    main()

"""Use the default ZeroConvert IME palette for the public site."""

import json
from pathlib import Path
import re
import sys


def main():
    windows = Path(__file__).resolve().parents[1] / "windows"
    settings = json.loads((windows / "deployment/settings.json").read_text(encoding="utf-8"))
    palettes = json.loads((windows / "crates/shared/themes.json").read_text(encoding="utf-8"))
    theme = next(palette for palette in palettes if palette["id"] == settings["appearance"]["ime_theme"])
    colors = {key: theme[key] for key in ("background", "surface", "selection", "text", "muted", "accent", "onAccent", "border")}
    if theme["mode"] not in ("light", "dark") or not all(re.fullmatch(r"#[0-9a-fA-F]{6}", color) for color in colors.values()):
        raise ValueError("Invalid ZeroConvert IME palette")
    lines = [f"  --ime-{key}: {color};" for key, color in colors.items()]
    output = [f"/* ZeroConvert IME: {theme['id']} */", ":root {", f"  color-scheme: {theme['mode']};", *lines, "}", ""]
    Path(sys.argv[1]).write_text("\n".join(output), encoding="utf-8")


if __name__ == "__main__":
    main()

"""Generate the Web demo's 12-key layouts from the Android Compose sources."""

import json
from pathlib import Path
import re
import sys


ANDROID = Path(__file__).resolve().parents[1] / "android/app/src/main"
SOURCE = ANDROID / "java/com/zeroconvert/ime"
STRING = r'"(?:[^"\\]|\\.)*"'


def section(source, marker, end):
    return source.split(marker, 1)[1].split(end, 1)[0]


def definition(arguments):
    return {key: json.loads(value) for key, value in re.findall(rf'(\w+)\s*=\s*({STRING})', arguments)}


def flick_layout(flick, function, definitions, shifted=False):
    layout = section(flick, f"fun {function}(", "\n@Composable")
    keys = {}
    for name, body in re.findall(r"\bval (\w+) = FlickKeyDef\((.*)\)", definitions):
        keys[name] = definition(body)
    for name, body in re.findall(r"\bfun (getKey\d+)\(\) = FlickKeyDef\((.*)\)", definitions):
        keys[name] = definition(body)
    for name, upper, lower in re.findall(r"fun (getKey\d+)\(isShifted: Boolean\) = if \(isShifted\) \{\s*FlickKeyDef\(([^\n]*)\)\s*\} else \{\s*FlickKeyDef\(([^\n]*)\)", definitions):
        keys[name] = definition(upper if shifted else lower)
    rows = []
    for group in re.findall(r"keys = Triple\((.*?)\),\s*keyBgColor", layout, re.S):
        rows.append([keys[key] for key in re.findall(r"\bFlick\w*KeyDefinitions\.(\w+)", group)])
    if len(rows) != 4 or any(len(row) != 3 for row in rows):
        raise ValueError(f"Expected four rows of three native keys in {function}")
    return rows


def palettes(theme):
    result = {}
    for mode in ("DARK", "LIGHT"):
        theme_id = re.search(rf'ZERO_CON_THEME_DEFAULT_{mode} = "(\w+)"', theme)[1]
        palette = re.search(rf'ZeroConTheme\("{theme_id}", {STRING}, (\w+)\)', theme)[1]
        body = section(theme, f"val {palette} = ZeroConPalette(", "\n)")
        result[mode.lower()] = {key: "#" + color for key, color in re.findall(r"(\w+) = Color\(0xFF([A-F0-9]{6})\)", body)}
        if not {"canvas", "surface", "keyNormal", "keyModifier", "primaryAccent", "textPrimary"}.issubset(result[mode.lower()]):
            raise ValueError(f"Incomplete Android palette: {palette}")
    return result


def picker_data(compose):
    categories = section(compose, "val symbolCategories = listOf(", "\n@Composable")
    symbols = [{"label": json.loads(title), "values": [json.loads(value) for value in re.findall(STRING, body)]}
               for title, body in re.findall(rf'title = ({STRING}),\s*symbols = listOf\((.*?)\n\s*\)\s*\)', categories, re.S)]
    catalog = (SOURCE / "EmojiCatalog.kt").read_text(encoding="utf-8")
    labels = dict(re.findall(r'"(\w+)" -> "([^"]+)"', section(catalog, "fun labelFor", "fun iconFor")))
    icons = dict(re.findall(r'"(\w+)" -> "([^"]+)"', section(catalog, "fun iconFor", "\n    }")))
    emojis = {key: {"label": label, "icon": icons[key], "values": []} for key, label in labels.items()}
    for row in (ANDROID / "assets/emoji_catalog.tsv").read_text(encoding="utf-8").splitlines():
        if row and not row.startswith("#"):
            group, emoji, *_ = row.split("\t")
            emojis[group]["values"].append(emoji)
    return {"symbols": symbols, "emoji": list(emojis.values())}


def main():
    flick = (SOURCE / "FlickKeyboardView.kt").read_text(encoding="utf-8")
    compose = (SOURCE / "ComposeKeyboardView.kt").read_text(encoding="utf-8")
    dimensions = (SOURCE / "KeyboardCandidateArea.kt").read_text(encoding="utf-8")
    service = (SOURCE / "ZeroConvertInputMethodService.kt").read_text(encoding="utf-8")
    touch = (SOURCE / "KeyboardTouchLogic.kt").read_text(encoding="utf-8")
    kana = section(flick, "object KanaModifier {", "\nfun calculateFlickDirection")
    japanese = section(flick, "object FlickKeyDefinitions {", "object FlickEnglishKeyDefinitions")
    english = section(flick, "object FlickEnglishKeyDefinitions {", "object FlickNumericKeyDefinitions")
    numeric = section(flick, "object FlickNumericKeyDefinitions {", "object KanaModifier")
    data = {
        "layouts": {
            "japanese": flick_layout(flick, "Japanese12KeyFlickLayout", japanese),
            "english": flick_layout(flick, "English12KeyFlickLayout", english),
            "shifted": flick_layout(flick, "English12KeyFlickLayout", english, shifted=True),
            "numeric": flick_layout(flick, "FlickNumericPadLayout", numeric),
        },
        "palettes": palettes((SOURCE / "ZeroConTheme.kt").read_text(encoding="utf-8")),
        "modifiers": {
            "center": dict(re.findall(r"'(.)'\s*->\s*'(.)'", kana)),
            **{direction: dict(re.findall(r"'(.)'\s*to\s*'(.)'", section(kana, name + "Forms: Map<Char, Char> = mapOf(", "\n    )")))
               for direction, name in (("left", "dakuten"), ("up", "small"), ("right", "handakuten"))},
        },
        "geometry": {
            "keyHeight": int(re.search(r"prefs.getInt\(KEY_KEY_HEIGHT_DP, (\d+)\)", service)[1]),
            "contentExtraHeight": float(re.search(r"KeyboardContentExtraHeight: Dp = ([\d.]+)\.dp", dimensions)[1]),
            "barRatio": float(re.search(r"LocalKeyboardKeyHeight.current \* ([\d.]+)f", dimensions)[1]),
            "columnWeights": [float(weight) for weight in re.findall(r"Modifier.weight\(([\d.]+)f\)", section(flick, "private fun ColumnScope.FlickGridRow(", "private fun FlickSideSymbolsButton")) if weight != "1"],
            "flickThreshold": int(re.search(r"prefs.getInt\(KEY_FLICK_SENSITIVITY_DP, (\d+)\)", service)[1]),
            "hysteresis": float(re.search(r"hysteresisRatio: Float = ([\d.]+)f", touch)[1]),
            "returnRatio": float(re.search(r"centerReturnRatio: Float = ([\d.]+)f", touch)[1]),
        },
        "pickers": picker_data(compose),
    }
    if len(data["geometry"]["columnWeights"]) != 5 or not all(data["modifiers"].values()):
        raise ValueError("Unexpected Android flick keyboard geometry or modifier table")
    source = "// Generated from the Android Compose keyboard and emoji catalog.\n"
    source += "// Emoji data © 2023 Unicode, Inc. https://www.unicode.org/license.txt\n"
    Path(sys.argv[1]).write_text(source + "export default " + json.dumps(data, ensure_ascii=False) + ";\n", encoding="utf-8")


if __name__ == "__main__":
    main()

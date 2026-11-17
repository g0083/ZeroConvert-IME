"""Record the ordered candidate rows from a packaged Windows Swift DLL."""

import argparse
import ctypes as ffi
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
from time import perf_counter


class Candidate(ffi.Structure):
    _fields_ = [
        ("text", ffi.c_char_p), ("suffix", ffi.c_char_p), ("reading", ffi.c_char_p),
        ("count", ffi.c_int32), ("id", ffi.c_uint64), ("prediction_reading", ffi.c_char_p),
    ]


def decode(value):
    return (value or b"").decode()


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def dictionary_matches(windows_dictionary, compressed):
    with gzip.open(compressed, "rb") as source:
        length, = struct.unpack("<I", source.read(4))
        files = json.loads(source.read(length))
        for name, size in files:
            relative = Path(name)
            if relative.is_absolute() or ".." in relative.parts:
                return False
            entry = windows_dictionary / relative
            if not entry.is_file() or entry.stat().st_size != size:
                return False
            if entry.read_bytes() != source.read(size):
                return False
        return source.read(1) == b""


class Engine:
    def __init__(self, library):
        def bind(name, result, *args):
            function = getattr(library, name)
            function.restype, function.argtypes = result, args
            return function

        text = ffi.c_void_p
        cstr = ffi.c_char_p
        integer = ffi.c_int32
        int_out = ffi.POINTER(integer)
        rows = ffi.POINTER(ffi.POINTER(Candidate))
        self.initialize = bind("Initialize", None, cstr, ffi.c_bool)
        self.free = bind("FreeCString", None, text)
        self.free_rows = bind("FreeCandidateList", None, rows, integer)
        self.raw = bind("GetRawInput", text)
        self.cursor = bind("GetCursorPosition", integer)
        self.rows = bind("GetComposedText", rows, int_out)
        self.cursor_rows = bind("GetComposedTextForCursorRange", rows, int_out)
        self.clear = bind("ClearText", None)
        self.context = bind("SetContext", None, cstr)
        self.append = bind("AppendText", text, cstr, int_out)
        self.backspace = bind("RemoveText", text, int_out)
        self.delete = bind("DeleteForwardText", text, int_out)
        self.move = bind("MoveCursor", text, integer, int_out)
        self.adjust = bind("AdjustClauseBoundary", text, integer, integer, cstr, int_out, int_out, int_out)
        self.shrink = bind("ShrinkText", text, integer)
        self.reading = ""

    def string(self, address):
        if not address:
            return ""
        try:
            return decode(ffi.string_at(address))
        finally:
            self.free(address)

    def step(self, key, **fields):
        cursor_out = ffi.c_int32()
        adjusted = None
        if key == "clear":
            self.clear()
            self.reading = ""
        elif key == "context":
            self.context(fields["value"].encode())
        else:
            if key == "append":
                address = self.append(fields["value"].encode(), ffi.byref(cursor_out))
            elif key == "move":
                address = self.move(fields["offset"], ffi.byref(cursor_out))
            elif key == "adjustClause":
                applied, boundary = ffi.c_int32(), ffi.c_int32()
                address = self.adjust(fields["inputCount"], fields["offset"], fields["value"].encode(),
                                      ffi.byref(applied), ffi.byref(boundary), ffi.byref(cursor_out))
                if applied.value:
                    adjusted = boundary.value
            elif key == "shrink":
                address = self.shrink(fields["offset"])
            else:
                address = {"backspace": self.backspace, "delete": self.delete}[key](ffi.byref(cursor_out))
            self.reading = self.string(address)
        cursor = self.cursor()
        raw = self.string(self.raw())
        count = ffi.c_int32()
        get_rows = self.rows if cursor == len(self.reading) else self.cursor_rows
        candidates = get_rows(ffi.byref(count))
        try:
            rows = [
                dict(text=decode(row.text), suffix=decode(row.suffix), reading=decode(row.reading), inputCount=row.count,
                     predictionReading=decode(row.prediction_reading))
                for row in (candidates[index].contents for index in range(count.value))
            ]
        finally:
            self.free_rows(candidates, count)
        result = dict(reading=self.reading, raw=raw, cursor=cursor, candidates=rows)
        if key == "adjustClause":
            result["adjustedInputCount"] = adjusted
        return result


def record(engine, cases):
    results = []
    for case in cases:
        engine.clear()
        engine.context(b"")
        engine.reading = ""
        steps = []
        for step in case["steps"]:
            if "type" in step:
                commands = [{"key": "append", "value": char} for char in step["type"]]
            else:
                commands = [step]
            for command in commands:
                key = command["key"]
                started = perf_counter()
                state = engine.step(key, **{k: v for k, v in command.items() if k != "key"})
                steps.append({"command": command, "state": state, "elapsedMs": round((perf_counter() - started) * 1000, 3)})
        results.append({"name": case["name"], "steps": steps})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path, help="Packaged payload containing the DLL and Dictionary")
    parser.add_argument("site", type=Path, help="Staged Web site containing ime-settings.json and the model")
    parser.add_argument("output", type=Path, help="Output JSON under artifacts/results")
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    dll = runtime / "azookey-server.dll"
    cases = json.loads(Path(__file__).with_name("parity-cases.json").read_text(encoding="utf-8"))
    settings = args.site / "ime-settings.json"
    config = json.loads(settings.read_text(encoding="utf-8"))
    if (config["zenzai"]["backend"] != "cpu" or config["zenzai"]["gpu_layers"] != 0
            or config["learning"]["mode"] not in {"none", "disabled"}):
        parser.error("The baseline requires CPU-only settings with learning disabled.")
    models = list(args.site.glob("*.gguf"))
    if len(models) != 1 or sha256(models[0]) != sha256(runtime / "models" / models[0].name):
        parser.error("The staged site and the Windows payload must contain the same GGUF.")
    dictionary = args.site / "dictionary.gz"
    if not dictionary_matches(runtime / "Dictionary", dictionary):
        parser.error("The Web dictionary bundle differs from the Windows payload.")
    with tempfile.TemporaryDirectory(prefix="zerocon-parity-") as isolated:
        application = Path(isolated) / "Azookey"
        application.mkdir()
        shutil.copyfile(settings, application / "settings.json")
        os.environ.update(APPDATA=isolated, LOCALAPPDATA=isolated,
                          AZOOKEY_LLAMA_BACKEND_DIR=str(runtime / "llama_cpu"))
        with os.add_dll_directory(str(runtime)), os.add_dll_directory(str(runtime / "llama_cpu")):
            engine = Engine(ffi.CDLL(str(dll)))
            engine.initialize(str(runtime).encode(), True)
            trace = record(engine, cases)
    result = {
        "schema": 2, "layer": "windows-swift-dll",
        "settingsSha256": sha256(settings), "model": models[0].name,
        "modelSha256": sha256(models[0]), "dictionarySha256": sha256(dictionary),
        "dllSha256": sha256(dll), "cases": trace,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Recorded {sum(len(case['steps']) for case in trace)} Windows DLL steps across {len(trace)} cases")


if __name__ == "__main__":
    main()

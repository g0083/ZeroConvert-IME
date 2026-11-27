"""Stage the pinned llama.cpp CPU sources for WASI (single-threaded) or Emscripten (pthreads)."""

import argparse
import importlib.util
from pathlib import Path
import re
import shutil


def windows_source(directory):
    root = Path(__file__).resolve().parents[1]
    path = root / "windows/scripts/prepare-modern-llama.py"
    spec = importlib.util.spec_from_file_location("prepare_windows_llama", path)
    windows = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(windows)
    archive, digest = windows.SOURCE_ARCHIVE
    source = windows.fetch(archive, f"https://github.com/ggml-org/llama.cpp/archive/{windows.REVISION}.zip", digest)
    windows.extract(source, directory)
    checkout, = (item for item in directory.iterdir() if item.is_dir())
    windows.apply_unified_patch(checkout, windows.PATCH_FILE)
    return checkout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--windows-source", type=Path, required=True)
    parser.add_argument("--for-emscripten", action="store_true", help="Keep real pthreads/std::async for Emscripten pthreads build")
    args = parser.parse_args()
    source_dir = windows_source(args.windows_source)
    args.output.mkdir(parents=True, exist_ok=True)
    for name in ("cmake", "ggml", "include", "src", "vendor"):
        shutil.copytree(source_dir / name, args.output / name, dirs_exist_ok=True,
                        copy_function=shutil.copyfile)
    for name in ("CMakeLists.txt", "LICENSE"):
        shutil.copyfile(source_dir / name, args.output / name)

    # WebAssembly has no x86 AMX backend.
    cpu = args.output / "ggml/src/ggml-cpu/CMakeLists.txt"
    source = cpu.read_text(encoding="utf-8")
    source, count = re.subn(r"(?m)^ +ggml-cpu/amx/(?:amx|mmq)\.(?:cpp|h)\n", "", source)
    if count != 4:
        raise ValueError("Unexpected llama.cpp AMX source list")
    cpu.write_text(source, encoding="utf-8")

    if not args.for_emscripten:
        # Single-threaded WASI SDK has no C++ thread implementation.
        (args.output / "ggml/src/ggml-threading.cpp").write_text(
            '#include "ggml-threading.h"\nvoid ggml_critical_section_start() {}\n'
            'void ggml_critical_section_end() {}\n', encoding="utf-8")

        loader = args.output / "src/llama-model-loader.cpp"
        source = loader.read_text(encoding="utf-8").replace(
            "std::future<std::pair<ggml_tensor *, bool>>", "std::pair<ggml_tensor *, bool>")
        source, count = re.subn(r"std::async\(std::launch::async, \[[^\]]+\] \{\s*return (std::make_pair\([^\n]+\));\s*\}\)",
                                r"\1", source)
        if count != 2:
            raise ValueError("Unexpected llama.cpp asynchronous validation calls")
        loader.write_text(source.replace("future.get()", "future"), encoding="utf-8")

    # The inference host has no model quantization entry point.
    llama = args.output / "src/CMakeLists.txt"
    source = llama.read_text(encoding="utf-8")
    llama.write_text(source.replace("    llama-quant.cpp\n", ""), encoding="utf-8")


if __name__ == "__main__":
    main()

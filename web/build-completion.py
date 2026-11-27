"""Link the standalone WASI completion reactor against the staged llama.cpp."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("clang_bin", type=Path, help="Swift toolchain usr/bin")
    parser.add_argument("bundle", type=Path, help="Wasm SDK's wasm32-unknown-wasip1 directory")
    parser.add_argument("source", type=Path, help="Staged llama.cpp source")
    parser.add_argument("build", type=Path, help="CMake build containing static llama/ggml archives")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    web = Path(__file__).resolve().parent
    native = web.parent / "engine/native"
    channel = web.parent / "windows/native/keyboard-prior"
    sysroot = args.bundle / "WASI.sdk"
    builtins = args.bundle / "swift.xctoolchain/usr/lib/clang/lib/wasip1/libclang_rt.builtins-wasm32.a"
    suffix = ".exe" if os.name == "nt" else ""
    compiler = args.clang_bin / ("clang" + suffix)
    linker = args.clang_bin / ("clang++" + suffix)
    target = [
        "--target=wasm32-wasip1",
        f"--sysroot={sysroot.resolve()}",
        "-O3",
        "-mbulk-memory",
        "-mnontrapping-fptoint",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="completion-", dir=args.output.parent) as temp:
        stubs = Path(temp) / "wasi-stubs.o"
        subprocess.run([
            compiler, *target, "-DZEROCON_STATIC_BACKENDS", "-c", web / "wasi-exceptions.c", "-o", stubs,
        ], check=True)
        subprocess.run([
            linker, *target, "-std=c++17", "-fno-c++-static-destructors", "-msimd128",
            "-mexec-model=reactor", "-nodefaultlibs",
            f"-I{args.source / 'include'}", f"-I{args.source / 'ggml/include'}",
            f"-I{native}", f"-I{channel}",
            web / "completion.cpp", native / "keyboard_assist.cpp", stubs,
            f"-L{args.build / 'src'}", f"-L{args.build / 'ggml/src'}",
            "-lllama", "-lggml", "-lggml-cpu", "-lggml-base", "-lc++", "-lc++abi",
            "-lwasi-emulated-signal", "-lwasi-emulated-mman", "-lwasi-emulated-process-clocks", "-lc", builtins,
            "-Wl,-z,stack-size=8388608",
            "-Wl,--export=malloc,--export=free,--export=zerocon_completion_init,"
            "--export=zerocon_complete,--export=zerocon_completion_stop,--export=zerocon_keyboard_repair",
            "-o", args.output,
        ], check=True)


if __name__ == "__main__":
    main()

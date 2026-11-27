"""Build the multi-threaded Emscripten (pthreads + SIMD128) llama.cpp backend."""

import argparse
from pathlib import Path
import subprocess

EXPORTED_FUNCTIONS = [
    "_malloc",
    "_free",
    "_mt_set_default_threads",
    "_mt_llama_backend_init",
    "_mt_llama_backend_free",
    "_mt_llama_model_load",
    "_mt_llama_model_free",
    "_mt_llama_model_size",
    "_mt_llama_model_get_vocab",
    "_mt_llama_vocab_n_tokens",
    "_mt_llama_vocab_bos",
    "_mt_llama_vocab_eos",
    "_mt_llama_vocab_is_eog",
    "_mt_llama_tokenize",
    "_mt_llama_token_to_piece",
    "_mt_llama_init_from_model",
    "_mt_llama_free",
    "_mt_llama_n_ctx",
    "_mt_llama_n_batch",
    "_mt_llama_set_n_threads",
    "_mt_llama_memory_clear",
    "_mt_llama_memory_seq_rm",
    "_mt_llama_memory_seq_cp",
    "_mt_llama_memory_seq_pos_max",
    "_mt_llama_decode_one",
    "_mt_llama_decode_flat",
    "_mt_llama_get_logits_ptr",
    "_mt_llama_get_logits_ith_ptr",
    "_mt_llama_get_logits_count",
    "_mt_llama_get_vocab_size_for_ctx",
    "_mt_llama_sampler_init_greedy",
    "_mt_llama_sampler_sample",
    "_mt_llama_sampler_accept",
    "_mt_llama_sampler_reset",
    "_mt_llama_sampler_free",
    "_zerocon_completion_init",
    "_zerocon_complete",
    "_zerocon_completion_stop",
    "_zerocon_keyboard_repair",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Staged llama.cpp source (for Emscripten)")
    parser.add_argument("build", type=Path, help="CMake build directory for Emscripten llama.cpp")
    parser.add_argument("output", type=Path, help="Output .mjs path (e.g. dist/pages-cache/zerocon_llama_mt.mjs)")
    parser.add_argument("--pool-size", type=int, default=4)
    args = parser.parse_args()

    web = Path(__file__).resolve().parent
    native = web.parent / "engine/native"
    channel = web.parent / "windows/native/keyboard-prior"

    flags = "-O3 -pthread -msimd128 -mbulk-memory -mnontrapping-fptoint"
    subprocess.run([
        "emcmake", "cmake", "-G", "Ninja",
        "-S", str(args.source),
        "-B", str(args.build),
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_C_FLAGS={flags}",
        f"-DCMAKE_CXX_FLAGS={flags}",
        "-DBUILD_SHARED_LIBS=OFF",
        "-DGGML_NATIVE=OFF",
        "-DGGML_OPENMP=OFF",
        "-DGGML_LLAMAFILE=OFF",
        "-DGGML_RPC=OFF",
        "-DLLAMA_BUILD_COMMON=OFF",
        "-DLLAMA_BUILD_TESTS=OFF",
        "-DLLAMA_BUILD_TOOLS=OFF",
        "-DLLAMA_BUILD_EXAMPLES=OFF",
        "-DLLAMA_BUILD_SERVER=OFF",
        "-DLLAMA_BUILD_APP=OFF",
        "-DLLAMA_BUILD_IS_DEV=OFF",
    ], check=True)

    subprocess.run([
        "cmake", "--build", str(args.build), "--target", "llama", "--parallel", "4",
    ], check=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    exported = ",".join(f"'{fn}'" for fn in EXPORTED_FUNCTIONS)
    subprocess.run([
        "em++",
        "-O3", "-std=c++17", "-pthread", "-msimd128", "-mbulk-memory", "-mnontrapping-fptoint",
        "-fexceptions",
        f"-I{args.source / 'include'}",
        f"-I{args.source / 'ggml/include'}",
        f"-I{native}",
        f"-I{channel}",
        str(web / "llama-mt-bridge.cpp"),
        str(native / "keyboard_assist.cpp"),
        f"-L{args.build / 'src'}",
        f"-L{args.build / 'ggml/src'}",
        "-lllama", "-lggml", "-lggml-cpu", "-lggml-base",
        "-sMODULARIZE=1",
        "-sEXPORT_ES6=1",
        "-sEXPORT_NAME=createZeroConLlamaMt",
        "-sENVIRONMENT=web,worker,node",
        f"-sPTHREAD_POOL_SIZE={args.pool_size}",
        "-sPTHREAD_POOL_SIZE_STRICT=0",
        "-sALLOW_MEMORY_GROWTH=1",
        "-sINITIAL_MEMORY=67108864",
        "-sMAXIMUM_MEMORY=536870912",
        "-sSTACK_SIZE=8388608",
        "-sFORCE_FILESYSTEM=1",
        "-sEXPORTED_RUNTIME_METHODS=['FS','HEAPU8','HEAP32','HEAPF32','UTF8ToString','stringToUTF8','lengthBytesUTF8']",
        f"-sEXPORTED_FUNCTIONS=[{exported}]",
        "-o", str(args.output),
    ], check=True)


if __name__ == "__main__":
    main()

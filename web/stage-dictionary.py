"""Stage a dictionary-only or multi-threaded-bridge Swift package from the pinned shared converter."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess


WEB = Path(__file__).resolve().parent
ROOT = WEB.parent
CHECKOUT_NAMES = {"jinja": "Jinja"}
CONVERTER_MANIFEST = '''// swift-tools-version: 6.1
import PackageDescription

let settings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("MemberImportVisibility"),
    .enableUpcomingFeature("InternalImportsByDefault"),
]
let package = Package(
    name: "AzooKeyKanaKanjiConverter",
    products: [.library(name: "KanaKanjiConverterModule", targets: ["KanaKanjiConverterModule"])],
    dependencies: [.package(path: "../swift-algorithms"), .package(path: "../swift-collections"), .package(path: "../swift-tokenizers")],
    targets: [
        .target(name: "SwiftUtils", dependencies: [.product(name: "Algorithms", package: "swift-algorithms")], swiftSettings: settings),
        .target(name: "EfficientNGram", dependencies: [.product(name: "Transformers", package: "swift-tokenizers")], resources: [.copy("tokenizer")], swiftSettings: settings),
        .target(name: "KanaKanjiConverterModule", dependencies: ["SwiftUtils", "EfficientNGram", .product(name: "Collections", package: "swift-collections")], swiftSettings: settings),
    ]
)
'''
APP_MANIFEST = '''// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "ZeroConDictionary",
    products: [.executable(name: "zerocon_dictionary", targets: ["App"])],
    dependencies: [.package(path: "../converter")],
    targets: [.executableTarget(name: "App", dependencies: [.product(name: "KanaKanjiConverterModule", package: "converter")])]
)
'''


def git(directory, *args):
    subprocess.run(["git", "-C", str(directory), *args], check=True)


def stage_dependencies(output, cache):
    pins = json.loads((ROOT / "engine/Package.resolved").read_text(encoding="utf-8"))["pins"]
    for pin in pins:
        name = CHECKOUT_NAMES.get(pin["identity"], pin["identity"])
        target = output / name
        if cache:
            shutil.copytree(cache / name, target, dirs_exist_ok=True, copy_function=shutil.copyfile,
                            ignore=shutil.ignore_patterns(".git", ".build", ".swiftpm"))
        else:
            target.mkdir(exist_ok=True)
            git(target, "init", "--quiet")
            git(target, "fetch", "--quiet", "--depth", "1", pin["location"], pin["state"]["revision"])
            git(target, "checkout", "--quiet", "--detach", "--force", "FETCH_HEAD")
        manifest = target / "Package.swift"
        content = manifest.read_text(encoding="utf-8")
        # This dependency is used only by upstream command-line targets.
        content = re.sub(r'^\s*\.package\(url: .*swift-argument-parser.*\n', '\n', content, flags=re.MULTILINE)
        content = re.sub(r'\.package\(url: "https://github\.com/[^/]+/([^".]+)(?:\.git)?", .+\)(,?)$',
                         r'.package(path: "../\1")\2', content, flags=re.MULTILINE)
        if re.search(r'\.package\(url:', content):
            raise ValueError(f"Unpinned remote dependency in {name}/Package.swift")
        manifest.write_text(content, encoding="utf-8")


def stage_converter(converter, output, llama):
    target = output / "converter"
    shutil.copytree(converter / "Sources", target / "Sources", dirs_exist_ok=True,
                    copy_function=shutil.copyfile,
                    ignore=shutil.ignore_patterns("KanaKanjiConverterModuleWithDefaultDictionary", "CliTool"))
    module = target / "Sources/KanaKanjiConverterModule"
    zenz = module / "ConversionAlgorithms/Zenzai/Zenz"
    builder = module / "DictionaryManagement/DataStructure/DictionaryBuilder.swift"
    content = builder.read_text(encoding="utf-8")
    if content.count(", options: .atomic)") != 2:
        raise ValueError("Unexpected dictionary builder revision")
    # WASI Foundation has no atomic file writer. The browser mounts only a read-only dictionary.
    builder.write_text(content.replace(", options: .atomic)", ")"), encoding="utf-8")
    manifest = CONVERTER_MANIFEST
    if llama:
        context = zenz / "ZenzContext.swift"
        content = context.read_text(encoding="utf-8")
        if "model_params.load_mode = LLAMA_LOAD_MODE_MMAP" not in content:
            raise ValueError("Unexpected model loading configuration")
        context.write_text(content.replace("model_params.load_mode = LLAMA_LOAD_MODE_MMAP",
                                           "model_params.load_mode = LLAMA_LOAD_MODE_NONE"), encoding="utf-8")
        headers = target / "Sources/llama.cpp"
        shutil.copyfile(llama / "include/llama.h", headers / "llama-current.h")
        shutil.copyfile(ROOT / "engine/swift/llama-compat.h", headers / "llama.h")
        for header in (llama / "ggml/include").glob("*.h"):
            shutil.copyfile(header, headers / header.name)
        manifest = manifest.replace('    targets: [\n', '    targets: [\n        .systemLibrary(name: "llama.cpp"),\n')
        manifest = manifest.replace('"SwiftUtils", "EfficientNGram",', '"SwiftUtils", "EfficientNGram", "llama.cpp",')
        manifest = manifest.replace('package: "swift-collections")], swiftSettings: settings)',
                                    'package: "swift-collections")], swiftSettings: settings + [.define("ZenzaiCPU")])')
    else:
        for name in ("ZenzContext", "ZenzInputTextGenerator", "ZenzPureGreedyDecoder"):
            (zenz / f"{name}.swift").unlink()
        for name, marker in (("ZenzCandidateEvaluator", "struct ZenzCandidateEvaluator"),
                             ("ZenzCompatibleInputLanguageModelContext", "extension ZenzContext")):
            path = zenz / f"{name}.swift"
            content = path.read_text(encoding="utf-8")
            path.write_text(content[:content.index(marker)].rstrip() + "\n", encoding="utf-8")
        shutil.copyfile(WEB / "DictionaryOnlyZenz.swift", zenz / "Zenz.swift")
    (target / "Package.swift").write_text(manifest, encoding="utf-8")


def stage_app(app, source, llama, llama_bridge=False):
    manifest = APP_MANIFEST
    if llama:
        windows = ROOT / "windows/server-swift/Sources"
        for path in (windows / "azookey-server").glob("*.swift"):
            if path.name in ("JevCandidateSelector.swift", "ConversionBenchmark.swift"):
                continue  # Remote-only reranking and native benchmark harness aren't part of the WASI route.
            text = path.read_text(encoding="utf-8").replace(", options: .atomic)", ")")
            text = re.sub(r"(?m)^import Dispatch\r?\n", "", text)
            (source / path.name).write_text(text, encoding="utf-8")
        shutil.copyfile(WEB / "WasiDispatchShim.swift", source / "WasiDispatchShim.swift")
        ffi = app / "Sources/ffi"
        headers = ffi / "include"
        headers.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(windows / "ffi/include/ffi.h", headers / "ffi.h")
        (headers / "module.modulemap").write_text('module ffi { header "ffi.h" export * }\n', encoding="utf-8")
        if llama_bridge:
            # Copy llama/ggml headers so wasi-llama-bridge.c compiles cleanly against the exact pinned ABI.
            bridge_inc = ffi / "llama_inc"
            bridge_inc.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(llama / "include/llama.h", bridge_inc / "llama-current.h")
            shutil.copyfile(ROOT / "engine/swift/llama-compat.h", bridge_inc / "llama.h")
            for header in (llama / "ggml/include").glob("*.h"):
                shutil.copyfile(header, bridge_inc / header.name)
            shutil.copyfile(WEB / "wasi-llama-bridge.c", ffi / "wasi-llama-bridge.c")
            ffi_target = '.target(name: "ffi", cSettings: [.headerSearchPath("llama_inc")])'
        else:
            shutil.copyfile(WEB / "wasi-exceptions.c", ffi / "wasi-exceptions.c")
            ffi_target = '.target(name: "ffi")'
        shutil.copyfile(WEB / "ModelBridge.swift", source / "ModelBridge.swift")
        manifest = manifest.replace('zerocon_dictionary', 'zerocon_model')
        manifest = manifest.replace('targets: [.executableTarget', f'targets: [{ffi_target}, .executableTarget')
        manifest = manifest.replace('name: "App", dependencies: [', 'name: "App", dependencies: ["ffi", ')
    else:
        shutil.copyfile(WEB / "DictionaryBridge.swift", source / "DictionaryBridge.swift")
    (app / "Package.swift").write_text(manifest, encoding="utf-8")
    (source / "main.swift").write_text("// WASI entry point. The browser uses the exported bridge.\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("converter", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--dependency-cache", type=Path)
    parser.add_argument("--llama-source", type=Path)
    parser.add_argument("--llama-bridge", action="store_true",
                        help="Link wasi-llama-bridge.c instead of static libllama.a (delegates inference to Emscripten pthreads)")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    stage_dependencies(args.output, args.dependency_cache)
    stage_converter(args.converter, args.output, args.llama_source)
    app = args.output / "app"
    source = app / "Sources/App"
    source.mkdir(parents=True, exist_ok=True)
    stage_app(app, source, args.llama_source, llama_bridge=args.llama_bridge)


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
# Build the Cloudflare Pages site locally, with the same steps and checks as
# .github/workflows/deploy-cloudflare-pages.yml (which no longer deploys).
#
# Run in WSL Ubuntu 22.04 (or Linux) with: Rust + wasm32-wasip1, Node 22, Python 3.12 (python3 on PATH),
# Emscripten 3.1.74 (~/emsdk), Swift 6.4.2 Linux + WASI SDK (~/zerocon-swift), binaryen (wasm-opt), cmake, ninja.
# Output: dist/pages in this checkout. Then run web/publish-site-release.sh.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=${ZEROCON_BUILD_WORK:-$HOME/zerocon-build-work}
swift_root=${SWIFT_ROOT:-$HOME/zerocon-swift}
emsdk_root=${EMSDK_ROOT:-$HOME/emsdk}
swift_bin="$swift_root/swift-6.4.2-RELEASE-ubuntu22.04/usr/bin"

test -x "$swift_bin/swift" || { echo "Swift 6.4.2 not found at $swift_bin" >&2; exit 1; }
test -f "$emsdk_root/emsdk_env.sh" || { echo "emsdk not found at $emsdk_root" >&2; exit 1; }

# Toolchain environment, mirroring the CI setup.
. "$HOME/.cargo/env"
. "$emsdk_root/emsdk_env.sh" >/dev/null
mkdir -p "$work/bin"
ln -sf "$(uv python find 3.12)" "$work/bin/python3"
export PATH="$work/bin:$HOME/.local/bin:$swift_bin:$PATH"
export RUNNER_TEMP="$work"
python3 --version | grep -q '3\.12\.' || { echo "python3 must be 3.12" >&2; exit 1; }
node --version | grep -q '^v22\.' || { echo "Node 22 is required" >&2; exit 1; }

cd "$root"
mkdir -p "$work"
rm -rf dist/pages dist/pages-cache
mkdir -p dist/pages dist/pages-cache
echo "== Shared Rust engines and web checks"
cargo rustc --locked --manifest-path engine/rust/Cargo.toml --target wasm32-wasip1 --release --lib --crate-type cdylib -- -C link-arg=--export=malloc -C link-arg=--export=free
node web/check-assist-wasm.cjs engine/rust/target/wasm32-wasip1/release/zerocon_engine.wasm
cargo rustc --locked --manifest-path web/live-policy/Cargo.toml --target wasm32-wasip1 --release -- -C link-arg=--export=malloc -C link-arg=--export=free
node web/check-session-wasm.cjs web/live-policy/target/wasm32-wasip1/release/zerocon_web_live_policy.wasm
node web/check-worker-session.mjs
node web/check-worker-assist.mjs
node web/check-assist-session.mjs
node web/check-learning-store.mjs
node web/check-settings-store.mjs
node web/check-load-progress.mjs
node web/check-release-downloads.mjs
cp web/pages/index.html web/pages/style.css web/pages/android-keyboard.css web/pages/android-keyboard.mjs web/pages/dictionary-engine.mjs web/pages/model-*.mjs web/pages/assist-session.mjs web/pages/learning-store.mjs web/pages/load-progress.mjs web/pages/release-downloads.mjs web/pages/settings-store.mjs dist/pages/
python3 web/pack-android-keyboard.py dist/pages/android-keyboard-layout.mjs
python3 web/pack-site-theme.py dist/pages/ime-theme.css
cp web/unicode-emoji-license.txt dist/pages/
cp windows/frontend/public/favicon.png dist/pages/
cp engine/rust/target/wasm32-wasip1/release/zerocon_engine.wasm dist/pages/
cp web/live-policy/target/wasm32-wasip1/release/zerocon_web_live_policy.wasm dist/pages/
cp windows/LICENSE dist/pages/LICENSE.txt

echo "== Stage llama.cpp sources"
python3 web/stage-llama.py "$work/llama-source" --windows-source "$work/llama-windows-source" --for-emscripten

echo "== Multi-threaded Emscripten llama.cpp"
python3 web/build-llama-mt.py "$work/llama-source" "$work/llama-mt-build" dist/pages-cache/zerocon_llama_mt.mjs --pool-size 4
cp "$work/llama-source/LICENSE" dist/pages-cache/LLAMA-LICENSE.txt

echo "== Swift model Wasm (bridged to MT llama)"
set -o pipefail
converter=$(python3 windows/scripts/prepare-modern-llama.py --converter-only | python3 -c 'import json,sys; print(json.load(sys.stdin)["converter"])')
python3 web/stage-dictionary.py "$converter" "$work/model-source" --llama-source "$work/llama-source" --llama-bridge
swift build -c release \
    --swift-sdks-path "$swift_root" --swift-sdk swift-6.4.2-RELEASE_wasm \
    --package-path "$work/model-source/app" --scratch-path "$work/model-build" \
    -Xswiftc -Xclang-linker -Xswiftc -mexec-model=reactor \
    -Xlinker --export=malloc -Xlinker --export=free -Xlinker --export=zerocon_model_request \
    -Xlinker -lwasi-emulated-signal -Xlinker -lwasi-emulated-mman -Xlinker -lwasi-emulated-process-clocks
wasm=$(find "$work/model-build" -name zerocon_model.wasm -type f -print -quit)
test -n "$wasm"
"$swift_bin/llvm-objcopy" --strip-debug --remove-section=name "$wasm" dist/pages-cache/zerocon_model.wasm
if command -v wasm-opt >/dev/null 2>&1; then
    wasm-opt -O3 --enable-simd --enable-bulk-memory --enable-nontrapping-float-to-int \
        dist/pages-cache/zerocon_model.wasm -o dist/pages-cache/zerocon_model.wasm.opt \
        && mv dist/pages-cache/zerocon_model.wasm.opt dist/pages-cache/zerocon_model.wasm || true
fi
cp "$converter/LICENSE" dist/pages-cache/CONVERTER-LICENSE.txt
set +o pipefail

echo "== Pinned dictionary"
git submodule update --init --depth 1 windows/server-swift/azooKey_dictionary_storage
dictionary=windows/server-swift/azooKey_dictionary_storage
node windows/scripts/generate-reverse-dictionary.mjs --source "$dictionary/Dictionary/louds" --output "$work/reverse-v2.bin"
python3 web/pack-dictionary.py "$dictionary/Dictionary" dist/pages-cache/dictionary.gz --reverse "$work/reverse-v2.bin"
cp "$dictionary/LICENSE" dist/pages-cache/DICTIONARY-LICENSE.txt

echo "== Assemble, fetch catalog model, verify, optimize"
cp dist/pages-cache/zerocon_llama_mt.mjs dist/pages/
cp dist/pages-cache/zerocon_llama_mt.wasm dist/pages/
cp dist/pages-cache/zerocon_llama_mt.worker.* dist/pages/ 2>/dev/null || true
cp dist/pages-cache/zerocon_model.wasm dist/pages/
cp dist/pages-cache/dictionary.gz dist/pages/
cp dist/pages-cache/reverse-v2.bin.gz dist/pages/
cp dist/pages-cache/DICTIONARY-LICENSE.txt dist/pages/
cp dist/pages-cache/CONVERTER-LICENSE.txt dist/pages/
cp dist/pages-cache/LLAMA-LICENSE.txt dist/pages/

python3 web/pack-model.py dist/pages --cache-dir "$work/model-cache"
python3 web/pack-candidate-window.py dist/pages/candidate-window.html
python3 web/pack-reading-window.py dist/pages/reading-window.html

node --test --test-name-pattern='^(empty candidates|short candidate lists|long candidate lists|render ranges|render range|selection indices|selection scrolling)' windows/crates/ui/tests/candidate_scroller.test.cjs
(cd web && npm ci --ignore-scripts --no-audit)
cp -R web/node_modules/@bjorn3/browser_wasi_shim dist/pages/wasi

# Verify multi-threaded Wasm engines before Cloudflare gzip/chunking
node web/check-model-wasm.mjs dist/pages
node web/check-model-assist.mjs dist/pages
node web/check-model-learning.mjs dist/pages

# Gzip large Wasm, generate SHA-256 asset-manifest, split any >24 MiB files, write COOP/COEP _headers
python3 web/pack-cloudflare-assets.py dist/pages

echo "BUILD_SITE_DONE: $(du -sh dist/pages | cut -f1) in dist/pages"

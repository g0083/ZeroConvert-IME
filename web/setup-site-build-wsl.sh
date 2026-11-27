#!/usr/bin/env bash
# One-time toolchain setup for web/build-site.sh in WSL Ubuntu 22.04. Idempotent; safe to re-run.
set -euo pipefail
echo "== apt"; sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cmake ninja-build build-essential unzip xz-utils pkg-config binaryen ca-certificates
echo "== node 22"
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" != "22" ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - >/dev/null
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs
fi
node --version
echo "== rust"
if [ ! -x "$HOME/.cargo/bin/rustup" ]; then curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal -q; fi
. "$HOME/.cargo/env"; rustup target add wasm32-wasip1; rustc --version
echo "== python 3.12 (uv)"
if ! command -v uv >/dev/null; then curl -LsSf https://astral.sh/uv/install.sh | sh; fi
export PATH="$HOME/.local/bin:$PATH"; uv python install 3.12 >/dev/null; uv python find 3.12
echo "== emsdk 3.1.74"
if [ ! -d "$HOME/emsdk" ]; then git clone -q --depth 1 https://github.com/emscripten-core/emsdk.git "$HOME/emsdk"; fi
cd "$HOME/emsdk" && ./emsdk install 3.1.74 && ./emsdk activate 3.1.74
echo "== swift 6.4.2 + wasm sdk"
root="$HOME/zerocon-swift"; mkdir -p "$root"; cd "$root"
server=https://download.swift.org/swift-6.4.2-release
[ -f swift-linux.tar.gz ] || curl --fail --silent --show-error --location --retry 3 "$server/ubuntu2204/swift-6.4.2-RELEASE/swift-6.4.2-RELEASE-ubuntu22.04.tar.gz" -o swift-linux.tar.gz
[ -f swift-wasm.tar.gz ] || curl --fail --silent --show-error --location --retry 3 "$server/wasm-sdk/swift-6.4.2-RELEASE/swift-6.4.2-RELEASE_wasm.artifactbundle.tar.gz" -o swift-wasm.tar.gz
echo 'c3081b1817117c66e4764d2a148357d997200048922c778b0f08c795833ae06d  swift-linux.tar.gz' | sha256sum --check
echo '83baca83553f8c443cda7b84800912e6e709c3acd2e7ebf277794ca5836c0da5  swift-wasm.tar.gz' | sha256sum --check
[ -d swift-6.4.2-RELEASE-ubuntu22.04 ] || tar -xzf swift-linux.tar.gz
tar -xzf swift-wasm.tar.gz
rm -f swift-linux.tar.gz swift-wasm.tar.gz
"$root/swift-6.4.2-RELEASE-ubuntu22.04/usr/bin/swift" --version
echo BOOTSTRAP_DONE

// Stage byte-identical Windows sources for SwiftPM; generated files are ignored.
import { cp as copy, mkdir, readFile, writeFile, readdir, lstat, realpath, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cp = (source, destination, options = {}) => copy(source, destination, { preserveTimestamps: true, ...options });
const stage = path.join(root, 'engine/build/swift');
const checkout = path.join(root, 'windows/server-swift/.build/checkouts/AzooKeyKanaKanjiConverter');
await mkdir(stage, { recursive: true });
// Prune only stale generated files. Empty directories and build caches remain;
// no repository sources, checkouts or recursively computed paths are deleted.
async function pruneStaleFiles(destination, source, generatedRoot = stage) {
  const destinationInfo = await lstat(destination).catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (!destinationInfo) return;
  if (destinationInfo.isSymbolicLink()) throw new Error(`Refusing to traverse staged link: ${destination}`);
  const relative = path.relative(await realpath(generatedRoot), await realpath(destination));
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Invalid generated staging path');
  for (const entry of await readdir(destination, { withFileTypes: true })) {
    const target = path.join(destination, entry.name);
    const original = path.join(source, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Refusing to traverse staged link: ${target}`);
    if (entry.isDirectory()) await pruneStaleFiles(target, original, generatedRoot);
    else if (!(await lstat(original).catch(error => {
      if (error.code === 'ENOENT') return null;
      throw error;
    }))) await unlink(target);
  }
}
await pruneStaleFiles(path.join(stage, 'Sources'), path.join(root, 'windows/server-swift/Sources'));
await pruneStaleFiles(path.join(stage, 'converter'), checkout);
await cp(path.join(root, 'windows/server-swift/Sources'), path.join(stage, 'Sources'), { recursive: true });
await cp(path.join(root, 'engine/swift/AndroidBridge.swift'), path.join(stage, 'Sources/azookey-server/AndroidBridge.swift'));
await cp(path.join(root, 'engine/native/inference_control.h'), path.join(stage, 'Sources/ffi/include/inference_control.h'));
await writeFile(path.join(stage, 'Sources/ffi/include/android-host.h'), '#include "ffi.h"\n#include "inference_control.h"\n');
await cp(checkout, path.join(stage, 'converter'), {
  recursive: true, filter: source => !['.git', '.build', '.swiftpm', 'Tests', '.github'].includes(path.basename(source)),
});
const llama = path.join(stage, 'converter/Sources/llama.cpp');
const native = path.join(root, 'android/app/src/main/cpp/llama.cpp');
const pins = JSON.parse(await readFile(path.join(root, 'windows/server-swift/Package.resolved'), 'utf8')).pins;
// The Windows converter is a local path dependency (Package.swift uses
// ZEROCON_CONVERTER_SOURCE), so Package.resolved no longer pins it. Keep the
// Android stage pinned to the converter revision prepared for Windows.
const LOCAL_CONVERTER_REVISION = 'b14b61ccf83c15fdb8679b68baadcac5235ba17b';
const converterPin = pins.find(pin => pin.identity === 'azookeykanakanjiconverter');
const converterRevision = converterPin?.state?.revision ?? LOCAL_CONVERTER_REVISION;
if (execFileSync('git', ['-C', checkout, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== converterRevision) {
  throw new Error('Windows converter checkout differs from the pinned Android converter revision; resolve the Windows dependency before building Android.');
}
const pinnedDependencies = pins.filter(pin => pin.identity !== 'azookeykanakanjiconverter').map(pin =>
  `.package(url: ${JSON.stringify(pin.location)}, ${pin.state.version ? `exact: ${JSON.stringify(pin.state.version)}` : `revision: ${JSON.stringify(pin.state.revision)}`})`
).join(',\n');
await cp(path.join(native, 'include/llama.h'), path.join(llama, 'llama-current.h'));
await cp(path.join(root, 'engine/swift/llama-compat.h'), path.join(llama, 'llama.h'));
await cp(path.join(root, 'engine/swift/LlamaCompatibility.swift'), path.join(stage, 'converter/Sources/KanaKanjiConverterModule/LlamaCompatibility.swift'));
for (const file of await readdir(path.join(native, 'ggml/include'))) {
  if (file.endsWith('.h')) await cp(path.join(native, 'ggml/include', file), path.join(llama, file));
}
// ffi's checked-in modulemap is designed for the Windows compiler include path.
await writeFile(path.join(stage, 'Sources/ffi/module.modulemap'), 'module ffi { header "include/android-host.h" export * }\n');
await writeFile(path.join(stage, 'Sources/ffi/include/module.modulemap'), 'module ffi { header "android-host.h" export * }\n');
await writeFile(path.join(stage, 'Package.swift'), `// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "ZeroConEmbedded", products: [
    .library(name: "zerocon_swift", type: .dynamic, targets: ["azookey-server"])
], dependencies: [.package(path: "converter", traits: ["ZenzaiCPU"]), ${pinnedDependencies}], targets: [
    .target(name: "ffi"),
    .target(name: "azookey-server", dependencies: ["ffi",
        .product(name: "KanaKanjiConverterModule", package: "converter")
    ], swiftSettings: [.interoperabilityMode(.Cxx)])
])
`);
const assets = path.join(root, 'engine/build/assets/shared-engine');
await mkdir(assets, { recursive: true });
await pruneStaleFiles(path.join(assets, 'Dictionary'), path.join(root, 'windows/server-swift/azooKey_dictionary_storage/Dictionary'), assets);
await cp(path.join(root, 'windows/server-swift/azooKey_dictionary_storage/Dictionary'), path.join(assets, 'Dictionary'), {
  recursive: true, filter: source => !['.git', '.gitkeep'].includes(path.basename(source)),
});
await mkdir(path.join(assets, 'Dictionary/Reverse'), { recursive: true });
execFileSync(process.execPath, [path.join(root, 'windows/scripts/generate-reverse-dictionary.mjs'),
  '--source', path.join(root, 'windows/server-swift/azooKey_dictionary_storage/Dictionary/louds'),
  '--output', path.join(assets, 'Dictionary/Reverse/reverse-v2.bin')], { stdio: 'inherit' });
await mkdir(path.join(assets, 'EmojiDictionary'), { recursive: true });
await cp(path.join(root, 'android/app/src/main/assets/emoji_all_E15.1.txt'), path.join(assets, 'EmojiDictionary/emoji_all_E15.1.txt'));
const sourceFiles = [];
async function fingerprint(dir, relative) {
  for (const entry of (await readdir(dir, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
    const rel = `${relative}/${entry.name}`;
    if (entry.isDirectory()) await fingerprint(path.join(dir, entry.name), rel);
    else sourceFiles.push({ path: rel, sha256: createHash('sha256').update(await readFile(path.join(dir, entry.name))).digest('hex') });
  }
}
await fingerprint(path.join(root, 'windows/server-swift/Sources'), 'windows/server-swift/Sources');
await fingerprint(path.join(root, 'windows/crates/server/src/ai_assist'), 'windows/crates/server/src/ai_assist');
await fingerprint(path.join(checkout, 'Sources/KanaKanjiConverterModule'), 'windows/server-swift/.build/checkouts/AzooKeyKanaKanjiConverter/Sources/KanaKanjiConverterModule');
await fingerprint(path.join(root, 'windows/server-swift/azooKey_dictionary_storage/Dictionary'), 'windows/server-swift/azooKey_dictionary_storage/Dictionary');
for (const directory of ['engine/swift', 'engine/native', 'engine/rust/src']) await fingerprint(path.join(root, directory), directory);
for (const file of ['windows/crates/shared/src/conversion_policy.rs', 'windows/server-swift/Package.swift', 'windows/server-swift/Package.resolved', 'engine/rust/Cargo.toml', 'engine/rust/Cargo.lock',
  'android/app/src/main/cpp/zenz_jni.cpp', 'android/app/src/main/cpp/assist_jni.cpp', 'android/app/src/main/cpp/azookey_louds.cpp', 'android/app/src/main/cpp/azookey_louds.h']) {
  sourceFiles.push({ path: file, sha256: createHash('sha256').update(await readFile(path.join(root, file))).digest('hex') });
}
await writeFile(path.join(assets, 'sources.json'), JSON.stringify({ schema: 1, sourceFiles }, null, 2) + '\n');
console.log('Staged Windows Swift sources, native header adapter, dictionary and source fingerprints.');

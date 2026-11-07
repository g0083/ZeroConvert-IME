// Stage byte-identical Windows sources for SwiftPM; generated files are ignored.
import { cp as copy, mkdir, readFile, writeFile, readdir, lstat, realpath, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { NATIVE_INPUTS, SHARED_INPUTS, SKIPPED_SOURCE_NAMES } from './shared-engine-inputs.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cp = (source, destination, options = {}) => copy(source, destination, { preserveTimestamps: true, ...options });
const stage = path.join(root, 'engine/build/swift');
// The Windows converter is a vendored, checksum-pinned artifact. Resolve it
// through the same preparation entry point the Windows build uses, so Android
// always stages the exact converter revision Windows compiles with.
// `--converter-only` skips the Windows llama runtime, which Android does not
// use: the staged headers come from the Android llama.cpp submodule below. That
// keeps this staging step runnable on a machine without the Windows toolchain.
const preparer = path.join(root, 'windows/scripts/prepare-modern-llama.py');
const pinnedRevision = (await readFile(preparer, 'utf8')).match(/CONVERTER\s*=\s*'([0-9a-f]{40})'/)?.[1];
if (!pinnedRevision) throw new Error('Could not read the pinned converter revision from prepare-modern-llama.py');
const prepared = JSON.parse(execFileSync('python', [
  path.join(root, 'scripts/run.py'), 'windows/scripts/prepare-modern-llama.py', '--converter-only',
], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] }));
const converterSource = path.resolve(prepared.converter);
if (path.basename(converterSource) !== `AzooKeyKanaKanjiConverter-${pinnedRevision}`) {
  throw new Error(`Vendored converter does not match the pinned revision ${pinnedRevision}; resolve the Windows dependency before building Android.`);
}
const converterRelative = path.relative(root, converterSource).split(path.sep).join('/');
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
await pruneStaleFiles(path.join(stage, 'converter'), converterSource);
await cp(path.join(root, 'windows/server-swift/Sources'), path.join(stage, 'Sources'), { recursive: true });
await cp(path.join(root, 'engine/swift/AndroidBridge.swift'), path.join(stage, 'Sources/azookey-server/AndroidBridge.swift'));
await cp(path.join(root, 'engine/native/inference_control.h'), path.join(stage, 'Sources/ffi/include/inference_control.h'));
await writeFile(path.join(stage, 'Sources/ffi/include/android-host.h'), '#include "ffi.h"\n#include "inference_control.h"\n');
await cp(converterSource, path.join(stage, 'converter'), {
  recursive: true, filter: source => !['.git', '.build', '.swiftpm', 'Tests', '.github'].includes(path.basename(source)),
});
const llama = path.join(stage, 'converter/Sources/llama.cpp');
const native = path.join(root, 'android/app/src/main/cpp/llama.cpp');
// Android resolves its Swift dependencies from this committed file, not from
// the Windows checkout. `scripts/check-shared-engine-pins.mjs` keeps it equal to
// the Windows pins so a Windows bump cannot silently leave Android behind.
const pins = JSON.parse(await readFile(path.join(root, 'engine/Package.resolved'), 'utf8')).pins;
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
    if (SKIPPED_SOURCE_NAMES.includes(entry.name)) continue;
    const rel = `${relative}/${entry.name}`;
    if (entry.isDirectory()) await fingerprint(path.join(dir, entry.name), rel);
    else sourceFiles.push({ path: rel, sha256: createHash('sha256').update(await readFile(path.join(dir, entry.name))).digest('hex') });
  }
}
await fingerprint(path.join(root, 'windows/server-swift/Sources'), 'windows/server-swift/Sources');
await fingerprint(path.join(root, 'windows/crates/server/src/ai_assist'), 'windows/crates/server/src/ai_assist');
await fingerprint(path.join(converterSource, 'Sources/KanaKanjiConverterModule'), `${converterRelative}/Sources/KanaKanjiConverterModule`);
await fingerprint(path.join(root, 'windows/server-swift/azooKey_dictionary_storage/Dictionary'), 'windows/server-swift/azooKey_dictionary_storage/Dictionary');
for (const directory of ['engine/swift', 'engine/native', 'engine/rust/src']) await fingerprint(path.join(root, directory), directory);
for (const file of [...NATIVE_INPUTS, ...SHARED_INPUTS]) {
  sourceFiles.push({ path: file, sha256: createHash('sha256').update(await readFile(path.join(root, file))).digest('hex') });
}
await writeFile(path.join(assets, 'sources.json'), JSON.stringify({ schema: 1, sourceFiles }, null, 2) + '\n');

// One committed record of the engine revision an APK was built from. The
// per-file manifest above decides rebuilds; this digest set is what a packaged
// APK can be matched against without shipping thousands of file hashes.
const digestOf = entries => createHash('sha256')
  .update(entries.map(entry => `${entry.path}:${entry.sha256}`).join('\n')).digest('hex');
const group = prefix => sourceFiles.filter(entry => entry.path.startsWith(prefix));
const fileDigest = async file => createHash('sha256').update(await readFile(path.join(root, file))).digest('hex');
const lock = {
  schema: 1,
  converterRevision: pinnedRevision,
  converterPatch: await fileDigest('windows/scripts/converter_patch.py'),
  swiftPins: await fileDigest('engine/Package.resolved'),
  rustLock: await fileDigest('engine/rust/Cargo.lock'),
  swiftSources: digestOf(group('windows/server-swift/Sources/')),
  converterSources: digestOf(group(`${converterRelative}/Sources/KanaKanjiConverterModule/`)),
  dictionary: digestOf(group('windows/server-swift/azooKey_dictionary_storage/Dictionary/')),
  engineSources: digestOf([
    ...group('engine/swift/'), ...group('engine/native/'), ...group('engine/rust/src/'),
  ]),
  nativeSources: digestOf(group('android/app/src/main/cpp/')),
};
const lockText = JSON.stringify(lock, null, 2) + '\n';
await writeFile(path.join(root, 'engine/shared-engine.lock.json'), lockText);
await writeFile(path.join(assets, 'lock.json'), lockText);
console.log('Staged Windows Swift sources, native header adapter, dictionary and source fingerprints.');
console.log(`Shared engine lock: converter ${lock.converterRevision}, swift ${lock.swiftSources.slice(0, 12)}.`);

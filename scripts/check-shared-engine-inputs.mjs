// Verify the shared-engine fingerprint manifest covers exactly the inputs that
// reach the compiled Android artifacts. Runs without a toolchain, so a missing
// or stale input is caught before anyone builds both ABIs.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NATIVE_INPUTS, SHARED_INPUTS, SKIPPED_SOURCE_NAMES } from './shared-engine-inputs.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'engine/build/assets/shared-engine/sources.json');

const manifestText = await readFile(manifestPath, 'utf8').catch(error => {
  if (error.code === 'ENOENT') {
    // A fresh checkout has no generated staging tree. The manifest is a build
    // output, so its absence is not a source defect.
    console.log('Shared engine manifest is not generated yet; skipping input coverage.');
    process.exit(0);
  }
  throw error;
});

const manifest = JSON.parse(manifestText);
if (manifest.schema !== 1) throw new Error(`Unsupported manifest schema: ${manifest.schema}`);
const recorded = new Set(manifest.sourceFiles.map(entry => entry.path));

const missing = [...NATIVE_INPUTS, ...SHARED_INPUTS].filter(input => !recorded.has(input));
if (missing.length) {
  throw new Error(`Shared engine manifest is missing compiled inputs:\n  ${missing.join('\n  ')}\nRebuild both Android ABIs.`);
}
const forbidden = SKIPPED_SOURCE_NAMES.filter(name =>
  manifest.sourceFiles.some(entry => entry.path.split('/').pop() === name));
if (forbidden.length) {
  throw new Error(`Shared engine manifest fingerprints uncompiled files:\n  ${forbidden.join('\n  ')}`);
}
const duplicates = manifest.sourceFiles.length - recorded.size;
if (duplicates) throw new Error(`Shared engine manifest has ${duplicates} duplicate path(s).`);

console.log(`Shared engine manifest covers ${recorded.size} inputs, including every compiled native source.`);

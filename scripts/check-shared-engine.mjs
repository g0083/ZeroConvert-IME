import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestText = await readFile(path.join(root, 'engine/build/assets/shared-engine/sources.json'), 'utf8');
const manifest = JSON.parse(manifestText);
const digest = value => createHash('sha256').update(value).digest('hex');
for (const source of manifest.sourceFiles) {
  const actual = digest(await readFile(path.join(root, source.path)));
  if (actual !== source.sha256) throw new Error(`Shared engine is stale: ${source.path}. Rebuild both Android ABIs.`);
}
const abis = process.argv.includes('--arm64-only') ? ['arm64-v8a'] : ['arm64-v8a', 'x86_64'];
for (const abi of abis) {
  const dir = path.join(root, 'engine/build/jniLibs', abi);
  if (await readFile(path.join(dir, 'build-inputs.json'), 'utf8') !== manifestText) throw new Error(`Rebuild shared engine for ${abi}.`);
  const libraries = JSON.parse((await readFile(path.join(dir, 'libraries.json'), 'utf8')).replace(/^\uFEFF/, ''));
  for (const library of libraries) {
    if (digest(await readFile(path.join(dir, library.name))) !== library.sha256) throw new Error(`Shared binary mismatch: ${abi}/${library.name}`);
  }
}
console.log(`Shared Windows source and native binary fingerprints verified (${abis.join(', ')}).`);

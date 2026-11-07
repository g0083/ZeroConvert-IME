// Keep the Android Swift dependency pins equal to the Windows ones.
//
// Android resolves from `engine/Package.resolved` so its build does not depend
// on the Windows checkout. That copy must not drift: a Windows dependency bump
// has to reach Android in the same commit.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const read = async file => JSON.parse(await readFile(path.join(root, file), 'utf8'));
const windows = await read('windows/server-swift/Package.resolved');
const android = await read('engine/Package.resolved');

const describe = pins => pins
  .filter(pin => pin.identity !== 'azookeykanakanjiconverter')
  .map(pin => `${pin.identity} ${pin.state.version ?? pin.state.revision} ${pin.location}`)
  .sort();

const expected = describe(windows.pins);
const actual = describe(android.pins);
const missing = expected.filter(pin => !actual.includes(pin));
const extra = actual.filter(pin => !expected.includes(pin));

if (missing.length || extra.length) {
  const lines = [];
  if (missing.length) lines.push(`  missing from engine/Package.resolved:\n    ${missing.join('\n    ')}`);
  if (extra.length) lines.push(`  only in engine/Package.resolved:\n    ${extra.join('\n    ')}`);
  throw new Error(
    `Android Swift pins differ from the Windows pins:\n${lines.join('\n')}\n`
    + 'Copy windows/server-swift/Package.resolved to engine/Package.resolved and rebuild both Android ABIs.',
  );
}

console.log(`Android Swift pins match the Windows pins (${actual.length} dependencies).`);

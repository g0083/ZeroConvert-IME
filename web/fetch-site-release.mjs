// Cloudflare Pages build step: download the locally built site pinned in site-release.json
// from the GitHub Release, verify it, and unpack it into dist/pages.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const pin = JSON.parse(readFileSync(new URL('./site-release.json', import.meta.url), 'utf8'));
if (!pin.tag || !pin.asset || !pin.sha256 || !pin.bytes) {
    throw new Error('web/site-release.json must name tag, asset, sha256 and bytes');
}

const url = `https://github.com/${pin.repository}/releases/download/${pin.tag}/${pin.asset}`;
console.log(`Fetching ${url}`);
const response = await fetch(url, { redirect: 'follow' });
if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
const archive = Buffer.from(await response.arrayBuffer());

if (archive.length !== pin.bytes) {
    throw new Error(`Size mismatch for ${pin.asset}: expected ${pin.bytes}, got ${archive.length}`);
}
const digest = createHash('sha256').update(archive).digest('hex');
if (digest !== pin.sha256) {
    throw new Error(`SHA-256 mismatch for ${pin.asset}: expected ${pin.sha256}, got ${digest}`);
}

const archivePath = join(tmpdir(), pin.asset);
writeFileSync(archivePath, archive);
rmSync('dist/pages', { recursive: true, force: true });
mkdirSync('dist/pages', { recursive: true });
execFileSync('tar', ['-xzf', archivePath, '-C', 'dist/pages'], { stdio: 'inherit' });
rmSync(archivePath, { force: true });
console.log(`Unpacked ${pin.asset} (${pin.tag}, commit ${pin.commit}) into dist/pages`);

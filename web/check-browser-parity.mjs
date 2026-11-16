// Browser test against the same staged directory published by the Pages workflow.
import assert from 'node:assert/strict';
import { createReadStream } from 'node:fs';
import { mkdir, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, resolve, sep } from 'node:path';
import { once } from 'node:events';

const [site, ...channels] = process.argv.slice(2);
if (!site) throw new Error('Pass the staged site directory and optionally browser channels');
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = resolve(site);
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
    '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm' };
const server = createServer(async (request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    const file = resolve(join(root, pathname === '/' ? 'index.html' : pathname));
    if (!file.startsWith(root + sep)) {
        response.writeHead(404).end();
        return;
    }
    try {
        const info = await stat(file);
        if (!info.isFile()) throw new Error('Not a file');
        response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream',
            'Content-Length': info.size });
        createReadStream(file).on('error', () => response.destroy()).pipe(response);
    } catch {
        response.writeHead(404).end();
    }
});

server.listen(0, '127.0.0.1');
await once(server, 'listening');
try {
    for (const channel of channels.length ? channels : [undefined]) {
        const browser = await chromium.launch({ channel });
        try {
            const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            const siteUrl = `http://127.0.0.1:${server.address().port}/`;
            const modelRoute = '**/*.gguf';
            const blockModel = route => route.abort('failed');
            await page.route(modelRoute, blockModel);
            await page.goto(siteUrl);
            const retry = page.locator('#retry-model');
            await retry.waitFor({ state: 'visible' });
            assert.equal(await page.locator('#romaji').isDisabled(), true);
            await page.unroute(modelRoute, blockModel);

            let releaseModel;
            const paused = new Promise(resolve => { releaseModel = resolve; });
            const delayModel = async route => { await paused; await route.continue(); };
            await page.route(modelRoute, delayModel);
            await retry.click();
            await page.waitForFunction(() => document.querySelector('[data-load-item="model"]').dataset.state === 'downloading');
            assert.match(await page.locator('[data-load-item="model"]').textContent(), /取得/);
            assert.equal(await page.locator('#romaji').isDisabled(), true);
            releaseModel();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
            await page.unroute(modelRoute, delayModel);
            assert.equal(await page.locator('#loader').getAttribute('data-state'), 'ready');
            assert.equal(await page.locator('.progress-items [data-state="done"]').count(), 3);
            const input = page.locator('#romaji');
            const reading = page.locator('#reading');
            const frame = page.locator('#candidate-window');
            const before = page.locator('#composition-before');
            const caret = page.locator('#composition-caret');
            const waitText = (selector, expected) => page.waitForFunction(
                ([selector, expected]) => document.querySelector(selector).textContent === expected, [selector, expected],
            );

            await input.click();
            await input.pressSequentially('ashitanok', { delay: 12 });
            await waitText('#reading', 'あしたのk');
            await input.press('Backspace');
            await waitText('#reading', 'あしたの');
            await input.pressSequentially('kaigi', { delay: 12 });
            await waitText('#reading', 'あしたのかいぎ');
            assert.equal(await before.textContent(), '明日の会議');
            assert.equal(await page.locator('#reading-ruby').isVisible(), true);
            await frame.waitFor({ state: 'visible' });
            const start = await caret.boundingBox();
            await input.press('Shift+ArrowLeft');
            await page.waitForFunction(x => document.querySelector('#composition-caret').getBoundingClientRect().x < x, start.x);
            await input.press('Shift+ArrowRight');
            await page.waitForFunction(x => Math.abs(document.querySelector('#composition-caret').getBoundingClientRect().x - x) < 1, start.x);
            await input.press('ArrowLeft');
            await page.waitForFunction(x => document.querySelector('#composition-caret').getBoundingClientRect().x < x, start.x);
            const moved = await caret.boundingBox();
            const popup = await frame.boundingBox();
            assert.ok(Math.abs(popup.x - moved.x) <= 2, 'candidate window follows the moved caret');
            await input.press('Delete');
            await waitText('#reading', 'あしたのかい');
            await input.press('Escape');
            await waitText('#reading', '');

            await input.pressSequentially('nihongo', { delay: 12 });
            await waitText('#reading', 'にほんご');
            await input.press('Space');
            await input.press('ArrowUp');
            await page.frameLocator('#candidate-window').locator('.candidate-item').first().click();
            await waitText('#confirmed', '日本語');
            assert.equal(await input.evaluate(element => element === document.activeElement), true);
            await page.locator('summary').focus();
            await frame.waitFor({ state: 'hidden' });
            await input.focus();
            assert.equal(await page.locator('#confirmed').textContent(), '日本語');

            await page.reload();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
            await input.click();
            await input.pressSequentially('okseri', { delay: 12 });
            await waitText('#reading', 'おかえり');
            await page.waitForFunction(() => document.querySelector('#assist').textContent.includes('おかえり'));
            const badge = await page.locator('#assist').boundingBox();
            const nearbyPopup = await frame.boundingBox();
            assert.ok(nearbyPopup.y >= badge.y + badge.height - 1 || nearbyPopup.y + nearbyPopup.height <= badge.y + 1,
                'candidate popup keeps the correction visible');
            const screenshots = process.env.ZEROCON_BROWSER_SCREENSHOTS;
            if (screenshots) {
                await mkdir(screenshots, { recursive: true });
                await page.screenshot({ path: join(screenshots, `${channel ?? 'chromium'}-assist.png`) });
            }
            await input.press('Backspace');
            await waitText('#reading', 'おkせり');
            await input.press('Escape');
            await waitText('#reading', '');

            await page.locator('summary').click();
            await page.locator('#enable-correction').uncheck();
            await page.locator('#enable-prediction').uncheck();
            await page.locator('#learning-mode').selectOption('enabled');
            await input.focus();
            await input.pressSequentially('hashi', { delay: 12 });
            await waitText('#reading', 'はし');
            const candidateRows = page.frameLocator('#candidate-window').locator('.candidate-item');
            const bridge = candidateRows.filter({ hasText: '橋' }).first();
            await bridge.click();
            await waitText('#confirmed', '橋');
            await page.waitForFunction(async () => {
                const { openLearningStore } = await import('./learning-store.mjs');
                const saved = await (await openLearningStore()).read();
                return saved.files.some(([name]) => name === 'selection-overrides.json');
            });
            await page.reload();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
            await input.focus();
            await input.pressSequentially('hashi', { delay: 12 });
            await waitText('#composition-before', '橋');
            const learned = candidateRows.first();
            await learned.click({ button: 'right' });
            await learned.click({ button: 'right' });
            await page.waitForFunction(() => document.querySelector('#learning-status').textContent.includes('選んだ候補'));
            await waitText('#composition-before', 'はし');

            // The real IndexedDB implementation guards concurrent tab writes.
            const storage = await page.evaluate(async () => {
                const { openLearningStore } = await import('./learning-store.mjs');
                const a = await openLearningStore();
                const b = await openLearningStore();
                const { version: v1 } = await a.read();
                const { version: v2 } = await b.read();
                const first = await a.save(v1, [['sample.json', new Uint8Array([42])]]);
                const stale = await b.save(v2, []);
                const { files } = await b.read();
                await a.save(first, []);
                return { saved: first === v1 + 1, stale, byte: files[0]?.[1][0] };
            });
            assert.deepEqual(storage, { saved: true, stale: null, byte: 42 });
            assert.deepEqual(errors, []);
            console.log(`${channel ?? 'Chromium'}: load and retry, typing, correction undo, candidates, and IndexedDB passed`);
        } finally {
            await browser.close();
        }
    }
} finally {
    server.close();
}

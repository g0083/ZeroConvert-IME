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
// Use the actual conversion engine and control only the nested assist Worker.
const assistFixture = `
    self.onmessage = ({ data: { type, id, input } }) => {
        if (type === 'initialize') {
            postMessage({ loadState: 'ready' });
            return;
        }
        let answer = null;
        if (input.op === 'correction' && input.raw === 'okseri') {
            answer = { text: 'おかえり', relevance: 30, autoApply: false };
        } else if (input.op === 'prediction' && input.input === 'おかえり') {
            answer = { text: 'おかえりなさい。', continuation: 'なさい。', relevance: 60 };
        }
        postMessage({ id, answer });
    };
`;
let useAssistFixture = false;
const server = createServer(async (request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    if (useAssistFixture && pathname === '/model-assist-worker.mjs') {
        response.writeHead(200, { 'Content-Type': mime['.mjs'], 'Cache-Control': 'no-store' }).end(assistFixture);
        return;
    }
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

async function checkKeyboardWidth(page) {
    const layout = await page.locator('#android-keyboard').evaluate(keyboard => {
        const { left, right, top, bottom } = keyboard.getBoundingClientRect();
        return {
            left, right, top, bottom,
            viewport: document.documentElement.clientWidth,
            scrollWidth: document.documentElement.scrollWidth,
            loaderBottom: document.querySelector('.loader').getBoundingClientRect().bottom,
            actionsTop: document.querySelector('.demo-actions').getBoundingClientRect().top,
        };
    });
    assert.ok(Math.abs(layout.left) <= 1 && Math.abs(layout.right - layout.viewport) <= 1, JSON.stringify(layout));
    assert.equal(layout.scrollWidth, layout.viewport);
    assert.ok(layout.loaderBottom <= layout.top && layout.bottom <= layout.actionsTop, JSON.stringify(layout));
}

async function checkAndroid(browser, siteUrl, channel) {
    const page = await browser.newPage({
        viewport: { width: 390, height: 820 }, isMobile: true, hasTouch: true,
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148',
    });
    try {
        const downloads = [];
        page.context().on('request', request => {
            if (request.url().endsWith('.gguf')) downloads.push(request.url());
        });
        await page.addInitScript(() => {
            window.vibrationCalls = [];
            Object.defineProperty(navigator, 'vibrate', { configurable: true, value(duration) {
                window.vibrationCalls.push(duration);
                return true;
            } });
        });
        await page.goto(`${siteUrl}#demo`);
        await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
        const presentation = page.locator('#demo-presentation');
        const keyboard = page.locator('#android-keyboard');
        const input = page.locator('#romaji');
        const reading = page.locator('.android-original bdi');
        const candidates = page.locator('.android-content');
        const key = text => keyboard.getByRole('button', { name: `${text}を入力`, exact: true });
        const cdp = await page.context().newCDPSession(page);
        async function flick(label, dx, dy) {
            const target = key(label);
            await target.scrollIntoViewIfNeeded();
            const { x, y, width, height } = await target.boundingBox();
            const start = { x: x + width / 2, y: y + height / 2 };
            await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [start] });
            await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: start.x + dx, y: start.y + dy }] });
            await keyboard.locator('.android-flick-guide, .android-qwerty-guide').waitFor({ state: 'visible' });
            await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
        }
        const waitReading = value => page.waitForFunction(value => document.querySelector('.android-original bdi').textContent === value, value);
        const waitConfirmed = value => page.waitForFunction(value => document.querySelector('#confirmed').textContent === value, value);
        assert.equal(await presentation.inputValue(), 'android');
        assert.equal(await input.getAttribute('inputmode'), 'none');
        assert.equal(await keyboard.locator('.android-key-row').count(), 4);
        await checkKeyboardWidth(page);
        assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor), 'rgb(16, 23, 34)');
        await key('あ').tap();
        await waitReading('あ');
        assert.deepEqual(await page.evaluate(() => window.vibrationCalls), [15]);
        await keyboard.getByRole('button', { name: '削除', exact: true }).tap();
        await waitReading('');
        await flick('な', -30, 0);
        await flick('は', 0, 30);
        await flick('わ', 0, -30);
        await flick('か', 0, 30);
        await key('小゛゜').tap();
        await waitReading('にほんご');
        assert.equal(await page.locator('#composition-before').textContent(), '日本語');
        assert.equal(await page.locator('#candidate-window').isVisible(), false);
        const layoutToggle = keyboard.locator('.android-layout-toggle');
        assert.equal(await layoutToggle.isVisible(), false);

        const height = (await candidates.boundingBox()).height;
        await keyboard.getByRole('button', { name: '候補一覧を開く' }).tap();
        assert.equal((await candidates.boundingBox()).height, height);
        await keyboard.getByRole('button', { name: '候補一覧を閉じる' }).tap();
        await presentation.selectOption('windows');
        assert.equal(await keyboard.isVisible(), false);
        await input.focus();
        await page.locator('#reading-window').waitFor({ state: 'visible' });
        assert.equal(await page.locator('#reading').textContent(), 'にほんご');
        await presentation.selectOption('android');
        await waitReading('にほんご');
        const screenshotDir = process.env.ZEROCON_BROWSER_SCREENSHOTS;
        if (screenshotDir) {
            await mkdir(screenshotDir, { recursive: true });
            await keyboard.screenshot({ path: join(screenshotDir, `${channel ?? 'chromium'}-android.png`) });
        }
        await keyboard.locator('.android-candidates').getByRole('button', { name: '日本語', exact: true }).first().tap();
        await waitConfirmed('日本語');

        await layoutToggle.waitFor({ state: 'visible' });
        assert.match(await layoutToggle.textContent(), /QWERTY/);
        await layoutToggle.tap();
        assert.equal(await keyboard.locator('.android-qwerty-row').count(), 4);
        await checkKeyboardWidth(page);
        await key('k').tap();
        await key('a').tap();
        await waitReading('か');
        await input.press('Escape');
        await waitReading('');
        await flick('q', 0, -30);
        await waitReading('1');
        await input.press('Escape');
        assert.match(await layoutToggle.textContent(), /12キー/);
        if (screenshotDir) await keyboard.screenshot({ path: join(screenshotDir, `${channel ?? 'chromium'}-qwerty.png`) });
        await layoutToggle.tap();
        assert.equal(await keyboard.locator('.android-key-row').count(), 4);

        await keyboard.getByRole('button', { name: /ZeroCon AI/ }).tap();
        await page.locator('#android-vibration').uncheck();
        const pulseCount = await page.evaluate(() => window.vibrationCalls.length);
        await key('あ').tap();
        await waitReading('あ');
        assert.equal(await page.evaluate(() => window.vibrationCalls.length), pulseCount);
        await input.press('Escape');

        await page.getByRole('button', { name: '補正の例「okseri」' }).tap();
        await waitReading('おかえり');
        await keyboard.getByRole('button', { name: '補正を取り消す' }).tap();
        await waitReading('おkせり');
        await page.getByRole('button', { name: '先読みの例「よろしくお願いし」' }).tap();
        await keyboard.getByRole('button', { name: '先読み ます。 を採用' }).tap();
        await waitConfirmed('よろしくお願いします。');
        assert.equal(downloads.length, 1, 'the assist Worker reuses the conversion Worker’s model download');

        await keyboard.getByRole('button', { name: '入力モード: 日本語' }).tap();
        await key('abc').tap();
        await keyboard.getByRole('button', { name: '入力モード: 英語' }).tap();
        await key('1').tap();
        await waitConfirmed('よろしくお願いします。a1');
        console.log(`${channel ?? 'Chromium'}: mobile default, direct kana, flicks, mode switching and inline Android assist passed`);
    } finally {
        await page.close();
    }
}

server.listen(0, '127.0.0.1');
await once(server, 'listening');
try {
    for (const channel of channels.length ? channels : [undefined]) {
        useAssistFixture = false;
        const browser = await chromium.launch({ channel });
        try {
            const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            const siteUrl = `http://127.0.0.1:${server.address().port}/`;
            await checkAndroid(browser, siteUrl, channel);
            const modelRoute = '**/*.gguf';
            const blockModel = route => route.abort('failed');
            await page.route(modelRoute, blockModel);
            await page.goto(siteUrl);
            assert.equal(await page.locator('#demo-presentation').inputValue(), 'windows');
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
            const presentation = page.locator('#demo-presentation');
            const windowsWidth = await page.locator('.demo-shell').evaluate(shell => shell.offsetWidth);
            await presentation.selectOption('android');
            await checkKeyboardWidth(page);
            await presentation.selectOption('windows');
            assert.equal(await page.locator('.demo-shell').evaluate(shell => shell.offsetWidth), windowsWidth);
            const input = page.locator('#romaji');
            const reading = page.locator('#reading');
            const frame = page.locator('#candidate-window');
            const readingWindow = page.locator('#reading-window');
            const overlay = page.frameLocator('#reading-window');
            const original = overlay.locator('#reading .reading-text');
            const pill = overlay.locator('#reading .ai-assist-pill');
            const before = page.locator('#composition-before');
            const caret = page.locator('#composition-caret');
            const waitText = (selector, expected) => page.waitForFunction(
                ([selector, expected]) => document.querySelector(selector).textContent === expected, [selector, expected],
            );

            await input.click();
            await input.pressSequentially('ashitanok', { delay: 12 });
            await waitText('#reading', 'あしたのk');
            await original.waitFor({ state: 'visible' });
            assert.equal(await original.textContent(), 'あしたのk');
            await input.press('Backspace');
            await waitText('#reading', 'あしたの');
            await input.pressSequentially('kaigi', { delay: 12 });
            await waitText('#reading', 'あしたのかいぎ');
            assert.equal(await before.textContent(), '明日の会議');
            assert.equal(await original.textContent(), 'あしたのかいぎ');
            assert.ok((await readingWindow.boundingBox()).y < (await caret.boundingBox()).y);
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
            await readingWindow.waitFor({ state: 'hidden' });

            await input.pressSequentially('nihongo', { delay: 12 });
            await waitText('#reading', 'にほんご');
            await input.press('Space');
            assert.equal(await original.isVisible(), true);
            await input.press('ArrowUp');
            await page.frameLocator('#candidate-window').locator('.candidate-item').first().click();
            await waitText('#confirmed', '日本語');
            assert.equal(await input.evaluate(element => element === document.activeElement), true);
            await page.locator('summary').focus();
            await frame.waitFor({ state: 'hidden' });
            await readingWindow.waitFor({ state: 'hidden' });
            await input.focus();
            assert.equal(await page.locator('#confirmed').textContent(), '日本語');

            await page.reload();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
            await input.click();
            await input.pressSequentially('okseri', { delay: 12 });
            await waitText('#reading', 'おかえり');
            await pill.waitFor({ state: 'visible' });
            assert.equal(await original.textContent(), 'おkせり');
            assert.equal(await overlay.locator('#reading .assist-original').textContent(), 'おkせり');
            assert.equal(await overlay.locator('#reading mark').textContent(), 'かえ');
            assert.equal(await overlay.locator('#reading .assist-action').textContent(), '補正済み · Esc戻す');
            const badge = await readingWindow.boundingBox();
            const nearbyPopup = await frame.boundingBox();
            assert.ok(nearbyPopup.y >= badge.y + badge.height - 1 || nearbyPopup.y + nearbyPopup.height <= badge.y + 1,
                'candidate popup keeps the correction visible');
            const screenshots = process.env.ZEROCON_BROWSER_SCREENSHOTS;
            if (screenshots) {
                await mkdir(screenshots, { recursive: true });
                await page.screenshot({ path: join(screenshots, `${channel ?? 'chromium'}-assist.png`) });
            }
            await pill.click();
            await waitText('#reading', 'おkせり');
            assert.equal(await input.evaluate(element => element === document.activeElement), true);
            await pill.waitFor({ state: 'hidden' });
            await input.press('Escape');
            await waitText('#reading', '');

            await page.getByRole('button', { name: '補正の例「okseri」' }).click();
            await waitText('#reading', 'おかえり');
            await input.press('Escape');
            await waitText('#reading', 'おkせり');

            await page.getByRole('button', { name: '補正の例「okseri」' }).click();
            await waitText('#reading', 'おかえり');
            await input.press('Backspace');
            await waitText('#reading', 'おかえ');
            await pill.waitFor({ state: 'hidden' });

            await page.getByRole('button', { name: '先読みの例「よろしくお願いし」' }).click();
            await waitText('#composition-before', 'よろしくお願いし');
            await overlay.locator('#reading .assist-change').getByText('ます。', { exact: true }).waitFor();
            assert.equal(await original.textContent(), 'よろしくおねがいし');
            assert.equal(await overlay.locator('#reading .assist-action').textContent(), 'Tabで確定');
            assert.equal(await page.frameLocator('#candidate-window').locator('.candidate-item').filter({ hasText: 'よろしくお願いします。' }).count(), 0);
            if (screenshots) await page.screenshot({ path: join(screenshots, `${channel ?? 'chromium'}-prediction.png`) });
            await input.press('Tab');
            await waitText('#confirmed', 'よろしくお願いします。');
            await readingWindow.waitFor({ state: 'hidden' });

            await page.locator('summary').click();
            await page.locator('#enable-correction').uncheck();
            await page.locator('#enable-prediction').uncheck();
            await page.locator('#learning-mode').selectOption('enabled');
            await input.focus();
            await input.press('Escape');
            await page.locator('#sample').click();
            await waitText('#reading', 'あしたのかいぎ');
            await page.getByRole('button', { name: '補正の例「okseri」' }).focus();
            await readingWindow.waitFor({ state: 'hidden' });
            await page.reload();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
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

            // Control completion results to test both the manual proposal and a
            // prediction that appears while an applied correction can be undone.
            useAssistFixture = true;
            await page.evaluate(() => localStorage.removeItem('zerocon-settings-v1'));
            await page.setViewportSize({ width: 390, height: 700 });
            await page.reload();
            await page.waitForFunction(() => !document.querySelector('#romaji').disabled);
            await page.getByRole('button', { name: '補正の例「okseri」' }).click();
            await waitText('#reading', 'おkせり');
            await pill.waitFor({ state: 'visible' });
            assert.equal(await overlay.locator('#reading .assist-action').textContent(), 'Tabで補正');
            assert.match(await candidateRows.nth(1).textContent(), /おかえり 〔補正〕/u);
            await pill.click();
            await waitText('#reading', 'おかえり');
            await overlay.locator('#reading .assist-change').getByText('なさい。', { exact: true }).waitFor();
            assert.deepEqual(await overlay.locator('#reading .assist-action').allTextContents(), ['Tabで確定', '補正済み · Escで戻す']);
            const narrowOverlay = await readingWindow.boundingBox();
            assert.ok(narrowOverlay.x >= 0 && narrowOverlay.x + narrowOverlay.width <= 390);
            await input.press('Escape');
            await waitText('#reading', 'おkせり');
            await pill.waitFor({ state: 'hidden' });
            assert.deepEqual(errors, []);
            console.log(`${channel ?? 'Chromium'}: loading, native reading popup, correction and undo, prediction adoption, candidates, and IndexedDB passed`);
        } finally {
            await browser.close();
        }
    }
} finally {
    server.close();
}

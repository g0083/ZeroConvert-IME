// PLAYWRIGHT_MODULE=<installed module URL> PLAYWRIGHT_CHANNEL=chrome node --test tests/ruby_reading.browser.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const source = readFileSync(new URL('../src/ruby.rs', import.meta.url), 'utf8');
const html = source.match(/r##"([\s\S]*?)"##/)[1];

for (const deviceScaleFactor of [1, 1.5]) {
    test(`reading overlay retains the correction and Tab badge at scale ${deviceScaleFactor}`, async () => {
        const browser = await chromium.launch({
            headless: true,
            ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
        });
        try {
            const page = await browser.newPage({ viewport: { width: 140, height: 40 }, deviceScaleFactor });
            await page.evaluate(() => {
                window.sizes = [];
                window.ipc = { postMessage: (message) => window.sizes.push(JSON.parse(message)) };
            });
            await page.setContent(html);

            // The IME sends the reading plus a tab-separated assist hint. Include
            // normal readings, the ordered okseri keystrokes, replacement, and clearing.
            const updates = [
                'お', 'おk\t💡 おか [Tab]', 'おks\t💡 おか [Tab]',
                'おkせ\t💡 お世話 [Tab]', 'おkせr\t💡 お世話 [Tab]',
                'おkせり\t💡 おかえり [Tab]',
                'かくにん\t🔮 してください [Tab]', 'おかえり', '',
                '<よみ>\t💡 <候補>& [Tab]',
                JSON.stringify({ reading: 'おkせり', assist: { status: 'proposal', source: 'おkせり', replacement: 'おかえり' } }),
                JSON.stringify({ reading: 'おkせり', assist: { status: 'applied', source: 'おkせり', replacement: 'おかえり' } }),
            ];
            for (const [index, reading] of updates.entries()) {
                await page.evaluate(([text, id]) => updateReading(text, id), [reading, index + 1]);
                // Apply the measured native-window dimensions, then let layout settle.
                for (let round = 0; round < 3; round++) {
                    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
                    const size = await page.evaluate(() => window.sizes.at(-1));
                    await page.setViewportSize({ width: size.width, height: size.height });
                }
                const layout = await page.evaluate(() => {
                    const reading = document.querySelector('#reading');
                    const pill = reading.querySelector('.ai-assist-pill');
                    const tab = reading.querySelector('.ai-assist-tab');
                    return {
                        text: reading.textContent,
                        pill: pill?.textContent,
                        tab: tab?.textContent,
                        visibleRight: reading.getBoundingClientRect().left + reading.clientWidth,
                        pillRight: pill?.getBoundingClientRect().right,
                        overflow: reading.scrollWidth - reading.clientWidth,
                        injectedElements: reading.querySelectorAll('候補, よみ').length,
                        requestId: window.sizes.at(-1).requestId,
                    };
                });
                assert.equal(layout.requestId, index + 1);
                assert.equal(layout.injectedElements, 0);
                if (reading.includes('\t') || reading.startsWith('{')) {
                    const applied = reading.startsWith('{') && JSON.parse(reading).assist.status === 'applied';
                    assert.equal(layout.tab, applied ? 'Esc' : 'Tab');
                    if (applied) assert.ok(layout.pill.includes('補正済み'));
                    assert.ok(layout.pillRight <= layout.visibleRight + 0.5,
                        `assist is clipped for ${JSON.stringify(reading)}: ${JSON.stringify(layout)}`);
                    assert.ok(layout.overflow <= 1, `reading is ellipsized: ${JSON.stringify(layout)}`);
                } else {
                    assert.equal(layout.text, reading);
                    assert.equal(layout.pill, undefined);
                }
            }
        } finally {
            await browser.close();
        }
    });
}

test('structured correction wraps within narrow screens and keeps its action in both themes', async () => {
    const browser = await chromium.launch({ headless: true,
        ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
    try {
        for (const colorScheme of ['light', 'dark']) {
            const page = await browser.newPage({ viewport: { width: 320, height: 200 }, screen: { width: 320, height: 640 }, colorScheme });
            await page.setContent(html);
            await page.evaluate(() => { window.sizes = []; window.ipc = { postMessage: message => window.sizes.push(JSON.parse(message)) }; });
            const reading = '昨日の打ち合わせについてのご連絡です。'.repeat(4) + 'おせわになております';
            const replacement = '昨日の打ち合わせについてのご連絡です。'.repeat(4) + 'お世話になっております';
            await page.evaluate(text => updateReading(text, 42), JSON.stringify({ reading, assist: { status: 'applied', source: reading, replacement } }));
            for (let i = 0; i < 3; i++) {
                await page.evaluate(() => new Promise(requestAnimationFrame));
                const size = await page.evaluate(() => window.sizes.at(-1));
                assert.ok(size.width <= 320, JSON.stringify(size));
                await page.setViewportSize({ width: size.width, height: size.height });
            }
            const layout = await page.evaluate(() => {
                const main = document.querySelector('main').getBoundingClientRect();
                const action = document.querySelector('.assist-action').getBoundingClientRect();
                return { main: { left: main.left, right: main.right, bottom: main.bottom },
                    action: { left: action.left, right: action.right, bottom: action.bottom },
                    viewport: { width: innerWidth, height: innerHeight },
                    text: document.querySelector('.ai-assist-pill').textContent,
                    highlighted: document.querySelector('mark')?.textContent };
            });
            assert.ok(layout.action.right <= layout.main.right && layout.action.bottom <= layout.main.bottom, JSON.stringify(layout));
            assert.ok(layout.main.right <= layout.viewport.width && layout.main.bottom <= layout.viewport.height, JSON.stringify(layout));
            assert.ok(layout.text.includes('Esc') && layout.text.includes('補正済み'));
            assert.ok(layout.highlighted.includes('世話'));
            if (process.env.RUBY_SCREENSHOT_DIR) await page.screenshot({ path: `${process.env.RUBY_SCREENSHOT_DIR}/typo-${colorScheme}.png` });
            await page.close();
        }
    } finally { await browser.close(); }
});

test('prediction shows only added text and preserves the separate correction undo hint', async () => {
    const browser = await chromium.launch({ headless: true,
        ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
    try {
        const page = await browser.newPage({ viewport: { width: 360, height: 180 } });
        await page.setContent(html);
        await page.evaluate(() => { window.ipc = { postMessage() {} }; });
        await page.evaluate(text => updateReading(text, 1), JSON.stringify({ reading: 'しりょうを',
            assist: { status: 'prediction', replacement: '資料を作成します。', continuation: '作成します。', undoAvailable: true } }));
        assert.equal(await page.locator('#reading .assist-change').textContent(), '作成します。');
        assert.equal(await page.locator('#reading .ai-assist-tab').textContent(), 'Tab');
        assert.ok((await page.locator('#reading').textContent()).includes('Escで戻す'));
        assert.ok((await page.locator('#reading .ai-assist-pill').textContent()).includes('で確定'));
    } finally { await browser.close(); }
});

import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const palettes = JSON.parse(readFileSync(new URL("../../crates/shared/themes.json", import.meta.url)));
const rgb = (hex) => `rgb(${hex.slice(1).match(/../g).map((x) => parseInt(x, 16)).join(", ")})`;
const launch = () => chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });

test("independent theme selection persists and handles failed saves", async () => {
    const server = await createServer({ root: fileURLToPath(new URL("../", import.meta.url)), server: { host: "127.0.0.1", port: 5197, strictPort: false }, logLevel: "error" });
    await server.listen();
    let browser;
    try {
        browser = await launch();
        const page = await browser.newPage({ viewport: { width: 1050, height: 900 } });
        const errors = [];
        page.on("pageerror", (error) => errors.push(error.message));
        await page.addInitScript(() => {
            let config = JSON.parse(localStorage.getItem("test-config") || '{"general":{},"debug":{}}');
            window.__TAURI_INTERNALS__ = {
                transformCallback: () => 1,
                invoke: async (command, args) => {
                    switch (command) {
                        case "get_config": return structuredClone(config);
                        case "update_config":
                            if (window.failThemeSave) throw Error("test save failure");
                            config = structuredClone(args.newConfig);
                            localStorage.setItem("test-config", JSON.stringify(config));
                            return { saved: true, server_applied: true, changed: true, config: structuredClone(config) };
                        case "check_for_updates": return { update_available: false };
                        case "take_config_startup_notice":
                        case "take_update_install_result": return null;
                        default: throw Error(`Unexpected command: ${command}`);
                    }
                },
            };
        });
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/appearance`);
        const frontend = page.getByRole("region", { name: "設定画面", exact: true });
        const ime = page.getByRole("region", { name: "IME", exact: true });
        for (const theme of palettes) {
            await frontend.getByRole("button", { name: theme.label }).click();
            await page.waitForFunction((id) => document.documentElement.dataset.colorTheme === id, theme.id);
            assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), rgb(theme.background));
            assert.equal(await ime.getByRole("button", { name: "ダークブルー" }).getAttribute("aria-pressed"), "true");
        }
        for (const theme of palettes) {
            await ime.getByRole("button", { name: theme.label }).click();
            await page.waitForFunction((id) => JSON.parse(localStorage.getItem("test-config")).appearance.ime_theme === id, theme.id);
            assert.equal(await page.locator("html").getAttribute("data-color-theme"), "paper");
        }
        await page.reload();
        await page.waitForFunction(() => document.documentElement.dataset.colorTheme === "paper");
        assert.equal(await ime.getByRole("button", { name: "ペーパー" }).getAttribute("aria-pressed"), "true");
        await page.evaluate(() => { window.failThemeSave = true; });
        await frontend.getByRole("button", { name: "サクラ" }).click();
        await page.getByText("設定の更新に失敗しました", { exact: true }).waitFor();
        assert.equal(await page.locator("html").getAttribute("data-color-theme"), "paper");
        await page.evaluate(() => { window.failThemeSave = false; });
        await frontend.getByRole("button", { name: "プラム" }).click();
        await page.waitForFunction(() => document.documentElement.dataset.colorTheme === "plum");
        await frontend.getByRole("button", { name: "プラム" }).waitFor({ state: "visible" });
        await page.waitForFunction(() => !document.querySelector('section[aria-label="設定画面"] button').disabled);
        await page.locator("[data-sonner-toast] [data-close-button]").first().click();
        await page.locator("[data-sonner-toast]").waitFor({ state: "detached" });
        mkdirSync(fileURLToPath(new URL("../../../.local/", import.meta.url)), { recursive: true });
        await page.screenshot({ path: fileURLToPath(new URL("../../../.local/themes-desktop.png", import.meta.url)), fullPage: true });
        await page.setViewportSize({ width: 390, height: 844 });
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        await page.screenshot({ path: fileURLToPath(new URL("../../../.local/themes-mobile.png", import.meta.url)), fullPage: true });
        assert.deepEqual(errors, []);
    } finally { await browser?.close(); await server.close(); }
});

test("all floating IME surfaces use the selected palette in both OS color modes", async () => {
    const browser = await launch();
    try {
        const script = readFileSync(new URL("../../crates/ui/src/theme.js", import.meta.url), "utf8").replace("__COLOR_PALETTES__", JSON.stringify(palettes)).replace("__INITIAL_THEME__", '"forest"');
        for (const colorScheme of ["dark", "light"]) for (const surface of ["candidate", "ruby", "indicator"]) {
            const source = readFileSync(new URL(`../../crates/ui/src/${surface}.rs`, import.meta.url), "utf8");
            const html = source.match(/r##"([\s\S]*?)"##/)[1].replace("__CANDIDATE_SCROLLER_SCRIPT__", readFileSync(new URL("../../crates/ui/src/candidate_scroller.js", import.meta.url), "utf8"));
            const page = await browser.newPage({ colorScheme });
            await page.addInitScript(() => { window.ipc = { postMessage() {} }; });
            await page.addInitScript({ content: script });
            await page.goto(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
            await page.waitForFunction(() => document.documentElement.dataset.colorTheme === "forest");
            for (const theme of palettes) {
                await page.evaluate((id) => window.setColorTheme(id), theme.id);
                assert.equal(await page.locator("main").evaluate((el) => getComputedStyle(el).backgroundColor), rgb(theme.surface), `${surface} ${colorScheme} ${theme.id}`);
                if (surface === "ruby") {
                    await page.evaluate(() => updateReading("にほんご\t🔮 を入力 [Tab]", 1));
                    assert.equal(await page.locator(".ai-assist-pill").evaluate((el) => getComputedStyle(el).color), rgb(theme.accent));
                }
            }
            await page.close();
        }
    } finally { await browser.close(); }
});

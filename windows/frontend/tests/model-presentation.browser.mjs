// Run with PLAYWRIGHT_MODULE pointing to an installed Playwright module.
import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");

test("model information and assist settings match the distributed models", async () => {
    const root = fileURLToPath(new URL("../", import.meta.url));
    const server = await createServer({ root, server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    let browser;
    try {
        browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
        const page = await browser.newPage({ viewport: { width: 920, height: 680 } });
        const errors = [];
        page.on("pageerror", (error) => errors.push(error.message));
        await page.addInitScript(() => {
            const config = { general: { experimental_ai_assist: true, experimental_ai_engine: "embedded" }, debug: {} };
            window.__TAURI_INTERNALS__ = {
                transformCallback: () => 1,
                invoke: async (command, args) => {
                    switch (command) {
                        case "get_config": return structuredClone(config);
                        case "update_config":
                            Object.assign(config, structuredClone(args.newConfig));
                            return { saved: true, server_applied: true, config: structuredClone(config) };
                        case "plugin:app|version": return "3.2.0";
                        case "check_for_updates": return { update_available: false };
                        case "take_config_startup_notice":
                        case "take_update_install_result": return null;
                        default: throw new Error(`Unexpected command: ${command}`);
                    }
                },
            };
        });
        const base = `http://127.0.0.1:${server.httpServer.address().port}`;
        await page.goto(`${base}/advanced`);
        const assist = page.getByRole("switch", { name: "タイポ補正・先読みを使う", exact: true });
        await assist.waitFor();
        assert.equal(await assist.getAttribute("aria-checked"), "true");
        assert.equal(await page.getByText("辞書ベース誤入力補正", { exact: true }).count(), 0);
        await page.getByText("corecctor（ZeroCon Model Corrector）", { exact: true }).waitFor();
        await page.getByLabel("タイポ補正の実行方法", { exact: true }).waitFor();
        await assist.click();
        await page.waitForFunction(() => document.getElementById("ai-assist")?.getAttribute("aria-checked") === "false");
        assert.equal(await page.getByLabel("タイポ補正の実行方法", { exact: true }).count(), 0);
        await assist.click();
        await page.getByLabel("タイポ補正の実行方法", { exact: true }).waitFor();
        await page.goto(`${base}/about`);
        await page.getByText("ZeroCon Model Standard / Nano", { exact: true }).waitFor();
        await page.getByText("corecctor（ZeroCon Model Corrector）", { exact: true }).waitFor();
        await page.getByText("zenz-v3.1-xsmall（corecctor のベースモデル）", { exact: true }).waitFor();
        await page.getByText("CC BY-SA 4.0", { exact: true }).waitFor();
        if (process.env.SETTINGS_UI_EVIDENCE_DIR) {
            await page.screenshot({ path: `${process.env.SETTINGS_UI_EVIDENCE_DIR}/models-about.png`, fullPage: true });
        }
        assert.deepEqual(errors, []);
    } finally {
        await browser?.close();
        await server.close();
    }
});

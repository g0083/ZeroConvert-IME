// Run: PLAYWRIGHT_MODULE=<installed playwright module> node --test tests/settings-ui.browser.mjs
// No production dependency is needed. Tauri commands are mocked for browser regression checks.
import test from "node:test";
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createServer } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");

function installMock({ setup = false, resume = false, failBenchmark = false, failApply = false } = {}) {
    if (resume) localStorage.setItem("zerocon-setup-benchmark-pending-v1", "1");
    const config = {
        general: {}, shortcuts: {}, character_width: {}, learning: { mode: "enabled" }, debug: {},
        zenzai: { enable: true, profile: "old profile", model: "standard", backend: "vulkan", gpu_layers: 99 },
        romaji_table: { rows: [{ input: "ka", output: "か", next_input: "" }] },
        user_dictionary: { entries: Array.from({ length: 25 }, (_, i) => ({ reading: `よみ${i}`, word: `単語${i}` })) },
    };
    window.settingsTest = { config, checks: 0, writes: 0, updateStarts: 0, failStart: false, holdStart: false, failCheck: false, failSave: false, failLoad: sessionStorage.getItem("failSettingsLoad") === "true", available: true, holdSave: false, callbacks: {}, listeners: {}, nextCallback: 0, destroys: 0 };
    Object.assign(window.settingsTest, {
        assets: { model: !setup, dictionary: !setup, english: !setup },
        downloads: [], failBenchmark, failApply,
    });
    window.isTauri = true;
    window.__TAURI_INTERNALS__ = {
        metadata: { currentWindow: { label: "main" } },
        transformCallback: (callback) => {
            const id = ++window.settingsTest.nextCallback;
            window.settingsTest.callbacks[id] = callback;
            return id;
        },
        invoke: async (command, args) => {
            const state = window.settingsTest;
            switch (command) {
                case "plugin:app|version": return "0.1.0-batao.15";
                case "plugin:event|listen": state.listeners[args.handler] = args; return args.handler;
                case "plugin:event|unlisten": delete state.listeners[args.eventId]; return null;
                case "plugin:window|destroy": state.destroys++; return null;
                case "take_config_startup_notice":
                case "take_update_install_result": return null;
                case "setup_assets_status": return {
                    model: { installed: state.assets.model }, dictionary: { installed: state.assets.dictionary }, english: { installed: state.assets.english },
                };
                case "conversion_model_download":
                case "dictionary_download":
                case "english_dictionary_download": {
                    const asset = { conversion_model_download: "model", dictionary_download: "dictionary", english_dictionary_download: "english" }[command];
                    state.downloads.push(asset);
                    while (state.holdDownload) await new Promise((resolve) => setTimeout(resolve, 20));
                    state.assets[asset] = true;
                    return { installed: true };
                }
                case "get_config":
                    if (state.failLoad) throw new Error("mock load failure");
                    return structuredClone(config);
                case "update_config":
                    state.writes++;
                    while (state.holdSave) await new Promise((resolve) => setTimeout(resolve, 20));
                    if (state.failSave) throw new Error("mock save failure");
                    Object.assign(config, structuredClone(args.newConfig));
                    return { saved: true, server_applied: !state.failApply, changed: true, config: structuredClone(config) };
                case "check_for_updates":
                    state.checks++;
                    if (state.failCheck) throw new Error("mock update failure");
                    return { current_version: "0.1.0-batao.15", latest_version: "0.1.0-batao.16", release_url: "https://github.com/batao9/azooKey-Windows/releases/tag/v0.1.0-batao.16", update_available: state.available };
                case "start_update":
                    state.updateStarts++;
                    while (state.holdStart) await new Promise((resolve) => setTimeout(resolve, 20));
                    if (state.failStart) throw new Error("mock updater launch failure");
                    return { launched: true };
                case "check_capability": return { cpu: true, vulkan: true };
                case "get_active_model": return { active_id: "standard", models: [] };
                case "run_quick_conversion_benchmark":
                    state.benchmarkRun = { backend: args.backend, mode: "quick" };
                    state.callbacks[args.progress.id]({ id: 0, message: { backend: args.backend, completed: 126, total: 252 } });
                    while (state.holdQuick) await new Promise((resolve) => setTimeout(resolve, 20));
                    return { backend: args.backend, gpu_layers: 0, elapsed_ms: 2100, rows: [
                        { id: 1, top1: "記者が取材する", candidates: ["記者が取材する", "汽車が取材する"], is_correct: true, latency_ms: 7.4, nbest_ms: 1.2 },
                        { id: 12, top1: "今日敗者に行く", candidates: ["今日敗者に行く"], is_correct: false, latency_ms: 15.2, nbest_ms: 2.8 },
                    ] };
                case "start_benchmark":
                    state.benchmarkRun = { backend: args.backend, mode: args.scope };
                    return { id: 1, batch_size: 16, backend: args.backend, gpu_layers: config.zenzai.gpu_layers };
                case "run_benchmark_batch": return args.cases.map((item) => item.kind === "typo" ? {
                    id: item.id, main_output: item.input_reading, moshikashite_output: item.is_clean ? null : item.expected,
                    status: item.is_clean ? "clean_preserved" : "variant_match", latency_ms: 8,
                } : {
                    id: item.id, top1: item.expected, candidates: [item.expected], is_correct: true, latency_ms: 6, nbest_ms: 1,
                });
                case "finish_benchmark": return 1900;
                case "run_quick_benchmark":
                    state.quickRuns = (state.quickRuns ?? 0) + 1;
                    if (state.failBenchmark) throw new Error("mock benchmark error");
                    if (args?.progress) {
                        const callback = state.callbacks[args.progress.id];
                        callback({ id: 0, message: { backend: "cpu", completed: 126, total: 504 } });
                        while (state.holdCpu) await new Promise((resolve) => setTimeout(resolve, 20));
                        callback({ id: 1, message: { backend: "vulkan", completed: 378, total: 504 } });
                    }
                    while (state.holdQuick) await new Promise((resolve) => setTimeout(resolve, 20));
                    if (args?.progress) state.callbacks[args.progress.id]({ id: 2, message: { backend: "vulkan", completed: 504, total: 504 } });
                    return {
                        model_id: "standard", vulkan_gpu_layers: config.zenzai.gpu_layers, case_count: 84,
                        results: [
                            { backend: "cpu", avg_latency_ms: 12.0, p95_latency_ms: 17.1, error: null },
                            { backend: "vulkan", avg_latency_ms: 20.0, p95_latency_ms: 23.1, error: null },
                        ],
                        recommended: "cpu",
                    };
                case "get_default_romaji_rows": return [{ input: "ka", output: "か", next_input: "" }, { input: "kk", output: "っ", next_input: "k" }];
                case "user_dictionary_export_file": {
                    state.dictionaryExports = state.dictionaryExports ?? [];
                    state.dictionaryExports.push(structuredClone(args.request));
                    return state.dictionaryExportPath === undefined
                        ? "C:\\Users\\test\\Documents\\zeroconvert-user-dictionary.json"
                        : state.dictionaryExportPath;
                }
                case "user_dictionary_import_file":
                    state.dictionaryImportCalls = (state.dictionaryImportCalls ?? 0) + 1;
                    return state.dictionaryImportFile ?? null;
                case "restart_server":
                    state.restarts = (state.restarts ?? 0) + 1;
                    if (state.failRestart) throw new Error("mock restart failure");
                    return null;
                case "get_learning_stats": return {
                    selection_count: 3, typo_count: 1, english_count: 2,
                    history_present: true, history_paused: false, learning_mode: "enabled",
                };
                case "reset_learning_history":
                    state.learningResetScopes = state.learningResetScopes ?? [];
                    return {
                        reset: true, server_applied: true,
                        applied: ["conversion", "typo", "english"], failed: [],
                        stats: {
                            selection_count: 0, typo_count: 0, english_count: 0,
                            history_present: false, history_paused: false, learning_mode: "enabled",
                        },
                    };
                default: throw new Error(`Unmocked command: ${command}`);
            }
        },
    };
}

async function assertInViewport(locator, page) {
    const rect = await locator.boundingBox();
    const { width, height } = page.viewportSize();
    assert.ok(rect && rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width + 1 && rect.y + rect.height <= height + 1, "control must remain inside the viewport");
}

test("settings layout, editing safety, navigation and update badge", async () => {
    const server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    const address = server.httpServer.address();
    const base = `http://127.0.0.1:${address.port}`;
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }).catch(async (error) => {
        await server.close();
        throw error;
    });
    const context = await browser.newContext({ viewport: { width: 800, height: 600 } });
    await context.addInitScript(installMock);
    const page = await context.newPage();
    const runtimeErrors = [];
    page.on("pageerror", (error) => runtimeErrors.push(error.message));
    const evidence = process.env.SETTINGS_UI_EVIDENCE_DIR;
    const screenshot = async (name) => {
        if (!evidence) return;
        await mkdir(evidence, { recursive: true });
        await page.screenshot({ path: path.join(evidence, `${name}.png`) });
    };
    const emitClose = () => page.evaluate(async () => {
        const state = window.settingsTest;
        const listeners = Object.values(state.listeners).filter((listener) => listener.event === "tauri://close-requested");
        if (listeners.length !== 1) throw new Error("native close listener must not leak across navigation or StrictMode");
        await Promise.all(listeners.map((listener) => state.callbacks[listener.handler]({ event: listener.event, id: listener.handler, payload: {} })));
    });
    try {
        await page.goto(`${base}/dictionary`);
        await page.getByRole("textbox", { name: "1 行目の読み", exact: true }).waitFor();
        await page.getByLabel("更新があります", { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.checks), 1, "startup check runs once, even in StrictMode");
        await page.getByRole("button", { name: "設定メニューを開閉", exact: true }).click();
        const rail = page.locator('[data-slot="sidebar"][data-state="collapsed"]');
        await rail.waitFor();
        for (const name of ["入力と文字", "変換と候補", "キーとローマ字", "ユーザ辞書", "詳細設定", /ZeroConについて/]) {
            const link = page.getByRole("link", { name, exact: typeof name === "string" });
            await assertInViewport(link, page);
            assert.ok(await link.locator("svg").isVisible(), "collapsed sidebar retains each navigation icon");
            assert.ok(await link.locator("span").first().isHidden(), "collapsed sidebar hides labels, not links");
        }
        const badge = page.getByLabel("更新があります", { exact: true });
        await assertInViewport(badge, page);
        assert.equal((await badge.boundingBox()).width, 8, "collapsed About keeps a red update dot");
        await page.getByRole("link", { name: "変換と候補", exact: true }).hover();
        await page.getByRole("tooltip", { name: "変換と候補", exact: true }).waitFor();
        await page.getByRole("link", { name: "変換と候補", exact: true }).click();
        await page.getByRole("heading", { name: "ZeroCon AI", exact: true }).waitFor();
        assert.equal(await page.locator(".settings-content > section").first().getByRole("heading").innerText(), "ZeroCon AI");
        assert.ok((await page.locator('[data-slot="sidebar-container"]').boundingBox()).width <= 64, "collapsed sidebar is a narrow icon rail");
        await screenshot("sidebar-icons");
        await page.getByRole("link", { name: "入力と文字", exact: true }).click();
        for (const label of ["数字", "() {} []", "、。", "･ ｢｣", "\" '", ": ;", "# % & @ $ ^ _ | ` \\", "~", "< > = + - / *", "? !"]) {
            await page.getByLabel(label, { exact: true }).waitFor();
        }
        await page.getByRole("button", { name: "設定メニューを開閉", exact: true }).click();
        await page.locator('[data-slot="sidebar"][data-state="expanded"]').waitFor();
        await page.getByRole("link", { name: "ユーザ辞書", exact: true }).click();
        await page.getByRole("button", { name: "行を追加", exact: true }).click();
        const reading = page.getByRole("textbox", { name: "26 行目の読み", exact: true });
        await reading.fill("ついか");
        await page.getByRole("button", { name: "保存", exact: true }).click();
        await page.getByText("単語を入力してください", { exact: true }).waitFor();
        const word = page.getByRole("textbox", { name: "26 行目の単語", exact: true });
        assert.ok(await word.evaluate((element) => element === document.activeElement));
        await word.fill("追加");
        const scrollBefore = await reading.evaluate((element) => element.closest("table").parentElement.scrollTop);
        assert.ok(scrollBefore > 0);
        await assertInViewport(page.getByRole("button", { name: "保存", exact: true }), page);
        await assertInViewport(page.getByRole("button", { name: "行を追加", exact: true }), page);
        await screenshot("dictionary-bottom");

        await page.evaluate(() => { window.settingsTest.holdSave = true; });
        await page.getByRole("button", { name: "保存", exact: true }).click();
        await page.getByText("保存中", { exact: true }).waitFor();
        assert.ok(await reading.isDisabled());
        assert.ok(await page.getByRole("button", { name: "行を追加", exact: true }).isDisabled());
        await page.evaluate(() => { window.settingsTest.holdSave = false; });
        await page.getByText("ユーザ辞書を保存しました", { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.writes), 1);
        assert.equal(await reading.evaluate((element) => element.closest("table").parentElement.scrollTop), scrollBefore);

        await page.getByRole("link", { name: "入力と文字", exact: true }).click();
        await page.getByRole("link", { name: "ユーザ辞書", exact: true }).click();
        await word.fill("変更");
        await page.evaluate(() => { window.settingsTest.failSave = true; });
        await page.getByRole("button", { name: "保存", exact: true }).click();
        await page.getByText("ユーザ辞書の保存に失敗しました", { exact: true }).waitFor();
        await page.getByText("未保存の変更", { exact: true }).waitFor();
        assert.equal(await word.inputValue(), "変更");
        await page.evaluate(() => history.back());
        await page.getByRole("alertdialog").waitFor();
        await page.getByRole("button", { name: "編集を続ける", exact: true }).click();
        assert.ok(page.url().endsWith("/dictionary"), "Back must not silently discard a draft");
        await page.getByRole("link", { name: "キーとローマ字", exact: true }).click();
        await page.getByRole("alertdialog").waitFor();
        await page.getByRole("button", { name: "編集を続ける", exact: true }).click();
        assert.ok(page.url().endsWith("/dictionary"));
        await page.getByRole("link", { name: "キーとローマ字", exact: true }).click();
        await page.getByRole("button", { name: "破棄して移動", exact: true }).click();
        await page.evaluate(() => { window.settingsTest.failSave = false; });

        await page.getByRole("button", { name: "編集", exact: true }).click();
        await page.getByRole("dialog").waitFor();
        await page.getByRole("button", { name: "行を追加", exact: true }).click();
        await page.getByRole("textbox", { name: "2行目 入力", exact: true }).fill("ki");
        await page.getByRole("button", { name: "保存", exact: true }).click();
        await page.getByText("出力を記入してください", { exact: true }).waitFor();
        const output = page.getByRole("textbox", { name: "2行目 出力", exact: true });
        assert.ok(await output.evaluate((element) => element === document.activeElement));
        await output.fill("き");
        await emitClose();
        await page.getByRole("alertdialog").waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.destroys), 0, "dirty Romaji must prevent native close");
        await page.keyboard.press("Tab");
        assert.ok(await page.evaluate(() => !!document.activeElement.closest('[role="alertdialog"]')));
        await page.getByRole("button", { name: "編集を続ける", exact: true }).click();
        await page.locator('[role="alertdialog"]').waitFor({ state: "detached" });
        assert.equal(await output.inputValue(), "き");
        await emitClose();
        await page.getByRole("alertdialog").waitFor();
        await page.keyboard.press("Escape");
        // Wait for the closing focus scope/exit animation before testing the underlying modal.
        await page.locator('[role="alertdialog"]').waitFor({ state: "detached" });
        assert.equal(await output.inputValue(), "き");
        // Radix removes the closed focus scope in a deferred cleanup after DOM detachment.
        await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        await page.getByRole("button", { name: "保存", exact: true }).focus();
        await page.keyboard.press("Tab");
        assert.ok(await page.evaluate(() => !!document.activeElement.closest('[role="dialog"]')), "Tab must stay inside the modal");
        await page.keyboard.press("Escape");
        await page.getByRole("alertdialog").waitFor();
        await page.getByRole("button", { name: "編集を続ける", exact: true }).click();
        await page.getByRole("button", { name: "初期値に戻す", exact: true }).click();
        await emitClose();
        await page.getByRole("alertdialog").getByRole("button", { name: "破棄して閉じる", exact: true }).waitFor();
        await page.getByRole("alertdialog").getByRole("button", { name: "編集を続ける", exact: true }).click();
        await page.getByRole("alertdialog").getByText("初期値に戻しますか？", { exact: true }).waitFor();
        await page.getByRole("alertdialog").getByRole("button", { name: "初期値に戻す", exact: true }).click();
        await page.getByRole("textbox", { name: "2行目 入力", exact: true }).waitFor();
        assert.equal(await page.getByRole("textbox", { name: "2行目 入力", exact: true }).inputValue(), "kk");
        await screenshot("romaji-editor");
        await page.getByRole("button", { name: "保存", exact: true }).click();
        await page.getByRole("dialog").waitFor({ state: "hidden" });

        await page.getByRole("link", { name: "変換と候補", exact: true }).click();
        await page.getByLabel("変換プロファイル", { exact: true }).fill("latest profile");
        await page.getByRole("link", { name: /ZeroConについて/ }).click();
        await page.waitForFunction(() => window.settingsTest.config.zenzai.profile === "latest profile");
        await page.getByText("オープンソースライセンスと謝辞", { exact: true }).waitFor();
        await page.evaluate(() => { window.settingsTest.failCheck = true; });
        await page.getByRole("button", { name: "更新を確認", exact: true }).click();
        await page.getByText("Error: mock update failure", { exact: true }).waitFor();
        assert.equal(await page.getByLabel("更新があります", { exact: true }).count(), 1);
        await screenshot("about-update");
        await page.evaluate(() => { window.settingsTest.failCheck = false; window.settingsTest.available = false; });
        await page.getByRole("button", { name: "更新を確認", exact: true }).click();
        await page.getByText("最新版を利用中です。", { exact: true }).waitFor();
        assert.equal(await page.getByLabel("更新があります", { exact: true }).count(), 0);

        await page.keyboard.press("Control+k");
        const search = page.getByRole("dialog", { name: "設定を検索", exact: true });
        await search.waitFor();
        await search.getByRole("textbox", { name: "設定を検索", exact: true }).fill("ばっくえんど");
        await search.getByRole("option").first().click();
        await page.getByRole("heading", { name: "ZeroCon AI", exact: true }).waitFor();
        const highlighted = page.locator('[data-settings-label="バックエンド"].settings-row-highlight');
        await highlighted.waitFor();
        await screenshot("settings-search");
        await page.keyboard.press("Control+k");
        await page.getByRole("dialog", { name: "設定を検索", exact: true }).waitFor();
        await page.keyboard.press("Escape");
        await page.getByRole("dialog", { name: "設定を検索", exact: true }).waitFor({ state: "hidden" });
        await page.getByRole("link", { name: /ZeroConについて/ }).click();

        await page.evaluate(() => { window.settingsTest.available = true; window.settingsTest.failStart = true; });
        await page.getByRole("button", { name: "更新を確認", exact: true }).click();
        const updateButton = page.getByRole("button", { name: "アップデート", exact: true });
        const recheckButton = page.getByRole("button", { name: "更新を確認", exact: true });
        await updateButton.click();
        await page.getByText("Error: mock updater launch failure", { exact: true }).waitFor();
        assert.ok(await updateButton.isEnabled(), "failed helper launches must allow retry");
        await page.evaluate(() => { window.settingsTest.failStart = false; window.settingsTest.holdStart = true; });
        await updateButton.click();
        await page.getByText("アップデートを開始中…", { exact: true }).waitFor();
        assert.ok(await page.getByRole("button", { name: "アップデートを開始中…", exact: true }).isDisabled());
        assert.ok(await recheckButton.isDisabled());
        await page.evaluate(() => { window.settingsTest.holdStart = false; });
        await page.getByText("アップデートを開始しました。完了までお待ちください。", { exact: true }).waitFor();
        const launchedButton = page.getByRole("button", { name: "開始済み", exact: true });
        await launchedButton.waitFor();
        assert.ok(await launchedButton.isDisabled(), "successful helper launch must stay disabled until restart");
        assert.ok(await recheckButton.isDisabled(), "rechecking must not reset the launched state");
        await page.getByRole("link", { name: "入力と文字", exact: true }).click();
        await page.getByLabel("句読点", { exact: true }).waitFor();
        await page.getByRole("link", { name: /ZeroConについて/ }).click();
        await page.getByText("アップデートを開始しました。完了までお待ちください。", { exact: true }).waitFor();
        assert.ok(await launchedButton.isDisabled(), "launch protection must survive navigation");
        await launchedButton.evaluate((button) => button.click());
        await recheckButton.evaluate((button) => button.click());
        assert.equal(await page.evaluate(() => window.settingsTest.updateStarts), 2, "only the failed launch and its successful retry may invoke the backend");
        assert.equal(await page.getByLabel("更新があります", { exact: true }).count(), 1, "launching must not silently clear the update notification");

        await page.getByRole("link", { name: "入力と文字", exact: true }).click();
        await page.getByLabel("句読点", { exact: true }).waitFor();
        await screenshot("input");
        await page.getByRole("link", { name: "変換と候補", exact: true }).click();
        await page.getByLabel("ライブ変換中の読みを表示", { exact: true }).waitFor();
        await screenshot("conversion");
        await page.getByRole("link", { name: "詳細設定", exact: true }).click();
        await page.getByLabel("翻訳機能を有効にする", { exact: true }).waitFor();
        await page.getByRole("heading", { name: "ログ", exact: true }).waitFor();
        await screenshot("advanced");

        await page.getByRole("link", { name: "ユーザ辞書", exact: true }).click();
        for (const viewport of [{ width: 640, height: 480 }, { width: 400, height: 360 }]) {
            await page.setViewportSize(viewport);
            await page.getByRole("button", { name: "行を追加", exact: true }).click();
            await assertInViewport(page.getByRole("button", { name: "行を追加", exact: true }), page);
            await assertInViewport(page.getByRole("button", { name: "保存", exact: true }), page);
            await page.getByRole("button", { name: "設定メニューを開閉", exact: true }).click();
            await page.getByRole("link", { name: "キーとローマ字", exact: true }).click();
            await page.getByRole("button", { name: "破棄して移動", exact: true }).click();
            await page.getByRole("button", { name: "編集", exact: true }).click();
            await assertInViewport(page.getByRole("button", { name: "行を追加", exact: true }), page);
            await assertInViewport(page.getByRole("button", { name: "保存", exact: true }), page);
            await screenshot(`romaji-${viewport.width}`);
            await page.getByRole("button", { name: "キャンセル", exact: true }).click();
            await page.getByRole("button", { name: "設定メニューを開閉", exact: true }).click();
            await page.getByRole("link", { name: "ユーザ辞書", exact: true }).click();
        }
        await page.evaluate(() => { sessionStorage.setItem("failSettingsLoad", "true"); });
        await page.reload();
        await page.getByText("辞書設定の読み込みに失敗しました。", { exact: true }).waitFor();
        assert.ok(await page.getByRole("button", { name: "保存", exact: true }).isDisabled());
        await page.evaluate(() => sessionStorage.removeItem("failSettingsLoad"));
        await page.setViewportSize({ width: 800, height: 600 });
        await page.emulateMedia({ colorScheme: "dark" });
        await page.reload();
        await page.getByRole("textbox", { name: "1 行目の読み", exact: true }).waitFor();
        assert.ok(await page.locator("html").evaluate((element) => element.classList.contains("dark")));
        await screenshot("dictionary-dark");
        await emitClose();
        assert.equal(await page.evaluate(() => window.settingsTest.destroys), 1, "a clean editor closes normally");
        await page.getByRole("textbox", { name: "1 行目の読み", exact: true }).fill("みほぞん");
        await emitClose();
        await page.getByRole("alertdialog").waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.destroys), 1, "native close must not bypass dirty confirmation");
        await page.getByRole("button", { name: "編集を続ける", exact: true }).click();
        assert.equal(await page.getByRole("textbox", { name: "1 行目の読み", exact: true }).inputValue(), "みほぞん");
        await emitClose();
        await page.getByRole("alertdialog").waitFor();
        await page.keyboard.press("Escape");
        await page.getByRole("alertdialog").waitFor({ state: "hidden" });
        await emitClose();
        await page.getByRole("button", { name: "破棄して閉じる", exact: true }).click();
        assert.equal(await page.evaluate(() => window.settingsTest.destroys), 2);
        assert.deepEqual(runtimeErrors, []);
    } finally {
        await browser.close();
        await server.close();
    }
});

test("user dictionary exports to a file and imports JSON or TSV with merge and replace", async () => {
    const server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
    try {
        const context = await browser.newContext({ viewport: { width: 1000, height: 800 } });
        await context.addInitScript(installMock);
        const page = await context.newPage();
        const runtimeErrors = [];
        page.on("pageerror", (error) => runtimeErrors.push(error.message));
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/dictionary`);
        await page.getByRole("textbox", { name: "25 行目の読み", exact: true }).waitFor();

        // JSON書き出し: 保存済みの内容をそのままファイルへ渡す
        await page.getByRole("button", { name: "書き出し", exact: true }).click();
        await page.getByLabel("形式", { exact: true }).waitFor();
        await page.getByRole("button", { name: "書き出す", exact: true }).click();
        await page.getByText("25件を書き出しました", { exact: true }).waitFor();
        const jsonExport = await page.evaluate(() => window.settingsTest.dictionaryExports.at(-1));
        assert.match(jsonExport.defaultFileName, /^zeroconvert-user-dictionary-\d{8}\.json$/);
        const payload = JSON.parse(jsonExport.contents);
        assert.equal(payload.format, "zeroconvert-user-dictionary");
        assert.deepEqual(payload.entries, Array.from({ length: 25 }, (_, i) => ({ reading: `よみ${i}`, word: `単語${i}`, pos: "proper_noun" })));

        // TSV書き出しはタブ区切りで、末尾に改行が付く
        await page.getByRole("button", { name: "書き出し", exact: true }).click();
        await page.getByLabel("形式", { exact: true }).selectOption("tsv");
        await page.getByRole("button", { name: "書き出す", exact: true }).click();
        await page.waitForFunction(() => window.settingsTest.dictionaryExports.length === 2);
        const tsvExport = await page.evaluate(() => window.settingsTest.dictionaryExports.at(-1));
        assert.equal(tsvExport.format, "tsv");
        assert.match(tsvExport.defaultFileName, /\.txt$/);
        assert.deepEqual(tsvExport.contents.split("\n").slice(0, 2), ["よみ0\t単語0\t固有名詞\t", "よみ1\t単語1\t固有名詞\t"]);
        assert.equal(tsvExport.contents.trimEnd().split("\n").length, 25);

        await page.getByRole("button", { name: "書き出し", exact: true }).click();
        await page.getByLabel("形式", { exact: true }).selectOption("msime");
        await page.getByRole("button", { name: "書き出す", exact: true }).click();
        await page.waitForFunction(() => window.settingsTest.dictionaryExports.length === 3);
        const msimeExport = await page.evaluate(() => window.settingsTest.dictionaryExports.at(-1));
        assert.equal(msimeExport.format, "msime");
        assert.match(msimeExport.contents, /^!Microsoft IME Dictionary Tool\r\n!Version:\r\n!Format:WORDLIST\r\n/);

        // 読み込み（追加）: 登録済みの行はスキップし、新しい行だけを下書きへ足す
        await page.evaluate(() => {
            window.settingsTest.dictionaryImportFile = {
                path: "C:\\tmp\\user-dictionary.json",
                contents: JSON.stringify({ format: "zeroconvert-user-dictionary", version: 1, entries: [
                    { reading: "よみ0", word: "単語0", pos: "proper_noun" },
                    { reading: "あたらしい", word: "新しい単語", pos: "サ変名詞" },
                ] }),
            };
        });
        await page.getByRole("button", { name: "読み込み", exact: true }).click();
        await page.getByRole("button", { name: "ファイルを選択…", exact: true }).click();
        await page.getByText("読み取り 2件 / 追加 1件 / 重複 1件を除外", { exact: true }).waitFor();
        await page.getByRole("button", { name: "追加", exact: true }).click();
        await page.getByText("1件を追加しました（「保存」で反映されます）", { exact: true }).waitFor();
        assert.equal(await page.getByRole("textbox", { name: "26 行目の読み", exact: true }).inputValue(), "あたらしい");
        assert.equal(await page.getByRole("combobox", { name: "26 行目の品詞", exact: true }).inputValue(), "sahen_noun");

        // 読み込み（置き換え）: TSVのコメント行を読み飛ばし、現在の登録を捨てる
        await page.evaluate(() => {
            window.settingsTest.dictionaryImportFile = {
                path: "C:\\tmp\\words.tsv",
                contents: "# 読み\t単語\t品詞\nよみ0\t単語0\nぜんぶ\t全部\nことば\t言葉\tnoun\n",
            };
        });
        await page.getByRole("button", { name: "読み込み", exact: true }).click();
        await page.getByRole("button", { name: "ファイルを選択…", exact: true }).click();
        await page.getByLabel("取り込み方法", { exact: true }).selectOption("replace");
        await page.getByText("読み取り 3件 / 置き換え後 3件 / 未対応の品詞 1件は一般名詞として登録", { exact: true }).waitFor();
        await page.getByRole("button", { name: "置き換える", exact: true }).click();
        await page.getByText("3件で置き換えました（「保存」で反映されます）", { exact: true }).waitFor();
        assert.equal(await page.getByRole("textbox", { name: "3 行目の単語", exact: true }).inputValue(), "言葉");
        assert.equal(await page.getByRole("combobox", { name: "3 行目の品詞", exact: true }).inputValue(), "common_noun");
        assert.equal(await page.getByRole("textbox", { name: "4 行目の読み", exact: true }).count(), 0);

        // 壊れたファイルは理由を出して取り込みを止める
        await page.evaluate(() => {
            window.settingsTest.dictionaryImportFile = { path: "C:\\tmp\\broken.json", contents: "{ こわれた" };
        });
        await page.getByRole("button", { name: "読み込み", exact: true }).click();
        await page.getByRole("button", { name: "ファイルを選択…", exact: true }).click();
        await page.getByRole("alert").getByText(/JSON として読み込めませんでした/).waitFor();
        assert.ok(await page.getByRole("button", { name: "追加", exact: true }).isDisabled());
        await page.getByRole("button", { name: "キャンセル", exact: true }).click();
        await page.getByRole("alertdialog").waitFor({ state: "hidden" });
        assert.equal(await page.getByRole("textbox", { name: "3 行目の単語", exact: true }).inputValue(), "言葉");
        assert.deepEqual(runtimeErrors, []);
    } finally {
        await browser.close();
        await server.close();
    }
});


test("runtime settings use RAM presets and retry failed application without a PC reboot", async () => {
    const server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
    try {
        const context = await browser.newContext({ viewport: { width: 1100, height: 900 } });
        await context.addInitScript(installMock);
        const page = await context.newPage();
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/conversion`);
        await page.getByLabel("バックエンド", { exact: true }).waitFor();
        const backend = page.getByLabel("バックエンド", { exact: true });
        const layers = page.getByLabel("GPUに載せる層数", { exact: true });
        await backend.click();
        await page.getByRole("option", { name: "CPU", exact: true }).click();
        await page.waitForFunction(() => window.settingsTest.config.zenzai.backend === "cpu");
        assert.ok(await layers.isDisabled());
        assert.equal(await page.getByText(/PCを再起動してください/).count(), 0);
        await backend.click();
        await page.getByRole("option", { name: "Vulkan", exact: true }).click();
        await layers.click();
        await page.getByRole("option", { name: "最大4層（RAM優先）", exact: true }).click();
        await page.waitForFunction(() => window.settingsTest.config.zenzai.gpu_layers === 4);
        await page.evaluate(() => { window.settingsTest.failApply = true; });
        await layers.click();
        await page.getByRole("option", { name: "最大8層", exact: true }).click();
        const retryRuntime = page.getByRole("button", { name: "IMEサーバーを再起動して再試行", exact: true });
        await retryRuntime.waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.config.zenzai.gpu_layers), 8);
        await page.evaluate(() => { window.settingsTest.failRestart = true; });
        await retryRuntime.click();
        await page.getByText(/IMEサーバーを再起動できませんでした/).waitFor();
        assert.ok(await retryRuntime.isVisible());
        await page.evaluate(() => { window.settingsTest.failRestart = false; window.settingsTest.failApply = false; });
        await retryRuntime.click();
        await retryRuntime.waitFor({ state: "hidden" });
        assert.equal(await page.evaluate(() => window.settingsTest.restarts), 2);
        await page.evaluate(() => { window.settingsTest.failSave = true; });
        await layers.click();
        await page.getByRole("option", { name: "最大16層", exact: true }).click();
        await page.getByText("推論設定の更新に失敗しました", { exact: true }).waitFor();
        assert.equal(await layers.innerText(), "最大8層");
        await page.evaluate(() => { window.settingsTest.failSave = false; });

        if (process.env.SETTINGS_UI_EVIDENCE_DIR) {
            await mkdir(process.env.SETTINGS_UI_EVIDENCE_DIR, { recursive: true });
            await page.screenshot({ path: path.join(process.env.SETTINGS_UI_EVIDENCE_DIR, "runtime-settings.png"), fullPage: true });
        }
    } finally {
        await browser.close();
        await server.close();
    }
});

test("initial setup downloads assets, reports benchmark progress, and applies the recommendation", async () => {
    const server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }).catch(async (error) => {
        await server.close();
        throw error;
    });
    const openPage = async (options) => {
        const context = await browser.newContext({ viewport: { width: 1100, height: 900 } });
        await context.addInitScript(installMock, options);
        const page = await context.newPage();
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/conversion`);
        return page;
    };
    try {
        const page = await openPage({ setup: true });
        const consent = page.getByRole("alertdialog", { name: "必要データを準備します" });
        await consent.waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.quickRuns ?? 0), 0);
        await page.evaluate(() => Object.assign(window.settingsTest, { holdDownload: true, holdCpu: true, holdQuick: true, holdSave: true }));
        await consent.getByRole("button", { name: "ダウンロードする", exact: true }).click();
        await page.waitForFunction(() => window.settingsTest.downloads.length === 1);
        assert.equal(await page.evaluate(() => window.settingsTest.quickRuns ?? 0), 0);
        await page.evaluate(() => { window.settingsTest.holdDownload = false; });

        const progress = page.getByRole("progressbar", { name: "バックエンド測定", exact: true });
        await page.getByText("CPUを測定中", { exact: true }).waitFor();
        assert.equal(await progress.getAttribute("aria-valuenow"), "25");
        assert.deepEqual(await page.evaluate(() => window.settingsTest.downloads), ["model", "dictionary", "english"]);
        await page.evaluate(() => { window.settingsTest.holdCpu = false; });
        await page.getByText("Vulkanを測定中", { exact: true }).waitFor();
        assert.equal(await progress.getAttribute("aria-valuenow"), "75");
        assert.equal(await page.evaluate(() => window.settingsTest.writes), 0);

        await page.evaluate(() => { window.settingsTest.holdQuick = false; });
        await page.getByText("推奨バックエンドを適用中", { exact: true }).waitFor();
        assert.equal(await progress.getAttribute("aria-valuenow"), "100");
        assert.equal(await page.getByRole("heading", { name: "変換と候補", exact: true }).count(), 0);
        await page.evaluate(() => { window.settingsTest.holdSave = false; });
        await page.getByRole("heading", { name: "変換と候補", exact: true }).waitFor();
        await page.getByText("必要なデータを準備し、推奨バックエンド CPU を適用しました。", { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.config.zenzai.backend), "cpu");
        assert.equal(await page.evaluate(() => window.settingsTest.quickRuns), 1);
        assert.equal(await page.evaluate(() => window.settingsTest.writes), 1);
        assert.equal(await page.evaluate(() => window.settingsTest.restarts ?? 0), 0);
        assert.equal(await page.evaluate(() => localStorage.getItem("zerocon-setup-benchmark-pending-v1")), null);

        // The benchmark resumes if the window was closed after the assets finished downloading.
        const resumed = await openPage({ resume: true, failBenchmark: true });
        await resumed.getByText(/バックエンドを測定できませんでした/).waitFor();
        await resumed.getByRole("heading", { name: "変換と候補", exact: true }).waitFor();
        assert.equal(await resumed.evaluate(() => window.settingsTest.quickRuns), 1);
        assert.equal(await resumed.evaluate(() => window.settingsTest.writes), 0);
        assert.equal(await resumed.evaluate(() => window.settingsTest.restarts), 1);
        assert.deepEqual(await resumed.evaluate(() => window.settingsTest.downloads), []);

        const pending = await openPage({ resume: true, failApply: true });
        await pending.getByRole("button", { name: "IMEサーバーを再起動して再試行", exact: true }).waitFor();
        assert.equal(await pending.evaluate(() => window.settingsTest.config.zenzai.backend), "cpu");
        assert.equal(await pending.evaluate(() => window.settingsTest.restarts ?? 0), 0);

        const existing = await openPage({});
        await existing.getByLabel("バックエンド", { exact: true }).waitFor();
        assert.equal(await existing.evaluate(() => window.settingsTest.quickRuns ?? 0), 0);
        await existing.locator("#zenzai-quick-benchmark").click();
        await existing.getByText("共通候補生成 84問（各3回の中央値）", { exact: true }).waitFor();
        assert.equal(await existing.evaluate(() => window.settingsTest.config.zenzai.backend), "vulkan");
        await existing.getByRole("button", { name: "推奨のCPUを適用", exact: true }).click();
        await existing.waitForFunction(() => window.settingsTest.config.zenzai.backend === "cpu");
    } finally {
        await browser.close();
        await server.close();
    }
});

test("quick conversion rows show answers and keep the measured backend when the selector changes", async () => {
    const server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }).catch(async (error) => {
        await server.close();
        throw error;
    });
    try {
        const context = await browser.newContext({ viewport: { width: 920, height: 700 } });
        await context.addInitScript(installMock);
        const page = await context.newPage();
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/benchmark`);
        const backend = page.locator("#benchmark-backend");
        await page.waitForFunction(() => document.querySelector("#benchmark-backend")?.value === "vulkan");
        await backend.selectOption("cpu");
        await page.getByRole("radio", { name: "クイック検証（84問）", exact: true }).check();
        await page.evaluate(() => { window.settingsTest.holdQuick = true; });
        await page.getByRole("button", { name: "計測開始（84問）", exact: true }).click();
        await page.getByRole("progressbar", { name: "クイック検証の進捗" }).waitFor();
        assert.equal(await backend.isDisabled(), true);
        await page.waitForFunction(() => window.settingsTest.benchmarkRun?.mode === "quick");
        await page.evaluate(() => { window.settingsTest.holdQuick = false; });

        const results = page.getByRole("region", { name: "問題ごとの結果" });
        const match = results.getByRole("row").filter({ hasText: "きしゃがしゅざいする" });
        await match.getByRole("cell", { name: "正解", exact: true }).waitFor();
        const miss = results.getByRole("row").filter({ hasText: "きょうはいしゃにいく" });
        await miss.getByRole("cell", { name: "不正解", exact: true }).waitFor();
        await miss.getByRole("cell", { name: "今日歯医者に行く", exact: true }).waitFor();
        await miss.getByRole("cell", { name: "今日敗者に行く", exact: true }).waitFor();
        await miss.getByRole("cell", { name: "15.2 ms", exact: true }).waitFor();
        await match.getByText("詳細", { exact: true }).click();
        await match.getByText("記者が取材する / 汽車が取材する", { exact: true }).waitFor();
        await page.getByRole("button", { name: "不正解のみ", exact: true }).click();
        assert.equal(await match.count(), 0);
        assert.equal(await miss.count(), 1);
        await page.getByRole("button", { name: "不正解のみ", exact: true }).click();

        await backend.selectOption("vulkan");
        await page.getByText(/この結果の使用バックエンド CPU/).waitFor();
        await page.getByRole("radio", { name: "共通候補生成", exact: true }).check();
        await page.getByRole("button", { name: "口語 (100問)", exact: true }).click();
        await page.getByRole("button", { name: "計測開始（100問）", exact: true }).click();
        await page.getByText(/この結果の使用バックエンド Vulkan（GPU全層）/).waitFor();
        await page.getByRole("button", { name: "計測開始（100問）", exact: true }).waitFor();
        assert.deepEqual(await page.evaluate(() => window.settingsTest.benchmarkRun), { backend: "vulkan", mode: "candidates" });

        await page.getByRole("radio", { name: "クイック検証（84問）", exact: true }).check();
        await miss.getByRole("cell", { name: "不正解", exact: true }).waitFor();
        await page.getByText(/この結果の使用バックエンド CPU/).waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.writes), 0);
        assert.equal(await page.evaluate(() => window.settingsTest.config.zenzai.backend), "vulkan");
        await page.getByRole("tab", { name: /タイポ補正/ }).click();
        await page.getByRole("button", { name: /タイポベンチマーク開始/ }).click();
        await page.getByText("450 / 450 問（正常保持を含む）", { exact: true }).waitFor();
        await page.getByText("0 / 255 問（実質 100.0%）", { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => window.settingsTest.benchmarkRun.mode), "typo");
    } finally {
        await browser.close();
        await server.close();
    }
});

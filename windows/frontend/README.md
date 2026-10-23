# Tauri + React + Typescript

This template should help get you started developing with Tauri, React and Typescript in Vite.

## Settings UI checks

```sh
npm run typecheck
npm test
npm run build
```

For a standalone Windows `frontend.exe`, run `npm run tauri:build` from this directory. `npm run build` builds only the web assets; a plain `cargo build --release -p frontend` does not apply the Tauri CLI's production protocol feature or generated configuration. The executable is written to `../target/release/frontend.exe`. The local trial environment uses `../../ZeroConvertIME-windows/frontend.exe`; it is a separate copy and is not updated by the build. For normal trial updates, use the repository root `Build-Package.bat` followed by `Apply-Trial.bat` to deploy the complete verified package. Do not copy this executable individually; see `docs/local-installer.md` at the repository root.

The browser regression check uses mocked Tauri commands and an existing Playwright installation (no new app dependency):

```sh
PLAYWRIGHT_MODULE=<absolute-path-to-playwright-module> node --test tests/settings-ui.browser.mjs
PLAYWRIGHT_MODULE=<absolute-path-to-playwright-module> node --test tests/model-presentation.browser.mjs
```

The model presentation check covers the corecctor attribution and license, current Standard / Nano names, removal of the dictionary correction control, and saving the assist toggle. It mocks Tauri commands and does not measure model quality or native IME behavior.

Optional environment variables: `PLAYWRIGHT_CHROMIUM_EXECUTABLE` for an existing Chromium binary, and `SETTINGS_UI_EVIDENCE_DIR` for screenshots. The check covers fixed editor actions at 800×600 and smaller sizes, validation, save failures, unsaved navigation/Back, mocked native-close events, modal focus, profile saving, stored-key check success/failures (without passing a secret to the renderer), external links, update badges, and dark mode. Native window close, Credential Manager, and Windows display scaling still require VM checks.

The Jev key check uses the authenticated `GET /v1/models` endpoint documented at https://docs.typesafe.ai/models. It does not send input, history, or inference requests; successful authentication does not guarantee available credits or successful conversion. Windows backend checks (local HTTP fixtures only): `cargo test -p frontend --lib`.

## Recommended IDE Setup

- [VS Code](https://code.visualstudio.com/) + [Tauri](https://marketplace.visualstudio.com/items?itemName=tauri-apps.tauri-vscode) + [rust-analyzer](https://marketplace.visualstudio.com/items?itemName=rust-lang.rust-analyzer)

## Theme colors

The Appearance page (`/appearance`) selects separate six-color palettes for the settings app and floating IME windows. See [theme settings and checks](../docs/themes.md). Browser regression: `node --test tests/themes.browser.mjs` with `PLAYWRIGHT_MODULE` and optionally `PLAYWRIGHT_CHANNEL` set.

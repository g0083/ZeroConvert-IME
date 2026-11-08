use anyhow::{Context as _, Result};
use tao::{
    event_loop::EventLoop,
    platform::windows::{WindowBuilderExtWindows, WindowExtWindows},
    window::{Window, WindowBuilder},
};
use windows::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{
        SetWindowLongW, GWL_EXSTYLE, GWL_STYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
        WS_POPUP,
    },
};
use wry::{WebContext, WebViewBuilder};

use crate::UserEvent;

pub fn create_ruby_window(event_loop: &EventLoop<UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_decorations(false)
        .with_title("Ruby")
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(false)
        .with_transparent(true)
        .build(event_loop)
        .context("Failed to create ruby window")?;

    let hwnd = window.hwnd() as *mut std::ffi::c_void;

    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_NOACTIVATE.0 | WS_EX_TOPMOST.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);

        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    };

    Ok(window)
}

pub fn create_ruby_webview<'a>(web_context: &'a mut WebContext) -> Result<WebViewBuilder<'a>> {
    let webview_builder = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_html(
            r##"
        <html>
            <head>
                <style>
                    body, html {
                        overscroll-behavior: none;
                        width: 100%;
                        height: 100%;
                    }
                    body {
                        margin: 0;
                        /* Keep the left inset in sync with RUBY_READING_LEFT_INSET_LOGICAL. */
                        padding: 5px 7px 4px 7px;
                        filter: drop-shadow(2px 2px 3px rgba(0, 0, 0, 0.16));
                        box-sizing: border-box;
                        display: flex;
                        align-items: flex-end;
                        justify-content: center;
                        overflow: hidden;
                    }
                    main {
                        position: relative;
                        width: fit-content;
                        min-width: 44px;
                        max-width: 100%;
                        min-height: 30px;
                        padding: 4px 12px;
                        border: 1px solid #E4E4E4;
                        border-radius: 15px;
                        background-color: #FFFFFF;
                        box-sizing: border-box;
                        color: #111827;
                        font-family: "Yu Gothic UI", "Meiryo", sans-serif;
                        font-size: 16px;
                        line-height: 1.35;
                        text-align: left;
                        white-space: normal;
                        overflow: visible;
                        user-select: none;
                        pointer-events: none;
                    }
                    #reading { display: block; max-width: 100%; }
                    .reading-text { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                    .ai-assist-pill {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        margin-bottom: 4px;
                        padding: 4px 7px;
                        border-radius: 6px;
                        font-size: 12px;
                        line-height: 1.5;
                        background-color: #EEF2FF;
                        color: #4338CA;
                        border: 1px solid #C7D2FE;
                        box-sizing: border-box;
                        white-space: normal;
                    }
                    .assist-change { flex: 1; min-width: 0; overflow-wrap: anywhere; }
                    .assist-original { opacity: .8; }
                    .assist-replacement { font-weight: 600; }
                    .assist-replacement mark { background: #DDE5FF; color: inherit; border-radius: 2px; }
                    .assist-action { flex-shrink: 0; white-space: nowrap; font-size: 11px; }
                    .ai-assist-tab {
                        display: inline-block;
                        margin-right: 3px;
                        padding: 0 4px;
                        font-size: 10px;
                        font-weight: bold;
                        border-radius: 3px;
                        background-color: #FFFFFF;
                        color: #4338CA;
                        border: 1px solid #A5B4FC;
                    }

                    /* Translation Mode Card Styles */
                    main.translation-mode {
                        width: 380px;
                        min-width: 320px;
                        max-width: 440px;
                        padding: 10px 14px;
                        border-radius: 12px;
                        background-color: var(--ime-background, #ffffff);
                        border: 1px solid var(--ime-border, #cbd5e1);
                        color: var(--ime-text, #0f172a);
                        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.28), 0 2px 8px rgba(0, 0, 0, 0.12);
                    }
                    main.translation-mode::after {
                        display: none !important;
                    }
                    .translation-card {
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                        width: 380px;
                        max-width: 100%;
                        box-sizing: border-box;
                    }
                    .translation-header {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        font-size: 11px;
                        color: var(--ime-muted, #64748b);
                    }
                    .translation-badge {
                        background: var(--ime-selection, #dbeafe);
                        color: var(--ime-accent, #2563eb);
                        padding: 2px 7px;
                        border-radius: 5px;
                        font-weight: 700;
                        font-size: 11px;
                        letter-spacing: 0.3px;
                    }
                    .translation-status {
                        font-size: 11px;
                        color: var(--ime-muted, #64748b);
                    }
                    .translation-content {
                        display: flex;
                        flex-direction: column;
                        gap: 4px;
                        margin: 2px 0;
                    }
                    .translation-row {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-size: 14px;
                        line-height: 1.4;
                    }
                    .translation-label {
                        font-size: 11px;
                        font-weight: 600;
                        color: var(--ime-muted, #64748b);
                        min-width: 44px;
                        flex-shrink: 0;
                    }
                    .translation-label.en-label {
                        font-weight: 700;
                        color: var(--ime-accent, #2563eb);
                    }
                    .translation-text {
                        flex: 1;
                        min-width: 0;
                        font-weight: 500;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        color: var(--ime-text, #0f172a);
                    }
                    .translation-text.en-text {
                        font-weight: 600;
                        color: var(--ime-accent, #2563eb);
                    }
                    .translation-text.loading {
                        color: var(--ime-muted, #64748b);
                        font-style: italic;
                        font-weight: 400;
                    }
                    .translation-footer {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        font-size: 11px;
                        color: var(--ime-muted, #64748b);
                        border-top: 1px solid var(--ime-border, #e2e8f0);
                        padding-top: 6px;
                        margin-top: 2px;
                    }
                    .translation-shortcuts {
                        display: flex;
                        gap: 10px;
                        align-items: center;
                    }
                    .translation-shortcuts .key {
                        background: var(--ime-surface, #f1f5f9);
                        border: 1px solid var(--ime-border, #cbd5e1);
                        border-radius: 4px;
                        padding: 1px 5px;
                        font-size: 10px;
                        font-family: monospace;
                        color: var(--ime-text, #0f172a);
                    }
                    .translation-model {
                        font-size: 11px;
                        color: var(--ime-muted, #64748b);
                        font-weight: 600;
                    }

                    :root main.translation-mode {
                        background: var(--ime-background);
                        border-color: var(--ime-border);
                        color: var(--ime-text);
                    }
                    :root .translation-badge {
                        background: var(--ime-selection);
                        color: var(--ime-accent);
                    }
                    :root .translation-shortcuts .key {
                        background: var(--ime-surface);
                        border-color: var(--ime-border);
                        color: var(--ime-text);
                    }
                    :root .translation-footer {
                        border-top-color: var(--ime-border);
                    }
                    :root .translation-text.en-text {
                        color: var(--ime-accent);
                    }
                    :root .translation-label.en-label {
                        color: var(--ime-accent);
                    }
                    :root .translation-status,
                    :root .translation-label,
                    :root .translation-model,
                    :root .translation-text.loading,
                    :root .translation-footer {
                        color: var(--ime-muted);
                    }
                    #measurement {
                        position: absolute;
                        left: 0;
                        top: 0;
                        display: inline-block;
                        width: max-content;
                        overflow: visible;
                        white-space: normal;
                        visibility: hidden;
                        pointer-events: none;
                    }
                    main::after {
                        content: "";
                        position: absolute;
                        left: 50%;
                        top: 100%;
                        width: 1px;
                        height: 4px;
                        background-color: #A3A3A3;
                        transform: translateX(-50%);
                    }

                    @media (prefers-color-scheme: dark) {
                        main {
                            border-color: #424242;
                            background-color: #1E1E1E;
                            color: #FFFFFF;
                        }
                        main::after {
                            background-color: #6B7280;
                        }
                        .ai-assist-pill {
                            background-color: #312E81;
                            color: #C7D2FE;
                            border-color: #4338CA;
                        }
                        .assist-replacement mark { background: #4338CA; }
                        .ai-assist-tab {
                            background-color: #1E1B4B;
                            color: #E0E7FF;
                            border-color: #6366F1;
                        }
                        main.translation-mode {
                            background-color: #1e1e24;
                            border-color: #3f3f4e;
                            color: #f8fafc;
                        }
                        .translation-badge {
                            background: #1e3a8a;
                            color: #93c5fd;
                        }
                        .translation-header,
                        .translation-status,
                        .translation-label {
                            color: #94a3b8;
                        }
                        .translation-label.en-label {
                            color: #60a5fa;
                        }
                        .translation-text {
                            color: #f8fafc;
                        }
                        .translation-text.en-text {
                            color: #60a5fa;
                        }
                        .translation-text.loading {
                            color: #94a3b8;
                        }
                        .translation-footer {
                            border-top-color: #3f3f4e;
                            color: #94a3b8;
                        }
                        .translation-shortcuts .key {
                            background: #2b2b36;
                            border-color: #3f3f4e;
                            color: #f8fafc;
                        }
                        .translation-model {
                            color: #94a3b8;
                        }
                    }
                </style>
                <script>
                    let currentRequestId = 0;
                    let measureFrame = null;
                    let resizeObserver = null;
                    const maxOverlayWidth = Math.max(160, Math.min(480, screen.availWidth - 16));

                    function cssPixels(style, property) {
                        const value = Number.parseFloat(style.getPropertyValue(property));
                        return Number.isFinite(value) ? value : 0;
                    }

                    function horizontalBox(style) {
                        return cssPixels(style, 'padding-left')
                            + cssPixels(style, 'padding-right')
                            + cssPixels(style, 'border-left-width')
                            + cssPixels(style, 'border-right-width');
                    }

                    function verticalBox(style) {
                        return cssPixels(style, 'padding-top')
                            + cssPixels(style, 'padding-bottom')
                            + cssPixels(style, 'border-top-width')
                            + cssPixels(style, 'border-bottom-width');
                    }

                    function scheduleMeasureRuby() {
                        measureRuby();
                    }

                    function measureRuby() {
                        const readingElement = document.getElementById('reading');
                        const measurementElement = document.getElementById('measurement');
                        const main = document.querySelector('main');
                        const body = document.body;
                        if (!readingElement || !measurementElement || !main || !body) {
                            return;
                        }

                        const isTranslation = main.classList.contains('translation-mode');
                        const mainStyle = window.getComputedStyle(main);
                        const bodyStyle = window.getComputedStyle(body);
                        const maxContentWidth = isTranslation
                            ? Math.max(380, maxOverlayWidth)
                            : (maxOverlayWidth - horizontalBox(mainStyle) - horizontalBox(bodyStyle));
                        measurementElement.style.maxWidth = Math.max(80, maxContentWidth) + "px";
                        const measurementRect = measurementElement.getBoundingClientRect();
                        const bodyHorizontal = horizontalBox(bodyStyle);
                        const bodyVertical = verticalBox(bodyStyle);
                        const measurementWidth = Math.max(
                            Math.min(measurementElement.scrollWidth, maxContentWidth),
                            measurementRect.width
                        );
                        const desiredMainWidth = Math.max(
                            measurementWidth + horizontalBox(mainStyle),
                            cssPixels(mainStyle, 'min-width')
                        );
                        const desiredMainHeight = Math.max(
                            measurementRect.height + verticalBox(mainStyle),
                            cssPixels(mainStyle, 'min-height')
                        );
                        const width = Math.ceil(desiredMainWidth + bodyHorizontal);
                        const height = Math.ceil(desiredMainHeight + bodyVertical);

                        if (!Number.isFinite(width) || !Number.isFinite(height)) {
                            return;
                        }

                        window.ipc.postMessage(JSON.stringify({
                            type: 'ruby-resize',
                            requestId: currentRequestId,
                            width,
                            height
                        }));
                    }

                    function escapeHtml(str) {
                        return String(str).replace(/[&<>"']/g, function(m) {
                            switch (m) {
                                case '&': return '&amp;';
                                case '<': return '&lt;';
                                case '>': return '&gt;';
                                case '"': return '&quot;';
                                case "'": return '&#39;';
                                default: return m;
                            }
                        });
                    }

                    function correctionDiff(source, replacement) {
                        const a = Array.from(source), b = Array.from(replacement);
                        let start = 0, end = 0;
                        while (start < a.length && start < b.length && a[start] === b[start]) start++;
                        while (end < a.length - start && end < b.length - start && a[a.length-1-end] === b[b.length-1-end]) end++;
                        const left = Math.max(0, start - 8), right = Math.min(b.length, b.length - end + 8);
                        const before = (left ? '…' : '') + b.slice(left, start).join('');
                        const changed = b.slice(start, b.length-end).join('');
                        const after = b.slice(b.length-end, right).join('') + (right < b.length ? '…' : '');
                        const originalRight = Math.min(a.length, a.length-end+8);
                        return { original: (left ? '…' : '') + a.slice(left, originalRight).join('') + (originalRight < a.length ? '…' : ''),
                            replacement: escapeHtml(before) + '<mark>' + escapeHtml(changed) + '</mark>' + escapeHtml(after) };
                    }

                    function updateReading(reading, requestId) {
                        const readingElement = document.getElementById('reading');
                        const measurementElement = document.getElementById('measurement');
                        const main = document.querySelector('main');
                        if (!readingElement || !measurementElement) {
                            return;
                        }

                        const numericRequestId = Number(requestId);
                        currentRequestId = Number.isFinite(numericRequestId)
                            ? numericRequestId
                            : currentRequestId + 1;
                        const text = typeof reading === 'string' ? reading : '';
                        
                        let readingText = text, assist = null;
                        let translationData = null;
                        // Structured presentation travels in the existing reading envelope.
                        // Legacy tab hints remain readable during a rolling binary update.
                        try {
                            const value = JSON.parse(text);
                            if (value && value.translationMode) {
                                translationData = value;
                            } else if (value && typeof value.reading === 'string' && value.assist) {
                                readingText = value.reading;
                                assist = value.assist;
                            }
                        } catch (_) {}

                        readingElement.innerHTML = '';

                        if (translationData) {
                            if (main) main.classList.add('translation-mode');
                            const ja = translationData.source || '';
                            const en = translationData.translation || '';
                            const hasError = Boolean(translationData.translationError);
                            const isLoading = !en && !hasError;
                            const modelStr = String(translationData.model || '0.8b').toLowerCase();
                            const modelTag = modelStr.includes('2b') ? '2B' : '0.8B';
                            const targetStr = String(translationData.target || 'auto');
                            const sourceLabel = targetStr === 'en' ? '入力:' : '日本語:';
                            const resultLabel = targetStr === 'en' ? '英訳:' : targetStr === 'ja' ? '日本語訳:' : '英訳:';

                            const statusText = hasError
                                ? '翻訳失敗・入力し直して再試行'
                                : (isLoading ? (ja ? '翻訳中' : '入力待ち') : 'リアルタイム英訳入力中');

                            const jaText = ja || '入力待ち…';
                            const enText = hasError
                                ? '翻訳できませんでした。Escで取消'
                                : (isLoading ? '翻訳中…' : en);
                            const loadingClass = isLoading ? ' loading' : '';

                            readingElement.innerHTML =
                                '<div class="translation-card">' +
                                    '<div class="translation-header">' +
                                        '<span class="translation-badge">翻訳モード (PLaMo 3)</span>' +
                                        '<span class="translation-status">' + escapeHtml(statusText) + '</span>' +
                                    '</div>' +
                                    '<div class="translation-content">' +
                                        '<div class="translation-row ja">' +
                                            '<span class="translation-label">' + escapeHtml(sourceLabel) + '</span>' +
                                            '<span class="translation-text ja-text">' + escapeHtml(jaText) + '</span>' +
                                        '</div>' +
                                        '<div class="translation-row en">' +
                                            '<span class="translation-label en-label">' + escapeHtml(resultLabel) + '</span>' +
                                            '<span class="translation-text en-text' + loadingClass + '">' + escapeHtml(enText) + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="translation-footer">' +
                                        '<div class="translation-shortcuts">' +
                                            '<span><span class="key">Enter</span> 確定</span>' +
                                            '<span><span class="key">Esc</span> 取消</span>' +
                                            '<span><span class="key">Ctrl+T</span> 終了</span>' +
                                        '</div>' +
                                        '<span class="translation-model">' + escapeHtml(modelTag) + '</span>' +
                                    '</div>' +
                                '</div>';
                        } else {
                            if (main) main.classList.remove('translation-mode');
                            if (!assist && text.includes('\t')) {
                                const parts = text.split('\t');
                                readingText = parts[0];
                                assist = { status: parts[1].startsWith('🔮') ? 'prediction' : 'proposal',
                                    source: readingText, replacement: parts[1].replace(/^[💡🔮]\s*/u, '').replace(' [Tab]', '') };
                            }
                            if (assist && typeof assist.replacement === 'string') {
                                const applied = assist.status === 'applied';
                                const prediction = assist.status === 'prediction';
                                const translation = assist.status === 'translation';
                                const diff = correctionDiff(String(assist.source || readingText), assist.replacement);
                                const change = translation ? '英訳: ' + escapeHtml(assist.replacement) : prediction ? escapeHtml(typeof assist.continuation === 'string' && assist.continuation ? assist.continuation : assist.replacement) :
                                    '<span class="assist-original">' + escapeHtml(diff.original) + '</span> → ' +
                                    '<span class="assist-replacement">' + diff.replacement + '</span>';
                                let assistHtml = '<span class="ai-assist-pill" role="status">' +
                                    '<span class="assist-change">' + change + '</span>' +
                                    '<span class="assist-action">' + (applied ? '補正済み · ' : '') +
                                    '<span class="ai-assist-tab">' + (applied ? 'Esc' : 'Tab') + '</span>' +
                                    (applied ? '戻す' : prediction || translation ? 'で確定' : 'で補正') + '</span></span>';
                                if (prediction && assist.undoAvailable) {
                                    assistHtml += '<span class="assist-action">補正済み · Escで戻す</span>';
                                }
                                readingElement.innerHTML += assistHtml;
                            }
                            readingElement.innerHTML += '<span class="reading-text">' + escapeHtml(readingText) + '</span>';
                        }

                        // Measure the actual styled badges, including their margins,
                        // padding and borders. A plain-text approximation can be a few
                        // pixels too narrow, causing the entire inline-block assist
                        // pill to be replaced by the reading's ellipsis.
                        measurementElement.innerHTML = readingElement.innerHTML;

                        scheduleMeasureRuby();
                    }

                    window.addEventListener('DOMContentLoaded', () => {
                        const main = document.querySelector('main');
                        if (window.ResizeObserver && main) {
                            resizeObserver = new ResizeObserver(scheduleMeasureRuby);
                            resizeObserver.observe(main);
                        }

                        scheduleMeasureRuby();
                    });
                </script>
            </head>
            <body>
                <main>
                    <span id="reading"></span>
                    <span id="measurement" aria-hidden="true"></span>
                </main>
            </body>
        </html>"##,
        );

    Ok(webview_builder)
}

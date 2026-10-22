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

const CANDIDATE_SCROLLER_SCRIPT: &str = include_str!("candidate_scroller.js");

pub fn create_candidate_window(event_loop: &EventLoop<UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_decorations(false)
        .with_title("CandidateList")
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(false)
        .with_transparent(true)
        .build(event_loop)
        .context("Failed to create window")?;

    let hwnd = window.hwnd() as *mut std::ffi::c_void;

    // set extended window style
    // https://docs.microsoft.com/en-us/windows/win32/winmsg/extended-window-styles
    // https://docs.microsoft.com/en-us/windows/win32/winmsg/window-styles
    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_NOACTIVATE.0 | WS_EX_TOPMOST.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);

        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    };

    Ok(window)
}

fn candidate_html() -> String {
    r##"
        <html>
            <head>
                <style>
                    body, html {
                        overscroll-behavior: none;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Yu Gothic UI", sans-serif;
                    }
                    body {
                        margin: 0;
                        padding: 8px;
                        filter: drop-shadow(0 10px 25px rgba(0, 0, 0, 0.12));
                    }
                    main {
                        width: 100%;
                        height: 100%;
                        padding: 6px;
                        border: 1px solid rgba(0, 0, 0, 0.08);
                        border-radius: 12px;
                        background-color: #FFFFFF;
                        box-sizing: border-box;
                        display: flex;
                        flex-direction: column;
                    }
                    main[data-candidate-list-hidden] ol,
                    main[data-candidate-list-hidden] footer {
                        display: none;
                    }
                    ol {
                        margin: 0;
                        padding: 0;
                        flex: 1;
                        overflow-y: auto;
                        overflow-anchor: none;
                        list-style-position: inside;
                        list-style-type: none;
                        user-select: none;
                        cursor: pointer;

                        &::-webkit-scrollbar {
                            width: 4px;
                        }

                        &::-webkit-scrollbar-thumb {
                            background-color: rgba(0, 0, 0, 0.15);
                            border-radius: 4px;
                        }
                    }
                    li {
                        padding: 7px 10px;
                        font-size: 13.5px;
                        font-weight: 500;
                        display: flex;
                        align-items: center;
                        border-radius: 8px;
                        color: #1E293B;
                        transition: background-color 0.12s ease;

                        &::before {
                            content: attr(data-number);
                            color: #64748B;
                            font-weight: 600;
                            font-size: 11px;
                            margin: 0 10px 0 0;
                            width: 18px;
                            height: 18px;
                            display: inline-flex;
                            align-items: center;
                            justify-content: center;
                            background: rgba(0, 0, 0, 0.04);
                            border-radius: 5px;
                            flex-shrink: 0;
                        }

                        &[data-selected] {
                            background-color: #F1F5F9;
                            color: #0F172A;
                            font-weight: 600;
                            box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.07);

                            &::before {
                                background: #334155;
                                color: #FFFFFF;
                            }
                        }
                    }
                    .virtual-spacer {
                        display: block;
                        margin: 0;
                        padding: 0;
                        border: 0;
                        pointer-events: none;
                    }
                    .virtual-spacer::before {
                        content: none;
                    }
                    .candidate-text {
                        min-width: 0;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        white-space: nowrap;
                    }
                    footer {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        padding: 6px 10px 4px 10px;
                        border-top: 1px solid rgba(0, 0, 0, 0.06);
                        font-size: 11px;
                        color: #94A3B8;
                        font-weight: 500;
                        user-select: none;
                    }

                    @media (prefers-color-scheme: dark) {
                        body {
                            color: #F1F5F9;
                        }
                        main {
                            border: 1px solid rgba(255, 255, 255, 0.1);
                            background-color: #13151B;
                        }
                        ol::-webkit-scrollbar-thumb {
                            background-color: rgba(255, 255, 255, 0.16);
                        }
                        li {
                            color: #E2E8F0;
                        
                            &::before {
                                color: #94A3B8;
                                background: rgba(255, 255, 255, 0.06);
                            }

                            &[data-selected] {
                                background-color: #1E2330;
                                color: #FFFFFF;
                                box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);

                                &::before {
                                    background: #475569;
                                    color: #F8FAFC;
                                }
                            }
                        }
                            
                        footer {
                            border-top: 1px solid rgba(255, 255, 255, 0.07);
                            color: #64748B;
                        }
                    }
                </style>
                <script>
                    __CANDIDATE_SCROLLER_SCRIPT__

                    const {
                        VISIBLE_ITEM_COUNT,
                        clampCandidateIndex,
                        clampScrollTop,
                        selectionPageScrollTop,
                        isSelectionFullyVisible,
                        calculateRenderRange,
                    } = CandidateScroller;
                    let currentCandidates = [];
                    let currentSelectionIndex = 0;
                    let currentItemHeight = 0;
                    let renderedRangeStart = -1;
                    let renderedRangeEnd = -1;
                    let adjustWindowSizeFrame = null;
                    let renderCandidateRangeFrame = null;

                    function scheduleAdjustWindowSize() {
                        if (adjustWindowSizeFrame !== null) {
                            return;
                        }

                        adjustWindowSizeFrame = window.requestAnimationFrame(() => {
                            adjustWindowSizeFrame = null;
                            adjustWindowSize();
                        });
                    }

                    function scheduleRenderCandidateRange() {
                        if (renderCandidateRangeFrame !== null) {
                            return;
                        }

                        renderCandidateRangeFrame = window.requestAnimationFrame(() => {
                            renderCandidateRangeFrame = null;
                            renderCandidateRange();
                        });
                    }

                    function clampSelectionIndex(index) {
                        return clampCandidateIndex(index, currentCandidates.length);
                    }

                    function createCandidateItem(index) {
                        const li = document.createElement('li');
                        const text = document.createElement('span');
                        li.className = 'candidate-item';
                        text.className = 'candidate-text';
                        text.textContent = currentCandidates[index];
                        text.title = currentCandidates[index];
                        li.appendChild(text);
                        li.setAttribute('data-number', String(index + 1));
                        if (index === currentSelectionIndex) {
                            li.setAttribute('data-selected', '');
                        }
                        return li;
                    }

                    function createVirtualSpacer(height) {
                        const spacer = document.createElement('li');
                        spacer.className = 'virtual-spacer';
                        spacer.setAttribute('aria-hidden', 'true');
                        spacer.setAttribute('role', 'presentation');
                        spacer.style.height = `${height}px`;
                        return spacer;
                    }

                    function measureListItemHeight(candidateList) {
                        const existingItem = candidateList.querySelector('.candidate-item');
                        if (existingItem && existingItem.offsetHeight > 0) {
                            currentItemHeight = existingItem.offsetHeight;
                            return currentItemHeight;
                        }

                        const li = document.createElement('li');
                        const text = document.createElement('span');
                        li.className = 'candidate-item';
                        li.style.visibility = 'hidden';
                        li.setAttribute('data-number', '1');
                        text.className = 'candidate-text';
                        text.textContent = 'Item';
                        li.appendChild(text);
                        candidateList.appendChild(li);
                        currentItemHeight = li.offsetHeight;
                        candidateList.removeChild(li);
                        return currentItemHeight;
                    }

                    function renderCandidateRange(force = false, requestedScrollTop = null) {
                        const candidateList = document.getElementById('candidate-list');
                        if (!candidateList) {
                            return;
                        }

                        if (currentCandidates.length === 0) {
                            candidateList.replaceChildren();
                            candidateList.scrollTop = 0;
                            renderedRangeStart = 0;
                            renderedRangeEnd = 0;
                            return;
                        }

                        const itemHeight = currentItemHeight || measureListItemHeight(candidateList);
                        if (itemHeight <= 0) {
                            return;
                        }

                        const scrollTop = requestedScrollTop === null
                            ? candidateList.scrollTop
                            : requestedScrollTop;
                        const safeScrollTop = clampScrollTop(
                            scrollTop,
                            currentCandidates.length,
                            itemHeight
                        );
                        const range = calculateRenderRange(
                            currentCandidates.length,
                            safeScrollTop,
                            itemHeight
                        );
                        if (!force &&
                            range.start === renderedRangeStart &&
                            range.end === renderedRangeEnd) {
                            return;
                        }

                        const fragment = document.createDocumentFragment();
                        if (range.topSpacerHeight > 0) {
                            fragment.appendChild(createVirtualSpacer(range.topSpacerHeight));
                        }
                        for (let index = range.start; index < range.end; index += 1) {
                            fragment.appendChild(createCandidateItem(index));
                        }
                        if (range.bottomSpacerHeight > 0) {
                            fragment.appendChild(createVirtualSpacer(range.bottomSpacerHeight));
                        }

                        candidateList.replaceChildren(fragment);
                        // Spacers must establish the new scroll height before applying a requested offset.
                        if (requestedScrollTop !== null || safeScrollTop !== scrollTop) {
                            candidateList.scrollTop = safeScrollTop;
                        }
                        renderedRangeStart = range.start;
                        renderedRangeEnd = range.end;
                    }

                    function updateCandidates(candidates, selectedIndex = null) {
                        if (!Array.isArray(candidates)) {
                            return;
                        }

                        currentCandidates = candidates;
                        currentSelectionIndex = clampSelectionIndex(
                            selectedIndex === null ? currentSelectionIndex : selectedIndex
                        );

                        const candidateList = document.getElementById('candidate-list');
                        if (candidateList) {
                            const itemHeight = currentItemHeight || measureListItemHeight(candidateList);
                            const desiredScrollTop = selectionPageScrollTop(
                                currentSelectionIndex,
                                currentCandidates.length,
                                itemHeight
                            );
                            renderCandidateRange(true, desiredScrollTop);
                        }

                        scheduleAdjustWindowSize();
                    }

                    function setCandidateListVisible(visible) {
                        const main = document.querySelector('main');
                        if (!main) {
                            return;
                        }

                        if (visible) {
                            main.removeAttribute('data-candidate-list-hidden');
                            renderCandidateRange(true);
                        } else {
                            main.setAttribute('data-candidate-list-hidden', '');
                        }

                        scheduleAdjustWindowSize();
                    }

                    function updateSelection(index) {
                        const candidateList = document.getElementById('candidate-list');
                        if (!candidateList || currentCandidates.length === 0) {
                            return;
                        }

                        const itemHeight = currentItemHeight || measureListItemHeight(candidateList);
                        const safeIndex = clampSelectionIndex(index);
                        currentSelectionIndex = safeIndex;
                        if (!isSelectionFullyVisible(
                            safeIndex,
                            candidateList.scrollTop,
                            currentCandidates.length,
                            itemHeight
                        )) {
                            candidateList.scrollTop = selectionPageScrollTop(
                                safeIndex,
                                currentCandidates.length,
                                itemHeight
                            );
                        }
                        renderCandidateRange(true);
                    }

                    function adjustWindowSize() {
                        const candidateList = document.getElementById('candidate-list');
                        const footer = document.querySelector('footer');
                        const main = document.querySelector('main');
                        const body = document.body;
                        if (!candidateList || !footer || !main) {
                            return;
                        }

                        const candidateListVisible = !main.hasAttribute('data-candidate-list-hidden');
                        const itemHeight = candidateListVisible ? measureListItemHeight(candidateList) : 0;
                        const candidateListHeight = itemHeight * VISIBLE_ITEM_COUNT;
                        const footerHeight = candidateListVisible ? footer.offsetHeight : 0;
                        const mainPadding = parseInt(window.getComputedStyle(main).paddingTop) + 
                                           parseInt(window.getComputedStyle(main).paddingBottom);
                        const bodyPadding = parseInt(window.getComputedStyle(body).paddingTop) + 
                                          parseInt(window.getComputedStyle(body).paddingBottom);
                        const totalHeight = candidateListHeight + footerHeight + mainPadding + bodyPadding;
                        
                        window.ipc.postMessage(JSON.stringify({
                            type: 'resize',
                            height: totalHeight
                        }));
                    }

                    
                    window.addEventListener('DOMContentLoaded', () => {
                        const candidateList = document.getElementById('candidate-list');
                        if (candidateList) {
                            candidateList.addEventListener('scroll', scheduleRenderCandidateRange, {
                                passive: true,
                            });
                        }
                        setTimeout(adjustWindowSize, 50); // Small delay to ensure rendering is complete
                    });
                </script>
            </head>
            <body style="margin: 0;">
                <main>
                    <ol id="candidate-list">
                    </ol>
                    <footer>
                        <span style="letter-spacing: 0.05em; font-weight: 600; font-size: 10px; text-transform: uppercase; color: #94A3B8;">ZeroCon</span>
                    </footer>
                </main>
            </body>
        </html>"##
        .replace("__CANDIDATE_SCROLLER_SCRIPT__", CANDIDATE_SCROLLER_SCRIPT)
}

pub fn create_candidate_webview<'a>(web_context: &'a mut WebContext) -> Result<WebViewBuilder<'a>> {
    let html = candidate_html();

    let webview_builder = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_html(html);

    Ok(webview_builder)
}

#[cfg(test)]
mod tests {
    use super::candidate_html;

    fn style_contents(html: &str) -> &str {
        html.split_once("<style>")
            .and_then(|(_, rest)| rest.split_once("</style>"))
            .map(|(style, _)| style)
            .expect("candidate HTML must contain a style element")
    }

    fn assert_nonzero_lengths_have_units(value: &str) {
        for component in value.split_ascii_whitespace() {
            if let Ok(number) = component.parse::<f64>() {
                if number == 0.0 {
                    continue;
                }
            }

            let unit_start = component
                .find(|character: char| {
                    !character.is_ascii_digit()
                        && character != '.'
                        && character != '-'
                        && character != '+'
                })
                .unwrap_or(component.len());
            let (number, unit) = component.split_at(unit_start);

            assert!(
                number.parse::<f64>().is_ok() && !unit.is_empty(),
                "non-zero CSS length must include a unit: {component}"
            );
        }
    }

    #[test]
    fn candidate_spacing_lengths_have_explicit_units() {
        let html = candidate_html();
        let style = style_contents(&html);

        for declaration in style.lines().map(str::trim) {
            let Some((property, value)) = declaration
                .strip_suffix(';')
                .and_then(|declaration| declaration.split_once(':'))
            else {
                continue;
            };
            if property == "margin"
                || property == "padding"
                || property.starts_with("margin-")
                || property.starts_with("padding-")
            {
                assert_nonzero_lengths_have_units(value.trim());
            }
        }

        assert!(style.contains("margin: 0 10px 0 0;"));
        assert!(style.contains("padding: 6px 10px 4px 10px;"));
    }
}

(function (root, factory) {
    const api = factory();

    if (typeof module === "object" && module.exports) {
        module.exports = api;
    } else {
        root.CorrectionDiff = api;
    }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    const DEFAULT_CONTEXT_CHARS = 5;
    const DEFAULT_MAX_CHANGED_CHARS = 14;
    const MAX_DP_CELLS = 20000;
    const MAX_MERGE_GAP = 2;
    const MAX_MERGED_HUNK_LEN = 6;
    const MAX_COMBINED_SPAN = 8;

    function defaultEscapeHtml(str) {
        return String(str).replace(/[&<>"']/g, function (m) {
            return {
                "&": "&amp;",
                "<": "&lt;",
                ">": "&gt;",
                '"': "&quot;",
                "'": "&#39;",
            }[m];
        });
    }

    function containsAsciiTypo(tokens) {
        return /[A-Za-z0-9]/.test(tokens.join(""));
    }

    function commonAffixLengths(a, b) {
        let prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) {
            prefix++;
        }
        let suffix = 0;
        while (
            suffix < a.length - prefix &&
            suffix < b.length - prefix &&
            a[a.length - 1 - suffix] === b[b.length - 1 - suffix]
        ) {
            suffix++;
        }
        return { prefix, suffix };
    }

    function computeLcsMatches(aMid, bMid) {
        const m = aMid.length;
        const n = bMid.length;
        if (m === 0 || n === 0 || m * n > MAX_DP_CELLS) {
            return null;
        }
        const stride = n + 1;
        const dp = new Int32Array((m + 1) * stride);
        for (let i = m - 1; i >= 0; i--) {
            const row = i * stride;
            const nextRow = (i + 1) * stride;
            for (let j = n - 1; j >= 0; j--) {
                if (aMid[i] === bMid[j]) {
                    dp[row + j] = 1 + dp[nextRow + j + 1];
                } else {
                    const down = dp[nextRow + j];
                    const right = dp[row + j + 1];
                    dp[row + j] = down >= right ? down : right;
                }
            }
        }
        const matches = [];
        let i = 0;
        let j = 0;
        while (i < m && j < n) {
            if (aMid[i] === bMid[j]) {
                matches.push([i, j]);
                i++;
                j++;
            } else if (dp[(i + 1) * stride + j] >= dp[i * stride + j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matches;
    }

    function extractDiffHunks(a, b, prefix, suffix) {
        const aMid = a.slice(prefix, a.length - suffix);
        const bMid = b.slice(prefix, b.length - suffix);
        const matches = computeLcsMatches(aMid, bMid);
        if (!matches || matches.length === 0) {
            return [{
                aStart: prefix,
                aEnd: a.length - suffix,
                bStart: prefix,
                bEnd: b.length - suffix,
            }];
        }

        const raw = [];
        let curA = 0;
        let curB = 0;
        matches.push([aMid.length, bMid.length]);
        for (let k = 0; k < matches.length; k++) {
            const [mA, mB] = matches[k];
            if (mA > curA || mB > curB) {
                raw.push({
                    aStart: prefix + curA,
                    aEnd: prefix + mA,
                    bStart: prefix + curB,
                    bEnd: prefix + mB,
                });
            }
            curA = mA + 1;
            curB = mB + 1;
        }
        if (raw.length === 0) {
            return [{
                aStart: prefix,
                aEnd: a.length - suffix,
                bStart: prefix,
                bEnd: b.length - suffix,
            }];
        }

        const merged = [raw[0]];
        for (let k = 1; k < raw.length; k++) {
            const prev = merged[merged.length - 1];
            const next = raw[k];
            const gapA = next.aStart - prev.aEnd;
            const gapB = next.bStart - prev.bEnd;
            const combinedALen = next.aEnd - prev.aStart;
            const combinedBLen = next.bEnd - prev.bStart;
            if (
                gapA === gapB &&
                gapA <= MAX_MERGE_GAP &&
                combinedALen <= MAX_MERGED_HUNK_LEN &&
                combinedBLen <= MAX_MERGED_HUNK_LEN
            ) {
                merged[merged.length - 1] = {
                    aStart: prev.aStart,
                    aEnd: next.aEnd,
                    bStart: prev.bStart,
                    bEnd: next.bEnd,
                };
            } else {
                merged.push(next);
            }
        }
        return merged;
    }

    function extractFocusedHunk(source, replacement) {
        const a = Array.from(String(source || ""));
        const b = Array.from(String(replacement || ""));
        const { prefix, suffix } = commonAffixLengths(a, b);
        if (prefix + suffix >= a.length && prefix + suffix >= b.length) {
            return { a, b, hunk: null };
        }
        const hunks = extractDiffHunks(a, b, prefix, suffix);
        if (hunks.length === 1) {
            return { a, b, hunk: hunks[0] };
        }
        const first = hunks[0];
        const last = hunks[hunks.length - 1];
        if (
            last.aEnd - first.aStart <= MAX_COMBINED_SPAN &&
            last.bEnd - first.bStart <= MAX_COMBINED_SPAN
        ) {
            return {
                a,
                b,
                hunk: {
                    aStart: first.aStart,
                    aEnd: last.aEnd,
                    bStart: first.bStart,
                    bEnd: last.bEnd,
                },
            };
        }
        for (let k = hunks.length - 1; k >= 0; k--) {
            const h = hunks[k];
            if (containsAsciiTypo(a.slice(h.aStart, h.aEnd))) {
                return { a, b, hunk: h };
            }
        }
        return { a, b, hunk: last };
    }

    function buildSideWindow(tokens, start, end, contextChars, maxChangedChars) {
        const changed = tokens.slice(start, end);
        let right = Math.min(tokens.length, end + contextChars);
        if (right === tokens.length - 1) {
            right = tokens.length;
        }
        const after = tokens.slice(end, right).join("") + (right < tokens.length ? "…" : "");
        if (changed.length > maxChangedChars) {
            return {
                before: "…",
                changed: changed.slice(changed.length - maxChangedChars).join(""),
                after,
            };
        }
        let left = Math.max(0, start - contextChars);
        if (left === 1) {
            left = 0;
        }
        const before = (left > 0 ? "…" : "") + tokens.slice(left, start).join("");
        return {
            before,
            changed: changed.join(""),
            after,
        };
    }

    function correctionDiff(source, replacement, options) {
        const opts = options || {};
        const contextChars =
            typeof opts.contextChars === "number" ? opts.contextChars : DEFAULT_CONTEXT_CHARS;
        const maxChangedChars =
            typeof opts.maxChangedChars === "number"
                ? opts.maxChangedChars
                : DEFAULT_MAX_CHANGED_CHARS;
        const escapeHtml =
            typeof opts.escapeHtml === "function" ? opts.escapeHtml : defaultEscapeHtml;

        const { a, b, hunk } = extractFocusedHunk(source, replacement);
        if (!hunk) {
            const tailKeep = contextChars * 2;
            const origText =
                a.length > tailKeep + 4 ? "…" + a.slice(a.length - tailKeep).join("") : a.join("");
            const replText =
                b.length > tailKeep + 4 ? "…" + b.slice(b.length - tailKeep).join("") : b.join("");
            return {
                original: origText,
                replacement: escapeHtml(replText),
            };
        }

        const origWin = buildSideWindow(a, hunk.aStart, hunk.aEnd, contextChars, maxChangedChars);
        const replWin = buildSideWindow(b, hunk.bStart, hunk.bEnd, contextChars, maxChangedChars);
        const markedChanged = replWin.changed
            ? "<mark>" + escapeHtml(replWin.changed) + "</mark>"
            : "";

        return {
            original: origWin.before + origWin.changed + origWin.after,
            replacement:
                escapeHtml(replWin.before) + markedChanged + escapeHtml(replWin.after),
        };
    }

    return {
        correctionDiff,
        extractFocusedHunk,
    };
});

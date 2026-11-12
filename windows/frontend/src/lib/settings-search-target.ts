export type PendingSettingsTarget = { label: string; section: string };

let pendingTarget: PendingSettingsTarget | null = null;

export function setPendingSettingsTarget(target: PendingSettingsTarget) {
    pendingTarget = target;
}

export function consumePendingSettingsTarget(): PendingSettingsTarget | null {
    const target = pendingTarget;
    pendingTarget = null;
    return target;
}

/**
 * 同じラベルが複数セクションにあるため、セクション見出しを先に特定してから
 * その中の行を探す。見つからない場合は画面全体からラベル一致で探す。
 */
export function findSettingsRow(label: string, section: string): HTMLElement | null {
    const sections = document.querySelectorAll<HTMLElement>("section.settings-section");
    for (const element of sections) {
        if (element.querySelector("h2")?.textContent?.trim() !== section) continue;
        const row = element.querySelector<HTMLElement>(`[data-settings-label="${CSS.escape(label)}"]`);
        if (row) return row;
    }
    return document.querySelector<HTMLElement>(`[data-settings-label="${CSS.escape(label)}"]`);
}

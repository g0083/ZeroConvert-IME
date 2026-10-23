import palettes from "../../../crates/shared/themes.json";
export const colorThemes = palettes;
export type ColorThemeId = "dark_blue" | "graphite" | "forest" | "plum" | "sakura" | "paper";
export type AppearanceConfig = { frontend_theme: ColorThemeId; ime_theme: ColorThemeId };
export const defaultAppearance: AppearanceConfig = { frontend_theme: "dark_blue", ime_theme: "dark_blue" };
export const getColorTheme = (id: string) => colorThemes.find((theme) => theme.id === id) ?? colorThemes[0];
export function applyFrontendTheme(id: ColorThemeId) {
    const theme = getColorTheme(id);
    const root = document.documentElement;
    root.classList.remove("dark", "light");
    root.classList.add(theme.mode);
    root.dataset.colorTheme = theme.id;
    root.style.colorScheme = theme.mode;
    const variables: Record<string, string> = {
        background: theme.background, foreground: theme.text,
        card: theme.surface, "card-foreground": theme.text,
        popover: theme.surface, "popover-foreground": theme.text,
        primary: theme.accent, "primary-foreground": theme.onAccent,
        secondary: theme.selection, "secondary-foreground": theme.text,
        muted: theme.selection, "muted-foreground": theme.muted,
        accent: theme.selection, "accent-foreground": theme.text,
        border: theme.border, input: theme.border, ring: theme.accent,
        sidebar: theme.surface, "sidebar-foreground": theme.text,
        "sidebar-primary": theme.accent, "sidebar-primary-foreground": theme.onAccent,
        "sidebar-accent": theme.selection, "sidebar-accent-foreground": theme.text,
        "sidebar-border": theme.border, "sidebar-ring": theme.accent,
    };
    for (const [name, value] of Object.entries(variables)) root.style.setProperty(`--${name}`, value);
}

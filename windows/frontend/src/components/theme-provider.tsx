import { toast } from "sonner";
import { createContext, useContext, useEffect, useLayoutEffect, useState } from "react";
import { getConfigAfterPendingUpdates, saveConfigWithToast } from "@/lib/config";
import { applyFrontendTheme, defaultAppearance, getColorTheme, type AppearanceConfig, type ColorThemeId } from "@/lib/themes";

type ThemeState = {
    theme: "dark" | "light";
    appearance: AppearanceConfig;
    loading: boolean;
    saving: boolean;
    saveTheme: (target: keyof AppearanceConfig, theme: ColorThemeId) => Promise<void>;
};
const ThemeContext = createContext<ThemeState | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
    const [appearance, setAppearance] = useState(defaultAppearance);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    useEffect(() => {
        let active = true;
        void getConfigAfterPendingUpdates().then((config) => {
            if (active) setAppearance({ ...defaultAppearance, ...config.appearance });
        }).catch(() => { toast("テーマ設定の読み込みに失敗しました"); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);
    useLayoutEffect(() => { applyFrontendTheme(appearance.frontend_theme); }, [appearance.frontend_theme]);
    async function saveTheme(target: keyof AppearanceConfig, theme: ColorThemeId) {
        setSaving(true);
        try {
            const config = await saveConfigWithToast((data) => {
                data.appearance = { ...defaultAppearance, ...data.appearance, [target]: theme };
            });
            if (config) setAppearance({ ...defaultAppearance, ...config.appearance });
        } finally { setSaving(false); }
    }
    return <ThemeContext.Provider value={{
        theme: getColorTheme(appearance.frontend_theme).mode === "dark" ? "dark" : "light",
        appearance, loading, saving, saveTheme,
    }}>{children}</ThemeContext.Provider>;
}
export function useTheme() {
    const context = useContext(ThemeContext);
    if (!context) throw new Error("useTheme must be used within a ThemeProvider");
    return context;
}

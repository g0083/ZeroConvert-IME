import React, { useRef } from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation } from "react-router";
import "./index.css";
import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { ThemeProvider } from "@/components/theme-provider";
import { AppSidebar, settingsPages } from "@/components/app-sidebar";
import { UpdateProvider } from "@/components/update-provider";
import { FirstLaunchQuickBenchmark } from "@/components/first-launch-quick-benchmark";
import { SettingsSearch } from "@/components/settings-search";
import { consumePendingSettingsTarget, findSettingsRow } from "@/lib/settings-search-target";
import { General } from "@/pages/general";
import { Appearance } from "@/pages/appearance";
import { About } from "@/pages/about";
import { Dictionary } from "@/pages/dictionary";
import { Benchmark } from "@/pages/benchmark";
import { Toaster } from "@/components/ui/sonner";
import { showConfigStartupNoticeOnce, showUpdateInstallResultOnce } from "@/lib/config";

function SettingsLayout() {
    const { pathname } = useLocation();
    const title = settingsPages.find((page) => page.url === pathname)?.title ?? "設定";
    // Remount the scroll region on navigation, not on edits or saves.
    const headerRef = useRef<HTMLHeadingElement>(null);
    React.useEffect(() => { headerRef.current?.focus({ preventScroll: true }); }, [pathname]);
    // Jump to the row chosen in search once the destination page has rendered.
    // Async pages (ZeroCon AI reads config first) mount their rows later, so retry briefly.
    React.useEffect(() => {
        const target = consumePendingSettingsTarget();
        if (!target) return;
        let cancelled = false;
        let timer = 0;
        const deadline = performance.now() + 4000;
        const tryScroll = () => {
            if (cancelled) return;
            const row = findSettingsRow(target.label, target.section);
            if (row) {
                row.scrollIntoView({ block: "center", behavior: "smooth" });
                row.classList.add("settings-row-highlight");
                window.setTimeout(() => row.classList.remove("settings-row-highlight"), 1600);
                return;
            }
            if (performance.now() < deadline) timer = window.setTimeout(tryScroll, 80);
        };
        timer = window.setTimeout(tryScroll, 0);
        return () => { cancelled = true; window.clearTimeout(timer); };
    }, [pathname]);
    return (
        <ThemeProvider>
            <UpdateProvider>
                <SidebarProvider className="h-dvh min-h-0 overflow-hidden" style={{ "--sidebar-width": "14rem" } as React.CSSProperties}>
                    <FirstLaunchQuickBenchmark />
                    <SettingsSearch />
                    <AppSidebar />
                    <main className="flex min-h-0 min-w-0 flex-1 flex-col bg-background">
                        <header className="flex shrink-0 items-center gap-3 border-b px-4 py-4 sm:px-6">
                            <SidebarTrigger aria-label="設定メニューを開閉" />
                            <h1 ref={headerRef} tabIndex={-1} className="text-lg font-semibold outline-none">{title}</h1>
                            <span className="ml-auto hidden items-center gap-1.5 text-xs text-muted-foreground sm:flex" aria-hidden="true">
                                検索
                                <kbd className="rounded border bg-muted px-1.5 py-0.5 font-sans text-[10px]">Ctrl</kbd>
                                <kbd className="rounded border bg-muted px-1.5 py-0.5 font-sans text-[10px]">K</kbd>
                            </span>
                        </header>
                        <div key={pathname} className={`settings-page min-h-0 flex-1 p-4 sm:p-6 ${pathname === "/dictionary" ? "overflow-hidden" : "overflow-auto"}`}>
                            <Outlet />
                        </div>
                    </main>
                    <Toaster position="top-right" closeButton />
                </SidebarProvider>
            </UpdateProvider>
        </ThemeProvider>
    );
}

const router = createBrowserRouter([{
    element: <SettingsLayout />,
    children: [
        { path: "/", element: <General key="input" page="input" /> },
        { path: "/conversion", element: <General key="conversion" page="conversion" /> },
        { path: "/keys", element: <General key="keys" page="keys" /> },
        { path: "/benchmark", element: <Benchmark /> },
        { path: "/dictionary", element: <Dictionary /> },
        { path: "/advanced", element: <General key="advanced" page="advanced" /> },
        { path: "/appearance", element: <Appearance /> },
        { path: "/about", element: <About /> },
        { path: "/zenzai", element: <Navigate to="/conversion" replace /> },
        { path: "/debug", element: <Navigate to="/advanced" replace /> },
        { path: "*", element: <Navigate to="/" replace /> },
    ],
}]);

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode><RouterProvider router={router} /></React.StrictMode>,
);

void showConfigStartupNoticeOnce();
void showUpdateInstallResultOnce();

import { BookText, Cpu, Info, Keyboard, Settings2, SlidersHorizontal, TextCursorInput } from "lucide-react";
import { NavLink, useLocation } from "react-router";
import {
    Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent,
    SidebarGroupLabel, SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar,
} from "@/components/ui/sidebar";
import { useUpdates } from "@/components/update-provider";
import { ZeroConvertLogo } from "@/components/zeroconvert-logo";

export const settingsPages = [
    { title: "入力と文字", url: "/", icon: TextCursorInput },
    { title: "変換と候補", url: "/conversion", icon: SlidersHorizontal },
    { title: "キーとローマ字", url: "/keys", icon: Keyboard },
    { title: "ユーザ辞書", url: "/dictionary", icon: BookText },
    { title: "ベンチマーク", url: "/benchmark", icon: Cpu },
    { title: "詳細設定", url: "/advanced", icon: Settings2 },
    { title: "ZeroConについて", url: "/about", icon: Info },
];

export function AppSidebar() {
    const { pathname } = useLocation();
    const { setOpenMobile } = useSidebar();
    const { result } = useUpdates();
    const menuItem = (item: typeof settingsPages[number]) => (
        <SidebarMenuItem key={item.url}>
            <SidebarMenuButton asChild isActive={pathname === item.url} tooltip={item.title}>
                <NavLink to={item.url} end aria-label={item.url === "/about" && result?.update_available ? `${item.title}（更新があります）` : item.title} onClick={() => setOpenMobile(false)}>
                    <item.icon />
                    <span className="min-w-0 flex-1 truncate group-data-[collapsible=icon]:hidden">{item.title}</span>
                    {item.url === "/about" && result?.update_available && (
                        <span className="ml-auto shrink-0 rounded-full bg-red-600 px-2 py-0.5 text-[10px] font-medium text-white group-data-[collapsible=icon]:absolute group-data-[collapsible=icon]:right-1 group-data-[collapsible=icon]:top-1 group-data-[collapsible=icon]:size-2 group-data-[collapsible=icon]:p-0 group-data-[collapsible=icon]:text-[0px]" aria-label="更新があります">更新</span>
                    )}
                </NavLink>
            </SidebarMenuButton>
        </SidebarMenuItem>
    );
    return (
        <Sidebar collapsible="icon">
            <SidebarContent>
                <div className="flex items-center gap-2.5 px-4 pt-4 pb-2 group-data-[collapsible=icon]:hidden">
                    <ZeroConvertLogo className="size-6 rounded-md shadow-2xs shrink-0" />
                    <div className="leading-tight">
                        <div className="text-sm font-semibold tracking-tight text-sidebar-foreground">ZeroCon IME</div>
                        <div className="text-[10px] text-muted-foreground">ZeroCon AI Engine</div>
                    </div>
                </div>
                <SidebarGroup>
                    <SidebarGroupLabel className="mb-2 h-auto px-2 py-2 text-xs font-semibold text-muted-foreground group-data-[collapsible=icon]:hidden">設定メニュー</SidebarGroupLabel>
                    <SidebarGroupContent>
                        <SidebarMenu>{settingsPages.slice(0, -1).map(menuItem)}</SidebarMenu>
                    </SidebarGroupContent>
                </SidebarGroup>
            </SidebarContent>
            <SidebarFooter>
                <SidebarMenu>{menuItem(settingsPages[settingsPages.length - 1])}</SidebarMenu>
            </SidebarFooter>
        </Sidebar>
    );
}

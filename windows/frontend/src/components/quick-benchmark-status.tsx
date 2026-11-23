import { LoaderCircle } from "lucide-react";
import { useQuickBenchmarkActivity } from "@/lib/quick-benchmark";

export function QuickBenchmarkStatus() {
    const activity = useQuickBenchmarkActivity();
    return (
        <div role="status">
            {activity !== "idle" && <div className="flex shrink-0 items-start gap-2 border-b border-border bg-muted/60 px-4 py-2.5 text-sm sm:px-6">
                <LoaderCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 motion-safe:animate-spin" />
                <span>{activity === "measuring"
                    ? "CPUとVulkanの性能を測定しています。完了まで一時的に動作が重くなる場合があります。"
                    : "推奨バックエンドを適用しています。切り替えが必要な場合はIMEサーバーを再起動します。"}</span>
            </div>}
        </div>
    );
}

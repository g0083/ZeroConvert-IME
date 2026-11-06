import { useEffect } from "react";
import { toast } from "sonner";
import { runQuickBenchmark, quickBenchmarkRecommendationMessage, saveQuickBenchmarkResult } from "@/lib/quick-benchmark";

const FIRST_LAUNCH_KEY = "zerocon-quick-benchmark-onboarding-v1";
// React StrictModeは開発中にeffectを二度実行するため、測定は一度だけ行う。
let firstLaunchBenchmarkStarted = false;

/**
 * 初回起動時にクイックベンチマークを実行し、CPU / Vulkan のうち
 * 実測レイテンシが短い方を推奨バックエンドとして通知する。
 */
export function FirstLaunchQuickBenchmark() {
    useEffect(() => {
        if (firstLaunchBenchmarkStarted || localStorage.getItem(FIRST_LAUNCH_KEY)) return;
        firstLaunchBenchmarkStarted = true;
        const toastId = toast.loading("CPUとVulkanのレイテンシを測定しています…（クイックベンチマーク）");
        runQuickBenchmark().then((result) => {
            saveQuickBenchmarkResult(result);
            toast.success(quickBenchmarkRecommendationMessage(result), { id: toastId, duration: 15000 });
        }).catch((error) => {
            toast.error(`クイックベンチマークを実行できませんでした: ${error}`, { id: toastId });
        }).finally(() => {
            localStorage.setItem(FIRST_LAUNCH_KEY, "1");
        });
    }, []);
    return null;
}

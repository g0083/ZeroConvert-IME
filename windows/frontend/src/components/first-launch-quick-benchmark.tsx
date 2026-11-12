import { useEffect } from "react";
import { toast } from "sonner";
import { runQuickBenchmark, quickBenchmarkRecommendationMessage, saveQuickBenchmarkResult } from "@/lib/quick-benchmark";

const FIRST_LAUNCH_KEY = "zerocon-quick-benchmark-onboarding-v1";
const FIRST_LAUNCH_ATTEMPTS_KEY = "zerocon-quick-benchmark-onboarding-attempts-v1";
// 失敗が続く環境で毎回通知しないよう、再試行は数回で打ち切る。
const MAX_ATTEMPTS = 3;
// React StrictModeは開発中にeffectを二度実行するため、測定は一度だけ行う。
let firstLaunchBenchmarkStarted = false;

const readAttempts = (): number => {
    const stored = Number(localStorage.getItem(FIRST_LAUNCH_ATTEMPTS_KEY));
    return Number.isFinite(stored) && stored > 0 ? stored : 0;
};

/**
 * 初回起動時にクイックベンチマークを実行し、CPU / Vulkan のうち
 * 実測レイテンシが短い方を推奨バックエンドとして通知する。
 * 失敗時は次回起動で再試行し、MAX_ATTEMPTS 回到達で打ち切る。
 */
export function FirstLaunchQuickBenchmark() {
    useEffect(() => {
        if (firstLaunchBenchmarkStarted || localStorage.getItem(FIRST_LAUNCH_KEY)) return;
        const attempts = readAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            localStorage.setItem(FIRST_LAUNCH_KEY, "1");
            return;
        }
        firstLaunchBenchmarkStarted = true;
        const toastId = toast.loading("CPUとVulkanのレイテンシを測定しています…（クイックベンチマーク）");
        runQuickBenchmark().then((result) => {
            saveQuickBenchmarkResult(result);
            localStorage.setItem(FIRST_LAUNCH_KEY, "1");
            toast.success(quickBenchmarkRecommendationMessage(result), { id: toastId, duration: 15000 });
        }).catch((error) => {
            localStorage.setItem(FIRST_LAUNCH_ATTEMPTS_KEY, String(attempts + 1));
            toast.error(`クイックベンチマークを実行できませんでした: ${error}`, { id: toastId });
        });
    }, []);
    return null;
}

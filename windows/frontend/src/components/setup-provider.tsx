import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { toast } from "sonner";
import {
    AlertDialog,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { downloadConversionModel } from "@/lib/conversion-model";
import { downloadEnglishDictionary } from "@/lib/english-dictionary";
import { BACKEND_LABELS, quickBenchmarkSummary, runQuickBenchmark, useQuickBenchmarkActivity, type QuickBenchmarkProgress } from "@/lib/quick-benchmark";
import {
    downloadDictionary,
    getSetupAssetsStatus,
    reloadDictionaryInServer,
    type SetupAssetId,
    type SetupAssetsStatus,
} from "@/lib/setup-assets";

const MANDATORY_MODEL_ID = "standard_v4_2b_iq4_emb8";
const PENDING_SETUP_KEY = "zerocon-setup-benchmark-pending-v1";

function hasPendingSetup() {
    try { return localStorage.getItem(PENDING_SETUP_KEY) === "1"; }
    catch { return false; }
}

function rememberPendingSetup(pending: boolean) {
    try {
        if (pending) localStorage.setItem(PENDING_SETUP_KEY, "1");
        else localStorage.removeItem(PENDING_SETUP_KEY);
    } catch {
        // ストレージに保存できない場合も、この起動中のセットアップは継続する。
    }
}

type ItemState = {
    status: "pending" | "running" | "done" | "error";
    percent: number | null;
    error?: string;
};

const initialItems = (): Record<SetupAssetId, ItemState> => ({
    model: { status: "pending", percent: null },
    dictionary: { status: "pending", percent: null },
    english: { status: "pending", percent: null },
});

const megabytes = (bytes: number | null | undefined) =>
    bytes === null || bytes === undefined ? null : (bytes / (1024 * 1024)).toFixed(1);

const missing = (status: SetupAssetsStatus): SetupAssetId[] => {
    const ids: SetupAssetId[] = [];
    if (!status.model.installed) ids.push("model");
    if (!status.dictionary.installed) ids.push("dictionary");
    if (!status.english.installed) ids.push("english");
    return ids;
};

type Requirement = {
    id: SetupAssetId;
    label: string;
    description: string;
    bytes: number | null;
};

const requirements = (status: SetupAssetsStatus): Requirement[] => [
    {
        id: "model",
        label: "変換モデル（ZeroCon Model Standard v4.2b）",
        description: "文脈に応じた漢字選択に使います。",
        bytes: status.model.size_bytes,
    },
    {
        id: "dictionary",
        label: "日本語辞書",
        description: "azooKey標準辞書と固有名詞などの追加辞書です。",
        bytes: status.dictionary.size_bytes,
    },
    {
        id: "english",
        label: "英語辞書",
        description: "英字入力の単語補完に使います。",
        bytes: status.english.size_bytes,
    },
];

const SetupGatePanel = ({
    status,
    items,
    running,
    benchmarkStarted,
    benchmarkProgress,
    onStart,
    error,
}: {
    status: SetupAssetsStatus;
    items: Record<SetupAssetId, ItemState>;
    running: boolean;
    benchmarkStarted: boolean;
    benchmarkProgress: QuickBenchmarkProgress | null;
    onStart: () => void;
    error: string | null;
}) => {
    const pending = missing(status);
    const activity = useQuickBenchmarkActivity();
    const percent = activity === "applying" ? 100
        : benchmarkProgress ? Math.floor(100 * benchmarkProgress.completed / benchmarkProgress.total) : 0;
    const benchmarkStatus = activity === "measuring"
        ? benchmarkProgress ? `${BACKEND_LABELS[benchmarkProgress.backend]}を測定中` : "測定を準備中"
        : activity === "applying" ? "推奨バックエンドを適用中" : benchmarkStarted ? "セットアップを完了中" : "ダウンロード後に開始";
    return (
        <div className="flex h-dvh overflow-y-auto bg-background p-6">
            <div className="m-auto w-full max-w-xl space-y-5 rounded-lg border p-6">
                <div className="space-y-1">
                    <h1 className="text-lg font-semibold">必要データの準備</h1>
                    <p className="text-sm text-muted-foreground">
                        必要なデータをダウンロードしてから、CPUとVulkanを比較し、推奨バックエンドを自動で適用します。
                    </p>
                </div>
                <ul className="space-y-3">
                    {requirements(status).map((requirement) => {
                        const state = items[requirement.id];
                        const installed = !pending.includes(requirement.id);
                        return (
                            <li key={requirement.id} className="space-y-1">
                                <div className="flex items-center justify-between gap-3 text-sm">
                                    <span className="font-medium">{requirement.label}</span>
                                    <span className="shrink-0 text-xs text-muted-foreground">
                                        {installed
                                            ? "準備済み"
                                            : state.status === "running"
                                                ? "ダウンロード中"
                                                : state.status === "done"
                                                    ? "完了"
                                                    : state.status === "error"
                                                        ? "失敗"
                                                        : "未取得"}
                                    </span>
                                </div>
                                <p className="text-xs text-muted-foreground">
                                    {requirement.description}
                                    {megabytes(requirement.bytes) ? ` 約${megabytes(requirement.bytes)}MB` : ""}
                                </p>
                                {state.percent !== null && state.status === "running" && (
                                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={state.percent} aria-valuemin={0} aria-valuemax={100}>
                                        <div className="h-full bg-primary transition-all" style={{ width: `${state.percent}%` }} aria-hidden />
                                    </div>
                                )}
                                {state.error && <p className="text-xs text-destructive">取得に失敗しました: {state.error}</p>}
                            </li>
                        );
                    })}
                    <li className="space-y-1">
                        <div className="flex flex-wrap items-center justify-between gap-x-3 text-sm">
                            <span className="font-medium">推奨バックエンドの測定</span>
                            <span role="status" className="text-xs text-muted-foreground">{benchmarkStatus}</span>
                        </div>
                        {benchmarkStarted && <>
                            <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted" role="progressbar" aria-label="バックエンド測定" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
                                <div className="h-full bg-primary transition-[width]" style={{ width: `${percent}%` }} aria-hidden />
                            </div>
                            <p className="text-xs text-muted-foreground">{percent}%{activity === "measuring" && "・測定中は一時的に動作が重くなる場合があります。"}</p>
                        </>}
                    </li>
                </ul>
                {error && <p className="text-sm text-destructive">{error}</p>}
                <Button onClick={onStart} disabled={running} className="w-full">
                    {benchmarkStarted ? "セットアップ中です…" : running ? "必要データをダウンロード中です…" : "ダウンロードする"}
                </Button>
            </div>
        </div>
    );
};

/**
 * 変換モデル・日本語辞書・英語辞書を初回起動時にまとめて取得する。
 * ダウンロードが完了したら同じ画面でバックエンドを測定・適用する。
 * 「あとで」を選ぶと確認ダイアログだけ閉じ、以降はダウンロードボタンだけの画面になる。
 */
export function SetupProvider({ children }: { children: ReactNode }) {
    const [status, setStatus] = useState<SetupAssetsStatus | null>(null);
    const [items, setItems] = useState(initialItems);
    const [running, setRunning] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [benchmarkStarted, setBenchmarkStarted] = useState(false);
    const [benchmarkProgress, setBenchmarkProgress] = useState<QuickBenchmarkProgress | null>(null);
    const checked = useRef(false);
    const started = useRef(false);

    const refresh = useCallback(async () => {
        const next = await getSetupAssetsStatus();
        setStatus(next);
        return next;
    }, []);

    const run = useCallback(async () => {
        if (started.current) return;
        started.current = true;
        setRunning(true);
        setError(null);
        setItems(initialItems());
        try {
            const pending = missing(await refresh());
            rememberPendingSetup(true);

            if (pending.includes("model")) {
                setItems((previous) => ({ ...previous, model: { status: "running", percent: 0 } }));
                const model = await downloadConversionModel(MANDATORY_MODEL_ID, (percent) =>
                    setItems((previous) => ({ ...previous, model: { status: "running", percent } })),
                );
                setItems((previous) => ({ ...previous, model: { status: model.installed ? "done" : "error", percent: 100 } }));
            }

            if (pending.includes("dictionary")) {
                setItems((previous) => ({ ...previous, dictionary: { status: "running", percent: 0 } }));
                const dictionary = await downloadDictionary((percent) =>
                    setItems((previous) => ({ ...previous, dictionary: { status: "running", percent } })),
                );
                setItems((previous) => ({ ...previous, dictionary: { status: dictionary.installed ? "done" : "error", percent: 100 } }));
            }

            if (pending.includes("english")) {
                setItems((previous) => ({ ...previous, english: { status: "running", percent: 0 } }));
                const english = await downloadEnglishDictionary((percent) =>
                    setItems((previous) => ({ ...previous, english: { status: "running", percent } })),
                );
                setItems((previous) => ({ ...previous, english: { status: english.installed ? "done" : "error", percent: 100 } }));
            }

            const next = await refresh();
            if (missing(next).length === 0) {
                setDialogOpen(false);
                setBenchmarkStarted(true);
                let readyMessage = "必要なデータを準備しました。";
                let restartedByApply = false;
                try {
                    const { result, outcome } = await runQuickBenchmark(true, setBenchmarkProgress);
                    const backend = result.recommended && BACKEND_LABELS[result.recommended];
                    restartedByApply = outcome === "applied" || outcome === "pending";
                    if (outcome === "applied") readyMessage = `必要なデータを準備し、推奨バックエンド ${backend} を適用しました。`;
                    else if (outcome === "unchanged") readyMessage += ` 推奨バックエンド ${backend} は設定済みです。`;
                    else if (outcome === "measured") readyMessage += ` ${quickBenchmarkSummary(result)}`;
                    else if (outcome === "stale") toast.info("測定中に変換設定が変わりました。「変換と候補」から再測定できます。");
                } catch (failure) {
                    toast.error("バックエンドを測定できませんでした。「変換と候補」から再測定できます。", { description: String(failure) });
                }
                if (!restartedByApply) {
                    // 推奨の適用で再起動しなかった場合は、取得したモデルと辞書を読み直す。
                    await reloadDictionaryInServer().catch(() => {
                        toast.error("準備したデータをIMEに読み込めませんでした。IMEサーバーを再起動してください。");
                    });
                }
                rememberPendingSetup(false);
                toast.success(readyMessage);
            } else {
                setError("一部のデータを取得できませんでした。再試行してください。");
            }
        } catch (failure) {
            setError(String(failure));
            setItems((previous) => {
                const next = { ...previous };
                for (const id of ["model", "dictionary", "english"] as SetupAssetId[]) {
                    if (next[id].status === "running") next[id] = { status: "error", percent: null, error: String(failure) };
                }
                return next;
            });
        } finally {
            started.current = false;
            setRunning(false);
        }
    }, [refresh]);

    const checkSetup = useCallback(async () => {
        try {
            const current = await refresh();
            if (missing(current).length > 0) setDialogOpen(true);
            else if (hasPendingSetup()) void run();
        } catch {
            setError("必要なデータの状態を確認できませんでした");
        }
    }, [refresh, run]);

    useEffect(() => {
        if (checked.current) return;
        checked.current = true;
        void checkSetup();
    }, [checkSetup]);

    if (status === null) {
        return (
            <div className="flex h-dvh flex-col items-center justify-center gap-3 bg-background">
                <p className="text-sm text-muted-foreground">{error ?? "必要なデータを確認しています…"}</p>
                {error && (
                    <Button
                        variant="outline"
                        onClick={() => {
                            setError(null);
                            void checkSetup();
                        }}
                    >
                        再確認する
                    </Button>
                )}
            </div>
        );
    }

    if (running || missing(status).length > 0) {
        return (
            <>
                <SetupGatePanel
                    status={status}
                    items={items}
                    running={running}
                    benchmarkStarted={benchmarkStarted}
                    benchmarkProgress={benchmarkProgress}
                    error={error}
                    onStart={() => {
                        setDialogOpen(false);
                        void run();
                    }}
                />
                <AlertDialog open={dialogOpen}>
                    <AlertDialogContent>
                        <AlertDialogHeader>
                            <AlertDialogTitle>必要データを準備します</AlertDialogTitle>
                            <AlertDialogDescription>
                                必要なデータをダウンロードして検証します。その後CPUとVulkanを測定し、推奨を適用する際はIMEサーバーを再起動します。
                            </AlertDialogDescription>
                        </AlertDialogHeader>
                        <ul className="space-y-1 text-sm">
                            {requirements(status)
                                .filter((requirement) => missing(status).includes(requirement.id))
                                .map((requirement) => (
                                    <li key={requirement.id} className="flex items-center justify-between gap-3">
                                        <span>{requirement.label}</span>
                                        <span className="text-xs text-muted-foreground">
                                            {megabytes(requirement.bytes) ? `約${megabytes(requirement.bytes)}MB` : ""}
                                        </span>
                                    </li>
                                ))}
                        </ul>
                        <AlertDialogFooter>
                            <Button variant="outline" onClick={() => setDialogOpen(false)}>
                                あとで
                            </Button>
                            <Button onClick={() => { setDialogOpen(false); void run(); }}>
                                ダウンロードする
                            </Button>
                        </AlertDialogFooter>
                    </AlertDialogContent>
                </AlertDialog>
            </>
        );
    }

    return <>{children}</>;
}

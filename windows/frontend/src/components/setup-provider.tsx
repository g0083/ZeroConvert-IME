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
import {
    downloadDictionary,
    getSetupAssetsStatus,
    reloadDictionaryInServer,
    type SetupAssetId,
    type SetupAssetsStatus,
} from "@/lib/setup-assets";

const MANDATORY_MODEL_ID = "nano_v4_1_iq4_emb8";

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
        label: "変換モデル（ZeroCon Model Nano v4.1）",
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
    onStart,
    error,
}: {
    status: SetupAssetsStatus;
    items: Record<SetupAssetId, ItemState>;
    running: boolean;
    onStart: () => void;
    error: string | null;
}) => {
    const pending = missing(status);
    return (
        <div className="flex h-dvh items-center justify-center bg-background p-6">
            <div className="w-full max-w-xl space-y-5 rounded-lg border p-6">
                <div className="space-y-1">
                    <h1 className="text-lg font-semibold">必要データの準備</h1>
                    <p className="text-sm text-muted-foreground">
                        変換に必要なデータがこのPCにありません。ダウンロードが終わるまで設定と入力は使えません。
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
                </ul>
                {error && <p className="text-sm text-destructive">{error}</p>}
                <Button onClick={onStart} disabled={running} className="w-full">
                    {running ? "必要データをダウンロード中です…" : "ダウンロードする"}
                </Button>
            </div>
        </div>
    );
};

/**
 * 変換モデル・日本語辞書・英語辞書を初回起動時にまとめて取得する。
 * 同梱物ではなくキャッシュ（`%LOCALAPPDATA%\ZeroConIME\`）に置き、揃っていれば何もしない。
 * 「あとで」を選ぶと確認ダイアログだけ閉じ、以降はダウンロードボタンだけの画面になる。
 */
export function SetupProvider({ children }: { children: ReactNode }) {
    const [status, setStatus] = useState<SetupAssetsStatus | null>(null);
    const [items, setItems] = useState(initialItems);
    const [running, setRunning] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [checked, setChecked] = useState(false);
    const started = useRef(false);

    const refresh = useCallback(async () => {
        const next = await getSetupAssetsStatus();
        setStatus(next);
        return next;
    }, []);

    useEffect(() => {
        if (checked) return;
        setChecked(true);
        refresh()
            .then((next) => {
                // 未取得のものがあれば、初回だけ確認ダイアログを出す。
                if (missing(next).length > 0) setDialogOpen(true);
            })
            .catch(() => {
                // 設定ウィンドウ以外（サーバー起動前など）では判定できない。
                setError("必要なデータの状態を確認できませんでした");
            });
    }, [checked, refresh]);

    const run = useCallback(async () => {
        if (started.current) return;
        started.current = true;
        setRunning(true);
        setError(null);
        setItems(initialItems());
        try {
            const pending = missing(await refresh());

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
                if (dictionary.installed) {
                    // 変換サーバーは起動時に辞書を読み込むため、取得後に読み直させる。
                    await reloadDictionaryInServer().catch(() => {});
                }
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
                toast.success("必要なデータを準備しました");
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

    if (status === null) {
        return (
            <div className="flex h-dvh flex-col items-center justify-center gap-3 bg-background">
                <p className="text-sm text-muted-foreground">{error ?? "必要なデータを確認しています…"}</p>
                {error && (
                    <Button
                        variant="outline"
                        onClick={() => {
                            setError(null);
                            void refresh()
                                .then((next) => {
                                    if (missing(next).length > 0) setDialogOpen(true);
                                })
                                .catch(() => setError("必要なデータの状態を確認できませんでした"));
                        }}
                    >
                        再確認する
                    </Button>
                )}
            </div>
        );
    }

    if (missing(status).length > 0) {
        return (
            <>
                <SetupGatePanel
                    status={status}
                    items={items}
                    running={running}
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
                                変換モデル・日本語辞書・英語辞書はアプリに含まれていません。初回起動時にまとめてダウンロードします（サイズとSHA-256を検証）。
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

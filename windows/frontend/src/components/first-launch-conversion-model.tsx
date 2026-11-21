import { useEffect, useRef, useState } from "react";
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
import {
    cancelConversionModelDownload,
    downloadConversionModel,
    getConversionModelStatus,
    type ConversionModelStatus,
} from "@/lib/conversion-model";

const MANDATORY_MODEL_ID = "nano_v4_1_iq4_emb8";
const DONE_KEY = "zerocon-conversion-model-v1";
let firstLaunchChecked = false;

const megabytes = (bytes: number | null) =>
    bytes === null ? "" : `${(bytes / (1024 * 1024)).toFixed(1)}MB`;

/**
 * 必須の変換モデル（Nano v4.1 IQ4_emb8）が無い状態で起動したら、設定画面から
 * 同意のもとで取得できるようにする。旧版の v4 重みは再利用しない。
 * 自動ダウンロードはしない（通信量・外部送信の同意が必要なため）。
 */
export function FirstLaunchConversionModel() {
    const [status, setStatus] = useState<ConversionModelStatus | null>(null);
    const [open, setOpen] = useState(false);
    const [progress, setProgress] = useState<number | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [finished, setFinished] = useState(false);
    const downloading = useRef(false);

    useEffect(() => {
        if (firstLaunchChecked) return;
        firstLaunchChecked = true;
        getConversionModelStatus(MANDATORY_MODEL_ID)
            .then((current) => {
                if (current.installed) {
                    localStorage.setItem(DONE_KEY, "1");
                    setStatus(current);
                    return;
                }
                setStatus(current);
                setOpen(true);
            })
            .catch(() => {
                // 設定ウィンドウ以外（サーバー起動前など）では何もしない。
            });
    }, []);

    const start = async () => {
        if (downloading.current) return;
        downloading.current = true;
        setError(null);
        setProgress(0);
        try {
            const next = await downloadConversionModel(MANDATORY_MODEL_ID, setProgress);
            setStatus(next);
            setProgress(100);
            setFinished(true);
            localStorage.setItem(DONE_KEY, "1");
            toast.success("変換モデルを保存しました");
        } catch (failure) {
            setError(String(failure));
            setProgress(null);
        } finally {
            downloading.current = false;
        }
    };

    const cancel = () => {
        void cancelConversionModelDownload();
    };

    return (
        <AlertDialog open={open}>
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>変換モデルを準備します</AlertDialogTitle>
                    <AlertDialogDescription>
                        変換に必須の ZeroCon Model Nano v4.1 IQ4_emb8 がこのPCにありません。
                        {status ? ` サイズは約${megabytes(status.size_bytes)}です。` : ""}
                        バイナリの改変・検証をしながらダウンロードします（サイズとSHA-256を検証）。
                    </AlertDialogDescription>
                </AlertDialogHeader>
                {progress !== null && (
                    <div className="space-y-1">
                        <div className="h-2 w-full overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
                            <div
                                className="h-full bg-primary transition-all"
                                style={{ width: `${progress}%` }}
                                aria-hidden
                            />
                        </div>
                        <p className="text-xs text-muted-foreground">{progress}%</p>
                    </div>
                )}
                {error && <p className="text-sm text-destructive">取得に失敗しました: {error}</p>}
                {finished && status && (
                    <p className="text-sm text-muted-foreground">
                        {status.filename} を保存しました。
                    </p>
                )}
                <AlertDialogFooter>
                    {finished ? (
                        <Button onClick={() => setOpen(false)}>閉じる</Button>
                    ) : (
                        <>
                            {progress !== null && (
                                <Button variant="ghost" onClick={cancel}>中断</Button>
                            )}
                            <Button onClick={start} disabled={progress !== null}>
                                {progress !== null ? "ダウンロード中…" : "ダウンロードする"}
                            </Button>
                            <Button variant="outline" onClick={() => {
                                localStorage.setItem(DONE_KEY, "1");
                                setOpen(false);
                            }}>
                                あとで
                            </Button>
                        </>
                    )}
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
}

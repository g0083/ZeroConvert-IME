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
    downloadEnglishDictionary,
    getEnglishDictionaryStatus,
    type EnglishDictionaryStatus,
} from "@/lib/english-dictionary";

const DONE_KEY = "zerocon-english-dictionary-v1";
// React StrictMode は開発中に effect を二度実行するため、確認は一度だけ行う。
let firstLaunchChecked = false;

const megabytes = (bytes: number) => (bytes / (1024 * 1024)).toFixed(1);

/**
 * 初回起動時に英単語辞書（wordfreq由来・約3万語）の取得を促す。
 * 辞書は同梱せず端末内で抽出・フィルタするため、配布物にCC BY-SAデータを含めない。
 */
export function FirstLaunchEnglishDictionary() {
    const [status, setStatus] = useState<EnglishDictionaryStatus | null>(null);
    const [open, setOpen] = useState(false);
    const [progress, setProgress] = useState<number | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [finished, setFinished] = useState(false);
    const downloading = useRef(false);

    useEffect(() => {
        if (firstLaunchChecked) return;
        firstLaunchChecked = true;
        if (localStorage.getItem(DONE_KEY)) return;
        getEnglishDictionaryStatus()
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
            const next = await downloadEnglishDictionary(setProgress);
            setStatus(next);
            setProgress(100);
            setFinished(true);
            localStorage.setItem(DONE_KEY, "1");
            toast.success(`英語辞書を準備しました（${next.words.toLocaleString()}語）`);
        } catch (failure) {
            setError(String(failure));
            setProgress(null);
        } finally {
            downloading.current = false;
        }
    };

    return (
        <AlertDialog open={open}>
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>英語辞書を準備します</AlertDialogTitle>
                    <AlertDialogDescription>
                        英字モードの単語補完に使う辞書（約3万語）を初回のみダウンロードします。
                        {status ? ` 約${megabytes(status.size_bytes)}MB。` : ""}
                        <br />
                        単語頻度データは wordfreq（CC BY-SA 4.0）から取得し、この端末内で抽出・並べ替えて保存します。
                        配布物にデータは含まれません。
                    </AlertDialogDescription>
                </AlertDialogHeader>
                {progress !== null && (
                    <div className="space-y-1">
                        <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
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
                        {status.words.toLocaleString()}語を保存しました。
                    </p>
                )}
                <AlertDialogFooter>
                    {finished ? (
                        <Button onClick={() => setOpen(false)}>閉じる</Button>
                    ) : (
                        <Button onClick={start} disabled={progress !== null}>
                            {progress !== null ? "ダウンロード中…" : "ダウンロードする"}
                        </Button>
                    )}
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
}

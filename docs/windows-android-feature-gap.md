# Windows / Android 変換・補正・先読みの機能差

> 復旧後の追記（2026-10-23）: この一覧は復旧前の作業ツリーを調べた記録です。その後、Gitの到達不能な退避コミット `a48be81`（同日17:18）から最新Windowsソースを復旧しました。以下の「W新規」のモジュール宣言・RPC・呼出し経路も復旧済みです。「未接続」の記述は復旧前の状態を示し、現在の状態には当てはまりません。復旧前の状態・差分は `.local/recovery/` に保存しています。

調査日: 2026-10-23。対象: `D:\angra\ZeroConvert-IME`、ブランチ `fix/typo-correction-pipeline`、HEAD `140bb74` と未コミット・未追跡のソース。移植前の静的調査であり、実行中のWindows IMEやAndroid APKとソースの一致、精度、実機動作は未検証。今回、実装は変更していない。

## 比較元にある不整合

Windowsについて、次の二つを区別する必要がある。

- **W接続済**: 現在のエントリーポイントから呼ばれるソース。`server/main.rs` → `append_ai_assist_candidates` → `ai_assist/mod.rs`。補正優先、旧モデル先読み、Tabで全文確定の経路。
- **W新規**: `ai_assist/corrector.rs`、`prediction.rs`、`reading_check.rs`、`client/.../composition/typo.rs` と、`docs/typo-correction.md`・`docs/predictive-assist.md` にある新方式。現状では上記Rustモジュール宣言・呼び出しや `GetAssistCandidates` のprotobuf契約がなく、IMEへの接続を確認できない。文書に書かれたサーバー分離・起動監視等も、現行launcherは旧方式のまま。

W新規のファイルが存在することを、現在のWindowsソースで動作することと同一視しない。配布済みWindows版に最新機能が入っている場合、そのビルド元との差分確認が移植の前提になる。`docs/architecture.md` には旧方式の説明も残っている。

Androidにも未コミット・未追跡の追加実装があるため、ファイルの存在だけで移植済みとは判定していない。

## 1. かな漢字変換・入力状態

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| C01 | 変換エンジン | W接続済: SwiftのAzooKeyKanaKanjiConverter / Zenzai | Kotlinの候補統合＋独自C++ LOUDS / llama.cpp。Windowsエンジンそのものは共有していない |
| C02 | ニューラル候補と辞書候補の統合 | Zenzaiのrich candidates＋通常N-bestを統合、重複を除去 | モデル出力1件を優先候補として追加し、その後に辞書の完全一致・前方一致等を追加。候補探索・順位決定が異なる |
| C03 | 辞書による複合語変換 | Swiftの変換器 | Androidにも品詞接続コスト `cb` と意味接続 `mm` を使うN-best Viterbiあり。ただし独自実装で、複合語探索は読み32文字以内という条件あり。単語辞書だけの実装ではない |
| C04 | 通常変換に組み込むキーボードタイポ補正 | Swiftの `experimentalKeyboardTypoCorrection`、補正メタデータ、生成済みタイポ辞書を使用 | 対応する変換ラティス内の補正は確認できない。後段の `TypoCorrectionEngine` は別機能 |
| C05 | 補正候補を先頭へ昇格する判定 | Swiftで単一補正・元の範囲・元読み全体の語彙代替の有無を確認して昇格 | 補正メタデータ・範囲・語彙代替を用いる同等の順位判定がない |
| C06 | カーソル前・文節単位の候補 | 専用のcursor-prefix / first-clause / exact-clause候補生成、文節境界調整 | カーソル前の読みを変換し、選択後に後半を残す処理はある。同等の文節候補選択・境界伸縮経路は確認できない |
| C07 | 確定文字列の再変換 | `StartReconversion`、逆引き辞書から読みを復元 | 選択文字列から読みへ戻す同等の経路は確認できない |
| C08 | 変換学習 | Swiftの変換学習＋選択表記override、候補ID・確定種別・保護された候補キャッシュ | 明示候補選択で「読み→表記」を最大500件保存。Swiftの文脈・形態素学習と同等ではない |
| C09 | 学習モード | 入出力学習／読取のみ／無効の切替、リセット | 学習消去はあるが、同等の3モードを変換経路で切り替える処理は確認できない |
| C10 | ユーザー辞書・プロファイルの反映 | Swift変換器への設定・ユーザー辞書、Zenzaiのprofile | 完全一致のユーザー語を優先追加し、読み中の一致を最大2件・短いprofileとしてモデルへ渡す。profileは25文字、合成は40文字に制限 |
| C11 | モデル出力の扱い | Swiftの候補構造・読み対応数を通して統合 | JNIは40生成トークン・出力文字数を読み長までに制限。Kotlinでも読み長で切り、連続同一文字を正規表現で圧縮。正しい反復や長い表記も影響を受け得る |
| C12 | 打鍵列・読み・表記・対応数 | RPCの候補ID・対応数、入力ledger、Swiftの正規入力状態 | `ComposingText`に打鍵と読み・カーソル・独立区間を保持する実装はある。候補はtext/reading/cost/isAIのみで、Windows相当のID・補正範囲・対応数契約はない |

## 2. 自動確定

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| A01 | 基本条件 | 全体の読み16文字以上・残り7文字以上・安定5段階 | 同じ基本値を使う実装あり。未移植ではない |
| A02 | 確定部分の最小長 | 読み8文字以上、表記4文字以上 | 読み3文字以上、表記3文字以上。短い範囲を確定できる |
| A03 | 自然な切れ目 | 句読点終端、または漢字/カタカナを含み平仮名終端→次が漢字/カタカナ | 類似判定あり。ただし次が句読点の場合も許可する等、条件が一致していない |
| A04 | 安定履歴の数え方 | 異なるpreviewを5件保持し、打鍵長・読み長のsnapshotと照合 | 同じ読みのsnapshotを置換し、直近5推論snapshotの接頭一致で判定。安定性の意味が違う |
| A05 | ローマ字途中での確定防止 | 未完了子音とraw入力区間の分割可否を別判定 | 末尾が英字なら停止。`prefixComplete`の区間処理はあるが、Windowsと同じ確定前のraw境界検査ではない |
| A06 | 確定後の継続・取消 | 残りの入力状態・文脈・snapshotを再基準化 | 残り読みの再変換と文脈更新あり。さらにBackspaceで直前の自動確定を巻き戻す独自設定あり。補正取消とは別機能として保持が必要 |

## 3. タイポ補正の生成・誤補正防止

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| T01 | ニューラル補正 | W接続済は規則の後に末尾最大8文字のモデル補正。W新規は左文脈＋誤入力から最終表記を生成するcorrector | 補正専用モデルのロード・推論APIがない。補正は辞書と規則のみ |
| T02 | モデルと規則の優先順 | W新規は正常な既知読みを保護、学習完全一致を優先、モデル正常終了を曖昧な規則より優先 | ローマ字復元→学習→小規模語彙の距離→候補生成→LOUDS検索。モデル判断を反映する段階がない |
| T03 | 「変更なし」の尊重 | W新規はモデルが変更不要とした結果を規則で覆さない | モデルによる変更不要判定がなく、正常入力でも近い別語彙への提案を作り得る |
| T04 | 全文・長文補正 | W新規は128文字まで全文、長文は読みと通常表記の句読点境界を照合して末尾句だけ補正。文書上の要求上限512文字 | 対応する全文モデル・境界照合・変換済み接頭部保護がない |
| T05 | 正常な読みの保護 | W接続済でもprefix距離補正で既知の正常prefixを保護。W新規は既知正常読みを早期除外 | 標準語彙と同じ読みをその語との比較では飛ばすだけで、他語との距離比較全体は止めない |
| T06 | 通常変換と補正の区別 | W新規は逆引き辞書で同じ読みの表記なら補正提案を抑止 | 読み復元はかなだけ対応する `ReadingCheck` があるが、補正経路から呼ばれていない。表記の文字列比較だけでは同音変換を区別できない |
| T07 | 語彙データ | W接続済は標準語彙・固定タイポ表・固有名詞・埋込みTSV・ユーザー追加語彙 | 標準語彙は約35件、生成読みを実LOUDS辞書でも確認する。Windowsのタイポ表・TSV・追加補正語彙と同じデータではない |
| T08 | 語頭・語中・語末補正 | W接続済は固定表の完全一致／最長prefix／語中境界、prefix距離、学習suffixを区別 | 全文距離・生成候補が中心。同等の最長prefix・語中境界・学習suffix経路がない |
| T09 | 候補の選び方 | W接続済はQWERTY近接性・語長等で優先 | 距離1、長さ6以上なら距離2の最初の標準語彙を返す。その後は生成読みを辞書順に探し最初のhitを返す。文脈に基づく選択はない |
| T10 | QWERTY一般補正 | Windowsはかなのローマ字対応を含む近接判定・語彙索引を使う | 近接文字置換はあるがかな→打鍵の比較が同等ではない。`TypoVariant` は標準語彙の簡易実装で、補正本体から未使用 |
| T11 | 混在ローマ字の復元 | Windowsは実際のローマ字→かな復元を使う。W新規は一意な近接復元を自動適用根拠にも使用 | `RomajiDecoder` はあるが内部の `romajiToHiragana` がカタカナ→平仮名変換関数を呼んでおり英字をかなに変換しない。`kinnnichiwa` の個別処理以外を同等実装とは判定できない |
| T12 | 原打鍵の利用 | W接続済は読みとraw打鍵の両方を補正対象にできる | サービスから `rawInput` と `hiragana` に同じ変換対象文字列を渡し、Managerも平仮名側を優先。原打鍵情報が補正へ届かない |
| T13 | フリック誤入力 | Windows補正はQWERTYが中心 | Androidには同一キーの方向違い・隣接キーのフリック補正候補生成あり。Android固有の機能として維持が必要 |
| T14 | 自動補正の根拠 | W新規は一意な近接キー復元、または同じ補正の明示採用・確定3回以上に限定 | `shouldAutoApply` は存在するが呼ばれておらず、自動適用はつながっていない |
| T15 | 補正出力の完結性 | W新規は入力長に応じ32〜384トークン、450msのHTTP呼出し、打切り・制御文字・空出力を除外し32件cache | 補正モデルがないため、終了理由・トークン予算・補正モデルcache等の契約もない |

## 4. 補正の適用・取消・学習・表示

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| U01 | 補正採用と確定 | W接続済のTabは全文確定。W新規は補正を未確定のままdirect入力へ絶対置換 | タップで `setComposingText` を呼ぶため画面上は未確定だが、内部 `ComposingText` を補正表記に置換しない |
| U02 | 補正後の追加入力・編集 | W新規はconverterと入力ledgerを同期し、補正後の状態から編集できる | 内部状態が原文のままなので次の入力・再変換・カーソル操作で原文に基づく表示へ戻り得る |
| U03 | Enterでの確定 | W新規は表示している補正済み本文を確定し、未採用提案は採用しない | `confirmAsync` は `predictedCandidate` または原文の再推論結果を確定。タップ補正の表示と確定対象が一致しない経路がある |
| U04 | 補正直後の取消 | W新規はEscで元のcomposition・打鍵ledgerを復元 | Backspaceで保存文字列の表示に戻す。保存値は元の読みで、変換済み表記・入力状態一式のsnapshotではない。通常削除操作とも競合する |
| U05 | 取消後の再提案 | W新規は同じ入力状態で取り消した補正を再表示しない | 候補をnullにするだけで、拒否した入力状態・表記を記憶する仕組みがない |
| U06 | 補正学習の確定時期 | W接続済は予約候補の確定通知で記録。W新規文書は明示採用後の確定成功を条件とし、自動適用・取消等を除外 | タップ直後に学習を記録。未確定・その後の取消も採用回数に含まれる |
| U07 | 学習記録の内容 | Windowsはtoken reading/surface、件数、更新時刻、完全一致とsuffix、最大500件 | Androidも最大500件だが読み全文→表記・件数の簡易記録。同じ読みに別表記を記録しても既存件数に加算するため「同じ補正3回」の根拠にそのまま使えない |
| U08 | 通常候補一覧への補正追加 | W接続済はTab用予約候補を通常表示から除外。W新規文書では2番目に「補正」として表示し通常候補のID・相対順を保持 | 補正は単独バッジ。`candidatesList` へ補正候補を追加していない |
| U09 | 表示情報 | W新規文書は変更前→変更後、変更箇所強調、長文共通部省略、補正／確定／取消の操作表示 | Androidは読みバナー＋💡/🔮の単独ラベル。差分表示・適用済み状態・取消案内はUIに渡していない |
| U10 | 補正と先読みの表示共存 | W新規は適用済み補正の取消案内と先読みを共存できる | `AiAssistResult?` 1件だけの状態。適用済み補正と新しい先読みを独立して管理できない |

## 5. 先読み

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| P01 | 生成元 | W接続済は共起表→定型句→確定履歴→cache→ZeroCon/LFM。W新規はZeroConだけ | 固定の定型句表のみ。モデル推論や確定履歴を使った先読みはない |
| P02 | 先読みモデル設定の実効性 | W接続済は設定でZeroCon/LFM経路が変わる。W新規文書では選択を廃止 | `modelType` を受け取るが処理で使用しない。設定UIのモデル選択が生成方式を変えない |
| P03 | 読みの続き生成 | W新規は左文脈＋カタカナ読みから短い読みの続きを24トークン・250msで生成 | 対応するJNIの生成APIがない。`nativeConvert` は末尾に表記生成用EE01を付ける通常変換専用 |
| P04 | 延長読みの表記化 | W新規は延長読み全体を再変換してから現在表記に対する追加部分を取得 | 定型句の完成表記を返すだけ。2段階のZeroCon先読みはない |
| P05 | 現在の表記を保護 | W新規は生成全文が現在の表記で始まる場合だけ採用 | 定型句のprefixに途中入力が一致すると完成文へ置換できる。現在表記を不変の接頭部とする一律検査がない |
| P06 | 妥当性・終了判定 | W新規は未完断片・制御文字・記号だけ・反復・元表記変更を除外。先に完成した短い句は利用 | 文字数2〜48と英数字/文字の有無の簡易検査。句の完結性・終了理由・反復等を検査しない |
| P07 | 文脈のない参照語の抑止 | W新規は「この記事」「上記」等が文脈にない追加を除外。意味適合全般の保証ではない | 同等の検査がない |
| P08 | 補正後の先読み | W新規は補正後に再要求し、漢字direct入力なら逆引きして読みを復元 | 補正採用後に先読みを再要求しない。漢字からの読み復元も未実装 |
| P09 | cacheと不通時 | W新規はモデル接続先・左文脈・読み・表記で32件、設定再読込で消去。モデル不通なら表示しない | 文脈・読み・表記で32件はあるが、モデル識別を含まず設定変更時のclear呼出しも確認できない。現在は定型句なのでモデル接続の概念自体がない |
| P10 | 先読み採用 | W新規は現在表記＋追加部分を全文確定 | Androidも完成全文をタップで確定する点は共通。ただし `commitText` 共通経路ではなく直接InputConnectionを呼ぶ |

## 6. 補正と先読みの調停・非同期処理

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| X01 | 補正と先読みの優先度 | W接続済は補正があれば先読みを生成しない。W新規は根拠値で選択し同値では先読み | Managerが補正を発見すると即return。補正優先の旧方式 |
| X02 | 根拠値 | W新規は自動適用補正100／学習補正70／検査済み先読み60／弱い補正30 | `AiAssistResult` に根拠・自動適用可否・元読み・追加部分等の項目がない |
| X03 | 独立した生成 | W新規は補正と先読みを別RPC・別生成枠で実行し、それぞれ同時1件 | 単一 `aiAssistJob` 内で補正の後に先読み。独立完了・独立取消・独立推論枠がない |
| X04 | 提示候補の安定性 | W新規は表示したTab対象を次の編集まで固定し、遅い弱補正で先読みを上書きしない | 単一結果を置換。到着順・表示済み採用対象を固定する明示的な状態がない |
| X05 | 入力の停止待ち | W新規文書は通常変換後に60ms停止してassist。W接続済は変換応答内で同期assist | Androidは変換前に50ms debounceし、通常変換完了後にassist開始。assist専用の60ms停止待ちはない |
| X06 | 古い結果の破棄 | W新規は要求世代・入力・候補・文脈・設定・接続・COM context/compositionを照合 | Job取消と読みの文字列一致が中心。同じ読みの削除再入力、文脈・設定・エディタ変更を識別する世代snapshotがない |
| X07 | 通常変換の結果照合 | Windowsはクライアントとサーバーの正規入力・対応数・操作順を照合 | Androidの通常推論完了時、候補一覧更新は主にcompositionが空かどうかで判定。一部inline・自動確定判定より条件が弱い |
| X08 | 推論資源と取消 | W新規は補正モデルと先読みモデルの独立用途・実行枠。現行Windows起動は旧方式 | Androidは単一モデル/llama contextをmutexで保護。辞書mutexは分離済み。abortはモデル・辞書共通の取消入口なので、新しいモデル仕事を足す際は用途別制御が必要 |
| X09 | 文脈の保持 | W新規は先読み末尾256文字、corrector末尾512文字 | サービスの左文脈は最大40文字。入力セッション開始時に取得し、自動・部分確定時に追記。最新方式と文脈量が異なる |
| X10 | 処理量の制限 | W新規文書は要求・時間・HTTP受信量・候補・cache上限を設定 | Androidにもnative生成40トークン・辞書件数・cache制限はあるが、補正候補生成は入力長に応じ多数の読みを作り、各々辞書を検索。補正全体の入力長・時間・候補数上限は同等ではない |

## 7. モデル・設定・周辺機能・検証

| ID | 機能・観点 | Windows | Androidの現状・差分 |
|---|---|---|---|
| M01 | モデル用途の分離 | W新規文書は通常変換・corrector・ZeroCon先読みを区別しcorrectorへの先読みfallbackを禁止 | モデル管理は通常変換モデルの1選択。correctorの別重み・別ロード・別context・用途別切替の管理がない |
| M02 | 重みの一致 | Windowsは実行フォルダ `zenz.gguf` と補正専用重みを使用 | AndroidはNano Q4、Nano Lite Q3、Standard Q4と取込GGUF。名前が似ていても同一重み・学習内容・精度とは判断できない |
| M03 | 推論backend | WindowsはCPU/Vulkan/CUDA等の選択・外部サーバー管理 | Androidの現行JNIはCPU4スレッド、context256トークン。Windowsの時間予算・文脈長・並列方式の達成可否は端末評価が必要 |
| M04 | 設定項目の分離 | Swift補正／Rust assist／変換モデル／profile／backend等が別設定 | Androidはassist一括ON/OFFと実効性のない旧先読みモデル選択が中心。通常変換のSwift補正に対応する設定やcorrector設定はない |
| M05 | モデル切替とcache | W新規文書は所有する先読みprocess停止を確認して切替、再起動・cache無効化を管理 | Androidは選択した通常モデルをnativeLoadModelで切替。複数用途の推論停止・要求世代更新・assist cache無効化の連携がない |
| M06 | 日付・時刻・計算マクロ | W接続済は通常候補へ接続済み。今日/明日等・年号・複数書式・式パーサー | Androidに今日/今/二項計算の簡易 `DynamicMacro` はあるが候補生成から未使用。機能範囲もWindowsより狭い |
| M07 | Android固有機能の保持 | WindowsのTSF・物理キー操作に合わせた設計 | フリック・濁点操作・ソフトキーのタップ採用・自動確定巻戻し等は移植で維持。Tab/Escの動作意図をAndroidの操作へ対応させる必要がある |
| M08 | 自動テスト | W接続済に入力・文節・復元等の回帰テストあり。W新規ファイルにも補正/先読み/取消等のテストはあるが未接続では実行対象にならない | Androidはかな入力・削除・カーソル・自動確定・規則補正・定型句・補正優先の単体テスト。最新モデル先読み・corrector・取消後抑止・世代照合・確定後学習を検証する同等テストがない |
| M09 | 画面内ベンチマーク | W新規文書では先読みを実モデルの共通生成・検査経路へ統一。補正簡易評価は実IMEと区別 | Android画面は35問の補正・30問の先読み等を持つが、現行の規則・定型句の評価である。最新モデル経路や実IMEの編集成功を示さない |

## 移植で先に解決する順序

1. Windowsの最新ビルド元を確定する。未接続ファイルを含む設計と、動いているWindows版の差を埋める。
2. Androidの原打鍵・読み・現在表記・候補・文脈・要求世代をsnapshot化する。補正適用時は内部compositionと表示を同時に更新し、復元と確定後学習を整える。
3. correctorの用途別モデル管理・推論APIと、ZeroConの「読み継続→延長読みの表記化」を追加する。
4. 出力検査・根拠値・独立生成・表示の安定性・補正後の先読みを接続する。定型句へのfallbackは最新方式の仕様と分ける。
5. Swift変換器固有の辞書補正・候補統合・文節・学習を、Androidの独自変換器へどこまで再現するか整理し、自動確定条件を合わせる。
6. 正常入力の誤補正、タイポの取りこぼし、先読み妥当性・出現率、打鍵削減、遅延、メモリを同じモデル条件で比較し、実機で入力→補正→追加入力→削除→カーソル→確定/取消を検証する。

## 根拠ファイル

- Windows接続経路: [server/main.rs](../windows/crates/server/src/main.rs)、[ai_assist/mod.rs](../windows/crates/server/src/ai_assist/mod.rs)、[composition.rs](../windows/crates/client/src/engine/composition.rs)、[service.proto](../windows/crates/shared/service.proto)、[launcher](../windows/crates/launcher/src/main.rs)。
- Windows通常変換: [azookey_server.swift](../windows/server-swift/Sources/azookey-server/azookey_server.swift)、[typo_variant.rs](../windows/crates/server/src/ai_assist/typo_variant.rs)、[learning.rs](../windows/crates/server/src/ai_assist/learning.rs)、[dynamic_macro.rs](../windows/crates/server/src/ai_assist/dynamic_macro.rs)。
- Windows新方式: [corrector.rs](../windows/crates/server/src/ai_assist/corrector.rs)、[prediction.rs](../windows/crates/server/src/ai_assist/prediction.rs)、[reading_check.rs](../windows/crates/server/src/ai_assist/reading_check.rs)、[typo.rs](../windows/crates/client/src/engine/composition/typo.rs)、[補正文書](typo-correction.md)、[先読み文書](predictive-assist.md)。文書とファイルだけで接続済みとは判定していない。
- Android入力連携: [サービス](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt)、[変換](../android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt)、[入力状態](../android/app/src/main/java/com/zeroconvert/ime/azookey/input/ComposingText.kt)、[候補UI](../android/app/src/main/java/com/zeroconvert/ime/ComposeKeyboardView.kt)、[設定](../android/app/src/main/java/com/zeroconvert/ime/MainActivity.kt)。
- Android補正・先読み: [Manager](../android/app/src/main/java/com/zeroconvert/ime/assist/AiAssistManager.kt)、[補正](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoCorrectionEngine.kt)、[先読み](../android/app/src/main/java/com/zeroconvert/ime/assist/PredictiveAssistEngine.kt)、[ローマ字復元](../android/app/src/main/java/com/zeroconvert/ime/assist/RomajiDecoder.kt)、[補正学習](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoLearning.kt)、[読み復元](../android/app/src/main/java/com/zeroconvert/ime/assist/ReadingCheck.kt)、[語彙変種](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoVariant.kt)、[マクロ](../android/app/src/main/java/com/zeroconvert/ime/assist/DynamicMacro.kt)。
- Android native・モデル: [ZenzNative.kt](../android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt)、[zenz_jni.cpp](../android/app/src/main/cpp/zenz_jni.cpp)、[azookey_louds.cpp](../android/app/src/main/cpp/azookey_louds.cpp)、[ModelManager.kt](../android/app/src/main/java/com/zeroconvert/ime/model/ModelManager.kt)。
- Android回帰テスト: [AiAssistTest](../android/app/src/test/java/com/zeroconvert/ime/AiAssistTest.kt)、[AutoCommitAndPipelineTest](../android/app/src/test/java/com/zeroconvert/ime/AutoCommitAndPipelineTest.kt)、[ComposingTextTest](../android/app/src/test/java/com/zeroconvert/ime/ComposingTextTest.kt)。

検証: 対象ソースと呼出し箇所、設定、テストを静的照合。ビルド・自動テスト・実IME操作は未実施（今回は機能差の調査と文書作成のみ）。

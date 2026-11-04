# Windows / Android 変換・補正・先読みの機能差

調査日: 2026-10-23。移植前の実装比較。

移植開始後の変更・テスト・未実装項目は [Android移植の進捗](android-windows-port-progress.md) を参照。この表は移植前の比較基準として残す。

以前の同名レポートは復旧前のWindowsを「接続済みの旧経路」と「未接続の新規ファイル」に分けていた。今回は復旧後のHEADを読み直し、新しいモジュール・RPC・クライアント・launcherの接続を確認して更新した。以前の版はGit履歴に残る。

## 比較の範囲と読み方

- 対象は、かな漢字変換、ライブ変換・自動確定、タイポ補正、先読み、およびそれらの採用・学習・設定・非同期制御・検証。
- 比較元は現在の作業ツリー。HEADは `a0bd2e5`、ブランチは `feature/windows-color-themes`。Androidには既存の未コミット変更・新規補助ファイルがあり、それらも含めた。上流GitHubや過去APKとの比較ではない。
- 「あり」はソースに処理があるという意味。実機での動作・品質・実行バイナリとの一致は今回未検証。「未接続」は補助関数が存在しても製品入力経路に呼び出しがない状態。
- Windows固有のTSF/RPC/プロセス構成はAndroidへそのままコピーせず、同等の振る舞いをInputConnection/JNIへ実装する。
- キーボード外観、クリップボード、英語補完、配布全般は範囲外。Android独自のフリック入力は移植時の保持対象として記載する。

## 主要な結論

1. Windowsの通常変換はSwift変換器のZenzai・通常N-best統合。Androidは独自LOUDS/Viterbiと、別途生成したAI表記を順に並べる方式で、同一エンジンではない。
2. WindowsのAI補正は専用Correctorによる文脈付き推論を主経路とし、ルールはオフライン時の代替・自動適用の根拠に使う。Androidはルール・辞書検索で、補正専用モデルの推論経路がない。
3. Windowsの先読みはZeroConで読みを延長し、全体を再変換してから既存表記を保つ追加部分だけを提示する。Androidは固定定型句検索で、設定のモデル種別は推論に使われない。
4. Windowsは補正と先読みを独立実行して根拠で優先順位を決める。Androidは補正が見つかると先読みを実行しない。
5. Windowsは補正の内部状態・取消・確定後学習・古い応答の棄却まで実装。Androidは表示だけの補正採用と読み文字列中心の照合で、ここも移植対象。

## 根拠ファイル

表中の記号は以下のファイル群を指す。関数名は検索用。

| 記号 | ファイル・主な入口 |
|---|---|
| W変換 | [azookey_server.swift](../windows/server-swift/Sources/azookey-server/azookey_server.swift): `getOptions`, `get_composed_text_impl`, `mergeZenzaiMainResultsWithNormalNBest`, `confidentKeyboardTypoCorrectionForZenzaiMerge`, `get_composed_text_for_cursor_prefix`, `commit_learning_candidates` |
| W補正 | [corrector.rs](../windows/crates/server/src/ai_assist/corrector.rs): `suggest`, `suggest_composition`, `completed_surface`, `can_auto_apply` |
| W規則 | [mod.rs](../windows/crates/server/src/ai_assist/mod.rs): `heuristic_typo_suggestion`, `rule_based_typo_correction`, `correction_for_embedded_typo` |
| W語彙 | [typo_variant.rs](../windows/crates/server/src/ai_assist/typo_variant.rs)、[builtin_vocab.tsv](../windows/crates/server/src/ai_assist/builtin_vocab.tsv)、[romaji_decoder.rs](../windows/crates/server/src/ai_assist/romaji_decoder.rs) |
| W読み | [reading_check.rs](../windows/crates/server/src/ai_assist/reading_check.rs): `has_same_reading`, `reading_for_surface` |
| W予測 | [prediction.rs](../windows/crates/server/src/ai_assist/prediction.rs): `suggest`, `zerocon`, `reading_extension`, `surface_suffix` |
| W統合 | [main.rs](../windows/crates/server/src/main.rs): `append_ai_assist_candidates`, `get_assist_candidates` |
| W入力 | [composition.rs](../windows/crates/client/src/engine/composition.rs)、[typo.rs](../windows/crates/client/src/engine/composition/typo.rs): `begin_typo_assist`, `apply_typo_correction`, `undo_typo_correction`, `matches_snapshot` |
| W運用 | [launcher/main.rs](../windows/crates/launcher/src/main.rs)、[shared/lib.rs](../windows/crates/shared/src/lib.rs)、[model-catalog.json](../windows/deployment/model-catalog.json)、[settings.json](../windows/deployment/settings.json) |
| W学習 | [learning.rs](../windows/crates/server/src/ai_assist/learning.rs): `lookup_learned_typo`, `record_learned_typo` |
| A変換 | [KanaKanjiConverter.kt](../android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt): `requestCandidates`, `buildEffectiveProfile`, `recordSelection` |
| A入力 | [ZeroConvertInputMethodService.kt](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt): `triggerDebouncedConversion`, `adoptAiAssist`, `revertAiAssist`, `confirmAsync`, `clearInput` |
| A補正 | [TypoCorrectionEngine.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoCorrectionEngine.kt)、[RomajiDecoder.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/RomajiDecoder.kt) |
| A補助 | [TypoVariant.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoVariant.kt)、[ReadingCheck.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/ReadingCheck.kt)、[TypoLearning.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoLearning.kt)、[DynamicMacro.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/DynamicMacro.kt) |
| A予測 | [PredictiveAssistEngine.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/PredictiveAssistEngine.kt)、[AiAssistManager.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/AiAssistManager.kt) |
| A推論 | [ZenzNative.kt](../android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt)、[zenz_jni.cpp](../android/app/src/main/cpp/zenz_jni.cpp)、[azookey_louds.cpp](../android/app/src/main/cpp/azookey_louds.cpp) |
| A設定 | [MainActivity.kt](../android/app/src/main/java/com/zeroconvert/ime/MainActivity.kt)、[ModelManager.kt](../android/app/src/main/java/com/zeroconvert/ime/model/ModelManager.kt) |

## かな漢字変換・ライブ変換

| ID | 項目 | Windowsの現行実装 | Androidの現行実装・差分 |
|---|---|---|---|
| C01 | 変換エンジン | SwiftのAzooKeyKanaKanjiConverterとZenzaiを使用。 | KotlinからAI生成と独自C++ LOUDS/Viterbi検索を呼ぶ。辞書形式が近くても変換処理の同等性はない。〔W変換/A変換/A推論〕 |
| C02 | AIと通常N-bestの統合 | Zenzai候補に別変換器の通常N-bestを補完し、表記重複を除外。 | 学習→ユーザー辞書→AI表記→通常辞書の完全一致→絵文字→接頭辞候補→かなの順。AIは基本1表記で、Windowsの統合・代替候補フィルタはない。〔W変換/A変換〕 |
| C03 | 辞書側のキーボード補正 | Swiftの実験的補正設定、生成タイポ辞書、補正メタデータに対応。AIアシストとは別層。 | フリック/QWERTY補正はアシスト側にあり、通常変換ラティスへ同じ補正規則・メタデータを組み込む経路はない。〔W変換/A補正/A推論〕 |
| C04 | 補正候補の上位昇格 | 元範囲、補正数、元入力全体を覆う正規の語彙候補の有無などを検査して昇格。 | 通常候補と補正候補を同様に比較・統合する判定はない。〔W変換/A変換/A予測〕 |
| C05 | カーソル前・文節候補 | カーソル前の先頭文節・全体・厳密文節ごとに候補を生成・統合する。 | カーソル前の読み全体を変換し、カーソル後を保持する仕組みはある。同じ文節候補生成・境界調整機構ではない。〔W変換/A変換/A入力〕 |
| C06 | 再変換時の扱い | 専用入口でZenzaiを無効化して再変換する。 | 調査した日本語入力経路に同等の再変換専用入口はない。〔W変換/A入力〕 |
| C07 | 通常候補の学習 | 候補IDと候補キャッシュを使い変換器に学習を通知。選択表記overrideも保持。 | 完全一致読み→選択表記を最大500件保持し先頭へ出す。形態素候補の学習通知・ID付きバッチ確定ではない。〔W変換/A変換〕 |
| C08 | ユーザー辞書 | Swift変換器へ動的辞書をインポート。 | 完全一致を候補追加し、読み中に含まれる最大2件をAIプロフィールにも付加。主たる辞書ラティスへのインポートとは異なる。〔W変換/A変換〕 |
| C09 | AI出力の切り詰め | Zenzai候補を変換器の候補構造として処理。 | JNIで読みの文字数に達すると生成停止。Kotlinでも読みのUTF-16長まで切り詰め、連続同一文字を一律圧縮。正当な反復や途中生成も影響し得るため移植時に見直す。〔W変換/A変換/A推論〕 |
| C10 | 文脈の取得・維持 | アシスト開始時の文脈版を保持し、応答適用時にも周辺文を再取得・照合。 | 入力開始時などに左文脈を取得し最大40文字でキャッシュ。通常の全文確定後はclearInputで消去。応答時の再取得・文脈版照合はない。〔W入力/A入力〕 |
| C11 | ライブ変換の既定 | 通常のComposing経路でライブ変換・アシストを統合。 | エディター内リアルタイム表記の既定はfalseで、候補・プレビュー生成とは別設定。〔W入力/A入力〕 |
| C12 | 自動確定の最小長 | 全読み16、確定読み8、残り7、表記接頭部4文字、安定5段階。 | 全読み16・残り7・安定5は共通。確定読みの下限は3、表記接頭部の下限も3で異なる。〔W入力/A入力〕 |
| C13 | 補正後の自動確定 | 補正適用時にholdを設定し安定履歴を消去。次の通常編集でも安定性を再構築。 | 補正採用と自動確定履歴を連携させる同等のhold処理はない。〔W入力/A入力〕 |
| C14 | 複合語探索の長さ | Swift変換器の候補探索を使用。 | 独自Viterbiは接続表があり読みが1〜32文字の場合だけ実行。長文を同じ探索条件で処理するとは限らない。〔W変換/A推論〕 |
| C15 | 通常変換の学習モード | 入出力学習・読取のみ・無効を切替可能。 | 学習消去はあるが、同じ3モードの変換経路への接続はない。プロフィールは25文字、辞書ヒントとの合成は40文字に制限される。〔W変換/A変換〕 |
| C16 | 自動確定の自然な境界 | 平仮名終端の後続は漢字/片仮名を要求。句読点終端は別途許可。 | 平仮名終端の後続が句読点でも許可するため、自然な切れ目の条件が一致しない。〔W入力/A入力〕 |
| C17 | 自動確定の安定履歴 | 同じpreviewの連続追加を避けた5件の履歴と、打鍵長/読み長のsnapshotを照合。 | 同じ読みのsnapshotを置換し直近5推論の接頭一致を照合。同じ「5」でも安定性の数え方が異なる。〔W入力/A入力〕 |
| C18 | 自動確定とraw入力境界 | 末尾未完ローマ字に加え、確定位置がローマ字区間を壊さないか検査。 | 末尾英字は除外しprefixCompleteで区間を扱うが、確定前の同じraw境界検査ではない。〔W入力/A入力〕 |

## タイポ補正の生成・誤補正抑止

| ID | 項目 | Windowsの現行実装 | Androidの現行実装・差分 |
|---|---|---|---|
| T01 | 専用モデル補正 | Correctorを文脈付きで呼び、完成した表記を得る。 | 補正エンジンからモデルを呼ばず、規則・語彙・LOUDS検索で候補生成。context引数も補正判定に使っていない。〔W補正/A補正〕 |
| T02 | 推論と規則の優先 | 完全一致の学習→既知正常読みの除外→混在ローマ字復元→モデル→推論失敗時の規則。 | ローマ字復元→学習→小規模語彙の距離判定→生成変種→辞書。モデルの完成応答を優先する処理はない。〔W補正/A補正〕 |
| T03 | モデルが変更なしと判断 | 入力と同じ読みの完成応答なら補正なしとし、曖昧な規則へ落とさない。 | モデルによる変更不要判断がなく、距離が近い別語へ進める。〔W補正/A補正〕 |
| T04 | 既知の正常入力保護 | 標準語彙の正しい読みを補正前に除外。接頭辞検索や変種検索にも正常語保護あり。 | 現在の語を飛ばして他の語との距離を探すため、同等の早期正常判定がない。〔W補正/W規則/W語彙/A補正〕 |
| T05 | 同音・表記だけの変換 | 逆引き辞書で読みが同じならAI補正から除外。 | ReadingCheckはかな表記の平仮名化だけで、漢字の読み復元は未実装。補正経路からも未接続。〔W読み/W補正/A補助〕 |
| T06 | 文全体・長文 | 最大128文字の補正対象を保持。超過時は対応する句読点境界で末尾を取り、変換済み先頭表記を保存。適切な境界がなければ補正しない。 | 同等の長文境界・先頭表記保持がない。全文に対し語彙距離や変種生成を行う。〔W補正/A補正〕 |
| T07 | 完了判定 | EOS/トークン上限・truncated・制御文字・特殊トークン・長さを検査。 | 補正モデル応答の構造自体がない。既存nativeConvertは文字列のみで、途中停止理由を上位へ返せない。〔W補正/A推論〕 |
| T08 | 固定誤入力辞書 | TYPO_TABLEによる完全一致・最長接頭辞一致・語中一致。 | 同じTYPO_TABLEと境界付き適用はない。`kinnnichiwa`の個別特例と語彙・生成規則で対処。〔W規則/A補正〕 |
| T09 | 音韻規則 | 促音・撥音・長音・助詞などを語彙と照合。 | 類似規則は存在するが、適用順・規則表・語彙・選択方法が異なる。フリック規則も加わるため完全互換ではない。〔W規則/A補正〕 |
| T10 | 距離による選択 | 全文距離1でQWERTY近接を優先。接頭辞は近接性・長さを評価して後続を保持。 | 語彙列の最初の距離1を採用し、6文字以上では距離2も採用。変種はソートするが同じ順位付けや接頭辞処理はない。〔W規則/A補正〕 |
| T11 | 補正語彙 | 標準語彙・固有名詞・埋込TSV・ユーザー追加JSONを索引化。 | STANDARD_VOCABの小規模マップ中心。TypoVariantの索引も同じマップで、TSV/ユーザー追加索引はない。〔W語彙/A補助/A補正〕 |
| T12 | QWERTY変種検索 | かなから打鍵近接性を評価し末尾側優先、最大8変種。全文と末尾語句を検索。 | 実行中の補正器は独自の変種一括生成。別のTypoVariantは英字置換だけで製品経路に未接続。〔W語彙/A補助/A補正〕 |
| T13 | ローマ字混在の復元 | 全半角英字・拗音等の正規化、隣接置換、母音挿入、子音削除。復元後の表記をモデルでも確認。 | 関数の呼出しはあるが、romajiToHiraganaが片仮名→平仮名変換を呼び、英字をかなへ戻せない。正解判定は英字を拒否するため一般的な復元は機能しない。`kinnnichiwa`の個別特例でこの問題を隠さない。〔W補正/W語彙/A補正〕 |
| T14 | 自動適用の独立根拠 | 完全一致の学習3回以上、または一意な隣接キー復元と表記一致が必要。末尾未完子音や全英字識別子を自動適用の根拠にしない。 | shouldAutoApplyは3回判定のみで未接続。一意性検査もなく、現在は候補の手動採用のみ。〔W補正/W語彙/A補助/A入力〕 |
| T15 | 補正専用キャッシュ | エンジン・ポート・文脈・入力をキーに32件、設定変更で消去。 | 補正結果の同等キャッシュはない。辞書検索キャッシュ256件は存在するが目的が異なる。〔W補正/W統合/A推論〕 |
| T16 | 補正学習の検索 | 完全一致優先、規則側では3文字以上の末尾語句一致も利用。 | 完全一致のみ。〔W学習/W規則/A補助〕 |
| T17 | 補正学習の保持 | 最大500件、回数・更新時刻を持つストア。 | 最大500件・回数あり。時刻を持たず、LinkedHashMapの先頭から削除する。共通部分はあるが退避順位が異なる。〔W学習/A補助〕 |
| T18 | 探索・入力上限 | モデル対象128文字、変種最大8などの明示的上限。 | 読みの各位置で多数の変種を生成し、かな変種ごとに最大5件の辞書検索。Windowsと同じ探索件数・入力長上限ではない。〔W補正/W語彙/A補正〕 |
| T19 | 同じ補正の回数 | ストアは読みと表記の一致を考慮して採用回数を管理し、自動適用では現在の表記との一致も確認。 | 同じ読みに別の表記を記録しても以前の回数に加算する。「同じ補正3回」の根拠に現在のcountをそのまま使えない。〔W学習/W補正/A補助〕 |

## 先読み生成・妥当性検査

| ID | 項目 | Windowsの現行実装 | Androidの現行実装・差分 |
|---|---|---|---|
| P01 | モデル先読み | 選択中のZeroCon変換モデルを先読み用サーバーで使用。 | 固定定型句のみ。modelTypeは受け取るが処理に使わず、230M/350Mを選んでもモデル推論しない。〔W予測/W運用/A予測/A設定〕 |
| P02 | 二段推論 | まず読みの続き、その後「元の読み＋続き」全体の表記を生成。 | 該当処理なし。現在のJNI APIも通常のかな漢字変換のみ。〔W予測/A予測/A推論〕 |
| P03 | 既存表記の維持 | 再変換結果が表示中の表記で始まることを検証し、その後ろだけを候補化。 | 定型句の完成文全体を返す。完成文のstartsWith照合や独立した追加部分の契約はない。〔W予測/A予測〕 |
| P04 | 過去の固定候補の廃止 | 現行先読みは共起語・定型句・履歴候補へフォールバックしない。モデル不通なら先読みなし。 | 定型句を主経路として使用。Windowsの古い定型句・共起語方式を移植元にしない。〔W予測/A予測〕 |
| P05 | 読みの復元 | 補正後など漢字を含む入力は逆引き辞書から読みを復元して予測。 | 漢字→読みの復元がなく、補正採用後の内部入力も更新していない。〔W予測/W読み/A補助/A入力〕 |
| P06 | 未完ローマ字 | 読み末尾が英字なら先読みしない。 | 予測関数に同じ早期除外はない。〔W予測/A予測〕 |
| P07 | 対象長・文脈長 | 表記2文字以上、読み512文字以下、左文脈256文字。 | 定型句ごとに表記2文字・読み3文字等の条件。文脈は入力サービスの40文字キャッシュに依存。〔W予測/A予測/A入力〕 |
| P08 | 生成途中の断片 | トークン上限で切れた読みは拒否。ただし完成した短い句読点区切りがあればその句だけ利用。表記生成も未完応答を拒否。 | 定型句なのでモデル応答の完了判定はない。移植には生成停止理由のJNI通知が必要。〔W予測/A推論〕 |
| P09 | 追加文字列の検査 | 読み延長はかな・指定句読点のみ。追加表記は2〜48文字、制御文字・特殊トークン・元表記の反復などを検査。 | 完成文全体が2〜48文字か、文字/数字があるかを検査するだけ。追加部分を別検査しない。〔W予測/A予測〕 |
| P10 | 文脈のない参照語 | 「この記事」「その記事」「上記」「前述」を文脈や元表記にないまま追加する候補を拒否。 | 同等の検査なし。〔W予測/A予測〕 |
| P11 | キャッシュと競合 | ポート・文脈・読み・表記がキー、32件、同時生成抑止。 | 文脈・読み・表記で32件、同時実行抑止はある。モデル識別はキーに含まず、モデル切替と消去の製品連携もない。〔W予測/A予測/A設定〕 |
| P12 | かな化の意味 | かな読みを明示的にモデルへ渡す。 | 定型句の漢字表記をtoHiraganaへ渡す経路があるが、この関数は片仮名→平仮名変換だけ。漢字の読み照合にはならない。〔W予測/A予測/A変換〕 |

## 採用・取消・優先順位・非同期処理

| ID | 項目 | Windowsの現行実装 | Androidの現行実装・差分 |
|---|---|---|---|
| I01 | 補正と先読みの実行 | 不変の通常変換スナップショットに対し別要求で独立実行。 | 補正が見つかるとreturnする直列方式。弱い補正でも先読みを隠す。〔W入力/W統合/A予測〕 |
| I02 | アシストの優先 | 自動適用根拠100、学習済み補正70、先読み60、一般補正30。スコアは確率ではない。 | 種類のみで補正を常に優先。relevance/autoApplyフィールドなし。〔W統合/W入力/A予測〕 |
| I03 | 表示採用先の安定 | 一度表示したTab対象を次の編集まで保持。一般補正で先読み待ちの採用キーを占有しない。 | 結果は単一AiAssistResultで差し替え。別候補の到着順・根拠を扱う状態はない。〔W入力/A予測/A入力〕 |
| I04 | 通常候補との共存 | 予約IDでアシストを区別。クライアントは通常候補順・識別を保って補正を挿入し、読み表示の採用先と分離。 | 通常候補と別バナーの単一結果。Candidateにはtext/reading/cost/isAIしかなく、補正・先読み・追加部分のID契約はない。〔W入力/W統合/A推論/A予測〕 |
| I05 | 補正採用の内部状態 | 変換器の正規入力、打鍵台帳、raw入力、候補、表示を一緒に置換。 | setComposingTextで表示のみ変更。ComposingText・predictedCandidate・lastInferredResultは補正後へ切り替わらない。〔W入力/A入力〕 |
| I06 | 採用後の追加入力・確定 | 補正後を新たな入力として続行し、確定までは未確定状態。 | 元の読みで入力・confirmAsyncを続けるため、採用した補正表記と通常の確定経路に不整合が残る。実際の症状は端末再現が必要。〔W入力/A入力〕 |
| I07 | 補正取消 | 元の変換済みプレビュー、入力台帳、候補状態を復元。取り消した補正は同じ編集状態で再提示を抑止。 | Backspaceで取消する仕組みはあるが、保存しているのは元の読み。元の変換表記・候補全体の復元や拒否候補の抑止はない。〔W入力/A入力〕 |
| I08 | 学習するタイミング | 明示採用を保留し、実際にその表記が確定した時だけ学習。自動適用そのものを明示採用に数えない。 | 補正をタップした時点で即学習。後で取消しても採用回数を取り消さない。〔W入力/W学習/A入力/A補助〕 |
| I09 | 古い結果の照合 | 世代・原入力・候補全体・入力先/未確定文字列の同一性・文脈版・モデル/設定を確認。 | Job取消と読み一致が中心。同じ読みになる削除再入力、入力先・文脈・モデル版を含む同等の照合はない。〔W入力/A入力〕 |
| I10 | 通常変換の遅延応答 | 入力台帳・正規状態とスナップショットで管理。 | 通常変換の候補反映は主にisActiveと非空を確認し、インライン表示・自動確定には別条件がある。アシスト以前の通常結果適用にも世代照合をそろえる必要がある。〔W入力/A入力〕 |
| I11 | 設定OFF・機密欄 | アシスト開始時と適用時に設定・入力無効状態・機密InputScopeを検査。 | 設定は要求開始側で判定するが適用側は読み一致のみ。調査したサービスにはパスワード等を分類してアシストを止める同等の判定がない。〔W入力/A入力〕 |
| I12 | 期限・同時実行上限 | 補正HTTP450ms、先読み250ms＋300ms、クライアント待機1600ms。サーバーの先読み要求枠も制限。 | 通常変換前に50msのdebounce、Job取消とnative abortはある。補正探索・将来の二段先読みを含む同じ期限/実行枠はない。数値の直輸入はせず端末で決める。〔W補正/W予測/W入力/W統合/A入力/A推論〕 |
| I13 | 推論資源の分離 | 補正と先読みは別モデルサーバー。通常変換とは要求経路を分離。 | nativeモデルは単一g_ctx・g_mutex。辞書は別mutexで改善済みだが、モデル補正・先読みを追加する資源設計は未実装。〔W運用/A推論〕 |
| I14 | 動的マクロ | 日時・計算等をローカル候補として追加する入口あり。 | DynamicMacroはファイルあり、製品入力経路には未接続。アシスト移植の隣接機能。〔W統合/A補助〕 |
| I15 | 補正表示と取消案内 | UIのruby.rsで変更前→変更後・変更箇所強調・共通部分省略・適用済み/取消案内を表示できる。 | ComposeKeyboardViewは単一ラベルのバナー。適用済み補正状態・変更差分・取消案内を独立して渡すUI契約がない。〔W入力/[ruby.rs](../windows/crates/ui/src/ruby.rs)/A入力〕 |

## 設定・モデル・テスト

| ID | 項目 | Windowsの現行実装 | Androidの現行実装・差分 |
|---|---|---|---|
| S01 | モデルの役割 | 通常変換/先読みのStandard・NanoとCorrectorをカタログで分離。補正18090、先読み18091。 | 選択中の通常変換モデルのみロード。専用Correctorの選択・初期化・切替・終了APIはない。〔W運用/A推論/A設定〕 |
| S02 | 先読み設定の世代 | 旧experimental_predictive_modelを無視し保存しない。選択中のZeroConモデルとbackendに追随。 | rule_based/230m_q4km/350m_q4kmを画面に表示・保存するが推論実装に未接続。設定と説明を更新する必要がある。〔W運用/A設定/A予測〕 |
| S03 | 補正の設定分離 | 辞書側experimental_typo_correctionとAIアシストを分離。現配布初期値は前者false、後者true。 | アシスト全体のON/OFF中心。Swift辞書補正相当の設定はない。〔W運用/A入力/A設定〕 |
| S04 | モデルの識別・移行 | カタログに用途・SHA-256・サイズ・旧ファイル名を持つ。 | 組込モデル名・期待サイズを持つ。SHA-256による同一性契約はなく、同サイズの重み更新を再展開できる保証はない。〔W運用/A設定〕 |
| S05 | 既定モデル | Windows配布初期値はStandard。 | Android既定はNano Q4_K_M。通常変換のモデル差だけでも出力が変わり得る。ファイル名・表示名だけで同じ重みと判断しない。〔W運用/A設定〕 |
| S06 | native推論の契約 | 補正/先読みは生成予算・終了理由付きのHTTP応答を検証。 | JNIは通常変換のString戻り値。n_ctx=256、最大40生成token、読み長停止、同一token反復停止。二段先読み・補正に必要な予算/完了情報をAPIへ追加する必要がある。〔W補正/W予測/A推論〕 |
| S07 | 生成回帰テスト | モデル応答の優先、変更不要、長文、異常応答、二段予測、既存表記維持、モデル不通で固定候補を出さないテストがある。 | AiAssistTestはルール・定型句・補正優先・決定性中心。モデル二段推論や終了理由の同等テストはない。〔W補正/W予測/Aテスト〕 |
| S08 | 入力状態の回帰テスト | typo.rsに補正取消、古い応答、候補ID/順序、表示採用先保持のテスト。compositionやSwiftにも関連テストあり。 | 入力状態・フリック・自動確定のJVMテストあり。補正採用から追加入力・取消・確定・入力先切替までの同等検証は未整備。〔W入力/Aテスト〕 |
| S09 | テスト期待値 | 一般補正が先読みを隠さず、モデル不通時は固定先読みを出さないことを検証。 | 現在は補正の絶対優先と固定先読みの出力をテストしている。移植時は実装と一緒に期待する振る舞いも改訂する必要がある。〔W入力/W予測/Aテスト〕 |
| S10 | 実機の検証経路 | TSF・プロセス・Windows入力アプリでの検証が必要。 | JVMではJNIロード失敗を許容。androidTestは未作成で、JVMテストだけでは辞書/モデル/IME接続を保証しない。端末で別途確認する。〔Android開発手順〕 |
| S11 | 画面内ベンチマーク | 実IMEと評価経路の共通性を個別確認する必要がある。 | BenchmarkRunnerは現在のルール補正・定型句先読みを直接呼ぶ。最新モデル推論へ移植する際は評価側も接続し、実IMEの編集成功とは別に扱う。〔[BenchmarkRunner.kt](../android/app/src/main/java/com/zeroconvert/ime/benchmark/BenchmarkRunner.kt)/A設定〕 |

ここでAテストは [AiAssistTest.kt](../android/app/src/test/java/com/zeroconvert/ime/AiAssistTest.kt)、[AutoCommitAndPipelineTest.kt](../android/app/src/test/java/com/zeroconvert/ime/AutoCommitAndPipelineTest.kt)、[ComposingTextTest.kt](../android/app/src/test/java/com/zeroconvert/ime/ComposingTextTest.kt)、[FlickInputAndModifierTest.kt](../android/app/src/test/java/com/zeroconvert/ime/FlickInputAndModifierTest.kt) を指す。

## Androidで既に存在し、保持する機能

単純に「Windowsから全部新規移植」とは扱わない。

- LOUDS辞書の複数形態素N-best Viterbi、品詞接続・意味接続コスト、接頭辞検索、辞書検索キャッシュと中断結果をキャッシュしない処理。
- 読み・カーソルを持つComposingText、QWERTY/12キーフリック、濁点・半濁点・小文字、途中挿入とカーソル前部分確定。
- 通常候補の選択学習、ユーザー辞書、絵文字候補、プロフィール付き変換。
- 距離・音韻・隣接キー補正、フリックの同一キー方向/隣接キー補正。ローマ字混在復元は入口があるが、T13の実装不良を修正する必要がある。
- 補正学習の保存・読込、補正候補表示と手動採用、Backspace取消の入口。
- ライブ変換、自動確定、任意のBackspace巻戻し、入力Job取消とnative abort、モデル取込・切替。

これらは存在を確認したもので、今回の調査で全経路の品質を検証した意味ではない。

## 移植の実装順案

1. **入力・候補の契約を整備**: 原打鍵/読み/表記/先読み追加部分/候補種類/要求世代/入力先/モデル版を分離。補正採用・取消で内部状態まで整合させ、学習を確定時へ移す。I05〜I11を先に扱う。
2. **補正用JNIとモデル管理**: 専用Corrector、終了理由・予算付きの推論API、キャンセル/実行枠/資源寿命を実装。旧設定・既存モデル・学習データは維持して移行。
3. **補正ロジック**: 正常語・同音判定、モデル優先と変更不要判定、文境界、語彙/規則/学習、自動適用の根拠を移す。フリック補正も保持する。
4. **先読みロジック**: 選択中のZeroConで二段推論、表記接頭部の保持、完成句・不適切な参照語・未完ローマ字の検査、根拠付きの候補競合を実装。旧定型句エンジン設定を実態と合わせる。
5. **通常変換の整合**: SwiftのN-best統合・補正候補昇格・文節処理を、AndroidのViterbi/候補構造に適応。強制的な文字数切詰め・反復圧縮を検証し直す。
6. **回帰・端末評価**: 打鍵/フリック→待機→採用→追加入力→削除→カーソル/選択→確定/取消→入力先/モデル切替を検証。正常入力の誤補正、取りこぼし、先読みの妥当性/出現率、停止から表示までの遅延を同一条件で比較。

この順序は実装提案であり、この調査で移植コードを編集したものではない。

## 検証範囲・残る不確定点

- 現行自前ソース・設定・テスト定義を静的比較し、既存のこのレポートを更新した。他の既存変更、機能コード、設定、モデル重みは変更していない。
- 文書だけの追加のためビルド・テスト・端末更新は未実施。今回、Windows試験IMEへの適用、APK作成、端末実IME、インストーラー、commit/pushも行っていない。
- 読み取り専用のGit確認は `git -c submodule.recurse=false status --short --branch --ignore-submodules=all` を使用。通常のstatusは辞書サブモジュールのGitメタデータへのアクセス拒否で失敗したため、依存内部の変更確認は対象外。
- モデル重みの実SHA-256、端末で選択中のモデル、インストール済みWindows/Androidの版・実行設定は未確認。カタログ上のモデルと実行モデルを同一視していない。
- 辞書サブモジュール・Swift取得依存・llama.cpp内部の全実装比較はしていない。特に通常変換の全出力差・形態素境界差は同一入力の実行比較が必要。
- [architecture.md](../../docs/architecture.md) の共起語優先、定型句/履歴へのフォールバック、旧先読みポート・設定、補正/先読み共用モデルに関する記載は現行Windowsと異なる。移植の根拠には上記の現行ソースを用いる。この調査では既存変更を含む同文書は編集していない。

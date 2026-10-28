# -*- coding: utf-8 -*-
"""Build a clean, balanced, leak-free distillation dataset for ZeroCon Model Nano v3.

All outputs are written to `artifacts/datasets/nano-v3-distill-20261028/` and
validated against `data/dataset_isolation.py` so zero keys or contexts are shared
with any evaluation benchmark (`kana_kanji_llm_testset_5x.jsonl`, `typo_benchmark_300.jsonl`,
`typo_testset.jsonl`, `predictive_testset.jsonl`) and zero source groups are shared
between train and validation splits.
"""

import argparse
import json
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "data"))

from dataset_isolation import (  # noqa: E402
    EVALUATION_FILES,
    POLICY,
    evaluation_keys,
    load_jsonl,
    normalize,
    separated_rows,
    sha256,
    source_keys,
    validate_rows,
)

PUA_RE = re.compile(r"[\uee00-\uf8ff]")
KANJI_RE = re.compile(r"[\u4e00-\u9fff]")
HIRA_RE = re.compile(r"[\u3041-\u3096]")


def hira_to_kata(text: str) -> str:
    return "".join(chr(ord(c) + 96) if 0x3041 <= ord(c) <= 0x3096 else c for c in text)


def make_row(left_context: str, reading_hira_or_kata: str, completion: str, category: str, family: str) -> dict:
    kata = hira_to_kata(reading_hira_or_kata)
    if PUA_RE.search(left_context) or PUA_RE.search(kata) or PUA_RE.search(completion):
        raise ValueError(f"PUA character found in row: {left_context} | {kata} | {completion}")
    if KANJI_RE.search(kata) or HIRA_RE.search(kata):
        raise ValueError(f"Non-katakana reading found: {kata}")
    prompt = f"\uee02{left_context}\uee00{kata}\uee01"
    return {
        "prompt": prompt,
        "completion": completion,
        "left_context": left_context,
        "reading": kata,
        "expected": completion,
        "category": category,
        "family": family,
    }


def collect_curated_families() -> list[dict]:
    """Curated, strictly balanced homophone, structural, and anchor families."""
    spec = [
        # === Family 1: キシャ (貴社 8 / 記者 10 / 汽車 8 / 帰社 6) ===
        ("お見積書の件につきまして、明日", "きしゃにれんらくする", "貴社に連絡する", "homophone", "kisha"),
        ("契約条件の詳細をメールで書く。", "きしゃにそうだんする", "貴社に相談する", "homophone", "kisha"),
        ("共同開発の企画書をメールで送る。", "きしゃにそうふする", "貴社に送付する", "homophone", "kisha"),
        ("新しい業務提携の案をメールに書く。", "きしゃにだしんする", "貴社に打診する", "homophone", "kisha"),
        ("導入スケジュールの案をメールする。", "きしゃにていじする", "貴社に提示する", "homophone", "kisha"),
        ("取引先への返信メールを作成している。", "きしゃにおつたえする", "貴社にお伝えする", "homophone", "kisha"),
        ("先方の担当者へのビジネスメールを書く。", "きしゃときょうぎする", "貴社と協議する", "homophone", "kisha"),
        ("ご依頼いただいた調査結果をメールする。", "きしゃにほうこくする", "貴社に報告する", "homophone", "kisha"),

        ("首相官邸の会見場で新聞社の", "きしゃがしつもんする", "記者が質問する", "homophone", "kisha"),
        ("事件の取材について話している。", "きしゃにたずねる", "記者に尋ねる", "homophone", "kisha"),
        ("報道番組のインタビューについて話す。", "きしゃがげんばをしゅざいする", "記者が現場を取材する", "homophone", "kisha"),
        ("現地特派員の", "きしゃにげんこうをたのむ", "記者に原稿を頼む", "homophone", "kisha"),
        ("スポーツ紙の担当", "きしゃにコメントする", "記者にコメントする", "homophone", "kisha"),
        ("経済部のベテラン", "きしゃがかいせつする", "記者が解説する", "homophone", "kisha"),
        ("記者会見の会場で新聞の", "きしゃにメモをわたす", "記者にメモを渡す", "homophone", "kisha"),
        ("テレビ局の報道", "きしゃがかけつける", "記者が駆けつける", "homophone", "kisha"),
        ("新聞の特集記事を", "きしゃがしっぴつする", "記者が執筆する", "homophone", "kisha"),
        ("事件の背景について新聞の", "きしゃにきく", "記者に聞く", "homophone", "kisha"),

        ("白い煙を吐きながら峠を登る", "きしゃにのってでかける", "汽車に乗って出かける", "homophone", "kisha"),
        ("古い鉄橋を渡る蒸気", "きしゃのしゃしんをとる", "汽車の写真を撮る", "homophone", "kisha"),
        ("山あいの無人駅で始発の", "きしゃをまちつづける", "汽車を待ち続ける", "homophone", "kisha"),
        ("汽笛を鳴らして走るレトロな", "きしゃにゆられる", "汽車に揺られる", "homophone", "kisha"),
        ("石炭を焚いて走る昔の", "きしゃをけんがくする", "汽車を見学する", "homophone", "kisha"),
        ("トンネルを抜けた夜行の", "きしゃがホームにつく", "汽車がホームに着く", "homophone", "kisha"),
        ("高原の路線を走る観光用の", "きしゃにこどもがのる", "汽車に子供が乗る", "homophone", "kisha"),
        ("雪景色の中をゆっくり進む", "きしゃのまどからみる", "汽車の窓から見る", "homophone", "kisha"),

        ("外回りの営業を終えて夕方に", "きしゃしてほうこくする", "帰社して報告する", "homophone", "kisha"),
        ("クライアント先での商談後に", "きしゃするよていです", "帰社する予定です", "homophone", "kisha"),
        ("現場の立ち会いが終わり次第", "きしゃしてしょるいをつくる", "帰社して書類を作る", "homophone", "kisha"),
        ("午後六時までにオフィスへ", "きしゃするようつたえる", "帰社するよう伝える", "homophone", "kisha"),
        ("出張先から東京の本社に", "きしゃしてかいぎにでる", "帰社して会議に出る", "homophone", "kisha"),
        ("本日は直帰せずいったん", "きしゃしてからかえる", "帰社してから帰る", "homophone", "kisha"),

        # === Family 2: コウセイ (校正 8 / 構成 10 / 鋼製 8 / 後世 6 / 公正 4 / 恒星 4) ===
        ("印刷所へ入稿する前に", "げらのこうせいをおこなう", "ゲラの校正を行う", "homophone", "kousei"),
        ("出版社の編集部で原稿の誤植を直す。", "げんこうのこうせいをおこなう", "原稿の校正を行う", "homophone", "kousei"),
        ("誤字や脱字がないか赤ペンで直す。", "もじのこうせいをおこなう", "文字の校正を行う", "homophone", "kousei"),
        ("文章の誤字や表記ゆれを直す。", "ぶんしょうのこうせいをおこなう", "文章の校正を行う", "homophone", "kousei"),
        ("パンフレットの文字の誤りを直す。", "こうせいさぎょうをおこなう", "校正作業を行う", "homophone", "kousei"),
        ("教科書の改訂版について誤字の", "こうせいをいらいする", "校正を依頼する", "homophone", "kousei"),
        ("初校と再校のゲラ刷りを並べて", "こうせいをすませる", "校正を済ませる", "homophone", "kousei"),
        ("社内報の誤字チェックと文章の", "こうせいをたんとうする", "校正を担当する", "homophone", "kousei"),

        ("プレゼン資料の全体的な章立てと", "こうせいをかんがえる", "構成を考える", "homophone", "kousei"),
        ("レポート全体の組み立てを整理する。", "ぶんしょうのこうせいをみなおす", "文章の構成を見直す", "homophone", "kousei"),
        ("全体の章立てや組み立てを直す。", "ぜんたいのこうせいをみなおす", "全体の構成を見直す", "homophone", "kousei"),
        ("アルバムの曲順と全体の", "こうせいをきめる", "構成を決める", "homophone", "kousei"),
        ("ネットワークのサーバー機器の", "こうせいをへんこうする", "構成を変更する", "homophone", "kousei"),
        ("委員会のメンバーと組織の", "こうせいをくみなおす", "構成を組み直す", "homophone", "kousei"),
        ("物語の起承転結とシナリオの", "こうせいをねる", "構成を練る", "homophone", "kousei"),
        ("番組のタイムテーブルとコーナーの", "こうせいをつくる", "構成を作る", "homophone", "kousei"),
        ("システム全体のモジュール", "こうせいをせっけいする", "構成を設計する", "homophone", "kousei"),
        ("世帯ごとの家族の人数や年齢の", "こうせいをしらべる", "構成を調べる", "homophone", "kousei"),

        ("高圧の配管に頑丈な金属を使う。", "こうせいのパイプをつかう", "鋼製のパイプを使う", "homophone", "kousei"),
        ("金属の材質と強度を説明する。", "こうせいのぶざいをつかう", "鋼製の部材を使う", "homophone", "kousei"),
        ("耐震性を高めるために太い", "こうせいのフレームをくむ", "鋼製のフレームを組む", "homophone", "kousei"),
        ("倉庫の入り口に防火用の重い", "こうせいのとびらをつける", "鋼製の扉をつける", "homophone", "kousei"),
        ("工場の架台として錆びにくい", "こうせいのラックをおく", "鋼製のラックを置く", "homophone", "kousei"),
        ("橋桁の補強工事で厚い", "こうせいのプレートをはる", "鋼製のプレートを貼る", "homophone", "kousei"),
        ("化学プラントの屋外に大型の", "こうせいのタンクをすえる", "鋼製のタンクを据える", "homophone", "kousei"),
        ("ボルトでしっかりと金属の", "こうせいのかなぐをとめる", "鋼製の金具を留める", "homophone", "kousei"),

        ("偉大な芸術作品を未来へ引き継ぐ。", "こうせいにつたえる", "後世に伝える", "homophone", "kousei"),
        ("次の世代の人々へ記録を引き継ぐ。", "きろくをこうせいにのこす", "記録を後世に残す", "homophone", "kousei"),
        ("未来の世代のために業績を記録する。", "なをこうせいにのこす", "名を後世に残す", "homophone", "kousei"),
        ("地域の伝統文化を記録して", "こうせいへのこす", "後世へ残す", "homophone", "kousei"),
        ("歴史的な発見として長く", "こうせいまでかたりつぐ", "後世まで語り継ぐ", "homophone", "kousei"),
        ("当時の貴重な建築物を", "こうせいのためにまもる", "後世のために守る", "homophone", "kousei"),

        ("第三者の立場で偏りのない", "こうせいな審査をする".replace("審査", "しんさ"), "公正な審査をする", "homophone", "kousei"),
        ("ルールに従って透明で", "こうせいなとりひきをする", "公正な取引をする", "homophone", "kousei"),
        ("選挙がルール通りに", "こうせいにおこなわれる", "公正に行われる", "homophone", "kousei"),
        ("裁判所で証拠に基づき", "こうせいなはんけつをくだす", "公正な判決を下す", "homophone", "kousei"),

        ("天文台の望遠鏡で遠くの", "こうせいのひかりをみる", "恒星の光を見る", "homophone", "kousei"),
        ("太陽系外にある明るい", "こうせいをかんそくする", "恒星を観測する", "homophone", "kousei"),
        ("夜空に輝く一等星の", "こうせいの온도をはかる".replace("온도", "おんど"), "恒星の温度を測る", "homophone", "kousei"),
        ("銀河系の中心にある古い", "こうせいのじゅみょうをしらべる", "恒星の寿命を調べる", "homophone", "kousei"),

        # === Family 3: セイセイ & セイ〜 アンカー (精製 8 / 生成 8 / 性質・清掃等 10) ===
        ("不純物を取り除いて高純度にする。", "かがくげんりょうをせいせいする", "化学原料を精製する", "homophone", "seisei"),
        ("化学工場で不純物を除去して純度を高める。", "げんりょうのせいせいをおこなう", "原料の精製を行う", "homophone", "seisei"),
        ("工場で純度を高めて", "いやくひんのげんりょうをせいせいする", "医薬品の原料を精製する", "homophone", "seisei"),
        ("製油所の蒸留塔で輸入した", "げんゆをせいせいする", "原油を精製する", "homophone", "seisei"),
        ("サトウキビの絞り汁から白い", "さとうをせいせいする", "砂糖を精製する", "homophone", "seisei"),
        ("実験室の遠心分離機で純粋な", "たんぱくしつをせいせいする", "タンパク質を精製する", "homophone", "seisei"),
        ("鉱石から不純物を分離して", "どうをせいせいする", "銅を精製する", "homophone", "seisei"),
        ("抽出液から有効成分だけを", "せいせいしてとりだす", "精製して取り出す", "homophone", "seisei"),

        ("大規模言語モデルを使って", "ぶんしょうをせいせいする", "文章を生成する", "homophone", "seisei"),
        ("プロンプトを入力してイラストの", "がぞうをせいせいする", "画像を生成する", "homophone", "seisei"),
        ("コンパイラがソースコードから", "バイナリをせいせいする", "バイナリを生成する", "homophone", "seisei"),
        ("乱数ジェネレータで安全な", "かぎをせいせいする", "鍵を生成する", "homophone", "seisei"),
        ("テンプレートから自動的にHTMLの", "ページをせいせいする", "ページを生成する", "homophone", "seisei"),
        ("太陽電池パネルでクリーンな", "でんりょくをせいせいする", "電力を生成する", "homophone", "seisei"),
        ("音声合成エンジンで自然な", "おんせいをせいせいする", "音声を生成する", "homophone", "seisei"),
        ("データベースのログから月次の", "レポートをせいせいする", "レポートを生成する", "homophone", "seisei"),

        # セイ〜 保護アンカー (精への過剰バイアス防止)
        ("実験で新しい化合物の特徴を調べる。", "ぶっしつのせいしつをぶんせきする", "物質の性質を分析する", "homophone", "sei_anchor"),
        ("金属とプラスチックの特性を比較する。", "ざいりょうのせいしつをしらべる", "材料の性質を調べる", "homophone", "sei_anchor"),
        ("公園の落ち葉を拾ってきれいに保つ。", "こうえんのせいそうをおこなう", "公園の清掃を行う", "homophone", "sei_anchor"),
        ("ビルの廊下や階段をきれいに掃除する。", "館内のせいそうをおこなう".replace("館内", "かんない"), "館内の清掃を行う", "homophone", "sei_anchor"),
        ("定期テストの結果を集計する。", "がくせいのせいせきをつける", "学生の成績をつける", "homophone", "sei_anchor"),
        ("工場のラインで自動車の部品を作る。", "ぶひんのせいさんをおこなう", "部品の生産を行う", "homophone", "sei_anchor"),
        ("机の上の書類をファイルにまとめる。", "しょるいのせいりをおこなう", "書類の整理を行う", "homophone", "sei_anchor"),
        ("誤差のない時計で時刻を合わせる。", "せいかくなじこくをはかる", "正確な時刻を測る", "homophone", "sei_anchor"),

        # === Family 4: シリョウ (史料 8 / 資料 10 / 飼料 3 / 試料 3) ===
        ("江戸時代の藩政を記録した古文書を読む。", "こぶんしょのしりょうをよみとく", "古文書の史料を読み解く", "homophone", "shiryou"),
        ("歴史研究の文献や古文書を調べる。", "とうじのしりょうをよみとく", "当時の史料を読み解く", "homophone", "shiryou"),
        ("中世の記録を調べて文献を読む。", "れきしのしりょうをよみとく", "歴史の史料を読み解く", "homophone", "shiryou"),
        ("戦国時代の合戦に関する一次", "しりょうをひもとく", "史料を紐解く", "homophone", "shiryou"),
        ("郷土博物館に保管されている歴史", "しりょうをちょうさする", "史料を調査する", "homophone", "shiryou"),
        ("明治維新の志士が残した日記や", "しりょうをかいどくする", "史料を解読する", "homophone", "shiryou"),
        ("幕末の外交交渉を記録した古い", "しりょうをけんきゅうする", "史料を研究する", "homophone", "shiryou"),
        ("公文書館で近代史の珍しい", "しりょうをえつらんする", "史料を閲覧する", "homophone", "shiryou"),

        ("営業会議で配るプレゼン用の", "しりょうをいんさつする", "資料を印刷する", "homophone", "shiryou"),
        ("新入社員向けの研修に使う説明", "しりょうをさくせいする", "資料を作成する", "homophone", "shiryou"),
        ("企画書の裏付けとなる統計データの", "しりょうをてんぷする", "資料を添付する", "homophone", "shiryou"),
        ("打ち合わせの前にメールで参考", "しりょうをきょうゆうする", "資料を共有する", "homophone", "shiryou"),
        ("取締役会で配布する予算案の", "しりょうをまとめる", "資料をまとめる", "homophone", "shiryou"),
        ("窓口でパンフレットと案内", "しりょうをうけとる", "資料を受け取る", "homophone", "shiryou"),
        ("セミナーの参加者にPDFの", "しりょうをくばる", "資料を配る", "homophone", "shiryou"),
        ("提案書にグラフや比較表の", "しりょうをついかする", "資料を追加する", "homophone", "shiryou"),
        ("", "かいぎのしりょうだけをさきにおくる", "会議の資料だけを先に送る", "homophone", "shiryou"),
        ("", "さんこうしりょうだけを添付する".replace("添付", "てんぷ"), "参考資料だけを添付する", "homophone", "shiryou"),

        ("牧場の乳牛に栄養価の高い", "しりょうをあたえる", "飼料を与える", "homophone", "shiryou"),
        ("養鶏場でトウモロコシを混ぜた配合", "しりょうをやる", "飼料をやる", "homophone", "shiryou"),
        ("家畜の健康を守るために安全な", "しりょうをこうにゅうする", "飼料を購入する", "homophone", "shiryou"),
        ("顕微鏡に観察用の血液の", "しりょうをセットする", "試料をセットする", "homophone", "shiryou"),
        ("水質検査のために河川から採取した", "しりょうをぶんせきする", "試料を分析する", "homophone", "shiryou"),
        ("地質調査でボーリング採取した岩石の", "しりょうをけんさする", "試料を検査する", "homophone", "shiryou"),

        # === Family 5: ホショウ (補償 8 / 保証 8 / 保障 6) ===
        ("工事による休業の損失を埋め合わせる。", "そんがいがくをほしょうする", "損害額を補償する", "homophone", "hoshou"),
        ("交通事故の被害者に対して賠償する。", "じこのそんがいをほしょうする", "事故の損害を補償する", "homophone", "hoshou"),
        ("システム障害で発生した被害を埋め合わせる。", "こきゃくのそんがいをほしょうする", "顧客の損害を補償する", "homophone", "hoshou"),
        ("被害を受けた方に対して損失を埋める。", "そんがいぶんをほしょうする", "損害分を補償する", "homophone", "hoshou"),
        ("公共事業に伴う立ち退きの", "ひようをほしょうする", "費用を補償する", "homophone", "hoshou"),
        ("業務中の労災事故について遺族に", "ほしょうをおこなう", "補償を行う", "homophone", "hoshou"),
        ("欠航によって生じた宿泊費の", "そんしつをほしょうする", "損失を補償する", "homophone", "hoshou"),
        ("公害による漁業被害の", "ひがいがくをほしょうする", "被害額を補償する", "homophone", "hoshou"),

        ("お買い上げから一年間は", "せいひんのひんしつをほしょうする", "製品の品質を保証する", "homophone", "hoshou"),
        ("家電製品の自然故障を無償で修理する", "ほしょうしょをはっこうする", "保証書を発行する", "homophone", "hoshou"),
        ("賃貸契約を結ぶ際に連帯", "ほしょうにんをたてる", "保証人を立てる", "homophone", "hoshou"),
        ("銀行の融資を受けるために信用", "ほしょうきこうをつかう", "保証機構を使う", "homophone", "hoshou"),
        ("メーカーがバッテリーの動作を", "ほしょうするきかん", "保証する期間", "homophone", "hoshou"),
        ("ダイヤモンドの鑑定書で本物だと", "ほしょうされている", "保証されている", "homophone", "hoshou"),
        ("通信速度の最低ラインを契約で", "ほしょうするサービス", "保証するサービス", "homophone", "hoshou"),
        ("初期不良があった場合は返金を", "ほしょういたします", "保証いたします", "homophone", "hoshou"),

        ("憲法によって国民の基本的人権と", "じゆうをほしょうする", "自由を保障する", "homophone", "hoshou"),
        ("高齢者が安心して暮らせる社会", "ほしょうせいどをまもる", "保障制度を守る", "homophone", "hoshou"),
        ("すべての子供に教育を受ける", "けんりをほしょうする", "権利を保障する", "homophone", "hoshou"),
        ("労働者の安全な職場環境と", "こようをほしょうする", "雇用を保障する", "homophone", "hoshou"),
        ("アジア地域の平和と安全", "ほしょうについてかたる", "保障について語る", "homophone", "hoshou"),
        ("最低限度の文化的な生活を", "ほしょうするしくみ", "保障する仕組み", "homophone", "hoshou"),

        # === Family 6: シュウガク (修学 8 / 就学 8) ===
        ("大学のカリキュラムを履修して卒業する。", "だいがくのしゅうがくきかんをおえる", "大学の修学期間を終える", "homophone", "shuugaku"),
        ("学校で学ぶ所定の期間を修了する。", "しょていのしゅうがくきかんをおえる", "所定の修学期間を終える", "homophone", "shuugaku"),
        ("学校で学ぶカリキュラムを修了し、", "よねんかんのしゅうがくきかんをおえる", "4年間の修学期間を終える", "homophone", "shuugaku"),
        ("大学院の博士課程における標準", "しゅうがくねんげんをみる", "修学年限を見る", "homophone", "shuugaku"),
        ("専門学校の二年間の", "しゅうがくきかんをまっとうする", "修学期間を全うする", "homophone", "shuugaku"),
        ("高校生が京都や奈良のお寺へ", "しゅうがくりょこうにいく", "修学旅行に行く", "homophone", "shuugaku"),
        ("学業に専念するための", "しゅうがくしえんきんをもらう", "修学支援金をもらう", "homophone", "shuugaku"),
        ("法科大学院での厳しい", "しゅうがくせいかつをおくる", "修学生活を送る", "homophone", "shuugaku"),

        ("来春から小学校に入学する", "しゅうがくまえのこども", "就学前の子供", "homophone", "shuugaku"),
        ("市役所から小学校の入学に関する", "しゅうがくつうちしょがとどく", "就学通知書が届く", "homophone", "shuugaku"),
        ("六歳になって義務教育の", "しゅうがくねんれいにたっする", "就学年齢に達する", "homophone", "shuugaku"),
        ("秋に小学校で行われる", "しゅうがくぜんけんしんをうける", "就学前検診を受ける", "homophone", "shuugaku"),
        ("途上国の農村部で子供の", "しゅうがくりつをたかめる", "就学率を高める", "homophone", "shuugaku"),
        ("保護者の所得に応じて給食費などの", "しゅうがくえんじょをおこなう", "就学援助を行う", "homophone", "shuugaku"),
        ("幼稚園や保育園に通う", "みしゅうがくじのいるかてい", "未就学児のいる家庭", "homophone", "shuugaku"),
        ("地域の特別支援学級への", "しゅうがくそうだんをおこなう", "就学相談を行う", "homophone", "shuugaku"),

        # === Family 7: サイ〜 (再変換 / 再接続 / 再設定 / 再起動 / 再確認 / 再構築) ===
        ("", "せんたくしたはんいだけをさいへんかんする", "選択した範囲だけを再変換する", "basic", "sai_compound"),
        ("", "かーそるのいちのたんごをさいへんかんする", "カーソルの位置の単語を再変換する", "basic", "sai_compound"),
        ("", "がめんのいちぶだけをさいへんかんする", "画面の一部だけを再変換する", "basic", "sai_compound"),
        ("誤って確定した文字を選択して", "さいへんかんする", "再変換する", "basic", "sai_compound"),
        ("かな漢字変換の候補一覧から", "さいへんかんをおこなう", "再変換を行う", "basic", "sai_compound"),
        ("確定したあとの文章をもう一度", "さいへんかんしてなおす", "再変換して直す", "basic", "sai_compound"),
        ("", "つうしんがとぎれたのでさいせつぞくする", "通信が途切れたので再接続する", "basic", "sai_compound"),
        ("", "ねっとわーくがきれたのでさいせつぞくする", "ネットワークが切れたので再接続する", "basic", "sai_compound"),
        ("Wi-Fiの電波が切れたのでルーターに", "さいせつぞくする", "再接続する", "basic", "sai_compound"),
        ("忘れたログイン用のパスワードを", "さいせっていする", "再設定する", "basic", "sai_compound"),
        ("アップデートを適用したあとPCを", "さいきどうする", "再起動する", "basic", "sai_compound"),
        ("送信ボタンを押す前に宛先を", "さいかくにんする", "再確認する", "basic", "sai_compound"),
        ("検索速度を上げるためインデックスを", "さいこうちくする", "再構築する", "basic", "sai_compound"),

        # === Family 8: ウェブ〜 / クラウド〜 / 基本変換アンカー ===
        ("", "うぇぶさいとをひらいてじょうほうをかくにんする", "ウェブサイトを開いて情報を確認する", "basic", "web_katakana"),
        ("", "うぇぶさいとからもうしこみをおこなう", "ウェブサイトから申し込みを行う", "basic", "web_katakana"),
        ("", "うぇぶさいとのないようをこうしんする", "ウェブサイトの内容を更新する", "basic", "web_katakana"),
        ("会社の公式情報を載せた", "うぇぶさいとをこうかいする", "ウェブサイトを公開する", "basic", "web_katakana"),
        ("広報部がリニューアルした新しい", "うぇぶさいとをみる", "ウェブサイトを見る", "basic", "web_katakana"),
        ("商品のカタログが掲載された", "うぇぶさいとをひらく", "ウェブサイトを開く", "basic", "web_katakana"),
        ("", "くらうどではなくろーかるでじっこうする", "クラウドではなくローカルで実行する", "basic", "web_katakana"),
        ("", "くらうどにばっくあっぷをほぞんする", "クラウドにバックアップを保存する", "basic", "web_katakana"),
        ("", "じょしやぶんせつのくぎりをかえない", "助詞や文節の区切りを変えない", "basic", "grammar_vocab"),
        ("", "ぶんみゃくやごじゅんをそのままにする", "文脈や語順をそのままにする", "basic", "grammar_vocab"),

        # 基本変換・漢字表記アンカー (退行防止)
        ("", "おんせいにゅうりょくのせいどをはかる", "音声入力の精度を測る", "basic", "kanji_anchor"),
        ("", "すいろんそくどとせいどをはかる", "推論速度と精度を測る", "basic", "kanji_anchor"),
        ("", "もっともたかいこうほをえらぶ", "最も高い候補を選ぶ", "basic", "kanji_anchor"),
        ("", "もっともよいけっかをえらぶ", "最も良い結果を選ぶ", "basic", "kanji_anchor"),
        ("", "みかんりのたんごはひらがなのままのこす", "未管理の単語はひらがなのまま残す", "basic", "kanji_anchor"),
        ("", "かなはひらがなのままのこす", "かなはひらがなのまま残す", "basic", "kanji_anchor"),
        ("", "あぷりのめもりしようりょうをしらべる", "アプリのメモリ使用量を調べる", "basic", "kanji_anchor"),
        ("", "さーばーのめもりしようりょうをけいそくする", "サーバーのメモリ使用量を計測する", "basic", "kanji_anchor"),
        ("歯科医院で虫歯の検査を受ける。", "しかいにみてもらう", "歯科医に診てもらう", "homophone", "kanji_anchor"),
        ("病院の外来で症状を相談する。", "いしゃにみてもらう", "医者に診てもらう", "homophone", "kanji_anchor"),
        ("お盆の高速道路の渋滞を予測する。", "じゅうたいをさける", "渋滞を避ける", "homophone", "kanji_anchor"),
        ("人混みの多い時間帯を外して移動する。", "こんざつをさける", "混雑を避ける", "homophone", "kanji_anchor"),
        ("休日に映画館のスクリーンで鑑賞する。", "新作のえいがをみる".replace("新作", "しんさく"), "新作の映画を観る", "homophone", "kanji_anchor"),
        ("劇場の客席で舞台の演劇を楽しむ。", "ぶたいをみる", "舞台を観る", "homophone", "kanji_anchor"),
        ("絵画や賞状を飾るための枠を買う。", "きゅうりょうの額ではなくかざるがくをえらぶ".replace("きゅうりょうの額ではなく", ""), "飾る額を選ぶ", "homophone", "kanji_anchor"),
        ("写真を飾るための木製の枠を探す。", "しゃしんのがくをえらぶ", "写真の額を選ぶ", "homophone", "kanji_anchor"),
        ("火災によって古い木造の家が燃える。", "かじでたてものがしょうしつする", "火事で建物が焼失する", "homophone", "kanji_anchor"),
        ("イベント会場に集まった多くの人を案内する。", "ぐんしゅうをあんないする", "群衆を案内する", "homophone", "kanji_anchor"),

        # 口語・短文漢字変換アンカー (退行防止)
        ("", "おれあしたいく", "俺明日行く", "colloquial", "colloquial_anchor"),
        ("", "かれはあしたくる", "彼は明日来る", "colloquial", "colloquial_anchor"),
        ("", "きのうかれがきた", "昨日彼が来た", "colloquial", "colloquial_anchor"),
        ("", "これたぶんできる", "これ多分できる", "colloquial", "colloquial_anchor"),
        ("", "きょういけるかも", "今日行けるかも", "colloquial", "colloquial_anchor"),
        ("", "しらんけど", "知らんけど", "colloquial", "colloquial_anchor"),
        ("", "あとにふんまって", "あと2分待って", "colloquial", "colloquial_anchor"),
        ("", "あとさんぷんまって", "あと3分待って", "colloquial", "colloquial_anchor"),

        # === Family 9: 数値・百の位 vs 十の位 (100/200/300/400/500 vs 20/30/40/50) ===
        ("", "まっぷのぶんみゃくをにひゃくもじおくってへんかんする", "マップの文脈を200文字送って変換する", "alphanumeric", "numbers"),
        ("", "まえのぶんみゃくをにひゃくもじわたしてすいろんする", "前の文脈を200文字渡して推論する", "alphanumeric", "numbers"),
        ("", "にゅうりょくされたぶんしょうをにひゃくもじわたす", "入力された文章を200文字渡す", "alphanumeric", "numbers"),
        ("アンケートの自由記述欄に", "ひゃくもじいかでかく", "100文字以下で書く", "alphanumeric", "numbers"),
        ("要約文のテキストを", "にひゃくもじにまとめる", "200文字にまとめる", "alphanumeric", "numbers"),
        ("紹介文の原稿をちょうど", "にひゃくもじでさくせいする", "200文字で作成する", "alphanumeric", "numbers"),
        ("SNSの投稿欄に", "にひゃくもじまでにゅうりょくする", "200文字まで入力する", "alphanumeric", "numbers"),
        ("プロフィール欄の説明を", "にひゃくもじいないにおさめる", "200文字以内に収める", "alphanumeric", "numbers"),
        ("あらすじの文章を", "さんびゃくもじでせつめいする", "300文字で説明する", "alphanumeric", "numbers"),
        ("小論文の下書きを", "よんひゃくもじでかきあげる", "400文字で書き上げる", "alphanumeric", "numbers"),
        ("エッセイの本文を", "ごひゃくもじにへんしゅうする", "500文字に編集する", "alphanumeric", "numbers"),

        ("タイトルの長さを", "にじゅうもじにおさめる", "20文字に収める", "alphanumeric", "numbers"),
        ("見出しのテキストを", "さんじゅうもじでかく", "30文字で書く", "alphanumeric", "numbers"),
        ("レスポンスが返るまで", "にびょうほどまつ", "2秒ほど待つ", "alphanumeric", "numbers"),
        ("画面が切り替わるまで", "さんびょうまつ", "3秒待つ", "alphanumeric", "numbers"),
        ("記念硬貨を貯金箱から", "いちまいとりだす", "1枚取り出す", "alphanumeric", "numbers"),
        ("プリントの用紙を", "にまいくばる", "2枚配る", "alphanumeric", "numbers"),

        # === Family 10: 英数字 + 半角スペース・URL保護 ===
        ("", "VS Codeでぷろじぇくとをひらく", "VS Codeでプロジェクトを開く", "alphanumeric", "ascii_space"),
        ("", "Android Studioでえみゅれーたをきどうする", "Android Studioでエミュレータを起動する", "alphanumeric", "ascii_space"),
        ("", "Visual Studioでびるどをじっこうする", "Visual Studioでビルドを実行する", "alphanumeric", "ascii_space"),
        ("", "RTX 4080 Superをとうさいする", "RTX 4080 Superを搭載する", "alphanumeric", "ascii_space"),
        ("", "Core i9 14900Kをつかう", "Core i9 14900Kを使う", "alphanumeric", "ascii_space"),
        ("", "Wi-Fi 6Eをゆうこうにする", "Wi-Fi 6Eを有効にする", "alphanumeric", "ascii_space"),
        ("", "HDMI 2.1でせつぞくする", "HDMI 2.1で接続する", "alphanumeric", "ascii_space"),
        ("", "PCIe 4.0 x16でせつぞくする", "PCIe 4.0 x16で接続する", "alphanumeric", "ascii_space"),
        ("", "cargo build --releaseをじっこうする", "cargo build --releaseを実行する", "alphanumeric", "ascii_space"),
        ("", "docker compose down -vをじっこうする", "docker compose down -vを実行する", "alphanumeric", "ascii_space"),
        ("", "git switch developをじっこうする", "git switch developを実行する", "alphanumeric", "ascii_space"),
        ("", "npm run buildでぱっけーじをつくる", "npm run buildでパッケージを作る", "alphanumeric", "ascii_space"),
        ("", "SELECT id FROM itemsをじっこうする", "SELECT id FROM itemsを実行する", "alphanumeric", "ascii_space"),
        ("", "http://192.168.10.25:8080/v1/chatにあくせすする", "http://192.168.10.25:8080/v1/chatにアクセスする", "alphanumeric", "ascii_space"),
        ("", "http://127.0.0.1:8000/api/statusにあくせすする", "http://127.0.0.1:8000/api/statusにアクセスする", "alphanumeric", "ascii_space"),
        ("新規の企画を立ち上げる。", "Project NOVAをしんこうする", "Project NOVAを進行する", "alphanumeric", "ascii_space"),

        # === Family 11: カタカナ人名 + と会う / に会う (未知語・固有名詞境界) ===
        ("登場人物の名前を含む。", "えみりあとあう", "エミリアと会う", "unknown", "katakana_name_au"),
        ("物語のキャラクター名を含む。", "そふぃあとあう", "ソフィアと会う", "unknown", "katakana_name_au"),
        ("ファンタジーの人物名を含む。", "ゆりあんとあう", "ユリアンと会う", "unknown", "katakana_name_au"),
        ("海外の人物名を含む。", "だみあんとあう", "ダミアンと会う", "unknown", "katakana_name_au"),
        ("小説の登場人物を含む。", "まりあんとあう", "マリアンと会う", "unknown", "katakana_name_au"),
        ("ゲームのキャラクター名を含む。", "ふろりあんとあう", "フロリアンと会う", "unknown", "katakana_name_au"),
        ("ギルドの仲間の名前を含む。", "あどりあんとあう", "アドリアンと会う", "unknown", "katakana_name_au"),
        ("王都の人物の名前を含む。", "りりあんとあう", "リリアンと会う", "unknown", "katakana_name_au"),

        # === Family 12: ひらがなペット名・愛称 + 助詞 (過剰漢字変換の防止アンカー) ===
        ("ペットの名前を含む。", "ちゃちゃまるにえさをあげる", "ちゃちゃまるに餌をあげる", "unknown", "pet_hiragana"),
        ("飼い猫の名前を含む。", "むぎまるにえさをあげる", "むぎまるに餌をあげる", "unknown", "pet_hiragana"),
        ("愛犬の名前を含む。", "こたろうにおやつをあげる", "こたろうにおやつをあげる", "unknown", "pet_hiragana"),
        ("小動物の名前を含む。", "すずまるにみずをあげる", "すずまるに水をあげる", "unknown", "pet_hiragana"),
        ("飼い犬の名前を含む。", "ふくまるにえさをやる", "ふくまるに餌をやる", "unknown", "pet_hiragana"),
        ("猫の愛称を含む。", "とらまるにごはんをあげる", "とらまるにご飯をあげる", "unknown", "pet_hiragana"),

        # === Family 13: コウカ (硬貨 6 / 効果 4 / 高価 4 / 降下 2) ===
        ("自動販売機の投入口に小銭を入れる。", "ひゃくえんのこうかをいれる", "100円の硬貨を入れる", "homophone", "kouka"),
        ("財布から小銭を取り出す。", "ごひゃくえんこうかをいちまいだす", "500円硬貨を1枚出す", "homophone", "kouka"),
        ("小銭入れから硬貨を出す。", "こうかをにまいとりだす", "硬貨を2枚取り出す", "homophone", "kouka"),
        ("お釣りとして五百円の", "こうかをうけとる", "硬貨を受け取る", "homophone", "kouka"),
        ("貯金箱に五百円の", "こうかをためる", "硬貨を貯める", "homophone", "kouka"),
        ("記念に発行された珍しい", "こうかをあつめる", "硬貨を集める", "homophone", "kouka"),
        ("新しい薬を飲んで治療の", "こうかをたしかめる", "効果を確かめる", "homophone", "kouka"),
        ("広告キャンペーンの宣伝", "こうかがあらわれる", "効果が現れる", "homophone", "kouka"),
        ("ショーケースに並んだ", "こうかなほうせきをみる", "高価な宝石を見る", "homophone", "kouka"),
        ("美術館に展示された", "こうかなかいがをみる", "高価な絵画を見る", "homophone", "kouka"),
        ("着陸態勢に入った飛行機が高度を", "こうかさせはじめる", "降下させ始める", "homophone", "kouka"),
        ("パラシュートを開いてゆっくり", "こうかする", "降下する", "homophone", "kouka"),

        # === Family 14: その他主要同音異義語アンカー群 ===
        ("テーブルの真ん中を広く空ける。", "さらをはしによせる", "皿を端に寄せる", "homophone", "hashi"),
        ("通路の邪魔にならないように", "にもつをはしによせる", "荷物を端に寄せる", "homophone", "hashi"),
        ("和食のテーブルマナーを学ぶ。", "はしをきれいにそろえる", "箸をきれいに揃える", "homophone", "hashi"),
        ("大きな川の両岸を繋ぐ。", "はしをわたってむこうへいく", "橋を渡って向こうへ行く", "homophone", "hashi"),

        ("スマートロックの暗証番号を入力する。", "げんかんをかいじょうする", "玄関を解錠する", "homophone", "kaijou"),
        ("指紋認証センサーに指を当てて", "とびらをかいじょうする", "扉を解錠する", "homophone", "kaijou"),
        ("イベントの開始時刻が近づく。", "コンサートホールがかいじょうする", "コンサートホールが開場する", "homophone", "kaijou"),
        ("セミナーの案内板を見て", "かいじょうにむかう", "会場に向かう", "homophone", "kaijou"),

        ("老朽化した体育館の耐震", "かいしゅうこうじをおこなう", "改修工事を行う", "homophone", "kaishuu"),
        ("アンケート用紙を出口で", "かいしゅうする", "回収する", "homophone", "kaishuu"),
        ("新幹線に乗って大阪へ", "いどうする", "移動する", "homophone", "idou"),
        ("四月の人事発令で営業部へ", "いどうになる", "異動になる", "homophone", "idou"),
        ("またお目にかかれる", "きかいをたのしみにする", "機会を楽しみにする", "homophone", "kikai"),
        ("工場の生産ラインに新しい", "きかいをどうにゅうする", "機械を導入する", "homophone", "kikai"),
        ("キャンペーンの応募", "きかんをえんちょうする", "期間を延長する", "homophone", "kikan"),
        ("公共交通の鉄道やバスなどの", "きかんをりようする", "機関を利用する", "homophone", "kikan"),
        ("結果だけでなくそこに至る", "かていをじゅうしする", "過程を重視する", "homophone", "katei"),
        ("仕事と温かい", "かていをりょうりつする", "家庭を両立する", "homophone", "katei"),
        ("もし条件が変わったと", "かていしてけいさんする", "仮定して計算する", "homophone", "katei"),
        ("週末に美術館で西洋絵画を", "かんしょうする", "鑑賞する", "homophone", "kanshou"),
        ("植物園の温室で珍しい熱帯植物を", "かんしょうする", "観賞する", "homophone", "kanshou"),
        ("他人のプライベートな問題に", "かんしょうしない", "干渉しない", "homophone", "kanshou"),
        ("ローマ字で入力したひらがなを漢字に", "へんかんする", "変換する", "homophone", "henkan"),
        ("奨学金の借り入れ分を毎月", "へんかんする", "返還する", "homophone", "henkan"),
        ("精密な金型を使って金属部品を", "せいさくする", "製作する", "homophone", "seisaku"),
        ("映画監督が新しいドキュメンタリー映像を", "せいさくする", "制作する", "homophone", "seisaku"),
        ("スマートフォンのQRコードで代金を", "けっさいする", "決済する", "homophone", "kessai"),
        ("稟議書を提出して部長の", "けっさいをあおぐ", "決裁を仰ぐ", "homophone", "kessai"),
        ("人質を無事に", "かいほうする", "解放する", "homophone", "kaihou"),
        ("休日に学校の校庭を地域住民に", "かいほうする", "開放する", "homophone", "kaihou"),
        ("アンケートの質問事項に", "かいとうする", "回答する", "homophone", "kaitou"),
        ("数学の試験問題の", "かいとうをかく", "解答を書く", "homophone", "kaitou"),
        ("四月から電気料金のプランを", "かいていする", "改定する", "homophone", "kaitei"),
        ("辞書の記述を見直して第三版に", "かいていする", "改訂する", "homophone", "kaitei"),
        ("降水", "かくりつがたかい", "確率が高い", "homophone", "kakuritsu"),
        ("新しい治療法を", "かくりつする", "確立する", "homophone", "kakuritsu"),
        ("割引キャンペーンの", "たいしょうになる", "対象になる", "homophone", "taishou"),
        ("二つの実験データを", "たいしょうしてひかくする", "対照して比較する", "homophone", "taishou"),
        ("左右がきれいに", "たいしょうなずけい", "対称な図形", "homophone", "taishou"),
    ]
    rows = []
    for left_ctx, reading, comp, cat, fam in spec:
        rows.append(make_row(left_ctx, reading, comp, cat, fam))
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output_dir",
        type=str,
        default="artifacts/datasets/nano-v3-distill-20261028",
    )
    parser.add_argument("--val_fraction", type=float, default=0.10)
    parser.add_argument("--seed", type=str, default="20261028")
    args = parser.parse_args()

    out_dir = ROOT / args.output_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    reserved_keys = evaluation_keys()
    eval_cases = load_jsonl(ROOT / "data/kana_kanji_llm_testset_5x.jsonl")
    eval_ctx_norm = {
        normalize(c["left_context"])
        for c in eval_cases
        if c.get("left_context") and c["left_context"] != "なし"
    }

    curated = collect_curated_families()
    curated_clean = [
        r for r in curated
        if (not r["left_context"] or normalize(r["left_context"]) not in eval_ctx_norm)
        and not (source_keys(r) & reserved_keys)
    ]
    dropped_curated = len(curated) - len(curated_clean)
    if dropped_curated:
        print(f"Dropped {dropped_curated} curated rows due to evaluation key/context overlap.")

    train, val, excluded = separated_rows(
        curated_clean,
        reserved=reserved_keys,
        val_fraction=args.val_fraction,
        seed=args.seed,
    )
    checks = validate_rows(train, val, reserved_keys)

    train_val_ctx = {normalize(r.get("left_context", "")) for r in train + val if r.get("left_context")}
    ctx_overlap = len(train_val_ctx & eval_ctx_norm)
    if ctx_overlap != 0:
        raise ValueError(f"Found {ctx_overlap} overlapping left_context strings with evaluation set!")

    train_path = out_dir / "train.jsonl"
    val_path = out_dir / "val.jsonl"
    with train_path.open("w", encoding="utf-8") as f:
        for r in train:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with val_path.open("w", encoding="utf-8") as f:
        for r in val:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    manifest = {
        "policy": POLICY,
        "seed": args.seed,
        "val_fraction": args.val_fraction,
        "counts": {
            "curated_unique": len(curated_clean),
            "train": len(train),
            "validation": len(val),
            "excluded_by_isolation": len(excluded),
        },
        "checks": {
            **checks,
            "evaluation_left_context_overlap": ctx_overlap,
        },
        "files": {
            "train": {"path": str(train_path), "sha256": sha256(train_path)},
            "validation": {"path": str(val_path), "sha256": sha256(val_path)},
            "evaluation": {p.name: sha256(p) for p in EVALUATION_FILES},
        },
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

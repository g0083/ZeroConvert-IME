# SPDX-License-Identifier: Apache-2.0
"""Create task-authored synthetic wiring examples. Not a production corpus."""
import argparse
import hashlib
import json
from pathlib import Path

from origin_contract import validate_partitions, validate_sources, write_json

# Authored for this implementation, without importing evaluation examples,
# existing corpora, personal input history, or external teacher output.
SEEDS = [
    ('棚の奥へ', 'たなの おくへ', 'tananookuhe', 'tananokoue', '棚の', '奥へ移します。'),
    ('薄紫の封筒', 'うすむらさきのふうとう', 'usumurasakinofuutou', 'usumurasakinofuutuo', '薄紫の', '封筒を選びます。'),
    ('窓際の鉢植え', 'まどぎわのはちうえ', 'madogiwanohachiue', 'madogiwanohachieu', '窓際の', '鉢植えに水を注ぎます。'),
    ('木箱を並べる', 'きばこをならべる', 'kibakowonaraberu', 'kibakowonaraebru', '木箱を', '並べておきます。'),
    ('夕方の船着場', 'ゆうがたのふなつきば', 'yuugatanofunatsukiba', 'yuugatanofunatsukbia', '夕方の', '船着場を歩きます。'),
    ('小さな温度計', 'ちいさなおんどけい', 'chiisanaonndokei', 'chiisanaonndokie', '小さな', '温度計を取り付けます。'),
    ('橙色の毛糸', 'だいだいいろのけいと', 'daidaiironokeito', 'daidaiironokieto', '橙色の', '毛糸をほどきます。'),
    ('坂道の風鈴', 'さかみちのふうりん', 'sakamichinofuurinn', 'sakamichinofururinn', '坂道の', '風鈴が揺れています。'),
    ('真鍮の取手', 'しんちゅうのとって', 'shinnchuunototte', 'shinnchuunottote', '真鍮の', '取手を磨きます。'),
    ('陶器の小皿', 'とうきのこざら', 'toukinokozara', 'toukinokozraa', '陶器の', '小皿を洗います。'),
    ('折畳みの椅子', 'おりたたみのいす', 'oritataminoisu', 'oritataminoius', '折畳みの', '椅子を運びます。'),
    ('中庭の白樺', 'なかにわのしらかば', 'nakaniwanoshirakaba', 'nakaniwanoshriakaba', '中庭の', '白樺を眺めます。'),
    ('青緑の栞', 'あおみどりのしおり', 'aomidorinoshiori', 'aomidorinoshioir', '青緑の', '栞を挟みます。'),
    ('地下室の配管', 'ちかしつのはいかん', 'chikashitsunohaikann', 'chikashitsunohiakann', '地下室の', '配管を調べます。'),
    ('綿麻の布地', 'めんあさのぬのじ', 'mennasanonunoji', 'mennasanonunojji', '綿麻の', '布地を裁ちます。'),
    ('屋根裏の木馬', 'やねうらのもくば', 'yaneuranomokuba', 'yaneuranomokbua', '屋根裏の', '木馬を下ろします。'),
    ('朝霧の牧草地', 'あさぎりのぼくそうち', 'asagirinobokusouchi', 'asagirinobokusoucih', '朝霧の', '牧草地を巡ります。'),
    ('壁面の見取図', 'へきめんのみとりず', 'hekimennnomitorizu', 'hekimennnomitorziu', '壁面の', '見取図を貼ります。'),
    ('深緑の手帳', 'ふかみどりのてちょう', 'fukamidorinotechou', 'fukamidorinotehocu', '深緑の', '手帳に記します。'),
    ('星形の留め具', 'ほしがたのとめぐ', 'hoshigatanotomegu', 'hoshigatanotmoegu', '星形の', '留め具を外します。'),
]


def fixture():
    rows = []
    for i, (surface, reading, raw, typo, prefix, suffix) in enumerate(SEEDS):
        reading = reading.replace(' ', '')
        common = {'source_id': 'origin-authored-fixture-v1', 'document_id': f'fixture-{i}',
                  'source_text': surface + '。' + prefix + suffix}
        variants = [
            ('convert', {'reading': reading}, 'emit', surface),
            ('repair_qwerty', {'raw': typo, 'surface': reading}, 'emit', reading),
            ('repair_qwerty', {'raw': raw, 'surface': reading}, 'keep', ''),
            ('repair_qwerty', {'raw': raw + 'k', 'pending': 'k', 'surface': reading}, 'abstain', ''),
            ('predict', {'surface': prefix}, 'emit', suffix),
            ('predict', {'surface': prefix + suffix}, 'abstain', ''),
        ]
        for j, (task, inputs, action, text) in enumerate(variants):
            rows.append({**common, 'id': f'fixture-{i}-{j}', 'task': task,
                         'input': inputs, 'target': {'action': action, 'text': text}})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    out = Path(args.out)
    if out.exists():
        raise FileExistsError(out)
    sources = {'origin-authored-fixture-v1': {
        'license': 'Apache-2.0', 'origin': 'Task-authored synthetic examples, 2026-10-29',
        'evidence': 'data/build_origin_fixture.py (SEEDS); no external corpus or teacher',
        'reviewed_by': 'Codex: authorship/provenance check only; readings not human-reviewed',
        'approved_uses': ['training', 'weight_distribution'], 'teacher': None,
        'approved_weight_license': 'Apache-2.0',
        'weight_license_review': 'Task-authored synthetic fixture; Apache-2.0 requested by project owner',
        'quality': 'wiring-fixture-only',
    }}
    rows = fixture()
    validate_sources(rows, sources)
    # Reserve whole documents before task expansion; all siblings stay together.
    val_docs = {'fixture-3', 'fixture-8', 'fixture-13', 'fixture-18'}
    train = [r for r in rows if r['document_id'] not in val_docs]
    val = [r for r in rows if r['document_id'] in val_docs]
    validate_partitions(train, val)
    out.mkdir(parents=True)
    for name, records in (('train', train), ('val', val)):
        (out / f'{name}.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in records), encoding='utf-8')
    write_json(out / 'sources.json', sources)
    write_json(out / 'manifest.json', {'schema': 'zerocon-origin-v1', 'purpose': 'wiring-fixture-only',
        'train_rows': len(train), 'val_rows': len(val), 'source_documents': len(SEEDS),
        'builder_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'limitations': ['No real input events', 'No production accuracy claim',
                         'No near-duplicate or semantic decontamination certification']})
    print(f'Created {len(train)} train / {len(val)} validation wiring examples: {out}')


if __name__ == '__main__':
    main()

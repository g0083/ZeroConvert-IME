# SPDX-License-Identifier: Apache-2.0
"""Assemble a local, measured research model card; never updates any IME."""
import argparse
import json
from pathlib import Path
import shutil

from origin_contract import digest, write_json


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--model-dir', type=Path, required=True)
    p.add_argument('--hf-result', type=Path, required=True)
    p.add_argument('--q8-result', type=Path, required=True)
    p.add_argument('--corrector-hf', type=Path, required=True)
    p.add_argument('--corrector-q8', type=Path, required=True)
    p.add_argument('--comparison', type=Path, required=True)
    p.add_argument('--checks', type=Path, required=True)
    args = p.parse_args()
    if (args.model_dir/'README.md').exists() or (args.model_dir/'MODEL_MANIFEST.json').exists():
        raise FileExistsError('Model card already exists')
    metrics = read(args.run/'metrics.json')
    selection = read(args.run/'selection-evidence.json')
    hf, q8, chf, cq8 = map(read, (args.hf_result, args.q8_result, args.corrector_hf, args.corrector_q8))
    quant = read(args.model_dir/'f16/q8-manifest.json')
    codec = read(args.model_dir/'f16/v4-unified-codec.json')
    verification = read(args.checks/'verification.json')
    if (hf['model_sha256'] != metrics['hf_hashes']['model.safetensors'] or chf['model_sha256'] != hf['model_sha256']
            or q8['model_sha256'] != quant['output_sha256'] or cq8['model_sha256'] != q8['model_sha256']):
        raise ValueError('Evaluation does not belong to selected model')
    if verification['original_evaluator_sha256'] != q8['evaluation_code_sha256']:
        raise ValueError('Archived evaluation source differs')
    files = {}
    for precision, name in (('f32', 'standard-qwerty-unified-f32.gguf'),
                            ('f16', 'standard-qwerty-unified-f16.gguf'),
                            ('f16', 'standard-qwerty-unified-q8_0.gguf')):
        path = args.model_dir/precision/name
        files[name] = {'path': str(path.relative_to(args.model_dir)), 'bytes': path.stat().st_size, 'sha256': digest(path)}
    if files['standard-qwerty-unified-q8_0.gguf']['sha256'] != quant['output_sha256']:
        raise ValueError('Q8 file changed')
    evidence = args.model_dir/'evidence'
    evidence.mkdir()
    for source in (args.hf_result, args.q8_result, args.corrector_hf, args.corrector_q8, args.comparison):
        shutil.copy2(source, evidence/source.name)
    shutil.copytree(args.checks, evidence/'checks')
    card = {'schema': 'zerocon-standard-qwerty-research-model-v1',
        'name': 'ZeroCon Standard-QWERTY Unified — v4-derived research R5',
        'date': '2026-10-29', 'status': 'research candidate; product quality gates not passed',
        'tasks': ['convert', 'repair', 'predict'], 'trained_input_method': 'romaji', 'tested_layout_tag': 'JP-QWERTY',
        'source_run': str(args.run), 'source_run_manifest_sha256': digest(args.run/'run-manifest.json'),
        'selected_checkpoint': selection, 'vocab_size': codec['vocab_size'],
        'input_contract': 'host codec -> integer IDs; observation derived from raw/pending; repair has explicit emit marker',
        'files': files, 'hf_summary': hf['summary'], 'q8_summary': q8['summary'],
        'corrector_hf_summary': chf['summary'], 'corrector_q8_summary': cq8['summary'],
        'timing_comparable_to_frozen_v4': False,
        'timing_limit': verification['timing_reason'],
        'ime_applied': False, 'automatic_adoption_enabled': False,
        'public_distribution_review_completed': False,
        'known_regressions': ['convert development score below parent 459/480',
            'new-format path backslashes may double', 'normal repair changes remain on corrector cohort',
            'real typing, calibration, sealed evaluation, and repair-to-convert total latency unverified'],
        'packaging_code_sha256': digest(__file__)}
    write_json(args.model_dir/'MODEL_MANIFEST.json', card)
    hs, qs, cs = hf['summary'], q8['summary'], cq8['summary']
    root = args.model_dir.as_posix()
    text = f'''# ZeroCon Standard-QWERTY Unified — 研究用初版 R5

v4派生の同じ重みで、通常変換・読み修復・先読みsuffixを処理する。**品質基準は未達。実IMEには未適用。**
使用時はGGUFと同梱codecを組み合わせる。固定CPU実行器の文字列tokenizeには差があるため、整数IDで呼ぶ。

## 重み

- Q8: `f16/standard-qwerty-unified-q8_0.gguf`（{files['standard-qwerty-unified-q8_0.gguf']['bytes']:,} bytes、約99.1 MiB）
- F16: `f16/standard-qwerty-unified-f16.gguf`
- F32: `f32/standard-qwerty-unified-f32.gguf`
- hash・来歴・実績: `MODEL_MANIFEST.json`、`evidence/`、各precisionのmanifest。
- 選択重み: R5の{selection['selected_step']}step（{selection['selected_processed_tokens']:,}処理tokens）。runは{selection['completed_steps']}stepまで完走。
- 6043語彙。旧6000 IDを保持し、不足byte24・制御19を追加。修復後の漢字表記は同じモデルのconvertで別に求める。

## 開発評価

| 指標 | HF FP32 | Q8 |
|---|---:|---:|
| 旧形式の通常変換 /480 | {hs['legacy_480']['perfect_legacy_scorer']} | {qs['legacy_480']['perfect_legacy_scorer']} |
| 新形式の通常変換 /480 | {hs['tagged_480']['perfect_legacy_scorer']} | {qs['tagged_480']['perfect_legacy_scorer']} |
| 同じ合成誤入力の読み救済 /100 | {hs['repair_emit']['reading_rescued']} | {qs['repair_emit']['reading_rescued']} |
| 同コホートの正常誤変更 /100 | {hs['repair_keep']['normal_changed']} | {qs['repair_keep']['normal_changed']} |
| 先読みの単一suffix完全一致 /100 | {hs['predict_emit']['exact']} | {qs['predict_emit']['exact']} |

別のcorrector由来コホートではQ8の救済{cs['repair_emit']['reading_rescued']}/100、正常誤変更{cs['repair_keep']['normal_changed']}/100。
正常入力の誤変更は残る。正常100件の0だけで99.5% precisionや誤変更率0.1%未満を達成したとは扱わない。
親Q8の459/480維持、新形式でのパス保護、独立評価、校正、実打鍵、実IME操作は未達／未検証。
文字列pathのbackslashが増える退行も確認した。現行IMEのモデル名だけ差し替える用途には使わない。

## 単発で試す

リポジトリルートから実行する。補正候補を表示するだけで、自動採用しない。

```powershell
.\\.venv_train\\Scripts\\python.exe scripts/run.py training/infer_v4_unified.py --codec {root}/f16 --gguf {root}/f16/standard-qwerty-unified-q8_0.gguf --task repair --raw argiatou
```

`--task convert --reading <ひらがな>`、`--task predict --surface <可視表記>`も同じ重みを使う。
pendingがある補正・先読み、形式違反、修復しても観測読みと同じ場合は、それぞれabstain／keepにする。
これらの入力側guardと、記録した生モデルの評価を区別する。

## 測定・利用範囲

F32/F16は149 tensorのcast一致、6プローブのgreedy token一致を確認。全入力の同一性を証明するものではない。
整数ID/return_tokensの測定と、過去の文字列/stop文字列のCPU時間は直接比較しない。
単発の32問同条件診断は`evidence/checks/verification.json`を参照。速度目標の達成とは扱わない。

correctorのかなだけの正解読み・文脈・正常保持・keyboard mutatorを継承し、新しい合成rawとして学習した。
元打鍵が残っていない旧かな誤入力を、実打鍵として逆変換したものではない。
公開配布の条件は今回未再審査。`sources.json`と`source-evidence/`、同梱MIT表示を保持する。
ソース・学習・試作書き出し・開発評価まで。Windows/Android反映、入力アプリ確認、installer、commit/pushは行っていない。

詳細: `reports/models/zerocon-standard-qwerty-unified-20261029.md`
'''
    (args.model_dir/'README.md').write_text(text, encoding='utf-8')
    print(f'Packaged research candidate: {args.model_dir}')


if __name__ == '__main__':
    main()

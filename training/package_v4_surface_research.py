# SPDX-License-Identifier: Apache-2.0
"""Package measured three-task surface-repair research weights, never an IME update."""
import argparse
import json
from pathlib import Path
import shutil

from origin_contract import digest, write_json


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--model-dir', type=Path, required=True)
    p.add_argument('--hf-result', type=Path, required=True)
    p.add_argument('--q8-result', type=Path, required=True)
    p.add_argument('--common-hf', type=Path, required=True)
    p.add_argument('--common-q8', type=Path, required=True)
    p.add_argument('--comparison', type=Path, required=True)
    p.add_argument('--sampling', type=Path, required=True)
    p.add_argument('--parity-f32', type=Path, required=True)
    p.add_argument('--parity-f16', type=Path, required=True)
    p.add_argument('--pipeline-current', type=Path)
    p.add_argument('--pipeline-fixed-normal', type=Path)
    p.add_argument('--pipeline-single-model', type=Path)
    p.add_argument('--reading-analysis', type=Path)
    p.add_argument('--cli-probe', type=Path)
    a = p.parse_args()
    if (a.model_dir / 'MODEL_MANIFEST.json').exists() or (a.model_dir / 'README.md').exists():
        raise FileExistsError('Research card already exists')
    metrics, manifest = read(a.run / 'metrics.json'), read(a.run / 'run-manifest.json')
    if metrics['status'] != 'training_completed':
        raise ValueError('Unfinished run')
    for name, expected in metrics['hf_hashes'].items():
        if digest(a.run / 'hf' / name) != expected:
            raise ValueError('HF changed after training')
    codec = read(a.run / 'hf/v4-unified-codec.json')
    if codec.get('repair_output') != 'surface' or codec.get('repair_input') != 'observed':
        raise ValueError('Not an observed-input surface repair model')
    weights = manifest['task_weights']
    for task in ('convert', 'repair', 'predict'):
        value = sum(v for key, v in weights.items() if (key.startswith('repair') if task == 'repair' else
                    key == 'predict' if task == 'predict' else key.startswith(('convert', 'anchor'))))
        if value <= 0:
            raise ValueError('A required task was not trained')
    quant = read(a.model_dir / 'f16/q8-manifest.json')
    hf, q8, chf, cq8 = map(read, (a.hf_result, a.q8_result, a.common_hf, a.common_q8))
    if any(r['model_sha256'] != metrics['hf_hashes']['model.safetensors'] for r in (hf, chf)):
        raise ValueError('HF evaluation mismatch')
    if any(r['model_sha256'] != quant['output_sha256'] for r in (q8, cq8)):
        raise ValueError('Q8 evaluation mismatch')
    if chf['fixture_sha256'] != cq8['fixture_sha256']:
        raise ValueError('Common cases differ')
    parities = {mode: read(path) for mode, path in [('f32', a.parity_f32), ('f16', a.parity_f16)]}
    if any(v['status'] != 'PASS_INTEGER_ID_CONTRACT' for v in parities.values()):
        raise ValueError('Integer-ID export verification did not pass')
    pipelines = {}
    pipeline_files = []
    for label, source in [('current', a.pipeline_current), ('fixed_normal', a.pipeline_fixed_normal), ('single_model', a.pipeline_single_model)]:
        if source:
            result = read(source)
            if label != 'current' and result['identity']['model_sha256'] != quant['output_sha256']:
                raise ValueError('Pipeline model mismatch')
            pipelines[label] = result['summary']
            pipeline_files.append(source)
    fixed_verified = False
    if a.pipeline_current and a.pipeline_fixed_normal:
        current, fixed = read(a.pipeline_current), read(a.pipeline_fixed_normal)
        original = {str(r['id']): r['baseline'] for r in current['cases']}
        fixed_ids = {str(r['id']) for r in fixed['cases']}
        if (len(original) != 300 or len(current['cases']) != 300 or len(fixed['cases']) != 300 or
                fixed_ids != set(original) or
                current['identity']['cases_sha256'] != fixed['identity']['cases_sha256'] or
                current['identity']['runner_sha256'] != fixed['identity']['runner_sha256'] or
                current['identity']['normal']['sha256'] != fixed['identity']['fixed_normal_sha256'] or
                any(original.get(str(r['id'])) != r['baseline'] for r in fixed['cases'])):
            raise ValueError('Fixed-normal policy comparison differs')
        fixed_verified = True
    files = {}
    for mode, name in [('f32', 'standard-qwerty-unified-f32.gguf'), ('f16', 'standard-qwerty-unified-f16.gguf'),
                       ('f16', 'standard-qwerty-unified-q8_0.gguf')]:
        file = a.model_dir / mode / name
        files[name] = {'relative_path': str(file.relative_to(a.model_dir)), 'bytes': file.stat().st_size, 'sha256': digest(file)}
    for mode, result in parities.items():
        if result['gguf_sha256'] != files[f'standard-qwerty-unified-{mode}.gguf']['sha256'] or result['hf_sha256'] != metrics['hf_hashes']['model.safetensors']:
            raise ValueError('Parity evidence mismatch')
    sampling = read(a.sampling)
    if sampling['run_manifest_sha256'] != digest(a.run / 'run-manifest.json') or sampling['status'] != 'PASS':
        raise ValueError('Sampler evidence mismatch')
    evidence = a.model_dir / 'evidence'
    evidence.mkdir()
    for source in (a.hf_result, a.q8_result, a.common_hf, a.common_q8, a.comparison,
                   a.sampling, a.parity_f32, a.parity_f16, a.run / 'best-selection.json', *pipeline_files):
        shutil.copy2(source, evidence / source.name)
    additional = {}
    for source in (a.reading_analysis, a.cli_probe):
        if source:
            shutil.copy2(source, evidence / source.name)
            additional[source.name] = digest(source)
    source_dir = evidence / 'evaluation-source'
    source_dir.mkdir()
    for name in ('benchmarks/research/benchmark_v4_surface_pipeline.py', 'benchmarks/research/test_v4_surface_pipeline_adapter.py',
                 'benchmarks/research/evaluate_v4_repair_comparison.py', 'benchmarks/research/verify_v4_unified_parity.py',
                 'training/package_v4_surface_research.py'):
        shutil.copy2(name, source_dir / Path(name).name)
    card = {'schema': 'v4-surface-unified-research-card-v1', 'name': 'ZeroCon Standard-QWERTY Unified — surface research',
            'status': 'Research candidate; product quality gates unproven; not installed',
            'source_run': str(a.run), 'run_manifest_sha256': digest(a.run / 'run-manifest.json'),
            'hf_source': str(a.run / 'hf'), 'selected_checkpoint': metrics['best'], 'sampling': sampling,
            'vocab_size': codec['vocab_size'], 'tasks': ['convert', 'repair', 'predict'],
            'input_contract': 'Bundled host codec -> integer IDs; observed reading from host or bundled raw decoder',
            'repair_output': 'keep / abstain / emit corrected surface; dictionary reading validation required before adoption',
            'files': files, 'hf_summary': hf['summary'], 'q8_summary': q8['summary'],
            'common_hf_summary': chf['summary'], 'common_q8_summary': cq8['summary'],
            'pinned_pipeline': pipelines,
            'fixed_normal_all_300_baselines_verified': fixed_verified,
            'additional_evidence_sha256': additional,
            'parity_status': {k: v['status'] for k, v in parities.items()},
            'native_text_tokenizer_status': {k: v['native_text_tokenizer_status'] for k, v in parities.items()},
            'ime_applied': False, 'automatic_adoption': False, 'timing_comparable_to_frozen_v4': False,
            'sealed_test': False, 'public_distribution_review_completed': False,
            'packager_sha256': digest(__file__)}
    write_json(a.model_dir / 'MODEL_MANIFEST.json', card)
    hs, qs, cs = hf['summary'], q8['summary'], cq8['summary']
    root = a.model_dir.as_posix()
    text = f'''# ZeroCon Standard-QWERTY Unified — surface研究版

状態：**学習・HF/GGUF/Q8・開発評価まで。品質目標は未達／未検証。実IME未適用。**

同じ重みでconvert・repair・predictを処理する。repairは修復した漢字交じり表記を返す。
従来の「ひらがな読みを返すrepair」と互換ではなく、既存IMEのファイル差替え用にはしない。
ホスト側codecで符号化した整数IDを使う。nativeの文字列tokenizer経由は別の契約である。

| 通常変換 | HF FP32 | Q8 |
|---|---:|---:|
| 旧形式 /480 | {hs['legacy_480']['perfect_legacy_scorer']} | {qs['legacy_480']['perfect_legacy_scorer']} |
| compact形式 /480 | {hs['tagged_480']['perfect_legacy_scorer']} | {qs['tagged_480']['perfect_legacy_scorer']} |

親Standard v4 Q8は459/480。増減の内訳はevidence内のpaired比較を参照。
通常変換には数字・コマンド・意味が変わる退行が残り、親と同等の品質を達成していない。
共通の合成タイポ100件ではQ8で読み一致確認{cs['typo_reading_matched']}件、
正常100件では観測読みの保持確認{cs['normal_preserved']}件。
表記の読みは参照表記または辞書で自動照合した値であり、意味・文脈の正しさや実IME成功率ではない。
既知ルール・採用フィルター・通常候補保護を通していない。pendingは共通評価ではホストガードで省略した。
封印評価、自動適用precision、正常誤補正率0.1%、実IMEの編集・総遅延は未検証。

選択stepは{metrics['best']['step']}。run終端と選択重みの学習量はMODEL_MANIFESTで区別する。
F32/F16のtensor・整数ID・6生成probeの結果はevidenceに保存した。全入力の数値同一性の証明ではない。

## 試す

リポジトリルートから、同梱codec・固定decoderと研究CLIを使う。IMEへ接続せず、自動採用しない。

```powershell
.\\.venv_train\\Scripts\\python.exe scripts/run.py training/infer_v4_unified.py --codec {root}/f16 --gguf {root}/f16/standard-qwerty-unified-q8_0.gguf --task repair --raw argiatou
```

`--reading`で実際の観測読みも渡せる。`--task convert --reading ...`、`--task predict --surface ...`も同じ重みを使う。
表記候補は読み検証が必要。配布条件は継承した来歴・通知を参照し、公開判断は本研究カードから行わない。
'''
    if a.cli_probe:
        text += '\n単発CLIの実測ログもevidenceに保存した。上のコマンドが正解を返すという使用例ではない。\n'
    if pipelines:
        text += '\n## 固定Rustポリシーでの300問\n\n| 条件 | 正しい補正 /255 | 誤提案 /255 | 正常誤提案 /45 | 表記不一致の自動適用判定 |\n|---|---:|---:|---:|---:|\n'
        for label, summary in pipelines.items():
            text += f"| {label} | {summary['typo_correct_suggestion']} | {summary['typo_wrong_suggestion']} | {summary['clean_wrong_suggestion']} | {summary['wrong_auto_apply']} |\n"
        text += '\nfixed_normalは通常候補を現行比較と300件すべて一致確認。single_modelは通常変換と補正で同じ重み・同じ推論プロセスを使う。\nSwift・TSF・実アプリ操作を含む実IME確認ではない。小さい正常集合の0件で誤補正率0.1%以下を証明しない。\n'
    (a.model_dir / 'README.md').write_text(text, encoding='utf-8')
    print(f'Created measured research card: {a.model_dir}', flush=True)


if __name__ == '__main__':
    main()

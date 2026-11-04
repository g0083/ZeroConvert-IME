"""Run the fixed 2x2x2 correction experiment without deploying any model.

Use a fresh --name for another experiment; existing weights/results are preserved.
Training and evaluation are separate so CPU measurements run after GPU training.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from dataset_guard import sha256

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOTS = {'xsmall': 'a728931c8b4867a53ca33d1ff3fe7360b0f15cd5',
             'small': '344612e331bf3ba752a9bfef6633c93c6b933ebb'}
CONDITIONS = ('narrow-surface', 'diverse-surface', 'narrow-reading', 'diverse-reading')


def run(command, log):
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        raise RuntimeError(f'Failed ({result.returncode}), inspect {log}')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--phase', choices=('train', 'evaluate'), required=True)
    p.add_argument('--name', default='corrector-ablation-20261027')
    p.add_argument('--models', nargs='+', choices=SNAPSHOTS, default=list(SNAPSHOTS))
    p.add_argument('--conditions', nargs='+', choices=CONDITIONS, default=list(CONDITIONS))
    args = p.parse_args()
    if Path(args.name).name != args.name:
        raise ValueError('name must be a single directory component')
    result_dir = ROOT / 'artifacts/results' / args.name
    result_dir.mkdir(parents=True, exist_ok=True)
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    for size in args.models:
        base = Path.home() / f'.cache/huggingface/hub/models--Miwa-Keita--zenz-v3.1-{size}/snapshots' / SNAPSHOTS[size]
        for condition in args.conditions:
            tag = f'{args.name}-{size}-{condition}'
            checkpoint = ROOT / 'artifacts/checkpoints' / f'{tag}-lora'
            merged = ROOT / 'artifacts/checkpoints' / f'{tag}-merged'
            f16 = ROOT / 'artifacts/models' / f'{tag}-f16.gguf'
            q4 = ROOT / 'artifacts/models' / f'{tag}-q4km.gguf'
            dataset = ROOT / 'artifacts/datasets' / args.name / condition
            logroot = ROOT / 'artifacts/logs'
            if args.phase == 'train':
                if any(x.exists() for x in (checkpoint, merged, f16, q4)):
                    raise FileExistsError(f'Preserving existing model: {tag}')
                record_path = result_dir / f'{size}-{condition}-training.json'
                record = {'model': size, 'condition': condition, 'base': str(base),
                          'base_weights_sha256': sha256(base/'model.safetensors'),
                          'dataset_manifest_sha256': sha256(dataset.parent/'manifest.json'),
                          'train_script_sha256': sha256(ROOT/'training/train_typo_corrector.py'),
                          'status': 'training', 'started_at_unix': time.time(),
                          'recipe': {'epochs':3, 'batch':32, 'accum':2, 'lr':.0001,
                                     'lora_r':16, 'lora_alpha':32, 'lora_dropout':.05, 'length':256, 'seed':42}}
                record_path.write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
                print(f'Training {size} {condition}', flush=True)
                run([sys.executable, 'scripts/run.py', 'training/train_typo_corrector.py',
                     '--base_model', base, '--train_file', dataset/'train.jsonl', '--val_file', dataset/'val.jsonl',
                     '--output_dir', checkpoint, '--epochs',3,'--batch_size',32,'--grad_accum',2,'--lr',.0001,
                     '--lora_r',16,'--lora_alpha',32,'--lora_dropout',.05,'--max_length',256,'--seed',42], logroot/f'{tag}-training.log')
                run([sys.executable,'scripts/run.py','training/export_typo_corrector.py','--base_model',base,
                     '--lora_dir',checkpoint,'--output_dir',merged],logroot/f'{tag}-export.log')
                run([sys.executable,'android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py',merged,
                     '--outfile',f16,'--outtype','f16'],logroot/f'{tag}-convert.log')
                run([ROOT/'ZeroConvertIME-windows/llama_cpu/llama-quantize.exe',f16,q4,'Q4_K_M'],logroot/f'{tag}-quantize.log')
                states = sorted(checkpoint.glob('checkpoint-*/trainer_state.json'),key=lambda x:int(x.parent.name.split('-')[-1]))
                state = json.loads(states[-1].read_text())
                record.update(status='completed', finished_at_unix=time.time(), model_path=str(q4),
                              model_bytes=q4.stat().st_size, model_sha256=sha256(q4),
                              best_checkpoint=state['best_model_checkpoint'], best_val_loss=state['best_metric'])
                record_path.write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
                print(f'Completed {size} {condition}',flush=True)
            else:
                for cases, label in [('data/typo_benchmark_300.jsonl','300'),('benchmarks/research/typo_testset.jsonl','35')]:
                    command = [sys.executable,'scripts/run.py','benchmarks/research/benchmark_corrector_pipeline.py',
                               '--corrector',q4,'--normal','artifacts/models/zerocon/zerocon-model-standard.gguf',
                               '--runner',f'artifacts/build/{args.name}/debug/examples/evaluate_corrector.exe',
                               '--cases',cases,'--out',result_dir/f'{size}-{condition}-{label}.json']
                    if condition.endswith('-reading'):
                        command.append('--reading-stage')
                    print(f'Evaluating {size} {condition} {label}',flush=True)
                    run(command,logroot/f'{tag}-eval-{label}.log')
    print(f'{args.phase} completed',flush=True)


if __name__ == '__main__':
    main()

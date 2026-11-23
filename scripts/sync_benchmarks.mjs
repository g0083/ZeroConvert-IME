// benchmarks/common を Windows/Android 配布物へ同期する。
// 正本: benchmarks/common/conversion.jsonl (840), typo.jsonl (450), predictive.jsonl (30)
// 生成物は手編集しない。--check は生成物が正本と一致するか検査する。
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const commonDir = path.join(root, 'benchmarks/common');
const checkOnly = process.argv.includes('--check');

function parseJsonl(text) {
  return text
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line));
}

async function readJsonl(rel) {
  const text = await readFile(path.join(root, rel), 'utf8');
  return parseJsonl(text);
}

function toJsonArray(rows) {
  return JSON.stringify(rows, null, 2) + '\n';
}

function toJsonl(rows) {
  return rows.map((r) => JSON.stringify(r)).join('\n') + '\n';
}

const conversion = await readJsonl('benchmarks/common/conversion.jsonl');
const typo = await readJsonl('benchmarks/common/typo.jsonl');
const predictive = await readJsonl('benchmarks/common/predictive.jsonl');
const parityText = await readFile(path.join(commonDir, 'parity_cases.jsonl'), 'utf8');
const parityCount = parseJsonl(parityText).length;

if (conversion.length !== 840) throw new Error(`conversion count ${conversion.length} != 840`);
if (typo.length !== 450) throw new Error(`typo count ${typo.length} != 450`);
if (predictive.length !== 30) throw new Error(`predictive count ${predictive.length} != 30`);

const basic230 = conversion.filter((r) => r.subset === 'basic');
const first480 = conversion.slice(0, 480);
const first740 = conversion.slice(0, 740);
const first300 = typo.slice(0, 300);
if (basic230.length !== 230) throw new Error(`basic ${basic230.length} != 230`);
if (first480.length !== 480) throw new Error(`first480 ${first480.length} != 480`);
if (first740.length !== 740) throw new Error(`first740 ${first740.length} != 740`);
if (first300.length !== 300) throw new Error(`first300 ${first300.length} != 300`);

const outputs = [
  {
    rel: 'windows/frontend/src/data/benchmark840.json',
    content: toJsonArray(conversion),
  },
  {
    rel: 'windows/frontend/src/data/benchmark740.json',
    content: toJsonArray(first740),
  },
  {
    rel: 'windows/frontend/src/data/benchmark480.json',
    content: toJsonArray(first480),
  },
  {
    rel: 'windows/frontend/src/data/benchmark230.json',
    content: toJsonArray(basic230),
  },
  {
    rel: 'windows/frontend/src/data/typo_benchmark450.json',
    content: toJsonArray(typo),
  },
  {
    rel: 'windows/frontend/src/data/typo_benchmark300.json',
    content: toJsonArray(first300),
  },
  {
    rel: 'windows/frontend/src/data/predictive_testset.json',
    content: toJsonArray(predictive),
  },
  {
    rel: 'android/app/src/main/assets/benchmark_dataset.jsonl',
    content: toJsonl(conversion),
  },
  {
    rel: 'android/app/src/main/assets/typo_testset.jsonl',
    content: toJsonl(typo),
  },
  {
    rel: 'android/app/src/main/assets/predictive_testset.jsonl',
    content: toJsonl(predictive),
  },
  {
    rel: 'android/app/src/main/assets/benchmark_parity_cases.jsonl',
    content: parityText.endsWith('\n') ? parityText : parityText + '\n',
  },
];

let stale = [];
for (const out of outputs) {
  const dest = path.join(root, out.rel);
  let current = null;
  try {
    current = await readFile(dest, 'utf8');
  } catch {
    current = null;
  }
  if (current !== out.content) {
    stale.push(out.rel);
    if (!checkOnly) {
      await mkdir(path.dirname(dest), { recursive: true });
      await writeFile(dest, out.content, 'utf8');
      console.log(`wrote ${out.rel}`);
    }
  }
}

if (checkOnly && stale.length > 0) {
  console.error(`stale benchmark outputs:\n  ${stale.join('\n  ')}`);
  console.error('Run: node scripts/sync_benchmarks.mjs');
  process.exit(1);
}

console.log(
  `benchmarks: conversion=${conversion.length} typo=${typo.length} predictive=${predictive.length} parity=${parityCount}`,
);
console.log(checkOnly ? 'benchmarks in sync' : 'benchmarks synced');

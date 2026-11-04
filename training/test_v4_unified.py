# SPDX-License-Identifier: Apache-2.0
import tempfile
import unittest
from pathlib import Path

import copy
import random
import torch
import torch.nn.functional as F
from peft import LoraConfig, get_peft_model
from transformers import GPT2Config, GPT2LMHeadModel

from transformers import AutoTokenizer
from v4_unified_contract import build_tokenizer, V4UnifiedCodec
from train_v4_unified import loss_for, optimizer_for, collate, save_checkpoint, resolve_weights, micro_batches, prepare, disable_dropout
from peft import set_peft_model_state_dict

PARENT = Path('artifacts/checkpoints/standard-v4-nano-transfer-merged')


class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.codec = build_tokenizer(PARENT, Path(cls.temp.name) / 'codec')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_old_ids_and_save_reload(self):
        old = AutoTokenizer.from_pretrained(PARENT, local_files_only=True)
        new = V4UnifiedCodec(Path(self.temp.name) / 'codec')
        self.assertTrue(all(new.tokenizer.get_vocab()[k] == v for k, v in old.get_vocab().items()))
        self.assertEqual(new.ids, self.codec.ids)

    def test_literals_bytes_whitespace_are_lossless(self):
        for text in ('  5　５０\t\n', '</s>[PAD]<task_repair><out>', '\uee00\uee01\uee02',
                     'か\u3099😀𠮷▁\x00', 'https://example.test/a  b?q=x', ''.join(map(chr, range(1, 128)))):
            with self.subTest(text=text):
                ids = self.codec.text(text)
                self.assertEqual(self.codec.decode_answer(ids + [3]), {'action': 'emit', 'text': text})

    def test_inputs_never_take_reference_reading(self):
        row = {'task': 'repair', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'raw': 'argiatou', 'pending': ''},
               'target': {'action': 'emit', 'text': 'ありがとう'}}
        original = self.codec.prompt(row)
        self.assertEqual(original, self.codec.prompt({**row, 'target': {'action': 'keep', 'text': ''}}))
        with self.assertRaises(ValueError):
            self.codec.prompt({**row, 'input': {**row['input'], 'reading': 'ありがとう'}})
        with self.assertRaises(ValueError):
            self.codec.example(row, 4)
        with self.assertRaises(ValueError):
            self.codec.prompt({**row, 'input_method': 'flick'})

    def test_literal_replay_does_not_use_lossy_legacy_teacher(self):
        import json
        directory = Path(self.temp.name) / 'literal-fixture'
        directory.mkdir()
        row = {'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'reading': 'alpha  beta'}, 'target': {'action': 'emit', 'text': 'alpha  beta'},
               'source_id': 'synthetic-literal-copy', 'category': 'v4-anchor', 'legacy_replay': False}
        for split in ('train', 'val'):
            (directory / f'{split}.jsonl').write_text(json.dumps(row) + '\n', encoding='utf-8')
        old = AutoTokenizer.from_pretrained(PARENT, local_files_only=True)
        examples, groups, counts = prepare(directory, self.codec, old, 256)
        self.assertEqual(set(groups['train']), {'anchor_tagged'})
        self.assertNotIn('teacher', examples['train'][0])
        self.assertEqual(counts['train_tagged_only_literal_copy'], 1)

    def test_identical_anchor_weights_have_no_dropout_mismatch(self):
        base = GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=1, n_embd=8, n_head=2,
                                         n_positions=32, bos_token_id=2, eos_token_id=3, pad_token_id=1,
                                         attn_pdrop=.1, embd_pdrop=.1, resid_pdrop=.1))
        teacher = copy.deepcopy(base).eval().requires_grad_(False)
        model = get_peft_model(base, LoraConfig(r=2, lora_dropout=.1, target_modules=['c_attn', 'c_proj']))
        disable_dropout(model)
        model.train()
        self.assertTrue(all(m.p == 0 for m in model.modules() if isinstance(m, torch.nn.Dropout)))
        example = {'input_ids': [10, 20, 21, 3], 'labels': [-100, 20, 21, 3]}
        example['teacher'] = copy.deepcopy(example)
        _, metrics = loss_for(model, teacher, [example], 'cpu', 1.)
        self.assertLess(abs(metrics['kl']), 1e-5)

    def test_invalid_outputs_and_pending(self):
        self.assertEqual(self.codec.decode_answer([self.codec.ids['<out>'], 3])['action'], 'invalid')
        self.assertEqual(self.codec.decode_answer(self.codec.text('予約'))['action'], 'invalid')
        row = {'task': 'predict', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'surface': '予約', 'pending': 'k'},
               'target': {'action': 'emit', 'text': 'します'}}
        with self.assertRaises(ValueError):
            self.codec.answer(row)

    def test_loss_alignment_freezes_teacher_and_old_rows(self):
        torch.manual_seed(42)
        base = GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=2, n_embd=12,
            n_head=2, n_positions=128, bos_token_id=2, eos_token_id=3, pad_token_id=1,
            attn_pdrop=0, embd_pdrop=0, resid_pdrop=0))
        teacher = copy.deepcopy(base).eval().requires_grad_(False)
        old_rows = base.get_input_embeddings().weight.detach().clone()
        base.resize_token_embeddings(6042, mean_resizing=False)
        model = get_peft_model(base, LoraConfig(r=2, lora_alpha=4,
            target_modules=['c_attn', 'c_proj', 'c_fc'], use_dora=True))
        weight = model.get_input_embeddings().weight
        weight.requires_grad_(True)
        weight.register_hook(lambda g: torch.cat((torch.zeros_like(g[:6000]), g[6000:])))
        optimizer = optimizer_for(model, 1e-3)
        examples = [{'input_ids': [6024, 6027, 20, 21, 3], 'labels': [-100, -100, 20, 21, 3],
                     'teacher': {'input_ids': [17, 20, 21, 3], 'labels': [-100, 20, 21, 3]}},
                    {'input_ids': [6025, 75, 6040, 3], 'labels': [-100, -100, 6040, 3]}]
        loss, _ = loss_for(model, teacher, examples, 'cpu', .5)
        self.assertTrue(torch.isfinite(loss))
        loss.backward()
        self.assertTrue(all(p.grad is None for p in teacher.parameters()))
        self.assertTrue(any(p.grad is not None and p.grad.abs().sum() > 0
                            for n,p in model.named_parameters() if 'lora_' in n))
        self.assertGreater(weight.grad[6000:].abs().sum(), 0)
        optimizer.step()
        self.assertTrue(torch.equal(weight[:6000], old_rows))
        bad = copy.deepcopy(examples)
        bad[0]['teacher']['labels'][-2] = 22
        with self.assertRaisesRegex(ValueError, 'alignment'):
            loss_for(model, teacher, bad, 'cpu', .5)

    def test_target_projection_matches_full_forward_loss_and_gradients(self):
        torch.manual_seed(42)
        base = GPT2LMHeadModel(GPT2Config(vocab_size=6042, n_layer=2, n_embd=12,
            n_head=2, n_positions=32, attn_pdrop=0, embd_pdrop=0, resid_pdrop=0))
        model = get_peft_model(base, LoraConfig(r=2, target_modules=['c_attn', 'c_proj', 'c_fc'], use_dora=True))
        examples = [{'input_ids': [6024, 10, 20, 21, 3], 'labels': [-100, -100, 20, 21, 3]},
                    {'input_ids': [6025, 6040, 3], 'labels': [-100, 6040, 3]}]
        compact, _ = loss_for(model, None, examples, 'cpu', 0)
        batch = collate(examples, 'cpu')
        logits = model(input_ids=batch['input_ids'], attention_mask=batch['attention_mask'], use_cache=False).logits[:, :-1].float()
        y = batch['labels'][:, 1:]
        token_loss = F.cross_entropy(logits.transpose(1, 2), y, ignore_index=-100, reduction='none')
        full = (token_loss.sum(1)/(y != -100).sum(1)).mean()
        parameters = [p for p in model.parameters() if p.requires_grad]
        full_grad = torch.autograd.grad(full, parameters)
        compact_grad = torch.autograd.grad(compact, parameters)
        torch.testing.assert_close(compact, full, rtol=1e-6, atol=1e-6)
        for expected, actual in zip(full_grad, compact_grad):
            torch.testing.assert_close(expected, actual, rtol=1e-4, atol=1e-6)

    def test_checkpoint_restores_new_rows_optimizer_and_next_step(self):
        def setup():
            torch.manual_seed(58)
            base = GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=1, n_embd=12,
                n_head=2, n_positions=32, attn_pdrop=0, embd_pdrop=0, resid_pdrop=0))
            base.resize_token_embeddings(6042, mean_resizing=False)
            model = get_peft_model(base, LoraConfig(r=2, target_modules=['c_attn', 'c_proj', 'c_fc'], use_dora=True))
            weight = model.get_input_embeddings().weight
            weight.requires_grad_(True)
            weight.register_hook(lambda g: torch.cat((torch.zeros_like(g[:6000]), g[6000:])))
            return model, optimizer_for(model, 1e-3)

        examples = [{'input_ids': [6025, 12, 6040, 3], 'labels': [-100, -100, 6040, 3]}]
        def step(model, optimizer):
            optimizer.zero_grad(set_to_none=True)
            loss, _ = loss_for(model, None, examples, 'cpu', 0)
            loss.backward()
            optimizer.step()
            return float(loss.detach())

        original, optimizer = setup()
        step(original, optimizer)
        rng = random.Random(52)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'state.pt'
            save_checkpoint(path, original, optimizer, 1, rng, 'owned-test', .5, [], 4)
            saved = torch.load(path, map_location='cpu', weights_only=False)
            resumed, resumed_optimizer = setup()
            set_peft_model_state_dict(resumed, saved['adapter'])
            with torch.no_grad():
                resumed.get_input_embeddings().weight[6000:].copy_(saved['new_rows'])
            resumed_optimizer.load_state_dict(saved['optimizer'])
            restored_rng = random.Random()
            restored_rng.setstate(saved['sample_rng'])
            self.assertEqual(rng.random(), restored_rng.random())
            self.assertEqual(step(original, optimizer), step(resumed, resumed_optimizer))
            for (name, expected), (_, actual) in zip(original.named_parameters(), resumed.named_parameters()):
                if expected.requires_grad:
                    torch.testing.assert_close(actual, expected, rtol=0, atol=0, msg=name)

    def test_repair_first_decision_is_not_diluted_by_a_long_reading(self):
        torch.manual_seed(42)
        base = GPT2LMHeadModel(GPT2Config(vocab_size=6042, n_layer=1, n_embd=12,
            n_head=2, n_positions=64, bos_token_id=2, eos_token_id=3, pad_token_id=1,
            attn_pdrop=0, embd_pdrop=0, resid_pdrop=0))
        model = get_peft_model(base, LoraConfig(r=2, target_modules=['c_attn', 'c_proj', 'c_fc'], use_dora=True))
        examples = [{'input_ids': [6025, 12] + [20]*24 + [3],
                     'labels': [-100, -100] + [20]*24 + [3], 'group': 'repair_emit'},
                    {'input_ids': [6024, 12, 21, 3], 'labels': [-100, -100, 21, 3], 'group': 'convert_tagged'}]
        batch = collate(examples, 'cpu')
        logits = model(input_ids=batch['input_ids'], attention_mask=batch['attention_mask'], use_cache=False).logits[:, :-1].float()
        y = batch['labels'][:, 1:]
        token_loss = F.cross_entropy(logits.transpose(1, 2), y, ignore_index=-100, reduction='none')
        means = token_loss.sum(1)/(y != -100).sum(1)
        expected = ((1-.65)*means[0] + .65*token_loss[0, 1] + means[1])/2
        actual, _ = loss_for(model, None, examples, 'cpu', 0, .65)
        torch.testing.assert_close(actual, expected)
        weights = resolve_weights(.5)
        self.assertAlmostEqual(sum(v for k,v in weights.items() if k.startswith('repair_')), .25)
        self.assertAlmostEqual(sum(v for k,v in weights.items() if k.startswith('repair_emit')), .25*.25)
        self.assertAlmostEqual(weights['repair_emit_corrector'], weights['repair_emit'])
        curriculum = resolve_weights(.5, {'convert': .1, 'repair': .85, 'predict': .05},
                                     {'keep': .3, 'emit': .65, 'abstain': .05})
        self.assertAlmostEqual(sum(curriculum.values()), 1)
        self.assertAlmostEqual(curriculum['repair_emit'] + curriculum['repair_emit_corrector'], .5525)
        self.assertAlmostEqual(curriculum['repair_keep'] + curriculum['repair_keep_corrector'], .255)
        with self.assertRaises(ValueError):
            resolve_weights(.5, {'convert': 0, 'repair': 1, 'predict': 0})
        staged = resolve_weights(.5, {'convert': .4, 'repair': .6, 'predict': 0},
                                 {'keep': .55, 'emit': .4, 'abstain': .05}, allow_zero_tasks=True)
        self.assertNotIn('predict', staged)
        self.assertAlmostEqual(sum(staged.values()), 1.)
        self.assertTrue(all(v > 0 for v in staged.values()))

    def test_observation_and_repair_marker_do_not_take_gold(self):
        table = Path('artifacts/datasets/origin-magpie-full-curated-20261029-r1/source-evidence/defaultRoman2Kana.swift')
        notice = table.parent/'AzooKeyKanaKanjiConverter-MIT-LICENSE'
        codec = build_tokenizer(PARENT, Path(self.temp.name)/'observed', True, table, notice)
        self.assertEqual(codec.ids['<emit>'], 6042)
        self.assertTrue(all(codec.ids[k] == v for k,v in self.codec.ids.items()))
        from keyboard_hybrid import RomanDecoder
        decoder = RomanDecoder(table)
        for raw in ('argiatou', 'kaigishitsu', 'gakkou', 'konnnichiha', 'tei ktu', 'shi', 'si'):
            self.assertEqual(codec.observed_reading(raw, ''), decoder.decode(raw))
        self.assertEqual(codec.observed_reading('arigatok', 'k'), decoder.decode('arigato'))
        with self.assertRaises(ValueError):
            codec.observed_reading('arigatou', 'k')
        row = {'task': 'repair', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'raw': 'argiatou', 'pending': ''},
               'target': {'action': 'emit', 'text': 'ありがとう'}}
        self.assertEqual(codec.prompt(row), codec.prompt({**row, 'target': {'action': 'keep', 'text': ''}}))
        with self.assertRaises(ValueError):
            codec.prompt({**row, 'input': {**row['input'], 'reading': 'ありがとう'}})
        answer = codec.answer(row)
        self.assertEqual(answer[0], codec.ids['<emit>'])
        self.assertEqual(codec.decode_answer(answer, 'repair'), row['target'])
        self.assertEqual(codec.decode_answer(answer, 'convert')['action'], 'invalid')
        self.assertEqual(codec.decode_answer(codec.text('ありがとう')+[3], 'repair')['action'], 'invalid')
        self.assertEqual(codec.decode_answer(codec.text('<emit></s>')+[3]), {'action': 'emit', 'text': '<emit></s>'})

    def test_variable_micro_batches_preserve_all_samples_and_mean_weight(self):
        examples = [{'input_ids': [20]*length, 'value': i} for i,length in enumerate((100, 100, 300, 300, 511))]
        batches = list(micro_batches(examples, 4, 512))
        self.assertEqual([e['value'] for b in batches for e in b], list(range(5)))
        self.assertTrue(all(len(b)*((max(len(e['input_ids']) for e in b)+7)//8*8) <= 512 for b in batches))
        combined = sum(sum(e['value'] for e in b)/len(b) * len(b)/len(examples) for b in batches)
        self.assertEqual(combined, sum(e['value'] for e in examples)/len(examples))

    def test_edit_loss_targets_the_error_not_the_prompt_or_correct_copy(self):
        torch.manual_seed(42)
        base = GPT2LMHeadModel(GPT2Config(vocab_size=6043, n_layer=1, n_embd=12,
            n_head=2, n_positions=32, bos_token_id=2, eos_token_id=3, pad_token_id=1,
            attn_pdrop=0, embd_pdrop=0, resid_pdrop=0))
        model = get_peft_model(base, LoraConfig(r=2, target_modules=['c_attn', 'c_proj', 'c_fc'], use_dora=True))
        example = {'input_ids': [6025, 6042, 20, 21, 3], 'labels': [-100, 6042, 20, 21, 3],
                   'group': 'repair_emit', 'repair_edits': [(2, 22)]}
        baseline, _ = loss_for(model, None, [example], 'cpu', 0, .65, 0)
        batch = collate([example], 'cpu')
        full = model(input_ids=batch['input_ids'], attention_mask=batch['attention_mask'], use_cache=False).logits.float()
        error = full[0, 2]
        expected = baseline + F.cross_entropy(error[None], torch.tensor([21])) + .25*F.relu(2.-(error[21]-error[22]))
        actual, metrics = loss_for(model, None, [example], 'cpu', 0, .65, 1.)
        torch.testing.assert_close(actual, expected)
        self.assertGreater(metrics['edit'], 0)
        actual.backward()
        self.assertTrue(any(p.grad is not None and p.grad.abs().sum() > 0 for p in model.parameters() if p.requires_grad))
        with self.assertRaisesRegex(ValueError, 'restricted'):
            loss_for(model, None, [{**example, 'group': 'convert_tagged'}], 'cpu', 0, .65, 1.)


if __name__ == '__main__':
    unittest.main()

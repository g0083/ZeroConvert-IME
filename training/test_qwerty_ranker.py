"""Check ranking gradients, observation-only prompts and strict preservation."""
import unittest
import torch
from transformers import AutoTokenizer
from qwerty_ranker import calibrate, encode_candidates, ranking_loss, select, sequence_scores


class RankerTests(unittest.TestCase):
    def test_ranking_gradient(self):
        scores=torch.tensor([-2.,-1.,-3.,-4.],requires_grad=True)
        ranking_loss(scores).backward()
        self.assertLess(scores.grad[0],0)
        self.assertTrue((scores.grad[1:]>0).all())

    def test_mask_and_length_normalization(self):
        logits=torch.zeros(2,4,5,requires_grad=True)
        labels=torch.tensor([[-100,-100,2,3],[-100,-100,-100,3]])
        scores=sequence_scores(logits,labels)
        self.assertTrue(torch.allclose(scores,torch.full((2,),-torch.log(torch.tensor(5.)).item())))
        scores.sum().backward()
        self.assertEqual(float(logits.grad[:,0].abs().sum()),0.)

    def test_observable_prompt_and_common_prefix(self):
        tok=AutoTokenizer.from_pretrained("artifacts/checkpoints/standard-v4-nano-transfer-merged",local_files_only=True)
        row={"context":"前の文章"*100,"observed_keys":"ksigi","native_observed":"kしぎ","input_state":"append","cursor_key_offset":5}
        candidates=[{"reading":"かいぎ"},{"reading":"かいぎのじかん"}]
        values=encode_candidates(tok,row,candidates)
        self.assertEqual(values,encode_candidates(tok,{**row,"expected":"SECRET","target_reading":"secret","canonical_keys":"secret"},candidates))
        self.assertEqual(values[0]["prefix_length"],values[1]["prefix_length"])
        n=values[0]["prefix_length"]
        self.assertEqual(values[0]["input_ids"][:n],values[1]["input_ids"][:n])
        self.assertTrue(all(len(v["input_ids"])<=256 and v["input_ids"][-1]==3 for v in values))

    def test_margin_and_validation_cap(self):
        candidates=[{"reading":"あ","unchanged":True},{"reading":"い","unchanged":False}]
        self.assertIsNone(select(candidates,[-2.,-1.],1.))
        self.assertEqual(select(candidates,[-2.,-1.],.9),1)
        rows=[{"id":"a","expected_action":"keep","target_reading":"あ"}]
        best,_=calibrate(rows,[{"id":"a","candidates":candidates,"scores":[-2.,-1.]}])
        self.assertEqual(best["metrics"]["keep_proposed"],0)


if __name__=="__main__": unittest.main()

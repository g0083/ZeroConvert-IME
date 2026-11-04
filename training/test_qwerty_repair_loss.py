import unittest
import torch
from transformers import AutoTokenizer
from qwerty_repair_loss import encode_repair,repair_loss


class RepairLossTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tokenizer=AutoTokenizer.from_pretrained("artifacts/checkpoints/nano-v4-argon4-merged",local_files_only=True)

    def test_contrast_uses_first_identical_prefix_and_normal_is_not_negative(self):
        tokenizer=self.tokenizer
        for target in ("E:ksi:kai","E:aaa:aa","E:abc:abac"):
            encoded=encode_repair(tokenizer,"\uee00テスト\uee01",target,384,3.)
            indexes=[i for i,x in enumerate(encoded["anti_labels"]) if x!=-100]
            self.assertEqual(len(indexes),1)
            i=indexes[0]
            self.assertNotEqual(encoded["labels"][i],encoded["anti_labels"][i])
            self.assertEqual(encoded["token_weights"][i],3.)
            answer=tokenizer.encode(target,add_special_tokens=False)+[3]
            old=target.split(":")[1]
            identity=tokenizer.encode(f"E:{old}:{old}",add_special_tokens=False)+[3]
            offset=len(encoded["input_ids"])-len(answer)
            self.assertEqual(answer[:i-offset],identity[:i-offset])
        for target in ("K","W"):
            encoded=encode_repair(tokenizer,"\uee00テスト\uee01",target,384,3.)
            self.assertTrue(all(x==-100 for x in encoded["anti_labels"]))
            self.assertTrue(all(w==1. for w,label in zip(encoded["token_weights"],encoded["labels"]) if label!=-100))

    def test_margin_pushes_gold_up_and_copy_down_without_prompt_gradients(self):
        logits=torch.zeros((1,5,8),requires_grad=True)
        labels=torch.tensor([[-100,-100,-100,5,3]])
        weights=torch.tensor([[0.,0.,0.,3.,1.]])
        anti=torch.tensor([[-100,-100,-100,6,-100]])
        baseline=repair_loss(logits,labels,weights,anti,0.)
        margin=repair_loss(logits,labels,weights,anti,.2)
        (margin-baseline).backward()
        self.assertLess(logits.grad[0,2,5].item(),0.)
        self.assertGreater(logits.grad[0,2,6].item(),0.)
        self.assertEqual(logits.grad[0,:2].abs().sum().item(),0.)
        self.assertEqual(logits.grad[0,3:].abs().sum().item(),0.)
        self.assertTrue(torch.isfinite(margin))

    def test_empty_and_no_negative_supervision(self):
        logits=torch.randn((2,3,8),requires_grad=True)
        empty=torch.full((2,3),-100)
        self.assertEqual(repair_loss(logits,empty,torch.zeros((2,3)),empty,.2).item(),0.)
        labels=torch.tensor([[-100,5,3],[-100,6,3]])
        a=repair_loss(logits,labels,torch.ones((2,3)),empty,0.)
        b=repair_loss(logits,labels,torch.ones((2,3)),empty,.2)
        self.assertEqual(a.item(),b.item())
        scaled=repair_loss(logits,labels,torch.full((2,3),3.),empty,0.)
        self.assertAlmostEqual(a.item(),scaled.item(),places=6)


if __name__=="__main__":unittest.main()

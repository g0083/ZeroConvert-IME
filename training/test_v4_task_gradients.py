import unittest
import torch
from v4_task_gradients import combine, backward_tasks


class GradientTests(unittest.TestCase):
    def test_conflict_removed_and_primary_retained(self):
        p = torch.nn.Parameter(torch.zeros(2))
        c, a = torch.tensor([2., 0.]), torch.tensor([-3., 4.])
        stats = combine([p], [c], [a], True)
        self.assertTrue(torch.allclose(p.grad, torch.tensor([2., 4.])))
        self.assertLess(stats['task_dot'], 0)

    def test_nonconflict_and_control_equal_sum(self):
        p = torch.nn.Parameter(torch.zeros(2))
        for project in (True, False):
            combine([p], [torch.ones(2)], [torch.tensor([2., 3.])], project)
            self.assertTrue(torch.equal(p.grad, torch.tensor([3., 4.])))
        combine([p], [torch.ones(2)], [-torch.ones(2)], False)
        self.assertTrue(torch.equal(p.grad, torch.zeros(2)))

    def test_zero_and_unused_gradients(self):
        ps = [torch.nn.Parameter(torch.zeros(2)) for _ in range(3)]
        combine(ps, [None, torch.zeros(2), None], [torch.ones(2), None, None], True)
        self.assertTrue(torch.equal(ps[0].grad, torch.ones(2)))
        self.assertTrue(torch.equal(ps[1].grad, torch.zeros(2)))
        self.assertIsNone(ps[2].grad)

    def test_nonfinite_rejected(self):
        p = torch.nn.Parameter(torch.zeros(1))
        with self.assertRaises(FloatingPointError):
            combine([p], [torch.tensor([float('nan')])], [torch.ones(1)], True)

    def test_task_split_preserves_effective_batch_gradient(self):
        from train_v4_unified import micro_batches
        model = torch.nn.Linear(2, 1, bias=False)
        examples = [{'group': group, 'input_ids': [1], 'x': torch.tensor(x), 'y': y}
                    for group, x, y in [('convert_tagged', [1., 2.], 1.),
                    ('repair_emit', [-1., 3.], 2.), ('anchor_legacy', [2., 0.], -1.),
                    ('predict', [4., 1.], 3.), ('repair_keep', [0., 1.], 0.)]]
        def loss_for(m, teacher, batch, device, *args):
            loss = torch.stack([(m(e['x']).squeeze()-e['y']).square() for e in batch]).mean()
            return loss, {'ce': float(loss.detach()), 'kl': 0., 'edit': 0., 'tokens': len(batch)}
        loss_for(model, None, examples, 'cpu')[0].backward()
        expected = model.weight.grad.clone()
        backward_tasks(model, None, examples, 2, 0, len(examples), 1., 0., 0., loss_for, micro_batches, False)
        self.assertTrue(torch.allclose(model.weight.grad, expected, atol=1e-6))


if __name__ == '__main__':
    unittest.main()

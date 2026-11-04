"""Accumulate task gradients before clipping; project auxiliary conflicts in FP32."""
import torch


def combine(parameters, primary, auxiliary, project=False):
    device = parameters[0].device
    dot = torch.zeros((), device=device)
    norm = torch.zeros_like(dot)
    aux_norm = torch.zeros_like(dot)
    for p, c, a in zip(parameters, primary, auxiliary):
        if c is not None:
            norm += c.float().square().sum()
        if a is not None:
            aux_norm += a.float().square().sum()
        if c is not None and a is not None:
            dot += (c.float() * a.float()).sum()
    if not torch.isfinite(torch.stack([dot, norm, aux_norm])).all():
        raise FloatingPointError('Nonfinite task gradient')
    coefficient = dot.clamp(max=0) / norm.clamp_min(1e-20) if project else dot * 0
    for p, c, a in zip(parameters, primary, auxiliary):
        if c is None and a is None:
            p.grad = None
        else:
            p.grad = (c if c is not None else torch.zeros_like(p)) + (a if a is not None else 0)
            if c is not None:
                p.grad = p.grad - coefficient * c
    return {'task_dot': float(dot), 'convert_grad_norm': float(norm.sqrt()),
            'auxiliary_grad_norm': float(aux_norm.sqrt()), 'projection_coefficient': float(coefficient)}


def backward_tasks(model, teacher, sampled, micro, token_budget, effective_batch,
                   kl_weight, action_weight, edit_weight, loss_for, micro_batches, project):
    parameters = [p for p in model.parameters() if p.requires_grad]
    accumulated, totals = [], {'ce': 0., 'kl': 0., 'edit': 0., 'loss': 0., 'tokens': 0}
    for convert in (True, False):
        model.zero_grad(set_to_none=True)
        selected = [e for e in sampled if e['group'].startswith(('anchor_', 'convert_')) == convert]
        for subset in micro_batches(selected, micro, token_budget):
            loss, metrics = loss_for(model, teacher, subset, parameters[0].device, kl_weight, action_weight, edit_weight)
            if not torch.isfinite(loss):
                raise FloatingPointError('Nonfinite task loss')
            (loss * len(subset) / effective_batch).backward()
            totals['loss'] += float(loss.detach()) * len(subset)
            for key in ('ce', 'kl', 'edit'):
                totals[key] += metrics[key] * len(subset)
            totals['tokens'] += metrics['tokens']
        accumulated.append([p.grad.detach().clone() if p.grad is not None else None for p in parameters])
    stats = combine(parameters, *accumulated, project=project)
    return totals, stats

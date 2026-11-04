# SPDX-License-Identifier: Apache-2.0
"""Training-only alignment of erroneous observed copies to reference tokens.

These annotations are labels, never prompt fields or inference-time features.
Deletion boundaries supervise the next correct token (including EOS). Identity
copies get no edit supervision. BPE alignment is an operational proxy, not a
claim of unique linguistic edit alignment.
"""
from difflib import SequenceMatcher


def repair_edit_annotations(codec, row):
    if row['task'] != 'repair' or row['target']['action'] != 'emit':
        return []
    observed = codec.observed_reading(row['input']['raw'], row['input']['pending'])
    wrong, correct = codec.text(observed), codec.text(row['target']['text'])
    offset = int(codec.metadata.get('repair_emit_marker', False))
    annotations = {}
    for operation, a, b, c, d in SequenceMatcher(a=wrong, b=correct, autojunk=False).get_opcodes():
        if operation == 'equal':
            continue
        positions = range(c, d) if c < d else [c]
        for j in positions:
            wrong_index = min(a + (j-c), b-1) if a < b else a
            negative = wrong[wrong_index] if wrong_index < len(wrong) else codec.eos_id
            target = correct[j] if j < len(correct) else codec.eos_id
            annotations[j+offset] = negative if negative != target else -1
    return sorted(annotations.items())

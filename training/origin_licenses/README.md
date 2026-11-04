# ZeroCon-Origin license records

Origin's new Python files carry `SPDX-License-Identifier: Apache-2.0`.
`Apache-2.0.txt` is the complete official license retrieved from
https://www.apache.org/licenses/LICENSE-2.0.txt on 2026-10-29.
This does not change the license of other repository files.

`sentencepiece-LICENSE.txt` was retrieved from the adopted release:
https://raw.githubusercontent.com/google/sentencepiece/v0.2.2/LICENSE
on 2026-10-29. The 0.2.2 Windows wheel does not include a LICENSE file.
This separate copy retains the official license text.

Runs also copy LICENSE/NOTICE files supplied by the installed Transformers,
PyTorch and safetensors distributions. Their exact versions are recorded in
run-manifest.json. These dependencies have their own notices; the model's
license does not replace them. Source records must explicitly document approval
for training and Apache-2.0 weight distribution before importing real corpora
or teacher output. Review metadata is a gate, not an automatic legal opinion.

Fixture data is task-authored synthetic text for wiring tests. No external
corpus, model weights, vocabulary or external teacher outputs are included.
Fixture-trained weights are experimental and unsuitable as a replacement IME.

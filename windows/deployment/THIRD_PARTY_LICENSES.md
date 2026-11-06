# Third-Party Licenses & Notices (ZeroCon IME for Windows)

ZeroCon IME (Windows) incorporates and builds upon the following open-source software, dictionaries, and machine learning models. We gratefully acknowledge the authors and contributors of these projects.

---

## 1. azooKey-Windows & AzooKeyKanaKanjiConverter
- **Upstream Authors**: batao (azooKey-Windows), ensan-hcl / Miwa Keita (AzooKeyKanaKanjiConverter)
- **License**: MIT License

```text
MIT License

Copyright (c) 2024 batao (azooKey-Windows)
Copyright (c) 2023 ensan-hcl (AzooKeyKanaKanjiConverter)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Zenz / zenz-v3.2 Conversion and Prediction Models (ZeroCon Model Nano v4)
- **Upstream Model**: `Miwa-Keita/zenz-v3.2-xsmall` (base), with a fine-tuned `Miwa-Keita/zenz-v3.2-small` teacher
- **Author**: Miwa Keita (ensan-hcl)
- **License**: Apache License 2.0
- **Notice**:
  - `models/zerocon-nano-v4-iq4nl-emb8.gguf` and `models/zerocon-nano-v4-q8_0.gguf` are modified derivatives of `Miwa-Keita/zenz-v3.2-xsmall`, trained with rank-64 DoRA and target-corrected dual-anchor distillation from a fine-tuned Standard v3 teacher.
  - The IQ4_NL variant blends the learned update at 85% and protects input/output tensors with Q8_0. The Q8_0 variant uses the full learned update.
  - Both models are distributed under the terms of the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

---

## 3. llama.cpp / ggml Inference Runtime (`llama.dll`, `ggml*.dll`)
- **Author**: Georgi Gerganov and contributors
- **License**: MIT License

```text
MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

## 4. Dictionary & Emoji Dictionary Data (`Dictionary/`, `EmojiDictionary/`)
- **Sources**:
  - azooKey Dictionary Storage (Apache License 2.0 / BSD-3-Clause)
  - Google Mozc Dictionary Data (BSD 3-Clause License, Copyright 2010-2018 Google Inc.)
  - IPAdic (`mecab-ipadic`, ICOT / NAIST license)
  - Unicode Emoji Data (`emoji_all_E15.1.txt`, `emoji_catalog.tsv`, Unicode License v3)
  - Keyboard Typo Dictionary & Rewrite Data (see `keyboard-typo-dictionary-NOTICE.md` and `keyboard-typo-rewrite-NOTICE.md`)


---

## 5. corecctor / ZeroCon Model Corrector (`models/zerocon-model-corrector.gguf`)
- **Base Model**: `zenz-v3.2-xsmall` (self-contained reconstruction from upstream `zenz-v3.2-xsmall-q5km.gguf`)
- **Original Author**: Miwa Keita (ensan-hcl)
- **Base License**: Apache License, Version 2.0
- **Derivative License**: Apache License, Version 2.0
- **Notice**:
  - `corecctor` is the independently fine-tuned Japanese input typo correction model, distributed as ZeroCon Model Corrector (`zerocon-model-corrector.gguf`; legacy filename: `typo-corrector-xsmall-q4km.gguf`). It is fine-tuned from `zenz-v3.2-xsmall` (Apache-2.0) specifically for Japanese input typo correction, quantized to Q4_K_M.
  - Distributed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

---

## 5. PLaMo 3 Translate (Translation Model)
- **Model**: `pfnet/plamo-3-translate-0.8b` / `plamo-3-translate-2b` (quantized GGUF)
- **Author**: Preferred Networks, Inc. (PFN), built on PLaMo 3 NICT (joint research with NICT)
- **License**: PLaMo Community License
- **Notice**:
  - Used for on-device translation features.
  - Free for personal, academic, and non-commercial use without registration. Commercial use is free for individuals/organizations with annual revenue of JPY 1 billion or less in the most recent fiscal year, subject to advance registration via PFN's form. Commercial use above that threshold requires a separate license agreement with PFN.
  - Full license text: https://huggingface.co/pfnet (PLaMo Community License).

# Third-Party Open Source Licenses — ZeroCon Keyboard

ZeroCon Keyboard incorporates the following open-source software, dictionaries, and neural network models. We gratefully acknowledge the authors and contributors of these projects.

---

## 1. azooKey / AzooKeyKanaKanjiConverter
- **Copyright**: Copyright (c) 2023-2026 Miwa (ensan-hcl) and contributors
- **License**: MIT License
- **Repository**: https://github.com/ensan-hcl/AzooKeyKanaKanjiConverter

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

---

## 2. ZeroCon Model Nano v4 / Zenz Neural Kana-Kanji Conversion Models
- **Modifications**: rank-64 DoRA, target-corrected dual-anchor distillation and self-copy/EOS margin training from zenz-v3.2-xsmall, using a fine-tuned Standard v3 teacher.
- **Bundled variants**: `zerocon-nano-v4-iq4nl-emb8.gguf` (Blend85, IQ4_NL with Q8_0 input/output) and `zerocon-nano-v4-q8_0.gguf` (full merge).
- **License of these derivatives**: Apache License 2.0
- **Author**: Miwa Keita (ensan-hcl)
- **License**: Apache License 2.0
- **URL**: https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf
- **License Terms**: http://www.apache.org/licenses/LICENSE-2.0

---

## 3. LOUDS System Dictionary & Emoji Dictionary
- **Authors**: Miwa (ensan-hcl) / azooKey Project (derived from Mozc & Unicode Emoji Annotations)
- **License**: Apache License 2.0
- **License Terms**: http://www.apache.org/licenses/LICENSE-2.0

---

## 4. llama.cpp
- **Copyright**: Copyright (c) 2023-2026 Georgi Gerganov and contributors
- **License**: MIT License
- **Repository**: https://github.com/ggerganov/llama.cpp

## 5. PLaMo 3 Translate (Translation Model)
- Model: pfnet/plamo-3-translate-0.8b / plamo-3-translate-2b (quantized GGUF)
- Author: Preferred Networks, Inc. (PFN), built on PLaMo 3 NICT
- License: PLaMo Community License
- Notice: Free for personal/academic/non-commercial use. Commercial use free for individuals/organizations with annual revenue <= JPY 1 billion (advance registration required). Commercial use above threshold requires separate license agreement with PFN.
- Android download: The Android app does not bundle this model. After the user confirms in the settings screen, it downloads `pfnet/plamo-3-translate-0.8b-GGUF` revision `03891e907db8a43c97d3a7c75add125cc6d1f96a` (`plamo-3-translate-0.8b-Q4_K_M.gguf`, SHA-256 `3dbb09295bfd3350231ba0dba92bc72cb14111ef1b034e37dad39d581bd86b9c`).
- Repository: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF
- License Terms: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/blob/main/LICENSE/ja

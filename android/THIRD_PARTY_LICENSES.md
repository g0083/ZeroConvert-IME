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

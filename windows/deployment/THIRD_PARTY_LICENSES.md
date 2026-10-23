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

## 2. Zenz / zenz-v3.2 Conversion and Prediction Models (ZeroCon Model Standard / Nano)
- **Upstream Model**: `Miwa-Keita/zenz-v3.2-small` (Standard) / `Miwa-Keita/zenz-v3.2-xsmall` (Nano)
- **Author**: Miwa Keita (ensan-hcl)
- **License**: Apache License 2.0
- **Notice**:
  - The conversion and prediction weights (`models/zerocon-model-standard.gguf` and `models/zerocon-model-nano.gguf`) are Q4_K_M quantizations of the respective upstream models and distributed under the terms of the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

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
  - Unicode Emoji Data (`emoji_all_E15.1.txt`, Unicode License v3)
  - Keyboard Typo Dictionary & Rewrite Data (see `keyboard-typo-dictionary-NOTICE.md` and `keyboard-typo-rewrite-NOTICE.md`)


---

## 5. corecctor / ZeroCon Model Corrector (`models/zerocon-model-corrector.gguf`)
- **Base Model**: [Miwa-Keita/zenz-v3.1-xsmall](https://huggingface.co/Miwa-Keita/zenz-v3.1-xsmall)
- **Original Author**: Miwa Keita (ensan-hcl)
- **Base License**: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
- **Derivative License**: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
- **Notice**:
  - `corecctor` is the independently fine-tuned Japanese input typo correction model, distributed as ZeroCon Model Corrector (`zerocon-model-corrector.gguf`; legacy filename: `typo-corrector-xsmall-q4km.gguf`). It is fine-tuned from `Miwa-Keita/zenz-v3.1-xsmall` specifically for Japanese input typo correction, quantized to Q4_K_M.
  - In accordance with CC BY-SA 4.0, this derivative model is distributed under the terms of the [CC BY-SA 4.0 License](https://creativecommons.org/licenses/by-sa/4.0/).

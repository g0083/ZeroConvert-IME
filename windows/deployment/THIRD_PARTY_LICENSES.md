# Third-Party Licenses & Notices (ZeroCon IME for Windows)

ZeroCon IME (Windows) incorporates and builds upon the following open-source software, dictionaries, and machine learning models. We gratefully acknowledge the authors and contributors of these projects.

Full license texts are bundled in this directory: `LICENSE-Apache-2.0.txt`, `LICENSE-Unicode.txt`, `LICENSE-Swift.txt`, and the generated crate/package lists `THIRD_PARTY_RUST.md` and `THIRD_PARTY_FRONTEND.md`.

The list shown in the settings app is rendered from `licenses/third-party.json` at the repository root, the same file the Android settings screen uses. Keep entry titles and license names there, and use this document for the legal notices.

---

## 1. azooKey-Windows & AzooKeyKanaKanjiConverter

- **Upstream Authors**: fkunn1326 (azooKey-Windows; this product is also derived from the batao9 fork), Miwa / Ensan (AzooKeyKanaKanjiConverter)
- **License**: MIT License

```text
MIT License

Copyright (c) 2026 fkunn1326 (azooKey-Windows)
Copyright (c) 2023 Miwa / Ensan (AzooKeyKanaKanjiConverter)

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

## 2. Zenz / zenz-v3.2 Conversion and Prediction Models (ZeroCon Model v4.2b)

- **Upstream Model**: `Miwa-Keita/zenz-v3.2-xsmall` (base), with a fine-tuned `Miwa-Keita/zenz-v3.2-small` teacher
- **Author**: Miwa Keita (ensan-hcl)
- **License of the upstream base used for training**: Apache License 2.0 (`LICENSE-Apache-2.0.txt`). [Pinned xsmall revision](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf/raw/4f5423f0fad41a73b1242eb96fe5c12ae4fdca83/README.md) · [Pinned small revision](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/raw/c67e03e07d215c869f591b274c1631170d3e11fe/README.md)
- **ZeroCon modifications**: ZeroCon's terms reserve rights in its own modifications where no separate permission has been granted. Rights already granted under third-party licenses continue to apply.
- **Notice**:
  - `models/zerocon-nano-v4.2b-iq4nl-emb8.gguf`, `models/zerocon-nano-v4.2b-q8_0.gguf`, `models/zerocon-standard-v4.2b-iq4nl-emb8.gguf`, `models/zerocon-standard-v4.2b-q8_0.gguf` are modified derivatives of `Miwa-Keita/zenz-v3.2-xsmall` (nano) / `Miwa-Keita/zenz-v3.2-small` (standard), trained with rank-64 DoRA, target-corrected dual-anchor distillation, v4.2b long-context / SuperDistill refinement, and conversational/anti-over-kanji, additional-dictionary vocabulary & numeral robustness tuning (`nam` digit-masking compatibility). The weights are fetched on demand from the pinned GitHub release and are not redistributed with the bundled installer/APK.
  - The IQ4_NL variant protects input/output tensors with Q8_0 (`IQ4_NL` body with `Q8_0` embeddings/output). The Q8_0 variant preserves full precision quantization across all tensors.

---

## 3. llama.cpp / ggml Runtime and Tools

- **Author**: Georgi Gerganov and contributors
- **License**: MIT License
- **Bundled**: `llama_cpu/`, `llama_vulkan/` (`llama-server.exe`, `llama-cli.exe`, `llama-bench.exe`, `ggml-rpc-server.exe`, `llama.dll`, `ggml*.dll`), statically linked by `keyboard-prior.exe`

```text
MIT License

Copyright (c) 2023-2024 The ggml authors
Copyright (c) 2023-2024 Georgi Gerganov and llama.cpp contributors

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

## 4. LLVM OpenMP (`llama_cpu/libomp.dll`, `llama_vulkan/libomp.dll`)

- **Author**: The LLVM Project
- **License**: Apache License 2.0 with LLVM Exceptions
- **Notice**: The upstream notice is bundled unchanged as `llama_cpu/LICENSE-LLVM-OpenMP`; the base license text is `LICENSE-Apache-2.0.txt`.
- **Repository**: https://github.com/llvm/llvm-project

---

## 5. Swift Runtime and Foundation

- **Author**: Apple Inc. and the Swift project authors
- **License**: Apache License 2.0 with Runtime Library Exception (`LICENSE-Swift.txt`)
- **Bundled**: `swiftCore.dll`, `swiftCRT.dll`, `swiftWinSDK.dll`, `swift_Concurrency.dll`, `swift_StringProcessing.dll`, `swift_RegexParser.dll`, `swiftDistributed.dll`, `swiftObservation.dll`, `swiftSynchronization.dll`, `swift_Differentiation.dll`, `swift_Volatile.dll`, `swiftRemoteMirror.dll`, `swiftSwiftOnoneSupport.dll`, `swift_Builtin_float.dll`, `Foundation.dll`, `FoundationEssentials.dll`, `FoundationInternationalization.dll`, `FoundationNetworking.dll`, `FoundationXML.dll`, `dispatch.dll`, `BlocksRuntime.dll`, `swiftDispatch.dll`, and `plutil.exe`
- **Notice**: The Runtime Library Exception waives the attribution required by Sections 4(a), 4(b), and 4(d) for programs compiled with this software. The license text is bundled regardless because the runtime libraries themselves are redistributed.
- **Repository**: https://github.com/swiftlang/swift

---

## 6. ICU (`_FoundationICU.dll`)

- **Author**: Unicode, Inc. and ICU contributors
- **License**: Unicode License v3 (`LICENSE-Unicode.txt`)
- **Repository**: https://github.com/unicode-org/icu

---

## 7. Dictionary & Emoji Dictionary Data (`Dictionary/`, `EmojiDictionary/`)

- **azooKey Dictionary Storage / Emoji Dictionary Storage**: Apache License 2.0 (`LICENSE-Apache-2.0.txt`). Copyright (c) Miwa / Ensan and contributors.
- **Google Mozc dictionary data and keyboard typo rewrite rules**: BSD 3-Clause License. The rewrite rules and validation follow [Mozc KeyCorrector](https://github.com/google/mozc/blob/851c3fe33060d2a6090363e4d7ec44fafde2c03d/src/converter/key_corrector.cc).
- **Unicode Emoji Data** (`emoji_all_E15.1.txt`, `emoji_catalog.tsv`): Unicode License v3 (`LICENSE-Unicode.txt`).
- **Additional vocabulary**: Selected entries from SudachiDict Full 20260723 (Works Applications Co., Ltd.) were extracted and converted to azooKey's readings, part-of-speech IDs, and scores. The expansion also contains Wikidata structured data (CC0 1.0). The installer ships `AdditionalDictionaryNotices/` with the full Apache License 2.0 text, upstream `LEGAL` (including UniDic and NEologd), and Wikidata attribution. The downloaded dictionary carries those documents under `Dictionary/AdditionalDictionaryNotices/`.

Mozc BSD 3-Clause text applies to the Mozc-derived dictionary data and rewrite rules:

```text
Copyright 2010-2021, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## 8. Settings UI (`ui.exe`, `frontend.exe`)

The bundled web assets are built from the Tauri application in `windows/frontend`. The complete package list, with versions, licenses, and upstream repositories, is generated into `THIRD_PARTY_FRONTEND.md`.

- **Tauri**: MIT License OR Apache License 2.0. Copyright 2017-2024 Tauri Programme within The Commons Conservancy. https://github.com/tauri-apps/tauri
- **React / React DOM**: MIT License. Copyright (c) Meta Platforms, Inc. and affiliates.
- **Radix UI primitives**: MIT License. Copyright (c) 2022 WorkOS.
- **Tailwind CSS**: MIT License. Copyright (c) Tailwind Labs, Inc.
- **lucide-react**: ISC License. Copyright (c) Lucide Contributors.
- **sonner**: MIT License. Copyright (c) 2023 Emil Kowalski.
- **next-themes**: MIT License. Copyright (c) 2021 Paco Coursey.
- **react-router**: MIT License. Copyright (c) React Training 2015-2019, Remix Software 2020-2024.
- **clsx**: MIT License. Copyright (c) Luke Edwards.
- **tailwind-merge**: MIT License. Copyright (c) 2023 Dany Castillo.
- **class-variance-authority**: Apache License 2.0. Copyright (c) Joe Bell (`LICENSE-Apache-2.0.txt`).
- **lightningcss** (build-time CSS processing): Mozilla Public License 2.0. Source is available at https://github.com/parcel-bundler/lightningcss.

MIT License text for the packages above:

```text
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

ISC License text (lucide-react and other ISC packages):

```text
Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

---

## 9. Rust Crates (`azookey.dll`, `azookey32.dll`, `azookey-server.exe`, `launcher.exe`, `ui.exe`, `frontend.exe`)

The complete crate list, with versions and license expressions, is generated into `THIRD_PARTY_RUST.md`. All listed crates are statically linked into the shipped binaries.

- Most crates are MIT and/or Apache License 2.0 (`LICENSE-Apache-2.0.txt`).
- Unicode-3.0 crates (ICU4X family): Unicode License v3 (`LICENSE-Unicode.txt`).
- Mozilla Public License 2.0 crates (for example `cssparser`, `selectors`, `option-ext`): source is available at the upstream repository recorded by crates.io. Modifications to those files, if any, are not published as separate files; rebuild from https://crates.io to obtain the corresponding source.
- Regenerate after any `Cargo.lock` change:
  `powershell -NoProfile -ExecutionPolicy Bypass -File windows/scripts/generate-rust-license-notice.ps1`

---

## 10. Microsoft Redistributables

- **Microsoft Visual C++ Runtime** (`msvcp140*.dll`, `vcruntime140*.dll`, `concrt140.dll`, `vccorlib140.dll`): redistributed under the Microsoft Visual Studio license terms, not an open-source license.
- **Microsoft Edge WebView2 Runtime**: required by the settings UI. Installed as a system component when absent; it is not redistributed inside this package.

---

## 11. PLaMo 3 Translate (Translation Model, Downloaded on Demand)

- **Model**: `pfnet/plamo-3-translate-0.8b` / `plamo-3-translate-2b` (quantized GGUF)
- **Author**: Preferred Networks, Inc. (PFN), built on PLaMo 3 NICT (joint research with NICT)
- **License**: PLaMo Community License
- **Notice**:
  - Not bundled with this package. When translation input is enabled, the settings app downloads the pinned GGUF from Hugging Face.
  - Free for personal, academic, and non-commercial use without registration. Commercial use is free for individuals/organizations with annual revenue of JPY 1 billion or less in the most recent fiscal year, subject to advance registration via PFN's form. Commercial use above that threshold requires a separate license agreement with PFN.
  - Full license text: https://huggingface.co/pfnet (PLaMo Community License).

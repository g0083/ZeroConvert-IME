# Third-Party Open Source Licenses — ZeroCon Keyboard

ZeroCon Keyboard incorporates the following open-source software, dictionaries, and neural network models. We gratefully acknowledge the authors and contributors of these projects.

Full license texts are bundled in the app at `app/src/main/assets/licenses/` (installed as `assets/licenses/*.txt`), together with the notice files referenced below.

The list shown in the settings screen is rendered from `licenses/third-party.json` at the repository root, the same file the Windows settings app uses. Gradle bundles it as `assets/licenses/third-party.json`; do not add a separate hand-written list in the Android UI.

---

## 1. azooKey / AzooKeyKanaKanjiConverter
- **Copyright**: Copyright (c) 2023-2026 Miwa (ensan-hcl) and contributors
- **License**: MIT License
- **Repository**: https://github.com/ensan-hcl/AzooKeyKanaKanjiConverter
- **Notice**: `app/src/main/assets/licenses/NOTICE-MIT.txt`

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

---

## 1b. azooKey-Windows
- **Copyright**: Copyright (c) 2026 fkunn1326 (per the LICENSE file in the Windows source tree; the project is also derived from the batao9/azooKey-Windows fork)
- **License**: MIT License
- **Repository**: https://github.com/fkunn1326/azooKey-Windows
- **Notice**: `app/src/main/assets/licenses/NOTICE-MIT.txt`
- **Usage**: The Android engine is a port of the Windows front end, which is derived from azooKey-Windows. The ported input state machine and romaji table are in `app/src/main/java/com/zeroconvert/ime/azookey/input/`.

Distributed under the same MIT terms reproduced in section 1.

---

## 2. ZeroCon Model Nano v4.1 / Zenz Neural Kana-Kanji Conversion Models
- **Modifications**: rank-64 DoRA, target-corrected dual-anchor distillation and self-copy/EOS margin training from zenz-v3.2-xsmall using a fine-tuned Standard v3 teacher, plus v4.1 conversational/anti-over-kanji & numeral (`nam` digit-masking) refinement.
- **Acquired variants**: `zerocon-nano-v4.1-iq4nl-emb8.gguf` / `zerocon-nano-v4.1-q8_0.gguf` (nano), `zerocon-standard-v4.1-iq4nl-emb8.gguf` / `zerocon-standard-v4.1-q8_0.gguf` (standard; IQ4_NL body with Q8_0 input/output, or Q8_0). Downloaded on demand from the pinned GitHub release and not redistributed with the APK.
- **License of the upstream base**: Apache License 2.0 (`app/src/main/assets/licenses/Apache-2.0.txt`)
- **License of these derivatives**: ZeroCon Model Nano の重み・学習物は独自改変物であり、本製品外での利用・再配布を許諾しません。上流部分には Apache License 2.0 の条件（著作権表示・ライセンス文の同梱・変更の明示）が及び、本アプリはそれを満たしています。
- **Author**: Miwa Keita (ensan-hcl)
- **URL**: https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf
- **License Terms**: http://www.apache.org/licenses/LICENSE-2.0

---

## 3. LOUDS System Dictionary & Emoji Dictionary
- **Authors**: Miwa (ensan-hcl) / azooKey Project (derived from Mozc & Unicode Emoji Annotations)
- **License**: Apache License 2.0
- **License Terms**: http://www.apache.org/licenses/LICENSE-2.0
- **Additional vocabulary**: Selected SudachiDict Full 20260723 entries were normalized and incorporated into the azooKey dictionary. The expansion also contains Wikidata structured data (CC0 1.0). SudachiDict's Apache License 2.0 text, upstream `LEGAL` notices, and the Wikidata attribution are bundled at `assets/shared-engine/Dictionary/AdditionalDictionaryNotices/`.

---

## 4. llama.cpp
- **Copyright**: Copyright (c) 2023-2026 Georgi Gerganov and contributors
- **License**: MIT License
- **Repository**: https://github.com/ggerganov/llama.cpp
- **Notice**: `app/src/main/assets/licenses/NOTICE-MIT.txt` (permission text)

---

## 5. AndroidX / Jetpack Compose (Apache License 2.0)
- **Copyright**: Copyright (C) The Android Open Source Project
- **License**: Apache License 2.0
- **Components**: `androidx.core:core-ktx`, `androidx.lifecycle:*`, `androidx.activity:activity-compose`, `androidx.savedstate:savedstate-ktx`, and the Compose artifacts pinned by `compose-bom:2024.02.02` (`ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `material-icons-extended`).
- **Notice**: `app/src/main/assets/licenses/NOTICE-AndroidX-Apache.txt`, license text `app/src/main/assets/licenses/Apache-2.0.txt`
- **Project**: https://androidx.dev/

---

## 6. Kotlin / kotlinx.coroutines (Apache License 2.0)
- **Copyright**: Copyright (C) JetBrains s.r.o. and Kotlin Programming Language contributors
- **License**: Apache License 2.0
- **Components**: Kotlin standard library, `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- **Notice**: `app/src/main/assets/licenses/NOTICE-Kotlin-Apache.txt`
- **Project**: https://kotlinlang.org/ , https://github.com/Kotlin/kotlinx.coroutines

---

## 7. Unicode Data Files (Emoji Test Data)
- **Copyright**: Copyright (c) 1991-2026 Unicode, Inc.
- **License**: Unicode License v3
- **Usage**: `data/emoji/emoji-test-15.1.txt` (Unicode License v3, CLDR order) is the source of `assets/emoji_catalog.tsv`, generated by `node scripts/generate-android-emoji-catalog.mjs`. The same license covers the ICU data bundled in `lib_FoundationICU.so`.
- **Notice**: `app/src/main/assets/licenses/Unicode-License-V3.txt`
- **Terms**: https://www.unicode.org/license.txt

---

## 8. PLaMo 3 Translate (Translation Model)
- Model: pfnet/plamo-3-translate-0.8b / plamo-3-translate-2b (quantized GGUF)
- Author: Preferred Networks, Inc. (PFN), built on PLaMo 3 NICT
- License: PLaMo Community License
- Notice: Free for personal/academic/non-commercial use. Commercial use free for individuals/organizations with annual revenue <= JPY 1 billion (advance registration required). Commercial use above threshold requires separate license agreement with PFN.
- Android download: The Android app does not bundle this model. After the user confirms in the settings screen, it downloads `pfnet/plamo-3-translate-0.8b-GGUF` revision `dbeb0856cb0b004b484f190bb71dd65876297013` (`plamo-3-translate-0.8b-Q4_K_M.gguf`, SHA-256 `923b2f28a9856de1b8b3b610a7e59ded0b14d007466fd5dd526e6a9ecaadafdd`).
- Repository: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF
- License Terms: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/blob/main/LICENSE/ja

---

## 9. Swift Runtime / Foundation / Dispatch (Apache License 2.0 with Runtime Library Exception)
- **Copyright**: Copyright (c) Apple Inc. and the Swift project authors
- **License**: Apache License 2.0 with Runtime Library Exception
- **Bundled**: `libswiftCore.so`, `libswiftAndroid.so`, `libswiftDispatch.so`, `libswiftObservation.so`, `libswiftSynchronization.so`, `libswift_Builtin_float.so`, `libswift_Concurrency.so`, `libswift_RegexParser.so`, `libswift_StringProcessing.so`, `libswift_math.so`, `libFoundation*.so`, `libdispatch.so`, `libBlocksRuntime.so`
- **Notice**: `app/src/main/assets/licenses/NOTICE-Swift-Runtime.txt`, base license `app/src/main/assets/licenses/Apache-2.0.txt`
- **Repository**: https://github.com/swiftlang/swift

---

## 10. LLVM libc++ (Apache License 2.0 with LLVM Exceptions)
- **Copyright**: Copyright (c) The LLVM Project
- **Bundled**: `libc++_shared.so` (Android NDK C++ standard library)
- **Notice**: `app/src/main/assets/licenses/NOTICE-LLVM.txt`
- **Repository**: https://github.com/llvm/llvm-project

---

## 11. Swift Packages Statically Linked into the Conversion Engine
- **License**: Apache License 2.0 (`app/src/main/assets/licenses/Apache-2.0.txt`)
- **Components**:
  - `swift-algorithms` — https://github.com/apple/swift-algorithms
  - `swift-collections` — https://github.com/apple/swift-collections
  - `swift-numerics` — https://github.com/apple/swift-numerics
  - `swift-transformers` — https://github.com/huggingface/swift-transformers (its tokenizer resources are bundled at `assets/shared-engine/swift-transformers_Hub.resources/`)
  - `swift-tokenizers` — https://github.com/ensan-hcl/swift-tokenizers
  - `Jinja` — https://github.com/johnmai-dev/Jinja, MIT License, Copyright (c) 2024 John Mai
- **Notice**: `app/src/main/assets/licenses/NOTICE-Swift-Runtime.txt`

---

## 12. Mozc-derived Dictionary Data and Keyboard Rewrite Rules (BSD 3-Clause)
- **Copyright**: Copyright 2010-2021, Google Inc.
- **License**: BSD 3-Clause License
- **Usage**: The bundled system dictionary and emoji dictionary are derived, through the azooKey dictionary storage, from Google Mozc dictionary data. The keyboard typo rewrite contract also follows Mozc `converter/key_corrector.cc`.
- **Notice**: `app/src/main/assets/licenses/NOTICE-Mozc.txt`
- **Repository**: https://github.com/google/mozc

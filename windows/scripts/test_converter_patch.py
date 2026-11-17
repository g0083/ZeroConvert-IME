"""Regression tests for the converter patch anchors shared by Windows and Android.

The pinned converter archive is third-party and immutable. These tests pin the
anchor contract so a converter revision bump fails in CI instead of only in the
Android build that stages the same patched sources.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows" / "scripts"))

import converter_patch


MODULEMAP = '''module llama [system] {
    header "llama.h"
    header "ggml.h"
    header "ggml-alloc.h"
    header "ggml-backend.h"

    link "llama"

    export *
}
'''

ZENZ_CONTEXT = '''        model_params.use_mmap = true
        ctx_params.n_ctx = 512
        ctx_params.n_batch = 512
        llama_backend_init()
    func resetContext() throws {
        llama_free(self.context)
        var params = Self.ctx_params
        #if ZenzaiCPU
        params.offload_kqv = false
        #endif
        let context = llama_init_from_model(self.model, params)
        guard let context else {
            debug("Could not load context!")
            throw ZenzError.couldNotLoadContext
        }
        self.context = context
        self.prevInputBySeq = [:]
        self.prevPromptBySeq = [:]
    }
        llama_kv_cache_seq_rm(context, 0, -1, -1)
        llama_kv_cache_seq_cp(context, 0, 1, -1, -1)
        let position = llama_kv_cache_seq_pos_max(self.context, 0)
        var batch = llama_batch_init(512, 0, 1)
        defer { llama_batch_free(batch) }
        let n_ctx = llama_n_ctx(context)
        let n_kv_req = tokens.count + (Int(n_len) - tokens.count)
        if n_kv_req > n_ctx {
            debug("error: n_kv_req > n_ctx, the required KV cache size is not big enough")
        }
        if llama_decode(context, batch) != 0 {
            debug("llama_decode() failed")
            return nil
        }
'''


CONVERTER_API = '\n'.join(
    [anchor for anchor, _ in converter_patch.CONVERTER_API_REPLACEMENTS[:-1]]
    + [converter_patch.CONVERTER_API_REPLACEMENTS[-1][0]]
)


class ExactKeyboardLookupTests(unittest.TestCase):
    def test_exposes_bounded_existing_dictionary_lookup_without_model_inference(self):
        patched = converter_patch.patch_converter_api(CONVERTER_API)
        lookup_start = patched.index('public func requestExactKeyboardCorrectionCandidates')
        lookup_end = patched.index(converter_patch.EXACT_KEYBOARD_LOOKUP_ANCHOR)
        lookup_body = patched[lookup_start:lookup_end]
        self.assertIn('lookupDicdata(composingText: inputData', lookup_body)
        self.assertIn('needTypoCorrection: false', lookup_body)
        self.assertIn('.prefix(8)', lookup_body)
        self.assertNotIn('convertToLattice', lookup_body)
        self.assertEqual(patched.count(converter_patch.EXACT_KEYBOARD_LOOKUP_ANCHOR), 1)
        self.assertIn('public func postCompositionConnectionScore(formerRcid: Int, latterLcid: Int)', patched)
        self.assertIn('public func requestContextualNextWordReadings(', patched)
        self.assertIn('public func invalidateLearningMemoryCache()', patched)
        self.assertIn('public func syncLearningConfig(', patched)
        self.assertIn('public func predictiveRubyPrefixes(for composingText: ComposingText)', patched)
        self.assertIn('cacheOnlyJapanesePrediction: Bool = false', patched)
        self.assertIn('prefixConstraintSurface: String? = nil', patched)
        self.assertIn('kana2lattice_all_with_prefix_constraint(', patched)

    def test_rejects_a_changed_converter_api_anchor(self):
        with self.assertRaises(ValueError):
            converter_patch.patch_converter_api('changed converter API')
        for anchor, _ in converter_patch.CONVERTER_API_REPLACEMENTS:
            broken = CONVERTER_API.replace(anchor, 'removed_by_upstream')
            with self.assertRaises(ValueError):
                converter_patch.patch_converter_api(broken)

    def test_converter_api_patching_is_not_repeatable(self):
        patched = converter_patch.patch_converter_api(CONVERTER_API)
        with self.assertRaises(ValueError):
            converter_patch.patch_converter_api(patched)


class ModulemapTests(unittest.TestCase):
    def test_links_the_ggml_libraries_the_converter_calls(self):
        patched = converter_patch.patch_modulemap(MODULEMAP)
        self.assertIn('link "llama"\n    link "ggml"\n    link "ggml-base"', patched)
        self.assertEqual(patched.count('link "llama"'), 1)

    def test_missing_anchor_is_reported_with_the_anchor_text(self):
        with self.assertRaises(ValueError) as raised:
            converter_patch.patch_modulemap('module llama [system] { export * }\n')
        self.assertIn(converter_patch.MODULEMAP_ANCHOR, str(raised.exception))


class ZenzContextTests(unittest.TestCase):
    def test_applies_every_replacement(self):
        patched = converter_patch.patch_zenz_context(ZENZ_CONTEXT)
        self.assertIn('model_params.load_mode = LLAMA_LOAD_MODE_MMAP', patched)
        self.assertIn('ctx_params.n_ctx = 1024', patched)
        self.assertIn('ctx_params.n_seq_max = 2', patched)
        self.assertIn('ctx_params.kv_unified = true', patched)
        self.assertIn('ctx_params.n_ubatch = 512', patched)
        self.assertIn('ctx_params.n_outputs_max = 256', patched)
        self.assertIn('ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED', patched)
        self.assertIn('ctx_params.no_perf = true', patched)
        self.assertIn('ctx_params.n_threads_batch = Int32(batchThreads)', patched)
        self.assertIn('llama_set_n_threads(context, decodeThreads, deltaTokens <= 4 ? decodeThreads : batchThreads)', patched)
        self.assertIn('llama_memory_clear(llama_get_memory(self.context), true)', patched)
        self.assertIn('requestedLogits <= 256', patched)
        self.assertIn('ggml_backend_load_all_from_path(path)', patched)
        self.assertIn('llama_memory_seq_rm(llama_get_memory(context),', patched)
        self.assertIn('llama_memory_seq_cp(llama_get_memory(context),', patched)
        self.assertIn('self.prevInputBySeq[seqId] = Array(effectivePrevInput.prefix(prefixCacheCount))', patched)
        self.assertIn('llama_memory_seq_pos_max(llama_get_memory(self.context),', patched)

    def test_backend_init_is_wrapped_not_removed(self):
        # The pinned converter must still initialize llama; only the backend
        # directory selection is added around the existing call.
        patched = converter_patch.patch_zenz_context(ZENZ_CONTEXT)
        self.assertEqual(patched.count('llama_backend_init()'), 1)
        self.assertLess(patched.index('ggml_backend_load_all_from_path(path)'),
                        patched.index('llama_backend_init()'))

    def test_removed_anchors_do_not_survive_patching(self):
        patched = converter_patch.patch_zenz_context(ZENZ_CONTEXT)
        for anchor, replacement in converter_patch.ZENZ_CONTEXT_REPLACEMENTS:
            if anchor in replacement:
                continue
            self.assertNotIn(anchor, patched)

    def test_patching_is_idempotent_only_through_the_anchor_contract(self):
        # A second pass must fail loudly rather than silently double-patch.
        patched = converter_patch.patch_zenz_context(ZENZ_CONTEXT)
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_context(patched)

    def test_each_missing_anchor_names_the_offending_text(self):
        for anchor, _ in converter_patch.ZENZ_CONTEXT_REPLACEMENTS:
            broken = ZENZ_CONTEXT.replace(anchor, 'removed_by_upstream')
            with self.assertRaises(ValueError) as raised:
                converter_patch.patch_zenz_context(broken)
            self.assertIn(anchor, str(raised.exception))


LEARNING_MEMORY = '''    func forgetMemory(data: [DicdataElement]) {
        guard self.config.learningType.needUpdateMemory else {
            return
        }
        // 1. temporary memoryを削除する
    }

    func resetMemory() {
        self.temporaryMemory = TemporalLearningMemoryTrie()
        guard let memoryURL = config.memoryURL else {
            debug(#function, "memoryURL is nil")
            return
        }
        do {
            try LongTermLearningMemory.reset(directoryURL: memoryURL)
        } catch {
            debug("LearningManager reset failed", error)
        }
    }

    /// - Returns: Whether cache should be reseted or not.
    func updateConfig(_ newConfig: LearningConfig) -> Bool {
        // 更新の必要がなければ何もしない
        if !newConfig.learningType.needUsingMemory {
            self.config = newConfig
            return false
        }
        return false
    }
'''


class LearningMemoryTests(unittest.TestCase):
    def test_explicit_deletion_is_allowed_when_learning_is_read_only(self):
        # Read-only users must still be able to remove a word they taught the IME
        # before switching the mode; deletion is not a new learning write.
        patched = converter_patch.patch_learning_memory(LEARNING_MEMORY)
        self.assertIn('learningType.needUsingMemory', patched)
        self.assertNotIn('learningType.needUpdateMemory', patched)

    def test_negative_max_memory_count_triggers_cache_invalidation(self):
        patched = converter_patch.patch_learning_memory(LEARNING_MEMORY)
        self.assertIn('if newConfig.maxMemoryCount < 0 {\n            return true', patched)

    def test_reset_recomputes_the_collapsed_state(self):
        # Without this the paused flag survives a reset and keeps learning off.
        patched = converter_patch.patch_learning_memory(LEARNING_MEMORY)
        reset_body = patched[patched.index('func resetMemory'):]
        self.assertIn('self.memoryCollapsed = LongTermLearningMemory.memoryCollapsed', reset_body)

    def test_learning_memory_anchors_report_the_offending_text(self):
        for anchor, _ in converter_patch.LEARNING_MEMORY_REPLACEMENTS:
            broken = LEARNING_MEMORY.replace(anchor, 'removed_by_upstream')
            with self.assertRaises(ValueError) as raised:
                converter_patch.patch_learning_memory(broken)
            self.assertIn('Converter patch no longer applies', str(raised.exception))

    def test_learning_memory_patching_is_not_repeatable(self):
        patched = converter_patch.patch_learning_memory(LEARNING_MEMORY)
        with self.assertRaises(ValueError):
            converter_patch.patch_learning_memory(patched)


LATTICE = '''        case .surface(let sIndex):
            if let iIndex = self.inputIndexToSurfaceIndexMap.filter({ $0.value == sIndex}).first?.key {
                .bothIndex(inputIndex: iIndex, surfaceIndex: sIndex)
            } else {
                .surfaceIndex(sIndex)
            }
        }
    }

    func indices(inputCount: Int, surfaceCount: Int) -> [DualIndex] {
        var indices: [DualIndex] = []
        var sIndexPointer = 0
        for i in 0 ..< inputCount {
            if let sIndex = self.inputIndexToSurfaceIndexMap[i] {
                for j in min(surfaceCount, sIndexPointer) ..< min(surfaceCount, sIndex) {
                    indices.append(.surfaceIndex(j))
                }
                if sIndexPointer <= sIndex && sIndex < surfaceCount {
                    indices.append(.bothIndex(inputIndex: i, surfaceIndex: sIndex))
                } else {
                    indices.append(.inputIndex(i))
                }
                sIndexPointer = sIndex + 1
            } else {
                indices.append(.inputIndex(i))
            }
        }
'''


class LatticePatchTests(unittest.TestCase):
    def test_guards_non_increasing_surface_bounds_and_deterministic_key(self):
        patched = converter_patch.patch_lattice(LATTICE)
        self.assertIn('.keys.min()', patched)
        self.assertIn('if surfaceStart < surfaceEnd {', patched)
        self.assertIn('sIndexPointer = max(sIndexPointer, sIndex + 1)', patched)
        self.assertNotIn(
            'for j in min(surfaceCount, sIndexPointer) ..< min(surfaceCount, sIndex)',
            patched,
        )

    def test_lattice_anchors_report_the_offending_text(self):
        for anchor, _ in converter_patch.LATTICE_REPLACEMENTS:
            broken = LATTICE.replace(anchor, 'removed_by_upstream')
            with self.assertRaises(ValueError) as raised:
                converter_patch.patch_lattice(broken)
            self.assertIn('Converter patch no longer applies', str(raised.exception))

    def test_lattice_patching_is_not_repeatable(self):
        patched = converter_patch.patch_lattice(LATTICE)
        with self.assertRaises(ValueError):
            converter_patch.patch_lattice(patched)


ZENZ_PROMPT_BUILDER = r'''enum ZenzPromptBuilder {
    static func inputPredictionPrompt(
        leftSideContext: String,
        composingText: String,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode
    ) -> String? {
        guard case let .v3(mode) = versionDependentConfig else {
            return nil
        }
        let conditions = self.v3Conditions(mode)
        let trimmedLeftContext = self.trimmedContext(leftSideContext, maxLength: mode.maxLeftSideContextLength)
        let input = composingText.toKatakana()
        if trimmedLeftContext.isEmpty {
            return conditions.joined(separator: "") + inputTag + input
        } else {
            return conditions.joined(separator: "") + contextTag + trimmedLeftContext + inputTag + input
        }
    }

    static func typoCorrectionPromptPrefix(leftSideContext: String) -> String {
        if leftSideContext.isEmpty {
            return inputTag
        } else {
            return contextTag + leftSideContext + inputTag
        }
    }

    static func candidateEvaluationPrompt(
        input: String,
        userDictionaryPrompt: String,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode
    ) -> String {
        var conditions: [String] = []
        if !userDictionaryPrompt.isEmpty {
            conditions.append("辞書:\(userDictionaryPrompt)")
        }

        let leftSideContext: String
        switch versionDependentConfig {
        case .v2(let mode):
            if let profile = mode.profile, !profile.isEmpty {
                let pf = profile.suffix(25)
                conditions.append("プロフィール:\(pf)")
            }
            leftSideContext = self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)
        case .v3(let mode):
            conditions.append(contentsOf: self.v3Conditions(mode))
            leftSideContext = self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)
        }

        switch versionDependentConfig {
        case .v2:
            if !conditions.isEmpty {
                return inputTag + input + contextTag + conditions.joined(separator: "・") + "・発言:\(leftSideContext)" + outputTag
            } else if !leftSideContext.isEmpty {
                return inputTag + input + contextTag + leftSideContext + outputTag
            } else {
                return inputTag + input + outputTag
            }
        case .v3:
            if !leftSideContext.isEmpty {
                return conditions.joined(separator: "") + contextTag + leftSideContext + inputTag + input + outputTag
            } else {
                return conditions.joined(separator: "") + inputTag + input + outputTag
            }
        }
    }
}
'''


class ZenzPromptBuilderTests(unittest.TestCase):
    def test_masks_digits_in_prompts_and_exposes_shared_masking_helper(self):
        patched = converter_patch.patch_zenz_prompt_builder(ZENZ_PROMPT_BUILDER)
        self.assertIn('public enum ZenzDigitMasking', patched)
        self.assertIn('ZenzDigitMasking.mask(input).text', patched)
        self.assertIn('ZenzDigitMasking.mask(composingText.toKatakana()).text', patched)
        self.assertIn('ZenzDigitMasking.mask(leftSideContext).text', patched)
        self.assertEqual(patched.count('enum ZenzPromptBuilder {'), 1)

    def test_rejects_broken_anchor_or_double_patching(self):
        patched = converter_patch.patch_zenz_prompt_builder(ZENZ_PROMPT_BUILDER)
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_prompt_builder(patched)
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_prompt_builder('changed prompt builder')


class ZenzEvaluatorTests(unittest.TestCase):
    @staticmethod
    def _evaluator_fixture():
        return (
            converter_patch.ZENZ_EVALUATOR_SETUP_ANCHOR
            + '\n'
            + converter_patch.ZENZ_EVALUATOR_LOOP_ANCHOR
        )

    def test_uses_single_pass_argmax_masks_digits_and_delays_logsumexp(self):
        patched = converter_patch.patch_zenz_evaluator(self._evaluator_fixture())
        self.assertIn('func stabilizedLogSumExp', patched)
        self.assertIn('var richHeap: FixedSizeHeap<TokenAndLogprob>?', patched)
        self.assertIn('ZenzDigitMasking.mask(input)', patched)
        self.assertIn('ZenzDigitMasking.mask(candidate.text, maxRuns: inputMask.rawDigits.count)', patched)
        self.assertIn('func restoreConstraintBytes', patched)
        self.assertIn('i < isLearnedToken.count ? isLearnedToken[i] : (isLearned: false, priority: Float(0))', patched)
        self.assertLess(patched.rindex('return .fixRequired'), patched.rindex('let logsumexp = stabilizedLogSumExp'))
        self.assertNotIn('tokenHeap.insertIfPossible(TokenAndLogprob(token: llama_token(index - startIndex), logprob: logp))', patched)

    def test_rejects_broken_anchor_or_double_patching(self):
        patched = converter_patch.patch_zenz_evaluator(self._evaluator_fixture())
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_evaluator(patched)
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_evaluator('changed evaluator loop')


class DicdataStoreAndContextualGeneratorTests(unittest.TestCase):
    def test_splits_zero_hint_csv_on_crlf_and_lf(self):
        source = '            let csvLines = csvString.split(separator: "\\n")\n'
        patched = converter_patch.patch_dicdata_store(source)
        self.assertIn('split(whereSeparator: \\.isNewline)', patched)
        self.assertNotIn('split(separator: "\\n")', patched)
        with self.assertRaises(ValueError):
            converter_patch.patch_dicdata_store(patched)

    def test_exposes_bounded_contextual_next_word_generator(self):
        gen_fixture = (
            converter_patch.ZENZ_INPUT_GENERATOR_STRUCT_ANCHOR
            + '\n'
            + converter_patch.ZENZ_INPUT_GENERATOR_TAIL_ANCHOR
        )
        patched_gen = converter_patch.patch_zenz_input_generator(gen_fixture)
        self.assertIn('public struct ContextualNextWordReading', patched_gen)
        self.assertIn('static func generateContextualCandidates(', patched_gen)
        self.assertIn('shouldCancel: (() -> Bool)? = nil', patched_gen)
        with self.assertRaises(ValueError):
            converter_patch.patch_zenz_input_generator(patched_gen)

        patched_zenz = converter_patch.patch_zenz(converter_patch.ZENZ_CONTEXTUAL_ANCHOR)
        self.assertIn('func contextualNextWordReadings(', patched_zenz)


if __name__ == '__main__':
    unittest.main()

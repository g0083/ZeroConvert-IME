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
        llama_backend_init()
        llama_kv_cache_seq_rm(context, 0, -1, -1)
        llama_kv_cache_seq_cp(context, 0, 1, -1, -1)
        let position = llama_kv_cache_seq_pos_max(self.context, 0)
'''


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
        self.assertIn('ggml_backend_load_all_from_path(path)', patched)
        self.assertIn('llama_memory_seq_rm(llama_get_memory(context),', patched)
        self.assertIn('llama_memory_seq_cp(llama_get_memory(context),', patched)
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


if __name__ == '__main__':
    unittest.main()

"""Edits applied to the vendored AzooKeyKanaKanjiConverter before it is built.

The converter is a checksum-pinned third-party archive. Windows and Android
compile the same patched copy, so the anchors below are the contract with that
revision: when the pinned revision changes, this module must change in the same
commit. `prepare-modern-llama.py --check-converter` verifies the anchors against
the pinned archive without building anything, which is what keeps a converter
bump from silently breaking only the Android build.
"""

MODULEMAP_PATH = 'Sources/llama.cpp/module.modulemap'
ZENZ_CONTEXT_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzContext.swift'
)

MODULEMAP_ANCHOR = 'link "llama"'
MODULEMAP_REPLACEMENT = 'link "llama"\n    link "ggml"\n    link "ggml-base"'

# Ordered: each anchor is matched against the text produced by the previous
# replacement, so a later entry must not depend on text an earlier one removed.
ZENZ_CONTEXT_REPLACEMENTS = (
    (
        'model_params.use_mmap = true',
        'model_params.load_mode = LLAMA_LOAD_MODE_MMAP',
    ),
    (
        'ctx_params.n_ctx = 512',
        'ctx_params.n_ctx = 1024\n        ctx_params.n_seq_max = 2\n        ctx_params.kv_unified = true',
    ),
    (
        'llama_backend_init()',
        'if let path = ProcessInfo.processInfo.environment["AZOOKEY_LLAMA_BACKEND_DIR"] {\n'
        '            ggml_backend_load_all_from_path(path)\n'
        '        } else { ggml_backend_load_all() }\n'
        '        llama_backend_init()',
    ),
    (
        'llama_kv_cache_seq_rm(context,',
        'llama_memory_seq_rm(llama_get_memory(context),',
    ),
    (
        'llama_kv_cache_seq_cp(context,',
        'llama_memory_seq_cp(llama_get_memory(context),',
    ),
    (
        'llama_kv_cache_seq_pos_max(self.context,',
        'llama_memory_seq_pos_max(llama_get_memory(self.context),',
    ),
)


def _require(text, anchor):
    if anchor not in text:
        raise ValueError('Converter patch no longer applies: ' + anchor)


def patch_modulemap(text):
    """Link the ggml libraries the converter's llama module needs."""
    _require(text, MODULEMAP_ANCHOR)
    return text.replace(MODULEMAP_ANCHOR, MODULEMAP_REPLACEMENT)


def patch_zenz_context(text):
    """Adapt the converter to the pinned llama.cpp memory API and load mode."""
    for anchor, replacement in ZENZ_CONTEXT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    return text

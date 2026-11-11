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
LEARNING_MEMORY_PATH = (
    'Sources/KanaKanjiConverterModule/DictionaryManagement/LearningMemory.swift'
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
    # n_batch caps how many tokens one llama_decode call accepts, so leaving it
    # at 512 while n_ctx is 1024 makes long prompts abort on GGML_ASSERT rather
    # than degrade. Keep the two paired.
    (
        'ctx_params.n_batch = 512',
        'ctx_params.n_batch = 1024',
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
    # The vendored llama_batch_add writes through batch.token[n_tokens] with no
    # bounds check, so a prompt longer than n_batch corrupts the heap before
    # llama_decode aborts on GGML_ASSERT. Enforce the capacity the context was
    # actually created with and degrade to a failed evaluation instead.
    (
        'var batch = llama_batch_init(512, 0, 1)\n'
        '        defer { llama_batch_free(batch) }\n'
        '        let n_ctx = llama_n_ctx(context)\n'
        '        let n_kv_req = tokens.count + (Int(n_len) - tokens.count)\n'
        '        if n_kv_req > n_ctx {\n'
        '            debug("error: n_kv_req > n_ctx, the required KV cache size is not big enough")\n'
        '        }',

        'let n_ctx = Int(llama_n_ctx(context))\n'
        '        let n_batch = Int(llama_n_batch(context))\n'
        '        // tokens.count is the full prompt, and llama_decode receives one\n'
        '        // batch, so both the KV cache and the batch must hold all of it.\n'
        '        guard tokens.count <= n_ctx, tokens.count <= n_batch else {\n'
        '            debug("error: prompt of", tokens.count, "tokens exceeds n_ctx", n_ctx, "or n_batch", n_batch)\n'
        '            return nil\n'
        '        }\n'
        '        var batch = llama_batch_init(Int32(tokens.count), 0, 1)\n'
        '        defer { llama_batch_free(batch) }',
    ),
)


def _require(text, anchor):
    if anchor not in text:
        raise ValueError('Converter patch no longer applies: ' + anchor)


# Forgetting a word is an explicit user action, not a learning write, so it must
# work in read-only mode too. The upstream guard ties deletion to the same
# setting that permits new learning, which would make per-word deletion a no-op
# for users who keep learning history but freeze new entries.
LEARNING_FORGET_ANCHOR = '''    func forgetMemory(data: [DicdataElement]) {
        guard self.config.learningType.needUpdateMemory else {
            return
        }'''

LEARNING_FORGET_REPLACEMENT = '''    func forgetMemory(data: [DicdataElement]) {
        // Deletion is explicit: allow it whenever history is read at all.
        guard self.config.learningType.needUsingMemory else {
            return
        }'''

# resetMemory deleted the files but left memoryCollapsed set, so a reset in a
# session that had hit an update failure kept learning disabled until restart.
LEARNING_RESET_ANCHOR = '''        do {
            try LongTermLearningMemory.reset(directoryURL: memoryURL)
        } catch {
            debug("LearningManager reset failed", error)
        }
    }'''

LEARNING_RESET_REPLACEMENT = '''        do {
            try LongTermLearningMemory.reset(directoryURL: memoryURL)
            // The files it was derived from are gone, so the paused state is too.
            self.memoryCollapsed = LongTermLearningMemory.memoryCollapsed(directoryURL: memoryURL)
        } catch {
            debug("LearningManager reset failed", error)
        }
    }'''

LEARNING_MEMORY_REPLACEMENTS = (
    (LEARNING_FORGET_ANCHOR, LEARNING_FORGET_REPLACEMENT),
    (LEARNING_RESET_ANCHOR, LEARNING_RESET_REPLACEMENT),
)


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


def patch_learning_memory(text):
    """Allow explicit per-word deletion in read-only mode and refresh collapse state."""
    for anchor, replacement in LEARNING_MEMORY_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    return text

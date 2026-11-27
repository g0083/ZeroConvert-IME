// Multi-threaded Emscripten (pthreads + SIMD128) llama.cpp backend shared by
// the Swift WASI main conversion engine and the Rust/C++ assist Worker.
#include "llama.h"
#include "keyboard_assist.h"

#ifdef __EMSCRIPTEN__
#include <emscripten/emscripten.h>
#define ZEROCON_EXPORT extern "C" EMSCRIPTEN_KEEPALIVE
#else
#define ZEROCON_EXPORT extern "C"
#endif

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;

int32_t g_max_threads = 4;

int32_t clamp_threads(int32_t requested) {
    if (requested <= 0) return g_max_threads;
    return std::max<int32_t>(1, std::min<int32_t>(g_max_threads, requested));
}

// Track the number of output logits rows from the most recent decode per context.
std::unordered_map<const llama_context *, int32_t> g_last_logits_count;

// Completion / assist state (matches web/completion.cpp)
llama_model *g_comp_model = nullptr;
llama_context *g_comp_context = nullptr;
llama_sampler *g_comp_sampler = nullptr;
std::vector<llama_token> g_comp_cached_tokens;
Clock::time_point g_comp_deadline;
std::string g_comp_output;
std::string g_comp_keyboard_output;
int g_comp_stop_type = 0;
constexpr int kCompContextSize = 512;
constexpr int kCompBatchSize = 512;

bool comp_expired() { return Clock::now() >= g_comp_deadline; }
} // namespace

// Used by engine/native/keyboard_assist.cpp
long long assist_epoch() { return 0; }

ZEROCON_EXPORT void mt_set_default_threads(int32_t n) {
    g_max_threads = std::max<int32_t>(1, std::min<int32_t>(4, n));
}

// ---------------------------------------------------------------------------
// Swift WASI bridge API (mt_llama_*)
// ---------------------------------------------------------------------------

ZEROCON_EXPORT void mt_llama_backend_init() {
    llama_backend_init();
}

ZEROCON_EXPORT void mt_llama_backend_free() {
    llama_backend_free();
}

ZEROCON_EXPORT int32_t mt_llama_model_load(const char *path) {
    if (!path) return 0;
    auto params = llama_model_default_params();
    params.load_mode = LLAMA_LOAD_MODE_NONE;
    params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, params);
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(model));
}

ZEROCON_EXPORT void mt_llama_model_free(int32_t model_ptr) {
    if (!model_ptr) return;
    llama_model_free(reinterpret_cast<llama_model *>(static_cast<uintptr_t>(model_ptr)));
}

ZEROCON_EXPORT uint64_t mt_llama_model_size(int32_t model_ptr) {
    if (!model_ptr) return 0;
    return llama_model_size(reinterpret_cast<const llama_model *>(static_cast<uintptr_t>(model_ptr)));
}

ZEROCON_EXPORT int32_t mt_llama_model_get_vocab(int32_t model_ptr) {
    if (!model_ptr) return 0;
    const llama_vocab *vocab = llama_model_get_vocab(
        reinterpret_cast<const llama_model *>(static_cast<uintptr_t>(model_ptr)));
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(vocab));
}

ZEROCON_EXPORT int32_t mt_llama_vocab_n_tokens(int32_t vocab_ptr) {
    if (!vocab_ptr) return 0;
    return llama_vocab_n_tokens(reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)));
}

ZEROCON_EXPORT int32_t mt_llama_vocab_bos(int32_t vocab_ptr) {
    if (!vocab_ptr) return -1;
    return llama_vocab_bos(reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)));
}

ZEROCON_EXPORT int32_t mt_llama_vocab_eos(int32_t vocab_ptr) {
    if (!vocab_ptr) return -1;
    return llama_vocab_eos(reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)));
}

ZEROCON_EXPORT int32_t mt_llama_vocab_is_eog(int32_t vocab_ptr, int32_t token) {
    if (!vocab_ptr) return 0;
    return llama_vocab_is_eog(reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)), token) ? 1 : 0;
}

ZEROCON_EXPORT int32_t mt_llama_tokenize(
    int32_t vocab_ptr,
    const char *text,
    int32_t text_len,
    int32_t *tokens,
    int32_t n_tokens_max,
    int32_t add_special,
    int32_t parse_special
) {
    if (!vocab_ptr || !text) return -1;
    return llama_tokenize(
        reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)),
        text, text_len, tokens, n_tokens_max, add_special != 0, parse_special != 0
    );
}

ZEROCON_EXPORT int32_t mt_llama_token_to_piece(
    int32_t vocab_ptr,
    int32_t token,
    char *buf,
    int32_t length,
    int32_t lstrip,
    int32_t special
) {
    if (!vocab_ptr) return -1;
    return llama_token_to_piece(
        reinterpret_cast<const llama_vocab *>(static_cast<uintptr_t>(vocab_ptr)),
        token, buf, length, lstrip, special != 0
    );
}

ZEROCON_EXPORT int32_t mt_llama_init_from_model(
    int32_t model_ptr,
    uint32_t n_ctx,
    uint32_t n_batch,
    uint32_t n_ubatch,
    int32_t n_threads,
    int32_t n_threads_batch,
    int32_t flash_attn_type
) {
    if (!model_ptr) return 0;
    auto *model = reinterpret_cast<llama_model *>(static_cast<uintptr_t>(model_ptr));
    auto params = llama_context_default_params();
    if (n_ctx > 0) params.n_ctx = n_ctx;
    if (n_batch > 0) params.n_batch = n_batch;
    if (n_ubatch > 0) params.n_ubatch = n_ubatch;
    params.n_threads = clamp_threads(n_threads);
    params.n_threads_batch = clamp_threads(n_threads_batch);
    params.flash_attn_type = static_cast<enum llama_flash_attn_type>(flash_attn_type);
    params.offload_kqv = false;
    params.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx) g_last_logits_count[ctx] = 0;
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(ctx));
}

ZEROCON_EXPORT void mt_llama_free(int32_t ctx_ptr) {
    if (!ctx_ptr) return;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    g_last_logits_count.erase(ctx);
    llama_free(ctx);
}

ZEROCON_EXPORT uint32_t mt_llama_n_ctx(int32_t ctx_ptr) {
    if (!ctx_ptr) return 0;
    return llama_n_ctx(reinterpret_cast<const llama_context *>(static_cast<uintptr_t>(ctx_ptr)));
}

ZEROCON_EXPORT uint32_t mt_llama_n_batch(int32_t ctx_ptr) {
    if (!ctx_ptr) return 0;
    return llama_n_batch(reinterpret_cast<const llama_context *>(static_cast<uintptr_t>(ctx_ptr)));
}

ZEROCON_EXPORT void mt_llama_set_n_threads(int32_t ctx_ptr, int32_t n_threads, int32_t n_threads_batch) {
    if (!ctx_ptr) return;
    llama_set_n_threads(
        reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr)),
        clamp_threads(n_threads),
        clamp_threads(n_threads_batch)
    );
}

ZEROCON_EXPORT void mt_llama_memory_clear(int32_t ctx_ptr, int32_t data) {
    if (!ctx_ptr) return;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    llama_memory_clear(llama_get_memory(ctx), data != 0);
}

ZEROCON_EXPORT int32_t mt_llama_memory_seq_rm(int32_t ctx_ptr, int32_t seq_id, int32_t p0, int32_t p1) {
    if (!ctx_ptr) return 0;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    return llama_memory_seq_rm(llama_get_memory(ctx), seq_id, p0, p1) ? 1 : 0;
}

ZEROCON_EXPORT void mt_llama_memory_seq_cp(int32_t ctx_ptr, int32_t src, int32_t dst, int32_t p0, int32_t p1) {
    if (!ctx_ptr) return;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    llama_memory_seq_cp(llama_get_memory(ctx), src, dst, p0, p1);
}

ZEROCON_EXPORT int32_t mt_llama_memory_seq_pos_max(int32_t ctx_ptr, int32_t seq_id) {
    if (!ctx_ptr) return -1;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    return llama_memory_seq_pos_max(llama_get_memory(ctx), seq_id);
}

ZEROCON_EXPORT int32_t mt_llama_decode_one(int32_t ctx_ptr, int32_t *tokens, int32_t n_tokens) {
    if (!ctx_ptr || !tokens || n_tokens <= 0) return -1;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    int32_t rc = llama_decode(ctx, llama_batch_get_one(tokens, n_tokens));
    g_last_logits_count[ctx] = (rc == 0) ? 1 : 0;
    return rc;
}

ZEROCON_EXPORT int32_t mt_llama_decode_flat(
    int32_t ctx_ptr,
    int32_t n_tokens,
    const int32_t *tokens,
    const int32_t *pos,
    const int32_t *n_seq_id,
    const int32_t *seq_ids_flat,
    const int8_t *logits
) {
    if (!ctx_ptr || !tokens || n_tokens <= 0) return -1;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    int32_t max_seq = 1;
    for (int32_t i = 0; i < n_tokens; ++i) {
        if (n_seq_id[i] > max_seq) max_seq = n_seq_id[i];
    }
    llama_batch batch = llama_batch_init(n_tokens, 0, max_seq);
    batch.n_tokens = n_tokens;
    int32_t seq_cursor = 0;
    int32_t output_count = 0;
    for (int32_t i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = pos[i];
        batch.n_seq_id[i] = n_seq_id[i];
        for (int32_t j = 0; j < n_seq_id[i]; ++j) {
            batch.seq_id[i][j] = seq_ids_flat[seq_cursor++];
        }
        batch.logits[i] = logits[i];
        if (logits[i]) output_count++;
    }
    int32_t rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    g_last_logits_count[ctx] = (rc == 0) ? std::max<int32_t>(output_count, 1) : 0;
    return rc;
}

ZEROCON_EXPORT int32_t mt_llama_get_logits_ptr(int32_t ctx_ptr) {
    if (!ctx_ptr) return 0;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(llama_get_logits(ctx)));
}

ZEROCON_EXPORT int32_t mt_llama_get_logits_ith_ptr(int32_t ctx_ptr, int32_t i) {
    if (!ctx_ptr) return 0;
    auto *ctx = reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(llama_get_logits_ith(ctx, i)));
}

ZEROCON_EXPORT int32_t mt_llama_get_logits_count(int32_t ctx_ptr) {
    if (!ctx_ptr) return 0;
    auto *ctx = reinterpret_cast<const llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    auto it = g_last_logits_count.find(ctx);
    return it == g_last_logits_count.end() ? 0 : it->second;
}

ZEROCON_EXPORT int32_t mt_llama_get_vocab_size_for_ctx(int32_t ctx_ptr) {
    if (!ctx_ptr) return 0;
    auto *ctx = reinterpret_cast<const llama_context *>(static_cast<uintptr_t>(ctx_ptr));
    const llama_model *model = llama_get_model(ctx);
    if (!model) return 0;
    const llama_vocab *vocab = llama_model_get_vocab(model);
    return vocab ? llama_vocab_n_tokens(vocab) : 0;
}

ZEROCON_EXPORT int32_t mt_llama_sampler_init_greedy() {
    return static_cast<int32_t>(reinterpret_cast<uintptr_t>(llama_sampler_init_greedy()));
}

ZEROCON_EXPORT int32_t mt_llama_sampler_sample(int32_t smpl_ptr, int32_t ctx_ptr, int32_t idx) {
    if (!smpl_ptr || !ctx_ptr) return -1;
    return llama_sampler_sample(
        reinterpret_cast<llama_sampler *>(static_cast<uintptr_t>(smpl_ptr)),
        reinterpret_cast<llama_context *>(static_cast<uintptr_t>(ctx_ptr)),
        idx
    );
}

ZEROCON_EXPORT void mt_llama_sampler_accept(int32_t smpl_ptr, int32_t token) {
    if (!smpl_ptr) return;
    llama_sampler_accept(reinterpret_cast<llama_sampler *>(static_cast<uintptr_t>(smpl_ptr)), token);
}

ZEROCON_EXPORT void mt_llama_sampler_reset(int32_t smpl_ptr) {
    if (!smpl_ptr) return;
    llama_sampler_reset(reinterpret_cast<llama_sampler *>(static_cast<uintptr_t>(smpl_ptr)));
}

ZEROCON_EXPORT void mt_llama_sampler_free(int32_t smpl_ptr) {
    if (!smpl_ptr) return;
    llama_sampler_free(reinterpret_cast<llama_sampler *>(static_cast<uintptr_t>(smpl_ptr)));
}

// ---------------------------------------------------------------------------
// Assist / Completion API (multi-threaded drop-in replacement for completion.cpp)
// ---------------------------------------------------------------------------

ZEROCON_EXPORT int zerocon_completion_init(const char *path) {
    if (g_comp_context) return 1;
    llama_backend_init();
    auto params = llama_model_default_params();
    params.load_mode = LLAMA_LOAD_MODE_NONE;
    params.n_gpu_layers = 0;
    g_comp_model = llama_model_load_from_file(path, params);
    if (!g_comp_model) return 0;
    auto options = llama_context_default_params();
    options.n_ctx = kCompContextSize;
    options.n_batch = kCompBatchSize;
    options.n_ubatch = 256;
    options.n_threads = options.n_threads_batch = g_max_threads;
    options.n_outputs_max = 1;
    options.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    options.offload_kqv = false;
    options.no_perf = true;
    options.abort_callback = [](void *) { return comp_expired(); };
    g_comp_context = llama_init_from_model(g_comp_model, options);
    g_comp_sampler = g_comp_context ? llama_sampler_init_greedy() : nullptr;
    if (g_comp_sampler) {
        keyboard_assist_attach(g_comp_model);
        return 1;
    }
    if (g_comp_context) llama_free(g_comp_context);
    llama_model_free(g_comp_model);
    g_comp_context = nullptr;
    g_comp_model = nullptr;
    return 0;
}

ZEROCON_EXPORT const char *zerocon_keyboard_repair(const char *payload, int budget_ms) {
    if (!g_comp_context || budget_ms < 1 || budget_ms > 5000) return nullptr;
    g_comp_keyboard_output = keyboard_assist_request(payload, budget_ms);
    return g_comp_keyboard_output.c_str();
}

ZEROCON_EXPORT int zerocon_completion_stop() { return g_comp_stop_type; }

ZEROCON_EXPORT const char *zerocon_complete(
    const char *prompt,
    int max_tokens,
    int budget_ms,
    const char *stops,
    int stops_length
) {
    g_comp_output.clear();
    g_comp_stop_type = 0;
    if (!g_comp_context || max_tokens < 1 || max_tokens > 384 || budget_ms < 1 || budget_ms > 5000
        || stops_length < 0 || stops_length > 1024) return nullptr;
    const auto size = std::strlen(prompt);
    if (!size || size > 8192) return nullptr;
    g_comp_deadline = Clock::now() + std::chrono::milliseconds(budget_ms);
    const auto *vocab = llama_model_get_vocab(g_comp_model);
    std::vector<llama_token> tokens(size + 16);
    const int count = llama_tokenize(vocab, prompt, int(size), tokens.data(), int(tokens.size()), true, true);
    if (count <= 0 || count + max_tokens > kCompContextSize) return nullptr;

    const auto clear_cache = [] {
        llama_memory_clear(llama_get_memory(g_comp_context), true);
        g_comp_cached_tokens.clear();
    };
    auto *memory = llama_get_memory(g_comp_context);
    int shared = 0;
    const int reusable = std::min(int(g_comp_cached_tokens.size()), count - 1);
    while (shared < reusable && g_comp_cached_tokens[shared] == tokens[shared]) shared++;
    if (shared == 0 || !llama_memory_seq_rm(memory, 0, shared, -1)
        || llama_memory_seq_pos_max(memory, 0) != shared - 1) {
        clear_cache();
        shared = 0;
    } else {
        g_comp_cached_tokens.resize(shared);
    }
    llama_sampler_reset(g_comp_sampler);
    for (int offset = shared; offset < count; offset += kCompBatchSize) {
        const int chunk = std::min(count - offset, kCompBatchSize);
        if (comp_expired() || llama_decode(g_comp_context, llama_batch_get_one(tokens.data() + offset, chunk)) != 0) {
            clear_cache();
            return nullptr;
        }
        g_comp_cached_tokens.insert(g_comp_cached_tokens.end(), tokens.begin() + offset, tokens.begin() + offset + chunk);
    }

    std::vector<char> piece(256);
    for (int i = 0; i < max_tokens; i++) {
        if (comp_expired()) return nullptr;
        auto token = llama_sampler_sample(g_comp_sampler, g_comp_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            g_comp_stop_type = 1;
            return g_comp_output.c_str();
        }
        llama_sampler_accept(g_comp_sampler, token);
        int piece_len = llama_token_to_piece(vocab, token, piece.data(), int(piece.size()), 0, false);
        if (piece_len < 0 && piece_len >= -8192) {
            piece.resize(-piece_len);
            piece_len = llama_token_to_piece(vocab, token, piece.data(), int(piece.size()), 0, false);
        }
        if (piece_len < 0 || g_comp_output.size() + piece_len > 8192) return nullptr;
        g_comp_output.append(piece.data(), piece_len);

        size_t stop = std::string::npos;
        for (std::string_view remaining(stops, stops_length); !remaining.empty();) {
            const auto end = remaining.find('\0');
            if (end == std::string_view::npos) return nullptr;
            if (end) stop = std::min(stop, g_comp_output.find(remaining.substr(0, end)));
            remaining.remove_prefix(end + 1);
        }
        if (stop != std::string::npos) {
            g_comp_output.resize(stop);
            g_comp_stop_type = 2;
            return g_comp_output.c_str();
        }
        if (i + 1 < max_tokens) {
            if (llama_decode(g_comp_context, llama_batch_get_one(&token, 1)) != 0) {
                clear_cache();
                return nullptr;
            }
            g_comp_cached_tokens.push_back(token);
        }
    }
    g_comp_stop_type = 3;
    return g_comp_output.c_str();
}

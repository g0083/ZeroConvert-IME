// Thin WASI C shim linked into zerocon_model.wasm.
// Forwards all llama.cpp inference calls to the multi-threaded Emscripten
// llama.cpp module via the "zerocon_llama" WebAssembly import namespace.
#include "llama.h"
#include "ggml-backend.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LLAMA_IMPORT(name) __attribute__((import_module("zerocon_llama"), import_name(name)))

LLAMA_IMPORT("backend_init") extern void host_llama_backend_init(void);
LLAMA_IMPORT("backend_free") extern void host_llama_backend_free(void);
LLAMA_IMPORT("model_load") extern int32_t host_llama_model_load(const char *path, int32_t path_len);
LLAMA_IMPORT("model_free") extern void host_llama_model_free(int32_t model);
LLAMA_IMPORT("model_size") extern int64_t host_llama_model_size(int32_t model);
LLAMA_IMPORT("model_get_vocab") extern int32_t host_llama_model_get_vocab(int32_t model);
LLAMA_IMPORT("vocab_n_tokens") extern int32_t host_llama_vocab_n_tokens(int32_t vocab);
LLAMA_IMPORT("vocab_bos") extern int32_t host_llama_vocab_bos(int32_t vocab);
LLAMA_IMPORT("vocab_eos") extern int32_t host_llama_vocab_eos(int32_t vocab);
LLAMA_IMPORT("vocab_is_eog") extern int32_t host_llama_vocab_is_eog(int32_t vocab, int32_t token);
LLAMA_IMPORT("tokenize") extern int32_t host_llama_tokenize(
    int32_t vocab, const char *text, int32_t text_len,
    int32_t *tokens, int32_t n_tokens_max, int32_t add_special, int32_t parse_special);
LLAMA_IMPORT("token_to_piece") extern int32_t host_llama_token_to_piece(
    int32_t vocab, int32_t token, char *buf, int32_t length, int32_t lstrip, int32_t special);
LLAMA_IMPORT("init_from_model") extern int32_t host_llama_init_from_model(
    int32_t model, uint32_t n_ctx, uint32_t n_batch, uint32_t n_ubatch,
    int32_t n_threads, int32_t n_threads_batch, int32_t flash_attn_type);
LLAMA_IMPORT("free_ctx") extern void host_llama_free(int32_t ctx);
LLAMA_IMPORT("n_ctx") extern uint32_t host_llama_n_ctx(int32_t ctx);
LLAMA_IMPORT("n_batch") extern uint32_t host_llama_n_batch(int32_t ctx);
LLAMA_IMPORT("set_n_threads") extern void host_llama_set_n_threads(int32_t ctx, int32_t n_threads, int32_t n_threads_batch);
LLAMA_IMPORT("memory_clear") extern void host_llama_memory_clear(int32_t ctx, int32_t data);
LLAMA_IMPORT("memory_seq_rm") extern int32_t host_llama_memory_seq_rm(int32_t ctx, int32_t seq_id, int32_t p0, int32_t p1);
LLAMA_IMPORT("memory_seq_cp") extern void host_llama_memory_seq_cp(int32_t ctx, int32_t src, int32_t dst, int32_t p0, int32_t p1);
LLAMA_IMPORT("memory_seq_pos_max") extern int32_t host_llama_memory_seq_pos_max(int32_t ctx, int32_t seq_id);
LLAMA_IMPORT("decode_one") extern int32_t host_llama_decode_one(int32_t ctx, const int32_t *tokens, int32_t n_tokens);
LLAMA_IMPORT("decode_flat") extern int32_t host_llama_decode_flat(
    int32_t ctx, int32_t n_tokens, const int32_t *tokens, const int32_t *pos,
    const int32_t *n_seq_id, const int32_t *seq_ids_flat, int32_t total_seq_ids, const int8_t *logits);
LLAMA_IMPORT("logits_size") extern int32_t host_llama_logits_size(int32_t ctx);
LLAMA_IMPORT("copy_logits") extern int32_t host_llama_copy_logits(int32_t ctx, float *dst, int32_t count);
LLAMA_IMPORT("logits_ith_size") extern int32_t host_llama_logits_ith_size(int32_t ctx);
LLAMA_IMPORT("copy_logits_ith") extern int32_t host_llama_copy_logits_ith(int32_t ctx, int32_t i, float *dst, int32_t count);
LLAMA_IMPORT("sampler_init_greedy") extern int32_t host_llama_sampler_init_greedy(void);
LLAMA_IMPORT("sampler_sample") extern int32_t host_llama_sampler_sample(int32_t smpl, int32_t ctx, int32_t idx);
LLAMA_IMPORT("sampler_accept") extern void host_llama_sampler_accept(int32_t smpl, int32_t token);
LLAMA_IMPORT("sampler_reset") extern void host_llama_sampler_reset(int32_t smpl);
LLAMA_IMPORT("sampler_free") extern void host_llama_sampler_free(int32_t smpl);

static float *g_logits_buf = NULL;
static int32_t g_logits_cap = 0;

static float *ensure_logits_buf(int32_t count) {
    if (count <= 0) return NULL;
    if (count > g_logits_cap) {
        float *next = (float *)realloc(g_logits_buf, (size_t)count * sizeof(float));
        if (!next) return NULL;
        g_logits_buf = next;
        g_logits_cap = count;
    }
    return g_logits_buf;
}

void ggml_backend_load_all(void) {}
void ggml_backend_load_all_from_path(const char *dir_path) { (void)dir_path; }
ggml_backend_dev_t ggml_backend_dev_by_type(enum ggml_backend_dev_type type) { (void)type; return NULL; }
bool llama_supports_gpu_offload(void) { return false; }

void llama_backend_init(void) { host_llama_backend_init(); }
void llama_backend_free(void) { host_llama_backend_free(); }

struct llama_model_params llama_model_default_params(void) {
    struct llama_model_params params;
    memset(&params, 0, sizeof(params));
    params.load_mode = LLAMA_LOAD_MODE_NONE;
    params.n_gpu_layers = 0;
    return params;
}

struct llama_context_params llama_context_default_params(void) {
    struct llama_context_params params;
    memset(&params, 0, sizeof(params));
    params.n_ctx = 512;
    params.n_batch = 512;
    params.n_ubatch = 256;
    params.n_seq_max = 2;
    params.n_threads = 4;
    params.n_threads_batch = 4;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    params.offload_kqv = false;
    params.no_perf = true;
    return params;
}

struct llama_model *llama_model_load_from_file(const char *path_model, struct llama_model_params params) {
    (void)params;
    if (!path_model) return NULL;
    int32_t h = host_llama_model_load(path_model, (int32_t)strlen(path_model));
    return (struct llama_model *)(uintptr_t)(uint32_t)h;
}

void llama_model_free(struct llama_model *model) {
    host_llama_model_free((int32_t)(uintptr_t)model);
}

uint64_t llama_model_size(const struct llama_model *model) {
    return (uint64_t)host_llama_model_size((int32_t)(uintptr_t)model);
}

const struct llama_vocab *llama_model_get_vocab(const struct llama_model *model) {
    int32_t h = host_llama_model_get_vocab((int32_t)(uintptr_t)model);
    return (const struct llama_vocab *)(uintptr_t)(uint32_t)h;
}

int32_t llama_vocab_n_tokens(const struct llama_vocab *vocab) {
    return host_llama_vocab_n_tokens((int32_t)(uintptr_t)vocab);
}

llama_token llama_vocab_bos(const struct llama_vocab *vocab) {
    return host_llama_vocab_bos((int32_t)(uintptr_t)vocab);
}

llama_token llama_vocab_eos(const struct llama_vocab *vocab) {
    return host_llama_vocab_eos((int32_t)(uintptr_t)vocab);
}

bool llama_vocab_is_eog(const struct llama_vocab *vocab, llama_token token) {
    return host_llama_vocab_is_eog((int32_t)(uintptr_t)vocab, token) != 0;
}

int32_t llama_tokenize(
    const struct llama_vocab *vocab,
    const char *text,
    int32_t text_len,
    llama_token *tokens,
    int32_t n_tokens_max,
    bool add_special,
    bool parse_special
) {
    return host_llama_tokenize(
        (int32_t)(uintptr_t)vocab, text, text_len, tokens, n_tokens_max,
        add_special ? 1 : 0, parse_special ? 1 : 0
    );
}

int32_t llama_token_to_piece(
    const struct llama_vocab *vocab,
    llama_token token,
    char *buf,
    int32_t length,
    int32_t lstrip,
    bool special
) {
    return host_llama_token_to_piece(
        (int32_t)(uintptr_t)vocab, token, buf, length, lstrip, special ? 1 : 0
    );
}

struct llama_context *llama_init_from_model(struct llama_model *model, struct llama_context_params params) {
    int32_t h = host_llama_init_from_model(
        (int32_t)(uintptr_t)model, params.n_ctx, params.n_batch, params.n_ubatch,
        params.n_threads, params.n_threads_batch, (int32_t)params.flash_attn_type
    );
    return (struct llama_context *)(uintptr_t)(uint32_t)h;
}

void llama_free(struct llama_context *ctx) {
    host_llama_free((int32_t)(uintptr_t)ctx);
}

uint32_t llama_n_ctx(const struct llama_context *ctx) {
    return host_llama_n_ctx((int32_t)(uintptr_t)ctx);
}

uint32_t llama_n_batch(const struct llama_context *ctx) {
    return host_llama_n_batch((int32_t)(uintptr_t)ctx);
}

void llama_set_n_threads(struct llama_context *ctx, int32_t n_threads, int32_t n_threads_batch) {
    host_llama_set_n_threads((int32_t)(uintptr_t)ctx, n_threads, n_threads_batch);
}

llama_memory_t llama_get_memory(const struct llama_context *ctx) {
    // Represent llama_memory_t directly as the owning context handle.
    return (llama_memory_t)(uintptr_t)ctx;
}

void llama_memory_clear(llama_memory_t mem, bool data) {
    host_llama_memory_clear((int32_t)(uintptr_t)mem, data ? 1 : 0);
}

bool llama_memory_seq_rm(llama_memory_t mem, llama_seq_id seq_id, llama_pos p0, llama_pos p1) {
    return host_llama_memory_seq_rm((int32_t)(uintptr_t)mem, seq_id, p0, p1) != 0;
}

void llama_memory_seq_cp(llama_memory_t mem, llama_seq_id seq_id_src, llama_seq_id seq_id_dst, llama_pos p0, llama_pos p1) {
    host_llama_memory_seq_cp((int32_t)(uintptr_t)mem, seq_id_src, seq_id_dst, p0, p1);
}

llama_pos llama_memory_seq_pos_max(llama_memory_t mem, llama_seq_id seq_id) {
    return host_llama_memory_seq_pos_max((int32_t)(uintptr_t)mem, seq_id);
}

struct llama_batch llama_batch_get_one(llama_token *tokens, int32_t n_tokens) {
    struct llama_batch batch;
    memset(&batch, 0, sizeof(batch));
    batch.n_tokens = n_tokens;
    batch.token = tokens;
    return batch;
}

struct llama_batch llama_batch_init(int32_t n_tokens_alloc, int32_t embd, int32_t n_seq_max) {
    struct llama_batch batch;
    memset(&batch, 0, sizeof(batch));
    if (embd) {
        batch.embd = (float *)calloc((size_t)n_tokens_alloc * (size_t)embd, sizeof(float));
    } else {
        batch.token = (llama_token *)calloc((size_t)n_tokens_alloc, sizeof(llama_token));
    }
    batch.pos = (llama_pos *)calloc((size_t)n_tokens_alloc, sizeof(llama_pos));
    batch.n_seq_id = (int32_t *)calloc((size_t)n_tokens_alloc, sizeof(int32_t));
    batch.seq_id = (llama_seq_id **)calloc((size_t)(n_tokens_alloc + 1), sizeof(llama_seq_id *));
    for (int32_t i = 0; i < n_tokens_alloc; ++i) {
        batch.seq_id[i] = (llama_seq_id *)calloc((size_t)n_seq_max, sizeof(llama_seq_id));
    }
    batch.logits = (int8_t *)calloc((size_t)n_tokens_alloc, sizeof(int8_t));
    return batch;
}

void llama_batch_free(struct llama_batch batch) {
    free(batch.token);
    free(batch.embd);
    free(batch.pos);
    free(batch.n_seq_id);
    if (batch.seq_id) {
        for (int32_t i = 0; batch.seq_id[i] != NULL; ++i) {
            free(batch.seq_id[i]);
        }
        free(batch.seq_id);
    }
    free(batch.logits);
}

int32_t llama_decode(struct llama_context *ctx, struct llama_batch batch) {
    if (!ctx || batch.n_tokens <= 0 || !batch.token) return -1;
    if (!batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        return host_llama_decode_one((int32_t)(uintptr_t)ctx, batch.token, batch.n_tokens);
    }
    int32_t total_seq = 0;
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        total_seq += batch.n_seq_id[i];
    }
    int32_t *flat_seq = (int32_t *)malloc((size_t)(total_seq > 0 ? total_seq : 1) * sizeof(int32_t));
    if (!flat_seq) return -1;
    int32_t cursor = 0;
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        for (int32_t j = 0; j < batch.n_seq_id[i]; ++j) {
            flat_seq[cursor++] = batch.seq_id[i][j];
        }
    }
    int32_t rc = host_llama_decode_flat(
        (int32_t)(uintptr_t)ctx, batch.n_tokens, batch.token, batch.pos,
        batch.n_seq_id, flat_seq, total_seq, batch.logits
    );
    free(flat_seq);
    return rc;
}

float *llama_get_logits(struct llama_context *ctx) {
    int32_t count = host_llama_logits_size((int32_t)(uintptr_t)ctx);
    float *buf = ensure_logits_buf(count);
    if (!buf) return NULL;
    return host_llama_copy_logits((int32_t)(uintptr_t)ctx, buf, count) ? buf : NULL;
}

float *llama_get_logits_ith(struct llama_context *ctx, int32_t i) {
    int32_t count = host_llama_logits_ith_size((int32_t)(uintptr_t)ctx);
    float *buf = ensure_logits_buf(count);
    if (!buf) return NULL;
    return host_llama_copy_logits_ith((int32_t)(uintptr_t)ctx, i, buf, count) ? buf : NULL;
}

struct llama_sampler *llama_sampler_init_greedy(void) {
    return (struct llama_sampler *)(uintptr_t)(uint32_t)host_llama_sampler_init_greedy();
}

llama_token llama_sampler_sample(struct llama_sampler *smpl, struct llama_context *ctx, int32_t idx) {
    return host_llama_sampler_sample((int32_t)(uintptr_t)smpl, (int32_t)(uintptr_t)ctx, idx);
}

void llama_sampler_accept(struct llama_sampler *smpl, llama_token token) {
    host_llama_sampler_accept((int32_t)(uintptr_t)smpl, token);
}

void llama_sampler_reset(struct llama_sampler *smpl) {
    host_llama_sampler_reset((int32_t)(uintptr_t)smpl);
}

void llama_sampler_free(struct llama_sampler *smpl) {
    host_llama_sampler_free((int32_t)(uintptr_t)smpl);
}

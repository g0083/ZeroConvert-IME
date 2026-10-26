#pragma once
#include "llama-current.h"
// The Windows Swift engine uses the pre-memory-API names. Keep the ABI
// translation here; the model parameters always come from the current header.
static inline bool llama_kv_cache_seq_rm(struct llama_context *ctx, llama_seq_id seq, llama_pos a, llama_pos b) {
    return llama_memory_seq_rm(llama_get_memory(ctx), seq, a, b);
}
static inline void llama_kv_cache_seq_cp(struct llama_context *ctx, llama_seq_id src, llama_seq_id dst, llama_pos a, llama_pos b) {
    llama_memory_seq_cp(llama_get_memory(ctx), src, dst, a, b);
}
static inline llama_pos llama_kv_cache_seq_pos_max(struct llama_context *ctx, llama_seq_id seq) {
    return llama_memory_seq_pos_max(llama_get_memory(ctx), seq);
}

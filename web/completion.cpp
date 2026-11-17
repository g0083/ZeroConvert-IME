// CPU-only llama.cpp completion in a dedicated, single-threaded Web Worker.
#include "llama.h"
#include "keyboard_assist.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
llama_model *model = nullptr;
llama_context *context = nullptr;
llama_sampler *sampler = nullptr;
std::vector<llama_token> cached_tokens;
Clock::time_point deadline;
std::string output;
std::string keyboard_output;
int stop_type;
// Match the Windows launcher's CPU prediction server.
constexpr int context_size = 512;
constexpr int batch_size = 512;

bool expired() { return Clock::now() >= deadline; }
}

// The assistance Worker drops old generations and bounds each synchronous call.
long long assist_epoch() { return 0; }

extern "C" int zerocon_completion_init(const char *path) {
    if (context) return 1;
    llama_backend_init();
    auto params = llama_model_default_params();
    params.load_mode = LLAMA_LOAD_MODE_NONE;
    params.n_gpu_layers = 0;
    model = llama_model_load_from_file(path, params);
    if (!model) return 0;
    auto options = llama_context_default_params();
    options.n_ctx = context_size;
    options.n_batch = batch_size;
    options.n_ubatch = 256;
    options.n_threads = options.n_threads_batch = 1;
    options.n_outputs_max = 1;
    options.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    options.offload_kqv = false;
    options.no_perf = true;
    options.abort_callback = [](void *) { return expired(); };
    context = llama_init_from_model(model, options);
    sampler = context ? llama_sampler_init_greedy() : nullptr;
    if (sampler) {
        keyboard_assist_attach(model);
        return 1;
    }
    if (context) llama_free(context);
    llama_model_free(model);
    context = nullptr;
    model = nullptr;
    return 0;
}

extern "C" const char *zerocon_keyboard_repair(const char *payload, int budget_ms) {
    if (!context || budget_ms < 1 || budget_ms > 5000) return nullptr;
    keyboard_output = keyboard_assist_request(payload, budget_ms);
    return keyboard_output.c_str();
}

// Stop reasons: 0 = rejected, 1 = EOG, 2 = stop string, 3 = token limit.
extern "C" int zerocon_completion_stop() { return stop_type; }

extern "C" const char *zerocon_complete(const char *prompt, int max_tokens, int budget_ms,
                                        const char *stops, int stops_length) {
    output.clear();
    stop_type = 0;
    if (!context || max_tokens < 1 || max_tokens > 384 || budget_ms < 1 || budget_ms > 5000
        || stops_length < 0 || stops_length > 1024) return nullptr;
    const auto size = std::strlen(prompt);
    if (!size || size > 8192) return nullptr;
    deadline = Clock::now() + std::chrono::milliseconds(budget_ms);
    const auto *vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(size + 16);
    // The Windows /completion route adds model special tokens and parses
    // special tokens already present in the prompt.
    const int count = llama_tokenize(vocab, prompt, int(size), tokens.data(), int(tokens.size()), true, true);
    if (count <= 0 || count + max_tokens > context_size) return nullptr;

    const auto clear_cache = [] {
        llama_memory_clear(llama_get_memory(context), true);
        cached_tokens.clear();
    };
    auto *memory = llama_get_memory(context);
    int shared = 0;
    const int reusable = std::min(int(cached_tokens.size()), count - 1);
    while (shared < reusable && cached_tokens[shared] == tokens[shared]) shared++;
    if (shared == 0 || !llama_memory_seq_rm(memory, 0, shared, -1)
        || llama_memory_seq_pos_max(memory, 0) != shared - 1) {
        clear_cache();
        shared = 0;
    } else {
        cached_tokens.resize(shared);
    }
    llama_sampler_reset(sampler);
    for (int offset = shared; offset < count; offset += batch_size) {
        const int size = std::min(count - offset, batch_size);
        if (expired() || llama_decode(context, llama_batch_get_one(tokens.data() + offset, size)) != 0) {
            clear_cache();
            return nullptr;
        }
        cached_tokens.insert(cached_tokens.end(), tokens.begin() + offset, tokens.begin() + offset + size);
    }

    std::vector<char> piece(256);
    for (int i = 0; i < max_tokens; i++) {
        if (expired()) return nullptr;
        auto token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            stop_type = 1;
            return output.c_str();
        }
        llama_sampler_accept(sampler, token);
        int size = llama_token_to_piece(vocab, token, piece.data(), int(piece.size()), 0, false);
        if (size < 0 && size >= -8192) {
            piece.resize(-size);
            size = llama_token_to_piece(vocab, token, piece.data(), int(piece.size()), 0, false);
        }
        if (size < 0 || output.size() + size > 8192) return nullptr;
        output.append(piece.data(), size);

        size_t stop = std::string::npos;
        for (std::string_view remaining(stops, stops_length); !remaining.empty();) {
            const auto end = remaining.find('\0');
            if (end == std::string_view::npos) return nullptr;
            if (end) stop = std::min(stop, output.find(remaining.substr(0, end)));
            remaining.remove_prefix(end + 1);
        }
        if (stop != std::string::npos) {
            output.resize(stop);
            stop_type = 2;
            return output.c_str();
        }
        if (i + 1 < max_tokens) {
            if (llama_decode(context, llama_batch_get_one(&token, 1)) != 0) {
                clear_cache();
                return nullptr;
            }
            cached_tokens.push_back(token);
        }
    }
    stop_type = 3;
    return output.c_str();
}

#pragma once

#include "llama.h"
#include <algorithm>
#include <chrono>
#include <string>
#include <vector>

struct GenerationResult {
    std::string reason;
    std::string text;
};

// At the token cap, a byte-fallback token can leave an otherwise valid UTF-8
// result mid-codepoint. Normalize before either the Windows or JNI transport.
inline std::string benchmark_utf8(const std::string &bytes) {
    std::string output;
    for (size_t i = 0; i < bytes.size();) {
        const auto lead = static_cast<unsigned char>(bytes[i]);
        int width = lead < 0x80 ? 1 : lead >= 0xC2 && lead <= 0xDF ? 2 :
            lead >= 0xE0 && lead <= 0xEF ? 3 : lead >= 0xF0 && lead <= 0xF4 ? 4 : 0;
        if (width > 0 && bytes.size() - i < static_cast<size_t>(width)) break;
        unsigned point = width == 1 ? lead : lead & (0x7Fu >> width);
        for (int j = 1; j < width; ++j) {
            const auto byte = static_cast<unsigned char>(bytes[i + j]);
            if ((byte & 0xC0) != 0x80) { width = 0; break; }
            point = (point << 6) | (byte & 0x3F);
        }
        const unsigned lower[] = {0, 0, 0x80, 0x800, 0x10000};
        if (width == 0 || point < lower[width] || point > 0x10FFFF ||
            (point >= 0xD800 && point <= 0xDFFF)) {
            output += "\xEF\xBF\xBD";
            ++i;
        } else {
            output.append(bytes, i, width);
            i += width;
        }
    }
    return output;
}

template <class Runtime, class CancelledReason>
GenerationResult generate_greedy(Runtime &run, const std::string &text, int max_tokens,
                                 CancelledReason cancelled_reason) {
    if (text.empty() || text.size() > 8192 || max_tokens < 1 || max_tokens > 384)
        return {"error", {}};
    const auto *vocab = llama_model_get_vocab(run.model);
    std::vector<llama_token> tokens(text.size() + 16);
    const int n = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()),
                                 tokens.data(), static_cast<int>(tokens.size()), false, true);
    if (n <= 0 || n + max_tokens > static_cast<int>(llama_n_ctx(run.context)))
        return {"error", {}};
    tokens.resize(n);
    auto *memory = llama_get_memory(run.context);
    auto invalidate = [&] {
        llama_memory_clear(memory, true);
        run.cached_tokens.clear();
    };
    int prefix = 0;
    const auto limit = std::min(run.cached_tokens.size(), static_cast<size_t>(n - 1));
    while (static_cast<size_t>(prefix) < limit && run.cached_tokens[prefix] == tokens[prefix])
        ++prefix;
    if (prefix == 0 || !llama_memory_seq_rm(memory, 0, prefix, -1) ||
        llama_memory_seq_pos_max(memory, 0) != prefix - 1) {
        invalidate();
        prefix = 0;
    }
    llama_sampler_reset(run.sampler);
    llama_set_n_threads(run.context, run.decode_threads,
                        n - prefix <= 4 ? run.decode_threads : run.batch_threads);
    run.cached_tokens.resize(prefix);
    auto failure = [&]() -> GenerationResult {
        invalidate();
        return {run.cancelled() ? cancelled_reason() : "error", {}};
    };
    for (int start = prefix; start < n;) {
        if (run.cancelled()) return {cancelled_reason(), {}};
        const int count = std::min(n - start, static_cast<int>(llama_n_batch(run.context)));
        if (llama_decode(run.context, llama_batch_get_one(tokens.data() + start, count)) != 0)
            return failure();
        run.cached_tokens.insert(run.cached_tokens.end(), tokens.begin() + start,
                                  tokens.begin() + start + count);
        start += count;
    }
    llama_set_n_threads(run.context, run.decode_threads, run.decode_threads);
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        if (run.cancelled()) return {cancelled_reason(), {}};
        auto token = llama_sampler_sample(run.sampler, run.context, -1);
        if (llama_vocab_is_eog(vocab, token)) return {"eos", output};
        llama_sampler_accept(run.sampler, token);
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (length < 0 && length >= -8192) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (length < 0) return {"error", {}};
        output.append(piece.data(), length);
        size_t stop = std::string::npos;
        for (const char *marker : {"</s>", "<s>", "\n", "\r", "\xEE\xB8\x80",
                                   "\xEE\xB8\x81", "\xEE\xB8\x82", "\xEE\xB8\x83"})
            stop = std::min(stop, output.find(marker));
        if (stop != std::string::npos) return {"stop", output.substr(0, stop)};
        if (output.size() > 8192) return {"error", {}};
        if (llama_decode(run.context, llama_batch_get_one(&token, 1)) != 0) return failure();
        run.cached_tokens.push_back(token);
    }
    return {"limit", output};
}

struct BenchmarkModel {
    using Clock = std::chrono::steady_clock;
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> cached_tokens;
    int decode_threads = 4;
    int batch_threads = 6;
    Clock::time_point deadline = Clock::time_point::max();

    BenchmarkModel() = default;
    BenchmarkModel(const BenchmarkModel &) = delete;
    BenchmarkModel &operator=(const BenchmarkModel &) = delete;
    ~BenchmarkModel() {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
    bool cancelled() const { return Clock::now() >= deadline; }

    bool load(const char *path, int gpu_layers) {
        auto params = llama_model_default_params();
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.n_gpu_layers = gpu_layers;
        params.split_mode = LLAMA_SPLIT_MODE_NONE;
        ggml_backend_dev_t cpu_devices[2] = {nullptr, nullptr};
        if (gpu_layers <= 0) {
            if (auto *cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
                cpu_devices[0] = cpu_dev;
                params.devices = cpu_devices;
            }
        }
        model = llama_model_load_from_file(path, params);
        if (!model) return false;
        auto config = llama_context_default_params();
        config.n_ctx = 512;
        config.n_batch = 256;
        config.n_ubatch = 256;
        config.n_outputs_max = 1;
        config.n_threads = decode_threads;
        config.n_threads_batch = batch_threads;
        config.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        config.offload_kqv = gpu_layers > 0;
        config.no_perf = true;
        config.abort_callback = [](void *data) { return static_cast<BenchmarkModel *>(data)->cancelled(); };
        config.abort_callback_data = this;
        context = llama_init_from_model(model, config);
        sampler = context ? llama_sampler_init_greedy() : nullptr;
        return sampler != nullptr;
    }

    GenerationResult complete(const std::string &prompt, int tokens, int timeout_ms) {
        deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
        auto result = generate_greedy(*this, prompt, tokens, [] { return "timeout"; });
        llama_synchronize(context);
        result.text = benchmark_utf8(result.text);
        return result;
    }

    bool warm_up() {
        const auto result = complete(u8"\uEE02\uEE00\u30C8\u30A6\u30AD\u30E7\u30A6\uEE01", 16, 2000);
        return (result.reason == "eos" || result.reason == "stop") && !result.text.empty();
    }
};

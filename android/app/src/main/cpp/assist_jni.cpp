#include <jni.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"
#include "assist_jni.h"

namespace {
using Clock = std::chrono::steady_clock;
std::atomic<long long> epoch{0};
std::atomic<long long> translation_epoch{0};

struct Runtime {
    std::atomic<long long> * epoch_counter = nullptr;
    std::mutex mutex;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    std::string path;
    bool mmap = false;
    int context_size = 1024;
    int threads = 2;
    long long request_epoch = -1;
    Clock::time_point deadline = Clock::time_point::max();

    bool cancelled() const {
        return (epoch_counter ? epoch_counter->load() : 0) != request_epoch || Clock::now() >= deadline;
    }
    void clear() {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        sampler = nullptr; context = nullptr; model = nullptr; path.clear();
    }
    bool load(const std::string & file, bool use_mmap = false, int ctx_size = 1024, int thread_count = 2) {
        if (file == path && context && mmap == use_mmap && context_size == ctx_size && threads == thread_count) {
            return true;
        }
        clear();
        if (file.empty()) return false;
        auto params = llama_model_default_params();
        params.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        model = llama_model_load_from_file(file.c_str(), params);
        if (!model) return false;
        auto cp = llama_context_default_params();
        cp.n_ctx = ctx_size;
        cp.n_batch = 128;
        cp.n_threads = thread_count;
        cp.n_threads_batch = thread_count;
        cp.abort_callback = [](void * data) { return static_cast<Runtime *>(data)->cancelled(); };
        cp.abort_callback_data = this;
        context = llama_init_from_model(model, cp);
        if (!context) { clear(); return false; }
        sampler = llama_sampler_init_greedy();
        if (!sampler) { clear(); return false; }
        path = file;
        mmap = use_mmap;
        context_size = ctx_size;
        threads = thread_count;
        return true;
    }
} prediction, corrector, translation;

struct RuntimeEpochs {
    RuntimeEpochs() {
        prediction.epoch_counter = &epoch;
        corrector.epoch_counter = &epoch;
        translation.epoch_counter = &translation_epoch;
    }
} runtime_epochs;

// GetStringUTFChars is Modified UTF-8. Encode real UTF-8 for supplementary context characters.
std::string utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const auto * chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::string result;
    for (jsize i = 0, n = env->GetStringLength(value); i < n; ++i) {
        unsigned cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
        }
        if (cp < 0x80) result += static_cast<char>(cp);
        else if (cp < 0x800) {
            result += static_cast<char>(0xC0 | (cp >> 6)); result += static_cast<char>(0x80 | (cp & 63));
        } else if (cp < 0x10000) {
            result += static_cast<char>(0xE0 | (cp >> 12)); result += static_cast<char>(0x80 | ((cp >> 6) & 63));
            result += static_cast<char>(0x80 | (cp & 63));
        } else {
            result += static_cast<char>(0xF0 | (cp >> 18)); result += static_cast<char>(0x80 | ((cp >> 12) & 63));
            result += static_cast<char>(0x80 | ((cp >> 6) & 63)); result += static_cast<char>(0x80 | (cp & 63));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring utf16(JNIEnv * env, const std::string & text) {
    std::vector<jchar> chars;
    for (size_t i = 0; i < text.size();) {
        unsigned char lead = text[i++];
        unsigned cp = lead; int count = 0;
        if (lead >= 0xF0 && lead <= 0xF4) { cp &= 7; count = 3; }
        else if (lead >= 0xE0 && lead <= 0xEF) { cp &= 15; count = 2; }
        else if (lead >= 0xC2 && lead <= 0xDF) { cp &= 31; count = 1; }
        else if (lead >= 0x80) return env->NewStringUTF("");
        const unsigned minimum = count == 3 ? 0x10000 : count == 2 ? 0x800 : count == 1 ? 0x80 : 0;
        if (i + count > text.size()) return env->NewStringUTF("");
        for (int k = 0; k < count; ++k) {
            unsigned char c = text[i++];
            if ((c & 0xC0) != 0x80) return env->NewStringUTF("");
            cp = (cp << 6) | (c & 63);
        }
        if (cp < minimum || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return env->NewStringUTF("");
        if (cp <= 0xFFFF) chars.push_back(static_cast<jchar>(cp));
        else { cp -= 0x10000; chars.push_back(0xD800 + (cp >> 10)); chars.push_back(0xDC00 + (cp & 1023)); }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

jobjectArray response(JNIEnv * env, const std::string & reason, const std::string & text = "") {
    auto cls = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(2, cls, nullptr);
    auto status = env->NewStringUTF(reason.c_str());
    auto content = utf16(env, text);
    env->SetObjectArrayElement(result, 0, status); env->SetObjectArrayElement(result, 1, content);
    env->DeleteLocalRef(status); env->DeleteLocalRef(content); env->DeleteLocalRef(cls);
    return result;
}
}

void assist_abort() { epoch.fetch_add(1); }
long long assist_epoch() { return epoch.load(); }
std::string assist_from_java(JNIEnv * env, jstring text) { return utf8(env, text); }
jstring assist_to_java(JNIEnv * env, const std::string & text) { return utf16(env, text); }
void assist_close() {
    assist_abort();
    translation_epoch.fetch_add(1);
    { std::lock_guard<std::mutex> lock(prediction.mutex); prediction.clear(); }
    { std::lock_guard<std::mutex> lock(corrector.mutex); corrector.clear(); }
    { std::lock_guard<std::mutex> lock(translation.mutex); translation.clear(); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeInitAssist(JNIEnv * env, jobject, jstring converter, jstring repair) {
    assist_abort();
    int ready = 0;
    { std::lock_guard<std::mutex> lock(prediction.mutex); if (prediction.load(utf8(env, converter))) ready |= 1; }
    { std::lock_guard<std::mutex> lock(corrector.mutex); if (corrector.load(utf8(env, repair))) ready |= 2; }
    return ready;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeAssistEpoch(JNIEnv *, jobject) { return epoch.load(); }

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeGenerate(JNIEnv * env, jobject, jint kind, jstring prompt,
                                                jint max_tokens, jint budget_ms, jlong expected_epoch) {
    if (kind != 0 && kind != 1) return response(env, "error");
    Runtime & run = kind == 0 ? prediction : corrector;
    std::unique_lock<std::mutex> lock(run.mutex, std::try_to_lock);
    if (!lock.owns_lock()) return response(env, "busy");
    if (!run.context || !run.model || !prompt || epoch.load() != expected_epoch) return response(env, "cancelled");
    const auto text = utf8(env, prompt);
    if (text.empty() || text.size() > 8192 || max_tokens < 1 || max_tokens > 384 || budget_ms < 1 || budget_ms > 2000) {
        return response(env, "error");
    }
    run.request_epoch = expected_epoch;
    run.deadline = Clock::now() + std::chrono::milliseconds(budget_ms);
    auto cancelled_reason = [&]() { return epoch.load() != expected_epoch ? "cancelled" : "timeout"; };
    const auto * vocab = llama_model_get_vocab(run.model);
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), tokens.data(), static_cast<int>(tokens.size()), false, true);
    if (n <= 0 || n + max_tokens > static_cast<int>(llama_n_ctx(run.context))) return response(env, "error");
    llama_memory_clear(llama_get_memory(run.context), true);
    llama_sampler_reset(run.sampler);
    for (int start = 0; start < n;) {
        if (run.cancelled()) return response(env, cancelled_reason());
        int size = std::min(n - start, static_cast<int>(llama_n_batch(run.context)));
        auto batch = llama_batch_get_one(tokens.data() + start, size);
        if (llama_decode(run.context, batch) != 0) return response(env, run.cancelled() ? cancelled_reason() : "error");
        start += size;
    }
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        if (run.cancelled()) return response(env, cancelled_reason());
        auto token = llama_sampler_sample(run.sampler, run.context, -1);
        if (llama_vocab_is_eog(vocab, token)) return response(env, "eos", output);
        llama_sampler_accept(run.sampler, token);
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (length < 0 && length >= -8192) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (length < 0) return response(env, "error");
        output.append(piece.data(), length);
        size_t stop = std::string::npos;
        for (const auto * marker : {"</s>", "<s>", "\n", "\r", "\xEE\xB8\x80", "\xEE\xB8\x81", "\xEE\xB8\x82", "\xEE\xB8\x83"}) {
            stop = std::min(stop, output.find(marker));
        }
        if (stop != std::string::npos) return response(env, "stop", output.substr(0, stop));
        if (output.size() > 8192) return response(env, "error");
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(run.context, batch) != 0) return response(env, run.cancelled() ? cancelled_reason() : "error");
    }
    return response(env, "limit", output);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeInitTranslation(JNIEnv * env, jobject, jstring model_path) {
    translation_epoch.fetch_add(1);
    std::lock_guard<std::mutex> lock(translation.mutex);
    const auto path = utf8(env, model_path);
    if (path.empty()) return JNI_FALSE;
    // The translation model is much larger than the conversion models. Use mmap
    // so file-backed pages can be reclaimed under memory pressure.
    return translation.load(path, true, 4096, 4) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeTranslationEpoch(JNIEnv *, jobject) {
    return translation_epoch.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeAbortTranslation(JNIEnv *, jobject) {
    translation_epoch.fetch_add(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeCloseTranslation(JNIEnv *, jobject) {
    translation_epoch.fetch_add(1);
    std::lock_guard<std::mutex> lock(translation.mutex);
    translation.clear();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeTranslate(JNIEnv * env, jobject, jstring prompt,
                                                    jint max_tokens, jint budget_ms,
                                                    jlong expected_epoch) {
    // Serialize translation requests. The caller runs on a worker coroutine, so
    // waiting for a previous aborted request to unwind is preferable to failing
    // a fresh request with "busy" during fast typing.
    std::unique_lock<std::mutex> lock(translation.mutex);
    if (!translation.context || !translation.model || !prompt ||
        translation_epoch.load() != expected_epoch) {
        return response(env, "cancelled");
    }
    const auto text = utf8(env, prompt);
    if (text.empty() || text.size() > 16384 || max_tokens < 1 || max_tokens > 512 ||
        budget_ms < 1 || budget_ms > 15000) {
        return response(env, "error");
    }
    translation.request_epoch = expected_epoch;
    translation.deadline = Clock::now() + std::chrono::milliseconds(budget_ms);
    auto cancelled_reason = [&]() {
        return translation_epoch.load() != expected_epoch ? "cancelled" : "timeout";
    };
    const auto * vocab = llama_model_get_vocab(translation.model);
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), tokens.data(),
                           static_cast<int>(tokens.size()), false, true);
    if (n <= 0 || n + max_tokens > static_cast<int>(llama_n_ctx(translation.context))) {
        return response(env, "error");
    }
    llama_memory_clear(llama_get_memory(translation.context), true);
    llama_sampler_reset(translation.sampler);
    for (int start = 0; start < n;) {
        if (translation.cancelled()) return response(env, cancelled_reason());
        int size = std::min(n - start, static_cast<int>(llama_n_batch(translation.context)));
        auto batch = llama_batch_get_one(tokens.data() + start, size);
        if (llama_decode(translation.context, batch) != 0) {
            return response(env, translation.cancelled() ? cancelled_reason() : "error");
        }
        start += size;
    }
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        if (translation.cancelled()) return response(env, cancelled_reason());
        auto token = llama_sampler_sample(translation.sampler, translation.context, -1);
        if (llama_vocab_is_eog(vocab, token)) return response(env, "eos", output);
        llama_sampler_accept(translation.sampler, token);
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (length < 0 && length >= -8192) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (length < 0) return response(env, "error");
        output.append(piece.data(), length);
        size_t stop = std::string::npos;
        for (const auto * marker : {"</s>", "<s>", "\n", "\r", "<|plamo:",
                                    "\xEE\xB8\x80", "\xEE\xB8\x81", "\xEE\xB8\x82", "\xEE\xB8\x83"}) {
            stop = std::min(stop, output.find(marker));
        }
        if (stop != std::string::npos) return response(env, "stop", output.substr(0, stop));
        if (output.size() > 8192) return response(env, "error");
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(translation.context, batch) != 0) {
            return response(env, translation.cancelled() ? cancelled_reason() : "error");
        }
    }
    return response(env, "limit", output);
}

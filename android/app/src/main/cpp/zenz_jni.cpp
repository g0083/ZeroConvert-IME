#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstdio>
#include <cstdarg>
#include <android/log.h>
#include "llama.h"

#include "azookey_louds.h"

#define TAG "ZenzNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mutex;
static std::atomic<bool> g_abort_flag{false};
static struct llama_model * g_model = nullptr;
static struct llama_context * g_ctx = nullptr;
static const struct llama_vocab * g_vocab = nullptr;
static struct llama_sampler * g_sampler = nullptr;
static std::string g_log_path;
static azookey::AzooKeyDictionary g_dictionary;

static void file_log(const char * fmt, ...) {
    if (g_log_path.empty()) return;
    FILE * f = fopen(g_log_path.c_str(), "a");
    if (!f) return;
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    fprintf(f, "\n");
    va_end(args);
    fclose(f);
}

static void llama_log_callback_android(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr) return;
    int android_level = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) {
        android_level = ANDROID_LOG_ERROR;
        file_log("[llama.cpp ERROR] %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        android_level = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_INFO) {
        android_level = ANDROID_LOG_INFO;
    }
    __android_log_print(android_level, "llama.cpp", "%s", text);
}

static bool load_model_internal(const char * path) {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    LOGI("Loading model from: %s", path);
    file_log("Model path: %s", path);

    llama_backend_init();

    struct llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_NONE; // Read directly into RAM on Android
    g_model = llama_model_load_from_file(path, mparams);

    if (g_model == nullptr) {
        LOGE("Failed to load llama model: %s", path);
        file_log("ERROR: Failed to load llama model: %s", path);
        return false;
    }

    g_vocab = llama_model_get_vocab(g_model);

    std::string path_str(path);
    bool is_q4km = (path_str.find("q4km") != std::string::npos || path_str.find("q4_k_m") != std::string::npos);
    bool is_q3km = (path_str.find("q3km") != std::string::npos || path_str.find("q3_k_m") != std::string::npos || path_str.find("zenz.gguf") != std::string::npos);
    bool is_small = (path_str.find("small") != std::string::npos && path_str.find("xsmall") == std::string::npos);

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 256; // 256 tokens fits entirely in CPU cache

    // Maximize performance across mobile Big cores (ARM Cortex-A7xx/X):
    // 4 threads fully parallelizes matrix multiplication without over-subscribing LITTLE cores.
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    LOGI("Using 4 threads for accelerated inference: path=%s, is_q4km=%d, is_q3km=%d, is_small=%d",
         path_str.c_str(), is_q4km, is_q3km, is_small);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("Failed to create llama context");
        file_log("ERROR: Failed to create llama context");
        llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }

    g_sampler = llama_sampler_init_greedy();

    LOGI("Zenz model loaded successfully! (threads=%d, is_q4km=%d, vocab=%d)", cparams.n_threads, is_q4km, llama_vocab_n_tokens(g_vocab));
    file_log("Zenz model and context initialized successfully! n_vocab=%d", llama_vocab_n_tokens(g_vocab));
    return true;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeInit(JNIEnv *env, jobject /* this */, jstring model_path, jstring log_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) {
        return JNI_TRUE;
    }

    if (log_path != nullptr) {
        const char * lp = env->GetStringUTFChars(log_path, nullptr);
        g_log_path = lp != nullptr ? lp : "";
        env->ReleaseStringUTFChars(log_path, lp);
        file_log("=== ZenzNative nativeInit started ===");
    }

    llama_log_set(llama_log_callback_android, nullptr);

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    bool ok = load_model_internal(path);
    env->ReleaseStringUTFChars(model_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeLoadModel(JNIEnv *env, jobject /* this */, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (model_path == nullptr) return JNI_FALSE;

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    bool ok = load_model_internal(path);
    env->ReleaseStringUTFChars(model_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static size_t count_utf8_chars(const std::string & str) {
    size_t count = 0;
    const unsigned char * p = reinterpret_cast<const unsigned char *>(str.data());
    size_t len = str.length();
    for (size_t i = 0; i < len; ++i) {
        if ((p[i] & 0xC0) != 0x80) {
            count++;
        }
    }
    return count;
}

// Safely converts UTF-8 (which may contain partial multi-byte sequences, PUA control tokens,
// or non-MUTF-8 bytes) into valid UTF-16 for Java String using env->NewString(jchar*, len).
// This COMPLETELY ELIMINATES Android ART CheckJNI crashes ("input is not valid Modified UTF-8: illegal continuation byte").
static jstring safe_new_string(JNIEnv *env, const std::string & utf8_str, const std::string & fallback = "") {
    std::vector<jchar> utf16;
    utf16.reserve(utf8_str.length() + 8);

    const unsigned char * p = reinterpret_cast<const unsigned char *>(utf8_str.data());
    size_t len = utf8_str.length();
    size_t i = 0;

    while (i < len) {
        unsigned char c = p[i];
        if (c < 0x80) {
            // ASCII: accept printable chars and tab
            if (c >= 0x20 || c == '\t') {
                utf16.push_back(static_cast<jchar>(c));
            }
            i += 1;
        } else if ((c & 0xE0) == 0xC0) {
            // 2-byte UTF-8 sequence
            if (i + 1 < len && (p[i + 1] & 0xC0) == 0x80) {
                uint32_t cp = ((c & 0x1F) << 6) | (p[i + 1] & 0x3F);
                if (cp >= 0x80) {
                    utf16.push_back(static_cast<jchar>(cp));
                }
                i += 2;
            } else {
                // Incomplete or invalid 2-byte sequence - safely skip
                i += 1;
            }
        } else if ((c & 0xF0) == 0xE0) {
            // 3-byte UTF-8 sequence
            if (i + 2 < len && (p[i + 1] & 0xC0) == 0x80 && (p[i + 2] & 0xC0) == 0x80) {
                uint32_t cp = ((c & 0x0F) << 12) | ((p[i + 1] & 0x3F) << 6) | (p[i + 2] & 0x3F);
                // Filter out Zenz PUA control tokens (\uEE00 - \uEE0F) and invalid surrogate range
                if (cp >= 0x0800 && (cp < 0xD800 || cp > 0xDFFF) && (cp < 0xEE00 || cp > 0xEE0F)) {
                    utf16.push_back(static_cast<jchar>(cp));
                }
                i += 3;
            } else {
                // Incomplete or invalid 3-byte sequence - safely skip
                i += 1;
            }
        } else if ((c & 0xF8) == 0xF0) {
            // 4-byte UTF-8 sequence (SMP: emojis, etc.)
            if (i + 3 < len && (p[i + 1] & 0xC0) == 0x80 && (p[i + 2] & 0xC0) == 0x80 && (p[i + 3] & 0xC0) == 0x80) {
                uint32_t cp = ((c & 0x07) << 18) | ((p[i + 1] & 0x3F) << 12) | ((p[i + 2] & 0x3F) << 6) | (p[i + 3] & 0x3F);
                if (cp >= 0x10000 && cp <= 0x10FFFF) {
                    // Encode as UTF-16 surrogate pair
                    cp -= 0x10000;
                    jchar high = static_cast<jchar>((cp >> 10) + 0xD800);
                    jchar low = static_cast<jchar>((cp & 0x3FF) + 0xDC00);
                    utf16.push_back(high);
                    utf16.push_back(low);
                }
                i += 4;
            } else {
                // Incomplete or invalid 4-byte sequence - safely skip
                i += 1;
            }
        } else {
            // Stray continuation byte or corrupt byte - safely skip
            i += 1;
        }
    }

    if (utf16.empty() && !fallback.empty()) {
        return safe_new_string(env, fallback, "");
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeConvert(JNIEnv *env, jobject /* this */, jstring left_context, jstring reading_katakana, jstring profile_str) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr || g_model == nullptr || g_vocab == nullptr) {
        file_log("ERROR: nativeConvert called but model/ctx/vocab is null!");
        return safe_new_string(env, "");
    }

    auto start_time = std::chrono::steady_clock::now();

    const char * lc = left_context != nullptr ? env->GetStringUTFChars(left_context, nullptr) : nullptr;
    const char * rk = reading_katakana != nullptr ? env->GetStringUTFChars(reading_katakana, nullptr) : nullptr;
    const char * pr = profile_str != nullptr ? env->GetStringUTFChars(profile_str, nullptr) : nullptr;

    // Format: optional \uEE03{profile} followed by \uEE02{leftContext}\uEE00{reading}\uEE01
    // UTF-8 bytes: \uEE00 -> \xEE\xB8\x80, \uEE01 -> \xEE\xB8\x81, \uEE02 -> \xEE\xB8\x82, \uEE03 -> \xEE\xB8\x83
    std::string prompt;
    if (pr != nullptr && pr[0] != '\0') {
        prompt += "\xEE\xB8\x83";
        prompt += pr;
    }
    prompt += "\xEE\xB8\x82";
    if (lc != nullptr && lc[0] != '\0') {
        prompt += lc;
    }
    prompt += "\xEE\xB8\x80";
    if (rk != nullptr) {
        prompt += rk;
    }
    prompt += "\xEE\xB8\x81";

    std::string reading_str = (rk != nullptr ? rk : "");
    std::string lc_str = (lc != nullptr ? lc : "");

    if (lc != nullptr) env->ReleaseStringUTFChars(left_context, lc);
    if (rk != nullptr) env->ReleaseStringUTFChars(reading_katakana, rk);
    if (pr != nullptr) env->ReleaseStringUTFChars(profile_str, pr);

    // Reset sampler state
    if (g_sampler != nullptr) {
        llama_sampler_reset(g_sampler);
    }

    // Clear KV cache
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }

    // Tokenize
    int32_t n_tokens_max = static_cast<int32_t>(prompt.length() + 32);
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(g_vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()), tokens.data(), n_tokens_max, false, true);

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt for reading: %s", reading_str.c_str());
        return safe_new_string(env, reading_str, reading_str);
    }

    // Prefill
    struct llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode prefill failed");
        return safe_new_string(env, reading_str, reading_str);
    }

    std::string result;
    const int max_generate_tokens = 40;
    char piece_buf[128];
    int generated_count = 0;
    llama_token last_token = -1;
    int token_repeat_count = 0;
    size_t max_allowed_chars = std::max<size_t>(1, count_utf8_chars(reading_str));

    g_abort_flag.store(false);

    for (int i = 0; i < max_generate_tokens; ++i) {
        if (g_abort_flag.load()) {
            return safe_new_string(env, "");
        }

        llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        llama_sampler_accept(g_sampler, token);

        // Stop on EOS (2 = <s>, 3 = </s>)
        if (token == llama_vocab_eos(g_vocab) || token == llama_vocab_eot(g_vocab) ||
            token == 2 || token == 3) {
            break;
        }

        // Loop detection: if same token repeats 2 or more times, break immediately
        if (token == last_token) {
            token_repeat_count++;
            if (token_repeat_count >= 1) { // 2nd occurrence of identical token
                break;
            }
        } else {
            last_token = token;
            token_repeat_count = 0;
        }

        int n_piece = llama_token_to_piece(g_vocab, token, piece_buf, sizeof(piece_buf), 0, false);
        if (n_piece > 0) {
            std::string piece(piece_buf, n_piece);
            // If piece contains Zenz control tokens, PUA prefix (\xEE\xB8), or newline, stop generation
            if (piece.find("\xEE\xB8") != std::string::npos ||
                piece.find('\n') != std::string::npos ||
                piece.find("<s>") != std::string::npos ||
                piece.find("</s>") != std::string::npos) {
                break;
            }
            result += piece;
        }

        // Stop if result length reaches or exceeds the reading character count
        if (count_utf8_chars(result) >= max_allowed_chars) {
            break;
        }

        generated_count++;
        struct llama_batch next_batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            break;
        }
    }

    auto end_time = std::chrono::steady_clock::now();
    long long elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();

    LOGI("Convert [%lld ms, %d tok]: reading='%s' -> result='%s'",
         elapsed_ms, generated_count, reading_str.c_str(), result.c_str());

    return safe_new_string(env, result, reading_str);
}

JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeAbort(JNIEnv * /* env */, jobject /* this */) {
    g_abort_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeClose(JNIEnv * /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeInitDict(JNIEnv *env, jobject /* this */, jstring dict_dir) {
    if (dict_dir == nullptr) return JNI_FALSE;
    const char * dd = env->GetStringUTFChars(dict_dir, nullptr);
    if (!dd) return JNI_FALSE;
    bool ok = g_dictionary.init(dd);
    env->ReleaseStringUTFChars(dict_dir, dd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeLookupDict(
    JNIEnv *env, jobject /* this */, jstring reading, jboolean prefix_search, jint max_count) {
    if (reading == nullptr) return safe_new_string(env, "");
    const char * rd = env->GetStringUTFChars(reading, nullptr);
    if (!rd) return safe_new_string(env, "");

    auto results = g_dictionary.lookup(rd, prefix_search == JNI_TRUE, (int)max_count);
    env->ReleaseStringUTFChars(reading, rd);

    // Encode to string: word\truby\tcost\n...
    std::string out;
    for (const auto& item : results) {
        out += item.word;
        out += '\t';
        out += item.ruby;
        out += '\t';
        out += std::to_string(item.cost);
        out += '\n';
    }

    return safe_new_string(env, out, "");
}

} // extern "C"

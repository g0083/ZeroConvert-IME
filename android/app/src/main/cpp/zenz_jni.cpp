#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdarg>
#include <android/log.h>
#include "llama.h"

#include "azookey_louds.h"
#include "assist_jni.h"

#define TAG "ZenzNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mutex;
static std::atomic<bool> g_abort_flag{false};
static long long g_normal_epoch = -1;
static std::chrono::steady_clock::time_point g_normal_deadline;
static bool normal_cancelled() {
    return g_abort_flag.load() || assist_epoch() != g_normal_epoch ||
        std::chrono::steady_clock::now() >= g_normal_deadline;
}

// Dedicated lock for dictionary access (LOUDS trees + txt3 shards).
// Kept separate from g_mutex so that dictionary lookups (typo correction /
// prediction assist) never serialize behind llama.cpp neural conversion.
static std::mutex g_dict_mutex;
static std::atomic<bool> g_dict_abort_flag{false};
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
    cparams.n_ctx = 1024;
    cparams.n_batch = 128;
    cparams.abort_callback = [](void *) { return normal_cancelled(); };

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
    const auto request_epoch = assist_epoch();
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr || g_model == nullptr || g_vocab == nullptr) {
        file_log("ERROR: nativeConvert called but model/ctx/vocab is null!");
        return assist_to_java(env, "");
    }

    auto start_time = std::chrono::steady_clock::now();

    const auto context_utf8 = assist_from_java(env, left_context);
    const auto reading_utf8 = assist_from_java(env, reading_katakana);
    const auto profile_utf8 = assist_from_java(env, profile_str);
    const char * lc = context_utf8.c_str();
    const char * rk = reading_utf8.c_str();
    const char * pr = profile_utf8.c_str();

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


    if (assist_epoch() != request_epoch || prompt.size() > 8192) return assist_to_java(env, "");
    g_normal_epoch = request_epoch;
    g_abort_flag.store(false);
    g_normal_deadline = start_time + std::chrono::milliseconds(1200);
    llama_sampler_reset(g_sampler);
    llama_memory_clear(llama_get_memory(g_ctx), true);
    const int max_generate_tokens = std::clamp(static_cast<int>(count_utf8_chars(reading_str) * 3 + 16), 32, 384);
    std::vector<llama_token> tokens(prompt.size() + 32);
    const int n_tokens = llama_tokenize(g_vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), static_cast<int>(tokens.size()), false, true);
    if (n_tokens <= 0 || n_tokens + max_generate_tokens > static_cast<int>(llama_n_ctx(g_ctx))) return assist_to_java(env, "");
    for (int start = 0; start < n_tokens;) {
        if (normal_cancelled()) return assist_to_java(env, "");
        int count = std::min(n_tokens - start, static_cast<int>(llama_n_batch(g_ctx)));
        auto batch = llama_batch_get_one(tokens.data() + start, count);
        if (llama_decode(g_ctx, batch) != 0) return assist_to_java(env, "");
        start += count;
    }
    std::string result;
    for (int i = 0; i < max_generate_tokens; ++i) {
        if (normal_cancelled()) return assist_to_java(env, "");
        auto token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, token)) return assist_to_java(env, result);
        llama_sampler_accept(g_sampler, token);
        std::vector<char> piece(256);
        int size = llama_token_to_piece(g_vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (size < 0 && size >= -8192) {
            piece.resize(-size);
            size = llama_token_to_piece(g_vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (size < 0) return assist_to_java(env, "");
        result.append(piece.data(), size);
        size_t stop = std::string::npos;
        for (const auto * marker : {"</s>", "<s>", "\n", "\r", "\xEE\xB8\x80", "\xEE\xB8\x81", "\xEE\xB8\x82", "\xEE\xB8\x83"}) {
            stop = std::min(stop, result.find(marker));
        }
        if (stop != std::string::npos) return assist_to_java(env, result.substr(0, stop));
        if (result.size() > 8192 || std::any_of(result.begin(), result.end(), [](unsigned char c) { return c < 32; })) return assist_to_java(env, "");
        auto next = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, next) != 0) return assist_to_java(env, "");
    }
    // A token/latency budget is not a word boundary. Fall back to dictionary candidates.
    return assist_to_java(env, "");
}

JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeAbort(JNIEnv * /* env */, jobject /* this */) {
    assist_abort();
    g_abort_flag.store(true);
    g_dict_abort_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeClose(JNIEnv * /* env */, jobject /* this */) {
    assist_close();
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
    std::lock_guard<std::mutex> lock(g_dict_mutex);
    if (dict_dir == nullptr) return JNI_FALSE;
    const char * dd = env->GetStringUTFChars(dict_dir, nullptr);
    if (!dd) return JNI_FALSE;
    bool ok = g_dictionary.init(dd);
    env->ReleaseStringUTFChars(dict_dir, dd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_ZenzNative_nativeLookupDict(
    JNIEnv *env, jobject /* this */, jstring reading, jboolean prefix_search, jint max_count, jintArray keyboard_ends,
    jobjectArray user_readings, jobjectArray user_words,
    jobjectArray learned_readings, jobjectArray learned_words, jintArray learned_ids, jfloatArray learned_scores) {
    if (reading == nullptr) return safe_new_string(env, "");
    const auto epoch = assist_epoch();
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(250);
    const auto rd = assist_from_java(env, reading);
    std::vector<int> keyboardSurfaceEnds;
    if (keyboard_ends != nullptr) {
        const auto count = env->GetArrayLength(keyboard_ends);
        if (count > 0 && count <= 512) {
            std::vector<jint> values(count);
            env->GetIntArrayRegion(keyboard_ends, 0, count, values.data());
            keyboardSurfaceEnds.assign(values.begin(), values.end());
        }
    }

    std::vector<azookey::DicdataElement> userEntries;
    if (user_readings != nullptr && user_words != nullptr) {
        const auto count = env->GetArrayLength(user_readings);
        if (count <= 50 && count == env->GetArrayLength(user_words)) {
            for (jsize index = 0; index < count; ++index) {
                if (epoch != assist_epoch()) return env->NewStringUTF("\x01");
                auto ruby = static_cast<jstring>(env->GetObjectArrayElement(user_readings, index));
                auto word = static_cast<jstring>(env->GetObjectArrayElement(user_words, index));
                if (ruby != nullptr && word != nullptr) {
                    userEntries.push_back({assist_from_java(env, word), assist_from_java(env, ruby),
                        -5.0f - static_cast<float>(index) * 0.01f, 1288, 1288, 501});
                }
                if (ruby != nullptr) env->DeleteLocalRef(ruby);
                if (word != nullptr) env->DeleteLocalRef(word);
            }
        }
    }

    if (learned_readings != nullptr && learned_words != nullptr && learned_ids != nullptr && learned_scores != nullptr) {
        const auto count = env->GetArrayLength(learned_readings);
        if (count <= 65536 && count == env->GetArrayLength(learned_words) && count == env->GetArrayLength(learned_scores) &&
            count * 3 == env->GetArrayLength(learned_ids)) {
            std::vector<jint> ids(count * 3);
            std::vector<jfloat> scores(count);
            if (count > 0) {
                env->GetIntArrayRegion(learned_ids, 0, count * 3, ids.data());
                env->GetFloatArrayRegion(learned_scores, 0, count, scores.data());
            }
            for (jsize index = 0; index < count; ++index) {
                if (epoch != assist_epoch() || std::chrono::steady_clock::now() >= deadline) return env->NewStringUTF("\x01");
                const auto lcid = ids[index * 3], rcid = ids[index * 3 + 1], mid = ids[index * 3 + 2];
                if (lcid < 0 || lcid > 1319 || rcid < 0 || rcid > 1319 || mid < 0 || mid > 502 || !std::isfinite(scores[index])) continue;
                auto ruby = static_cast<jstring>(env->GetObjectArrayElement(learned_readings, index));
                auto word = static_cast<jstring>(env->GetObjectArrayElement(learned_words, index));
                if (ruby != nullptr && word != nullptr) {
                    userEntries.push_back({assist_from_java(env, word), assist_from_java(env, ruby), scores[index], lcid, rcid, mid});
                }
                if (ruby != nullptr) env->DeleteLocalRef(ruby);
                if (word != nullptr) env->DeleteLocalRef(word);
            }
        }
    }

    // Serialize dictionary access: AzooKeyDictionary caches LOUDS trees / txt3
    // shards in unordered_maps that are mutated on cache misses, so concurrent
    // lookups would otherwise be a data race.
    std::string out;
    {
        std::unique_lock<std::mutex> lock(g_dict_mutex, std::try_to_lock);
        if (!lock.owns_lock() || epoch != assist_epoch()) return env->NewStringUTF("\x01");
        g_dict_abort_flag.store(false);
        const auto cancelled = [&]() { return epoch != assist_epoch() || std::chrono::steady_clock::now() >= deadline; };
        auto results = g_dictionary.lookup(rd, prefix_search == JNI_TRUE, std::clamp((int)max_count, 1, 64), &g_dict_abort_flag, cancelled, keyboardSurfaceEnds, userEntries);

        if (g_dict_abort_flag.load() || cancelled()) {
            // The lookup was interrupted and the result set is incomplete.
            // Signal this explicitly so the caller can discard the partial
            // result instead of memoizing it.
            // NOTE: NewStringUTF is used directly because safe_new_string()
            // strips control characters, which would erase the marker.
            return env->NewStringUTF("\x01");
        }

        const auto hex = [](const std::string& value) {
            static const char* digits = "0123456789abcdef";
            std::string result;
            for (unsigned char byte : value) { result += digits[byte >> 4]; result += digits[byte & 15]; }
            return result;
        };
        // Keep morphology and span readings; fields containing lexical text are hex encoded.
        for (const auto& item : results) {
            out += item.word;
            out += '\t';
            out += item.ruby;
            out += '\t';
            out += std::to_string(item.cost);
            out += '\t';
            const auto segments = item.segments.empty() ? std::vector<azookey::LexicalSegment>{
                {item.word, item.ruby, item.cost, item.lcid, item.rcid, item.mid}} : item.segments;
            for (const auto& segment : segments) {
                if (out.back() != '\t') out += '|';
                out += hex(segment.ruby) + ":" + hex(segment.word) + ":" + std::to_string(segment.lcid) + ":" +
                    std::to_string(segment.rcid) + ":" + std::to_string(segment.mid) + ":" + std::to_string(segment.cost) + ":" +
                    std::to_string(segment.correctionKind) + ":" + std::to_string(segment.originalStart) + ":" +
                    std::to_string(segment.originalEnd) + ":" + hex(segment.lexicalRuby.empty() ? segment.ruby : segment.lexicalRuby);
            }
            out += '\n';
        }
    }

    return assist_to_java(env, out);
}

} // extern "C"

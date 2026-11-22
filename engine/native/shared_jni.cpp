#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include "assist_jni.h"
#include "inference_control.h"
#include "keyboard_assist.h"
#include "ggml-backend.h"

extern "C" {
char *zerocon_swift_request(const char *);
void zerocon_swift_free(char *);
void *ZeroConOpenConversionBenchmark(const char *, const char *, int32_t);
char *ZeroConRunConversionBenchmark(void *, const char *, const char *);
void ZeroConCloseConversionBenchmark(void *);
using Complete = char *(*)(void *, bool, const char *, unsigned long long);
using Release = void (*)(void *, char *);
char *zerocon_assist_request(const char *, Complete, Release, void *);
char *zerocon_assist_policy(const char *);
void zerocon_engine_free(char *);
}

struct CompletionHost { JNIEnv *env; jobject host; jmethodID complete; };

extern "C" JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeAssistPolicy(JNIEnv *env, jobject, jstring input) {
    const auto text = assist_from_java(env, input);
    auto *result = zerocon_assist_policy(text.c_str());
    auto output = assist_to_java(env, result ? result : "{\"error\":\"empty policy response\"}");
    zerocon_engine_free(result);
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeConversionEpoch(JNIEnv *, jobject) {
    return static_cast<jlong>(zerocon_conversion_epoch());
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeAbortConversion(JNIEnv *, jobject) {
    zerocon_conversion_abort();
}

static char *complete(void *context, bool prediction, const char *payload, unsigned long long timeout) {
    // Keyboard repair is scored natively on the loaded model; it never reaches Kotlin.
    if (payload && std::strstr(payload, "\"keyboard_repair\":\"")) {
        return strdup(keyboard_assist_request(payload, timeout).c_str());
    }
    auto *host = static_cast<CompletionHost *>(context);
    auto input = assist_to_java(host->env, payload);
    auto output = static_cast<jstring>(host->env->CallObjectMethod(host->host, host->complete, prediction, input, static_cast<jlong>(timeout)));
    host->env->DeleteLocalRef(input);
    if (host->env->ExceptionCheck() || !output) return nullptr;
    auto text = assist_from_java(host->env, output);
    host->env->DeleteLocalRef(output);
    return strdup(text.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeSwift(JNIEnv *env, jobject, jstring input) {
    auto text = assist_from_java(env, input);
    auto *result = zerocon_swift_request(text.c_str());
    auto output = assist_to_java(env, result ? result : "{\"error\":\"empty Swift response\"}");
    zerocon_swift_free(result);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeSetDataRoot(JNIEnv *env, jobject, jstring input) {
    auto text = assist_from_java(env, input);
    setenv("APPDATA", text.c_str(), 1);
}

// The Japanese dictionary is downloaded after install, so the engine cannot rely
// on the executable-relative copy. Point Swift at the extracted dictionary.
extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeSetDictionaryRoot(JNIEnv *env, jobject, jstring input) {
    auto text = assist_from_java(env, input);
    setenv("ZEROCON_DICTIONARY_DIR", text.c_str(), 1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeLoadBackends(JNIEnv *env, jobject, jstring input) {
    const auto directory = assist_from_java(env, input);
    // The compressed native libraries are extracted by Android's installer.
    // Use that explicit directory; an IME's executable path is app_process.
    ggml_backend_load_all_from_path(directory.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativePreloadAssistDictionary(JNIEnv *env, jobject, jstring path) {
    const auto dictionary_path = assist_from_java(env, path);
    keyboard_assist_preload_dictionary(dictionary_path.c_str());
}

static jstring assist_request(JNIEnv *env, jstring input, jobject inference) {
    auto cls = env->GetObjectClass(inference);
    auto method = env->GetMethodID(cls, "complete", "(ZLjava/lang/String;J)Ljava/lang/String;");
    env->DeleteLocalRef(cls);
    if (!method) return nullptr;
    CompletionHost host{env, inference, method};
    auto text = assist_from_java(env, input);
    auto *result = zerocon_assist_request(text.c_str(), complete, [](void *, char *value) { free(value); }, &host);
    if (env->ExceptionCheck()) { zerocon_engine_free(result); return nullptr; }
    auto output = assist_to_java(env, result ? result : "null");
    zerocon_engine_free(result);
    return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_engine_WindowsEngine_nativeAssist(JNIEnv *env, jobject, jstring input, jobject inference) {
    return assist_request(env, input, inference);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_benchmark_BenchmarkSession_nativeRun(JNIEnv *env, jobject session, jstring input) {
    return assist_request(env, input, session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeroconvert_ime_benchmark_BenchmarkSession_nativeOpenCandidates(
        JNIEnv *env, jobject, jstring model, jstring dictionary, jstring library_dir) {
    const auto directory = assist_from_java(env, library_dir);
    ggml_backend_load_all_from_path(directory.c_str());
    const auto model_path = assist_from_java(env, model);
    const auto dictionary_path = assist_from_java(env, dictionary);
    return reinterpret_cast<jlong>(ZeroConOpenConversionBenchmark(model_path.c_str(), dictionary_path.c_str(), 0));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zeroconvert_ime_benchmark_BenchmarkSession_nativeCandidates(
        JNIEnv *env, jobject, jlong handle, jstring reading, jstring context) {
    const auto input = assist_from_java(env, reading);
    const auto left_context = assist_from_java(env, context);
    auto *result = ZeroConRunConversionBenchmark(reinterpret_cast<void *>(handle), input.c_str(), left_context.c_str());
    auto output = assist_to_java(env, result ? result : "null");
    zerocon_swift_free(result);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroconvert_ime_benchmark_BenchmarkSession_nativeCloseCandidates(JNIEnv *, jobject, jlong handle) {
    ZeroConCloseConversionBenchmark(reinterpret_cast<void *>(handle));
}

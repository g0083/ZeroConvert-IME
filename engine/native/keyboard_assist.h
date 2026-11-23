#pragma once
#include <string>

struct llama_model;

// Phase 3-A: the Windows `keyboard-prior --assist` algorithm, run in-process on
// the already loaded conversion model. No second model, no child process.
// The model is borrowed: detach before the owner frees it.
void keyboard_assist_attach(llama_model *model);
void keyboard_assist_detach();
void keyboard_assist_preload_dictionary(const char *path);

// `payload` is the flat JSON built by the Rust bridge (no escapes inside values).
// Returns the worker's reply JSON object, or "null" when no answer is available
// (dictionary still loading, cancelled, timed out, or error).
std::string keyboard_assist_request(const char *payload, unsigned long long timeout_ms);

#ifdef __ANDROID__
struct BenchmarkKeyboardAssist;
BenchmarkKeyboardAssist *keyboard_assist_open_benchmark(llama_model *model, const char *path);
void keyboard_assist_close_benchmark(BenchmarkKeyboardAssist *session);
std::string keyboard_assist_request_benchmark(BenchmarkKeyboardAssist *session,
                                             const char *payload, unsigned long long timeout_ms);
#endif

#pragma once
#include <string>

struct llama_model;

// Phase 3-A: the Windows `keyboard-prior --assist` algorithm, run in-process on
// the already loaded conversion model. No second model, no child process.
// The model is borrowed: detach before the owner frees it.
void keyboard_assist_attach(llama_model *model);
void keyboard_assist_detach();

// `payload` is the flat JSON built by the Rust bridge (no escapes inside values).
// Returns the worker's reply JSON object, or "null" when no answer is available
// (dictionary still loading, cancelled, timed out, or error).
std::string keyboard_assist_request(const char *payload, unsigned long long timeout_ms);

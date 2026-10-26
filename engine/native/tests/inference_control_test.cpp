// Exercise the real Android host adapter against a controllable decode backend.
// No model, Android runtime, or Windows conversion implementation is substituted.
#include "inference_control.h"
#include "llama.h"
#include <cassert>
#include <chrono>
#include <iostream>
#include <thread>

struct llama_context { ggml_abort_callback callback; void *data; };
static unsigned real_calls = 0;
static unsigned work_ms = 0;
extern "C" void llama_set_abort_callback(llama_context *ctx, ggml_abort_callback callback, void *data) {
    ctx->callback = callback;
    ctx->data = data;
}
extern "C" int32_t __real_llama_decode(llama_context *ctx, llama_batch) {
    ++real_calls;
    for (unsigned n = 0; n < work_ms; ++n) {
        if (ctx->callback(ctx->data)) return 2;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return 0;
}
extern "C" int32_t __wrap_llama_decode(llama_context *, llama_batch);
int main() {
    llama_context ctx{};
    llama_batch batch{};
    // Cancellation while queued must prevent entry into the backend.
    const auto queued_epoch = zerocon_conversion_epoch();
    zerocon_conversion_abort();
    zerocon_conversion_begin(queued_epoch);
    assert(__wrap_llama_decode(&ctx, batch) == 2 && real_calls == 0);
    assert(zerocon_conversion_aborted_count() == 1);
    zerocon_conversion_end();
    // Cancellation must be observable inside an already-running decode.
    zerocon_conversion_begin(zerocon_conversion_epoch());
    work_ms = 200;
    std::thread canceller([] {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        zerocon_conversion_abort();
    });
    assert(__wrap_llama_decode(&ctx, batch) == 2);
    canceller.join();
    assert(zerocon_conversion_decode_count() == 1 && zerocon_conversion_aborted_count() == 1);
    assert(zerocon_conversion_decode_nanoseconds() > 0);
    zerocon_conversion_end();
    // The fresh request succeeds; an earlier cancellation does not poison it.
    zerocon_conversion_begin(zerocon_conversion_epoch());
    work_ms = 0;
    assert(__wrap_llama_decode(&ctx, batch) == 0);
    assert(zerocon_conversion_decode_count() == 1 && zerocon_conversion_aborted_count() == 0);
    zerocon_conversion_end();
    assert(!zerocon_conversion_cancelled());
    std::cout << "PASS queued cancellation, in-flight decode cancellation, fresh request recovery\n";
}

#include "inference_control.h"
#include "llama.h"
#include <atomic>
#include <chrono>

namespace {
std::atomic<uint64_t> generation{0};
// Only the serial Swift actor accesses request state. The aborting UI thread
// changes generation only; it never touches a context or model.
uint64_t request_generation = 0;
bool active = false;
uint64_t decode_nanoseconds = 0;
uint64_t decode_count = 0;
uint64_t aborted_count = 0;
bool should_abort(void *) { return zerocon_conversion_cancelled(); }
}

extern "C" uint64_t zerocon_conversion_epoch() { return generation.load(std::memory_order_acquire); }
extern "C" void zerocon_conversion_abort() { generation.fetch_add(1, std::memory_order_acq_rel); }
extern "C" void zerocon_conversion_begin(uint64_t epoch) {
    request_generation = epoch;
    decode_nanoseconds = decode_count = aborted_count = 0;
    active = true;
}
extern "C" void zerocon_conversion_end() { active = false; }
extern "C" bool zerocon_conversion_cancelled() { return active && request_generation != zerocon_conversion_epoch(); }
extern "C" uint64_t zerocon_conversion_decode_nanoseconds() { return decode_nanoseconds; }
extern "C" uint64_t zerocon_conversion_decode_count() { return decode_count; }
extern "C" uint64_t zerocon_conversion_aborted_count() { return aborted_count; }

// --wrap is applied to libzerocon_swift only. The pinned converter calls the
// original llama API unchanged. Android supplies its cancellation callback here;
// the independent correction/prediction contexts in JNI are not intercepted.
extern "C" int32_t __real_llama_decode(llama_context *, llama_batch);
extern "C" int32_t __wrap_llama_decode(llama_context *context, llama_batch batch) {
    if (zerocon_conversion_cancelled()) { ++aborted_count; return 2; }
    llama_set_abort_callback(context, should_abort, nullptr);
    const auto started = std::chrono::steady_clock::now();
    const auto result = __real_llama_decode(context, batch);
    decode_nanoseconds += std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count();
    ++decode_count;
    if (result == 2) ++aborted_count;
    return result;
}

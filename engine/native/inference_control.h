#pragma once
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
// An epoch is captured before queueing, not when the worker finally starts.
uint64_t zerocon_conversion_epoch(void);
void zerocon_conversion_abort(void);
void zerocon_conversion_begin(uint64_t epoch);
void zerocon_conversion_end(void);
bool zerocon_conversion_cancelled(void);
uint64_t zerocon_conversion_decode_nanoseconds(void);
uint64_t zerocon_conversion_decode_count(void);
uint64_t zerocon_conversion_aborted_count(void);
#ifdef __cplusplus
}
#endif

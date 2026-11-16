#include <stdlib.h>

// The single-threaded Swift WASI SDK has no C++ exception runtime. A C++
// failure stops this worker before the native code resumes with a partial result.
void *__cxa_allocate_exception(size_t size) {
    (void) size;
    abort();
}

void __cxa_throw(void *value, void *type, void *destructor) {
    (void) value;
    (void) type;
    (void) destructor;
    abort();
}

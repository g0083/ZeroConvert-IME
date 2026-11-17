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

#ifdef ZEROCON_STATIC_BACKENDS
// The llama registry uses the CPU backend linked into this module. WASI does
// not support loading plugins; return the standard failure results.
void *dlopen(const char *name, int flags) { (void) name; (void) flags; return NULL; }
void *dlsym(void *handle, const char *name) { (void) handle; (void) name; return NULL; }
int dlclose(void *handle) { (void) handle; return -1; }
char *dlerror(void) { static char message[] = "dynamic loading is unavailable"; return message; }
#endif

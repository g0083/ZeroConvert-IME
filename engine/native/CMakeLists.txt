cmake_minimum_required(VERSION 3.22.1)
project(zerocon_embedded LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
# Keep the baseline CPU backend and let upstream's runtime feature checks select
# DOTPROD/i8mm on capable phones. Never raise the minimum ARM instruction set.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
  set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(ANDROID_SOURCES "${REPO_ROOT}/android/app/src/main/cpp")
add_subdirectory("${ANDROID_SOURCES}/llama.cpp" llama.cpp)
# Record exactly the current targets, including dlopen backends, instead of
# picking up obsolete libraries left in an incremental build directory.
set(RUNTIME_LIBRARIES "$<TARGET_FILE:llama>\n$<TARGET_FILE:ggml>\n$<TARGET_FILE:ggml-base>\n")
foreach(backend IN LISTS GGML_AVAILABLE_BACKENDS)
  if(TARGET ${backend})
    string(APPEND RUNTIME_LIBRARIES "$<TARGET_FILE:${backend}>\n")
  endif()
endforeach()
file(GENERATE OUTPUT "${CMAKE_BINARY_DIR}/runtime-libraries.txt" CONTENT "${RUNTIME_LIBRARIES}")
add_library(zerocon_rust STATIC IMPORTED)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(RUST_TARGET aarch64-linux-android)
else()
  set(RUST_TARGET x86_64-linux-android)
endif()
set_target_properties(zerocon_rust PROPERTIES IMPORTED_LOCATION "${REPO_ROOT}/engine/rust/target/${RUST_TARGET}/release/libzerocon_engine.a")
add_library(zerocon_swift SHARED IMPORTED)
set_target_properties(zerocon_swift PROPERTIES IMPORTED_LOCATION "${REPO_ROOT}/engine/build/jniLibs/${ANDROID_ABI}/libzerocon_swift.so" IMPORTED_NO_SONAME TRUE)
add_library(zenz_jni SHARED
  "${ANDROID_SOURCES}/zenz_jni.cpp"
  "${ANDROID_SOURCES}/assist_jni.cpp"
  "${ANDROID_SOURCES}/azookey_louds.cpp"
  shared_jni.cpp
  keyboard_assist.cpp)
# Phase 3-A compiles the Windows keyboard-prior candidate/dictionary header as-is.
target_include_directories(zenz_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${REPO_ROOT}/windows/native/keyboard-prior" "${ANDROID_SOURCES}" "${ANDROID_SOURCES}/llama.cpp/include" "${ANDROID_SOURCES}/llama.cpp/ggml/include")
target_link_libraries(zenz_jni PRIVATE llama zerocon_rust zerocon_swift log android dl)

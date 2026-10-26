cmake_minimum_required(VERSION 3.22.1)
project(zenz_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp build options
set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP for mobile" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(zenz_jni SHARED
    zenz_jni.cpp
    assist_jni.cpp
    azookey_louds.cpp
)

target_include_directories(zenz_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(zenz_jni PRIVATE
    llama
    log
    android
)

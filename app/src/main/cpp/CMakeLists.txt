cmake_minimum_required(VERSION 3.22.1)
project(alalay_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CPU-only build: no host-flag codegen, no dlopen'd backend variants.
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# llama.cpp: library only, no tools/tests/curl.
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL   OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL   OFF CACHE BOOL "" FORCE)

# whisper.cpp: library only.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)

# llama.cpp first: it owns the shared ggml build. whisper.cpp's top-level
# CMake skips its own ggml when a `ggml` target already exists — the two
# vendored ggml trees are identical (0.26.0), so this is safe.
add_subdirectory(vendor/llama.cpp)
add_subdirectory(vendor/whisper.cpp)

find_library(LOG_LIB log)

add_library(alalay_llama SHARED jni_llama.cpp)
target_include_directories(alalay_llama PRIVATE vendor/llama.cpp/include)
target_link_libraries(alalay_llama PRIVATE llama ${LOG_LIB})

add_library(alalay_whisper SHARED jni_whisper.cpp)
target_include_directories(alalay_whisper PRIVATE vendor/whisper.cpp/include)
target_link_libraries(alalay_whisper PRIVATE whisper ${LOG_LIB})

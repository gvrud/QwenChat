cmake_minimum_required(VERSION 3.22.1)

project(qwenchat C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Build only the llama/ggml libraries. BUILD_SHARED_LIBS=ON gives one libllama.so that
# libqwen.so links against; all .so files are packaged into the APK automatically.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

find_library(log-lib log)

add_library(qwen SHARED qwen_jni.cpp)
target_link_libraries(qwen llama ${log-lib})

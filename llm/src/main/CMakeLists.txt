# 墨唐 :llm 原生构建
#
# 目标：把 llama.cpp（含 ggml）静态链接进单个 libmotang_llm.so，
# 由 JNI 暴露「加载模型 / 流式生成 / 语音转写」三类能力。
#
# 注意：llama.cpp 以 git submodule 固定在 b12726（见 .gitmodules）。

cmake_minimum_required(VERSION 3.22.1)
project(motang_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 本文件位于 llm/src/main/CMakeLists.txt，子模块在 llm/llama.cpp
set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "找不到 llama.cpp：${LLAMA_DIR}。请执行 git submodule update --init --recursive")
endif()

# KleidiAI 只支持 AArch64；x86_64（模拟器）必须关闭，否则 CMake 直接 FATAL_ERROR。
if (DEFINED CMAKE_ANDROID_ARCH_ABI AND NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

# llama.cpp 的库目标（llama / ggml / ggml-base / ggml-cpu）
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(motang_llm SHARED
    cpp/llm_bridge.cpp
)

target_include_directories(motang_llm PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(motang_llm
    llama
    android
    ${ANDROID_LOG_LIB}
)

# 体积与对齐
target_compile_options(motang_llm PRIVATE -O3 -fvisibility=hidden)
target_link_options(motang_llm PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
)

cmake_minimum_required(VERSION 3.22.1)
project(projectexe_llm CXX C)

# ── llama.cpp submodule path ──────────────────────────────────
# Clone with: git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule missing.\n"
        "Run: git submodule add https://github.com/ggerganov/llama.cpp "
        "app/src/main/cpp/llama.cpp\n"
        "Then: git submodule update --init --recursive"
    )
endif()

# ── ARM optimisation flags for Cortex-A78 (Revvl 7 / Snapdragon 6 Gen 1) ─
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -march=armv8.2-a+dotprod+i8mm+fp16")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod+i8mm+fp16")
endif()

# ── Build llama.cpp as a static library ──────────────────────
set(GGML_NEON      ON  CACHE BOOL "" FORCE)
set(GGML_ARM_DOTPROD ON CACHE BOOL "" FORCE)
set(GGML_ARM_I8MM  ON  CACHE BOOL "" FORCE)
set(GGML_FP16_VA   ON  CACHE BOOL "" FORCE)
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

# ── JNI bridge shared library ─────────────────────────────────
add_library(projectexe_jni SHARED llama_jni.cpp)

target_include_directories(projectexe_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/common
)

target_link_libraries(projectexe_jni
    llama
    common
    android
    log
)

target_compile_options(projectexe_jni PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

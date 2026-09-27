cmake_minimum_required(VERSION 3.22.1)
project(aixodia_jev)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/Clauszy/llama.cpp.git
    GIT_TAG 5d8c71ce6e3b9cf481e980c8ad96b00bfdad27c9
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(aixodia_jev SHARED
    jev_jni.cpp
    ${llama_SOURCE_DIR}/tools/laya/laya.cpp
)

target_include_directories(aixodia_jev PRIVATE
    ${llama_SOURCE_DIR}/tools/laya
    ${llama_SOURCE_DIR}/ggml/include
    ${llama_SOURCE_DIR}/ggml/src
    ${llama_SOURCE_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(aixodia_jev PRIVATE ggml ggml-base ggml-cpu log android)

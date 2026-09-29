cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(llama.cpp)

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    llama
    common
    ${log-lib}
    ${android-lib}
)

target_include_directories(native-lib PRIVATE
    llama.cpp
    llama.cpp/common
    llama.cpp/include
    llama.cpp/ggml/include
)

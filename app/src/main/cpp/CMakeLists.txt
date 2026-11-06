cmake_minimum_required(VERSION 3.22.1)
project(projectx LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Map met de door Faust gegenereerde header (wordt door Gradle meegegeven).
if(NOT FAUST_GEN_DIR)
    message(FATAL_ERROR "FAUST_GEN_DIR is niet gezet")
endif()

find_package(oboe REQUIRED CONFIG)

add_library(projectx SHARED
    native-lib.cpp
    SynthEngine.cpp
)

target_include_directories(projectx PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${FAUST_GEN_DIR})
target_compile_options(projectx PRIVATE -O3 -Wall)
target_link_libraries(projectx oboe::oboe log)

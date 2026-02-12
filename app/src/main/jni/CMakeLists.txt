cmake_minimum_required(VERSION 3.22.1)
project("actl_mvp")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti)

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(adbpair SHARED adb_pairing.cpp)

target_link_libraries(
    adbpair
    log
    boringssl::crypto_static
    cxx::cxx
)

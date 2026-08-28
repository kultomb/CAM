cmake_minimum_required(VERSION 3.22.1)

project("hbg_uvc")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# BẮT BUỘC: Căn chỉnh 16KB Page Size Alignment cho Android 14/15 (Pixel 8/9, Galaxy S24, Xiaomi 14)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(
        hbg_uvc
        SHARED
        uvc_native.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        hbg_uvc
        ${log-lib}
        ${android-lib}
)

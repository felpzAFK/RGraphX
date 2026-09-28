cmake_minimum_required(VERSION 3.22.1)
project("rgraphx_native")

add_library(
        rgraphx_native
        SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp)

find_library(log-lib log)
find_library(graphics-lib jnigraphics)

target_link_libraries(rgraphx_native ${log-lib} ${graphics-lib})
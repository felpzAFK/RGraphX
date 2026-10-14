cmake_minimum_required(VERSION 3.22.1)
project("rgraphx_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Raiz do repositório (app/src/main/cpp -> ../../../..)
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(OPENCV_NATIVE "${REPO_ROOT}/opencv/native")

# OpenCV 5.0.0 (SDK Android). Linkamos a libopencv_java5.so direto pelo caminho,
# em vez de find_package(OpenCV): o OpenCVConfig.cmake do SDK procura em
# <raiz>/sdk/native/..., mas nesta estrutura a pasta se chama "opencv/".
# A .so é empacotada no APK pelo módulo :opencv (jniLibs = native/libs).
add_library(lib_opencv_java SHARED IMPORTED)
set_target_properties(lib_opencv_java PROPERTIES
        IMPORTED_LOCATION "${OPENCV_NATIVE}/libs/${ANDROID_ABI}/libopencv_java5.so")

add_library(
        rgraphx_native
        SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp)

target_include_directories(rgraphx_native PRIVATE "${OPENCV_NATIVE}/jni/include")

find_library(log-lib log)
find_library(graphics-lib jnigraphics)

target_link_libraries(rgraphx_native lib_opencv_java ${log-lib} ${graphics-lib})

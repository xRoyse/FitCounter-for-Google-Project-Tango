cmake_minimum_required(VERSION 3.22.1)
project(fitcounter_pose LANGUAGES C CXX)

set(TFLITE_ENABLE_GPU OFF CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_NNAPI OFF CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_XNNPACK OFF CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_EXTERNAL_DELEGATE OFF CACHE BOOL "" FORCE)
set(TFLITE_C_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${TENSORFLOW_ROOT}/tensorflow/lite/c" "${CMAKE_BINARY_DIR}/tflite")
add_library(fitcounter_pose SHARED movenet_jni.cpp)
target_include_directories(fitcounter_pose PRIVATE "${TENSORFLOW_ROOT}")
target_link_libraries(fitcounter_pose PRIVATE tensorflowlite_c log)
target_link_options(fitcounter_pose PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined")

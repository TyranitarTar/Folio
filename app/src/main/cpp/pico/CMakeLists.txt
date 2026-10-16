cmake_minimum_required(VERSION 3.22)
project(folio_pico LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}/libonnxruntime.so")
add_library(folio_pico SHARED pico_jni.cpp)
target_include_directories(folio_pico PRIVATE include)
target_compile_options(folio_pico PRIVATE -Wall -Wextra -Werror=return-type -fvisibility=hidden "$<$<CONFIG:Debug>:-O2>")
# AGP may invoke clang++ by an 8.3 Windows name, losing the driver's "++"
# suffix. Explicit mode keeps automatic libc++ linking correct on that path.
target_link_options(folio_pico PRIVATE --driver-mode=g++ "-Wl,-z,max-page-size=16384")
target_link_libraries(folio_pico PRIVATE onnxruntime log)

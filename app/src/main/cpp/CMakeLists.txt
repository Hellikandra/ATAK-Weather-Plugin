cmake_minimum_required(VERSION 3.22.1)
project("windparticle" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable NEON on ARM for SIMD
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

# Optimisation — critical for particle perf
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -DNDEBUG")

add_library(windparticle SHARED
    wind_particle_engine.cpp
    wind_particle_jni.cpp
)

find_library(log-lib log)

target_link_libraries(windparticle
    ${log-lib}
)

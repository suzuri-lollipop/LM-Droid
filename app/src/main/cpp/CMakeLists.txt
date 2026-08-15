cmake_minimum_required(VERSION 3.22.1)

project("lmdroid_native")

# Support 16 KB page alignment for all native libraries (Android 15+)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Set options for stable-diffusion.cpp BEFORE adding the subdirectory
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "sd: build examples" FORCE)
set(SD_BUILD_TESTS OFF CACHE BOOL "sd: build tests" FORCE)
set(SD_VULKAN OFF CACHE BOOL "sd: vulkan backend" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "sd: build shared libs" FORCE)

# NDK debug builds compile native code at -O0, which makes ggml/Whisper inference
# 5-10x slower; keep the numeric native code optimized in debug builds too.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")

# ggml disables every x86 SIMD kernel when cross-compiling, which leaves Whisper
# inference ~20x slower than realtime on the x86_64 emulator. The x86_64 ABI only
# ever runs there, and AVX2/FMA/F16C are baseline on any host that can run it.
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(GGML_AVX ON CACHE BOOL "ggml: enable AVX" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "ggml: enable AVX2" FORCE)
    set(GGML_FMA ON CACHE BOOL "ggml: enable FMA" FORCE)
    set(GGML_F16C ON CACHE BOOL "ggml: enable F16C" FORCE)
endif()

# stable-diffusion.cpp directory
set(SD_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp)

# Add stable-diffusion.cpp as a subdirectory
add_subdirectory(${SD_CPP_DIR})

# Bullet Physics (vendored) — only the core simulation libraries, no demos/tests.
# Used by the NDK-native MMD renderer's rigid body / joint simulation (see mmd/).
set(BUILD_CPU_DEMOS OFF CACHE BOOL "bullet: build demos" FORCE)
set(BUILD_BULLET2_DEMOS OFF CACHE BOOL "bullet: build bullet2 examples" FORCE)
set(BUILD_OPENGL3_DEMOS OFF CACHE BOOL "bullet: build opengl3 demo" FORCE)
set(BUILD_BULLET2_ROBOTICS_EXTRA OFF CACHE BOOL "bullet: build robotics extras" FORCE)
set(BUILD_BULLET3 OFF CACHE BOOL "bullet: build bullet3" FORCE)
set(BUILD_EXTRAS OFF CACHE BOOL "bullet: build extras" FORCE)
set(BUILD_UNIT_TESTS OFF CACHE BOOL "bullet: build unit tests" FORCE)
set(BUILD_CLSOCKET OFF CACHE BOOL "bullet: build clsocket" FORCE)
set(BUILD_ENET OFF CACHE BOOL "bullet: build enet" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "bullet: static libs" FORCE)
set(USE_DOUBLE_PRECISION OFF CACHE BOOL "bullet: single precision" FORCE)
set(INSTALL_LIBS OFF CACHE BOOL "bullet: no install rules" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/bullet3)

# Define the JNI library
add_library(sd_jni SHARED
        sd_jni.cpp
)

# Define the Whisper JNI library
add_library(whisper_jni SHARED
        whisper.cpp
        whisper_jni.cpp
)

# Define the MMD renderer JNI library (PMX/VMD + toon renderer + Bullet physics)
add_library(mmd_jni SHARED
        mmd_jni.cpp
        mmd/pmx_parser.cpp
        mmd/vmd_parser.cpp
        mmd/mmd_engine.cpp
        mmd/mmd_physics.cpp
        mmd/mmd_renderer.cpp
)

# Explicitly force 16 KB page alignment for these targets
target_link_options(sd_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(mmd_jni PRIVATE "-Wl,-z,max-page-size=16384")

# Include directories
target_include_directories(sd_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SD_CPP_DIR}/include
)

target_include_directories(whisper_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SD_CPP_DIR}/ggml/include
)

target_include_directories(mmd_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/bullet3/src
)

# Link against Android libraries and stable-diffusion
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(glesv3-lib GLESv3)

target_link_libraries(sd_jni
        stable-diffusion
        ${log-lib}
        ${jnigraphics-lib}
)

target_link_libraries(whisper_jni
        ggml
        ${log-lib}
)

target_link_libraries(mmd_jni
        BulletDynamics
        BulletCollision
        LinearMath
        ${glesv3-lib}
        ${log-lib}
)

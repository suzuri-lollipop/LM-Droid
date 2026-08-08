cmake_minimum_required(VERSION 3.22.1)

project("lmdroid_native")

# Set options for stable-diffusion.cpp BEFORE adding the subdirectory
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "sd: build examples" FORCE)
set(SD_BUILD_TESTS OFF CACHE BOOL "sd: build tests" FORCE)
set(SD_VULKAN OFF CACHE BOOL "sd: vulkan backend" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "sd: build shared libs" FORCE)

# stable-diffusion.cpp directory
set(SD_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp)

# Add stable-diffusion.cpp as a subdirectory
add_subdirectory(${SD_CPP_DIR})

# Define the JNI library
add_library(sd_jni SHARED
        sd_jni.cpp
)

# Include directories
target_include_directories(sd_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SD_CPP_DIR}/include
)

# Link against Android libraries and stable-diffusion
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(sd_jni
        stable-diffusion
        ${log-lib}
        ${jnigraphics-lib}
)

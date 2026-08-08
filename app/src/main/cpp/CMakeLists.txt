cmake_minimum_required(VERSION 3.22.1)

project("lmdroid_native")

# stable-diffusion.cpp should be placed in this directory
set(SD_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp)

# Define the JNI library
add_library(sd_jni SHARED
        sd_jni.cpp
        # Include stable-diffusion.cpp sources here or as a subdirectory
        # If it's a separate project with its own CMakeLists.txt:
        # add_subdirectory(${SD_CPP_DIR} sd_cpp_build)
)

# Include directories
target_include_directories(sd_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SD_CPP_DIR}
)

# Link against Android libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(sd_jni
        ${log-lib}
        ${jnigraphics-lib}
        # sd_cpp_target # The target name from stable-diffusion.cpp
)

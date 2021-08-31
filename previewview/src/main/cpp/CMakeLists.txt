# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.10.2)

# Declares and names the project.

project("previewview")

add_library( # Sets the name of the library.
        renderer_jni

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        jni_hooks.cpp
        renderer_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(opengl-lib GLESv2)
find_library(egl-lib EGL)

target_link_libraries( # Specifies the target library.
        renderer_jni

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
        ${android-lib}
        ${opengl-lib}
        ${egl-lib} )
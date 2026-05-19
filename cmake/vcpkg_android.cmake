#
# vcpkg_android.cmake 
#
# Helper script when using vcpkg with cmake. It should be triggered via the variable VCPKG_TARGET_ANDROID
#
# For example:
# if (VCPKG_TARGET_ANDROID)
#     include("cmake/vcpkg_android.cmake")
# endif()
# 
# This script will:
# 1 & 2. check the presence of needed env variables: ANDROID_NDK_HOME and VCPKG_ROOT
# 3. set VCPKG_TARGET_TRIPLET according to ANDROID_ABI
# 4. Combine vcpkg and Android toolchains by setting CMAKE_TOOLCHAIN_FILE 
#    and VCPKG_CHAINLOAD_TOOLCHAIN_FILE

# Note: VCPKG_TARGET_ANDROID is not an official Vcpkg variable. 
# it is introduced for the need of this script

if (VCPKG_TARGET_ANDROID)

    #
    # 1. Resolve the Android NDK path.
    #
    # Gradle's externalNativeBuild passes ANDROID_NDK / CMAKE_ANDROID_NDK directly,
    # so do not require the legacy ANDROID_NDK_HOME environment variable when those
    # are already available.
    #
    set(VITA3K_ANDROID_NDK "")
    if (DEFINED CMAKE_ANDROID_NDK AND EXISTS "${CMAKE_ANDROID_NDK}")
        set(VITA3K_ANDROID_NDK "${CMAKE_ANDROID_NDK}")
    elseif(DEFINED ANDROID_NDK AND EXISTS "${ANDROID_NDK}")
        set(VITA3K_ANDROID_NDK "${ANDROID_NDK}")
    elseif(DEFINED ENV{ANDROID_NDK} AND EXISTS "$ENV{ANDROID_NDK}")
        set(VITA3K_ANDROID_NDK "$ENV{ANDROID_NDK}")
    elseif(DEFINED ENV{ANDROID_NDK_HOME} AND EXISTS "$ENV{ANDROID_NDK_HOME}")
        set(VITA3K_ANDROID_NDK "$ENV{ANDROID_NDK_HOME}")
    endif()

    if (VITA3K_ANDROID_NDK STREQUAL "")
        message(FATAL_ERROR "
        Unable to determine the Android NDK path.
        Expected one of CMAKE_ANDROID_NDK, ANDROID_NDK, ANDROID_NDK_HOME,
        or a Gradle-provided Android NDK path.
        For example:
        export ANDROID_NDK_HOME=/home/your-account/Android/Sdk/ndk-bundle
        Or:
        export ANDROID_NDK_HOME=/home/your-account/Android/android-ndk-r21b
        ")
    endif()
    message("vcpkg_android.cmake: Android NDK was resolved to ${VITA3K_ANDROID_NDK}")

    #
    # 2. Resolve the vcpkg root.
    #
    set(VITA3K_VCPKG_ROOT "")
    if (DEFINED ENV{VCPKG_ROOT} AND EXISTS "$ENV{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")
        set(VITA3K_VCPKG_ROOT "$ENV{VCPKG_ROOT}")
    elseif(EXISTS "${CMAKE_SOURCE_DIR}/vcpkg/scripts/buildsystems/vcpkg.cmake")
        set(VITA3K_VCPKG_ROOT "${CMAKE_SOURCE_DIR}/vcpkg")
    endif()

    if (VITA3K_VCPKG_ROOT STREQUAL "")
        message(FATAL_ERROR "
        Unable to determine the vcpkg root.
        Expected VCPKG_ROOT to point to a vcpkg checkout, or a checkout at:
        ${CMAKE_SOURCE_DIR}/vcpkg
        For example:
        export VCPKG_ROOT=/path/to/vcpkg
        ")
    endif()
    message("vcpkg_android.cmake: VCPKG_ROOT was resolved to ${VITA3K_VCPKG_ROOT}")


    #
    # 3. Set VCPKG_TARGET_TRIPLET according to ANDROID_ABI
    # 
    # There are four different Android ABI, each of which maps to 
    # a vcpkg triplet. The following table outlines the mapping from vcpkg architectures to android architectures
    #
    # |VCPKG_TARGET_TRIPLET       | ANDROID_ABI          |
    # |---------------------------|----------------------|
    # |arm64-android              | arm64-v8a            |
    # |arm-android                | armeabi-v7a          |
    # |x64-android                | x86_64               |
    # |x86-android                | x86                  |
    #
    # The variable must be stored in the cache in order to successfully the two toolchains. 
    #
    if (ANDROID_ABI MATCHES "arm64-v8a")
        set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "" FORCE)
    elseif(ANDROID_ABI MATCHES "armeabi-v7a")
        set(VCPKG_TARGET_TRIPLET "arm-android" CACHE STRING "" FORCE)
    elseif(ANDROID_ABI MATCHES "x86_64")
        set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "" FORCE)
    elseif(ANDROID_ABI MATCHES "x86")
        set(VCPKG_TARGET_TRIPLET "x86-android" CACHE STRING "" FORCE)
    else()
        message(FATAL_ERROR "
        Please specify ANDROID_ABI
        For example
        cmake ... -DANDROID_ABI=armeabi-v7a

        Possible ABIs are: arm64-v8a, armeabi-v7a, x64-android, x86-android
        ")
    endif()
    message("vcpkg_android.cmake: VCPKG_TARGET_TRIPLET was set to ${VCPKG_TARGET_TRIPLET}")


    #
    # 4. Combine vcpkg and Android toolchains
    #

    # vcpkg and android both provide dedicated toolchains:
    #
    # vcpkg_toolchain_file=$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake
    # android_toolchain_file=<android-ndk>/build/cmake/android.toolchain.cmake
    #
    # When using vcpkg, the vcpkg toolchain shall be specified first. 
    # However, vcpkg provides a way to preload and additional toolchain, 
    # with the VCPKG_CHAINLOAD_TOOLCHAIN_FILE option.
    set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${VITA3K_ANDROID_NDK}/build/cmake/android.toolchain.cmake")
    set(CMAKE_TOOLCHAIN_FILE "${VITA3K_VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")
    message("vcpkg_android.cmake: CMAKE_TOOLCHAIN_FILE was set to ${CMAKE_TOOLCHAIN_FILE}")
    message("vcpkg_android.cmake: VCPKG_CHAINLOAD_TOOLCHAIN_FILE was set to ${VCPKG_CHAINLOAD_TOOLCHAIN_FILE}")

endif(VCPKG_TARGET_ANDROID)

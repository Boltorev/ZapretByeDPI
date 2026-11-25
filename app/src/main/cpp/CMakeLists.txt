# ---------------------------------------------------------------------------
# CMake только для ByeDPI.
#
# Важно: это НЕ shared library, а ELF executable. Мы собираем его с именем
# libbyedpi.so, кладём в jniLibs и запускаем из nativeLibraryDir через
# ProcessBuilder (вариант A).
#
# hev-socks5-tunnel собирается отдельно через ndk-build (app/src/main/jni).
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)

project(byedpi_exec C)

# Все .c, кроме win_service.c (это Windows-служба).
file(GLOB BYEDPI_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/byedpi/*.c")
list(REMOVE_ITEM BYEDPI_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c")

add_executable(byedpi ${BYEDPI_SOURCES})

target_include_directories(byedpi PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/byedpi")

target_compile_definitions(byedpi PRIVATE
        _GNU_SOURCE
        _DEFAULT_SOURCE)

target_compile_options(byedpi PRIVATE
        -std=c99
        -O2
        -Wall
        -Wno-unused
        -Wextra
        -Wno-unused-parameter
        -fPIE)

target_link_options(byedpi PRIVATE
        -fPIE
        -pie
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

set_target_properties(byedpi PROPERTIES
        OUTPUT_NAME "byedpi"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")

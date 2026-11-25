# ---------------------------------------------------------------------------
# Корневой Android.mk для ndk-build.
#
# Здесь собираются:
#   1. libhev-socks5-tunnel.so — tun2socks (исходники hev 2.20.0);
#   2. libhevbridge.so        — наш тонкий JNI-мост к hev.
#
# ByeDPI (libbyedpi.so как ELF executable) собирается отдельно через
# NDK clang в Gradle-задаче buildByeDpiExecutable.
# ---------------------------------------------------------------------------
ROOT_PATH := $(call my-dir)

# hev: статические подмодули (yaml/lwip/hev-task-system) + сама библиотека.
include $(ROOT_PATH)/hev-socks5-tunnel/Android.mk

# Наш JNI-мост. Линкуется с libhev-socks5-tunnel.so.
include $(ROOT_PATH)/hevbridge/Android.mk

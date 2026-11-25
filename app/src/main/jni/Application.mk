# ---------------------------------------------------------------------------
# Application.mk для ndk-build.
#
# minSdk 26 -> android-26. Собираем все нужные ABI:
#   arm64-v8a   — все современные телефоны;
#   armeabi-v7a — старые 32-битные телефоны (x86-приложения под «armeabi»);
#   x86         — старые эмуляторы (API 26-27, 32 бита);
#   x86_64      — современные эмуляторы и редкие устройства.
#
# Отдельного ABI «armeabi» (ARMv5 soft-float) в NDK 27 больше нет: он удалён
# начиная с NDK r17, поэтому 32-битные ARM-устройства обслуживает armeabi-v7a.
#
# APP_SUPPORT_FLEXIBLE_PAGE_SIZES включает выравнивание под 16 КБ страницы
# (Android 15+ на некоторых устройствах).
# ---------------------------------------------------------------------------
APP_PLATFORM := android-26
APP_ABI := arm64-v8a armeabi-v7a x86 x86_64
APP_OPTIM := release
APP_CFLAGS := -O3
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# hev build.mk иначе пытается вызвать git в каталоге проекта.
REV_ID := 2.20.0

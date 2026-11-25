# ---------------------------------------------------------------------------
# JNI-мост libhevbridge.so.
# ---------------------------------------------------------------------------
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := hevbridge
LOCAL_SRC_FILES := hev_bridge.c

# Заголовок hev-socks5-tunnel.h лежит в src, hev-list.h — в src/misc.
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/../hev-socks5-tunnel/src \
    $(LOCAL_PATH)/../hev-socks5-tunnel/src/misc

LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog

LOCAL_CFLAGS += -O2 -fvisibility=hidden
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

include $(BUILD_SHARED_LIBRARY)

/*
 * hev_bridge.c — JNI-мост между Kotlin и hev-socks5-tunnel.
 *
 * Зачем свой мост, а не встроенный hev-jni.c:
 *  - hev-jni.c рассчитан на класс hev.htproxy.TProxyService и сам создаёт
 *    поток; нам удобнее держать поток в Kotlin и контролировать запуск/стоп;
 *  - мы хотим сами логировать старт и ошибки через logcat;
 *  - hev 2.20 при внешнем tun_fd НЕ закрывает его (см. hev_socks5_tunnel_fini),
 *    поэтому fd остаётся во владении ParcelFileDescriptor в Kotlin.
 *
 * Методы совпадают с Kotlin-объектом:
 *   com.zapret.byedpi.nativebridge.HevBridge
 */

#include <jni.h>
#include <android/log.h>

#include "hev-main.h"

#define TAG "ZapretHev"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Запускает tun2socks. Блокируется до hev_socks5_tunnel_quit() или ошибки.
 * Kotlin вызывает этот метод в отдельном потоке.
 *
 * fd — файловый дескриптор TUN из VpnService.Builder.establish().
 * Возвращает 0 при успехе, отрицательное число при ошибке.
 */
JNIEXPORT jint JNICALL
Java_com_zapret_byedpi_nativebridge_HevBridge_run(
        JNIEnv *env, jobject thiz, jstring config, jint fd) {
    (void) thiz;

    if (config == NULL || fd < 0) {
        LOGE("hev: invalid arguments (config=%p, fd=%d)", config, fd);
        return -1;
    }

    /*
     * hev 2.20 при внешнем tun_fd не закрывает его: fd остаётся
     * во владении ParcelFileDescriptor в Kotlin. Поэтому передаём
     * исходный fd без dup.
     */
    const char *cfg = (*env)->GetStringUTFChars(env, config, NULL);
    if (cfg == NULL) {
        LOGE("hev: GetStringUTFChars failed");
        return -3;
    }

    jsize cfg_len = (*env)->GetStringUTFLength(env, config);
    LOGI("hev: starting tun2socks (fd=%d, config=%d bytes)",
         fd, (int) cfg_len);

    int result = hev_socks5_tunnel_main_from_str(
            (const unsigned char *) cfg, (unsigned int) cfg_len, fd);

    (*env)->ReleaseStringUTFChars(env, config, cfg);

    if (result < 0) {
        LOGE("hev: tunnel exited with code %d", result);
    } else {
        LOGI("hev: tunnel stopped normally");
    }
    return result;
}

/* Просит hev завершить цикл. Можно вызывать из любого потока. */
JNIEXPORT void JNICALL
Java_com_zapret_byedpi_nativebridge_HevBridge_stop(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    LOGI("hev: stop requested");
    hev_socks5_tunnel_quit();
}

/*
 * Статистика TUN: [tx_packets, tx_bytes, rx_packets, rx_bytes].
 * Нужна для строки «Трафик» в UI.
 */
JNIEXPORT jlongArray JNICALL
Java_com_zapret_byedpi_nativebridge_HevBridge_stats(
        JNIEnv *env, jobject thiz) {
    (void) thiz;

    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    jlong values[4];
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    }
    return result;
}

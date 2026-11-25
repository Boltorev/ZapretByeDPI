package hev.htproxy;

/**
 * Официальный JNI-контракт hev-socks5-tunnel 2.20.
 *
 * Зачем класс в нашем проекте, если мы используем собственный мост
 * com.zapret.byedpi.nativebridge.HevBridge:
 *
 *  - в hev-jni.c есть JNI_OnLoad(), который делает
 *    FindClass("hev/htproxy/TProxyService") и возвращает JNI_ERR, если
 *    класса нет. Без этого класса System.loadLibrary("hev-socks5-tunnel")
 *    падал бы ещё до вызова наших методов.
 *
 * Класс можно использовать и напрямую, но в приложении применяется
 * libhevbridge.so: он даёт более удобный контроль потока и статистику.
 *
 * Лицензия hev-socks5-tunnel — MIT.
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}

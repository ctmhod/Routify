package hev.htproxy;

/** JNI bridge to hev-socks5-tunnel (MIT license), which turns the VPN interface into SOCKS5 traffic. */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}

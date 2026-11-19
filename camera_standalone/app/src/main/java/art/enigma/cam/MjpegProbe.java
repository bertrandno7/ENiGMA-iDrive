package art.enigma.cam;

import android.net.Network;
import java.net.HttpURLConnection;
import java.util.Locale;

/** Optional read-only HTTP fallback. Never assumes port 8192 is an MJPEG service. */
final class MjpegProbe {
    static boolean supports(Network network, String host) {
        if (network == null || !CameraNetwork.safePrivateIpv4(host)) return false;
        HttpURLConnection c = null;
        try {
            c = CameraProtocol.open(network, "http://" + host + ":8192/");
            c.setConnectTimeout(750);
            c.setReadTimeout(1100);
            if (c.getResponseCode() != 200) return false;
            String type = c.getContentType();
            return type != null && type.toLowerCase(Locale.ROOT).contains("multipart/x-mixed-replace");
        } catch (Exception ignored) { return false; }
        finally { if (c != null) c.disconnect(); }
    }
}

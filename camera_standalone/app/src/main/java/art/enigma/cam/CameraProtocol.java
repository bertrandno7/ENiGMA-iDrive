package art.enigma.cam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;

/** Only read-only command probes. Never modify the camera or interrupt recording. */
final class CameraProtocol {
    static final String NOVATEK = "novatek";
    static final String EEASYTECH = "eeasytech";
    static final String HIMOBILE = "himobile";
    static final String RTSP = "rtsp";
    static final String UNKNOWN = "unknown";

    static final class Scan {
        final String host;
        final String protocol;
        final String details;
        final String diagnostic;
        final String streamUrl;
        Scan(String h, String p, String d, String log, String stream) {
            host = h; protocol = p; details = d; diagnostic = log; streamUrl = stream;
        }
        JSONObject json() {
            JSONObject j = new JSONObject();
            try {
                j.put("host", host); j.put("protocol", protocol);
                j.put("details", details); j.put("diagnostic", diagnostic); j.put("streamUrl", streamUrl);
            } catch (Exception ignored) { }
            return j;
        }
    }

    static Scan identify(Network network,String preferred,String gateway) throws Exception {
        String host=CameraNetwork.safePrivateIpv4(preferred)?preferred:CameraNetwork.safePrivateIpv4(gateway)?gateway:"192.168.0.1";
        String body=getText(network,"http://"+host+"/cgi-bin/hisnet/getdeviceattr.cgi",110000);
        java.util.Map<String,String> values=CamText.variables(body);
        if(!CamText.deviceSignature(values))throw new java.io.IOException("Camera did not return the expected device information");
        String details=values.get("model")+" · "+values.get("softversion");
        return new Scan(host,HIMOBILE,details,"ENiGMA CAM v0.6\nCamera: "+host+"\n"+CamText.excerpt(body)+"\n","");
    }

    static Scan discover(Network network, String preferred, String gateway) {
        String host = CameraNetwork.safePrivateIpv4(preferred) ? preferred :
                CameraNetwork.safePrivateIpv4(gateway) ? gateway : "192.168.0.1";
        StringBuilder log = new StringBuilder("ENiGMA CAM v0.6 — camera response diagnostics\n");
        log.append("Target: ").append(host).append("\nRead-only GET and RTSP OPTIONS/DESCRIBE; no recording or configuration commands.\n\n");
        boolean himobile = HiMobileAdapter.fingerprint(network, host, log);
        String protocol = himobile ? HIMOBILE : UNKNOWN;
        if (!himobile) {
            try {
                String xml = getText(network, "http://" + host + "/?custom=1&cmd=3012", 50000);
                if (xml.contains("<Function") && xml.contains("<Cmd>3012</Cmd>")) protocol = NOVATEK;
            } catch (Exception e) { log.append("Novatek: ").append(shortError(e)).append('\n'); }
        }
        String stream=LiveDiscovery.find(network,host,log);
        if (!stream.isEmpty() && UNKNOWN.equals(protocol)) protocol = RTSP;
        String details = himobile ? "HiSilicon/HiMobileCam API detected; " : "Camera response captured; ";
        details += stream.isEmpty() ? "live stream not confirmed" : "video SDP confirmed";
        log.append("\nResult: ").append(protocol).append("; ").append(details).append('\n');
        if (stream.isEmpty()) log.append("RTSP 400 is a rejected request, not confirmation that live video is available.\n");
        return new Scan(host, protocol, details, log.toString(), stream);
    }

    static String getText(Network network, String address, int maxBytes) throws Exception {
        HttpURLConnection connection = open(network, address);
        try {
            if (connection.getResponseCode() != 200)
                throw new java.io.IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > maxBytes) throw new java.io.IOException("Response size exceeds limit");
                    out.write(buffer, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    static HttpURLConnection open(Network network, String address) throws Exception {
        if (network == null) throw new java.io.IOException("Camera Wi-Fi unavailable");
        URL url = new URL(address);
        if (!"http".equals(url.getProtocol()) ||
                !CameraNetwork.safePrivateIpv4(url.getHost()))
            throw new SecurityException("Only private camera HTTP addresses are permitted");
        HttpURLConnection conn = (HttpURLConnection) network.openConnection(url);
        conn.setConnectTimeout(2200);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Connection", "close");
        return conn;
    }

    static JSONArray listNovatek(Network network, String host) throws Exception {
        String xml = getText(network, "http://" + host + "/?custom=1&cmd=3015", 1_500_000);
        if (xml.contains("<Status>") && !xml.contains("<File>"))
            throw new java.io.IOException("Camera did not return files. Recording may need to be stopped or playback mode enabled in Viidure.");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList nodes = doc.getElementsByTagName("File");
        JSONArray items = new JSONArray();
        for (int i = 0; i < nodes.getLength() && i < 1500; i++) {
            Element element = (Element) nodes.item(i);
            String rawPath = child(element, "FPATH");
            String name = child(element, "NAME");
            if (rawPath.isEmpty()) rawPath = name;
            String path = normalizeCameraPath(rawPath);
            if (path.isEmpty()) continue;
            String suffix = path.toLowerCase(Locale.ROOT);
            if (!suffix.endsWith(".mp4") && !suffix.endsWith(".mov") && !suffix.endsWith(".avi")
                    && !suffix.endsWith(".ts") && !suffix.endsWith(".jpg") && !suffix.endsWith(".jpeg")) continue;
            if (name.isEmpty()) name = path.substring(path.lastIndexOf('/') + 1);
            JSONObject file = new JSONObject();
            file.put("name", name);
            file.put("path", path);
            file.put("url", urlForFile(host, path));
            file.put("date", child(element, "TIME"));
            file.put("bytes", parseLong(child(element, "SIZE")));
            String check = path.toUpperCase(Locale.ROOT);
            file.put("emergency", check.contains("EMR") || check.contains("SOS") || check.contains("/RO/"));
            items.put(file);
        }
        return items;
    }

    private static String child(Element el, String name) {
        NodeList list = el.getElementsByTagName(name);
        return list.getLength() > 0 ? list.item(0).getTextContent().trim() : "";
    }
    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception ex) { return 0; }
    }

    static String normalizeCameraPath(String raw) {
        if (raw == null) return "";
        String path = raw.replace('\\', '/').replaceAll("^[A-Za-z]:", "");
        while (path.startsWith("/")) path = path.substring(1);
        if (path.contains("..") || path.contains("?") || path.contains("#") || path.contains(":") || path.isEmpty()) return "";
        return path;
    }

    static String urlForFile(String host, String path) throws Exception {
        if (!CameraNetwork.safePrivateIpv4(host)) throw new SecurityException("Bad camera address");
        String normalized = normalizeCameraPath(path);
        if (normalized.isEmpty()) throw new SecurityException("Invalid camera file path");
        StringBuilder url = new StringBuilder("http://").append(host).append('/');
        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) url.append('/');
            url.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return url.toString();
    }

    interface Progress { void update(long bytes, long total); }

    /** Streams a selected camera clip into the user's Movies/ENiGMA CAM folder. */
    static Uri download(Network network, ContentResolver resolver, String host, String path, Progress progress) throws Exception {
        String normalized = normalizeCameraPath(path);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".mp4") && !lower.endsWith(".mov") && !lower.endsWith(".avi")
                && !lower.endsWith(".ts") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg"))
            throw new SecurityException("Unsupported media file type");
        String address = urlForFile(host, path);
        HttpURLConnection conn = open(network, address);
        Uri dest = null;
        try {
            conn.setReadTimeout(20_000);
            if (conn.getResponseCode() != 200)
                throw new java.io.IOException("Camera returned HTTP " + conn.getResponseCode() + " for clip");
            String contentType = conn.getContentType();
            if (contentType != null && (contentType.contains("html") || contentType.contains("xml") || contentType.contains("json")))
                throw new java.io.IOException("Camera returned an error document instead of footage");
            String mime = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" :
                    lower.endsWith(".avi") ? "video/x-msvideo" :
                    lower.endsWith(".ts") ? "video/mp2t" : "video/mp4";
            boolean image = mime.startsWith("image/");
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (image ? "Pictures/" : "Movies/") + "ENiGMA CAM");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = image ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) :
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            dest = resolver.insert(collection, cv);
            if (dest == null) throw new java.io.IOException("Cannot create MediaStore file");
            long total = conn.getContentLengthLong();
            long received = 0, nextUpdate = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = resolver.openOutputStream(dest, "w")) {
                if (out == null) throw new java.io.IOException("Cannot write output file");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
                    out.write(buffer, 0, n);
                    received += n;
                    if (received >= nextUpdate) {
                        progress.update(received, total);
                        nextUpdate = received + 512_000;
                    }
                }
                out.flush();
            }
            if (received == 0) throw new java.io.IOException("Camera sent an empty file");
            if (total > 0 && received != total) throw new java.io.IOException("Camera transfer ended before the full recording was received");
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(dest, cv, null, null);
            progress.update(received, total);
            return dest;
        } catch (Exception ex) {
            if (dest != null) try { resolver.delete(dest, null, null); } catch (Exception ignored) { }
            throw ex;
        } finally { conn.disconnect(); }
    }

    static String shortError(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : " (" + m.replace('\n', ' ').substring(0, Math.min(65, m.length())) + ")");
    }
}

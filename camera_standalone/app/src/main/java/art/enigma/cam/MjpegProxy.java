package art.enigma.cam;

import android.net.Network;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal authenticated localhost relay for HTTP MJPEG feeds; never opens a camera control port. */
final class MjpegProxy {
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final String token;
    private volatile ServerSocket server;
    private volatile Network cameraNetwork;
    private volatile String cameraHost;
    private volatile Socket activeClient;

    MjpegProxy() {
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        StringBuilder b = new StringBuilder();
        for (byte c : random) b.append(String.format("%02x", c & 255));
        token = b.toString();
    }

    synchronized String start(Network network, String host) throws Exception {
        if (!CameraNetwork.safePrivateIpv4(host) || network == null) throw new SecurityException("No verified camera address");
        stop();
        cameraNetwork = network; cameraHost = host;
        server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ServerSocket listening = server;
        workers.execute(() -> {
            while (!listening.isClosed()) {
                try { Socket socket = listening.accept(); workers.execute(() -> handle(socket)); }
                catch (Exception ex) { if (!listening.isClosed()) break; }
            }
        });
        return "http://127.0.0.1:" + listening.getLocalPort() + "/live?token=" + token;
    }

    private void handle(Socket socket) {
        HttpURLConnection upstream = null;
        try (Socket client = socket) {
            activeClient = client;
            client.setSoTimeout(15000);
            BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String line = request.readLine();
            if (line == null || !line.startsWith("GET /live?token=" + token + " ")) {
                error(client, "403 Forbidden", "Restricted preview"); return;
            }
            while ((line = request.readLine()) != null && !line.isEmpty()) { /* discard request headers */ }
            // Novatek devices sometimes expose an MJPEG live stream on port 8192.
            // This is a candidate, not a claim about the connected camera.
            upstream = CameraProtocol.open(cameraNetwork, "http://" + cameraHost + ":8192/");
            upstream.setReadTimeout(15000);
            int status = upstream.getResponseCode();
            String type = upstream.getContentType();
            if (status != 200 || type == null || !(type.toLowerCase().contains("multipart/") || type.toLowerCase().contains("image/jpeg"))) {
                error(client, "415 Unsupported Media Type", "Camera does not expose an HTTP MJPEG preview here");
                return;
            }
            OutputStream out = client.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\nContent-Type: " + type.replace("\r", "").replace("\n", "") +
                    "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII)); out.flush();
            try (InputStream input = upstream.getInputStream()) {
                byte[] buf = new byte[16384]; int n;
                while (!client.isClosed() && (n = input.read(buf)) != -1) {
                    out.write(buf, 0, n); out.flush();
                }
            }
        } catch (Exception ignored) {
            // Closing the view or losing Wi-Fi terminates the stream cleanly.
        } finally {
            if (upstream != null) upstream.disconnect();
            if (activeClient == socket) activeClient = null;
        }
    }

    private static void error(Socket socket, String status, String message) throws Exception {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: " + body.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    synchronized void stop() {
        if (activeClient != null) try { activeClient.close(); } catch (Exception ignored) { }
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
    }

    void shutdown() { stop(); workers.shutdownNow(); }
}

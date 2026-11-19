package art.enigma.cam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 41;
    private static final int PERMISSIONS = 42;
    private WebView web;
    private CameraNetwork camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MjpegProxy mjpeg = new MjpegProxy();
    private final CamPlayer player = new CamPlayer();
    private final ExecutorService liveIo = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean liveBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile CameraProtocol.Scan lastScan;
    private volatile Network verifiedNetwork;
    private volatile JSONArray cameraFiles = new JSONArray();
    private volatile String lastPlaybackState="idle";
    private volatile int renderedSessions=0;
    private volatile String log = "No camera scan performed yet";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080C13);
        getWindow().setNavigationBarColor(0xFF080C13);
        camera = new CameraNetwork(this, reason -> {
            try {
                Network current=camera.current();
                if(verifiedNetwork!=null&&!verifiedNetwork.equals(current)) {
                    verifiedNetwork=null;lastScan=null;cameraFiles=new JSONArray();send("cameraFiles",cameraFiles);
                    send("deviceLost",message("Camera Wi-Fi changed"));
                }
                send("network",camera.state());
            }catch(Exception ignored){}
        });
        camera.start();
        web = new WebView(this);
        web.setBackgroundColor(0xFF080C13);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public void onPageFinished(WebView view, String url) {
                send("network", camera.state());
                send("library", localVideos());
                JSONObject meta = new JSONObject();
                try { meta.put("version", "0.9.0-playback-recovery"); meta.put("permissions", hasPermissions()); }
                catch (Exception ignored) { }
                send("system", meta);
                if(camera.current()!=null)web.postDelayed(() -> connectAndLoad(""), 500);
            }
        });
        web.addJavascriptInterface(new Bridge(), "ENIGMA_NATIVE");
        setContentView(web);
        web.loadUrl("file:///android_asset/enigma-camera/index.html");
    }

    private void send(String event, JSONObject payload) {
        if (web == null) return;
        runOnUiThread(() -> web.evaluateJavascript("window.ENiGMA&&window.ENiGMA.fromNative(" +
                JSONObject.quote(event) + "," + payload.toString() + ");", null));
    }
    private void send(String event, JSONArray payload) {
        if (web == null) return;
        runOnUiThread(() -> web.evaluateJavascript("window.ENiGMA&&window.ENiGMA.fromNative(" +
                JSONObject.quote(event) + "," + payload.toString() + ");", null));
    }
    private JSONObject message(String text) {
        JSONObject j = new JSONObject();
        try { j.put("text", text); } catch (Exception ignored) { }
        return j;
    }
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < 37) return true;
        return checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED;
    }
    private boolean checkPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
            needed.add("android.permission.ACCESS_LOCAL_NETWORK");
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (needed.isEmpty()) return true;
        runOnUiThread(() -> requestPermissions(needed.toArray(new String[0]), PERMISSIONS));
        send("notice", message("Allow Nearby devices / Local network, then retry your action"));
        return false;
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        send("network", camera.state());
        JSONObject status = new JSONObject();
        try { status.put("permissions", hasPermissions()); } catch (Exception ignored) { }
        send("system", status);
        if (!hasPermissions()) send("notice", message("Local-network permission is needed to communicate with your camera"));
    }

    private JSONArray localVideos() {
        JSONArray list = new JSONArray();
        try {
            String saved = getPreferences(MODE_PRIVATE).getString("local_uris", "[]");
            JSONArray uris = new JSONArray(saved);
            for (int i = 0; i < uris.length(); i++) {
                JSONObject j = uris.optJSONObject(i);
                if (j != null && j.has("uri")) list.put(j);
            }
        } catch (Exception ignored) { }
        return list;
    }
    private void addLocalVideo(Uri uri) {
        JSONArray existing = localVideos();
        String displayName = "Imported video";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) displayName = c.getString(0);
        } catch (Exception ignored) { }
        try {
            JSONObject j = new JSONObject();
            j.put("uri", uri.toString()); j.put("name", displayName);
            j.put("added", System.currentTimeMillis());
            for (int i = 0; i < existing.length(); i++) {
                if (uri.toString().equals(existing.optJSONObject(i).optString("uri"))) {
                    send("library", existing); return;
                }
            }
            existing.put(j);
            getPreferences(MODE_PRIVATE).edit().putString("local_uris", existing.toString()).apply();
            send("library", existing);
            send("notice", message("Added to your offline library"));
        } catch (Exception ignored) { }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            addLocalVideo(uri);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (camera != null) send("network", camera.state());
    }
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.ENiGMA&&window.ENiGMA.back()", null);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (camera != null) camera.stop();
        player.close(); mjpeg.shutdown(); io.shutdownNow(); liveIo.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("ENIGMA_NATIVE"); web.destroy(); web = null; }
        super.onDestroy();
    }

    private void playback(String state,String detail) {
        lastPlaybackState=state;
        if("playing".equals(state))renderedSessions++;
        JSONObject payload=new JSONObject();try{payload.put("state",state);payload.put("detail",detail);}catch(Exception ignored){}
        send("playback",payload);
        if("error".equals(state))log += "\nPlayback: "+detail+"\n";
    }
    private void connectAndLoad(String preferred) {
        if(!checkPermissions()||!connecting.compareAndSet(false,true))return;
        io.execute(()->{
            try {
                Network net=camera.current();if(net==null)throw new java.io.IOException("Connect to the camera Wi-Fi first");
                send("busy",message("Connecting to camera…"));
                CameraProtocol.Scan match=CameraProtocol.identify(net,preferred,camera.gateway());verifiedNetwork=net;lastScan=match;log=match.diagnostic;send("scan",match.json());
                send("busy",message("Loading recordings…"));
                cameraFiles=HiMobileAdapter.list(net,match.host);send("cameraFiles",cameraFiles);
                log += "Loaded "+cameraFiles.length()+" recordings, newest first.\n";
                send("notice",message("Loaded "+cameraFiles.length()+" recordings. Tap a clip to play."));
            }catch(Exception e){send("error",message(CameraProtocol.shortError(e)));}
            finally{connecting.set(false);send("idle",message(""));}
        });
    }

    private final class Bridge {
        @JavascriptInterface public void closeApp(){runOnUiThread(()->finish());}
        @JavascriptInterface public void refreshNetwork() { send("network", camera.state()); }

        @JavascriptInterface public void requestConnection(String password) {
            if (!checkPermissions()) return;
            runOnUiThread(() -> camera.requestWifi(password == null ? "" : password));
        }
        @JavascriptInterface public void openWifiSettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        }
        @JavascriptInterface public void askPermissions() { checkPermissions(); }

        @JavascriptInterface public void scan(String userHost) { connectAndLoad(userHost); }
        @JavascriptInterface public void diagnostics() {
            if(!checkPermissions())return;
            io.execute(()->{
                Network net=camera.current();CameraProtocol.Scan match=lastScan;
                if(net==null||match==null){send("error",message("Connect to the camera first"));return;}
                send("busy",message("Checking camera and live video…"));
                CameraProtocol.Scan report=CameraProtocol.discover(net,match.host,camera.gateway());
                StringBuilder journal=new StringBuilder(report.diagnostic);
                String stream=report.streamUrl;
                lastScan=new CameraProtocol.Scan(match.host,match.protocol,match.details,journal.toString(),stream);
                log=journal.toString();send("scan",lastScan.json());
            });
        }

        @JavascriptInterface public void listFiles() {
            if (!checkPermissions()) return;
            io.execute(() -> {
                Network net = camera.current();
                if (net == null) { send("error", message("Camera Wi-Fi disconnected")); return; }
                CameraProtocol.Scan match = lastScan;
                if(match==null){connectAndLoad("");return;}
                try {
                    JSONArray array = CameraProtocol.NOVATEK.equals(match.protocol) ? CameraProtocol.listNovatek(net, match.host) : HiMobileAdapter.list(net,match.host);
                    cameraFiles = array;
                    send("cameraFiles", array);
                    send("notice", message("Loaded " + array.length() + " camera files"));
                } catch (Exception ex) {
                    send("error", message("Cannot list clips: " + CameraProtocol.shortError(ex)));
                }
            });
        }

        @JavascriptInterface public void downloadFile(int index) {
            if (!checkPermissions()) return;
            io.execute(() -> {
                CameraProtocol.Scan match = lastScan;
                Network net = camera.current();
                if (match == null || net == null || index < 0 || index >= cameraFiles.length()) {
                    send("error", message("Choose a camera file while connected")); return;
                }
                JSONObject entry = cameraFiles.optJSONObject(index);
                if (entry == null) return;
                String path = entry.optString("path", "");
                String name = entry.optString("name", "Camera file");
                JSONObject start = new JSONObject();
                try { start.put("name", name); start.put("index", index); } catch (Exception ignored) { }
                send("downloadStart", start);
                try {
                    Uri saved = FastDownload.download(net, getContentResolver(), match.host, path, (size, total, mode) -> {
                        JSONObject progress = new JSONObject();
                        try { progress.put("name", name); progress.put("bytes", size); progress.put("total", total); progress.put("mode", mode); }
                        catch (Exception ignored) { }
                        send("downloadProgress", progress);
                    }, note -> send("transferNote", message(note)));
                    JSONObject completed = new JSONObject();
                    completed.put("name", name); completed.put("uri", saved.toString());
                    addLocalVideo(saved);
                    send("downloadComplete", completed);
                } catch (Exception ex) {
                    send("error", message("Download failed: " + CameraProtocol.shortError(ex)));
                }
            });
        }

        @JavascriptInterface public void importLocal() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("video/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, PICK_VIDEO);
            });
        }
        @JavascriptInterface public void openLocal(int index) {
            JSONArray videos=localVideos();JSONObject video=videos.optJSONObject(index);if(video==null)return;
            Uri uri=Uri.parse(video.optString("uri",""));
            if(!"content".equals(uri.getScheme())){send("error",message("Invalid saved recording"));return;}
            runOnUiThread(()->player.show(MainActivity.this,null,uri.toString(),video.optString("name","Saved video"),false,MainActivity.this::playback));
        }
        @JavascriptInterface public void playFile(int index) {
            CameraProtocol.Scan match=lastScan;Network net=camera.current();JSONObject item=cameraFiles.optJSONObject(index);
            if(match==null||net==null||item==null){send("error",message("Connect and choose a recording"));return;}
            try {
                playback("opening",item.optString("name","Recording"));
                String path=item.optString("path","");String address=CameraProtocol.urlForFile(match.host,path);
                runOnUiThread(()->player.show(MainActivity.this,net,address,item.optString("name","Recording"),false,MainActivity.this::playback));
            }catch(Exception e){send("error",message(CameraProtocol.shortError(e)));}
        }
        @JavascriptInterface public void refreshLibrary() { send("library", localVideos()); }

        @JavascriptInterface public void preview() {
            if(!checkPermissions()||!liveBusy.compareAndSet(false,true))return;
            liveIo.execute(()->{
                try {
                    Network net=camera.current();CameraProtocol.Scan match=lastScan;
                    if(net==null||match==null){send("error",message("Connect to your camera first"));return;}
                    playback("opening","Live view");send("notice",message("Opening live view…"));
                    StringBuilder journal = new StringBuilder();
                    String stream = match.streamUrl == null ? "" : match.streamUrl;
                    if (stream.isEmpty()) stream = LiveDiscovery.find(net, match.host, journal);
                    log += "\nLive check: " + journal + "\n";
                    if (stream.isEmpty()) {
                        // A verified HTTP MJPEG feed works without RTSP, if exposed.
                        if (MjpegProbe.supports(net, match.host)) {
                            String url = mjpeg.start(net, match.host);
                            JSONObject payload = new JSONObject(); payload.put("url", url);
                            send("preview", payload);
                            playback("playing", "Live MJPEG camera");
                        } else {
                            send("liveUnavailable", message("Camera is connected, but its firmware rejected live video. Saved recordings are still available."));
                            playback("unavailable", "RTSP stream not confirmed");
                        }
                        return;
                    }
                    final String verifiedStream = stream;
                    log += "\nLive SDP confirmed: " + verifiedStream + "\n";
                    runOnUiThread(() -> player.show(MainActivity.this, net, verifiedStream,
                            "Live camera", true, MainActivity.this::playback));
                } catch (Exception e) {
                    log += "\nLive attempt failed: " + CameraProtocol.shortError(e);
                    send("liveUnavailable", message("This camera could not start its live video feed. Recordings remain accessible."));
                    playback("unavailable", "Live session error");
                } finally { liveBusy.set(false); }
            });
        }
        @JavascriptInterface public void stopPreview(){runOnUiThread(()->player.close());mjpeg.stop();}

        @JavascriptInterface public void shareDiagnostics() {
            runOnUiThread(() -> {
                String report = "ENiGMA CAM v0.9 (Playback recovery)\n" +
                        "Wi-Fi SSID: " + camera.state().optString("ssid", "not known") + "\n" +
                        "Gateway: " + camera.gateway() + "\n" +
                        "Protocol: " + (lastScan == null ? "not scanned" : lastScan.protocol) + "\n\n" + log;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(send, "Share camera diagnostics"));
            });
        }
    }
}

package art.enigma.cam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

/** Camera sockets are routed through a Wi-Fi Network; the PROCESS is never bound. */
final class CameraNetwork {
    static final String CAMERA_SSID = "MCD_HOOS_4K_1601";
    interface Observer { void changed(String message); }

    private final Activity activity;
    private final ConnectivityManager connectivity;
    private final Observer observer;
    private volatile Network selected;
    private volatile Network userRequested;
    private ConnectivityManager.NetworkCallback monitor;
    private ConnectivityManager.NetworkCallback requested;

    CameraNetwork(Activity activity, Observer observer) {
        this.activity = activity;
        this.observer = observer;
        this.connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void start() {
        selectWifi();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        monitor = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                selectWifi(); observer.changed("Wi-Fi network detected");
            }
            @Override public void onLost(Network network) {
                if (network.equals(selected)) selected = null;
                selectWifi(); observer.changed("Camera Wi-Fi connection changed");
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                selectWifi(); observer.changed("Network status updated");
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                selectWifi(); observer.changed("Network route updated");
            }
        };
        try { connectivity.registerNetworkCallback(request, monitor); }
        catch (Exception ex) { observer.changed("Network monitoring unavailable"); }
    }

    void stop() {
        if (monitor != null) {
            try { connectivity.unregisterNetworkCallback(monitor); } catch (Exception ignored) { }
            monitor = null;
        }
        if (requested != null) {
            try { connectivity.unregisterNetworkCallback(requested); } catch (Exception ignored) { }
            requested = null;
        }
    }

    synchronized Network current() {
        selectWifi();
        return selected;
    }

    private synchronized void selectWifi() {
        Network match = null;
        Network requestedMatch = null;
        Network fallback = null;
        int wifiCount = 0;
        try {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                wifiCount++;
                fallback = network;
                if (network.equals(userRequested)) requestedMatch = network;
                String name = ssid(network);
                if (CAMERA_SSID.equalsIgnoreCase(name)) { match = network; break; }
            }
        } catch (SecurityException ignored) { }
        // Only use the unlabelled Wi-Fi if it is the sole Wi-Fi transport. This
        // does not prove it is the camera: scan requires explicit user action.
        selected = requestedMatch != null ? requestedMatch : match != null ? match : wifiCount == 1 ? fallback : null;
    }

    String ssid(Network network) {
        if (network == null) return "";
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.getTransportInfo() instanceof WifiInfo) {
                String value = ((WifiInfo) caps.getTransportInfo()).getSSID();
                if (value != null) return value.replace("\"", "");
            }
            WifiManager manager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null && manager.getConnectionInfo() != null && network.equals(connectivity.getActiveNetwork())) {
                String name = manager.getConnectionInfo().getSSID();
                return name == null ? "" : name.replace("\"", "");
            }
        } catch (Exception ignored) { }
        return "";
    }

    String gateway() {
        Network network = current();
        if (network == null) return "";
        LinkProperties link = connectivity.getLinkProperties(network);
        if (link == null) return "";
        for (RouteInfo route : link.getRoutes()) {
            InetAddress address = route.getGateway();
            if (route.isDefaultRoute() && address instanceof Inet4Address && !address.isAnyLocalAddress())
                return address.getHostAddress();
        }
        for (RouteInfo route : link.getRoutes()) {
            InetAddress address = route.getGateway();
            if (address instanceof Inet4Address && !address.isAnyLocalAddress()) return address.getHostAddress();
        }
        return "";
    }

    JSONObject state() {
        JSONObject data = new JSONObject();
        try {
            Network wiFi = current();
            data.put("wifi", wiFi != null);
            data.put("ssid", wiFi == null ? "" : ssid(wiFi));
            data.put("gateway", gateway());
            boolean cell = false;
            boolean cellValidated = false;
            for (Network n : connectivity.getAllNetworks()) {
                NetworkCapabilities c = connectivity.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    cell = true;
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) cellValidated = true;
                }
            }
            data.put("cellular", cell);
            data.put("cellularInternet", cellValidated);
            data.put("knownSsid", CAMERA_SSID);
        } catch (Exception ex) {
            try { data.put("error", "Cannot inspect networks: " + ex.getClass().getSimpleName()); }
            catch (Exception ignored) { }
        }
        return data;
    }

    void requestWifi(String password) {
        if (Build.VERSION.SDK_INT < 29 || password == null || password.isEmpty()) {
            // Prefer system-managed saved credentials; never request/display them.
            activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            observer.changed("Select the camera Wi-Fi in Android settings, then return here");
            return;
        }
        if (password.length() < 8 || password.length() > 63) {
            observer.changed("Wi-Fi password must be 8–63 characters"); return;
        }
        if (requested != null) {
            try { connectivity.unregisterNetworkCallback(requested); } catch (Exception ignored) { }
            requested = null;
        }
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(CAMERA_SSID).setWpa2Passphrase(password).build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build();
        requested = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                userRequested = network;
                selected = network;
                observer.changed("Dashcam Wi-Fi connected through Android");
            }
            @Override public void onUnavailable() { observer.changed("Android couldn't connect to camera Wi-Fi"); }
            @Override public void onLost(Network network) {
                if (network.equals(selected)) selected = null;
                if (network.equals(userRequested)) userRequested = null;
                observer.changed("Dashcam Wi-Fi disconnected");
            }
        };
        try { connectivity.requestNetwork(request, requested, 20000); }
        catch (Exception ex) { observer.changed("Wi-Fi connection request failed: " + ex.getClass().getSimpleName()); }
    }

    static boolean safePrivateIpv4(String candidate) {
        if (candidate == null || !candidate.matches("[0-9.]{7,15}")) return false;
        String[] v = candidate.split("\\.");
        if (v.length != 4) return false;
        int[] a = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                if (v[i].isEmpty() || v[i].length() > 3) return false;
                a[i] = Integer.parseInt(v[i]);
                if (a[i] < 0 || a[i] > 255) return false;
            } catch (NumberFormatException bad) { return false; }
        }
        return a[0] == 10 || (a[0] == 172 && a[1] >= 16 && a[1] <= 31)
                || (a[0] == 192 && a[1] == 168);
    }
}

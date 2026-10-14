package com.cloudmusic2ktv.dlna;

import android.app.*;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import java.io.IOException;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

/** Keeps the single casting relay alive while the screen is off or the Activity is in background. */
public final class RelayService extends Service {
    private static final String CHANNEL = "phone-relay";
    private final LocalBinder binder = new LocalBinder();
    private final Set<HttpRelay> relays = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivity;
    private volatile Network network;
    private volatile InetAddress address;
    private boolean closed;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network lost) {
            if (lost.equals(network)) lostNetwork();
        }
        @Override public void onLinkPropertiesChanged(Network changed, LinkProperties properties) {
            InetAddress current = address;
            if (changed.equals(network) && current != null
                    && properties.getLinkAddresses().stream().noneMatch(a -> current.equals(a.getAddress()))) lostNetwork();
        }
    };

    final class LocalBinder extends Binder { RelayService service() { return RelayService.this; } }

    @SuppressLint("WakelockTimeout") // Foreground service owns and releases both locks in onDestroy.
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "手机中转投屏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RelayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("手机中转投屏")
                .setContentText("正在提供视频。投屏结束后请停止并断开。")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止投屏", stop).build()).build());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UrlDisplayDlna:relay");
        // Lifetime is explicitly bounded by this foreground service; pausing must not expire the relay.
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UrlDisplayDlna:relay");
            wifiLock.acquire();
        }
        connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) CastSession.INSTANCE.stopFromNotification();
        return START_NOT_STICKY;
    }

    synchronized HttpRelay prepare(String url, String rendererUrl) throws IOException {
        if (closed) throw new IOException("手机中转已停止");
        InetAddress destination = InetAddress.getByName(new URL(rendererUrl).getHost());
        Network chosen = null;
        InetAddress local = null;
        for (Network candidate : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(candidate);
            LinkProperties properties = connectivity.getLinkProperties(candidate);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || properties == null) continue;
            try (DatagramSocket socket = new DatagramSocket()) {
                candidate.bindSocket(socket);
                socket.connect(destination, 1900);
                InetAddress bound = socket.getLocalAddress();
                if (bound instanceof Inet4Address && !bound.isAnyLocalAddress()) {
                    chosen = candidate; local = bound; break;
                }
            }
        }
        if (chosen == null) throw new IOException("手机中转需要可用的 Wi-Fi IPv4 网络");
        final Network selected = chosen;
        HttpRelay relay = new HttpRelay(local, url,
                endpoint -> (HttpURLConnection) selected.openConnection(endpoint),
                (method, status, bytes) -> Log.i("UrlDisplayDlna", "Relay " + method + " status=" + status + " bytes=" + bytes));
        relays.add(relay); network = chosen; address = local;
        return relay;
    }

    synchronized void discard(HttpRelay relay) {
        if (relay != null) { relays.remove(relay); relay.close(); }
    }

    private void lostNetwork() {
        main.post(() -> CastSession.INSTANCE.relayLost(this));
    }

    @Override public synchronized void onDestroy() {
        closed = true;
        for (HttpRelay relay : relays) relay.close();
        relays.clear();
        if (connectivity != null) connectivity.unregisterNetworkCallback(networkCallback);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}

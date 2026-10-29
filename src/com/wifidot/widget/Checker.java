package com.wifidot.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.widget.RemoteViews;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

/** Wi-Fi/internet check, router detection and widget rendering. */
public final class Checker {

    static final String PREFS = "wifidot";
    static final String STARLINK_URL = "http://192.168.100.1/";

    private static final int FLAG_IMMUTABLE = 0x04000000;
    private static final int FLAG_MUTABLE = 0x02000000;

    private static final Object LOCK = new Object();
    private static boolean running = false;
    private static boolean again = false;

    private Checker() {}

    /** Run a check in the background; done (may be null) is called when finished. */
    static void runAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (running) { again = true; if (done != null) done.run(); return; }
            running = true;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        try { checkNow(app); } catch (Throwable ignored) {}
                        synchronized (LOCK) {
                            if (!again) { running = false; break; }
                            again = false;
                        }
                    }
                } finally {
                    if (done != null) done.run();
                }
            }
        }, "wifidot-check").start();
    }

    /** Check; redraws widget/icon/tile only if something changed. */
    static synchronized void checkNow(Context ctx) {
        registerNetworkCallback(ctx);
        Events.register(ctx);
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = findWifi(cm);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);

        String state, label, url, gw = "";
        boolean hotspot = Hotspot.isOn(ctx);
        String hsNet = "";
        if (hotspot) {
            // The phone shares its default connection (usually mobile data): blue hotspot icon.
            Network up = cm.getActiveNetwork();
            if (up == null) up = wifi;
            NetworkCapabilities uc = up != null ? cm.getNetworkCapabilities(up) : null;
            boolean validated = uc != null && uc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            hsNet = validated || (up != null && hasInternet(up)) ? "online" : "offline";
            state = "hotspot"; label = "Hotspot"; url = "";
        } else if (wifi == null) {
            state = "nowifi"; label = "no Wi-Fi"; url = "";
        } else {
            boolean online = hasInternet(wifi);
            String g = gateway(ctx, cm, wifi);
            gw = g == null ? "" : g;
            long now = System.currentTimeMillis();
            boolean cached = gw.equals(p.getString("gw", null))
                    && now - p.getLong("detected2", 0) < 10 * 60 * 1000L
                    && p.getString("url", "").length() > 0;
            if (cached) {
                // same router — do not re-probe it every 10 seconds
                label = p.getString("label", gw); url = p.getString("url", "");
            } else {
                String name = ssid(ctx);
                boolean byName = name != null && name.toLowerCase(Locale.ROOT).contains("starlink");
                if (markedStarlink(ctx, g) || byName || isStarlink(wifi, g)) {
                    label = "Starlink"; url = STARLINK_URL;
                } else if (g != null) {
                    url = "http://" + g + "/";
                    String vendor = detectVendor(wifi, url);
                    label = vendor != null ? vendor : g;
                } else {
                    label = "Wi-Fi"; url = "";
                }
                p.edit().putLong("detected2", now).apply();
            }
            state = online ? "online" : "offline";
        }

        boolean changed = !state.equals(p.getString("state", ""))
                || !hsNet.equals(p.getString("hs_net", ""))
                || !label.equals(p.getString("label", ""))
                || !url.equals(p.getString("url", ""));
        p.edit().putString("state", state).putString("label", label).putString("url", url)
                .putString("gw", gw).putString("hs_net", hsNet)
                .putLong("latency", "online".equals(state) || "online".equals(hsNet) ? lastLatencyMs : -1)
                .putLong("time", System.currentTimeMillis()).apply();
        if (changed || !p.getBoolean("rendered", false)) {
            p.edit().putBoolean("rendered", true).apply();
            render(ctx);
        }
        // Keep a light poll going, also without Wi-Fi: turning the hotspot on/off creates no network event.
        Poller.schedule(ctx);
    }

    /** Network for the speed test: Wi-Fi, or the shared (mobile) connection when the hotspot is on. */
    static Network testNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network w = findWifi(cm);
        return w != null ? w : cm.getActiveNetwork();
    }

    static boolean wifiClientConnected(Context ctx) {
        return findWifi((ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE)) != null;
    }


    // ---------- Wi-Fi ----------

    @SuppressWarnings("deprecation")
    private static Network findWifi(ConnectivityManager cm) {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            // Wi-Fi client connections only: the hotspot / Wi-Fi Direct show up as local-only
            // Wi-Fi networks without the INTERNET capability — those are not "Wi-Fi without internet".
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return n;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String gateway(Context ctx, ConnectivityManager cm, Network wifi) {
        LinkProperties lp = cm.getLinkProperties(wifi);
        if (lp != null) {
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress g = r.getGateway();
                if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) {
                    return g.getHostAddress();
                }
            }
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getDhcpInfo().gateway;
            if (ip != 0) {
                return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------- Internet ----------

    static volatile long lastLatencyMs = -1;

    private static boolean hasInternet(Network wifi) {
        lastLatencyMs = -1;
        String[] urls = {
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://cp.cloudflare.com/generate_204",
        };
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) wifi.openConnection(new URL(u));
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                long t0 = System.nanoTime();
                // 204 = internet OK; 200/302 usually means a captive portal
                if (c.getResponseCode() == 204) {
                    lastLatencyMs = (System.nanoTime() - t0) / 1_000_000L;
                    return true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    // ---------- Router detection ----------

    /** Manual "this network is Starlink" flag (by gateway address). */
    static boolean markedStarlink(Context ctx, String gw) {
        return gw != null && ctx.getSharedPreferences(PREFS, 0)
                .getStringSet("starlink_gws", new java.util.HashSet<String>()).contains(gw);
    }

    /** Network name (only available with location permission and Location turned on). */
    @SuppressWarnings("deprecation")
    static String ssid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            String s = wm.getConnectionInfo().getSSID();
            if (s == null || s.contains("unknown ssid")) return null;
            return s.replace("\"", "");
        } catch (Throwable e) { return null; }
    }

    private static boolean isStarlink(final Network wifi, String gw) {
        // The Starlink dish is always reachable at 192.168.100.1 (also through the Starlink router).
        // Probe the dish gRPC ports and web port in parallel — any answer means Starlink.
        // Plus the Starlink router's own gRPC port 9000 (usually 192.168.1.1:9000).
        final String[] hosts = {"192.168.100.1", "192.168.100.1", "192.168.100.1", gw, gw};
        final int[] ports = {9200, 9201, 80, 9000, 9001};
        final boolean[] ok = {false};
        Thread[] ts = new Thread[ports.length];
        for (int i = 0; i < ports.length; i++) {
            final int port = ports[i];
            final String host = hosts[i];
            ts[i] = new Thread(new Runnable() { public void run() {
                if (host != null && tcpOpen(wifi, host, port, 3000)) synchronized (ok) { ok[0] = true; }
            }});
            ts[i].start();
        }
        for (Thread t : ts) { try { t.join(3500); } catch (InterruptedException ignored) {} }
        synchronized (ok) { if (ok[0]) return true; }
        if (gw != null) {
            String g = fetchHead(wifi, "http://" + gw + "/", 2500);
            if (g != null && g.contains("starlink")) return true;
        }
        return false;
    }

    private static boolean tcpOpen(Network net, String host, int port, int timeout) {
        Socket s = null;
        try {
            s = net.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    /** Server header + start of the page, lower-cased, or null. */
    private static String fetchHead(Network net, String url, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) net.openConnection(new URL(url));
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            String server = c.getHeaderField("Server");
            String realm = c.getHeaderField("WWW-Authenticate");
            String loc = c.getHeaderField("Location");
            if (server != null) sb.append(server).append('\n');
            if (realm != null) sb.append(realm).append('\n');
            if (loc != null) sb.append(loc).append('\n');
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int total = 0, r;
                while (total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0) total += r;
                in.close();
                sb.append(new String(buf, 0, total, "UTF-8"));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final String[][] VENDORS = {
            {"tp-link", "TP-Link"}, {"tplink", "TP-Link"}, {"mercusys", "Mercusys"},
            {"keenetic", "Keenetic"}, {"zyxel", "Zyxel"}, {"asus", "ASUS"},
            {"mikrotik", "MikroTik"}, {"routeros", "MikroTik"}, {"netgear", "Netgear"},
            {"d-link", "D-Link"}, {"dlink", "D-Link"}, {"huawei", "Huawei"},
            {"zte", "ZTE"}, {"miwifi", "Xiaomi"}, {"xiaomi", "Xiaomi"},
            {"tenda", "Tenda"}, {"ubiquiti", "Ubiquiti"}, {"unifi", "UniFi"},
            {"linksys", "Linksys"}, {"luci", "OpenWrt"}, {"openwrt", "OpenWrt"},
            {"fritz", "FRITZ!Box"}, {"netis", "Netis"}, {"cudy", "Cudy"},
            {"totolink", "TOTOLINK"}, {"tenda", "Tenda"}, {"eero", "eero"},
            {"google wifi", "Google"}, {"nest wifi", "Google"}, {"sagemcom", "Sagemcom"},
            {"technicolor", "Technicolor"}, {"arris", "Arris"}, {"ruijie", "Ruijie"},
    };

    private static String detectVendor(Network wifi, String url) {
        String page = fetchHead(wifi, url, 2500);
        if (page == null) return null;
        for (String[] v : VENDORS) if (page.contains(v[0])) return v[1];
        return null;
    }

    // ---------- Widget ----------

    static void render(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        String state = p.getString("state", "");
        String label = p.getString("label", "…");

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setImageViewResource(R.id.wifi, iconFor(p));
        rv.setInt(R.id.wifi, "setColorFilter", colorFor(state));
        rv.setTextViewText(R.id.label, label);

        Intent menu = new Intent(ctx, MenuActivity.class);
        menu.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, menu,
                PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.root, pi);

        try {
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            m.updateAppWidget(new ComponentName(ctx, WifiDotWidget.class), rv);
        } catch (Throwable ignored) {} // widget turned off

        WifiTileService.requestUpdate(ctx);
    }

    static final int GREEN = 0xFF2ECC40, RED = 0xFFFF3B30, GREY = 0xFF9E9E9E, BLUE = 0xFF2F80ED;

    /** Icon for the saved status. */
    static int iconFor(SharedPreferences p) { return iconFor(p.getString("state", "")); }

    /** The same icon everywhere: widget, tile, menu, app. */
    static int iconFor(String state) {
        if ("hotspot".equals(state)) return R.drawable.ic_hotspot;
        if ("offline".equals(state)) return R.drawable.ic_stat_bad;
        if ("nowifi".equals(state)) return R.drawable.ic_stat_off;
        return R.drawable.ic_stat_ok;
    }

    static int colorFor(String state) {
        if ("online".equals(state)) return GREEN;
        if ("offline".equals(state)) return RED;
        if ("hotspot".equals(state)) return BLUE;
        return GREY; // Wi-Fi off / unknown
    }

    /** Network-change subscription — the system wakes NetReceiver on every Wi-Fi change. */
    static void registerNetworkCallback(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(req, netPendingIntent(ctx));
        } catch (Throwable ignored) {}
    }

    static void unregisterNetworkCallback(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(netPendingIntent(ctx));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent netPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, NetReceiver.class).setAction(NetReceiver.ACTION_NET);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx, 1, i, flags);
    }
}

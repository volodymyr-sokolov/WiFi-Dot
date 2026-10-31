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
    /** Callers waiting for the current check (their receiver/job stays alive until it's done). */
    private static final java.util.List<Waiter> waiting = new java.util.ArrayList<Waiter>();
    private static final java.util.concurrent.atomic.AtomicBoolean callbackRegistered =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** When this process registered the callback (its immediate "available" echo is ignored). */
    static volatile long callbackRegisteredAt = -1;

    private Checker() {}

    /** A caller waiting for a check: released after the first pass that started after it arrived. */
    private static final class Waiter {
        final Runnable done; final long needPass;
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        Waiter(Runnable d, long p) { done = d; needPass = p; }
        void fire() { if (fired.compareAndSet(false, true)) { try { done.run(); } catch (Throwable ignored) {} } }
    }
    private static long passesStarted = 0;   // guarded by LOCK
    private static final long MAX_WAIT_MS = 45_000L;   // receivers/jobs must finish well before Android's limit
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Run a check in the background. Triggers that arrive while a check runs are coalesced into
     * one more pass. done (may be null) is called after the first complete pass that started after
     * this call — and in any case after 45 s, so a broadcast or job never overstays.
     */
    static void runAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        final Waiter w;
        synchronized (LOCK) {
            w = done == null ? null : new Waiter(done, passesStarted + 1);
            if (w != null) waiting.add(w);
            if (running) again = true;
        }
        if (w != null) MAIN.postDelayed(new Runnable() { public void run() { w.fire(); } }, MAX_WAIT_MS);
        synchronized (LOCK) {
            if (running) return;
            running = true;
            again = false;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    long pass;
                    synchronized (LOCK) { pass = ++passesStarted; }
                    try { checkNow(app); } catch (Throwable ignored) {}
                    java.util.List<Waiter> ready = new java.util.ArrayList<Waiter>();
                    boolean stop;
                    synchronized (LOCK) {
                        for (java.util.Iterator<Waiter> it = waiting.iterator(); it.hasNext(); ) {
                            Waiter x = it.next();
                            if (x.needPass <= pass) { ready.add(x); it.remove(); }
                        }
                        stop = !again;
                        if (stop) running = false; else again = false;
                    }
                    for (Waiter x : ready) x.fire();
                    if (stop) break;
                }
            }
        }, "wifidot-check").start();
    }

    /** Check; redraws widget/tile only if something changed. The next poll is always scheduled. */
    static synchronized void checkNow(Context ctx) {
        try {
            checkBody(ctx);
        } finally {
            try { Poller.schedule(ctx); } catch (Throwable ignored) {}
        }
    }

    private static void checkBody(Context ctx) {
        Cleanup.run(ctx);
        Clock.checkBoot(ctx);
        registerCallbackOnce(ctx);
        Events.register(ctx);
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = findWifi(cm);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);

        String state, label, url, gw = "", netKey = "";
        boolean wifiValidated = false, wifiUnknown = false;
        boolean measured = true;   // false when the Wi-Fi result was kept/assumed, not actually probed
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
            NetworkCapabilities wc = cm.getNetworkCapabilities(wifi);
            wifiValidated = wc != null && wc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            boolean unknown = false;
            if (online) {
                p.edit().remove("fail_since").apply();
            } else if (blockedForUs(cm, wifi)) {
                // The phone blocks our background access: not an outage. Keep a recent result,
                // otherwise say "checking" rather than guess.
                p.edit().remove("fail_since").apply();
                Boolean prev = recentPrevious(p);
                if (prev == null) unknown = true; else online = prev;
                measured = false;
            } else if (wifiValidated) {
                // Probes fail but Android still says the Wi-Fi works: could be our access being
                // throttled. Call it an outage only if it lasts a full minute.
                long since = p.getLong("fail_since", -1);
                if (since < 0) { since = Clock.now(); p.edit().putLong("fail_since", since).apply(); }
                if (Clock.now() - since < FAIL_VALIDATED_MS) {
                    Boolean prev = recentPrevious(p);
                    online = prev == null || prev;   // Android validated it: assume OK until proven
                    measured = false;
                    Poller.scheduleIn(ctx, 20_000);
                }
            }
            if (unknown) wifiUnknown = true;
            String g = gateway(ctx, cm, wifi);
            gw = g == null ? "" : g;
            netKey = wifi.toString() + "@" + gw;   // same router on a different network → re-detect
            long now = System.currentTimeMillis();
            boolean cached = netKey.equals(p.getString("net_key", null))
                    && now - p.getLong("detected2", 0) < 10 * 60 * 1000L
                    && p.getString("url", "").length() > 0;
            if (cached) {
                // same router — do not re-probe it every 10 seconds
                label = p.getString("label", gw); url = p.getString("url", "");
            } else {
                if (Starlink.detect(wifi, g)) {
                    label = "Starlink"; url = STARLINK_URL;
                } else if (g != null) {
                    url = "http://" + g + "/";
                    // Data saver: don't download the router's page just to recognise its brand.
                    String vendor = Prefs.dataSaver(ctx) ? null : detectVendor(wifi, url);
                    label = vendor != null ? vendor : g;
                } else {
                    label = "Wi-Fi"; url = "";
                }
                p.edit().putLong("detected2", now).apply();
            }
            state = wifiUnknown ? "" : online ? "online" : "offline";   // "" = grey "Checking…"
        }
        if (!"online".equals(state) && !"offline".equals(state) && !"".equals(state)
                || !netKey.equals(p.getString("net_key", ""))) {
            p.edit().remove("fail_since").apply();   // a failure on an old network doesn't count here
        }

        String prevState = p.getString("state", "");
        boolean wifiState = "online".equals(state) || "offline".equals(state);
        if (wifiState && !("online".equals(prevState) || "offline".equals(prevState))) {
            p.edit().putLong("wifi_since", Clock.now()).apply();   // Wi-Fi just (re)connected
        }
        boolean changed = !state.equals(prevState)
                || !hsNet.equals(p.getString("hs_net", ""))
                || !label.equals(p.getString("label", ""))
                || !url.equals(p.getString("url", ""));
        p.edit().putString("state", state).putString("label", label).putString("url", url)
                .putString("gw", gw).putString("net_key", netKey).putString("hs_net", hsNet)
                .putBoolean("wifi_validated", wifiValidated)
                .putLong("latency", "online".equals(state) || "online".equals(hsNet) ? lastLatencyMs : -1)
                .putLong("time", System.currentTimeMillis()).apply();
        // Only a real measurement renews "recent": a kept/assumed result must age out after 2 min.
        if (measured) p.edit().putLong("state_at", Clock.now()).apply();
        if (changed || !p.getBoolean("rendered", false)) {
            p.edit().putBoolean("rendered", true).apply();
            render(ctx);
        }
        // Lock-screen card first (so an unlocked phone never shows it next to an alert), then alerts.
        try { LockScreen.update(ctx); } catch (Throwable ignored) {}
        try { Alerts.afterCheck(ctx); } catch (Throwable ignored) {}
    }

    /**
     * Network for the quality measurement: the Wi-Fi, or the shared upstream while the hotspot is
     * on; null otherwise (never rate mobile data when Wi-Fi is simply off).
     */
    static android.net.Network qualityNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network w = findWifi(cm);
        if (w != null) return w;
        return Hotspot.isOn(ctx) ? cm.getActiveNetwork() : null;
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
        Network active = cm.getActiveNetwork();
        if (active != null && isWifiClient(cm.getNetworkCapabilities(active))) return active;
        // With two Wi-Fi client networks (dual Wi-Fi), pick deterministically:
        // validated first, then unrestricted, then the lowest network id.
        Network best = null;
        int bestScore = -1;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (!isWifiClient(c)) continue;
            int score = (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 0)
                    + (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ? 1 : 0);
            if (best == null || score > bestScore
                    || score == bestScore && netId(n) < netId(best)) { best = n; bestScore = score; }
        }
        return best;
    }

    /**
     * A Wi-Fi client connection. The hotspot / Wi-Fi Direct show up as local-only Wi-Fi networks
     * without the INTERNET capability — those are not "Wi-Fi without internet".
     */
    private static long netId(Network n) {
        try { return Long.parseLong(n.toString()); } catch (Throwable e) { return Long.MAX_VALUE; }
    }

    private static boolean isWifiClient(NetworkCapabilities c) {
        return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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

    private static final long FAIL_VALIDATED_MS = 60_000L;
    private static final long PROBE_DEADLINE_MS = 4_000L;   // per URL, DNS included

    /** The previous Wi-Fi result if it is less than 2 minutes old, else null. */
    private static Boolean recentPrevious(SharedPreferences p) {
        long at = p.getLong("state_at", -1);
        long now = Clock.now();
        if (at < 0 || at > now || now - at > 120_000L) return null;   // none, other boot, or too old
        String prev = p.getString("state", "");
        if ("online".equals(prev)) return Boolean.TRUE;
        if ("offline".equals(prev)) return Boolean.FALSE;
        return null;
    }

    /** Is the system blocking this app's traffic on that network (data saver / background restriction)? */
    @SuppressWarnings("deprecation")
    private static boolean blockedForUs(ConnectivityManager cm, Network n) {
        try {
            android.net.NetworkInfo ni = cm.getNetworkInfo(n);
            return ni != null && ni.getDetailedState() == android.net.NetworkInfo.DetailedState.BLOCKED;
        } catch (Throwable e) {
            return false;
        }
    }

    static volatile long lastLatencyMs = -1;

    private static final String[] PROBE_URLS = {
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
    };

    /**
     * Internet check: each URL gets its own 4 s budget including DNS (socket timeouts don't cover
     * DNS). A late probe thread can't touch this check's result: latency travels with it.
     */
    private static boolean hasInternet(final Network net) {
        lastLatencyMs = -1;
        for (final String u : PROBE_URLS) {
            final long[] result = {-1};   // latency in ms, or -1
            Thread t = new Thread(new Runnable() { public void run() {
                long r = probe(net, u);
                synchronized (result) { result[0] = r; }
            } }, "wifidot-probe");
            t.setDaemon(true);
            t.start();
            try { t.join(PROBE_DEADLINE_MS); } catch (InterruptedException ignored) {}
            synchronized (result) {
                if (result[0] >= 0) { lastLatencyMs = result[0]; return true; }
            }
        }
        return false;
    }

    /** One probe: latency in ms if the URL answered 204, else -1. */
    private static long probe(Network wifi, String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) wifi.openConnection(new URL(u));
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            long t0 = System.nanoTime();
            // 204 = internet OK; 200/302 usually means a captive portal
            if (c.getResponseCode() == 204) return (System.nanoTime() - t0) / 1_000_000L;
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    // ---------- Router detection ----------





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

        if (Build.VERSION.SDK_INT >= 24) WifiTileService.requestUpdate(ctx);
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
    /**
     * Register the Wi-Fi network callback once per process. Re-registering on every check made
     * Android immediately re-deliver "network available" → another check → an endless loop.
     * NetReceiver also ignores the one delivery that follows each registration.
     */
    static void registerCallbackOnce(Context ctx) {
        if (!callbackRegistered.compareAndSet(false, true)) return;   // lock-free: never blocks the UI
        callbackRegisteredAt = android.os.SystemClock.elapsedRealtime();
        registerNetworkCallback(ctx);
    }

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

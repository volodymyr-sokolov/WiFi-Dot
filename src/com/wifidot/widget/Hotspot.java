package com.wifidot.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phone hotspot (tethering) detection and a count of connected devices.
 *
 * Android gives ordinary apps no API for the client list, so several sources are tried:
 * the neighbour table (/proc/net/arp, `ip neigh`), then a ping/TCP sweep of the hotspot subnet.
 * Every step is written to a short report shown in the app ("Hotspot diagnostics").
 */
final class Hotspot {
    private static final String ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final int AP_ENABLED = 13, AP_ENABLING = 12;
    private static final long SWEEP_EVERY_MS = 30_000L;

    private static volatile int cachedCount = -1;
    private static volatile long cachedAt = 0;
    private static volatile String cachedIface = null;
    private static volatile String upstream = "";
    private static volatile String report = "";

    private Hotspot() {}

    static String report() { return report; }

    static void noteUpstream(String s) { upstream = s; }

    static void forgetCache() { cachedAt = 0; }

    /** Is the phone's Wi-Fi hotspot on? Cheap — safe to call often. */
    static boolean isOn(Context ctx) {
        try { // sticky system broadcast with the current hotspot state
            Intent i = ctx.getApplicationContext().registerReceiver(null, new IntentFilter(ACTION_AP_STATE));
            if (i != null) {
                int s = i.getIntExtra("wifi_state", -1);
                if (s >= 10 && s <= 14) return s == AP_ENABLED || s == AP_ENABLING;
            }
        } catch (Throwable ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return (Boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
        } catch (Throwable ignored) {}
        return !tetheredIfaces(ctx).isEmpty();
    }

    /**
     * Number of devices connected to the hotspot, or -1 if unknown.
     * @param wifiClientIp the phone's own Wi-Fi client address (to skip that interface), may be null
     */
    static int countClients(Context ctx, String wifiClientIp) {
        StringBuilder r = new StringBuilder();
        List<String> tethered = tetheredIfaces(ctx);
        r.append("Tethered: ").append(tethered.isEmpty() ? "unknown" : join(tethered)).append('\n');

        Found ap = apInterface(tethered, wifiClientIp);
        if (ap == null) {
            r.append("Hotspot interface: not found · ").append(allIfaces()).append('\n');
            report = r.append("Upstream: ").append(upstream).toString();
            return -1;
        }
        r.append("Hotspot interface: ").append(ap.name).append(' ')
                .append(ap.addr.getAddress().getHostAddress()).append('/').append(ap.addr.getNetworkPrefixLength()).append('\n');

        int arp = fromArp(ap.name, r);
        int neigh = arp > 0 ? -2 : fromIpNeigh(ap.name, r);
        int n = Math.max(arp, neigh);
        if (n > 0) {
            cachedCount = n; cachedAt = System.currentTimeMillis(); cachedIface = ap.name;
            report = r.append("Upstream: ").append(upstream).toString();
            return n;
        }

        // Neighbour table not readable or empty: sweep the subnet, at most every 30 s.
        long now = System.currentTimeMillis();
        if (cachedCount >= 0 && ap.name.equals(cachedIface) && now - cachedAt < SWEEP_EVERY_MS) {
            r.append("Sweep: ").append(cachedCount).append(" (cached)\n");
            report = r.append("Upstream: ").append(upstream).toString();
            return cachedCount;
        }
        int swept = sweep(ap.addr, r);
        if (swept < 0 && (arp == 0 || neigh == 0)) swept = 0;
        cachedCount = swept; cachedAt = now; cachedIface = ap.name;
        report = r.append("Upstream: ").append(upstream).toString();
        return swept;
    }

    // ------------------------------------------------------------------ interface

    /** Tethered interface names straight from the system (hidden API, usable at targetSdk 28). */
    static List<String> tetheredIfaces(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            String[] ifs = (String[]) ConnectivityManager.class.getMethod("getTetheredIfaces").invoke(cm);
            if (ifs != null) Collections.addAll(out, ifs);
        } catch (Throwable ignored) {}
        return out;
    }

    private static final class Found {
        final String name; final InterfaceAddress addr;
        Found(String n, InterfaceAddress a) { name = n; addr = a; }
    }

    private static final String[] AP_NAMES = {"ap", "swlan", "softap", "wlan", "wifi", "wl", "wigig"};
    private static final String[] NOT_AP = {"rmnet", "ccmni", "clat", "v4-", "tun", "dummy", "lo", "ipsec",
            "seth", "radio", "p2p", "bt-pan", "rndis", "usb", "ncm", "eth"};

    private static Found apInterface(List<String> tethered, String wifiClientIp) {
        try {
            Found byName = null, anyPrivate = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (a.getHostAddress().equals(wifiClientIp)) continue; // the Wi-Fi client side
                    if (tethered.contains(name)) return new Found(name, ia);
                    boolean bad = false;
                    for (String x : NOT_AP) if (lower.startsWith(x)) bad = true;
                    if (bad) continue;
                    boolean apName = false;
                    for (String x : AP_NAMES) if (lower.startsWith(x)) apName = true;
                    if (apName && byName == null) byName = new Found(name, ia);
                    if (anyPrivate == null && a.getHostAddress().endsWith(".1")) anyPrivate = new Found(name, ia);
                }
            }
            return byName != null ? byName : anyPrivate;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String allIfaces() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ni.getName()).append('=').append(ia.getAddress().getHostAddress());
                    }
                }
            }
        } catch (Throwable e) {
            return "interfaces unreadable";
        }
        return sb.length() == 0 ? "no IPv4 interfaces" : sb.toString();
    }

    // ------------------------------------------------------------------ neighbour table

    /** /proc/net/arp. Returns count, or -1 if unreadable. */
    private static int fromArp(String iface, StringBuilder r) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader("/proc/net/arp"));
            in.readLine(); // header
            Set<String> macs = new HashSet<String>();
            int rows = 0;
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                // IP address, HW type, Flags, HW address, Mask, Device
                if (f.length < 6) continue;
                rows++;
                if (f[5].equals(iface) && !"0x0".equals(f[2]) && !f[3].equals("00:00:00:00:00:00")) macs.add(f[3]);
            }
            r.append("ARP table: ").append(macs.size()).append(" on ").append(iface)
                    .append(" (").append(rows).append(" rows)\n");
            return macs.size();
        } catch (Throwable e) {
            r.append("ARP table: blocked\n");
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** `ip neigh`. Returns count, or -1 if not allowed. */
    private static int fromIpNeigh(String iface, StringBuilder r) {
        Process p = null;
        try {
            p = new ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Set<String> macs = new HashSet<String>();
            int rows = 0;
            String line;
            while ((line = in.readLine()) != null) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("denied") || l.contains("not permitted") || l.contains("cannot")) {
                    r.append("ip neigh: blocked\n");
                    return -1;
                }
                rows++;
                if (!l.contains(" dev " + iface.toLowerCase(Locale.ROOT) + " ")) continue;
                int i = l.indexOf("lladdr ");
                if (i < 0 || l.contains("failed") || l.contains("incomplete")) continue;
                macs.add(l.substring(i + 7).split("\\s+")[0]);
            }
            int code = p.waitFor();
            if (code != 0) { r.append("ip neigh: error ").append(code).append('\n'); return -1; }
            r.append("ip neigh: ").append(macs.size()).append(" (").append(rows).append(" rows)\n");
            return rows == 0 ? -1 : macs.size();
        } catch (Throwable e) {
            r.append("ip neigh: unavailable\n");
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ------------------------------------------------------------------ subnet sweep

    private static final int[] PORTS = {62078 /* iPhone/iPad */, 80, 443, 445 /* Windows */, 7000 /* Mac */};

    private static int sweep(InterfaceAddress ap, StringBuilder r) {
        long t0 = System.currentTimeMillis();
        try {
            byte[] own = ap.getAddress().getAddress();
            final InetAddress src = ap.getAddress();
            final AtomicInteger alive = new AtomicInteger(), byPing = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(128);
            for (int last = 1; last <= 254; last++) {
                if ((own[3] & 0xff) == last) continue;
                final byte[] b = own.clone();
                b[3] = (byte) last;
                ex.execute(new Runnable() {
                    public void run() {
                        try {
                            InetAddress host = InetAddress.getByAddress(b);
                            if (host.isReachable(600)) { alive.incrementAndGet(); byPing.incrementAndGet(); }
                            else if (tcpAlive(host, src)) alive.incrementAndGet();
                        } catch (Throwable ignored) {}
                    }
                });
            }
            ex.shutdown();
            ex.awaitTermination(7, TimeUnit.SECONDS);
            ex.shutdownNow();
            r.append("Sweep: ").append(alive.get()).append(" found (").append(byPing.get()).append(" by ping) in ")
                    .append(System.currentTimeMillis() - t0).append(" ms\n");
            return alive.get();
        } catch (Throwable e) {
            r.append("Sweep: failed (").append(e.getClass().getSimpleName()).append(")\n");
            return -1;
        }
    }

    /** A host that answers a TCP connect — even with "refused" — is there. */
    private static boolean tcpAlive(InetAddress host, InetAddress src) {
        for (int port : PORTS) {
            Socket s = new Socket();
            try {
                // Send from the hotspot's own address so the probe leaves via the hotspot, not mobile data.
                try { s.bind(new InetSocketAddress(src, 0)); } catch (Throwable ignored) {}
                s.connect(new InetSocketAddress(host, port), 300);
                return true;
            } catch (java.net.ConnectException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (m.contains("refused")) return true;            // RST: device present, port closed
            } catch (Throwable ignored) {
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }
}

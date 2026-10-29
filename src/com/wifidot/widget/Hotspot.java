package com.wifidot.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phone hotspot (tethering) detection and a best-effort count of connected devices.
 *
 * Android gives ordinary apps no API for the client list, so the count is taken from the
 * neighbour table when the system allows reading it, otherwise by a quick sweep of the
 * hotspot subnet (ping + TCP). Devices with a strict firewall may not be counted.
 */
final class Hotspot {
    private static final String ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final int AP_ENABLED = 13, AP_ENABLING = 12;
    private static final long SWEEP_EVERY_MS = 30_000L;

    private static volatile int cachedCount = -1;
    private static volatile long cachedAt = 0;
    private static volatile String cachedIface = null;

    private Hotspot() {}

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
        return apInterface(null) != null;
    }

    /**
     * Number of devices connected to the hotspot, or -1 if unknown.
     * @param wifiClientIp the phone's own Wi-Fi client address (to skip that interface), may be null
     */
    static int countClients(String wifiClientIp) {
        InterfaceAddress ap = apInterface(wifiClientIp);
        if (ap == null) return -1;
        String iface = ifaceName(ap);

        int n = fromArp(iface);
        if (n <= 0) n = fromIpNeigh(iface);
        // An empty table may just mean "not allowed to read it" — confirm with a sweep.
        if (n > 0) { cachedCount = n; cachedAt = System.currentTimeMillis(); cachedIface = iface; return n; }

        // Neighbour table not readable (Android 10+): sweep the subnet, at most every 30 s.
        long now = System.currentTimeMillis();
        if (cachedCount >= 0 && iface.equals(cachedIface) && now - cachedAt < SWEEP_EVERY_MS) return cachedCount;
        n = sweep(ap);
        cachedCount = n; cachedAt = now; cachedIface = iface;
        return n;
    }

    static void forgetCache() { cachedAt = 0; }

    // ------------------------------------------------------------------ interface

    private static final String[] AP_NAMES = {"ap", "swlan", "softap", "wlan1", "wlan2", "wifi-ap", "wl0.1"};

    /** The hotspot's own address on its interface (usually x.x.x.1), or null. */
    static InterfaceAddress apInterface(String wifiClientIp) {
        try {
            InterfaceAddress fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                boolean apName = false;
                for (String p : AP_NAMES) if (name.startsWith(p)) apName = true;
                boolean wlan = name.startsWith("wlan");
                if (!apName && !wlan) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (a.getHostAddress().equals(wifiClientIp)) continue; // that's the Wi-Fi client side
                    if (apName) return ia;
                    if (fallback == null && a.getHostAddress().endsWith(".1")) fallback = ia;
                }
            }
            return fallback;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String ifaceName(InterfaceAddress ia) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress x : ni.getInterfaceAddresses()) if (x.equals(ia)) return ni.getName();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    // ------------------------------------------------------------------ neighbour table

    /** /proc/net/arp — readable up to Android 9. */
    private static int fromArp(String iface) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            r.readLine(); // header
            Set<String> macs = new HashSet<String>();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                // IP, HW type, Flags, HW address, Mask, Device
                if (f.length >= 6 && f[5].equals(iface) && "0x2".equals(f[2]) && !f[3].equals("00:00:00:00:00:00")) {
                    macs.add(f[3]);
                }
            }
            return macs.size();
        } catch (Throwable e) {
            return -1;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    /** `ip neigh` — works on some phones where /proc/net/arp is blocked. */
    private static int fromIpNeigh(String iface) {
        Process p = null;
        try {
            p = new ProcessBuilder("ip", "neigh", "show", "dev", iface).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Set<String> macs = new HashSet<String>();
            boolean any = false;
            String line;
            while ((line = r.readLine()) != null) {
                any = true;
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("denied") || l.contains("not permitted")) return -1;
                int i = l.indexOf("lladdr ");
                if (i < 0 || l.contains("failed") || l.contains("incomplete")) continue;
                macs.add(l.substring(i + 7).split("\\s+")[0]);
            }
            int code = p.waitFor();
            if (code != 0) return -1;
            return any ? macs.size() : -1;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ------------------------------------------------------------------ subnet sweep

    private static final int[] PORTS = {62078 /* iPhone/iPad */, 80};

    private static int sweep(InterfaceAddress ap) {
        try {
            byte[] own = ap.getAddress().getAddress();
            final NetworkInterface ni = NetworkInterface.getByInetAddress(ap.getAddress());
            final AtomicInteger alive = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(96);
            for (int last = 1; last <= 254; last++) {
                if ((own[3] & 0xff) == last) continue;
                final byte[] b = own.clone();
                b[3] = (byte) last;
                ex.execute(new Runnable() {
                    public void run() {
                        try {
                            InetAddress host = InetAddress.getByAddress(b);
                            if (host.isReachable(ni, 0, 500) || tcpAlive(host)) alive.incrementAndGet();
                        } catch (Throwable ignored) {}
                    }
                });
            }
            ex.shutdown();
            ex.awaitTermination(6, TimeUnit.SECONDS);
            ex.shutdownNow();
            return alive.get();
        } catch (Throwable e) {
            return -1;
        }
    }

    /** A host that answers a TCP connect — even with "refused" — is there. */
    private static boolean tcpAlive(InetAddress host) {
        for (int port : PORTS) {
            Socket s = new Socket();
            try {
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
}

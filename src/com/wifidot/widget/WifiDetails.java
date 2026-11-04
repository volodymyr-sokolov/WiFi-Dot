package com.wifidot.widget;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Wi-Fi network name and signal strength.
 *
 * Android reveals the network name (SSID) only to apps with location permission and with
 * Location turned on; without them it returns "<unknown ssid>" and the name is simply hidden.
 * The hotspot's own name is only readable on some phones; otherwise it's hidden too.
 */
final class WifiDetails {
    private WifiDetails() {}

    static final String LOCATION = "android.permission.ACCESS_FINE_LOCATION";

    static boolean hasLocationPermission(Context ctx) {
        return ctx.checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    /** Name of the connected Wi-Fi network, or null if Android doesn't reveal it. */
    @SuppressWarnings("deprecation")
    static String ssid(Context ctx) {
        try {
            WifiInfo wi = wifi(ctx).getConnectionInfo();
            if (wi == null) return null;
            String s = wi.getSSID();
            if (s == null) return null;
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            if (s.isEmpty() || s.equalsIgnoreCase("<unknown ssid>") || s.equals("0x")) return null;
            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Signal strength in dBm, or 0 if unknown. */
    @SuppressWarnings("deprecation")
    static int rssi(Context ctx) {
        try {
            WifiInfo wi = wifi(ctx).getConnectionInfo();
            int r = wi == null ? 0 : wi.getRssi();
            return r > -127 && r < 0 ? r : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Signal bars 0–4 for a dBm value. */
    static int bars(int rssi) {
        if (rssi == 0) return -1;
        return WifiManager.calculateSignalLevel(rssi, 5);
    }

    /** The phone's own hotspot name, where the phone allows reading it; else null. */
    static String hotspotName(Context ctx) {
        WifiManager wm = wifi(ctx);
        try {   // Android 11+
            Object cfg = WifiManager.class.getMethod("getSoftApConfiguration").invoke(wm);
            Object ssid = cfg.getClass().getMethod("getSsid").invoke(cfg);
            if (ssid != null && ssid.toString().length() > 0) return ssid.toString();
        } catch (Throwable ignored) {}
        try {   // older versions
            Object cfg = WifiManager.class.getMethod("getWifiApConfiguration").invoke(wm);
            Object ssid = cfg.getClass().getField("SSID").get(cfg);
            if (ssid != null && ssid.toString().length() > 0) return ssid.toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private static WifiManager wifi(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
}

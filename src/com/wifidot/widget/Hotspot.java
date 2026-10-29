package com.wifidot.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

/** Is the phone's own Wi-Fi hotspot (tethering) on? */
final class Hotspot {
    private static final String ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final int AP_ENABLED = 13, AP_ENABLING = 12;

    private Hotspot() {}

    /** Cheap — safe to call often. */
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
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            String[] ifs = (String[]) ConnectivityManager.class.getMethod("getTetheredIfaces").invoke(cm);
            return ifs != null && ifs.length > 0;
        } catch (Throwable ignored) {}
        return false;
    }
}

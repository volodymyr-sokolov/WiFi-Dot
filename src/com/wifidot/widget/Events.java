package com.wifidot.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Instant updates while the app process is alive: hotspot on/off, Wi-Fi on/off, connectivity
 * changes, screen on/off and unlock. (Android doesn't deliver these to the manifest receiver on 8.0+,
 * so they're registered at runtime; the poll alarm covers the time the process is asleep.)
 */
final class Events {
    private static boolean registered;

    private Events() {}

    @SuppressWarnings("deprecation")
    static synchronized void register(Context ctx) {
        if (registered) return;
        final Context app = ctx.getApplicationContext();
        IntentFilter f = new IntentFilter();
        f.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        f.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        f.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (isInitialStickyBroadcast()) return;
                    String a = i.getAction();
                    // Lock-screen card: ready before the screen comes back, gone as soon as the phone is unlocked.
                    if (Intent.ACTION_USER_PRESENT.equals(a)) { LockScreen.unlocked(app); return; }
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) { LockScreen.update(app); return; }
                    if (Intent.ACTION_SCREEN_ON.equals(a)) LockScreen.update(app);
                    Checker.runAsync(app, null);
                }
            }, f);
            registered = true;
        } catch (Throwable ignored) {}
    }
}

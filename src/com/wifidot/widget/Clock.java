package com.wifidot.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Monotonic time for the alert/connect timers: elapsedRealtime() never jumps when the wall clock
 * is corrected. It restarts at boot, so stored timestamps are only valid for the same boot.
 */
final class Clock {
    private Clock() {}

    static long now() { return SystemClock.elapsedRealtime(); }

    /** Identifies the current boot (boot count where available, else the boot time to the minute). */
    static String bootId(Context ctx) {
        try {
            int n = Settings.Global.getInt(ctx.getContentResolver(), "boot_count", -1);
            if (n >= 0) return "c" + n;
        } catch (Throwable ignored) {}
        return "t" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
    }

    /** Clears timers stored during a previous boot. */
    static void checkBoot(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String id = bootId(ctx);
        if (id.equals(p.getString("boot_id", ""))) return;
        p.edit().putString("boot_id", id)
                .remove("pending_state").remove("pending_since").remove("last_alert_at")
                .putLong("wifi_since", now()).remove("fail_since").apply();
    }
}

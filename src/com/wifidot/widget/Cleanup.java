package com.wifidot.widget;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import java.util.List;

/** Removes everything older versions left behind: their notifications and notification channels. */
final class Cleanup {
    private Cleanup() {}

    static void run(Context ctx) {
        if (ctx.getSharedPreferences(Checker.PREFS, 0).getBoolean("cleaned_v3", false)) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            for (int id : new int[]{1, 2}) nm.cancel(id);   // old status icon + old single-ID alert
            if (Build.VERSION.SDK_INT >= 26) {
                List<?> chans = (List<?>) nm.getClass().getMethod("getNotificationChannels").invoke(nm);
                for (Object ch : chans) {
                    String id = (String) ch.getClass().getMethod("getId").invoke(ch);
                    if (id.equals("alerts_v2") || id.equals("lockscreen_v2")) continue;   // current ones
                    nm.getClass().getMethod("deleteNotificationChannel", String.class).invoke(nm, id);
                }
            }
        } catch (Throwable ignored) {}
        ctx.getSharedPreferences(Checker.PREFS, 0).edit().putBoolean("cleaned_v3", true)
                .remove("notif").remove("alerts").remove("alerted_state").remove("confirm_state")
                .remove("hs_count").remove("hs_report").remove("hs_sys_count").remove("hs_sys_source").apply();
    }
}

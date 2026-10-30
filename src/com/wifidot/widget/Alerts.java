package com.wifidot.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Short pop-up when the internet over Wi-Fi goes down or comes back ("Internet OK · Starlink").
 *
 * - Disappears by itself after a few seconds; no permanent icon next to the clock.
 * - Only after two checks in a row agree (a quick re-check is scheduled right away), so a blip
 *   never alerts; never twice for the same status.
 * - Never while the phone's hotspot is on.
 */
final class Alerts {
    private static final String CHANNEL = "alerts_v2";
    private static final int ID = 2;
    private static final long SHOW_MS = 8000;
    private static final long CONFIRM_MS = 5000;

    private Alerts() {}

    static boolean enabled(Context c) { return c.getSharedPreferences(Checker.PREFS, 0).getBoolean("alerts2", true); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(Checker.PREFS, 0).edit().putBoolean("alerts2", on).apply();
        if (on) ensureChannel(c); else cancel(c);
    }

    /** Called after every check. */
    static void afterCheck(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String prev = p.getString("confirm_state", "");
        p.edit().putString("confirm_state", state).apply();

        if ("hotspot".equals(state)) { cancel(ctx); return; }          // never with the hotspot on
        if (!"online".equals(state) && !"offline".equals(state)) return;
        String told = p.getString("alerted_state", "");
        if (told.length() == 0) { p.edit().putString("alerted_state", state).apply(); return; } // first run: no pop-up
        if (state.equals(told)) return;                                  // already told
        if (!state.equals(prev)) { Poller.scheduleIn(ctx, CONFIRM_MS); return; } // confirm first
        p.edit().putString("alerted_state", state).apply();
        if (enabled(ctx)) show(ctx, p, state);
    }

    static void cancel(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);
    }

    private static void show(Context ctx, SharedPreferences p, String state) {
        String label = p.getString("label", "");
        String url = p.getString("url", "");
        Notification.Builder b = builder(ctx)
                .setSmallIcon(Checker.iconFor(state))
                .setColor(Checker.colorFor(state))
                .setContentTitle(("online".equals(state) ? "Internet OK" : "No internet")
                        + (label.length() > 0 ? " · " + label : ""))
                .setContentText(url.length() > 0 ? "Tap to open " + url.replace("http://", "").replace("/", "") : "")
                .setAutoCancel(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSound(null).setVibrate(null)
                .setContentIntent(PendingIntent.getActivity(ctx, 0,
                        new Intent(ctx, OpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */));
        if (Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setTimeoutAfter", long.class).invoke(b, SHOW_MS); }
            catch (Throwable ignored) {}
        }
        try {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID, b.build());
        } catch (Throwable ignored) {}
    }

    /**
     * Creates the pop-up channel. Called from the app screen too: on Android 13+ that is when the
     * system asks once for permission to show notifications.
     */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "Internet status pop-ups", 4 /* IMPORTANCE_HIGH: pops up */);
            ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
            ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                    .invoke(channel, null, null);
            ch.getMethod("enableVibration", boolean.class).invoke(channel, false);
            nm.getClass().getMethod("createNotificationChannel", ch).invoke(nm, channel);
        } catch (Throwable ignored) {}
    }

    private static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(ctx);
            try {
                return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(ctx, CHANNEL);
            } catch (Throwable ignored) {}
        }
        return new Notification.Builder(ctx);
    }
}

package com.wifidot.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Ongoing notification: status-bar icon next to the clock; the same icon (no extra large icon) in the shade. */
final class StatusNotifier {
    private static final String CHANNEL = "status_v3";
    private static final String[] OLD_CHANNELS = {"status", "status_top"};
    static final int NOTIF_ID = 1;

    private StatusNotifier() {}

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Checker.PREFS, 0).getBoolean("notif", true);
    }

    static void update(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!enabled(ctx)) { nm.cancel(NOTIF_ID); return; }
        try { nm.notify(NOTIF_ID, build(ctx)); } catch (Throwable ignored) {} // notification permission denied
    }

    static void cancel(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
    }

    static String titleFor(String state, String label) {
        if ("online".equals(state)) return "Internet OK · " + label;
        if ("offline".equals(state)) return "No internet · " + label;
        if ("nowifi".equals(state)) return "Wi-Fi not connected";
        return "Checking…";
    }

    static Notification build(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String label = p.getString("label", "");
        String url = p.getString("url", "");

        String text;
        if ("nowifi".equals(state)) text = "Tap to open Wi-Fi settings";
        else if (url.length() > 0) text = "Tap to open " + url.replace("http://", "").replace("/", "");
        else text = "";

        int icon = Checker.iconFor(state);
        int color = Checker.colorFor(state);

        Notification.Builder b = builder(ctx)
                .setSmallIcon(icon)
                .setColor(color)
                .setContentTitle(titleFor(state, label))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_MAX)
                .setSound(null).setVibrate(null)
                .setContentIntent(openIntent(ctx));
        return b.build();
    }

    static PendingIntent openIntent(Context ctx) {
        Intent open = new Intent(ctx, OpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }

    /** Notification.Builder with a channel on Android 8+ (via reflection — we compile against API 23). */
    private static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Internet status", 4 /* IMPORTANCE_HIGH: icon first by the clock */);
                ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
                ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                        .invoke(channel, null, null);
                ch.getMethod("enableVibration", boolean.class).invoke(channel, false);
                ch.getMethod("enableLights", boolean.class).invoke(channel, false);
                Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                for (String old : OLD_CHANNELS) {
                    try { nm.getClass().getMethod("deleteNotificationChannel", String.class).invoke(nm, old); }
                    catch (Throwable ignored) {}
                }
                nm.getClass().getMethod("createNotificationChannel", ch).invoke(nm, channel);
                return Notification.Builder.class.getConstructor(Context.class, String.class)
                        .newInstance(ctx, CHANNEL);
            } catch (Throwable ignored) {}
        }
        return new Notification.Builder(ctx);
    }
}

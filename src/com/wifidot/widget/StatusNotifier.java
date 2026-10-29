package com.wifidot.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Status-bar icon (ongoing notification) and pop-up alerts.
 *
 * - The icon is shown only while Wi-Fi is connected (never a "Wi-Fi off" icon or message).
 * - A pop-up appears only when the status really changes (OK ↔ no internet), never twice for the same
 *   status — e.g. not again after Wi-Fi reconnects or the app restarts. Silent updates use the
 *   "group alert summary" trick, which suppresses sound and heads-up on a high-importance channel
 *   while keeping the icon first next to the clock.
 */
final class StatusNotifier {
    private static final String CH_STATUS = "status_v4";
    private static final String CH_ALERT = "alerts_v1";
    private static final String[] OLD_CHANNELS = {"status", "status_top", "status_v3"};
    static final int NOTIF_ID = 1;
    private static final int ALERT_ID = 2;
    private static final String SILENT_GROUP = "wifidot.silent";

    /** Last posted status notification — the monitor re-uses it so it never silences a pop-up. */
    static volatile Notification last;

    private StatusNotifier() {}

    static Notification current(Context ctx) {
        Notification n = last;
        return n != null ? n : build(ctx, false);
    }

    static boolean enabled(Context ctx) { return Prefs.statusIcon(ctx); }

    static boolean showsStatus(String state) {
        return "online".equals(state) || "offline".equals(state) || "hotspot".equals(state);
    }

    /** Called after every status change. */
    static void update(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (!showsStatus(state)) {             // no Wi-Fi: no icon, no message
            MonitorService.stop(ctx);
            nm.cancel(NOTIF_ID);
            nm.cancel(ALERT_ID);
            return;
        }

        // The icon itself always updates silently; pop-ups come from maybeAlert().
        if (Prefs.statusIcon(ctx)) {
            last = build(ctx, false);
            try { nm.notify(NOTIF_ID, last); } catch (Throwable ignored) {}
            MonitorService.start(ctx);
        } else {
            MonitorService.stop(ctx);
            nm.cancel(NOTIF_ID);
        }
    }

    /**
     * Called after every check. A pop-up appears only when the internet went down or came back
     * over Wi-Fi, the new status was seen by two checks in a row (so blips while Wi-Fi or the
     * hotspot switch over never alert), and it differs from the last status we alerted about.
     * Never for the hotspot.
     */
    static void maybeAlert(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String prev = p.getString("confirm_state", "");
        p.edit().putString("confirm_state", state).apply();
        if (!"online".equals(state) && !"offline".equals(state)) return;
        if (!state.equals(prev)) return;                                   // not confirmed yet
        if (state.equals(p.getString("alerted_state", ""))) return;        // already told
        p.edit().putString("alerted_state", state).apply();
        if (!Prefs.alerts(ctx)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (Prefs.statusIcon(ctx)) {
                last = build(ctx, true);
                nm.notify(NOTIF_ID, last);
            } else {
                nm.notify(ALERT_ID, buildAlert(ctx));
            }
        } catch (Throwable ignored) {}
    }

    static void cancelAll(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID);
        nm.cancel(ALERT_ID);
    }

    static String titleFor(String state, String label) {
        if ("hotspot".equals(state)) return "Hotspot · " + label;
        if ("online".equals(state)) return "Internet OK · " + label;
        if ("offline".equals(state)) return "No internet · " + label;
        return "Checking Wi-Fi internet…";
    }

    private static String textFor(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        if ("hotspot".equals(p.getString("state", ""))) {
            return ("offline".equals(p.getString("hs_net", "")) ? "No internet to share · " : "")
                    + "Tap to open hotspot settings";
        }
        String url = p.getString("url", "");
        return url.length() > 0 ? "Tap to open " + url.replace("http://", "").replace("/", "") : "";
    }

    /** The ongoing status notification. alert=false → no sound, no pop-up. */
    static Notification build(Context ctx, boolean alert) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        // Never the crossed-out icon next to the clock: before the first check show the plain fan.
        int icon = showsStatus(state) ? Checker.iconFor(p) : R.drawable.ic_stat_ok;
        Notification.Builder b = builder(ctx, CH_STATUS)
                .setSmallIcon(icon)
                .setColor(Checker.colorFor(state))
                .setContentTitle(titleFor(state, p.getString("label", "")))
                .setContentText(textFor(ctx))
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_MAX)
                .setSound(null).setVibrate(null)
                .setOnlyAlertOnce(!alert)
                .setContentIntent(openIntent(ctx));
        if (!alert) silence(b);
        return b.build();
    }

    /** One-off pop-up used when the status-bar icon is turned off. Disappears by itself. */
    private static Notification buildAlert(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        Notification.Builder b = builder(ctx, CH_ALERT)
                .setSmallIcon(Checker.iconFor(p))
                .setColor(Checker.colorFor(state))
                .setContentTitle(titleFor(state, p.getString("label", "")))
                .setContentText(textFor(ctx))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSound(null).setVibrate(null)
                .setContentIntent(openIntent(ctx));
        call(b, "setTimeoutAfter", long.class, 8000L);
        return b.build();
    }

    private static void silence(Notification.Builder b) {
        b.setGroup(SILENT_GROUP);
        call(b, "setGroupAlertBehavior", int.class, 1 /* GROUP_ALERT_SUMMARY: children never alert */);
    }

    private static void call(Notification.Builder b, String m, Class<?> type, Object arg) {
        if (Build.VERSION.SDK_INT < 26) return;
        try { Notification.Builder.class.getMethod(m, type).invoke(b, arg); } catch (Throwable ignored) {}
    }

    static PendingIntent openIntent(Context ctx) {
        Intent open = new Intent(ctx, OpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }

    /** Notification.Builder with channels on Android 8+ (via reflection — we compile against API 23). */
    private static Notification.Builder builder(Context ctx, String channelId) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                for (String old : OLD_CHANNELS) {
                    try { nm.getClass().getMethod("deleteNotificationChannel", String.class).invoke(nm, old); }
                    catch (Throwable ignored) {}
                }
                String name = CH_STATUS.equals(channelId) ? "Status bar icon" : "Pop-up alerts";
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, name, 4 /* IMPORTANCE_HIGH: first icon by the clock */);
                ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
                ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                        .invoke(channel, null, null);
                ch.getMethod("enableVibration", boolean.class).invoke(channel, false);
                ch.getMethod("enableLights", boolean.class).invoke(channel, false);
                nm.getClass().getMethod("createNotificationChannel", ch).invoke(nm, channel);
                return Notification.Builder.class.getConstructor(Context.class, String.class)
                        .newInstance(ctx, channelId);
            } catch (Throwable ignored) {}
        }
        return new Notification.Builder(ctx);
    }
}

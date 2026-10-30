package com.wifidot.widget;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;

/**
 * Status card on the lock screen, like a widget: coloured icon + "Internet OK · Starlink".
 *
 * Android has no lock-screen widgets for ordinary apps on most phones, so this is a silent
 * notification that exists only while the phone is locked: posted when the screen locks,
 * removed as soon as the phone is unlocked. Off by default.
 */
final class LockScreen {
    private static final String CHANNEL = "lockscreen_v2";
    private static final String OLD_CHANNEL = "lockscreen_v1";
    private static final int ID = 3;

    private LockScreen() {}

    static boolean enabled(Context c) { return c.getSharedPreferences(Checker.PREFS, 0).getBoolean("lock", false); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(Checker.PREFS, 0).edit().putBoolean("lock", on).apply();
        if (on) ensureChannel(c); else cancel(c);
    }

    /** False if notifications (or this card's channel) are switched off for WiFi Dot. */
    static boolean allowed(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 24
                    && !(Boolean) nm.getClass().getMethod("areNotificationsEnabled").invoke(nm)) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                Object chan = nm.getClass().getMethod("getNotificationChannel", String.class).invoke(nm, CHANNEL);
                if (chan != null && (Integer) chan.getClass().getMethod("getImportance").invoke(chan) == 0) return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    static boolean locked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km.isKeyguardLocked();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Show the card if enabled and the phone is locked; otherwise remove it. */
    static void update(Context ctx) {
        if (!enabled(ctx) || !locked(ctx)) { cancel(ctx); return; }
        try {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID, build(ctx));
        } catch (Throwable ignored) {}
    }

    /** Screen just turned off: post the card even if the lock hasn't engaged yet (it may lock after a delay). */
    static void showNow(Context ctx) {
        if (!enabled(ctx)) return;
        try {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID, build(ctx));
        } catch (Throwable ignored) {}
    }

    static void cancel(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);
    }

    private static Notification build(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String label = p.getString("label", "");
        String title, text;
        if ("online".equals(state)) { title = "Internet OK"; text = label; }
        else if ("offline".equals(state)) { title = "No internet"; text = label; }
        else if ("hotspot".equals(state)) {
            title = "Hotspot on";
            text = "offline".equals(p.getString("hs_net", "")) ? "No internet to share" : "Sharing your connection";
        }
        else if ("nowifi".equals(state)) { title = "Wi-Fi is off"; text = ""; }
        else { title = "Checking…"; text = ""; }

        Notification.Builder b = builder(ctx)
                .setSmallIcon(Checker.iconFor(state))
                .setColor(Checker.colorFor(state))
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)   // full content on a secure lock screen too
                .setSound(null).setVibrate(null)
                .setContentIntent(PendingIntent.getActivity(ctx, 4,
                        new Intent(ctx, MenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */));
        // Never alerts: no sound, no pop-up, never wakes the screen (children of a group with
        // "alert summary only" behaviour are always silent), yet it stays a regular notification
        // that the lock screen shows (silent-channel notifications are often hidden there).
        b.setGroup("wifidot.lockscreen");
        if (Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setGroupAlertBehavior", int.class).invoke(b, 1); }
            catch (Throwable ignored) {}
        }
        Bitmap pic = Alerts.statusPicture(ctx, state);   // same coloured icon as the widget
        if (pic != null) b.setLargeIcon(pic);
        return b.build();
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "Lock screen status", 3 /* IMPORTANCE_DEFAULT: shown on the lock screen */);
            ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
            ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                    .invoke(channel, null, null);
            ch.getMethod("enableVibration", boolean.class).invoke(channel, false);
            ch.getMethod("enableLights", boolean.class).invoke(channel, false);
            try { nm.getClass().getMethod("deleteNotificationChannel", String.class).invoke(nm, OLD_CHANNEL); }
            catch (Throwable ignored) {}
            ch.getMethod("setLockscreenVisibility", int.class).invoke(channel, Notification.VISIBILITY_PUBLIC);
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

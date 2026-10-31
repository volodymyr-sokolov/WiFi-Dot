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
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long UNLOCK_QUIET_MS = 5000;   // never post right after an unlock
    private static final long EXPIRE_MS = 3 * 60_000L;   // a card nobody refreshed removes itself
                                                          // (screen-off checks re-post it every ~2 min)
    private static volatile long lastUnlock = 0;

    /**
     * Show, refresh or remove the card. Runs on the main thread, in order with the screen/unlock
     * broadcasts, and re-checks the conditions at the moment of posting — so a slow background
     * check can never put the card back after the phone was unlocked.
     *  - screen off  → show (the phone may lock a few seconds later)
     *  - screen on   → show only while locked, otherwise remove
     */
    static void update(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        Runnable r = new Runnable() { public void run() { decide(app); } };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run(); else MAIN.post(r);
    }

    /** The phone was just unlocked. */
    static void unlocked(Context ctx) {
        lastUnlock = android.os.SystemClock.elapsedRealtime();
        cancel(ctx);
    }

    /**
     * Should the card be on screen right now? The single rule used both for the card and by
     * Alerts (which stays quiet whenever the card is showing), so the two never appear together.
     */
    static boolean isShowing(Context ctx) {
        return enabled(ctx) && allowed(ctx) && !justUnlocked() && lockedOrDark(ctx);
    }

    private static boolean justUnlocked() {
        return android.os.SystemClock.elapsedRealtime() - lastUnlock < UNLOCK_QUIET_MS;
    }

    private static boolean lockedOrDark(Context ctx) {
        boolean interactive = ((android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).isInteractive();
        return !interactive || locked(ctx);
    }

    private static void decide(final Context ctx) {
        if (enabled(ctx) && justUnlocked() && lockedOrDark(ctx)) {
            // e.g. unlocked and pressed power within 5 s: try again once the quiet period is over
            long wait = UNLOCK_QUIET_MS - (android.os.SystemClock.elapsedRealtime() - lastUnlock) + 200;
            MAIN.postDelayed(new Runnable() { public void run() { decide(ctx); } }, wait);
        }
        if (!isShowing(ctx)) { cancel(ctx); return; }
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
        if (Build.VERSION.SDK_INT >= 26) {   // removes itself if the app can't refresh/remove it
            try { Notification.Builder.class.getMethod("setTimeoutAfter", long.class).invoke(b, EXPIRE_MS); }
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

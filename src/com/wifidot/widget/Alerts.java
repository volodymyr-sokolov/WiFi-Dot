package com.wifidot.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Short pop-up when the internet over Wi-Fi goes down or comes back ("Internet OK · Starlink").
 *
 * - Disappears by itself after a few seconds; no permanent icon next to the clock.
 * - A change must hold for CONFIRM_MS across at least two checks (15 s if Android still reports
 *   the Wi-Fi as validated); no "no internet" in the first 20 s after Wi-Fi connects; at least
 *   30 s between pop-ups; never twice for the same status.
 * - Each status has its own notification ID and the other one is removed first, so a pop-up
 *   that is still on screen is never updated in place (some phones then keep the old icon).
 * - Never while the phone's hotspot is on; while locked with the lock-screen card on, the card
 *   shows the change instead.
 */
final class Alerts {
    private static final String CHANNEL = "alerts_v2";
    private static final int ID_OK = 21, ID_DOWN = 22;
    private static final long SHOW_MS = 8000;
    private static final long CONFIRM_MS = 5000;           // a change must hold at least this long
    private static final long CONFIRM_VALIDATED_MS = 15000;  // ...longer if Android still says "validated"
    private static final long CONNECT_GRACE_MS = 20000;      // no "no internet" right after Wi-Fi connects
    private static final long MIN_GAP_MS = 30000;            // between two pop-ups

    private Alerts() {}

    static boolean enabled(Context c) { return c.getSharedPreferences(Checker.PREFS, 0).getBoolean("alerts2", true); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(Checker.PREFS, 0).edit().putBoolean("alerts2", on).apply();
        if (on) ensureChannel(c); else cancel(c);
    }

    /** Called after every check (checks are serialised). */
    static void afterCheck(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        long now = Clock.now();   // monotonic; timers from a previous boot are cleared by Clock

        if ("hotspot".equals(state)) { cancel(ctx); clearPending(p); return; }   // never with the hotspot on
        if (!"online".equals(state) && !"offline".equals(state)) { clearPending(p); return; }
        String told = p.getString("alerted_state", "");
        if (told.length() == 0) { p.edit().putString("alerted_state", state).apply(); return; } // first run
        if (state.equals(told)) { clearPending(p); return; }                   // nothing new / blip over

        // A new status: it must hold for a while, across at least two checks.
        if (!state.equals(p.getString("pending_state", ""))) {
            p.edit().putString("pending_state", state).putLong("pending_since", now).apply();
            Poller.scheduleIn(ctx, CONFIRM_MS);
            return;
        }
        long held = now - p.getLong("pending_since", now);
        long need = CONFIRM_MS;
        if ("offline".equals(state)) {
            long sinceConnect = now - p.getLong("wifi_since", 0);
            if (sinceConnect < CONNECT_GRACE_MS) need = Math.max(need, CONNECT_GRACE_MS - sinceConnect + held);
            if (p.getBoolean("wifi_validated", false)) need = Math.max(need, CONFIRM_VALIDATED_MS);
        }
        if (held < need) { Poller.scheduleIn(ctx, need - held + 500); return; }
        long gap = now - p.getLong("last_alert_at", 0);
        if (gap >= 0 && gap < MIN_GAP_MS) { Poller.scheduleIn(ctx, MIN_GAP_MS - gap + 500); return; }

        // Confirmed.
        p.edit().putString("alerted_state", state).putLong("last_alert_at", now).apply();
        clearPending(p);
        if (!enabled(ctx)) return;
        if (LockScreen.isShowing(ctx)) { cancel(ctx); return; }   // the lock-screen card shows it
        show(ctx, p, state);
    }

    private static void clearPending(SharedPreferences p) {
        if (p.contains("pending_state")) p.edit().remove("pending_state").remove("pending_since").apply();
    }

    static void cancel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(ID_OK);
        nm.cancel(ID_DOWN);
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
        // Some phones show the app icon (always the green fan) instead of the small icon in pop-ups,
        // so the status icon is also attached as the picture: green fan / red fan with "!".
        Bitmap pic = statusPicture(ctx, state);
        if (pic != null) b.setLargeIcon(pic);
        if (Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setTimeoutAfter", long.class).invoke(b, SHOW_MS); }
            catch (Throwable ignored) {}
        }
        final NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        final int id = "online".equals(state) ? ID_OK : ID_DOWN;
        try {
            nm.cancel(id == ID_OK ? ID_DOWN : ID_OK);   // a fresh pop-up, never an in-place update
            nm.notify(id, b.build());
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT < 26) {   // no setTimeoutAfter before Android 8: remove it ourselves
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { try { nm.cancel(id); } catch (Throwable ignored) {} }
            }, SHOW_MS);
        }
    }

    /** The status icon in its colour on a soft round background, as a bitmap. */
    static Bitmap statusPicture(Context ctx, String state) {
        try {
            int size = Math.round(64 * ctx.getResources().getDisplayMetrics().density);
            int color = Checker.colorFor(state);
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor((color & 0x00FFFFFF) | 0x26000000); // same colour, 15% opacity
            c.drawCircle(size / 2f, size / 2f, size / 2f, bg);
            Drawable d = ctx.getDrawable(Checker.iconFor(state)).mutate();
            d.setTint(color);
            int pad = Math.round(size * 0.2f);
            d.setBounds(pad, pad, size - pad, size - pad);
            d.draw(c);
            return bm;
        } catch (Throwable e) {
            return null;
        }
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

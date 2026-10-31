package com.wifidot.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keeps the widget, tile and lock-screen card fresh with light inexact, non-waking alarms:
 * every 20 s with the screen on, every 2 min with it off (non-waking alarms are held while the
 * phone sleeps and delivered as soon as it wakes). Network/hotspot events update sooner.
 * A requested quick re-check (scheduleIn) is never overwritten by the regular schedule.
 */
final class Poller {
    static final String ACTION_POLL = "com.wifidot.widget.POLL";
    private static final long SCREEN_ON = 20_000L, SCREEN_OFF = 2 * 60_000L;
    private static volatile long soonAt = 0;   // elapsedRealtime of a requested quick re-check

    private Poller() {}

    static void schedule(Context ctx) {
        if (!Prefs.anyOutputOn(ctx)) { cancel(ctx); return; }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        long now = SystemClock.elapsedRealtime();
        long at = now + (pm.isInteractive() ? SCREEN_ON : SCREEN_OFF);
        if (soonAt > now && soonAt < at) at = soonAt;   // keep a pending quick re-check
        set(ctx, at);
    }

    /** One quick re-check, e.g. to confirm a status change before a pop-up. */
    static void scheduleIn(Context ctx, long delayMs) {
        long at = SystemClock.elapsedRealtime() + Math.max(1000, delayMs);
        soonAt = at;
        set(ctx, at);
    }

    private static void set(Context ctx, long at) {
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME, at, pi(ctx));
    }

    static void cancel(Context ctx) {
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(pi(ctx));
    }

    private static PendingIntent pi(Context ctx) {
        Intent i = new Intent(ctx, NetReceiver.class).setAction(ACTION_POLL);
        return PendingIntent.getBroadcast(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }
}

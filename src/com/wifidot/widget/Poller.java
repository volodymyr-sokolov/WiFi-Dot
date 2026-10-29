package com.wifidot.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Light polling with inexact alarms, used when the foreground monitor is not running
 * (no Wi-Fi/hotspot, status-bar icon turned off, or Android refused to start it).
 */
final class Poller {
    static final String ACTION_POLL = "com.wifidot.widget.POLL";
    private static final long SCREEN_ON = 20_000L, SCREEN_OFF = 5 * 60_000L;

    private Poller() {}

    static void schedule(Context ctx) {
        if (!Prefs.anyOutputOn(ctx)) { cancel(ctx); return; }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        long delay = pm.isInteractive() ? SCREEN_ON : SCREEN_OFF;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pi(ctx));
    }

    static void cancel(Context ctx) {
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(pi(ctx));
    }

    private static PendingIntent pi(Context ctx) {
        Intent i = new Intent(ctx, NetReceiver.class).setAction(ACTION_POLL);
        return PendingIntent.getBroadcast(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }
}

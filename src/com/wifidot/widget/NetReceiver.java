package com.wifidot.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Network-change events, poll alarms, boot and app-update broadcasts. */
public class NetReceiver extends BroadcastReceiver {
    static final String ACTION_NET = "com.wifidot.widget.NET_CHANGED";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (ACTION_NET.equals(a)) {
            // Each callback registration is answered at once with "available" — not a real change.
            long reg = ctx.getSharedPreferences(Checker.PREFS, 0).getLong("cb_registered_at", -1);
            long now = SystemClock.elapsedRealtime();
            if (reg >= 0 && reg <= now && now - reg < 30_000L) return;
        }
        LockScreen.update(ctx);   // remove a stale lock-screen card right away if the phone was unlocked
        RefreshJob.schedule(ctx);
        final PendingResult pr = goAsync();
        Checker.runAsync(ctx, new Runnable() { public void run() { pr.finish(); } });
    }
}

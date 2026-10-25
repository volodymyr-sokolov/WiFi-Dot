package com.wifidot.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives network-change events, boot and app-update broadcasts. */
public class NetReceiver extends BroadcastReceiver {
    static final String ACTION_NET = "com.wifidot.widget.NET_CHANGED";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        RefreshJob.schedule(ctx);
        MonitorService.start(ctx);
        final PendingResult pr = goAsync();
        Checker.runAsync(ctx, new Runnable() { public void run() { pr.finish(); } });
    }
}

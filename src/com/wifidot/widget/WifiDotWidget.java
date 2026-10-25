package com.wifidot.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class WifiDotWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        Checker.render(ctx);
        RefreshJob.schedule(ctx);
        MonitorService.start(ctx);
        final PendingResult pr = goAsync();
        Checker.runAsync(ctx, new Runnable() { public void run() { pr.finish(); } });
    }

    @Override
    public void onEnabled(Context ctx) {
        Checker.registerNetworkCallback(ctx);
        RefreshJob.schedule(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        Checker.unregisterNetworkCallback(ctx);
        RefreshJob.cancel(ctx);
    }
}

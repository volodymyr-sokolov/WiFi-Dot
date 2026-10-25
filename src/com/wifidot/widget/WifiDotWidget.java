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

    // onDisabled: nothing to stop — the status bar icon, pop-ups and tile keep working without the widget.
}

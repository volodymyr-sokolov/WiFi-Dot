package com.wifidot.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class WifiDotWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        Checker.render(ctx);
        RefreshJob.schedule(ctx);
        final PendingResult pr = goAsync();
        Checker.runAsync(ctx, new Runnable() { public void run() { pr.finish(); } });
    }

    @Override
    public void onEnabled(Context ctx) {
        Checker.registerCallbackOnce(ctx);
        RefreshJob.schedule(ctx);
    }

    // onDisabled: nothing to stop — the tile keeps working without the widget.
}

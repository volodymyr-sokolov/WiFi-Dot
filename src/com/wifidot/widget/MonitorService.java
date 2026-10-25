package com.wifidot.widget;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Continuous monitoring: checks the internet every 10 s with the screen on
 * and every 2 minutes with it off. Keeps the status-bar icon.
 */
public class MonitorService extends Service {
    static final long INTERVAL_SCREEN_ON = 10_000L;
    static final long INTERVAL_SCREEN_OFF = 120_000L;

    private final Object wake = new Object();
    private volatile boolean stop = false;
    private Thread loop;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            synchronized (wake) { wake.notifyAll(); } // screen turned on — check right away
        }
    };

    static void start(Context ctx) {
        if (!StatusNotifier.enabled(ctx)) return;
        Intent i = new Intent(ctx, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(ctx, i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {} // background start not allowed — it will start on next open
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, MonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(StatusNotifier.NOTIF_ID, StatusNotifier.build(this));
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        loop = new Thread(new Runnable() {
            public void run() { runLoop(); }
        }, "wifidot-monitor");
        loop.start();
    }

    private void runLoop() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        while (!stop) {
            try { Checker.checkNow(this); } catch (Throwable ignored) {}
            long wait = pm.isInteractive() ? INTERVAL_SCREEN_ON : INTERVAL_SCREEN_OFF;
            synchronized (wake) {
                try { wake.wait(wait); } catch (InterruptedException e) { return; }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (wake) { wake.notifyAll(); }
        if (!StatusNotifier.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        synchronized (wake) { wake.notifyAll(); }
        try { unregisterReceiver(screen); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

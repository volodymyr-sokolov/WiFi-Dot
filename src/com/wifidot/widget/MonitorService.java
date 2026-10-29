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
 * Foreground monitor behind the status-bar icon: checks every 10 s with the screen on and
 * every 2 min with it off. Runs only while Wi-Fi is connected and the status-bar icon is on;
 * otherwise Poller takes over with lighter alarms.
 */
public class MonitorService extends Service {
    static final long INTERVAL_SCREEN_ON = 10_000L;
    static final long INTERVAL_SCREEN_OFF = 120_000L;

    static volatile boolean running = false;

    private final Object wake = new Object();
    private volatile boolean stop = false;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            synchronized (wake) { wake.notifyAll(); } // screen turned on — check right away
        }
    };

    static void start(Context ctx) {
        if (running || !Prefs.statusIcon(ctx) || !Checker.wifiPresent(ctx)) return;
        Intent i = new Intent(ctx, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(ctx, i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable e) {
            // Android 12+ may refuse a start from the background; light polling covers the gap.
            Poller.schedule(ctx);
        }
    }

    static void stop(Context ctx) {
        if (running) ctx.stopService(new Intent(ctx, MonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (!goForeground()) { running = false; stopSelf(); return; }
        Poller.cancel(this);
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_ON));
        new Thread(new Runnable() {
            public void run() { runLoop(); }
        }, "wifidot-monitor").start();
    }

    private boolean goForeground() {
        try {
            startForeground(StatusNotifier.NOTIF_ID, StatusNotifier.current(this));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void runLoop() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        while (!stop) {
            try { Checker.checkNow(this); } catch (Throwable ignored) {}
            if (stop) break;
            long wait = pm.isInteractive() ? INTERVAL_SCREEN_ON : INTERVAL_SCREEN_OFF;
            synchronized (wake) {
                try { wake.wait(wait); } catch (InterruptedException e) { return; }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) return START_NOT_STICKY;
        goForeground(); // satisfies startForegroundService() if called again before onCreate finished
        synchronized (wake) { wake.notifyAll(); }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        synchronized (wake) { wake.notifyAll(); }
        try { unregisterReceiver(screen); } catch (Throwable ignored) {}
        Poller.schedule(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

package com.wifidot.widget;

import android.net.Network;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;

/** Download speed test over the Wi-Fi network: 4 parallel streams for up to 8 seconds. */
final class SpeedTest {
    interface Listener {
        void progress(double mbps);
        void done(double mbps, boolean ok);
    }

    private static final String URL_DOWN = "https://speed.cloudflare.com/__down?bytes=100000000";
    private static final int STREAMS = 4;
    private static final long WARMUP_MS = 1000, DURATION_MS = 8000;

    private volatile boolean cancel;

    void cancel() { cancel = true; }

    void run(final Network net, final Listener l) {
        new Thread(new Runnable() { public void run() { measure(net, l); } }, "wifidot-speed").start();
    }

    private void measure(final Network net, Listener l) {
        if (net == null) { l.done(0, false); return; }
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final long start = System.currentTimeMillis();
        Thread[] ts = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) net.openConnection(new URL(URL_DOWN));
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(5000);
                        c.setUseCaches(false);
                        InputStream in = c.getInputStream();
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while (!cancel && System.currentTimeMillis() - start < DURATION_MS
                                && (r = in.read(buf)) > 0) {
                            bytes.addAndGet(r);
                        }
                        in.close();
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
            ts[i].start();
        }
        long baseBytes = -1, baseTime = 0;
        double mbps = 0;
        while (!cancel) {
            try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            long now = System.currentTimeMillis();
            boolean alive = false;
            for (Thread t : ts) alive |= t.isAlive();
            if (baseBytes < 0 && now - start >= WARMUP_MS) { baseBytes = bytes.get(); baseTime = now; }
            if (baseBytes >= 0 && now > baseTime) {
                mbps = (bytes.get() - baseBytes) * 8.0 / ((now - baseTime) / 1000.0) / 1e6;
                l.progress(mbps);
            }
            if (!alive || now - start > DURATION_MS + 1500) break;
        }
        if (baseBytes < 0) { // finished before warm-up ended (very fast or failed)
            long el = Math.max(1, System.currentTimeMillis() - start);
            mbps = bytes.get() * 8.0 / (el / 1000.0) / 1e6;
        }
        l.done(mbps, bytes.get() > 0 && errors.get() < STREAMS);
    }
}

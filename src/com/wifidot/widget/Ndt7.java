package com.wifidot.widget;

import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Speed test with M-Lab ndt7 (https://www.measurementlab.net/tests/ndt/ndt7/):
 * find the nearest M-Lab server with the Locate API v2, then ~10 s download and ~10 s upload
 * over a WebSocket ("net.measurementlab.ndt.v7"). Throughput comes from the server's own
 * measurement messages (AppInfo NumBytes / ElapsedTime) where available, else client counts.
 */
final class Ndt7 {

    interface Listener {
        void server(String name);
        void download(double mbps, boolean done);
        void upload(double mbps, boolean done);
        void finished(double downMbps, double upMbps, long bytesUsed, String error);
    }

    private static final String LOCATE = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
            + "?client_name=wifidot-android&client_version=" + BuildInfo.VERSION;
    private static final String PROTOCOL = "net.measurementlab.ndt.v7";
    private static final long PHASE_MS = 10_000, HARD_CAP_MS = 15_000;
    private static final int MIN_FRAME = 1 << 13, MAX_FRAME = 1 << 20;

    private volatile boolean cancel;
    private long bytesUsed;
    /** The open WebSocket: closing it unblocks a stuck read or write (Stop, watchdog). */
    private volatile Ws current;

    void cancel() {
        cancel = true;
        Ws w = current;
        if (w != null) w.close();
    }

    /** Closes the socket if a phase overruns its time (a stalled link can block a write for minutes). */
    private Thread watchdog(final long ms) {
        Thread t = new Thread(new Runnable() { public void run() {
            try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
            Ws w = current;
            if (w != null) w.close();
        } }, "wifidot-ndt7-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    void run(final Network net, final Listener l) {
        new Thread(new Runnable() { public void run() { test(net, l); } }, "wifidot-ndt7").start();
    }

    private void test(Network net, Listener l) {
        double down = -1, up = -1;
        String error = null;
        try {
            if (net == null) throw new IOException("No connection to test");
            JSONObject target = locate(net);
            JSONObject loc = target.optJSONObject("location");
            String where = loc == null ? "" : (loc.optString("city", "") + ", " + loc.optString("country", ""));
            String machine = target.optString("machine", "M-Lab");
            String site = machine.contains("-") ? machine.split("\\.")[0].replaceAll("^mlab\\d-", "") : machine;
            l.server("M-Lab · " + (where.length() > 2 ? where + " (" + site + ")" : site));
            JSONObject urls = target.getJSONObject("urls");
            if (!cancel) down = download(net, urls.getString("wss:///ndt/v7/download"), l);
            if (!cancel) up = upload(net, urls.getString("wss:///ndt/v7/upload"), l);
            if (cancel) error = "Stopped";
        } catch (Throwable e) {
            error = cancel ? "Stopped"
                    : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        l.finished(down, up, bytesUsed, error);
    }

    // ------------------------------------------------------------------ locate

    private JSONObject locate(Network net) throws Exception {
        HttpURLConnection c = (HttpURLConnection) net.openConnection(new URL(LOCATE));
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "wifidot-android/" + BuildInfo.VERSION);
        int code = c.getResponseCode();
        if (code == 429) throw new IOException("M-Lab is busy, try again in a minute");
        if (code != 200) throw new IOException("Server lookup failed (HTTP " + code + ")");
        String body = readAll(c.getInputStream());
        c.disconnect();
        JSONArray results = new JSONObject(body).getJSONArray("results");
        if (results.length() == 0) throw new IOException("No M-Lab server available");
        return results.getJSONObject(0);
    }

    // ------------------------------------------------------------------ download

    private double download(Network net, String url, Listener l) throws Exception {
        Ws ws = Ws.open(net, url);
        current = ws;
        if (cancel) ws.close();
        Thread dog = watchdog(HARD_CAP_MS + 3000);
        try {
            long start = System.nanoTime(), lastUi = 0, total = 0;
            ServerCount srv = new ServerCount();
            ws.setTimeout(3000);
            while (!cancel) {
                long ms = (System.nanoTime() - start) / 1_000_000L;
                if (ms > HARD_CAP_MS) break;
                Ws.Frame f = ws.read();
                if (f == null || f.opcode == 0x8) break;           // closed by the server (~10 s)
                bytesUsed += f.length;
                if (f.opcode == 0x2 || f.opcode == 0x0) total += f.length;
                else if (f.opcode == 0x1) srv.parse(f.text());
                else if (f.opcode == 0x9) ws.send(0xA, f.data, f.data.length);   // ping → pong
                if (ms - lastUi >= 250 && ms > 0) {
                    lastUi = ms;
                    l.download(total * 8.0 / (ms * 1000.0), false);
                }
            }
            if (cancel) throw new IOException("Stopped");
            // Download: the client's own count (what actually arrived), as the ndt7 spec recommends.
            double secs = (System.nanoTime() - start) / 1e9;
            double mbps = total * 8.0 / (secs * 1e6);
            l.download(mbps, true);
            return mbps;
        } finally {
            dog.interrupt();
            current = null;
            ws.close();
        }
    }

    // ------------------------------------------------------------------ upload

    private double upload(Network net, String url, final Listener l) throws Exception {
        final Ws ws = Ws.open(net, url);
        current = ws;
        if (cancel) ws.close();
        Thread dog = watchdog(PHASE_MS + 8000);
        try {
            return uploadOn(ws, l);
        } finally {
            dog.interrupt();
            current = null;
            ws.close();
        }
    }

    private double uploadOn(final Ws ws, final Listener l) throws Exception {
        final ServerCount srv = new ServerCount();
        // The server reports what it received; read those messages in parallel.
        Thread reader = new Thread(new Runnable() { public void run() {
            try {
                ws.setTimeout(PHASE_MS_INT + 6000);
                while (true) {
                    Ws.Frame f = ws.read();
                    if (f == null || f.opcode == 0x8) break;
                    if (f.opcode == 0x1) {
                        srv.parse(f.text());
                        if (srv.mbps() > 0) l.upload(srv.mbps(), false);
                    }
                }
            } catch (Throwable ignored) {}
        } }, "wifidot-ndt7-up");
        reader.setDaemon(true);
        reader.start();
        long start = System.nanoTime(), sent = 0, lastUi = 0;
        int size = MIN_FRAME;
        byte[] payload = new byte[MAX_FRAME];
        new SecureRandom().nextBytes(payload);   // incompressible
        try {
            while (!cancel) {
                long ms = (System.nanoTime() - start) / 1_000_000L;
                if (ms >= PHASE_MS) break;
                ws.send(0x2, payload, size);
                sent += size;
                bytesUsed += size;
                if (size < MAX_FRAME && sent >= 16L * size) size *= 2;   // ndt7 message scaling
                if (srv.mbps() <= 0 && ms - lastUi >= 250 && ms > 0) {   // until the server reports
                    lastUi = ms;
                    l.upload(sent * 8.0 / (ms * 1000.0), false);
                }
            }
            ws.send(0x8, new byte[]{0x03, (byte) 0xE8}, 2);   // close, 1000 normal
        } catch (IOException e) {
            if (srv.mbps() <= 0) throw e;
        }
        try { reader.join(5000); } catch (InterruptedException ignored) {}
        if (cancel) throw new IOException("Stopped");
        double secs = Math.min(PHASE_MS, (System.nanoTime() - start) / 1_000_000L) / 1000.0;
        double mbps = srv.mbps() > 0 ? srv.mbps() : sent * 8.0 / (secs * 1e6);
        l.upload(mbps, true);
        return mbps;
    }

    private static final int PHASE_MS_INT = (int) PHASE_MS;

    /** Throughput from the server's measurement messages: AppInfo NumBytes / ElapsedTime (µs). */
    private static final class ServerCount {
        private volatile double mbps = -1;
        void parse(String json) {
            try {
                JSONObject app = new JSONObject(json).optJSONObject("AppInfo");
                if (app == null) return;
                long bytes = app.optLong("NumBytes", -1), us = app.optLong("ElapsedTime", -1);
                if (bytes > 0 && us > 0) mbps = bytes * 8.0 / us;
            } catch (Throwable ignored) {}
        }
        double mbps() { return mbps; }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        in.close();
        return b.toString("UTF-8");
    }

    // ------------------------------------------------------------------ minimal WebSocket client

    /** Just enough RFC 6455 for ndt7: TLS, client masking, binary/text/ping/close frames. */
    static final class Ws {
        static final class Frame {
            int opcode; long length; byte[] data;
            String text() { try { return new String(data, "UTF-8"); } catch (Exception e) { return ""; } }
        }

        private final Socket sock;
        private final InputStream in;
        private final OutputStream out;
        private final byte[] skip = new byte[64 * 1024];
        private final byte[] mask = new byte[4];
        private final SecureRandom rnd = new SecureRandom();
        private byte[] maskedBuf = new byte[0];

        private Ws(Socket s) throws IOException {
            sock = s;
            in = new java.io.BufferedInputStream(s.getInputStream(), 64 * 1024);
            out = new java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        }

        static Ws open(Network net, String url) throws IOException {
            URI u = URI.create(url);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 443;
            Socket raw = net.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(net.getByName(host), port), 8000);   // DNS on this network
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, port, true);
            s.setSoTimeout(8000);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                s.close();
                throw new IOException("Certificate does not match " + host);
            }
            Ws ws = new Ws(s);
            byte[] key = new byte[16];
            ws.rnd.nextBytes(key);
            String path = u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: " + PROTOCOL + "\r\n"
                    + "User-Agent: wifidot-android/" + BuildInfo.VERSION + "\r\n\r\n";
            ws.out.write(req.getBytes("ISO-8859-1"));
            ws.out.flush();
            String status = ws.readLine();
            if (status == null || !status.contains(" 101")) {
                ws.close();
                throw new IOException("Test server refused (" + status + ")");
            }
            String line;
            while ((line = ws.readLine()) != null && line.length() > 0) { /* skip headers */ }
            return ws;
        }

        void setTimeout(int ms) throws IOException { sock.setSoTimeout(ms); }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            return c < 0 && sb.length() == 0 ? null : sb.toString();
        }

        /** Next frame. Binary payloads are skipped (only their length counted); others are kept. */
        Frame read() throws IOException {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            Frame f = new Frame();
            f.opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (readByte() << 8) | readByte();
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(); }
            boolean masked = (b1 & 0x80) != 0;
            byte[] m = new byte[4];
            if (masked) for (int i = 0; i < 4; i++) m[i] = (byte) readByte();
            f.length = len;
            if (f.opcode == 0x2 || f.opcode == 0x0) {
                long left = len;
                while (left > 0) {
                    int r = in.read(skip, 0, (int) Math.min(skip.length, left));
                    if (r < 0) throw new IOException("Connection closed");
                    left -= r;
                }
                f.data = new byte[0];
            } else {
                if (len > 1 << 20) throw new IOException("Control/text frame too large");
                f.data = new byte[(int) len];
                int n = 0;
                while (n < len) {
                    int r = in.read(f.data, n, (int) len - n);
                    if (r < 0) throw new IOException("Connection closed");
                    n += r;
                }
                if (masked) for (int i = 0; i < f.data.length; i++) f.data[i] ^= m[i & 3];
            }
            return f;
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed");
            return b;
        }

        /** Send one masked frame (a fresh random mask every frame, as RFC 6455 requires). */
        synchronized void send(int opcode, byte[] payload, int len) throws IOException {
            out.write(0x80 | opcode);
            if (len < 126) out.write(0x80 | len);
            else if (len < 65536) { out.write(0x80 | 126); out.write(len >> 8); out.write(len); }
            else { out.write(0x80 | 127); for (int i = 7; i >= 0; i--) out.write((int) ((long) len >> (8 * i))); }
            rnd.nextBytes(mask);
            if (maskedBuf.length < len) maskedBuf = new byte[len];
            byte m0 = mask[0], m1 = mask[1], m2 = mask[2], m3 = mask[3];
            int i = 0;
            for (; i + 3 < len; i += 4) {
                maskedBuf[i] = (byte) (payload[i] ^ m0);
                maskedBuf[i + 1] = (byte) (payload[i + 1] ^ m1);
                maskedBuf[i + 2] = (byte) (payload[i + 2] ^ m2);
                maskedBuf[i + 3] = (byte) (payload[i + 3] ^ m3);
            }
            for (; i < len; i++) maskedBuf[i] = (byte) (payload[i] ^ mask[i & 3]);
            out.write(mask);
            out.write(maskedBuf, 0, len);
            out.flush();
        }

        void close() {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    static String fmt(double mbps) {
        if (mbps < 0) return "—";
        return mbps >= 100 ? String.format(Locale.US, "%.0f", mbps) : String.format(Locale.US, "%.1f", mbps);
    }
}

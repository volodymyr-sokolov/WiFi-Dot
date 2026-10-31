package com.wifidot.widget;

import android.net.Network;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * Detects Starlink by asking the hardware itself, not by the network name.
 *
 * Starlink dishes (always at 192.168.100.1, also behind the Starlink router) and Starlink
 * routers answer the SpaceX device API ("SpaceX.API.Device.Device/Handle"). The app sends a
 * get_status request in gRPC-web form and accepts only a genuine reply from that service
 * (a message frame, or status OK / PermissionDenied — never "Unimplemented"), which no other
 * router or modem produces. Third check in parallel: the dish's native gRPC port 9200 must
 * answer the HTTP/2 handshake with a SETTINGS frame (a plain open port is not enough).
 * The whole detection is capped at about 3 s.
 */
final class Starlink {
    static final String DISH = "192.168.100.1";
    private static final String PATH = "/SpaceX.API.Device.Device/Handle";
    /** gRPC-web frame: flag 0, length 3, protobuf Request{ get_status (field 1004) = {} }. */
    private static final byte[] GET_STATUS = {0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xE2, 0x3E, 0x00};

    private Starlink() {}

    /** True if the Wi-Fi network leads to a Starlink dish or router. Takes up to ~4 s. */
    static boolean detect(final Network net, final String gateway) {
        final boolean[] found = {false};
        Thread[] ts = {
                thread(found, new Check() { public boolean run() {               // dish, gRPC-web
                    return askDevice(net, "http://" + DISH + ":9201" + PATH); } }),
                gateway == null ? null : thread(found, new Check() { public boolean run() {  // router, gRPC-web
                    return askDevice(net, "http://" + gateway + ":9001" + PATH); } }),
                thread(found, new Check() { public boolean run() {               // dish, native gRPC
                    return http2Handshake(net, DISH, 9200); } }),
        };
        for (Thread t : ts) if (t != null) t.start();
        long deadline = System.currentTimeMillis() + 3000;
        for (Thread t : ts) {
            if (t == null) continue;
            long left = deadline - System.currentTimeMillis();
            if (left > 0) try { t.join(left); } catch (InterruptedException ignored) {}
            synchronized (found) { if (found[0]) return true; }
        }
        synchronized (found) { return found[0]; }
    }

    private interface Check { boolean run(); }

    private static Thread thread(final boolean[] found, final Check c) {
        Thread t = new Thread(new Runnable() { public void run() {
            if (c.run()) synchronized (found) { found[0] = true; }
        } }, "wifidot-starlink");
        t.setDaemon(true);
        return t;
    }

    /** POST get_status as gRPC-web; true only for a real gRPC response from the SpaceX service. */
    static boolean askDevice(Network net, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) net.openConnection(new URL(url));
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/grpc-web+proto");
            c.setRequestProperty("Accept", "application/grpc-web+proto");
            c.setRequestProperty("X-Grpc-Web", "1");
            c.setFixedLengthStreamingMode(GET_STATUS.length);
            OutputStream out = c.getOutputStream();
            out.write(GET_STATUS);
            out.close();
            int code = c.getResponseCode();
            String type = c.getContentType();
            if (code != 200 || type == null
                    || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/grpc")) return false;
            // Trailers-only reply: the status is in the headers.
            String hs = c.getHeaderField("grpc-status");
            if (hs != null) return okStatus(hs.trim());
            // Otherwise gRPC-web frames: 0x00 = a message (the device answered), 0x80 = trailers.
            InputStream in = c.getInputStream();
            byte[] buf = new byte[256];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            if (n == 0) return false;
            if ((buf[0] & 0xFF) == 0x00) return true;
            if ((buf[0] & 0xFF) == 0x80) {
                String t = new String(buf, 5, Math.max(0, n - 5), "ISO-8859-1").toLowerCase(java.util.Locale.ROOT);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("grpc-status:\\s*(\\d+)").matcher(t);
                return m.find() && okStatus(m.group(1));
            }
            return false;
        } catch (Throwable e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 0 = OK, 7 = PermissionDenied (the service exists but refuses). 12 = Unimplemented → not SpaceX. */
    private static boolean okStatus(String s) { return "0".equals(s) || "7".equals(s); }

    /** Native gRPC = HTTP/2: the server must answer the connection preface with a SETTINGS frame. */
    static boolean http2Handshake(Network net, String host, int port) {
        Socket s = null;
        try {
            s = net.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("ISO-8859-1"));
            out.write(new byte[]{0, 0, 0, 0x04, 0, 0, 0, 0, 0});   // empty SETTINGS frame
            out.flush();
            byte[] h = new byte[9];
            InputStream in = s.getInputStream();
            int n = 0, r;
            while (n < 9 && (r = in.read(h, n, 9 - n)) > 0) n += r;
            return n == 9 && h[3] == 0x04;   // frame type SETTINGS
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }
}

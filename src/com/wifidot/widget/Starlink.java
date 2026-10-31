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
 * get_status request in gRPC-web form and accepts only a genuine gRPC reply from that
 * service, which no other router or modem produces. Fallback: the dish's native gRPC port
 * 9200 being open on 192.168.100.1.
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
                probeThread(net, "http://" + DISH + ":9201" + PATH, found),        // dish, gRPC-web
                gateway == null ? null
                        : probeThread(net, "http://" + gateway + ":9001" + PATH, found), // Starlink router, gRPC-web
        };
        for (Thread t : ts) if (t != null) t.start();
        for (Thread t : ts) if (t != null) try { t.join(4000); } catch (InterruptedException ignored) {}
        synchronized (found) { if (found[0]) return true; }
        return portOpen(net, DISH, 9200, 2000);   // dish native gRPC (HTTP/2) port
    }

    private static Thread probeThread(final Network net, final String url, final boolean[] found) {
        Thread t = new Thread(new Runnable() { public void run() {
            if (askDevice(net, url)) synchronized (found) { found[0] = true; }
        } }, "wifidot-starlink");
        t.setDaemon(true);
        return t;
    }

    /** POST get_status as gRPC-web; true only for a real gRPC response from the SpaceX service. */
    static boolean askDevice(Network net, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) net.openConnection(new URL(url));
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
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
            boolean grpcHeaders = (type != null && type.toLowerCase().startsWith("application/grpc"))
                    || c.getHeaderField("grpc-status") != null;
            if (code != 200 || !grpcHeaders) return false;
            // The body must be gRPC-web frames (first byte 0x00 = message, 0x80 = trailers).
            InputStream in = c.getInputStream();
            int first = in.read();
            in.close();
            return first == 0x00 || first == 0x80 || c.getHeaderField("grpc-status") != null;
        } catch (Throwable e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean portOpen(Network net, String host, int port, int timeout) {
        Socket s = null;
        try {
            s = net.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }
}

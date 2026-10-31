package com.wifidot.widget;

import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Connection quality: latency, jitter and packet loss, combined into a score (0–100) and a
 * five-point rating (1 Bad … 5 Excellent).
 *
 * Measurement: bursts of tiny UDP packets (DNS queries to a public resolver) sent over the
 * Wi-Fi (or, with the hotspot on, the shared) network. UDP shows real packet loss — HTTP/TCP
 * would hide it by retransmitting. Results are pooled over a rolling window of recent packets.
 */
final class Quality {

    /** One measured window. */
    static final class Result {
        final int sent, received;
        final double latencyMs, jitterMs, lossPct;
        final int score, level;   // 0–100 and 1–5; level 0 = could not measure
        final String target;
        Result(int sent, int received, double lat, double jit, double loss, int score, int level, String target) {
            this.sent = sent; this.received = received; latencyMs = lat; jitterMs = jit; lossPct = loss;
            this.score = score; this.level = level; this.target = target;
        }
    }

    static final String[] LABELS = {"Unknown", "Very bad", "Bad", "Fair", "Good", "Excellent"};
    static final int[] COLORS = {0xFF9E9E9E, 0xFFE53935, 0xFFFB8C00, 0xFFF9C80E, 0xFF7CB342, 0xFF2ECC40};

    private static final String[] RESOLVERS = {"1.1.1.1", "8.8.8.8"};
    private static final int BURST = 15;              // packets per measurement round
    private static final int SPACING_MS = 100;        // between packets
    private static final int TIMEOUT_MS = 800;        // a reply later than this counts as lost
    private static final int WINDOW = 100;            // packets pooled for the statistics (~7 rounds)

    /** Rolling window: RTT in ms, or -1 for a lost packet. Oldest first. */
    private final List<Double> window = new ArrayList<Double>();
    private int resolver = 0;
    private final Random rnd = new Random();

    /** One round of BURST packets over the given network; returns the updated window result. */
    synchronized Result measure(Network net) {
        if (net == null) return new Result(0, 0, 0, 0, 0, 0, 0, "");
        List<Double> round = burst(net, RESOLVERS[resolver]);
        if (countReceived(round) == 0) {
            // Nothing came back: maybe this resolver/UDP 53 is blocked here. Try the other one.
            int other = (resolver + 1) % RESOLVERS.length;
            List<Double> retry = burst(net, RESOLVERS[other]);
            if (countReceived(retry) > 0) { resolver = other; round = retry; window.clear(); }
        }
        window.addAll(round);
        while (window.size() > WINDOW) window.remove(0);
        return evaluate(window, RESOLVERS[resolver]);
    }

    synchronized void reset() { window.clear(); }

    private List<Double> burst(Network net, String host) {
        List<Double> out = new ArrayList<Double>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            net.bindSocket(sock);
            InetAddress dst = InetAddress.getByName(host);   // numeric: no DNS lookup
            sock.setSoTimeout(TIMEOUT_MS);
            byte[] buf = new byte[512];
            for (int i = -1; i < BURST; i++) {   // i = -1: untimed warm-up (Wi-Fi power save may drop it)
                int id = rnd.nextInt(0xFFFF);
                byte[] q = query(id);
                long t0 = System.nanoTime();
                double rtt = -1;
                try {
                    sock.send(new DatagramPacket(q, q.length, dst, 53));
                    long deadline = t0 + TIMEOUT_MS * 1_000_000L;
                    while (true) {
                        long left = (deadline - System.nanoTime()) / 1_000_000L;
                        if (left <= 0) break;
                        sock.setSoTimeout((int) Math.max(1, left));
                        DatagramPacket r = new DatagramPacket(buf, buf.length);
                        sock.receive(r);
                        boolean ours = r.getLength() >= 12 && dst.equals(r.getAddress())
                                && ((buf[0] & 0xFF) << 8 | (buf[1] & 0xFF)) == id
                                && (buf[2] & 0x80) != 0;   // QR bit: a response
                        if (ours) {
                            rtt = (System.nanoTime() - t0) / 1e6;
                            break;
                        } // else: a late reply to an earlier packet — keep waiting for ours
                    }
                } catch (java.net.SocketTimeoutException lost) {
                    // rtt stays -1
                } catch (Throwable e) {
                    rtt = -1;
                }
                if (i >= 0) out.add(rtt);
                long spent = (System.nanoTime() - t0) / 1_000_000L;
                if (spent < SPACING_MS) try { Thread.sleep(SPACING_MS - spent); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable e) {
            // socket could not be created/bound: report the round as lost
            while (out.size() < BURST) out.add(-1.0);
        } finally {
            if (sock != null) sock.close();
        }
        return out;
    }

    /** Minimal DNS query: "example.com" A IN, recursion desired. */
    private static byte[] query(int id) {
        byte[] name = {7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0};
        byte[] q = new byte[12 + name.length + 4];
        q[0] = (byte) (id >> 8); q[1] = (byte) id;
        q[2] = 0x01;              // RD
        q[5] = 0x01;              // QDCOUNT = 1
        System.arraycopy(name, 0, q, 12, name.length);
        int o = 12 + name.length;
        q[o + 1] = 0x01;          // QTYPE A
        q[o + 3] = 0x01;          // QCLASS IN
        return q;
    }

    private static int countReceived(List<Double> l) {
        int n = 0;
        for (double d : l) if (d >= 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ the model

    /**
     * Latency = median RTT; jitter = mean difference between consecutive replies (RFC 3550 style);
     * loss = lost / sent. Rating:
     *  - from latency: ≤30 ms 5, ≤60 ms 4, ≤100 ms 3, ≤200 ms 2, else 1;
     *  - jitter ≥30 ms costs 1 point, ≥80 ms 2 points (a low ping with jumping jitter means lag);
     *  - any loss above 1% (at least 2 lost packets) caps the rating at "Bad" (2), or
     *    "Very bad" (1) from 10% loss;
     *  - the 0–100 score is continuous and kept inside the band of the rating.
     */
    static Result evaluate(List<Double> samples, String target) {
        int sent = samples.size();
        List<Double> ok = new ArrayList<Double>();
        for (double d : samples) if (d >= 0) ok.add(d);
        int received = ok.size();
        if (sent == 0) return new Result(0, 0, 0, 0, 0, 0, 0, target);
        double loss = 100.0 * (sent - received) / sent;
        if (received == 0) return new Result(sent, 0, 0, 0, 100, 0, 1, target);

        double[] sorted = new double[received];
        for (int i = 0; i < received; i++) sorted[i] = ok.get(i);
        Arrays.sort(sorted);
        double latency = received % 2 == 1 ? sorted[received / 2]
                : (sorted[received / 2 - 1] + sorted[received / 2]) / 2;
        double jitter = 0;
        if (received > 1) {
            for (int i = 1; i < received; i++) jitter += Math.abs(ok.get(i) - ok.get(i - 1));
            jitter /= (received - 1);
        }
        // One lost packet among few is noise (public resolvers drop the odd query): the loss rule
        // needs at least two lost packets; then any loss above 1% makes the rating "Bad".
        int[] r = rate(latency, jitter, sent - received >= 2 ? loss : 0);
        return new Result(sent, received, latency, jitter, loss, r[0], r[1], target);
    }

    /** {score 0–100, level 1–5} from latency, jitter (ms) and loss (%). */
    static int[] rate(double latency, double jitter, double loss) {
        int level = latency <= 30 ? 5 : latency <= 60 ? 4 : latency <= 100 ? 3 : latency <= 200 ? 2 : 1;
        if (jitter >= 80) level -= 2; else if (jitter >= 30) level -= 1;
        if (loss > 1) level = Math.min(level, loss >= 10 ? 1 : 2);
        level = Math.max(1, Math.min(5, level));

        // Continuous score: latency 20→300 ms maps 100→0, minus jitter and loss penalties.
        double score = 100 - Math.max(0, latency - 20) * (100.0 / 280);
        score -= Math.min(40, jitter * 0.4);
        score -= Math.min(100, loss * 8);
        int[] lo = {0, 0, 30, 50, 70, 85}, hi = {0, 29, 49, 69, 84, 100};
        int sc = (int) Math.round(Math.max(lo[level], Math.min(hi[level], score)));
        return new int[]{sc, level};
    }
}

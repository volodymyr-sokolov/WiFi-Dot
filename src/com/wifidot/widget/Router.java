package com.wifidot.widget;

import android.net.Network;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the router brand.
 *
 * 1. Local names (reliable): many routers answer their own name on the local network, e.g.
 *    fritz.box → FRITZ!Box, router.asus.com → ASUS. If such a name resolves to the gateway, that's
 *    the brand. A made-up name is resolved too: if it also points to the gateway, the router
 *    answers every name and this method is skipped.
 * 2. The router's web page (fallback): weighted evidence — page title, Server header and login
 *    realm count much more than the page body; only whole words count; the strongest brand wins.
 *    (Taking the first brand mentioned anywhere made a FRITZ!Box page come out as "D-Link".)
 */
final class Router {
    private Router() {}

    /**
     * {name, brand, trusted}. "trusted" names exist only inside the router's own network
     * (fritz.box, speedport.ip, …). The others are real internet domains: some of them publicly
     * point to a private address such as 192.168.0.1 (e.g. tplinkwifi.net), so ANY router at that
     * address would match — those need the router's page not to point to another brand.
     */
    private static final String[][] LOCAL_NAMES = {
            {"fritz.box", "FRITZ!Box", "trusted"},
            {"speedport.ip", "Speedport", "trusted"},
            {"easy.box", "Vodafone EasyBox", "trusted"},
            {"dlinkrouter.local", "D-Link", "trusted"}, {"dlinkrouter", "D-Link", "trusted"},
            {"homerouter.cpe", "Huawei", "trusted"},
            {"myrouter.local", "Linksys", "trusted"},
            {"openwrt.lan", "OpenWrt", "trusted"},
            {"router.asus.com", "ASUS", ""},
            {"tplinkwifi.net", "TP-Link", ""}, {"tplinklogin.net", "TP-Link", ""},
            {"my.keenetic.net", "Keenetic", ""},
            {"routerlogin.net", "Netgear", ""},
            {"miwifi.com", "Xiaomi", ""}, {"router.miwifi.com", "Xiaomi", ""},
            {"tendawifi.com", "Tenda", ""},
            {"mwlogin.net", "Mercusys", ""},
            {"router.huawei.com", "Huawei", ""},
    };

    /** {regex (whole words), brand}. Checked on title/headers (weight 5) and body (weight 1). */
    private static final String[][] SIGNS = {
            {"fritz!?\\s?box|\\bavm\\b|fritz\\.box", "FRITZ!Box"},
            {"\\btp-?link\\b|tplinkwifi", "TP-Link"},
            {"\\bmercusys\\b", "Mercusys"},
            {"\\bkeenetic\\b", "Keenetic"},
            {"\\bzyxel\\b", "Zyxel"},
            {"\\basus(wrt)?\\b", "ASUS"},
            {"\\bmikrotik\\b|\\brouteros\\b", "MikroTik"},
            {"\\bnetgear\\b", "Netgear"},
            {"\\bd-link\\b|\\bdlink\\b", "D-Link"},
            {"\\bhuawei\\b", "Huawei"},
            {"\\bzte\\b", "ZTE"},
            {"\\bmiwifi\\b|\\bxiaomi\\b", "Xiaomi"},
            {"\\btenda\\b", "Tenda"},
            {"\\bubiquiti\\b|\\bunifi\\b", "Ubiquiti"},
            {"\\blinksys\\b", "Linksys"},
            {"\\bopenwrt\\b|\\bluci\\b", "OpenWrt"},
            {"\\bnetis\\b", "Netis"},
            {"\\bcudy\\b", "Cudy"},
            {"\\btotolink\\b", "TOTOLINK"},
            {"\\beero\\b", "eero"},
            {"\\bspeedport\\b", "Speedport"},
            {"\\beasybox\\b", "Vodafone EasyBox"},
            {"\\bsagemcom\\b", "Sagemcom"},
            {"\\btechnicolor\\b", "Technicolor"},
            {"\\barris\\b", "Arris"},
            {"\\bruijie\\b", "Ruijie"},
            {"\\bgoogle wifi\\b|\\bnest wifi\\b", "Google"},
    };

    /** Brand of the router at {@code gateway}, or null. Takes up to ~4 s. */
    static String detect(Network net, String gateway) {
        String[] byName = byLocalName(net, gateway);   // {brand, trusted?}
        if (byName != null && "trusted".equals(byName[1])) return byName[0];
        String[] page = fetch(net, "http://" + gateway + "/");
        String pageBrand = page == null ? null : score(page[0], page[1]);
        int pageStrength = page == null || pageBrand == null ? 0 : strength(pageBrand, page[0], page[1]);
        if (byName != null) {
            // A public-domain name matched: believe it unless the page clearly says otherwise.
            return pageBrand != null && !pageBrand.equals(byName[0]) && pageStrength >= 5 ? pageBrand : byName[0];
        }
        return pageBrand;
    }

    /** Score of one brand (to judge how clear the page's evidence is). */
    private static int strength(String brand, String strong, String body) {
        int sc = 0;
        for (String[] s : SIGNS) if (s[1].equals(brand)) {
            Pattern pat = Pattern.compile(s[0]);
            sc += 5 * Math.min(3, count(pat, strong)) + Math.min(10, count(pat, body));
        }
        return sc;
    }

    // ------------------------------------------------------------------ 1. local names

    static String[] byLocalName(final Network net, final String gateway) {
        if (gateway == null) return null;
        final int[] found = {-1};
        final boolean[] wildcard = {false};
        Thread[] ts = new Thread[LOCAL_NAMES.length + 1];
        for (int i = 0; i < LOCAL_NAMES.length; i++) {
            final String host = LOCAL_NAMES[i][0];
            final int order = i;
            ts[i] = new Thread(new Runnable() { public void run() {
                if (resolvesTo(net, host, gateway)) synchronized (found) {
                    // keep the earliest (most trusted) list entry if several match
                    if (found[0] < 0 || found[0] > order) found[0] = order;
                }
            } });
        }
        final String probe = "wifidot-" + Integer.toHexString(new Random().nextInt()) + ".lan";
        ts[LOCAL_NAMES.length] = new Thread(new Runnable() { public void run() {
            if (resolvesTo(net, probe, gateway)) synchronized (found) { wildcard[0] = true; }
        } });
        for (Thread t : ts) { t.setDaemon(true); t.start(); }
        long deadline = System.currentTimeMillis() + 2000;
        for (Thread t : ts) {
            long left = deadline - System.currentTimeMillis();
            if (left > 0) try { t.join(left); } catch (InterruptedException ignored) {}
        }
        synchronized (found) {
            if (wildcard[0] || found[0] < 0) return null;
            return new String[]{LOCAL_NAMES[found[0]][1], LOCAL_NAMES[found[0]][2]};
        }
    }

    private static boolean resolvesTo(Network net, String host, String ip) {
        try {
            for (InetAddress a : net.getAllByName(host)) if (ip.equals(a.getHostAddress())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ------------------------------------------------------------------ 2. the web page

    static String byPage(Network net, String url) {
        String[] p = fetch(net, url);   // {strong: title + headers, body}
        if (p == null) return null;
        return score(p[0], p[1]);
    }

    /** Highest weighted score wins: title/headers ×5 per match, body ×1 per match (capped). */
    static String score(String strong, String body) {
        Map<String, Integer> scores = new HashMap<String, Integer>();
        String best = null;
        int bestScore = 0;
        for (String[] s : SIGNS) {
            Pattern pat = Pattern.compile(s[0]);
            int sc = 5 * Math.min(3, count(pat, strong)) + Math.min(10, count(pat, body));
            if (sc == 0) continue;
            Integer prev = scores.get(s[1]);
            sc += prev == null ? 0 : prev;
            scores.put(s[1], sc);
            if (sc > bestScore) { bestScore = sc; best = s[1]; }
        }
        return best;
    }

    private static int count(Pattern p, String s) {
        if (s == null || s.isEmpty()) return 0;
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** {title + Server + realm + redirect target, body}, lower-cased; null on failure. */
    private static String[] fetch(Network net, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) net.openConnection(new URL(url));
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            StringBuilder strong = new StringBuilder();
            for (String h : new String[]{"Server", "WWW-Authenticate", "Location"}) {
                String v = c.getHeaderField(h);
                if (v != null) strong.append(v).append('\n');
            }
            strong.append(c.getURL().getHost()).append('\n');   // after redirects, e.g. fritz.box
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                byte[] buf = new byte[32768];
                int total = 0, r;
                while (total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0) total += r;
                in.close();
                body = new String(buf, 0, total, "UTF-8").toLowerCase(Locale.ROOT);
            }
            Matcher t = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL).matcher(body);
            if (t.find()) strong.append(t.group(1));
            return new String[]{strong.toString().toLowerCase(Locale.ROOT), body};
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

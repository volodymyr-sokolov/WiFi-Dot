package com.wifidot.widget;

import java.util.HashMap;
import java.util.Map;

/**
 * Approximate coordinates of M-Lab sites. M-Lab names its sites after the nearest airport
 * (IATA code + number, e.g. "fra05" = Frankfurt), so the airport position is a good estimate
 * (well within a few km of the metro). Unknown sites simply show no distance.
 */
final class Sites {
    private Sites() {}

    private static final Map<String, double[]> AIRPORTS = new HashMap<String, double[]>();

    private static void a(String iata, double lat, double lon) { AIRPORTS.put(iata, new double[]{lat, lon}); }

    static {
        // Europe
        a("ams", 52.31, 4.76); a("arn", 59.65, 17.92); a("ath", 37.94, 23.94); a("bcn", 41.30, 2.08);
        a("beg", 44.82, 20.29); a("ber", 52.37, 13.50); a("bru", 50.90, 4.48); a("bts", 48.17, 17.21);
        a("bud", 47.44, 19.26); a("cdg", 49.01, 2.55); a("cph", 55.62, 12.65); a("dub", 53.43, -6.25);
        a("dus", 51.29, 6.77); a("fco", 41.80, 12.25); a("fra", 50.04, 8.56); a("gva", 46.24, 6.11);
        a("ham", 53.63, 9.99); a("hel", 60.32, 24.96); a("ist", 41.26, 28.74); a("kbp", 50.35, 30.89);
        a("lhr", 51.47, -0.45); a("lis", 38.77, -9.13); a("lju", 46.22, 14.46); a("lux", 49.63, 6.21);
        a("mad", 40.47, -3.56); a("man", 53.35, -2.27); a("mil", 45.63, 8.72); a("mxp", 45.63, 8.72);
        a("lin", 45.45, 9.28); a("mrs", 43.44, 5.22); a("muc", 48.35, 11.79); a("osl", 60.19, 11.10);
        a("otp", 44.57, 26.09); a("par", 49.01, 2.55); a("prg", 50.10, 14.26); a("rix", 56.92, 23.97);
        a("sof", 42.70, 23.41); a("str", 48.69, 9.22); a("tll", 59.41, 24.83); a("trn", 45.20, 7.65);
        a("vie", 48.11, 16.57); a("vno", 54.63, 25.29); a("waw", 52.17, 20.97); a("zag", 45.74, 16.07);
        a("zrh", 47.46, 8.55); a("lon", 51.47, -0.45); a("mow", 55.97, 37.41); a("svo", 55.97, 37.41);
        a("led", 59.80, 30.26); a("kiv", 46.93, 28.93); a("tgd", 42.36, 19.25); a("skp", 41.96, 21.62);
        a("tia", 41.41, 19.72); a("lca", 34.88, 33.62); a("mla", 35.86, 14.48); a("kef", 63.99, -22.62);
        a("edi", 55.95, -3.37); a("ory", 48.72, 2.38); a("lys", 45.73, 5.08); a("nce", 43.66, 7.22);
        a("ntz", 43.66, 7.22); a("got", 57.67, 12.29); a("aar", 56.30, 10.62); a("cgn", 50.87, 7.14);
        a("hhn", 49.95, 7.26); a("nue", 49.50, 11.08); a("lej", 51.42, 12.24); a("krk", 50.08, 19.78);
        a("wro", 51.10, 16.89); a("gdn", 54.38, 18.47); a("brq", 49.15, 16.69); 
       a("ods", 46.43, 30.68); a("lwo", 49.81, 23.96); a("hrk", 49.92, 36.29); a("cwl", 51.40, -3.34);
        // Americas
        a("atl", 33.64, -84.43); a("bog", 4.70, -74.15); a("bos", 42.36, -71.01); a("chs", 32.90, -80.04);
        a("clt", 35.21, -80.94); a("cvg", 39.05, -84.67); a("den", 39.86, -104.67); a("dfw", 32.90, -97.04);
        a("eze", -34.82, -58.54); a("for", -3.78, -38.53); a("fln", -27.67, -48.55); a("gru", -23.43, -46.47);
        a("gig", -22.81, -43.25); a("hnl", 21.32, -157.92); a("iad", 38.95, -77.46); a("iah", 29.98, -95.34);
        a("lax", 33.94, -118.41); a("las", 36.08, -115.15); a("lga", 40.78, -73.87); a("jfk", 40.64, -73.78);
        a("lim", -12.02, -77.11); a("mci", 39.30, -94.71); a("mex", 19.44, -99.07); a("mia", 25.79, -80.29);
        a("msp", 44.88, -93.22); a("nuq", 37.42, -122.05); a("sjc", 37.36, -121.93); a("sfo", 37.62, -122.38);
        a("ord", 41.98, -87.90); a("pdx", 45.59, -122.60); a("phx", 33.43, -112.01); a("pty", 9.07, -79.38);
        a("scl", -33.39, -70.79); a("sea", 47.45, -122.31); a("slc", 40.79, -111.98); a("stl", 38.75, -90.37);
        a("tpa", 27.98, -82.53); a("uio", -0.13, -78.36); a("yqm", 46.11, -64.68); a("yul", 45.47, -73.74);
        a("yvr", 49.19, -123.18); a("yyc", 51.13, -114.01); a("yyz", 43.68, -79.63); a("ywg", 49.91, -97.24);
        a("bna", 36.12, -86.68); a("aus", 30.19, -97.67); a("sju", 18.44, -66.00); a("gdl", 20.52, -103.31);
        a("mty", 25.78, -100.11); a("cmh", 39.99, -82.89); a("det", 42.21, -83.35); a("dtw", 42.21, -83.35);
        a("pit", 40.49, -80.23); a("phl", 39.87, -75.24); a("ric", 37.51, -77.32); a("sat", 29.53, -98.47);
        a("san", 32.73, -117.19); a("smf", 38.70, -121.59); a("okc", 35.39, -97.60); a("oma", 41.30, -95.89);
        a("abq", 35.04, -106.61); a("bwi", 39.18, -76.67); a("ewr", 40.69, -74.17); a("mia", 25.79, -80.29);
        // Asia / Pacific / Africa / Middle East
        a("akl", -37.01, 174.79); a("bkk", 13.69, 100.75); a("bom", 19.09, 72.87); a("bne", -27.38, 153.12);
        a("cgk", -6.13, 106.66); a("del", 28.56, 77.10); a("hkg", 22.31, 113.91); a("hnd", 35.55, 139.78);
        a("nrt", 35.77, 140.39); a("kix", 34.43, 135.24); a("icn", 37.46, 126.44); a("kul", 2.75, 101.71);
        a("maa", 12.99, 80.17); a("mel", -37.67, 144.84); a("mnl", 14.51, 121.02); a("per", -31.94, 115.97);
        a("sin", 1.36, 103.99); a("syd", -33.94, 151.18); a("tpe", 25.08, 121.23); a("blr", 13.20, 77.71);
        a("hyd", 17.24, 78.43); a("ccu", 22.65, 88.45); a("sgn", 10.82, 106.65); a("han", 21.22, 105.81);
        a("dxb", 25.25, 55.36); a("doh", 25.27, 51.61); a("tlv", 32.01, 34.89); a("jnb", -26.14, 28.24);
        a("cpt", -33.97, 18.60); a("los", 6.58, 3.32); a("nbo", -1.32, 36.93); a("acc", 5.61, -0.17);
        a("cai", 30.12, 31.41); a("cmn", 33.37, -7.59); a("tun", 36.85, 10.23); a("alg", 36.69, 3.22);
        a("kgl", -1.97, 30.14); a("dar", -6.88, 39.20); a("ebb", 0.04, 32.44); a("dkr", 14.74, -17.49);
        a("mru", -20.43, 57.68); a("khi", 24.91, 67.16); a("isb", 33.55, 72.83); a("dac", 23.84, 90.40);
        a("cmb", 7.18, 79.88); a("ktm", 27.70, 85.36); a("ala", 43.35, 77.04); a("tas", 41.26, 69.28);
        a("gyd", 40.47, 50.05); a("tbs", 41.67, 44.95); a("evn", 40.15, 44.40); a("wlg", -41.33, 174.81);
        a("chc", -43.49, 172.53); a("pom", -9.44, 147.22); a("nan", -17.76, 177.44); a("gum", 13.48, 144.80);
    }

    /** [lat, lon] for a site like "fra05" or a machine like "mlab1-fra05.mlab-oti…", or null. */
    static double[] coords(String siteOrMachine) {
        if (siteOrMachine == null) return null;
        String first = siteOrMachine.toLowerCase(java.util.Locale.ROOT).split("\\.")[0];
        // "mlab1-fra05" → "fra05"; other formats: the first part that starts with a known airport code
        for (String part : first.split("-")) {
            if (part.length() >= 3 && !part.startsWith("mlab")) {
                double[] c = AIRPORTS.get(part.substring(0, 3));
                if (c != null) return c;
            }
        }
        return null;
    }

    /** Site code ("fra05") from a machine name ("mlab1-fra05.mlab-oti.measurement-lab.org"). */
    static String site(String machine) {
        if (machine == null) return "";
        String s = machine.toLowerCase(java.util.Locale.ROOT).split("\\.")[0];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^mlab\\d+-([a-z]{3}\\d+)$").matcher(s);
        if (m.find()) return m.group(1);   // classic M-Lab machine: mlab1-fra05
        return s;                         // other formats: keep the whole first label
    }

    /** Great-circle distance in km. */
    static double km(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}

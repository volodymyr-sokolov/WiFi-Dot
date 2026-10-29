package com.wifidot.widget;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact hotspot device count, read from the system's own "Hotspot is active · 1 device connected"
 * notification. Needs Notification access (granted by the user in system settings).
 * Only the hotspot notification is looked at; nothing is stored except the number.
 */
public class HotspotNotifications extends NotificationListenerService {

    private static final String[] HOTSPOT_WORDS = {
            "hotspot", "hot spot", "tether", "access point",
            "точка доступа", "точка доступу", "режим модема", "модем", "хотспот", "热点"};
    private static final String[] ZERO_WORDS = {
            "no device", "no connected", "not connected", "нет устройств", "нет подключ",
            "немає пристро", "немає підключ", "жодного"};
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    static volatile boolean connected = false;

    static ComponentName component(Context ctx) { return new ComponentName(ctx, HotspotNotifications.class); }

    /** Is Notification access granted to WiFi Dot? */
    static boolean granted(Context ctx) {
        if (connected) return true;
        try { // Android 8.1+: the official check
            Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Object r = nm.getClass().getMethod("isNotificationListenerAccessGranted", ComponentName.class)
                    .invoke(nm, component(ctx));
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        ComponentName c = component(ctx);
        return s.contains(c.flattenToString()) || s.contains(c.flattenToShortString());
    }

    /** Ask the system to (re)connect the listener after access was just granted. */
    static void rebind(Context ctx) {
        if (connected || !granted(ctx)) return;
        try {
            NotificationListenerService.class.getMethod("requestRebind", ComponentName.class)
                    .invoke(null, component(ctx));
        } catch (Throwable ignored) {}
    }

    /** Device count from the system notification, or -1 if unknown. */
    static int count(Context ctx) {
        if (!granted(ctx)) return -1;
        return ctx.getSharedPreferences(Checker.PREFS, 0).getInt("hs_sys_count", -1);
    }

    @Override public void onListenerConnected() { connected = true; scan(); }
    // API 24+: called by the system; no @Override because we compile against API 23
    public void onListenerDisconnected() { connected = false; }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { if (!isOwn(sbn)) scan(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { if (!isOwn(sbn)) scan(); }

    private boolean isOwn(StatusBarNotification sbn) { return getPackageName().equals(sbn.getPackageName()); }

    private void scan() {
        int found = -1;
        String source = "";
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification sbn : all) {
                if (isOwn(sbn)) continue;
                int n = parse(sbn.getNotification());
                if (n >= 0) { found = Math.max(found, n); source = sbn.getPackageName(); }
            }
        } catch (Throwable ignored) {}
        SharedPreferences p = getSharedPreferences(Checker.PREFS, 0);
        int old = p.getInt("hs_sys_count", -1);
        p.edit().putInt("hs_sys_count", found).putString("hs_sys_source", source).apply();
        if (old != found) Checker.runAsync(this, null); // update widget/tile/icon right away
    }

    /** -1 if this isn't a hotspot notification with a device count; otherwise the count. */
    static int parse(Notification n) {
        if (n == null || n.extras == null) return -1;
        Bundle e = n.extras;
        String text = (str(e.get(Notification.EXTRA_TITLE)) + " \n " + str(e.get(Notification.EXTRA_TEXT)) + " \n "
                + str(e.get(Notification.EXTRA_SUB_TEXT)) + " \n " + str(e.get(Notification.EXTRA_BIG_TEXT)))
                .toLowerCase(Locale.ROOT);
        boolean hotspot = false;
        for (String w : HOTSPOT_WORDS) if (text.contains(w)) { hotspot = true; break; }
        if (!hotspot) return -1;
        for (String w : ZERO_WORDS) if (text.contains(w)) return 0;
        // the number next to "device(s)/устройств/пристр/connected/подключ"
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String after = text.substring(m.end(), Math.min(text.length(), m.end() + 24));
            String before = text.substring(Math.max(0, m.start() - 24), m.start());
            if (after.matches("(?s)\\s*(device|devices|client|clients|connected|устрой|подключ|пристр|підключ|користув|user).*")
                    || before.matches("(?s).*(device|devices|clients|connected|устройств|подключено|пристроїв|підключено|users)\\s*:?\\s*")) {
                try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
            }
        }
        return -1;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}

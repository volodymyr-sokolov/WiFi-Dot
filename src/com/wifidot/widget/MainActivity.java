package com.wifidot.widget;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/** Main screen: live status, connection speed, where the status is shown, settings, icon legend. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String POST_NOTIF = "android.permission.POST_NOTIFICATIONS";
    private static final String LOCATION = "android.permission.ACCESS_FINE_LOCATION";

    private int cBg, cCard, cText, cSub, cIcon, cRipple, cPill, cLine;
    private float dp;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private ImageView statusIcon;
    private TextView statusTitle, statusSub, statusTime;
    private TextView linkValue, latencyValue, downloadValue, testLabel;
    private Switch swIcon, swAlerts, swWidget, swTile, swStarlink;
    private TextView starlinkSub, locationSub, batterySub, exactSub;
    private View addWidget, addTile, diagSection;
    private LinearLayout diagCard, diagActions;
    private TextView diagText;
    private boolean binding;
    private SpeedTest test;

    private final Runnable ticker = new Runnable() {
        public void run() { updateTime(); updateLink(); handler.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Checker.PREFS, 0);
        dp = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            cBg = 0xFF101418; cCard = 0xFF1C2228; cText = 0xFFECEFF1; cSub = 0xFF9AA4AE;
            cIcon = 0xFFB0BAC4; cRipple = 0x33FFFFFF; cPill = 0xFF2A3239; cLine = 0x1FFFFFFF;
        } else {
            cBg = 0xFFF2F4F7; cCard = 0xFFFFFFFF; cText = 0xFF14191E; cSub = 0xFF66707A;
            cIcon = 0xFF55606B; cRipple = 0x22000000; cPill = 0xFFEDF0F3; cLine = 0x14000000;
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(cBg));
        setContentView(buildUi());

        askNotifPermission();
        Checker.registerNetworkCallback(this);
        RefreshJob.schedule(this);
        MonitorService.start(this);
        Checker.runAsync(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (test != null) test.cancel();
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        handler.post(new Runnable() { public void run() { refresh(); } });
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout col = vbox();
        col.setPadding(px(16), px(20), px(16), px(28));

        col.addView(text("WiFi Dot", 28, cText, true));
        TextView tagline = text("Wi-Fi internet monitor", 14, cSub, false);
        tagline.setPadding(0, px(2), 0, px(16));
        col.addView(tagline);

        // ---- Status
        LinearLayout status = card(true);
        LinearLayout top = hbox();
        top.setGravity(Gravity.CENTER_VERTICAL);
        statusIcon = new ImageView(this);
        top.addView(statusIcon, new LinearLayout.LayoutParams(px(56), px(56)));
        LinearLayout texts = vbox();
        texts.setPadding(px(16), 0, 0, 0);
        statusTitle = text("Checking…", 20, cText, true);
        statusSub = text("", 14, cSub, false);
        statusTime = text("", 12, cSub, false);
        texts.addView(statusTitle);
        texts.addView(statusSub);
        texts.addView(statusTime);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        status.addView(top);

        LinearLayout pills = hbox();
        pills.setPadding(0, px(16), 0, 0);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = px(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
        lp2.leftMargin = px(6);
        pills.addView(pill(R.drawable.ic_ui_refresh, "Check now", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putLong("detected2", 0).apply();
                statusTitle.setText("Checking…");
                Checker.runAsync(MainActivity.this, null);
            }
        }), lp1);
        pills.addView(pill(R.drawable.ic_ui_open, "Router page", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, OpenActivity.class)); }
        }), lp2);
        status.addView(pills);
        col.addView(status);

        // ---- Hotspot diagnostics (only while the hotspot is on)
        diagSection = section("Hotspot diagnostics");
        col.addView(diagSection);
        diagCard = card(true);
        diagText = text("", 12, cSub, false);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextIsSelectable(true);
        diagCard.addView(diagText);
        TextView diagHint = text("Long-press to select and copy. Send this if the device count looks wrong.", 12, cSub, false);
        diagHint.setPadding(0, px(8), 0, 0);
        diagCard.addView(diagHint);
        diagActions = hbox();
        diagActions.setPadding(0, px(12), 0, 0);
        LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(0, -2, 1);
        d1.rightMargin = px(6);
        LinearLayout.LayoutParams d2 = new LinearLayout.LayoutParams(0, -2, 1);
        d2.leftMargin = px(6);
        diagActions.addView(pill(R.drawable.ic_hs_1, "Exact count", new View.OnClickListener() {
            public void onClick(View v) { openNotificationAccess(); }
        }), d1);
        diagActions.addView(pill(R.drawable.ic_ui_info, "App info", new View.OnClickListener() {
            public void onClick(View v) { openAppSettings(); }
        }), d2);
        diagCard.addView(diagActions);
        col.addView(diagCard);

        // ---- Connection speed
        col.addView(section("Connection speed"));
        LinearLayout speed = card(false);
        linkValue = text("—", 13, cSub, false);
        latencyValue = text("—", 13, cSub, false);
        downloadValue = text("Not tested yet", 13, cSub, false);
        speed.addView(row(R.drawable.ic_ui_link, "Wi-Fi link", linkValue, null, null));
        speed.addView(divider());
        speed.addView(row(R.drawable.ic_ui_timer, "Latency", latencyValue, null, null));
        speed.addView(divider());
        View testPill = smallPill("Test", new View.OnClickListener() {
            public void onClick(View v) { toggleSpeedTest(); }
        });
        testLabel = (TextView) ((ViewGroup) testPill).getChildAt(0);
        speed.addView(row(R.drawable.ic_ui_download, "Download", downloadValue, testPill, null));
        col.addView(speed);

        // ---- Where the status is shown
        col.addView(section("Show status in"));
        LinearLayout show = card(false);

        swIcon = new Switch(this);
        show.addView(row(R.drawable.ic_stat_ok, "Status bar icon",
                text("Next to the clock · checks every 10 s", 13, cSub, false), swIcon, null));
        swIcon.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setStatusIcon(MainActivity.this, on);
                if (on) askNotifPermission();
                StatusNotifier.update(MainActivity.this);
                Checker.runAsync(MainActivity.this, null);
            }
        });

        swAlerts = new Switch(this);
        show.addView(divider());
        show.addView(row(R.drawable.ic_ui_bell, "Pop-up alerts",
                text("Only when the internet goes down or comes back", 13, cSub, false), swAlerts, null));
        swAlerts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setAlerts(MainActivity.this, on);
                if (on) askNotifPermission();
            }
        });

        swWidget = new Switch(this);
        addWidget = smallPill("Add", new View.OnClickListener() {
            public void onClick(View v) { requestPinWidget(); }
        });
        show.addView(divider());
        show.addView(row(R.drawable.ic_ui_widgets, "Home screen widget",
                text("Long-press the home screen → Widgets → WiFi Dot", 13, cSub, false),
                pair(addWidget, swWidget), null));
        swWidget.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setWidget(MainActivity.this, on);
                if (on) { requestPinWidget(); Checker.render(MainActivity.this); }
                refresh();
            }
        });

        swTile = new Switch(this);
        addTile = smallPill("Add", new View.OnClickListener() {
            public void onClick(View v) { requestAddTile(); }
        });
        show.addView(divider());
        show.addView(row(R.drawable.ic_ui_toggle, "Quick Settings tile",
                text("Pull down the shade → ✎ Edit → drag “Wi-Fi Internet”", 13, cSub, false),
                pair(addTile, swTile), null));
        swTile.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setTile(MainActivity.this, on);
                if (on) requestAddTile();
                refresh();
            }
        });
        col.addView(show);

        // ---- Settings
        col.addView(section("Settings"));
        LinearLayout settings = card(false);
        swStarlink = new Switch(this);
        starlinkSub = text("", 13, cSub, false);
        settings.addView(row(R.drawable.ic_ui_router, "This network is Starlink", starlinkSub, swStarlink, null));
        swStarlink.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                String gw = prefs.getString("gw", "");
                if (gw.length() == 0) return;
                Set<String> set = new HashSet<String>(prefs.getStringSet("starlink_gws", new HashSet<String>()));
                if (on) set.add(gw); else set.remove(gw);
                prefs.edit().putStringSet("starlink_gws", set).putLong("detected2", 0).apply();
                Checker.runAsync(MainActivity.this, null);
            }
        });

        locationSub = text("", 13, cSub, false);
        settings.addView(divider());
        settings.addView(row(R.drawable.ic_ui_location, "Detect Starlink by network name", locationSub,
                chevron(), new View.OnClickListener() {
                    public void onClick(View v) {
                        if (checkSelfPermission(LOCATION) != 0) requestPermissions(new String[]{LOCATION}, 2);
                        else openAppSettings();
                    }
                }));

        exactSub = text("", 13, cSub, false);
        settings.addView(divider());
        settings.addView(row(R.drawable.ic_hs_1, "Exact hotspot device count", exactSub, chevron(),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        openNotificationAccess();
                    }
                }));

        batterySub = text("", 13, cSub, false);
        settings.addView(divider());
        settings.addView(row(R.drawable.ic_ui_battery, "Battery optimization", batterySub, chevron(),
                new View.OnClickListener() {
                    public void onClick(View v) { requestBatteryExemption(); }
                }));
        col.addView(settings);

        // ---- Icons
        col.addView(section("Icons"));
        LinearLayout legend = card(false);
        legend.addView(legendRow(R.drawable.ic_stat_ok, Checker.GREEN, "Internet is available"));
        legend.addView(legendRow(R.drawable.ic_stat_bad, Checker.RED, "Wi-Fi connected, no internet"));
        legend.addView(legendRow(R.drawable.ic_stat_off, Checker.GREY, "Wi-Fi is off (widget only)"));
        legend.addView(legendRow(R.drawable.ic_hs_2, Checker.BLUE, "Hotspot on · number of connected devices"));
        col.addView(legend);

        TextView foot = text("Tap the widget for Details or the router page. The status bar icon and the "
                + "tile open the router page — Starlink: 192.168.100.1 — or the hotspot settings. "
                + "The device count is best effort: Android doesn't give apps the hotspot client list.", 12, cSub, false);
        foot.setPadding(px(6), px(14), px(6), 0);
        col.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        sv.addView(col);
        return sv;
    }

    private void refresh() {
        String state = prefs.getString("state", "");
        String label = prefs.getString("label", "");
        String url = prefs.getString("url", "");
        String gw = prefs.getString("gw", "");

        statusIcon.setImageResource(Checker.iconFor(prefs));
        statusIcon.setColorFilter(Checker.colorFor(state));
        boolean hs = "hotspot".equals(state);
        boolean hsOnline = hs && "online".equals(prefs.getString("hs_net", ""));
        if (hs) statusTitle.setText("Hotspot on");
        else if ("online".equals(state)) statusTitle.setText("Internet OK");
        else if ("offline".equals(state)) statusTitle.setText("No internet");
        else if ("nowifi".equals(state)) statusTitle.setText("Wi-Fi is off");
        else statusTitle.setText("Checking…");
        String host = url.replace("http://", "").replace("/", "");
        if (hs) {
            int n = prefs.getInt("hs_count", -1);
            statusSub.setText((n < 0 ? "Devices: unknown" : Checker.devicesText(n) + " connected")
                    + (hsOnline ? " · internet OK" : " · no internet"));
        } else statusSub.setText("nowifi".equals(state) ? "Connect to a Wi-Fi network"
                : (host.length() > 0 && !host.equals(label) ? label + " · " + host : label));
        updateTime();

        boolean showDiag = hs;
        diagSection.setVisibility(showDiag ? View.VISIBLE : View.GONE);
        diagCard.setVisibility(showDiag ? View.VISIBLE : View.GONE);
        diagText.setText(prefs.getString("hs_report", "Collecting…"));
        diagActions.setVisibility(HotspotNotifications.granted(this) ? View.GONE : View.VISIBLE);

        long lat = prefs.getLong("latency", -1);
        latencyValue.setText(lat >= 0 ? lat + " ms" : ("online".equals(state) || hsOnline ? "—" : "No internet"));
        if (test == null) {
            float dl = prefs.getFloat("dl_mbps", -1);
            long t = prefs.getLong("dl_time", 0);
            downloadValue.setText(dl < 0 ? "Not tested yet · uses ~30–100 MB"
                    : fmtMbps(dl) + " · " + ago(t));
        }
        updateLink();

        binding = true;
        swIcon.setChecked(Prefs.statusIcon(this));
        swAlerts.setChecked(Prefs.alerts(this));
        swWidget.setChecked(Prefs.widget(this));
        swTile.setChecked(Prefs.tile(this));
        boolean marked = Checker.markedStarlink(this, gw);
        swStarlink.setChecked(marked);
        swStarlink.setEnabled(gw.length() > 0);
        binding = false;
        addWidget.setVisibility(Prefs.widget(this) && canPinWidget() ? View.VISIBLE : View.GONE);
        addTile.setVisibility(Prefs.tile(this) && Build.VERSION.SDK_INT >= 33 ? View.VISIBLE : View.GONE);

        starlinkSub.setText(gw.length() == 0 ? "Connect to Wi-Fi first"
                : "Gateway " + gw + (marked ? " · opens 192.168.100.1" : " · use if not detected"));
        locationSub.setText(checkSelfPermission(LOCATION) == 0
                ? "On · also needs Location turned on" : "Needs location access");
        boolean exact = HotspotNotifications.granted(this);
        exactSub.setText(exact
                ? "On · read from the system hotspot notification ✓"
                : Build.VERSION.SDK_INT >= 33
                    ? "Off · tap, then turn on WiFi Dot. Switch greyed out? App info → ⋮ → Allow restricted settings"
                    : "Off · tap, then turn on WiFi Dot");
        if (exact) HotspotNotifications.rebind(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        batterySub.setText(pm.isIgnoringBatteryOptimizations(getPackageName())
                ? "Off for WiFi Dot ✓" : "Turn off so background checks keep running");
    }

    private void updateTime() {
        long t = prefs.getLong("time", 0);
        statusTime.setText(t == 0 ? "" : "Checked " + ago(t));
    }

    @SuppressWarnings("deprecation")
    private void updateLink() {
        String st = prefs.getString("state", "");
        if ("nowifi".equals(st)) { linkValue.setText("Not connected"); return; }
        if ("hotspot".equals(st) && !Checker.wifiClientConnected(this)) {
            linkValue.setText("Hotspot on · sharing mobile data"); return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() == -1 && wi.getLinkSpeed() <= 0) { linkValue.setText("—"); return; }
            StringBuilder sb = new StringBuilder();
            int rx = intCall(wi, "getRxLinkSpeedMbps"), tx = intCall(wi, "getTxLinkSpeedMbps");
            if (rx > 0 && tx > 0) sb.append("↓ ").append(rx).append(" · ↑ ").append(tx).append(" Mbps");
            else if (wi.getLinkSpeed() > 0) sb.append(wi.getLinkSpeed()).append(" Mbps");
            int f = wi.getFrequency();
            if (f > 0) sb.append(sb.length() > 0 ? " · " : "").append(f >= 5900 ? "6 GHz" : f >= 4900 ? "5 GHz" : "2.4 GHz");
            int rssi = wi.getRssi();
            if (rssi > -127 && rssi < 0) sb.append(" · ").append(rssi).append(" dBm");
            linkValue.setText(sb.length() > 0 ? sb.toString() : "—");
        } catch (Throwable e) {
            linkValue.setText("—");
        }
    }

    private static int intCall(Object o, String m) {
        try { return (Integer) o.getClass().getMethod(m).invoke(o); } catch (Throwable e) { return -1; }
    }

    // ---------------------------------------------------------------- speed test

    private void toggleSpeedTest() {
        if (test != null) { test.cancel(); return; }
        String st = prefs.getString("state", "");
        boolean ok = "online".equals(st) || "hotspot".equals(st) && "online".equals(prefs.getString("hs_net", ""));
        if (!ok) {
            downloadValue.setText("Needs a working internet connection");
            return;
        }
        test = new SpeedTest();
        testLabel.setText("Stop");
        downloadValue.setText("Testing…");
        test.run(Checker.testNetwork(this), new SpeedTest.Listener() {
            public void progress(final double mbps) {
                handler.post(new Runnable() { public void run() {
                    if (test != null) downloadValue.setText("Testing… " + fmtMbps(mbps));
                } });
            }
            public void done(final double mbps, final boolean ok) {
                handler.post(new Runnable() { public void run() {
                    test = null;
                    testLabel.setText("Test");
                    if (ok) prefs.edit().putFloat("dl_mbps", (float) mbps)
                            .putLong("dl_time", System.currentTimeMillis()).apply();
                    else downloadValue.setText("Test failed — try again");
                    if (ok) refresh();
                } });
            }
        });
    }

    private static String fmtMbps(double m) {
        return m >= 100 ? String.format(Locale.US, "%.0f Mbps", m) : String.format(Locale.US, "%.1f Mbps", m);
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    // ---------------------------------------------------------------- add widget / tile

    private boolean canPinWidget() {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(this);
            return (Boolean) m.getClass().getMethod("isRequestPinAppWidgetSupported").invoke(m);
        } catch (Throwable e) { return false; }
    }

    private void requestPinWidget() {
        if (!canPinWidget()) return;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(this);
            m.getClass().getMethod("requestPinAppWidget", ComponentName.class, Bundle.class, PendingIntent.class)
                    .invoke(m, new ComponentName(this, WifiDotWidget.class), null, null);
        } catch (Throwable ignored) {}
    }

    private void requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            Class<?> sbmClass = Class.forName("android.app.StatusBarManager");
            Object sbm = Context.class.getMethod("getSystemService", Class.class).invoke(this, sbmClass);
            Class<?> consumer = Class.forName("java.util.function.Consumer");
            Object cb = Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{consumer}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                }
            });
            Executor ex = new Executor() { public void execute(Runnable r) { handler.post(r); } };
            sbmClass.getMethod("requestAddTileService", ComponentName.class, CharSequence.class, Icon.class,
                    Executor.class, consumer)
                    .invoke(sbm, new ComponentName(this, WifiTileService.class), "Wi-Fi Internet",
                            Icon.createWithResource(this, R.drawable.ic_stat_ok), ex, cb);
        } catch (Throwable ignored) {}
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        Intent i = pm.isIgnoringBatteryOptimizations(getPackageName())
                ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Throwable e) { openAppSettings(); }
    }

    // ---------------------------------------------------------------- view helpers

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private LinearLayout card(boolean padded) {
        LinearLayout c = vbox();
        c.setBackground(round(cCard, 20));
        if (padded) c.setPadding(px(18), px(18), px(18), px(18));
        else c.setPadding(0, px(4), 0, px(4));
        c.setElevation(px(1));
        c.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(4);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 12, cSub, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(px(6), px(20), 0, px(8));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cLine);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, px(0.7f)));
        lp.leftMargin = px(58);
        v.setLayoutParams(lp);
        return v;
    }

    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView i = new ImageView(this);
        i.setImageResource(res);
        i.setColorFilter(tint);
        i.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return i;
    }

    private ImageView chevron() { return icon(R.drawable.ic_ui_chevron, cSub, 22); }

    private View pair(View a, View b) {
        LinearLayout l = hbox();
        l.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(8);
        l.addView(a, lp);
        l.addView(b);
        return l;
    }

    private View row(int iconRes, String title, TextView sub, View end, View.OnClickListener click) {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(18), px(12), px(14), px(12));
        r.addView(icon(iconRes, cIcon, 24));
        LinearLayout t = vbox();
        t.setPadding(px(16), 0, px(8), 0);
        t.addView(text(title, 16, cText, false));
        if (sub != null) t.addView(sub);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (end != null) r.addView(end);
        final Switch sw = end instanceof Switch ? (Switch) end
                : end instanceof ViewGroup && ((ViewGroup) end).getChildCount() == 2
                  && ((ViewGroup) end).getChildAt(1) instanceof Switch ? (Switch) ((ViewGroup) end).getChildAt(1) : null;
        if (sw != null && click == null) {
            click = new View.OnClickListener() { public void onClick(View v) { if (sw.isEnabled()) sw.toggle(); } };
        }
        if (click != null) {
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), null, new ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(click);
        }
        return r;
    }

    private View legendRow(int iconRes, int color, String s) {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(18), px(10), px(14), px(10));
        r.addView(icon(iconRes, color, 24));
        TextView t = text(s, 15, cText, false);
        t.setPadding(px(16), 0, 0, 0);
        r.addView(t);
        return r;
    }

    private View pill(int iconRes, String s, View.OnClickListener click) {
        LinearLayout p = hbox();
        p.setGravity(Gravity.CENTER);
        p.setPadding(px(10), px(11), px(10), px(11));
        p.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), round(cPill, 22), null));
        p.addView(icon(iconRes, cText, 18));
        TextView t = text(s, 14, cText, true);
        t.setPadding(px(8), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        p.addView(t);
        p.setOnClickListener(click);
        return p;
    }

    private View smallPill(String s, View.OnClickListener click) {
        LinearLayout p = hbox();
        p.setGravity(Gravity.CENTER);
        p.setPadding(px(14), px(7), px(14), px(7));
        p.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), round(cPill, 16), null));
        p.addView(text(s, 13, cText, true));
        p.setOnClickListener(click);
        return p;
    }

    /** Opens WiFi Dot's own Notification-access page where possible, else the list. */
    private void openNotificationAccess() {
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        HotspotNotifications.component(this).flattenToString());
        Intent[] tries = Build.VERSION.SDK_INT >= 30
                ? new Intent[]{detail, new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")}
                : new Intent[]{new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")};
        for (Intent i : tries) {
            try { startActivity(i); return; } catch (Throwable ignored) {}
        }
        openAppSettings();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(POST_NOTIF) != 0) {
            requestPermissions(new String[]{POST_NOTIF}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        prefs.edit().putLong("detected2", 0).apply();
        MonitorService.start(this);
        Checker.runAsync(this, null);
        refresh();
    }
}

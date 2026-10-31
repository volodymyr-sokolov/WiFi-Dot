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
import java.util.Locale;
import java.util.concurrent.Executor;

/** Main screen: live status, connection speed, where the status is shown, settings, icon legend. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private int cBg, cCard, cText, cSub, cIcon, cRipple, cPill, cLine;
    private float dp;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private ImageView statusIcon;
    private TextView statusTitle, statusSub, statusTime;
    private TextView linkValue, testLabel, testServer, testInfo, saverSub;
    private Gauge gDown, gUp;
    private View testPill, qMeasure;
    private Switch swSaver;
    private TextView qualityValue, qLatency, qJitter, qLoss, qNote;
    private LinearLayout qualityScale, qualityDetails;
    private ImageView qualityArrow;
    private static final Quality QUALITY = new Quality();
    private volatile boolean measuring;
    private volatile int loopGen;   // each loop runs only while its generation is current
    private Quality.Result lastQuality;
    private Switch swAlerts, swLock, swWidget, swTile;
    private TextView batterySub;
    private View addWidget, addTile, lockSettings;
    private TextView lockSub;
    private boolean binding;
    private Ndt7 test;

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

        Checker.registerCallbackOnce(this);
        RefreshJob.schedule(this);
        Cleanup.run(this);
        if (Alerts.enabled(this)) askNotifications();
        Checker.runAsync(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
        handler.post(ticker);
        startQualityLoop();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacks(ticker);
        measuring = false;
        loopGen++;   // an old loop still inside a round stops when it finishes that round
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

        // ---- Connection speed
        col.addView(section("Connection speed"));
        LinearLayout speed = card(false);
        linkValue = text("—", 13, cSub, false);

        speed.addView(row(R.drawable.ic_ui_link, "Wi-Fi link", linkValue, null, null));
        speed.addView(divider());
        speed.addView(qualityRow());
        speed.addView(divider());
        speed.addView(speedPanel());
        col.addView(speed);

        // ---- Where the status is shown
        col.addView(section("Show status in"));
        LinearLayout show = card(false);

        swAlerts = new Switch(this);
        show.addView(row(R.drawable.ic_ui_bell, "Pop-up alerts",
                text("When the internet goes down or comes back · not while the hotspot is on", 13, cSub, false),
                swAlerts, null));
        swAlerts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Alerts.setEnabled(MainActivity.this, on);
                if (on) askNotifications();
            }
        });
        show.addView(divider());

        swLock = new Switch(this);
        lockSub = text("", 13, cSub, false);
        lockSettings = smallPill("Settings", new View.OnClickListener() {
            public void onClick(View v) { openNotificationSettings(); }
        });
        show.addView(row(R.drawable.ic_ui_lock, "Lock screen", lockSub, pair(lockSettings, swLock), null));
        swLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                LockScreen.setEnabled(MainActivity.this, on);
                if (on) {
                    askNotifications();
                    if (!LockScreen.allowed(MainActivity.this)) openNotificationSettings();
                }
                Checker.runAsync(MainActivity.this, null);
                refresh();
            }
        });
        show.addView(divider());

        swWidget = new Switch(this);
        addWidget = smallPill("Add", new View.OnClickListener() {
            public void onClick(View v) { requestPinWidget(); }
        });
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
        swSaver = new Switch(this);
        saverSub = text("No speed test · quality only on request · checks every 60 s · "
                + "no router brand lookup", 13, cSub, false);
        settings.addView(row(R.drawable.ic_ui_download, "Data saver", saverSub, swSaver, null));
        swSaver.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setDataSaver(MainActivity.this, on);
                if (on && test != null) test.cancel();
                measuring = false; loopGen++;
                lastQuality = null;
                if (!on) startQualityLoop();
                Checker.runAsync(MainActivity.this, null);   // reschedules the poll at the new interval
                refresh();
            }
        });
        settings.addView(divider());

        batterySub = text("", 13, cSub, false);
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
        legend.addView(legendRow(R.drawable.ic_hotspot, Checker.BLUE, "Phone hotspot is on"));
        col.addView(legend);

        TextView foot = text("Tap the widget for Details or the router page. The tile opens the router page "
                + "(Starlink: 192.168.100.1), or the hotspot settings while the hotspot is on.", 12, cSub, false);
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
            statusSub.setText(hsOnline ? "Sharing your connection · internet OK" : "No internet to share");
        } else statusSub.setText("nowifi".equals(state) ? "Connect to a Wi-Fi network"
                : (host.length() > 0 && !host.equals(label) ? label + " · " + host : label));
        updateTime();

        showQuality();
        showSpeedResult();
        updateLink();

        binding = true;
        swAlerts.setChecked(Alerts.enabled(this));
        swSaver.setChecked(Prefs.dataSaver(this));
        swLock.setChecked(LockScreen.enabled(this));
        boolean lockOn = LockScreen.enabled(this);
        boolean lockOk = LockScreen.allowed(this);
        lockSub.setText(!lockOn ? "Status card like the widget · only while the phone is locked"
                : lockOk ? "On · lock the phone to see it. Not there? Tap Settings → allow on lock screen"
                : "Blocked: tap Settings and allow WiFi Dot notifications");
        lockSettings.setVisibility(lockOn ? View.VISIBLE : View.GONE);
        swWidget.setChecked(Prefs.widget(this));
        swTile.setChecked(Prefs.tile(this));
        binding = false;
        addWidget.setVisibility(Prefs.widget(this) && canPinWidget() ? View.VISIBLE : View.GONE);
        addTile.setVisibility(Prefs.tile(this) && Build.VERSION.SDK_INT >= 33 ? View.VISIBLE : View.GONE);

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

    // ---------------------------------------------------------------- connection quality

    /** "Connection quality" row: rating + 0–100 score + five-step colour scale; tap to expand details. */
    private View qualityRow() {
        LinearLayout box = vbox();
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(18), px(12), px(14), px(12));
        r.addView(icon(R.drawable.ic_ui_timer, cIcon, 24));
        LinearLayout t = vbox();
        t.setPadding(px(16), 0, px(8), 0);
        t.addView(text("Connection quality", 16, cText, false));
        LinearLayout line = hbox();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, px(3), 0, 0);
        qualityValue = text("Measuring…", 13, cSub, true);
        line.addView(qualityValue);
        qualityScale = hbox();
        qualityScale.setPadding(px(10), 0, 0, 0);
        for (int i = 0; i < 5; i++) {
            View seg = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(14), px(6));
            lp.rightMargin = px(3);
            seg.setLayoutParams(lp);
            seg.setBackground(round(cLine, 3));
            qualityScale.addView(seg);
        }
        line.addView(qualityScale);
        t.addView(line);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        qMeasure = smallPill("Measure", new View.OnClickListener() {
            public void onClick(View v) { measureOnce(); }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.rightMargin = px(6);
        r.addView(qMeasure, mp);
        qualityArrow = icon(R.drawable.ic_ui_chevron, cSub, 22);
        qualityArrow.setRotation(90);   // pointing down = collapsed
        r.addView(qualityArrow);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), null, new ColorDrawable(0xFFFFFFFF)));
        box.addView(r);

        qualityDetails = vbox();
        qualityDetails.setPadding(px(58), 0, px(18), px(12));
        qLatency = detailLine(qualityDetails, "Latency");
        qJitter = detailLine(qualityDetails, "Jitter");
        qLoss = detailLine(qualityDetails, "Packet loss");
        qNote = text("", 12, cSub, false);
        qNote.setPadding(0, px(6), 0, 0);
        qualityDetails.addView(qNote);
        qualityDetails.setVisibility(View.GONE);
        box.addView(qualityDetails);

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = qualityDetails.getVisibility() != View.VISIBLE;
                qualityDetails.setVisibility(open ? View.VISIBLE : View.GONE);
                qualityArrow.animate().rotation(open ? -90 : 90).setDuration(150).start();
            }
        });
        return box;
    }

    private TextView detailLine(LinearLayout parent, String name) {
        LinearLayout l = hbox();
        l.setPadding(0, px(3), 0, px(3));
        TextView n = text(name, 14, cSub, false);
        l.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text("—", 14, cText, false);
        l.addView(v);
        parent.addView(l);
        return v;
    }

    /** Measure in rounds of 15 UDP packets every ~10 s while this screen is visible. */
    private void startQualityLoop() {
        if (measuring || Prefs.dataSaver(this)) return;   // Data saver: only on request (Measure)
        measuring = true;
        final int gen = ++loopGen;
        final Context app = getApplicationContext();
        new Thread(new Runnable() { public void run() {
            String lastNet = null;
            while (measuring && gen == loopGen) {
                android.net.Network net = Checker.qualityNetwork(app);
                String key = net == null ? "" : net.toString();
                if (!key.equals(lastNet)) { QUALITY.reset(); lastNet = key; }   // new network: start over
                final Quality.Result res = net == null ? null : QUALITY.measure(net);
                if (gen != loopGen) break;
                handler.post(new Runnable() { public void run() { lastQuality = res; showQuality(); } });
                for (int i = 0; i < 100 && measuring && gen == loopGen; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
            }
        } }, "wifidot-quality").start();
    }

    /** Data saver: one round of 15 tiny packets (about 2 KB) on request. */
    private void measureOnce() {
        final Context app = getApplicationContext();
        qualityValue.setText("Measuring…");
        new Thread(new Runnable() { public void run() {
            android.net.Network net = Checker.qualityNetwork(app);
            final Quality.Result res = net == null ? null : QUALITY.measure(net);
            handler.post(new Runnable() { public void run() { lastQuality = res; showQuality(); } });
        } }, "wifidot-quality-once").start();
    }

    private void showQuality() {
        if (qualityValue == null) return;
        qMeasure.setVisibility(Prefs.dataSaver(this) ? View.VISIBLE : View.GONE);
        Quality.Result q = lastQuality;
        String st = prefs.getString("state", "");
        int level;
        if (q == null || q.sent == 0) {
            boolean none = "nowifi".equals(st);
            qualityValue.setText(none ? "No connection"
                    : Prefs.dataSaver(this) ? "Tap Measure (Data saver)" : "Measuring…");
            qualityValue.setTextColor(cSub);
            level = 0;
            qLatency.setText("—"); qJitter.setText("—"); qLoss.setText("—"); qNote.setText("");
        } else if (q.received == 0) {
            level = 1;
            qualityValue.setText("Very bad · 0/100 · no replies");
            qualityValue.setTextColor(Quality.COLORS[1]);
            qLatency.setText("—"); qJitter.setText("—"); qLoss.setText("100%");
            qNote.setText(q.sent + " packets to " + q.target + ", none came back");
        } else {
            level = q.level;
            qualityValue.setText(Quality.LABELS[level] + " · " + q.score + "/100");
            qualityValue.setTextColor(Quality.COLORS[level]);
            qLatency.setText(String.format(Locale.US, "%.0f ms", q.latencyMs));
            qJitter.setText(String.format(Locale.US, "%.0f ms", q.jitterMs));
            qLoss.setText(q.lossPct == 0 ? "0%" : String.format(Locale.US, "%.1f%%", q.lossPct));
            qNote.setText("DNS round trip, last " + q.sent + " packets to " + q.target
                    + " · updates every 10 s while this screen is open");
        }
        for (int i = 0; i < 5; i++) {
            qualityScale.getChildAt(i).setBackground(round(i < level ? Quality.COLORS[level] : cLine, 3));
        }
    }

    // ---------------------------------------------------------------- speed test (M-Lab ndt7)

    /** Two speedometers (download / upload), the start button and the data note. */
    private View speedPanel() {
        LinearLayout box = vbox();
        box.setPadding(px(14), px(10), px(14), px(14));
        LinearLayout gauges = hbox();
        gDown = new Gauge(this, "Download", 0xFF2F80ED, cLine, cText, cSub);
        gUp = new Gauge(this, "Upload", 0xFF9B51E0, cLine, cText, cSub);
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(0, -2, 1);
        g1.rightMargin = px(6);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(0, -2, 1);
        g2.leftMargin = px(6);
        gauges.addView(gDown, g1);
        gauges.addView(gUp, g2);
        box.addView(gauges);

        LinearLayout bar = hbox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(4), px(8), 0, 0);
        LinearLayout texts = vbox();
        testServer = text("Speed test by M-Lab (ndt7)", 13, cText, false);
        testInfo = text("", 12, cSub, false);
        texts.addView(testServer);
        texts.addView(testInfo);
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        testPill = smallPill("Start", new View.OnClickListener() {
            public void onClick(View v) { toggleSpeedTest(); }
        });
        testLabel = (TextView) ((ViewGroup) testPill).getChildAt(0);
        bar.addView(testPill);
        box.addView(bar);
        return box;
    }

    /** Rough data use of one test: ~10 s download + ~10 s upload at the given speeds. */
    private static double estimateMb(double down, double up) {
        return (down + up) * 10 / 8 * 1.1;
    }

    private String dataWarning() {
        float down = prefs.getFloat("st_down", -1), up = prefs.getFloat("st_up", -1);
        if (down > 0) {
            return String.format(Locale.US, "One test uses about %.0f MB at your last speeds "
                    + "(10 s download + 10 s upload at full speed).", estimateMb(down, Math.max(0, up)));
        }
        return "One test downloads and uploads at full speed for about 10 s each: roughly 100–300 MB "
                + "on a typical Starlink connection, up to about 1.3 GB on a 1 Gbit/s line.";
    }

    private void toggleSpeedTest() {
        if (test != null) { test.cancel(); testLabel.setText("Stopping…"); return; }
        if (Prefs.dataSaver(this)) { testInfo.setText("Turned off by Data saver (Settings below)"); return; }
        final android.net.Network net = Checker.qualityNetwork(this);
        String st = prefs.getString("state", "");
        boolean ok = net != null && ("online".equals(st)
                || "hotspot".equals(st) && "online".equals(prefs.getString("hs_net", "")));
        if (!ok) { testInfo.setText("Needs a working internet connection over Wi-Fi or the hotspot"); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Run speed test?")
                .setMessage(dataWarning() + "\n\nOn a metered connection (satellite plan, mobile data via "
                        + "the hotspot) this counts towards your data allowance.")
                .setPositiveButton("Start", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { startSpeedTest(net); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startSpeedTest(android.net.Network net) {
        test = new Ndt7();
        testLabel.setText("Stop");
        testServer.setText("Finding the nearest M-Lab server…");
        testInfo.setText("");
        gDown.setValue(-1);
        gUp.setValue(-1);
        test.run(net, new Ndt7.Listener() {
            public void server(final String name) { ui(new Runnable() { public void run() {
                testServer.setText(name); testInfo.setText("Testing download…"); } }); }
            public void download(final double mbps, final boolean done) { ui(new Runnable() { public void run() {
                gDown.setValue(mbps); if (done) testInfo.setText("Testing upload…"); } }); }
            public void upload(final double mbps, final boolean done) { ui(new Runnable() { public void run() {
                gUp.setValue(mbps); } }); }
            public void finished(final double down, final double up, final long bytes, final String error) {
                ui(new Runnable() { public void run() {
                    test = null;
                    testLabel.setText("Start");
                    if (error == null && down > 0) {
                        prefs.edit().putFloat("st_down", (float) down).putFloat("st_up", (float) Math.max(0, up))
                                .putLong("st_time", System.currentTimeMillis()).putLong("st_bytes", bytes)
                                .putString("st_server", testServer.getText().toString()).apply();
                        showSpeedResult();
                    } else {
                        testInfo.setText("Test " + (error != null ? "failed: " + error : "failed")
                                + String.format(Locale.US, " · used %.0f MB", bytes / 1e6));
                    }
                } });
            }
        });
    }

    private void ui(Runnable r) { handler.post(r); }

    /** Last result on the gauges (when no test is running) and the data note. */
    private void showSpeedResult() {
        if (gDown == null || test != null) return;
        boolean saver = Prefs.dataSaver(this);
        testPill.setAlpha(saver ? 0.4f : 1f);
        float down = prefs.getFloat("st_down", -1), up = prefs.getFloat("st_up", -1);
        long t = prefs.getLong("st_time", 0), bytes = prefs.getLong("st_bytes", 0);
        gDown.setValue(down > 0 ? down : -1);
        gUp.setValue(down > 0 ? up : -1);
        if (saver) {
            testServer.setText("Speed test by M-Lab (ndt7)");
            testInfo.setText("Turned off by Data saver");
        } else if (down > 0) {
            testServer.setText(prefs.getString("st_server", "M-Lab"));
            testInfo.setText(ago(t) + String.format(Locale.US, " · used %.0f MB · tap Start for a new test", bytes / 1e6));
        } else {
            testServer.setText("Speed test by M-Lab (ndt7)");
            testInfo.setText("Uses a lot of data — see the estimate before it starts");
        }
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
                    .invoke(sbm, new ComponentName(getPackageName(), Prefs.TILE_CLASS), "Wi-Fi Internet",
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

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    /** WiFi Dot's notification settings (where phones keep the per-app "lock screen" switch). */
    private void openNotificationSettings() {
        Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("android.provider.extra.APP_PACKAGE", getPackageName())
                .putExtra("app_package", getPackageName())
                .putExtra("app_uid", getApplicationInfo().uid);
        try { startActivity(i); } catch (Throwable e) { openAppSettings(); }
    }

    /** Creates the pop-up channel (Android 13+ then asks once for notification permission). */
    private void askNotifications() {
        Alerts.ensureChannel(this);
        if (LockScreen.enabled(this)) LockScreen.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        prefs.edit().putLong("detected2", 0).apply();
        Checker.runAsync(this, null);
        refresh();
    }
}

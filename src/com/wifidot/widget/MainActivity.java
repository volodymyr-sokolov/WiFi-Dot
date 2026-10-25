package com.wifidot.widget;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import java.util.HashSet;
import java.util.Set;

/** Main screen: live status, settings, icon legend and a short how-to. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String POST_NOTIF = "android.permission.POST_NOTIFICATIONS";
    private static final String LOCATION = "android.permission.ACCESS_FINE_LOCATION";

    // palette (set in onCreate for light / dark)
    private int cBg, cCard, cText, cSub, cIcon, cRipple, cPill;
    private float dp;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private ImageView statusIcon;
    private TextView statusTitle, statusSub, statusTime;
    private Switch notifSwitch, starlinkSwitch;
    private TextView starlinkSub, locationSub;
    private boolean binding;

    private final Runnable ticker = new Runnable() {
        public void run() { updateTime(); handler.postDelayed(this, 1000); }
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
            cIcon = 0xFFB0BAC4; cRipple = 0x33FFFFFF; cPill = 0xFF2A3239;
        } else {
            cBg = 0xFFF2F4F7; cCard = 0xFFFFFFFF; cText = 0xFF14191E; cSub = 0xFF66707A;
            cIcon = 0xFF55606B; cRipple = 0x22000000; cPill = 0xFFEDF0F3;
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
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        handler.post(new Runnable() { public void run() { refresh(); } });
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(20), px(16), px(28));

        // Header
        TextView title = text("WiFi Dot", 28, cText, true);
        col.addView(title);
        TextView tagline = text("Wi-Fi internet monitor", 14, cSub, false);
        tagline.setPadding(0, px(2), 0, px(16));
        col.addView(tagline);

        // Status card
        LinearLayout status = card();
        LinearLayout top = hbox();
        top.setGravity(Gravity.CENTER_VERTICAL);
        statusIcon = new ImageView(this);
        statusIcon.setImageResource(R.drawable.ic_stat_ok);
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
        View check = pill(R.drawable.ic_ui_refresh, "Check now", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putLong("detected2", 0).apply();
                statusTitle.setText("Checking…");
                Checker.runAsync(MainActivity.this, null);
            }
        });
        View open = pill(R.drawable.ic_ui_open, "Open router page", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, OpenActivity.class)); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = px(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
        lp2.leftMargin = px(6);
        pills.addView(check, lp1);
        pills.addView(open, lp2);
        status.addView(pills);
        col.addView(status);

        // Settings
        col.addView(section("Settings"));
        LinearLayout settings = card();
        settings.setPadding(0, px(4), 0, px(4));

        notifSwitch = new Switch(this);
        settings.addView(row(R.drawable.ic_ui_bell, "Status bar icon",
                text("Checks every 10 s, icon next to the clock", 13, cSub, false), notifSwitch, null));
        notifSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                prefs.edit().putBoolean("notif", on).apply();
                if (on) { askNotifPermission(); MonitorService.start(MainActivity.this); }
                else { MonitorService.stop(MainActivity.this); StatusNotifier.cancel(MainActivity.this); }
            }
        });

        starlinkSwitch = new Switch(this);
        starlinkSub = text("", 13, cSub, false);
        settings.addView(divider());
        settings.addView(row(R.drawable.ic_ui_router, "This network is Starlink", starlinkSub, starlinkSwitch, null));
        starlinkSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
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

        settings.addView(divider());
        settings.addView(row(R.drawable.ic_ui_battery, "Battery optimization",
                text("Turn off for WiFi Dot so the icon stays", 13, cSub, false), chevron(),
                new View.OnClickListener() {
                    public void onClick(View v) { tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                }));
        col.addView(settings);

        // Icon legend
        col.addView(section("Icons"));
        LinearLayout legend = card();
        legend.setPadding(0, px(4), 0, px(4));
        legend.addView(legendRow(R.drawable.ic_stat_ok, Checker.GREEN, "Internet is available"));
        legend.addView(legendRow(R.drawable.ic_stat_bad, Checker.RED, "Wi-Fi connected, no internet"));
        legend.addView(legendRow(R.drawable.ic_stat_off, Checker.RED, "Wi-Fi is off"));
        col.addView(legend);

        // How-to
        col.addView(section("Where to find it"));
        LinearLayout how = card();
        how.setPadding(0, px(4), 0, px(4));
        how.addView(row(R.drawable.ic_ui_widgets, "Home-screen widget",
                text("Long-press home screen → Widgets → WiFi Dot", 13, cSub, false), null, null));
        how.addView(divider());
        how.addView(row(R.drawable.ic_ui_toggle, "Quick settings tile",
                text("Pull down the shade → ✎ Edit → drag “Wi-Fi Internet”", 13, cSub, false), null, null));
        how.addView(divider());
        how.addView(row(R.drawable.ic_ui_open, "Tap any icon",
                text("Opens the Starlink page (192.168.100.1) or your router’s page", 13, cSub, false), null, null));
        col.addView(how);

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

        statusIcon.setImageResource(Checker.iconFor(state));
        statusIcon.setColorFilter(Checker.colorFor(state));
        if ("online".equals(state)) statusTitle.setText("Internet OK");
        else if ("offline".equals(state)) statusTitle.setText("No internet");
        else if ("nowifi".equals(state)) statusTitle.setText("Wi-Fi not connected");
        else statusTitle.setText("Checking…");
        String host = url.replace("http://", "").replace("/", "");
        statusSub.setText("nowifi".equals(state) ? "Connect to a Wi-Fi network"
                : (host.length() > 0 && !host.equals(label) ? label + " · " + host : label));
        updateTime();

        binding = true;
        notifSwitch.setChecked(StatusNotifier.enabled(this));
        boolean marked = Checker.markedStarlink(this, gw);
        starlinkSwitch.setChecked(marked);
        starlinkSwitch.setEnabled(gw.length() > 0);
        binding = false;
        starlinkSub.setText(gw.length() == 0 ? "Connect to Wi-Fi first"
                : "Gateway " + gw + (marked ? " · opens 192.168.100.1" : " · use if not detected"));
        locationSub.setText(checkSelfPermission(LOCATION) == 0
                ? "On · also needs Location turned on" : "Needs location access");
    }

    private void updateTime() {
        long t = prefs.getLong("time", 0);
        if (t == 0) { statusTime.setText(""); return; }
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        statusTime.setText(s < 60 ? "Checked " + s + " s ago" : "Checked " + (s / 60) + " min ago");
    }

    // ---------------------------------------------------------------- helpers

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

    private LinearLayout card() {
        LinearLayout c = vbox();
        c.setBackground(round(cCard, 20));
        c.setPadding(px(18), px(18), px(18), px(18));
        c.setElevation(px(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(4);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 12, cSub, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(px(6), px(20), 0, px(8));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cRipple);
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
        if (end != null) {
            if (end.getParent() != null) ((ViewGroup) end.getParent()).removeView(end);
            r.addView(end);
        }
        if (end instanceof Switch && click == null) {
            final Switch sw = (Switch) end;
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

    private void tryStart(Intent i) {
        try { startActivity(i); } catch (Throwable e) { openAppSettings(); }
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

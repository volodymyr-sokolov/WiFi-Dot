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
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small menu shown when the home-screen widget is tapped: Details / Router main page. */
public class MenuActivity extends Activity {
    private float dp;
    private int cCard, cText, cSub, cIcon, cRipple, cLine;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) { cCard = 0xFF1C2228; cText = 0xFFECEFF1; cSub = 0xFF9AA4AE; cIcon = 0xFFB0BAC4; cRipple = 0x33FFFFFF; cLine = 0x22FFFFFF; }
        else { cCard = 0xFFFFFFFF; cText = 0xFF14191E; cSub = 0xFF66707A; cIcon = 0xFF55606B; cRipple = 0x22000000; cLine = 0x14000000; }

        SharedPreferences p = getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String label = p.getString("label", "");
        String url = p.getString("url", "");
        String host = url.replace("http://", "").replace("/", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(px(24));
        card.setBackground(bg);
        card.setPadding(0, px(8), 0, px(8));
        card.setClipToOutline(true);

        // Header: current status with the same icon as everywhere else
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(20), px(14), px(20), px(14));
        ImageView ic = new ImageView(this);
        ic.setImageResource(Checker.iconFor(p));
        ic.setColorFilter(Checker.colorFor(state));
        head.addView(ic, new LinearLayout.LayoutParams(px(32), px(32)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(px(14), 0, 0, 0);
        boolean hs = "hotspot".equals(state);
        if (hs) label = "offline".equals(p.getString("hs_net", "")) ? "No internet to share" : "Sharing your connection";
        String title = hs ? "Hotspot on" : "online".equals(state) ? "Internet OK" : "offline".equals(state) ? "No internet"
                : "nowifi".equals(state) ? "Wi-Fi is off" : "Checking…";
        ht.addView(text(title, 18, cText, true));
        if (label.length() > 0 && !"nowifi".equals(state)) ht.addView(text(label, 13, cSub, false));
        head.addView(ht);
        card.addView(head);

        View line = new View(this);
        line.setBackgroundColor(cLine);
        card.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, px(0.7f))));

        card.addView(item(R.drawable.ic_ui_info, "Details", "Open WiFi Dot", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            }
        }));
        boolean noWifi = "nowifi".equals(state);
        card.addView(item(R.drawable.ic_ui_open,
                hs ? "Hotspot settings" : noWifi ? "Wi-Fi settings" : "Router main page",
                hs ? "Name, password, devices" : noWifi ? "Connect to a network"
                        : (host.length() > 0 ? host : "Address unknown"),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MenuActivity.this, OpenActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                    }
                }));

        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setPadding(px(12), px(12), px(12), px(12));
        root.addView(card, new LinearLayout.LayoutParams(px(300), -2));
        setContentView(root);
        getWindow().setLayout(-2, -2);

        Checker.runAsync(this, null);
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private View item(int icon, String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(22), px(14), px(20), px(14));
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setColorFilter(cIcon);
        r.addView(i, new LinearLayout.LayoutParams(px(24), px(24)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(18), 0, 0, 0);
        t.addView(text(title, 16, cText, false));
        t.addView(text(sub, 13, cSub, false));
        r.addView(t);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), null, new ColorDrawable(0xFFFFFFFF)));
        r.setOnClickListener(click);
        return r;
    }
}

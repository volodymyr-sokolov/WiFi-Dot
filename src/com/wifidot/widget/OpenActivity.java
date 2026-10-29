package com.wifidot.widget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Opens the router or Starlink page in the browser (or hotspot / Wi-Fi settings) and re-checks. */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String state = getSharedPreferences(Checker.PREFS, 0).getString("state", "");
        String url = getSharedPreferences(Checker.PREFS, 0).getString("url", "");
        try {
            if ("hotspot".equals(state)) {
                openHotspotSettings();
            } else if ("nowifi".equals(state)) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else if (url.length() > 0) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(i);
            } else {
                Toast.makeText(this, "Router address unknown", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            Toast.makeText(this, "Could not open " + url, Toast.LENGTH_SHORT).show();
        }
        Checker.runAsync(this, null);
        finish();
    }

    private void openHotspotSettings() {
        Intent[] tries = {
                new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
        };
        for (Intent i : tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Throwable ignored) {}
        }
    }
}

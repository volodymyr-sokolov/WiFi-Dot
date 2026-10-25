package com.wifidot.widget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Tap on widget/tile/notification: open the router or Starlink page in the browser and re-check. */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String state = getSharedPreferences(Checker.PREFS, 0).getString("state", "");
        String url = getSharedPreferences(Checker.PREFS, 0).getString("url", "");
        try {
            if ("nowifi".equals(state)) {
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
        MonitorService.start(this);
        Checker.runAsync(this, null);
        finish();
    }
}

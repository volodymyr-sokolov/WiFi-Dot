package com.wifidot.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile (Android 7.0+). */
public class WifiTileService extends TileService {

    static void requestUpdate(Context ctx) {
        if (Build.VERSION.SDK_INT < 24) return;
        try { TileService.requestListeningState(ctx, new ComponentName(ctx, WifiTileService.class)); }
        catch (Throwable ignored) {}
    }

    @Override public void onTileAdded() { refresh(); }

    @Override public void onStartListening() {
        refresh();
        Checker.runAsync(this, null); // result arrives via requestUpdate
    }

    @Override public void onClick() {
        Runnable go = new Runnable() { public void run() { openPage(); } };
        if (isLocked()) unlockAndRun(go); else go.run();
    }

    private void openPage() {
        Intent i = new Intent(this, OpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(
                    this, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */));
            else startActivityAndCollapse(i);
        } catch (Throwable e) {
            try { startActivity(i); } catch (Throwable ignored) {}
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        SharedPreferences p = getSharedPreferences(Checker.PREFS, 0);
        String state = p.getString("state", "");
        String label = p.getString("label", "Wi-Fi");
        String sub;
        if ("hotspot".equals(state)) {
            t.setState(Tile.STATE_ACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_hotspot));
            sub = "offline".equals(p.getString("hs_net", "")) ? "On · no internet" : "On";
            label = "Hotspot";
        } else if ("online".equals(state)) {
            t.setState(Tile.STATE_ACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_ok));
            sub = "Internet OK";
        } else if ("offline".equals(state)) {
            t.setState(Tile.STATE_INACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_bad));
            sub = "No internet";
        } else if ("nowifi".equals(state)) {
            t.setState(Tile.STATE_INACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_off));
            sub = "Wi-Fi off"; label = "Wi-Fi";
        } else {
            t.setState(Tile.STATE_INACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_ok));
            sub = "Checking…";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            t.setLabel(label);
            try { t.setSubtitle(sub); } catch (Throwable ignored) {}
        } else {
            t.setLabel(sub + " · " + label);
        }
        t.setContentDescription(sub + ", " + label);
        t.updateTile();
    }
}

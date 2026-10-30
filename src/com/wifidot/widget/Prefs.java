package com.wifidot.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** User options: where the status is shown. All on by default. */
final class Prefs {
    private Prefs() {}

    static boolean widget(Context c) { return componentOn(c, WifiDotWidget.class); }
    static boolean tile(Context c) { return componentOn(c, WifiTileService.class); }

    static void setWidget(Context c, boolean on) { setComponent(c, WifiDotWidget.class, on); }
    static void setTile(Context c, boolean on) { setComponent(c, WifiTileService.class, on); }

    static boolean anyOutputOn(Context c) { return widget(c) || tile(c) || Alerts.enabled(c) || LockScreen.enabled(c); }

    private static boolean componentOn(Context c, Class<?> cls) {
        int s = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c, cls));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private static void setComponent(Context c, Class<?> cls, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, cls),
                on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}

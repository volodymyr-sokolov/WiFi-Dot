package com.wifidot.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** User options: where the status is shown. All on by default. */
final class Prefs {
    private Prefs() {}

    static boolean widget(Context c) { return componentOn(c, WifiDotWidget.class); }
    /** The tile class only exists on Android 7+; referencing it on Android 6 would crash. */
    static final String TILE_CLASS = "com.wifidot.widget.WifiTileService";

    static boolean tile(Context c) { return Build.VERSION.SDK_INT >= 24 && componentOn(c, TILE_CLASS); }

    static void setWidget(Context c, boolean on) { setComponent(c, WifiDotWidget.class, on); }
    static void setTile(Context c, boolean on) { if (Build.VERSION.SDK_INT >= 24) setComponent(c, TILE_CLASS, on); }

    static boolean anyOutputOn(Context c) { return widget(c) || tile(c) || Alerts.enabled(c) || LockScreen.enabled(c); }

    private static boolean componentOn(Context c, Class<?> cls) { return componentOn(c, cls.getName()); }

    private static boolean componentOn(Context c, String cls) {
        int s = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c.getPackageName(), cls));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private static void setComponent(Context c, Class<?> cls, boolean on) { setComponent(c, cls.getName(), on); }

    private static void setComponent(Context c, String cls, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c.getPackageName(), cls),
                on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}

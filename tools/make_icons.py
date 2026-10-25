#!/usr/bin/env python3
"""Generates every WiFi Dot status icon from one shape, so they stay identical everywhere.

The fan is a classic 90-degree Wi-Fi sector (same proportions as the system Wi-Fi icon),
as large as possible inside the 24x24 square and centred both ways.
Run from the repo root: python3 tools/make_icons.py
"""
import math, os

PAD = 0.5                       # keep 0.5 unit from the square's edges
HALF = math.radians(45)         # 90-degree sector
R = (24 - 2 * PAD) / (2 * math.sin(HALF))
TOP = (24 - R) / 2              # centre vertically
AY = TOP + R                    # apex (bottom point)
EX = 12 - R * math.sin(HALF)
EY = AY - R * math.cos(HALF)
FAN = f"M12,{AY:.2f}L{EX:.2f},{EY:.2f}A{R:.2f},{R:.2f} 0 0 1 {24 - EX:.2f},{EY:.2f}z"

def area(p):
    return sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))

OUTER = area([(12, AY), (EX, EY), (24 - EX, EY)])

def hole(x1, y1, x2, y2):
    """Rectangle wound opposite to the fan, so it is cut out (works on every Android version)."""
    pts = [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]
    if (area(pts) > 0) == (OUTER > 0):
        pts = pts[::-1]
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z"

# Large "!" centred on the fan's axis
W = 3.2                                    # stroke width
DOT_BOTTOM = AY - (W / 2 + 1.0)            # ≥1 unit of fan left on each side of the dot
DOT_TOP = DOT_BOTTOM - 3.0
BAR_BOTTOM = DOT_TOP - 1.3
BAR_TOP = TOP + 1.5
BANG = hole(12 - W / 2, BAR_TOP, 12 + W / 2, BAR_BOTTOM) + hole(12 - W / 2, DOT_TOP, 12 + W / 2, DOT_BOTTOM)

# Slash through the fan's centre (12, 12)
SLASH = "M4.5,4.5L19.5,19.5"

def vector(body, size=24, view=24):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp" '
            f'android:viewportWidth="{view}" android:viewportHeight="{view}">\n{body}\n</vector>\n')

def group(scale, tx, ty, color="#FF2ECC40"):
    return (f'    <group android:translateX="{tx:.2f}" android:translateY="{ty:.2f}" '
            f'android:scaleX="{scale}" android:scaleY="{scale}">\n'
            f'        <path android:fillColor="{color}" android:pathData="{FAN}" />\n    </group>')

D = os.path.join(os.path.dirname(__file__), "..", "res", "drawable")
files = {
    "ic_stat_ok.xml": vector(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{FAN}" />'),
    "ic_stat_bad.xml": vector(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{FAN}{BANG}" />'),
    "ic_stat_off.xml": vector(
        f'    <path android:fillColor="#4DFFFFFF" android:pathData="{FAN}" />\n'
        f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="2.6" '
        f'android:strokeLineCap="round" android:pathData="{SLASH}" />'),
    # adaptive launcher foreground (108dp, 66dp safe zone)
    "ic_launcher_fg.xml": vector(group(2.6, (108 - 24 * 2.6) / 2, (108 - 24 * 2.6) / 2), 108, 108),
    # legacy launcher icon (Android 6–7)
    "ic_launcher.xml": vector(
        '    <path android:fillColor="#FF1E2A32" android:pathData="M24,2a22,22 0,1 1,0 44a22,22 0,1 1,0 -44z" />\n'
        + group(1.3, (48 - 24 * 1.3) / 2, (48 - 24 * 1.3) / 2), 48, 48),
    # widget picker preview: dark rounded tile, green fan, label bar
    "widget_preview.xml": vector(
        '    <path android:fillColor="#99000000" android:pathData="M14,0H58A14,14 0,0 1,72 14V58A14,14 0,0 1,58 72H14A14,14 0,0 1,0 58V14A14,14 0,0 1,14 0z" />\n'
        + group(1.5, (72 - 24 * 1.5) / 2, 6) + '\n'
        '    <path android:fillColor="#FFFFFFFF" android:pathData="M27,52h18v3H27z" />', 72, 72),
}
for name, xml in files.items():
    with open(os.path.join(D, name), "w") as f:
        f.write(xml)
print("FAN", FAN)
print("BANG", BANG)
print("SLASH", SLASH)

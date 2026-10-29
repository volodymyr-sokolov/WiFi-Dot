# WiFi Dot

A tiny Android app that shows whether your **Wi-Fi actually has internet**, not just whether you are connected.
Built with Starlink in mind: one tap opens the dish status page.

![Icons](docs/icons.png)

**[Download WiFiDot.apk (v2.2)](https://github.com/volodymyr-sokolov/wifi-dot/raw/main/release/WiFiDot.apk)**. Open it on the phone and allow installing from unknown sources.

| Icon | Meaning |
|------|---------|
| Green fan | Internet is available |
| Red fan with **!** | Wi-Fi connected, no internet |
| Grey crossed-out fan | Wi-Fi is off (widget and app only; never shown next to the clock) |
| Blue hotspot with a number | The phone's hotspot is on; the number is how many devices are connected (dot = none, + = 10 or more) |

## Where the status appears

Each can be turned on or off in the app (all on by default):

- **Status bar icon**: a full-size icon next to the clock, checked every 10 s.
- **Pop-up alerts**: shown only when the internet goes down or comes back over Wi-Fi. The new status must be confirmed by two checks in a row, and the same status is never announced twice.
- **Home screen widget**: a green, red or grey fan with the router name. Tap it for a menu with **Details** (opens the app) and **Router main page**.
- **Quick Settings tile**: "Wi-Fi Internet".

The status bar icon and the tile open the router page directly. **Starlink** opens `http://192.168.100.1`. Other routers open their gateway address. TP-Link, Keenetic, ASUS, MikroTik, Xiaomi and others are recognised by name.

## Hotspot

When the phone's own Wi-Fi hotspot is on, every status place switches to a blue hotspot icon with the number of connected devices in the middle:

- the status bar icon
- the widget, which shows "1 device", "3 devices" and so on
- the Quick Settings tile
- the app

Tapping it opens the hotspot settings. The app also checks whether the shared connection has internet.

Android doesn't give ordinary apps the hotspot client list. The most reliable source is the system's own
"Hotspot is active · 1 device connected" notification:

- **Exact (recommended):** turn on **Exact hotspot device count** in the app and grant WiFi Dot *Notification access*. The app then reads only the number from the system hotspot notification and updates instantly.
  On Android 13+ an app installed from an APK file can't get this access at first: the switch is greyed out. Open **App info → ⋮ → Allow restricted settings**, then turn the switch on.
- **Where the phone allows it:** the app reads the system's neighbour table, which is also exact.
- **Otherwise:** the app sweeps the hotspot subnet with ping and TCP every 30 s. A device with a strict firewall may not be counted.

While the hotspot is on, the app shows a **Hotspot diagnostics** card listing what each method found, so a wrong count can be traced.

Turning the hotspot on or off updates everything within seconds while the app is running in the background, or within about 20 s otherwise.

## Connection speed

The main screen shows:

- the Wi-Fi link rate, band and signal strength
- the latency of each internet check
- an on-demand download test: 4 parallel streams for 8 s, about 30–100 MB

## How it works

- Internet is checked with real HTTP probes (`generate_204`) bound to the Wi-Fi network. Checks run every 10 s with the screen on and every 2 min with it off, using a foreground service behind the status bar icon. When that service isn't running, lighter alarm-based polling takes over.
- With no Wi-Fi, monitoring stops and the status bar icon disappears. It comes back automatically when Wi-Fi connects.
- Starlink is detected by any of:
  - the dish (`192.168.100.1`, ports 9200/9201/80)
  - the Starlink router gRPC port (`:9000`)
  - the network name (needs location permission)
  - a manual "This network is Starlink" switch
- For reliable background checks, turn off battery optimization for WiFi Dot. The app has a shortcut for this.

Requires Android 6.0+. The Quick Settings tile requires Android 7.0+.

## Building

No Gradle needed. The build uses the Android SDK tools packaged in Ubuntu/Debian:

```sh
sudo apt install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange openjdk-17-jdk
./build.sh          # → WiFiDot.apk
```

`build.sh` runs `aapt → javac → dx → zipalign → apksigner`. Newer APIs (Quick Settings tile, notification channels, pinning widgets and tiles) are compiled against the small stubs in `stubs/` and called with version checks or reflection.
Icons are generated from one shape by `tools/make_icons.py`.
The app targets API 28 on purpose. That lets it restart its monitor from the background on Android 12+ and read the neighbour table for the hotspot device count.
A signing key `wifidot.keystore` is generated on the first build and is not committed.

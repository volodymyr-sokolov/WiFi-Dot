# WiFi Dot

A tiny Android app that shows whether your **Wi-Fi actually has internet** — not just whether you are connected.

| Icon | Meaning |
|------|---------|
| Green fan | Internet is available |
| Red fan with **!** | Wi-Fi connected, no internet |
| Crossed-out fan | Wi-Fi is off |

## Where the status appears

- **Status bar** — icon next to the clock (via an ongoing notification).
- **Quick settings tile** — "Wi-Fi Internet".
- **Home-screen widget** — green / red fan with the router name.

Tap any of them to open the router's page: **Starlink** opens `http://192.168.100.1`,
other routers open their gateway address (TP-Link, Keenetic, ASUS, MikroTik, Xiaomi and others are recognised by name).

## How it works

- Internet is checked with real HTTP probes (`generate_204`) bound to the Wi-Fi network,
  every 10 s with the screen on and every 2 min with it off.
- Starlink is detected by the dish (`192.168.100.1`, ports 9200/9201/80), the Starlink router
  gRPC port (`:9000`), the network name (with location permission), or a manual "This network is Starlink" switch.

Requires Android 6.0+. Quick settings tile requires Android 7.0+.

## Building

No Gradle needed — the build uses the Android SDK tools packaged in Ubuntu/Debian:

```sh
sudo apt install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange openjdk-17-jdk
./build.sh          # → WiFiDot.apk
```

`build.sh` runs `aapt → javac → dx → zipalign → apksigner`. Newer APIs (quick settings tile, notification
channels) are compiled against the small stubs in `stubs/` and called with version checks / reflection.
A signing key `wifidot.keystore` is generated on first build (not committed).

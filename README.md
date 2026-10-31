# WiFi Dot

A tiny Android app that shows whether your **Wi-Fi actually has internet**, not just whether you are connected.
Built with Starlink in mind: one tap opens the dish status page.

![Icons](docs/icons.png)

**[Download WiFiDot.apk (v3.7)](https://github.com/volodymyr-sokolov/wifi-dot/raw/main/release/WiFiDot.apk)**. Open it on the phone and allow installing from unknown sources.

| Icon | Meaning |
|------|---------|
| Green fan | Internet is available |
| Red fan with **!** | Wi-Fi connected, no internet |
| Grey crossed-out fan | Wi-Fi is off |
| Blue hotspot | The phone's own hotspot is on |

## Where the status appears

Each can be turned on or off in the app (all on by default):

- **Pop-up alerts**: a short message when the internet over Wi-Fi goes down or comes back, e.g. "No internet · Starlink". It shows the green or red status icon, disappears by itself and leaves no icon next to the clock.
  - A change must hold for at least 5 s across two checks, or 15 s if Android still reports the Wi-Fi as working.
  - There is no "No internet" in the first 20 s after Wi-Fi connects.
  - Pop-ups are at least 30 s apart, and each status gets a fresh notification, so mixed icons can't appear.
  - Never twice for the same status, never while the phone's hotspot is on, and never together with the lock-screen card.
- **Lock screen** (off by default): a status card like the widget, with the coloured icon and e.g. "Internet OK · Starlink". It's a regular notification that never makes a sound, never pops up and never wakes the screen. It exists only while the phone is locked and is removed as soon as you unlock. If it doesn't appear, use the **Settings** button next to the switch to allow WiFi Dot notifications on the lock screen. Most phones don't let ordinary apps put widgets on the lock screen. On phones that do, the widget itself can also be placed there.
- **Home screen widget**: the icon in its colour, with the router name. Tap it for a small menu with **Details** (opens the app) and **Router main page**, or **Hotspot settings** while the hotspot is on.
- **Quick Settings tile**: "Wi-Fi Internet". Tap it to open the router page.

**Starlink** opens `http://192.168.100.1`. Other routers open their gateway address. TP-Link, Keenetic, ASUS, MikroTik, Xiaomi and others are recognised by name.

Apart from the pop-up alerts and the optional lock-screen card, the app shows no notifications and no icon next to the clock. The system's own Wi-Fi and hotspot indicators cover that.

## Connection speed

The main screen shows:

- the Wi-Fi link rate, band and signal strength
- **Connection quality**: a rating from 1 to 5 (Very bad, Bad, Fair, Good, Excellent) with a colour scale and a score out of 100. Tap it to see latency, jitter and packet loss. It's measured while the screen is open: every 10 s the app sends 15 tiny UDP packets (DNS queries to 1.1.1.1, or 8.8.8.8 if that's blocked) and pools the last 100. Each round starts with one untimed warm-up packet. Only genuine replies from the server queried are counted. With Wi-Fi off and no hotspot, nothing is measured. UDP is used because it shows real packet loss, which a web request hides by retrying.
  - Latency (median): ≤30 ms → 5, ≤60 → 4, ≤100 → 3, ≤200 → 2, slower → 1.
  - Jitter of 30 ms or more costs 1 point, 80 ms or more costs 2. A 20 ms ping with jitter jumping to 100 ms means lag, so it rates "Fair" rather than "Excellent".
  - Packet loss above 1% caps the rating at 2 ("Bad"), and 10% or more makes it 1 ("Very bad"), however low the ping. A single lost packet is treated as noise, so it takes at least 2.
- **Speed test** by [M-Lab ndt7](https://www.measurementlab.net/tests/ndt/ndt7/): two speedometers (Download and Upload, non-linear scale 0–1000 Mbit/s) under Connection quality.
  - Tap **Start**. Before every test the app shows how much data it will use: about 10 s download plus 10 s upload at full speed. That's roughly 100–300 MB on a typical Starlink connection and up to about 1.3 GB on a 1 Gbit/s line. After a test it uses your last speeds to estimate. Afterwards it shows the data actually used.
  - It finds the nearest M-Lab server with the Locate API v2 and connects over a WebSocket (`net.measurementlab.ndt.v7`), bound to the Wi-Fi (or, with the hotspot on, the shared connection).
  - Download speed comes from what actually arrived; upload speed comes from the server's own count. **Stop** ends a test at once, even on a stalled connection.

## Data saver

Off by default. Turn it on in **Settings** to cut the app's own data use, at the cost of some features:

- no speed test
- connection quality only when you tap **Measure** (one round, about 2 KB)
- background checks every 60 s with the screen on (instead of 20 s) and every 10 min with it off (instead of 2 min)
- no router brand lookup (the router's page isn't downloaded; the gateway address is shown instead). Starlink detection still works.

## How it works

- Internet is checked with real HTTP probes (`generate_204`) bound to the Wi-Fi network.
- Checks run when Wi-Fi or the hotspot changes, when the screen turns on, and on light background alarms: every 20 s with the screen on, every 2 min with it off. A screen-off alarm doesn't wake the phone; it fires when the phone next wakes.
- While the hotspot is on, the app checks the connection being shared (usually mobile data).
- Starlink is detected automatically, without looking at the network name. The app sends the SpaceX device API's `get_status` request (gRPC-web, `SpaceX.API.Device.Device/Handle`) to the dish at `192.168.100.1:9201` and to the router at `<gateway>:9001`, and accepts only a genuine reply: a message, or status OK or PermissionDenied, but never "Unimplemented". It also checks that the dish's native gRPC port `9200` completes an HTTP/2 handshake; a merely open port doesn't count. The whole detection takes at most about 3 s. The app needs no location permission.
- If the phone blocks the app's background network access, the app doesn't report an outage. It keeps the last result for up to 2 minutes, then shows grey "Checking…". The same goes for failures while Android still says the Wi-Fi works: the app reports an outage only if they last a full minute.
- For reliable background checks, turn off battery optimization for WiFi Dot. The app has a shortcut for this.
- Known limitation: while the app isn't running, Android only wakes it when a Wi-Fi network connects, not when one drops. A drop is noticed at the next check, which can be up to 20 s with the screen on, or when you next wake the phone.

Requires Android 6.0+. The Quick Settings tile requires Android 7.0+.

## Building

No Gradle needed. The build uses the Android SDK tools packaged in Ubuntu/Debian:

```sh
sudo apt install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange openjdk-17-jdk
./build.sh          # → WiFiDot.apk
```

`build.sh` runs `aapt → javac → dx → zipalign → apksigner`. Newer APIs (Quick Settings tile, pinning widgets and tiles) are compiled against the small stubs in `stubs/` and called with version checks or reflection.
Icons are generated by `tools/make_icons.py`.
A signing key `wifidot.keystore` is generated on the first build and is not committed.

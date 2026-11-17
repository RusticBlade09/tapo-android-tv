# TapoATV — a light Android TV viewer for Tapo cameras

A small, fast Android TV app that shows TP-Link Tapo cameras on your home network, plays the recordings kept on a
Tapo **H500 HomeBase**, and controls pan/tilt — made because the official Tapo app is too heavy for cheap TV boxes.
Everything is **local**: the app talks straight to the cameras and the hub on your LAN, with no cloud calls.

> Not affiliated with, endorsed by or supported by TP-Link. "Tapo" is a TP-Link trademark, used here only to say what
> the app works with.

## Features
- **Grid of 4 live cameras** (VGA stream to spare the CPU); more cameras = **dashboards** (pages of 4, ◀ ▶ / CH+ CH−).
- **Full screen in HD** with **camera sound**; arrow keys = **pan / tilt**.
- **Recordings from the H500 HomeBase** in a right-hand panel: pick a day, thumbnails, event icons (person, vehicle, pet,
  motion, line crossing, tampering), newest/oldest first, "Last watched" badge. Play / pause, ½× and ¼× slow motion.
- **Double OK** = play the newest recording at once.
- **Hold OK** in full screen = dock: screenshot, record to the box, camera microphone on/off, neighbour cameras,
  **saved positions** (PTZ presets on the camera: create, go to, rename, re-aim, delete).
- **Events**: when the HomeBase logs a detection, that camera opens big (or its neighbour group side by side), with
  the event type, then returns to the grid by itself.

## Remote keys
| Where | Key | Does |
|---|---|---|
| Grid | OK | camera full screen (double OK = newest recording) |
| Full screen | arrows | pan / tilt |
| Full screen | OK | recordings panel |
| Full screen | hold OK | dock |
| Recording | OK / ◀ / ▶ / ▲ | pause-play / slower / normal / recordings |
| Anywhere | BACK | one step back · MENU = password + rescan |

## Requirements
- An Android TV box on **Android 9+** (minSdk 28). Built and tested on an Amlogic box running Android 10.
- Tapo cameras on the same network. In the Tapo phone app: **Me → Tapo Lab → Third-Party Compatibility = ON**
  (newer firmware refuses local logins without it).
- Your **Tapo account (cloud) password**. The app asks once and stores only its MD5/SHA-256 hashes, never the password.
- Optional: a Tapo **H500 HomeBase** for recordings and events (the cameras need no SD card).

Tested with 2× C530WS (fw 1.4.1) and an H500 (fw 1.3.20). Other models may work; firmware that only offers the newest
login (TPAP, error −40211) is not supported yet.

## Build
Android Studio, or from the command line (JDK 17+):
```
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Gradle 8.10 · AGP 8.7 · Kotlin 2.0 · Media3 (ExoPlayer) 1.4. No other dependencies.

## How it works (short)
- **Video**: port 8800, digest login, AES-encrypted MPEG-TS parts (like go2rtc's `tapo://`) → ExoPlayer. Tapo H.264 has
  no AUD units, so the TS extractor runs with `FLAG_DETECT_ACCESS_UNITS`; the G.711 A-law sound (stream type 0x90) has
  its own small reader.
- **Control**: port 443, the same secure-passthrough API pytapo uses (PTZ, presets, microphone, device info).
- **Recordings**: the H500 lists its cameras via `getGeneralDeviceList`, clips via `searchVideoWithUTC`, events via
  `searchDetectionList`, and streams a clip with `type=download` on 8800. The hub allows one media session at a time.

`tools/` holds the Python probe scripts used to work the protocol out (they ask for the password; nothing is stored).

## Thanks
Protocol knowledge from [pytapo](https://github.com/JurajNyiri/pytapo), [go2rtc](https://github.com/AlexxIT/go2rtc),
[tapo-h500-local-recordings](https://github.com/Sujeom/tapo-h500-local-recordings) and the
[tapo](https://github.com/mihai-dinculescu/tapo) Rust crate.

## Licence
[MIT](LICENSE)

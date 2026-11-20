# BD Futsal Cam Pro — Android

Native Android version of the BD Futsal Cam Pro web app: record a futsal match
with two phones on one mount, one filming the LEFT half and one the RIGHT.

## Features

| Tab | What it does |
| --- | --- |
| **Capture** | Rear-camera preview and recording (CameraX). LEFT/RIGHT role, 720p / 1080p / 4K (only the qualities the phone supports are offered), 0.5× ultra-wide when the phone has one, microphone on/off. **Record now**, **Arm timed start**, **Stop & save**. |
| **Match** | Create a match (name + 90 s–10 min countdown) and share it to the other phone as a `bdfutsalcam://match?...` link. Tapping the link opens the app and joins; or paste the whole shared message and tap **Join**. |
| **Library** | Every clip from the app, with thumbnail, role, length, size and exact recorded start time. Play, share, delete, or send to the Sync lab. |
| **Sync lab** | Load LEFT and RIGHT clips, set the right camera's offset (slider, ±1 s, ±1 frame, or *Use recorded start times*), play both locked together, choose which audio to hear, export the alignment as JSON. |

### Differences from the iOS Safari web app

- Clips are saved as normal MP4 files in **Movies/BDFutsalCam** (shared storage), not in
  browser storage, so they can't be evicted and are reachable from Gallery, Files or USB.
- The screen is kept awake automatically. There's no need to change Auto-Lock.
- **Start beep:** with a timed start, each phone beeps just after it starts recording. With
  the phones side by side, both microphones hear both beeps, which gives a shared audio
  reference for alignment.
- Each clip stores the wall-clock time recording actually began, so the Sync lab can
  suggest an offset straight away.
- If a timed start fires more than a second late, the app says so.

## Limitations

- No pairing server: each phone starts on its own clock, so the start is only as close as
  the two clocks (normally a fraction of a second). Use the clap or beeps to fine-tune.
- Leaving the app (Home, a phone call taking over the screen) stops the camera and finalizes
  the clip. Use Do Not Disturb during matches.
- No automatic stitching yet; the Sync lab only previews the alignment.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
cd futsal-cam-pro
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or open the folder in Android Studio
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
```

Minimum Android version: 8.0 (API 26). Target: API 35.

## Code map

```
app/src/main/java/com/bdfutsal/campro/
  MainActivity.kt          header, tabs, permissions, match-link handling, keep-screen-on
  AppViewModel.kt          app state (settings, session, library, sync lab)
  camera/CameraStation.kt  CameraX binding, recording, timed start, start beep, zoom
  data/MatchSession.kt     match link create/parse (unit-tested)
  data/AppPrefs.kt         saved settings
  data/ClipRepository.kt   MediaStore clip listing and per-clip metadata
  ui/                      Compose screens: Capture, Session, Library, SyncLab
```

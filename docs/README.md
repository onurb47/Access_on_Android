# BD Futsal Cam Pro

Two-iPhone match recording for BD Futsal. It runs in Safari, so you don't need a Mac, Xcode, the App Store or TestFlight.

| Phone | Role | Records |
|---|---|---|
| iPhone 17 Pro Max | **Director**: starts and stops both phones, tags moments | CAM 02 · RIGHT half |
| iPhone 15 Pro Max | **Camera**: follows the Director | CAM 01 · LEFT half |

Both phones sit on the Veo Go mount at the halfway line. Each one records its half of the court on the 0.5× ultra-wide lens, in landscape, and keeps the video on that phone.

## One-time setup: put the app online (2 minutes, can be done on an iPhone)

Safari only allows camera access on an `https://` page, so the app has to be hosted. GitHub Pages does this for free from this repository:

1. Open **github.com/onurb47/Access_on_Android** → **Settings** → **Pages**.
2. Under *Build and deployment* choose **Source: Deploy from a branch**.
3. Pick branch **`claude/marke-this-kl7jsp`** and folder **`/docs`**, then tap **Save**.
4. After about a minute the app is live at **https://onurb47.github.io/Access_on_Android/**

On each iPhone, open that address in Safari. For a full-screen app, tap **Share → Add to Home Screen**.

## Match day

1. Work through the **Pre-match checklist** on the home screen on both phones. The important items are Auto-Lock **Never**, Portrait Orientation Lock **off** and Do Not Disturb **on**.
2. **iPhone 17 Pro Max → Director.** Allow the camera and microphone. Tap **Pair** to show a QR code and a 5-letter code.
3. **iPhone 15 Pro Max:** scan that QR code with the normal Camera app and open the link. Alternatively, open the app, choose **Camera** and type the code.
   Both phones need internet only for this pairing step. Use the same Wi-Fi, or join the Camera phone to the Director's Personal Hotspot. After pairing, the phones talk to each other directly.
4. Mount both phones in landscape. The Director shows a live thumbnail of the other camera's view. Angle the phones so the two pictures overlap across the centre circle.
5. Set a **Match name** in Settings, for example `BD vs City Uni — H1`. The Camera phone gets it automatically.
6. Tap **Start both**. Both phones start recording at the same agreed instant, and the Director shows how many milliseconds apart they actually started. About 2 seconds in, the Director plays two short beeps. Clap once in front of the mount as well; this gives an exact point to line the two videos up later.
7. While recording, the Director can tap **BD goal / Opp goal / Shot / Chance / Mark** to save timestamps.
8. Tap **Dim** to black the screen and save battery. Tap the screen to wake it.
9. **Stop both** at half-time. Start a new recording for the second half.

## Getting the videos off the phones

Open **Recordings** on each phone and tap a recording:

- **Save to Photos / AirDrop** opens the iOS share sheet. Choose *Save Video*, or AirDrop the file, for example the CAM 01 file to the Director phone or to a Mac.
- **Download to Files** saves to Files → Downloads.
- **Export tags (.csv)** saves the tagged moments, with their times in the video.

Both phones use the same timestamp in their file names, so each pair sorts together:

```
BDFutsal_BD-vs-City-Uni-H1_2026-11-21_1930_CAM01-LEFT.mp4
BDFutsal_BD-vs-City-Uni-H1_2026-11-21_1930_CAM02-RIGHT.mp4
```

Video stays inside Safari's storage until you save it. Save each match to Photos afterwards. iOS can clear website data when a phone runs very low on space.

## What happens if something goes wrong

| Situation | What the app does |
|---|---|
| The phones lose their connection mid-recording | Both keep recording. The Camera reconnects by itself, and the Director can then stop both as normal. |
| The Camera phone reloads or crashes mid-recording | The video recorded up to that point is recovered. When it rejoins, the Director shows **Start it now**. The new file notes how far into the Director's video it begins. |
| Safari leaves the screen (a call, the home button) | iOS pauses the camera. A warning appears on the Director. The checklist items prevent this. |
| A phone runs out of space | Recording stops, and everything recorded so far is kept. The storage chip shows minutes left at the current quality. |
| No internet at the venue | Each phone can still record on its own: the Director with **Start this phone**, the Camera with **Record here**. Pairing needs internet once. |

## Storage and battery

| Quality | Per phone, per hour |
|---|---|
| 4K (3840×2160, 30 fps) | about 11 GB |
| 1080p | about 4.5 GB |
| 720p | about 2.3 GB |

The format chip at the top shows the resolution the lens actually delivered. If Safari gives less than 4K, the chip turns yellow. Use a power bank for full matches.

## How it works

- **Capture.** Safari's camera access on the rear ultra-wide lens records H.264 MP4 at about 24 Mbit/s for 4K. The video is written to on-device storage in roughly one-second pieces as it records, so a full half never has to fit in memory.
- **Synchronised start.** The Camera keeps measuring the clock difference to the Director, about every 2 seconds. When you press Start, the Director picks a moment 1.5 s ahead and both phones start at that moment, each by its own clock. The Director then reports how far apart the two starts were.
- **Pairing.** The phones connect over WebRTC through [PeerJS](https://peerjs.com). The free public PeerJS server only introduces the two phones; no video passes through it. To use your own server instead, add `?peer=your-host:port/path` to the address.
- **Offline.** After the first visit the app opens without signal, but pairing still needs a connection.

The code is plain HTML, CSS and JavaScript in this folder, with no build step. `js/app.js` holds the screens, `js/camera.js` capture and recording, `js/link.js` pairing and clock sync, and `js/store.js` on-device storage. The two libraries it uses are bundled in `vendor/`.

## Next steps

1. **Panoramic stitching.** Combine the LEFT and RIGHT files into one wide view of the court, lined up with the sync beep or clap. This runs on a computer.
2. **AI follow-cam.** Track the ball and players, then pan and zoom inside the panorama.
3. **Highlights.** Cut clips automatically from the tags exported with each recording.

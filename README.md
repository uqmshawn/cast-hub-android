# Cast Hub for Android

Play the videos on your phone on a Chromecast — including the original 2013 (1st-gen) model.

## Install
On your phone, open:
**https://github.com/uqmshawn/cast-hub-android/releases/latest/download/CastHub.apk**

Open the downloaded file and allow "Install unknown apps" for your browser when asked.
New builds install over the old one.

## Use
1. Phone and Chromecast on the same Wi-Fi.
2. Open Cast Hub, allow access to videos.
3. Tap the cast icon (top right) → pick your TV.
4. Tap a video. The full-screen remote opens: pause, seek, ±30 s, volume, subtitles.

- **✓ Ready** videos play on the 1st-gen Chromecast. **⚠** videos (H.265/x265, 4K, 10-bit, AC3/DTS sound, MKV) may not —
  convert them with the PC version of Cast Hub first.
- **CC** button: attach an `.srt` / `.vtt` subtitle file to a video.
- **Cast a web link**: direct `.mp4` / `.m3u8` links. You can also *Share* a link to Cast Hub from other apps.
- The phone streams the file to the TV, so keep it on Wi-Fi. A notification with a **Stop** button shows while streaming.

## How it works
- Lists videos with Android's MediaStore and checks each one's codecs with `MediaExtractor`.
- A small built-in web server (NanoHTTPD, with byte-range support for seeking) serves the file over Wi-Fi,
  behind a random per-session token.
- Google's Cast SDK tells the Chromecast's built-in media player to stream from that address.
- A foreground service keeps streaming alive with the screen off.

Built by GitHub Actions on every push to `main` (`.github/workflows/build.yml`).
The signing key in `app/casthub.keystore` is a personal sideload key, not for Play Store publishing.

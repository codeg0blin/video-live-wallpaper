# Video Live Wallpaper

A small Android app that lets you pick any video from your device and set
it as a looping live wallpaper.

## Features

- Pick a video with the system file picker
- Preview it looping (muted) before setting it
- Adjustable playback speed (0.25x–2.0x)
- Fill or fit crop mode
- One-tap wallpaper setting — applies to both home and lock screen
- No ads, no analytics, no network access of any kind

## How it works

The app implements an Android `WallpaperService` that loops your chosen
video using `MediaPlayer`. Setting the wallpaper goes through Android's own
system picker (`ACTION_CHANGE_LIVE_WALLPAPER`) — this is required by the
OS for all live wallpaper apps, not just this one.

Live wallpapers on Android apply to both the home screen and lock screen
simultaneously. Independent control per surface is not supported by the
Android live wallpaper API on any device.

## Building

Open the project in Android Studio (or run `./gradlew assembleDebug` /
`assembleRelease` from the command line). Requires Android SDK 34 and
min SDK 26 (Android 8.0+).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

See [ATTRIBUTION.md](ATTRIBUTION.md) for a full list of the open-source
libraries this app depends on, and a note on how the source code itself
was written.

# Video Live Wallpaper

A small Android app that lets you pick any video from your device and set
it as a looping live wallpaper, with separate buttons for the home screen
and lock screen.

## Features

- Pick a video with the system file picker
- Preview it looping (muted) before setting it
- Set as home screen wallpaper
- Set as lock screen wallpaper
- No ads, no analytics, no network access of any kind

## How it works

The app implements an Android `WallpaperService` that loops your chosen
video using `MediaPlayer`. Setting the wallpaper goes through Android's own
system picker (`ACTION_CHANGE_LIVE_WALLPAPER`) — this is required by the
OS for all live wallpaper apps, not just this one.

Note: whether your launcher offers separate "Home" / "Lock screen" / "Both"
options when you tap one of the buttons is controlled by your device's
launcher, not by this app. Some OEM launchers (e.g. some TCL, Samsung, or
MIUI builds) only offer a combined "Home and lock screen" option for live
wallpapers.

## Building

Open the project in Android Studio (or run `./gradlew assembleDebug` /
`assembleRelease` from the command line). Requires Android SDK 34 and
min SDK 26 (Android 8.0+).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

See [ATTRIBUTION.md](ATTRIBUTION.md) for a full list of the open-source
libraries this app depends on, and a note on how the source code itself
was written.

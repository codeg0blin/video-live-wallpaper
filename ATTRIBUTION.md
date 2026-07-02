# Attribution & Open Source Notices

This document lists everything this app is built on, for transparency and
because it's a prerequisite for F-Droid submission. It also notes how the
app's own source code was produced.

## How the app's source code was written

This app's source code (`MainActivity.kt`, `VideoWallpaperService.kt`, all
layout/resource XML, and the Gradle build files) was written specifically
for this project with the help of an AI assistant (Claude, by Anthropic),
based on a plain-language description of the desired feature set. No code
was copied from a tutorial, blog post, or another app's repository. All of
it is original to this project and is released under the license in
`LICENSE` (Apache License 2.0).

It uses standard, publicly documented Android SDK and AndroidX APIs in the
ordinary way any Android app would — see the dependency list below for
exactly which libraries are linked in.

## Runtime dependencies (linked into the app)

All of the following are open source and Apache License 2.0, pulled
automatically by Gradle from Google's Maven repository (no source code
copied into this repo):

| Library | Version | License | Purpose |
|---|---|---|---|
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx/releases/core) | 1.13.1 | Apache 2.0 | Kotlin extensions for Android framework APIs |
| [androidx.appcompat:appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.0 | Apache 2.0 | Backward-compatible UI components |
| [com.google.android.material:material](https://github.com/material-components/material-components-android) | 1.12.0 | Apache 2.0 | Material Design buttons/theming |
| [androidx.constraintlayout:constraintlayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) | 2.1.4 | Apache 2.0 | Layout engine for `activity_main.xml` |
| [androidx.activity:activity-ktx](https://developer.android.com/jetpack/androidx/releases/activity) | 1.9.0 | Apache 2.0 | `ActivityResultContracts` file-picker API |
| [androidx.cardview:cardview](https://developer.android.com/jetpack/androidx/releases/cardview) | 1.0.0 | Apache 2.0 | Rounded preview card |
| [androidx.preference:preference-ktx](https://developer.android.com/jetpack/androidx/releases/preference) | 1.2.1 | Apache 2.0 | Reading/writing saved settings (video URI, speed, crop mode) |

## Build tooling

| Tool | Version | License |
|---|---|---|
| [Android Gradle Plugin](https://developer.android.com/build) | 8.5.2 | Apache 2.0 |
| [Kotlin / Kotlin Android plugin](https://github.com/JetBrains/kotlin) | 1.9.24 | Apache 2.0 |
| [Gradle](https://gradle.org/) | (wrapper-managed) | Apache 2.0 |

## Android platform APIs used (no extra license — part of the OS, not a dependency)

These are not bundled into the app; they're system services every Android
device already provides:

- `android.service.wallpaper.WallpaperService` — the live wallpaper engine
- `android.media.MediaPlayer` — video decode/playback
- `android.app.WallpaperManager` — system "set wallpaper" intent
- `android.content.ContentResolver` (`takePersistableUriPermission`) — durable file access from the picker

## What this app does NOT include

For transparency, since this matters for F-Droid's review and for anyone
auditing the repo:

- No analytics or telemetry SDKs (no Firebase, no Crashlytics, etc.)
- No advertising SDKs or ad network code
- No network permissions of any kind — the app never connects to the internet
- No proprietary/closed-source libraries
- No tracking of any kind; the only persisted data is the URI of the video
  you pick, stored locally in `SharedPreferences` on your own device

## License

This project is licensed under the **Apache License 2.0** — see `LICENSE`.

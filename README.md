# GameAdsSuck

[![Build](https://github.com/applehat/GameAdsSuck/actions/workflows/build.yml/badge.svg)](https://github.com/applehat/GameAdsSuck/actions/workflows/build.yml)

An Android app that watches your mobile games and automatically kills and relaunches them the moment an advertisement interstitial appears — so you never have to sit through an intrusive ad again.

---

## How it works

1. **Add games to the watch list** — open GameAdsSuck, tap **+**, and pick the games you want to protect.
2. **Enable the Accessibility Service** — tap the red status banner and turn on *GameAdsSuck Ad Detector* in your device's Accessibility Settings.
3. **Play your games** — GameAdsSuck runs silently in the background. When it detects an ad interstitial (by recognising known ad-SDK packages or activity class names), it:
   - Navigates to the home screen.
   - Terminates the game process.
   - Immediately relaunches the game.

The ad never gets a chance to play.

---

## Features

| Feature | Details |
|---|---|
| App watch list | Add / remove any installed app |
| Ad SDK detection | Recognises 17+ ad networks (Unity Ads, AdMob, AppLovin, Vungle, IronSource, …) |
| Class-name heuristics | Also catches ads shown inside the game's own process |
| Cooldown | 5-second minimum between actions to avoid rapid loops |
| Status banner | One-tap access to Accessibility Settings |
| Notifications | Persistent "watching" notification; per-event "ad detected" flash |

---

## Permissions

| Permission | Why |
|---|---|
| `KILL_BACKGROUND_PROCESSES` | Terminate the game after it moves to the background |
| `QUERY_ALL_PACKAGES` | List installed apps in the picker |
| `FOREGROUND_SERVICE` | Keep the detector running reliably |
| Accessibility Service | Observe window state changes to detect ad interstitials |

---

## Installing a pre-built APK

Pre-built APKs are attached to every [GitHub Release](https://github.com/applehat/GameAdsSuck/releases).
To install:
1. Open the latest release on GitHub.
2. Download `GameAdsSuck-<version>.apk` from the **Assets** section.
3. Transfer the file to your Android device and open it. You may need to allow **Install from unknown sources** in your device settings.

---

## CI / GitHub Actions

| Workflow | Trigger | Output |
|---|---|---|
| **Build** | Every push and pull request | Debug APK uploaded as a build artifact (14-day retention) |
| **Release** | GitHub Release published | Release APK attached to the release as a downloadable asset |

The release APK is signed with the debug signing key so it can be sideloaded on any Android device.

---

## Building

Requirements: Android Studio Hedgehog (or newer) / Gradle 8.2 / JDK 17.

```bash
# Generate the Gradle wrapper if not already present
gradle wrapper --gradle-version 8.2

# Build a debug APK
./gradlew assembleDebug
```

The generated APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Project structure

```
app/src/main/
├── java/com/gameadssuck/
│   ├── AdDetectorService.kt    # AccessibilityService — detects ads, kills & relaunches
│   ├── AppPickerActivity.kt    # Searchable list of installed apps
│   ├── AppPickerAdapter.kt     # RecyclerView adapter for app picker
│   ├── MainActivity.kt         # Home screen — watch list + service status
│   ├── WatchedAppsAdapter.kt   # RecyclerView adapter for watched apps
│   └── WatchedAppsManager.kt   # SharedPreferences-backed watch list storage
└── res/
    ├── layout/                 # XML layouts
    ├── values/                 # Strings, colours, themes
    └── xml/
        └── accessibility_service_config.xml
```

---

## Limitations

* **No root required**, but the app can only *terminate background processes*. It first sends the game to the background (HOME action) before terminating it, so the sequence is: home → terminate → relaunch.
* Game progress that is not cloud-saved will be lost when the process is terminated — this is a deliberate trade-off to skip the ad.
* Ad detection relies on known SDK package names and activity class-name heuristics. New or obscure ad SDKs may not be detected until their identifiers are added to `AdDetectorService.AD_SDK_PACKAGES` / `AD_ACTIVITY_KEYWORDS`.


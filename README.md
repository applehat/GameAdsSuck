# GameAdsSuck

[![Build](https://github.com/applehat/GameAdsSuck/actions/workflows/build.yml/badge.svg)](https://github.com/applehat/GameAdsSuck/actions/workflows/build.yml)

An Android app that watches your mobile games and tries to dismiss advertisement overlays using accessibility actions that still work on modern Android.

---

## How it works

1. **Add games to the watch list** — open GameAdsSuck, tap **+**, and pick the games you want to protect.
2. **Enable the Accessibility Service** — tap the red status banner and turn on *GameAdsSuck Ad Detector* in your device's Accessibility Settings.
3. **Play your games** — GameAdsSuck runs in the background. When it detects an ad interstitial, it tries to:
   - Click visible **close** / **skip** controls inside the ad UI.
   - Fall back to the Android **Back** action when no dismiss control is available.
   - Use **Home** only as a last resort.

This keeps the app aligned with what Android 14/15 still allows third-party accessibility tools to do.

---

## Features

| Feature | Details |
|---|---|
| App watch list | Add / remove any installed app |
| Ad SDK detection | Recognises common ad SDK package prefixes and ad activity names |
| In-app dismissal | Tries to click close / skip / dismiss controls in the active window |
| Back fallback | Uses Android Back when no actionable dismiss control is found |
| Cooldown | 5-second minimum between actions to avoid rapid loops |
| Status banner | One-tap access to Accessibility Settings |
| Notifications | Optional status + ad-detected notifications |

---

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Show optional status and ad-detected notifications on Android 13+ |
| Package visibility `<queries>` | List launcher apps in the picker without broad package access |
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

The debug build uploaded by CI is the easiest way to try a PR before merge: open the PR's **Build** workflow run and download the `GameAdsSuck-debug` artifact.

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
│   ├── AdDetectorService.kt    # AccessibilityService — detects ads and tries to dismiss them
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

* **No root required**. The app only uses accessibility actions that normal apps can still perform on Android 14/15.
* Some ads deliberately hide or delay their dismiss buttons. In those cases the app falls back to Back, which may not work for every game or every ad SDK.
* Ad detection still relies on heuristics plus visible UI structure. New or obscure ad SDKs may not be detected until their identifiers are added to `AdDetectorService`.

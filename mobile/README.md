# AI Notebook: phone apps

Kotlin Multiplatform: native UI on each platform, shared Kotlin logic.

- `composeApp/`: Android app (Jetpack Compose, foreground recording service, notifications).
- `iosApp/`: iOS app (SwiftUI, background audio, Live Activity in the `RecorderWidget` target).
  Open `iosApp/iosApp.xcworkspace`, not the `.xcodeproj`.
- `shared/`: recording queue (SQLDelight), chunked upload logic, planned length rules, models, strings.

Sign-in is Firebase Auth (project `team-akshay-sharma`); everything else goes to the `ntApi` Cloud Function
with the Firebase ID token. See the README at the repository root.

## API address

- Android: `BuildConfig.API_BASE_URL`, set in `composeApp/build.gradle.kts`; override with
  `./gradlew -PapiBaseUrl=https://... :composeApp:assembleDebug`.
- iOS: `ApiBaseUrl` in `iosApp/iosApp/Info.plist`, filled from `API_BASE_URL` in `iosApp/Configuration/Config.xcconfig`.

## Firebase config files (not committed)

| File | Place at |
|---|---|
| `google-services.json` | `composeApp/google-services.json` |
| `GoogleService-Info.plist` | `iosApp/iosApp/GoogleService-Info.plist` |

Download commands are in the root README.

## Build

- Android: `./gradlew :composeApp:assembleDebug` (or `installDebug`).
- iOS: `./gradlew :shared:generateDummyFramework` once, then `cd iosApp && pod install`
  (needs `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`), open the workspace in Xcode and Run.

## Fast test mode (debug builds)

Long-press the "AI Notebook" title on the recorder screen to shrink the planned lengths to minutes, so the warning,
Keep recording and the automatic stop can be tried quickly. It turns off when the app restarts.

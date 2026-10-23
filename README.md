# SpareBeam

**Your spare phone. Your own server.** SpareBeam turns an Android phone into a private media server for devices on the same Wi-Fi network.

## Current status

This first implementation slice establishes the Android app, folder selection through Android's Storage Access Framework, foreground service lifecycle, an embedded HTTP server, and range parsing for seeking. The browser library and end-to-end validation are still in progress.

## Build

Open this project in Android Studio (Android Gradle Plugin 8.7.3, JDK 17, Android SDK 35), then run `:app:assembleDebug` and `:app:testDebugUnitTest`.

## Privacy and safety

- Folders are selected explicitly by the user and use persisted URI access.
- The server binds to the local network on port 8080 and exposes read-only media routes.
- There are no accounts, ads, cloud services, or remote deletion features.
- Do not forward the local server address outside your trusted network.

## Known limitations

- Pairing/PIN protection is not implemented yet; use only on a trusted home Wi-Fi network.
- Android device testing is required for foreground-service behavior and SAF compatibility.
- The web library currently lists common files and relies on browser-native playback; device/browser format support varies.

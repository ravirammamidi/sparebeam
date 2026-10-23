# SpareBeam

**Your spare phone. Your own server.** SpareBeam turns an Android phone into a private media server for devices on the same Wi-Fi network.

## Current status

The app supports folder selection through Android's Storage Access Framework, a foreground service, and a browser media library with HTTP range streaming for seeking. Browsers must pair with the six-digit code shown on the host phone. Pairing creates an HttpOnly same-site session cookie; sessions expire after 12 hours and repeated incorrect codes trigger a short lockout.

## Build

Open this project in Android Studio (Android Gradle Plugin 8.7.3, JDK 17, Android SDK 35), then run `:app:assembleDebug` and `:app:testDebugUnitTest`.

## Privacy and safety

- Folders are selected explicitly by the user and use persisted URI access.
- The server binds to the local network on port 8080 and exposes read-only media routes only to paired browsers.
- There are no accounts, ads, cloud services, or remote deletion features.
- Traffic uses HTTP on the local network; use SpareBeam only on a trusted Wi-Fi network and do not forward its address outside that network.

## Known limitations

- Android device testing is still required for foreground-service behavior, pairing in mobile browsers, and SAF compatibility.
- The web library currently lists common files and relies on browser-native playback; device/browser format support varies.

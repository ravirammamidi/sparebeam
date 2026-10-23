# SpareBeam

**Your spare phone. Your own server.** SpareBeam turns an Android phone into a private media server for devices on the same Wi-Fi network.

## Current status

The app supports folder selection through Android's Storage Access Framework, importing user-selected photos and videos into private SpareBeam folders, a foreground service, and a browser media library with HTTP range streaming for seeking. Browsers must pair with the six-digit code shown on the host phone. Pairing creates an HttpOnly same-site session cookie; sessions expire after 12 hours and repeated incorrect codes trigger a short lockout.

## Build

Open this project in Android Studio (Android Gradle Plugin 8.7.3, JDK 17, Android SDK 35), then run `:app:assembleDebug` and `:app:testDebugUnitTest`.

## Privacy and safety

- Folders are selected explicitly by the user and use persisted URI access.
- Imported copies are kept in SpareBeam's private app storage. The system media picker limits selection to items the user chooses; originals are left unchanged. Copies are removed if SpareBeam is uninstalled or its app data is cleared.
- The server binds only to the phone's current Wi-Fi IPv4 address on port 8080, rather than listening on every network interface. Media routes are read-only and require a paired browser.
- There are no accounts, ads, cloud services, or remote deletion features.
- Traffic uses HTTP on the local network, so a trusted Wi-Fi network is required. Do not forward the address outside that network. Pairing controls access through the app, but HTTP does not encrypt media or session cookies against someone who can monitor the same network.

## Known limitations

- Android device testing is still required for foreground-service behavior, pairing in mobile browsers, and SAF compatibility.
- The server uses the Wi-Fi address detected at startup. If the phone changes networks, stop and start SpareBeam again.
- The web library currently lists common files and relies on browser-native playback; device/browser format support varies.

# SpareBeam

**Your spare phone. Your own server.** SpareBeam turns an Android phone into a private media server for devices on the same Wi-Fi network.

## What it does

- Share an existing folder by granting SpareBeam read-only access to the folder you choose.
- Create folders in SpareBeam's private app storage and copy in photos and videos you select with Android's system media picker. The originals are unchanged.
- Browse the shared and imported media from another device's browser. Pair the browser with the six-digit code shown on the host phone.
- Stream media over your local Wi-Fi, including byte-range requests used for video seeking.

## Install from source

There is no prebuilt APK release yet. Build and install the debug app from this repository.

### Requirements

- Android phone or tablet running Android 8.0 (API 26) or later.
- JDK 17, Gradle 8.9, and Android SDK Platform 35 with Build Tools 35.0.0.
- For USB installation, Android Debug Bridge (ADB) and USB debugging enabled on the device.

### Build and install

1. Clone this repository and open a terminal in the project folder:

   ```sh
   git clone https://github.com/ravirammamidi/sparebeam.git
   cd sparebeam
   ```

2. Install the requirements above. If you use Android Studio, install Android SDK Platform 35 and Build Tools 35.0.0 from **Tools → SDK Manager**; install JDK 17 and Gradle 8.9 separately if they are not already available.

3. Build the APK and run the unit tests:

   ```sh
   gradle assembleDebug testDebugUnitTest
   ```

4. Connect an Android device with USB debugging enabled and install the APK:

   ```sh
   gradle installDebug
   ```

   Or install the APK directly with ADB:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

GitHub Actions builds the app, compiles the Android instrumentation tests, runs unit tests, and uploads the debug APK as a seven-day Actions artifact. The hosted emulator has been unreliable, so instrumentation tests are not currently executed in CI.

## Use SpareBeam

### Add media

Choose either or both methods in the app:

**Share an existing folder:** Tap **Add folder** and choose a folder in Android's file picker. SpareBeam gets read-only access to that folder and its contents. Files stay in their original location.

**Copy selected photos or videos into SpareBeam:** Tap **Create folder**, enter a name, then tap **Add photos/videos** next to that folder. Choose the items in Android's media picker. SpareBeam copies only those selected items into its private app storage; it does not change the originals. These copies use space on the phone and are removed if the app is uninstalled or its data is cleared.

### Start sharing

1. Connect the host phone to Wi-Fi and tap **Start server**. The app shows its address and pairing code.
2. Connect the viewing device to the same trusted Wi-Fi network.
3. Open the shown `http://…:8080` address in a browser on that device.
4. Enter the six-digit code from the host phone. Browse the media library and open a photo, audio file, or video.
5. Tap **Stop server** on the host phone when you are finished sharing.

The browser session expires after 12 hours. Stopping and restarting the server also ends existing sessions, so pair the browser again.

## Test the app

Run the build and unit tests from the project folder:

```sh
gradle assembleDebug testDebugUnitTest
```

To compile the instrumentation tests as well:

```sh
gradle assembleDebugAndroidTest
```

Then test on a physical phone and a second device:

1. Create a SpareBeam folder and copy in one photo and one video. Confirm the originals are unchanged and the copied-item count updates.
2. Start the server, open its address from the second device, and pair with the code.
3. Confirm the copied files appear and can be opened; seek within the video.
4. Add another item with the same filename and verify the existing copy is not overwritten.
5. Cancel the media picker and confirm no additional item is copied.
6. Stop the server and confirm the media is no longer available; restart and pair again.
7. Restart the phone and confirm the imported folders and copies remain in SpareBeam.

The instrumentation smoke tests cover the home screen, private-folder creation, selected-file copying, and rejection of paths outside the private media directory. Run them on an emulator or Android device with:

```sh
gradle connectedDebugAndroidTest
```

A real-device check is still needed for Wi-Fi access from a separate browser device, system picker behavior, and media playback.

## Troubleshooting

- **Start server is unavailable:** Add at least one existing folder or create a SpareBeam folder first, and make sure Wi-Fi is connected.
- **The browser cannot connect:** Use the exact address shown in SpareBeam and check that both devices are on the same Wi-Fi. Guest networks may prevent devices from reaching each other. SpareBeam uses port `8080`.
- **Pairing is rejected:** Recheck the six-digit code on the host phone. Repeated incorrect attempts trigger a 60-second lockout.
- **New imports do not show in an already-open browser:** Refresh the browser page to reload the media list.
- **A file does not play:** Browser playback depends on the file format and the browser/device support.
- **The address changes after switching networks:** Stop and start the server again to bind to the phone's current Wi-Fi address.

## Privacy and safety

- Folder access is granted only after the user chooses a folder. Existing shared folders are read-only.
- Android's media picker gives SpareBeam access to the photos and videos the user selects, rather than the whole media library.
- Imported copies stay in the app's private storage. SpareBeam does not upload media to a cloud service.
- The server listens on the phone's current Wi-Fi IPv4 address, not every network interface. Browser access requires pairing; media routes are read-only.
- Traffic uses HTTP, so media and session cookies are not encrypted from someone monitoring the same network. Use a trusted Wi-Fi network and do not forward the address outside it.
- Stop the server when sharing is finished. Do not use SpareBeam on a public or untrusted Wi-Fi network.

## Known limitations

- Device testing is needed for foreground-service behavior, Android picker behavior, storage capacity, and playback across browsers.
- Imported copies currently remain in app storage until app data is cleared or SpareBeam is uninstalled; folder deletion is not yet available in the app.
- The web library relies on browser-native playback, so supported media formats vary by device and browser.

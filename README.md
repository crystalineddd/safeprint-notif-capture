# GCash Android Notification Capture (Flutter + Firebase)

This app listens to Android notifications and captures GCash messages in this pattern:

You received [amount] from [name] [number].

It extracts and saves:
- timestamp
- amount
- number

Records are also written to Firebase Firestore in the collection `gcash_notifications`.

## 1. Prerequisites (from scratch)

Install these first:
- Flutter SDK
- Android Studio (with Android SDK + platform tools)
- Java 17 (recommended by recent Android Gradle Plugin)
- A real Android phone with GCash installed (notification listeners are best tested on real devices)

Verify Flutter:

```bash
flutter doctor
```

## 2. Create the project (from scratch)

If you are starting from an empty folder:

```bash
flutter create --project-name notif_capture .
```

This generated the baseline Flutter files in this folder.

## 3. Firebase setup (required)

1. Create a Firebase project at https://console.firebase.google.com
2. Add an Android app with package name:
	 - `com.example.notif_capture`
3. Download `google-services.json`.
4. Place the file here:
	 - `android/app/google-services.json`
5. In Firebase Console, enable Firestore Database.
6. Start with test mode for initial testing (then lock down rules later).

## 4. Files added/edited in this project

- `lib/main.dart`
	- Flutter UI
	- Native bridge (MethodChannel/EventChannel)
	- Firebase initialization and Firestore write
- `pubspec.yaml`
	- Added `firebase_core` and `cloud_firestore`
- `android/app/src/main/kotlin/com/example/notif_capture/MainActivity.kt`
	- Configures channels between Android and Flutter
- `android/app/src/main/kotlin/com/example/notif_capture/NotificationCaptureService.kt`
	- Android NotificationListenerService
	- GCash parsing logic
	- Streams parsed payload to Flutter
- `android/app/src/main/AndroidManifest.xml`
	- Registers notification listener service + internet permission
- `android/settings.gradle.kts`
	- Adds Google services plugin version
- `android/app/build.gradle.kts`
	- Applies `com.google.gms.google-services`

## 5. Install dependencies

Run in project root:

```bash
flutter pub get
```

## 6. Run the app

```bash
flutter run
```

## 7. Grant notification listener access (important)

After app opens:
1. Tap `Open Notification Access Settings`
2. Find this app in the list and enable notification access
3. Return to app
4. Tap `Refresh Access Status`

Without this access, no notification can be captured.

## 8. Test with GCash notification

When a GCash notification arrives with text similar to:

You received PHP 1,000.00 from Juan Dela Cruz 09171234567.

The app captures and displays:
- amount (example: `PHP 1,000.00`)
- number (example: `09171234567`)
- timestamp (notification post time)

Then it writes to Firestore collection `gcash_notifications`.

## 9. Firestore document structure

Each document written by the app includes:
- `timestamp` (Firestore Timestamp)
- `timestampEpochMs` (number)
- `amount` (string)
- `number` (string)
- `rawText` (string)
- `packageName` (string)
- `capturedAt` (server timestamp)

## 10. Notes and limits

- Android-only capture flow is implemented.
- Parsing is pattern-based and expects the `You received ... from ... [number].` style.
- If GCash changes the notification format, update regex in:
	- `android/app/src/main/kotlin/com/example/notif_capture/NotificationCaptureService.kt`
- If your actual GCash package differs, update package matching in the same file.

## 11. Build release later

Before production:
- set your own `applicationId` in `android/app/build.gradle.kts`
- register that package in Firebase and download a matching `google-services.json`
- configure signing for release

# SafePrint GCash Android Notification Capture

SafePrint is a Flutter Android app that listens to notifications and captures GCash payment messages.

It displays payment history in-app and writes records to Firebase Firestore.

## Features

- Captures GCash-related notifications from Android Notification Listener
- Parses payment amount and contact number
- Preserves masked sender name format from notifications (for example: `CR*******E D.`)
- Shows payment history in expandable rows
- Stores captured records in Firestore collection `gcash_notifications`

## Tech Stack

- Flutter (Android)
- Kotlin (native notification listener)
- Firebase Core + Cloud Firestore

## Prerequisites

- Flutter SDK
- Android Studio (Android SDK + platform-tools)
- Java 17+
- Physical Android phone (recommended for notification-listener testing)

Verify setup:

```bash
flutter doctor
```

## Firebase Setup

1. Create a Firebase project at https://console.firebase.google.com
2. Add Android app package:
   - `com.safeprint.app`
3. Download `google-services.json`
4. Place it at:
   - `android/app/google-services.json`
5. Enable Firestore Database
6. For initial testing, you can start with test rules, then lock down later

## Install and Run

```bash
flutter pub get
flutter run
```

## Grant Notification Access

After app opens:

1. Tap `Open Access Settings`
2. Enable notification access for SafePrint
3. Return to the app

If capture is not working after updates/reinstall:

1. Toggle notification access OFF then ON
2. Force close and reopen SafePrint
3. Disable battery optimization for SafePrint (important on some devices)

## Expected Notification Pattern

Typical supported text style:

`You have received PHP 1.00 of Gcash from CR*******E D. 09XXXXXXXXX`

The parser is tolerant and still stores raw text when full parse is not possible.

## Firestore Output

Collection name:

- `gcash_notifications`

Each document currently stores:

- `amount` (string)
- `number` (string)
- `rawText` (string)
- `capturedAt` (server timestamp)

## Project Structure (Key Files)

- `lib/main.dart`
  - UI (SafePrint Payments)
  - MethodChannel/EventChannel bridge
  - Firebase initialization and Firestore writes
- `android/app/src/main/kotlin/com/safeprint/app/MainActivity.kt`
  - Android/Flutter bridge setup
- `android/app/src/main/kotlin/com/safeprint/app/NotificationCaptureService.kt`
  - Notification listener service
  - Parsing + payload publish to Flutter
- `android/app/src/main/AndroidManifest.xml`
  - Notification listener service declaration
- `android/app/build.gradle.kts`
  - Android config and Google Services plugin
- `android/settings.gradle.kts`
  - Plugin management

## Security Notes for Repository

Do not commit Firebase config or signing secrets.

Already ignored in `.gitignore`:

- `android/app/google-services.json`
- `android/key.properties`
- `**/*.jks`
- `**/*.keystore`
- `local.properties`

## Release Notes

Before publishing to Play Store:

1. Use your final `applicationId` in `android/app/build.gradle.kts`
2. Register the same package in Firebase
3. Use matching `google-services.json`
4. Configure release signing

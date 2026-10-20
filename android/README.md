# CrossNotify — Android Client

The official native Android client for **CrossNotify**, a self-hosted, end-to-end encrypted notification relay system.

CrossNotify allows an Android phone to act as a **Relay** (capturing incoming notifications from selected apps, encrypting them with AES-256-GCM, and publishing them to your private server) or as a **Subscriber** (receiving encrypted push notifications via Firebase Cloud Messaging and decrypting them on device), or **both** simultaneously with daisy-chain forwarding.

> [!IMPORTANT]
> **Universal APK — No compile-time credentials required**:
> The CrossNotify Android application does **not** bundle a `google-services.json` file and does not use the `google-services` Gradle plugin. All Firebase project credentials and server endpoints are configured **dynamically at runtime** when scanning server and subscriber QR codes. A single compiled APK works with any self-hosted CrossNotify server!

---

## Features

- **Zero-Knowledge Encryption**: Notifications are encrypted locally on the relay phone using **AES-256-GCM** with 256-bit random keys. Encryption keys are generated on device and shared strictly via in-person QR code scans. Neither your server nor Google FCM ever sees plaintext notifications or keys.
- **Dynamic Multi-Project Firebase Architecture**: Supports connecting to multiple independent self-hosted servers, each backed by its own Firebase project, without rebuilding the app.
- **Dual Delivery Modes**:
  - **`battery_saver` (default)**: FCM notification messages delivered directly by Google Play Services without waking the application process. Tray displays an encrypted placeholder (`Relay • App / Tap to decrypt`). Minimal battery impact.
  - **`immediate_preview`**: High-priority FCM data messages that wake `FirebaseMessagingService` momentarily to decrypt and post full local notifications immediately. Ideal for smartwatches, head units, lock screens, and daisy chaining.
- **Hardware-Backed Keystore Security**: Database credentials and E2E keys are encrypted at rest using an **Android Keystore** AES-256-GCM master key (`cn_master`), backed by StrongBox when available on supported hardware.
- **Biometric QR Reveal**: Sharing a channel to a subscriber requires biometric or device-credential verification (`BiometricPrompt`). The pairing QR code is displayed with `FLAG_SECURE` (preventing screenshots and screen recording) and automatically self-destructs after 60 seconds.
- **App Filtering**: Choose exactly which installed applications to forward per relay channel using an **Allow-list** or **Deny-list**.
- **Offline Outbox & Catch-up Sync**: Notifications sent while offline are queued in Room and delivered by **WorkManager** when network connectivity is restored. Subscribers catch up on missed notifications upon opening the app.
- **Daisy Chaining & Loop Prevention**: Forward incoming subscriptions to other relay channels across different servers with built-in hop counting and loop detection (maximum 3 hops).

---

## Technical Stack & Architecture

| Component | Technology |
| :--- | :--- |
| **Language & Tooling** | Kotlin 2.0.21, Gradle 8.11+, Android Gradle Plugin 8.7.3 |
| **UI Framework** | Jetpack Compose (BOM `2024.11.00`), Material 3, Navigation Compose `2.8.4` |
| **Dependency Injection** | Dagger Hilt `2.52` with Hilt Work `1.2.0` |
| **Local Database** | Room Persistence Library `2.6.1` with KSP compiler |
| **Background Processing** | AndroidX WorkManager `2.10.0` |
| **Networking** | Retrofit `2.11.0`, OkHttp `4.12.0`, `kotlinx.serialization` `1.7.3` |
| **Push Notifications** | Firebase Cloud Messaging (`firebase-bom:35.0.0`), dynamically initialized |
| **Barcode Scanning** | CameraX `1.4.0` + Google ML Kit Barcode Scanning `17.3.0` (offline model) |
| **QR Code Generation** | ZXing Core `3.5.3` |
| **Authentication** | AndroidX Biometric `1.1.0` |
| **Minimum SDK** | Android 8.0 (API level 26) |
| **Compile & Target SDK** | Android 16 (API level 36) |

---

## How to Compile the App

### 1. Prerequisites

Before building the app, make sure you have:
- **Java Development Kit (JDK)**: JDK 17 or higher (JDK 17 or JDK 21 recommended, e.g. OpenJDK or Amazon Corretto).
- **Android SDK**: Android SDK Platform 36 and Android SDK Build-Tools installed via Android Studio or the `sdkmanager` CLI.
- **Environment Variables**: `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing to your Android SDK directory.

Verify your Java version:
```bash
java -version
# Should output Java version 17 or higher
```

### 2. Configure Android SDK Path

Navigate to the `android/` directory:
```bash
cd android
```

If `ANDROID_HOME` is not set in your shell environment, create or update `local.properties`:

- **Linux / macOS**:
  ```properties
  sdk.dir=/home/username/Android/Sdk
  ```
- **Windows**:
  ```properties
  sdk.dir=C\:\\Users\\Username\\AppData\\Local\\Android\\Sdk
  ```

### 3. Compile the Debug APK

Run the Gradle wrapper to build the debug variant:

- **Linux / macOS**:
  ```bash
  ./gradlew assembleDebug
  ```
- **Windows (PowerShell / Command Prompt)**:
  ```powershell
  .\gradlew.bat assembleDebug
  ```

Once compilation finishes, the generated APK will be located at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Compile the Release APK

To compile an optimized release APK:

- **Linux / macOS**:
  ```bash
  ./gradlew assembleRelease
  ```
- **Windows (PowerShell / Command Prompt)**:
  ```powershell
  .\gradlew.bat assembleRelease
  ```

The unsigned release APK will be located at:
```
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

#### Signing the Release APK

To distribute or install the release APK on physical devices, sign it using `apksigner`:

1. **Create a keystore** (if you don't already have one):
   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias crossnotify
   ```

2. **Align and sign the APK**:
   ```bash
   zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
   apksigner sign --ks release.jks --ks-key-alias crossnotify --out CrossNotify-release.apk app-release-aligned.apk
   ```

Alternatively, configure `signingConfigs` in `app/build.gradle.kts` using environment variables or a secure keystore file.

### 5. Run Unit Tests

Execute the automated unit test suite (covering E2E crypto, QR payload parsing, and daisy-chaining policies):

- **Linux / macOS**:
  ```bash
  ./gradlew testDebugUnitTest
  ```
- **Windows**:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```

Test reports are generated in `android/app/build/reports/tests/testDebugUnitTest/index.html`.

### 6. Install Directly to a Connected Device

Connect your Android phone with **USB Debugging** enabled:
```bash
adb devices
```

Install the debug build directly:
```bash
# Via Gradle:
./gradlew installDebug

# Or via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## App Permissions & Setup

When opening the app for the first time, visit the **Permissions Health** section in the UI:

| Permission | Reason | Required Role |
| :--- | :--- | :--- |
| **Notification Listener** (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Reads notifications posted by installed apps so they can be encrypted and relayed. | **Relay only** |
| **Post Notifications** (`POST_NOTIFICATIONS`) | Displays local notifications (required on Android 13+). | **Subscriber** |
| **Camera** (`CAMERA`) | Scans pairing QR codes (`cn-relay` and `cn-sub`). | **Both** |
| **Ignore Battery Optimizations** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Prevents aggressive OEM battery managers from freezing the relay service when the screen is off. | **Relay (recommended)** |
| **Biometrics / Screen Lock** (`USE_BIOMETRIC`) | Confirms device owner identity before revealing subscriber pairing QR codes. | **Relay** |

> [!TIP]
> **Relay Devices on aggressive OEM skins (Xiaomi MIUI/HyperOS, Samsung OneUI, Huawei, OnePlus)**:
> In addition to ignoring battery optimizations in Android settings, ensure the app is locked in the recent apps menu and background autostart is enabled. Refer to [dontkillmyapp.com](https://dontkillmyapp.com) for device-specific instructions.

---

## How to Use the App

### Scenario A: Setting up a Relay Phone
1. Open the CrossNotify web dashboard on your server and click **Add relay phone**. Enter a name (e.g. `Work Phone`).
2. On the relay phone, open CrossNotify, navigate to the **Relay** tab, and tap **Add relay channel**.
3. Scan the `cn-relay` QR code displayed on the server dashboard.
4. Review the server URL and channel name, then tap **Confirm**.
5. Grant the **Notification Access** permission when prompted.
6. Configure app filters in the channel settings (select which apps to relay).

### Scenario B: Adding a Subscriber Phone
1. On the **relay phone**, open the channel in the **Relay** tab and tap **Share with subscriber**.
2. Complete biometric or device-PIN verification. The `cn-sub` pairing QR code will appear on screen for 60 seconds.
3. On the **subscriber phone**, open CrossNotify, navigate to the **Subscriptions** tab, and tap **Add subscription**.
4. Scan the QR code displayed on the relay phone.
5. Choose your desired delivery mode:
   - **Battery Saver**: Pushes without waking the app; tap notification to decrypt.
   - **Immediate Preview**: Pushes decrypt immediately; real title and text shown in tray.
6. The subscriber appears in the server dashboard immediately, and test notifications will now arrive.

---

## Source Tree Overview

```
android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/crossnotify/app/
│   │   │   │   ├── crypto/              # AES-256-GCM engine, Keystore, daisy-chain rules
│   │   │   │   ├── data/
│   │   │   │   │   ├── api/             # Retrofit client and API models
│   │   │   │   │   ├── db/              # Room database, DAOs, and entities
│   │   │   │   │   ├── prefs/           # DataStore/SharedPreferences utilities
│   │   │   │   │   └── repository/      # Repository layer coordinating DB and network
│   │   │   │   ├── di/                  # Hilt dependency injection modules
│   │   │   │   ├── firebase/            # Dynamic runtime FirebaseApp project manager
│   │   │   │   ├── qr/                  # ZXing QR bitmap generator and QR payload models
│   │   │   │   ├── service/             # NotificationListenerService & FirebaseMessagingService
│   │   │   │   ├── ui/                  # Jetpack Compose screens, ViewModels, and themes
│   │   │   │   ├── util/                # Notification helpers and system utilities
│   │   │   │   └── worker/              # WorkManager workers (outbox, catch-up, token refresh)
│   │   │   ├── res/                     # App resources, drawables, strings, themes
│   │   │   └── AndroidManifest.xml      # App manifest with dynamic Firebase init provider removal
│   │   └── test/                        # Unit tests for crypto, QR parsing, and routing
│   └── build.gradle.kts                 # App module Gradle configuration
├── gradle/                              # Gradle wrapper files
├── build.gradle.kts                     # Top-level Gradle configuration
├── gradle.properties                    # JVM memory and build flags
└── settings.gradle.kts                  # Gradle project settings
```

---

## Troubleshooting

- **"Could not connect to the Gradle daemon"**:
  Ensure your user account has loopback TCP permissions, or run with `./gradlew assembleDebug --no-daemon`.
- **"Cleartext HTTP traffic not permitted"**:
  By default, `network_security_config.xml` allows cleartext HTTP only on `localhost`, `127.0.0.1`, `10.0.2.2`, `.local`, and `.lan`. For public deployments over the internet, HTTPS is strictly enforced.
- **Push notifications not arriving**:
  - Verify Google Play Services is installed and updated on the subscriber phone.
  - Check the server dashboard to verify that the subscriber is registered with status `active`.
  - In `battery_saver` mode, ensure the `crossnotify_relay` notification channel is not muted in Android notification settings.

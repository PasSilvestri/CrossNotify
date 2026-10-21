# CrossNotify — Firebase Setup Guide

CrossNotify uses **Firebase Cloud Messaging (FCM)** to deliver push notifications without draining your battery. Every CrossNotify server uses **its own Firebase project**, owned by the person running the server. This guide shows how to create that project and get the **two files** the server dashboard asks for.

| File | What it is | Secret? |
| :--- | :--- | :--- |
| `google-services.json` | **Client config**: public identifiers that let the CrossNotify app on your phones receive pushes from *your* project. The server passes them to phones inside the pairing QR codes. | No (but keep it private anyway) |
| `serviceAccountKey.json` | **Server credentials**: allow your server to *send* pushes through your project. | **Yes. Treat it like a password.** |

> [!NOTE]
> Firebase Cloud Messaging is **free**, with no charge per message. The free **Spark** plan is all you need. No credit card is required.

You do **not** need to compile the app. The precompiled CrossNotify APK reads your Firebase settings from the QR codes at runtime.

---

## 1. Create a Firebase Project

1. Go to the **[Firebase Console](https://console.firebase.google.com/)** and sign in with a Google account.
2. Click **Create a project** (or **Add project**).
3. Enter a name, for example `CrossNotify`, and click **Continue**.
4. **Google Analytics**: you can turn it **off**. CrossNotify doesn't use it.
5. Click **Create project**, wait for it to finish, then click **Continue**.

---

## 2. Register the Android App → `google-services.json`

The Firebase console home page now shows Gemini shortcuts and may hide the platform icons. The reliable route is through **Project settings**:

1. Click the **gear icon (⚙️)** next to *Project Overview* in the left sidebar, then **Project settings**.
   (Direct link: `https://console.firebase.google.com/project/<YOUR-PROJECT-ID>/settings/general`)
2. On the **General** tab, scroll down to **Your apps**.
3. Click **Add app** and choose **Android** (the robot icon).
4. **Android package name**: enter exactly
   ```
   com.crossnotify.app
   ```
   > [!IMPORTANT]
   > The package name must match exactly. The server rejects a `google-services.json` that doesn't contain `com.crossnotify.app`.
5. **App nickname**: optional (e.g. `CrossNotify`).
6. **Debug signing certificate SHA-1**: leave it **empty**. FCM doesn't need it.
7. Click **Register app**.
8. Click **Download google-services.json** and save the file.
9. Click **Next** through the remaining SDK instructions, then **Continue to console**. You can ignore the Gradle snippets, because the CrossNotify app is already built.

---

## 3. Generate the Server Key → `serviceAccountKey.json`

1. In **Project settings**, open the **Service accounts** tab.
2. Make sure **Firebase Admin SDK** is selected (the language selector doesn't matter).
3. Click **Generate new private key**, then **Generate key** in the confirmation dialog.
4. A file named like `<project-id>-firebase-adminsdk-xxxxx-xxxxxxxxxx.json` downloads. You can upload it as is, without renaming. In this guide it's called `serviceAccountKey.json`.

> [!CAUTION]
> This file gives **full administrative access** to your Firebase project. Never commit it to Git, share it, or send it over chat.

---

## 4. Check That the FCM API (V1) Is Enabled

1. In **Project settings**, open the **Cloud Messaging** tab.
2. Under **Firebase Cloud Messaging API (V1)**, the status should be **Enabled**.
3. If it says *Disabled*: click the **⋮** menu → **Manage API in Google Cloud Console** → **Enable**.

You can ignore the *Cloud Messaging API (Legacy)* entry. It's deprecated and CrossNotify doesn't use it.

---

## 5. Upload Both Files in the CrossNotify Dashboard

1. Start your CrossNotify server (see [server/README.md](./server/README.md)) and open its URL in a browser.
2. Finish the first-run admin setup. You'll need the **setup token** printed in the server logs.
3. The dashboard opens the **Firebase setup** page, which has two upload cards:
   - **Client config**: choose `google-services.json`
   - **Server credentials**: choose `serviceAccountKey.json`
4. The server checks that:
   - both files belong to the **same Firebase project**,
   - `google-services.json` contains the app `com.crossnotify.app`,
   - Google accepts the service account key.
5. When both cards show ✅, the dashboard switches to its normal view and you can add relay phones.

Both files are stored **encrypted** in the server's database. After a successful upload you can **delete your local copies**. If you need them again, you can always download a fresh `google-services.json` and generate a new service account key.

---

## 6. Maintenance & Security

- **Rotating the server key**: generate a new key (step 3) and upload it in **Settings → Firebase**. Phones aren't affected because the project is the same. Then delete the old key in the Google Cloud Console (**IAM & Admin → Service Accounts → firebase-adminsdk → Keys**).
- **Leaked key**: delete it right away in the Google Cloud Console (see above), then upload a new one.
- **Switching to a different Firebase project**: the dashboard warns you that every channel will be revoked. All phones have to be re-paired, because they hold the old project's settings.
- **Lost server master key** (`CROSSNOTIFY_SECRET` or `data/secret.key`): the server can't decrypt the stored files anymore and asks you to upload both files again. Channels and phones keep working once you do.

---

## FAQ

**Can several people use my Firebase project?**
Yes. Everyone paired to *your server* uses your project automatically through the QR codes. Other people who run *their own server* should create their own project.

**Do I need one Firebase project per phone?**
No. You need one project per **server**. A single phone can subscribe to channels on several servers backed by different projects, and the app handles this automatically.

**Why does the app need the package name `com.crossnotify.app` registered?**
FCM issues push tokens only to apps registered in the project. The precompiled APK uses that package name.

---

## Appendix — For Android Developers

The Android app configures Firebase **at runtime** from the QR code parameters (`projectId`, `appId`, `apiKey`, `senderId`), using named `FirebaseApp` instances. Details are in [ARCHITECTURE.md §7.3](./ARCHITECTURE.md#73-runtime-firebase-configuration-on-android--multi-project-fallback).

So, unlike the snippets the Firebase console shows:
- **Do not** apply the `com.google.gms.google-services` Gradle plugin.
- **Do not** bundle a `google-services.json` in `android/app/`.
- **Do not** add `firebase-analytics`.

`android/app/build.gradle.kts`:
```kotlin
dependencies {
    // Firebase BoM (Bill of Materials)
    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    // Firebase Cloud Messaging (version managed by the BoM)
    implementation("com.google.firebase:firebase-messaging")
}
```

`AndroidManifest.xml`: stop Firebase from auto-creating a default app from (missing) resources:
```xml
<provider
    android:name="com.google.firebase.provider.FirebaseInitProvider"
    android:authorities="${applicationId}.firebaseinitprovider"
    tools:node="remove" />
```

For local development, register `com.crossnotify.app` in your own test project and upload your test files to your local server's dashboard, just like an end user would. Your personal `google-services.json` and `serviceAccountKey.json` must stay out of Git (they are listed in `.gitignore`).

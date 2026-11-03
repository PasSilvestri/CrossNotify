# CrossNotify Security Audit Report

## 1. Executive Summary & Zero-Knowledge Verification

### Architectural Security & Zero-Knowledge Assessment
CrossNotify is a self-hosted, end-to-end encrypted (E2EE) Android notification relay system composed of a native Kotlin/Jetpack Compose Android client (`android/`) and a Node.js/Express/SQLite backend with a Vue 3 management dashboard (`server/`).

A rigorous code-level audit of the cryptographic pipeline across both client and server **confirms CrossNotify's core zero-knowledge cryptographic guarantee**:

1. **Client-Only Key Generation & Peer-to-Peer Distribution:**
   - The 256-bit channel symmetric key (`e2eKey`) is generated exclusively on the Relay device using `java.security.SecureRandom` in [`E2ECryptoEngine.generateKey()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/E2ECryptoEngine.kt#L104-L107) during [`CrossNotifyRepository.pairRelayFromQr()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L172-L212).
   - During relay provisioning (`POST /api/v1/relay/init` in [`server/src/routes/device/relay.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/relay.ts#L22-L38)) and subscriber key minting (`POST /api/v1/channels/:channelId/subscribers/keys` in [`server/src/routes/device/subscribers.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/subscribers.ts#L37-L48)), the `e2eKey` is **never transmitted** to the backend. Instead, the Relay device embeds the Base64URL-encoded `e2eKey` directly into the `cn-sub` QR payload ([`CrossNotifyRepository.generateSubscriberQrPayload()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L232-L275)), transferring it out-of-band to Subscriber devices.
2. **Authenticated Encryption (AES-256-GCM) with Context-Bound AAD:**
   - Every notification is encrypted on the Relay device via [`E2ECryptoEngine.encrypt()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/E2ECryptoEngine.kt#L129-L147) using `AES/GCM/NoPadding` with a fresh 96-bit (12-byte) `SecureRandom` IV per message and a 128-bit authentication tag (`GCM_TAG_BITS = 128`).
   - Additional Authenticated Data (AAD) is deterministically bound to `cn1|<channelId>|<notificationId>` ([`E2ECryptoEngine.buildAad()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/E2ECryptoEngine.kt#L83-L87)). On the Subscriber device, [`E2ECryptoEngine.decrypt()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/E2ECryptoEngine.kt#L164-L188) verifies the 128-bit GCM authentication tag and AAD before parsing any JSON plaintext. This prevents cross-channel ciphertext replay and notification ID substitution by a malicious or compromised server.
3. **Hardware-Backed Key Wrapping at Rest:**
   - On Android, both `e2eKey` and device API keys (`cnr_...`, `cns_...`) are wrapped before storage in Room using [`CryptoManager`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L26-L120), which generates a non-exportable 256-bit `AES/GCM/NoPadding` master key (`cn_master`) inside the `AndroidKeyStore`, attempting StrongBox hardware security module backing first with automatic TEE fallback ([`CryptoManager.generateMasterKey()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L46-L74)).
   - On the server, the uploaded Firebase `serviceAccountKey.json` is encrypted at rest in SQLite using AES-256-GCM with AAD `'firebase_service_account'` ([`server/src/crypto/secretBox.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/crypto/secretBox.ts#L17-L56)) keyed by a 32-byte `secret.key` created with `0o600` permissions ([`server/src/crypto/masterKey.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/crypto/masterKey.ts#L7-L38)).
4. **Zero-Knowledge Server Ingestion & Storage:**
   - [`server/src/routes/device/notifications.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L15-L35) and [`server/src/services/notificationService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L30-L68) accept, persist, and forward only `iv` (12-byte Base64URL) and `ciphertext` (up to 8192 bytes). The server has zero cryptographic capability to decrypt notification titles, bodies, package names, or icons stored inside the encrypted [`NotificationPlaintext`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/E2ECryptoEngine.kt#L24-L42) payload.
   - **Metadata Privacy Caveat:** While notification *contents* are zero-knowledge, when `sendAppName` is enabled on a relay channel (default `true` in [`RelayChannelEntity`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/db/Entities.kt#L47)), the human-readable application label (`appName`, e.g., `"Signal"`, `"WhatsApp"`) is also sent in plaintext alongside the ciphertext ([`OutboxWorker.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/worker/OutboxWorker.kt#L81-L87)), stored in plaintext in SQLite (`notifications.app_name` in [`001_initial.sql`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/db/migrations/001_initial.sql#L87)), and included in plaintext FCM notification headers for `battery_saver` mode ([`pushService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L50)).

---

### Summary of Vulnerability Findings

| ID | Severity | Title | Component | Target Files |
| :--- | :---: | :--- | :--- | :--- |
| **SEC-01** | **HIGH** | Unauthenticated Cross-Project FCM Push Injection & Permanent Key Wipe (`channel_revoked` DoS) | Android Client | [`CrossNotifyMessagingService.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt#L37-L82), [`CrossNotifyRepository.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L649-L662) |
| **SEC-02** | **HIGH** | Exported `DecryptActivity` Without Permission Enables Cross-App Intent Spoofing, Forced Network Fetch & Screen Leaks | Android Client | [`AndroidManifest.xml`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/AndroidManifest.xml#L84-L94), [`DecryptActivity.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt#L64-L108) |
| **SEC-03** | **HIGH** | Biometric Authentication Bypass on Error States & Plaintext E2E Key Exfiltration via Clipboard/Share Intent | Android Client | [`MainActivity.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L88-L132), [`ShareSubscriberQrDialog.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt#L63-L172) |
| **SEC-04** | **HIGH** | Server-Side Request Forgery (SSRF) & Error Reflection via Unconstrained `token_uri` in Firebase Service Account Upload | Server API | [`firebaseConfigService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseConfigService.ts#L7-L22), [`routes/admin/firebase.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/firebase.ts#L28-L50) |
| **SEC-05** | **HIGH** | Async Race Condition & Host Header Poisoning in First-Run Admin Setup (`POST /admin/api/setup/admin`) | Server API | [`routes/admin/setup.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/setup.ts#L27-L60), [`authService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/authService.ts#L50-L63) |
| **SEC-06** | **MEDIUM** | Permissive Global CORS (`*`) & Port-Ignoring `originCheck` Allow Same-Host Cross-Port / Bodyless CSRF on Admin API | Server API | [`app.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L114), [`originCheck.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/originCheck.ts#L3-L45) |
| **SEC-07** | **MEDIUM** | `deviceKeyLimiter` Middleware Ordering Bug & Unthrottled Device Init Endpoints | Server API | [`app.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L151-L161), [`rateLimits.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/rateLimits.ts#L39-L51), [`requireDeviceKey.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/requireDeviceKey.ts#L98-L145) |
| **SEC-08** | **MEDIUM** | Cross-Channel Notification ID Collision & Timestamp Oracle + Serving Expired Notifications Before TTL Cleanup | Server API / SQLite DB | [`notificationService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L30-L116), [`routes/device/notifications.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L15-L35) |
| **SEC-09** | **MEDIUM** | Destructive `removeToken` Deletes Entire Subscriber Account Across All Channels & Missing `:channelId` Verification in `initSubscriber` | Server API / SQLite DB | [`subscriberService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/subscriberService.ts#L65-L153), [`routes/device/subscribers.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/subscribers.ts#L51-L69), [`pushService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L32-L37) |
| **SEC-10** | **MEDIUM** | Exposed Container Port (`0.0.0.0:3000`) Bypassing Reverse Proxy & Missing `.dockerignore` Leaking Local Secrets into Build Context | Containers / CI-CD | [`docker-compose.yml`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/docker-compose.yml#L6-L7), [`Dockerfile`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/Dockerfile#L5-L21) |
| **SEC-11** | **LOW** | Disabled R8/ProGuard Minification & Legacy V1 Signing in Release Builds, Plus Permissive URL/Firebase Validation in `QrPayloadParser` | Android Client | [`android/app/build.gradle.kts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/build.gradle.kts#L46-L58), [`QrModels.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt#L140-L152) |
| **SEC-12** | **LOW** | Unzeroed E2E Key Material in JVM Heap & Residual Deleted Key Blobs in SQLite WAL (`secure_delete` Disabled) | Android Client / SQLite DB | [`CryptoManager.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L80-L119), [`CrossNotifyRepository.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L649-L662), [`AppModule.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt#L25-L31) |

| Severity | Count |
| :--- | :---: |
| **Critical** | 0 |
| **High** | 5 |
| **Medium** | 5 |
| **Low** | 2 |
| **Informational** | 0 (see Section 3 for Defense-in-Depth) |

---

## 2. Prioritized Vulnerability Findings

### [SEC-01] [HIGH] Unauthenticated Cross-Project FCM Push Injection & Permanent Key Wipe (`channel_revoked` DoS)
- **Component:** Android Client
- **Target File(s) & Line Numbers:**
  - [`android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt#L37-L82`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt#L37-L82)
  - [`android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L649-L662`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L649-L662)
- **Vulnerability Mechanics:**
  1. [`CrossNotifyMessagingService.onMessageReceived()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt#L37-L82) receives push messages across all dynamically initialized Firebase projects ([`FirebaseProjectManager.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/firebase/FirebaseProjectManager.kt#L46-L108)). However, it never verifies `message.from` (the authenticated FCM `senderId` of the project that originated the push) against the `ServerEntity.fbSenderId` bound to `data["channelId"]`.
  2. When a push arrives with `data["cn"] == "1"` and `data["type"] == "channel_revoked"`, [`CrossNotifyMessagingService`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt#L46-L50) directly invokes [`repository.handleSubscriptionRevoked(channelId, incomingSubId)`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L649-L662), which **immediately and irreversibly overwrites** `apiKeyEnc = ''` and `e2eKeyEnc = ''` in the local SQLite database via [`subscriptionDao.markRevokedAndWipeKeys(channelId)`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/db/Daos.kt#L131-L132)—without querying the CrossNotify server to verify whether the channel or subscriber was actually revoked.
- **Impact & Threat Scenario:**
  - **Cross-Server Destructive DoS:** CrossNotify is explicitly architected to support subscriptions across multiple independent servers (`ServerEntity`). If a user subscribes to an untrusted third-party channel on `Server B`, `Server B` holds valid Firebase Admin credentials for its own FCM project. If `Server B` learns or guesses a `channelId` belonging to the user's private `Server A` (or if `Server A` and `Server B` share a Firebase project), `Server B` can dispatch a push with `type = "channel_revoked"` and `channelId = <Server A's channelId>`. Because `message.from` is not checked against `Server A`'s `fbSenderId` and revocation is not verified via `Server A`'s authenticated REST API, the client permanently wipes the user's `e2eKey` and `apiKey` for `Server A`, requiring physical re-pairing.
  - **Forced Network Fetch:** Similarly, an untrusted project can send `type = "notification"` with `fetch = "1"`, forcing the client to issue authenticated HTTP requests to `Server A`.
- **Remediation Patch:**
  Verify `senderId` (`message.from`) against the channel's associated `ServerEntity.fbSenderId`, and confirm revocation status with the channel's server before wiping keys when triggered by an unauthenticated push notification.

```diff
--- a/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt
+++ b/android/app/src/main/java/com/crossnotify/app/service/CrossNotifyMessagingService.kt
@@ -40,14 +40,21 @@
 
         val channelId = data["channelId"]?.takeIf { it.isNotBlank() } ?: return
         val type = data["type"].orEmpty()
+        val senderId = message.from?.trim()
 
         serviceScope.launch {
+            if (!repository.verifyPushSenderForChannel(channelId, senderId)) {
+                Log.w(TAG, "Dropping FCM message for channel $channelId: senderId mismatch ($senderId)")
+                return@launch
+            }
+
             when (type) {
                 "channel_revoked" -> {
                     val incomingSubId = data["subscriberId"]?.takeIf { it.isNotBlank() }
                     Log.i(TAG, "Received channel_revoked push for channel $channelId (subscriberId=$incomingSubId)")
-                    repository.handleSubscriptionRevoked(channelId, incomingSubId)
+                    repository.handlePushSubscriptionRevoked(channelId, incomingSubId)
                 }
                 "notification" -> {
```

```diff
--- a/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt
+++ b/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt
@@ -642,6 +642,50 @@
         firebaseProjectManager.reconcileAfterServerRemoval()
     }
 
+    /**
+     * Verifies that an incoming FCM push's `from` senderId matches the configured
+     * Firebase senderId of the server bound to [channelId].
+     */
+    suspend fun verifyPushSenderForChannel(channelId: String, senderId: String?): Boolean {
+        if (senderId.isNullOrBlank()) return false
+        val sub = subscriptionDao.getById(channelId) ?: return false
+        val server = serverDao.getById(sub.serverId) ?: return false
+        return server.fbSenderId == senderId
+    }
+
+    /**
+     * Handles an untrusted `channel_revoked` FCM push by confirming revocation with the
+     * authoritative CrossNotify server API before permanently wiping local E2E keys.
+     */
+    suspend fun handlePushSubscriptionRevoked(channelId: String, targetSubscriberId: String? = null) {
+        val sub = subscriptionDao.getById(channelId) ?: return
+        if (sub.status == "revoked") return
+        if (targetSubscriberId != null && sub.subscriberId != targetSubscriberId) {
+            return
+        }
+        val server = serverDao.getById(sub.serverId) ?: return
+        val confirmedRevoked = runCatching {
+            val subApiKey = cryptoManager.unwrapString(sub.apiKeyEnc)
+            val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
+            val response = service.getChannelInfo(apiClient.bearerHeader(subApiKey), channelId)
+            if (response.isSuccessful) {
+                response.body()?.status == "revoked"
+            } else {
+                response.code() == 401 || response.code() == 403
+            }
+        }.getOrDefault(false)
+
+        if (confirmedRevoked) {
+            handleSubscriptionRevoked(channelId, targetSubscriberId)
+        } else {
+            Log.w(TAG, "Ignored unconfirmed channel_revoked push for channel $channelId")
+        }
+    }
+
     /**
      * Handles revocation events when the server or relay closes a channel or revokes this subscriber.
```

---

### [SEC-02] [HIGH] Exported `DecryptActivity` Without Permission Enables Cross-App Intent Spoofing, Forced Network Fetch & Screen Leaks
- **Component:** Android Client
- **Target File(s) & Line Numbers:**
  - [`android/app/src/main/AndroidManifest.xml#L84-L94`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/AndroidManifest.xml#L84-L94)
  - [`android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt#L64-L108`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt#L64-L108)
- **Vulnerability Mechanics:**
  1. In [`AndroidManifest.xml`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/AndroidManifest.xml#L84-L94), `DecryptActivity` is declared with `android:exported="true"` and an unprotected `<intent-filter>` (`com.crossnotify.app.OPEN_NOTIFICATION`). No `android:permission` attribute restricts access.
  2. In [`DecryptActivity.onCreate()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt#L72-L107), `extractIntentExtras(intent)` accepts arbitrary `channelId` and `notificationId` strings from any caller and immediately calls `repository.fetchAndDecryptSingleNotification(channelId, notificationId, postLocalNotification = false, markAsRead = true)`.
  3. `DecryptActivity` does not set `WindowManager.LayoutParams.FLAG_SECURE` on its window before rendering decrypted notification bodies.
- **Impact & Threat Scenario:**
  - Any unprivileged third-party app installed on the device can launch `DecryptActivity` with arbitrary `channelId` and `notificationId` extras, forcing CrossNotify to perform authenticated network requests, marking unread notifications in the user's inbox as read (`markAsRead = true`), and popping up decrypted notification contents on screen.
  - Because `FLAG_SECURE` is absent on `DecryptActivity`, decrypted sensitive notifications (such as banking OTPs or private messages) are captured by Android's Recents/task-switcher snapshot cache on disk and are visible to screen recording or media projection apps.
  - Note: Even when FCM delivers a background `notification` payload (`battery_saver` mode) with `clickAction = "com.crossnotify.app.OPEN_NOTIFICATION"`, Google Play Services resolves the intent restricted to the target application's package (`setPackage(packageName)`), and local notifications in [`NotificationHelper.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/util/NotificationHelper.kt#L100-L112) use an explicit component `Intent(context, DecryptActivity::class.java)`. Therefore, `DecryptActivity` does **not** need to be exported to third-party apps.
- **Remediation Patch:**
  Set `android:exported="false"` on `DecryptActivity` in [`AndroidManifest.xml`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/AndroidManifest.xml#L84-L94) and enforce `FLAG_SECURE` in [`DecryptActivity.onCreate()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt#L72-L75).

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -84,7 +84,7 @@
         <activity
             android:name=".ui.DecryptActivity"
             android:label="@string/title_activity_decrypt"
-            android:exported="true"
+            android:exported="false"
             android:launchMode="singleTop"
             android:theme="@style/Theme.CrossNotify">
             <intent-filter>
```

```diff
--- a/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt
+++ b/android/app/src/main/java/com/crossnotify/app/ui/DecryptActivity.kt
@@ -5,6 +5,7 @@
 import android.graphics.BitmapFactory
 import android.os.Bundle
 import android.util.Base64
+import android.view.WindowManager
 import androidx.activity.compose.setContent
 import androidx.appcompat.app.AppCompatActivity
 import androidx.compose.foundation.Image
@@ -71,6 +72,10 @@
 
     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)
+        window.setFlags(
+            WindowManager.LayoutParams.FLAG_SECURE,
+            WindowManager.LayoutParams.FLAG_SECURE
+        )
         extractIntentExtras(intent)
 
         setContent {
```

---

### [SEC-03] [HIGH] Biometric Authentication Bypass on Error States & Plaintext E2E Key Exfiltration via Clipboard/Share Intent
- **Component:** Android Client
- **Target File(s) & Line Numbers:**
  - [`android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L88-L132`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L88-L132)
  - [`android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt#L63-L172`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt#L63-L172)
- **Vulnerability Mechanics:**
  1. **Fail-Open Biometric Gate:** In [`MainActivity.authenticateAndGenerateSubscriberQr()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L99-L104), the code checks `if (canAuth != BiometricManager.BIOMETRIC_SUCCESS)` and **unconditionally proceeds** to call `viewModel.generateShareSubscriberQr(channel)` for *any* non-success status code. This means transient or security error states—such as `BIOMETRIC_ERROR_HW_UNAVAILABLE` (biometric hardware busy or disabled during lockouts), `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`, or `BIOMETRIC_STATUS_UNKNOWN`—bypass authentication completely and reveal the channel's E2E key!
  2. **Clipboard & Share Intent Key Exfiltration:** In [`ShareSubscriberQrDialog.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt#L126-L170), clicking **"Copy JSON"** copies `qrState.payloadJson` (which contains the raw 256-bit `e2e.key` and `cns_...` subscriber API key) to the system clipboard via Compose's `LocalClipboardManager.setText(...)` without marking the `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE`, and without clearing the clipboard when the 60-second dialog timer expires. On Android 13+, unflagged clipboard text is displayed in plaintext in the system clipboard overlay; on Android 8–9 (`minSdk = 26`), any background app can read the system clipboard continuously; and third-party IMEs/cloud clipboard sync services persist unflagged clipboard items indefinitely.
- **Impact & Threat Scenario:**
  - An opportunistic attacker with brief physical access to an unlocked Relay device can trigger `BIOMETRIC_ERROR_HW_UNAVAILABLE` (e.g., by exhausting biometric attempts on another prompt or occupying the sensor) and bypass [`authenticateAndGenerateSubscriberQr()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L88-L132) without knowing the user's PIN/biometric.
  - When a user copies the subscriber pairing JSON, the raw 256-bit E2E key remains in the system clipboard indefinitely after the 60-second QR dialog closes and is exposed to IME history and clipboard listeners.
- **Remediation Patch:**
  1. In [`MainActivity.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt#L88-L132), fail closed on all biometric error states except when the device genuinely has no lockscreen credential or biometric enrolled (`BIOMETRIC_ERROR_NONE_ENROLLED` / `BIOMETRIC_ERROR_NO_HARDWARE` when no device credential is set).
  2. In [`ShareSubscriberQrDialog.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt#L63-L172), use Android's native `ClipboardManager` with `ClipDescription.EXTRA_IS_SENSITIVE = true` and automatically scrub the clipboard when the dialog is dismissed or expires.

```diff
--- a/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt
+++ b/android/app/src/main/java/com/crossnotify/app/ui/MainActivity.kt
@@ -1,8 +1,10 @@
 package com.crossnotify.app.ui
 
+import android.app.KeyguardManager
 import android.os.Build
 import android.os.Bundle
+import android.widget.Toast
 import androidx.activity.compose.setContent
 import androidx.appcompat.app.AppCompatActivity
 import androidx.biometric.BiometricManager
 import androidx.biometric.BiometricManager.Authenticators
@@ -89,18 +91,27 @@
         channel: RelayChannelEntity,
         viewModel: MainViewModel
     ) {
+        val keyguardManager = getSystemService(KeyguardManager::class.java)
+        val isDeviceSecure = keyguardManager?.isDeviceSecure == true
+        if (!isDeviceSecure) {
+            // Device has no PIN/pattern/password or biometric configured at all
+            viewModel.generateShareSubscriberQr(channel)
+            return
+        }
+
         val biometricManager = BiometricManager.from(this)
         val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
         } else {
             Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
         }
 
         val canAuth = biometricManager.canAuthenticate(authenticators)
         if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
-            // If no lockscreen / biometrics are enrolled on this device (e.g. emulator), proceed directly
-            viewModel.generateShareSubscriberQr(channel)
+            Toast.makeText(
+                this,
+                "Biometric or device lock authentication is currently unavailable (code: $canAuth).",
+                Toast.LENGTH_LONG
+            ).show()
             return
         }
```

```diff
--- a/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt
+++ b/android/app/src/main/java/com/crossnotify/app/ui/components/ShareSubscriberQrDialog.kt
@@ -1,8 +1,13 @@
 package com.crossnotify.app.ui.components
 
 import android.app.Activity
+import android.content.ClipData
+import android.content.ClipDescription
+import android.content.ClipboardManager
 import android.content.Intent
+import android.os.Build
+import android.os.PersistableBundle
 import android.view.WindowManager
 import androidx.compose.foundation.Image
 import androidx.compose.foundation.background
@@ -36,8 +41,6 @@
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.graphics.asImageBitmap
-import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
-import androidx.compose.ui.text.AnnotatedString
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
@@ -65,7 +68,7 @@
     onDismiss: () -> Unit
 ) {
     val context = LocalContext.current
-    val clipboardManager = LocalClipboardManager.current
+    val systemClipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
     val scope = rememberCoroutineScope()
     var isCopied by remember { mutableStateOf(false) }
     var showRawJson by remember { mutableStateOf(false) }
@@ -77,6 +80,15 @@
         activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
         onDispose {
             activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
+            if (isCopied) {
+                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
+                    systemClipboard?.clearPrimaryClip()
+                } else {
+                    systemClipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
+                }
+            }
         }
     }
 
@@ -125,7 +137,15 @@
                     OutlinedButton(
                         onClick = {
-                            clipboardManager.setText(AnnotatedString(qrState.payloadJson))
+                            val clip = ClipData.newPlainText("CrossNotify Pairing Payload", qrState.payloadJson)
+                            clip.description.extras = PersistableBundle().apply {
+                                putBoolean(
+                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
+                                        ClipDescription.EXTRA_IS_SENSITIVE
+                                    else
+                                        "android.content.extra.IS_SENSITIVE",
+                                    true
+                                )
+                            }
+                            systemClipboard?.setPrimaryClip(clip)
                             isCopied = true
                             scope.launch {
                                 delay(2000L)
```

---

### [SEC-04] [HIGH] Server-Side Request Forgery (SSRF) & Error Reflection via Unconstrained `token_uri` in Firebase Service Account Upload
- **Component:** Server API
- **Target File(s) & Line Numbers:**
  - [`server/src/services/firebaseConfigService.ts#L7-L22`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseConfigService.ts#L7-L22)
  - [`server/src/services/firebaseAdmin.ts#L7-L30`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseAdmin.ts#L7-L30)
  - [`server/src/routes/admin/firebase.ts#L28-L50`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/firebase.ts#L28-L50)
- **Vulnerability Mechanics:**
  1. In [`firebaseConfigService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseConfigService.ts#L7-L22), `ServiceAccountSchema` defines `token_uri: z.string().optional()` and uses `.passthrough()`, allowing arbitrary URLs in `token_uri`, `auth_uri`, `client_x509_cert_url`, and arbitrary additional properties such as `universe_domain`.
  2. When `POST /admin/api/firebase/service-account` is called ([`routes/admin/firebase.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/firebase.ts#L28-L50)), [`defaultFirebaseVerifier`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseAdmin.ts#L7-L30) initializes a temporary Firebase Admin app with `cert(sa)` and calls `cred.getAccessToken()`. Under the hood, `google-auth-library` sends an HTTP POST request containing a signed JWT assertion to `sa.token_uri` (or the endpoint derived from `universe_domain`) and throws an exception containing the upstream response status/body on failure, which is reflected directly in `res.status(422).json({ detail: err?.message })` ([`routes/admin/firebase.ts#L45-L48`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/firebase.ts#L45-L48)).
- **Impact & Threat Scenario:**
  - A compromised admin session (or an attacker exploiting the CSRF vector in **SEC-06**) can supply a crafted `serviceAccountKey.json` with `token_uri` pointing to internal loopback services (`http://127.0.0.1:...`), Docker bridge network hosts, or cloud instance metadata services (`http://169.254.169.254/latest/meta-data/`), using the CrossNotify backend as an SSRF proxy and reading error details returned in the HTTP 422 response.
- **Remediation Patch:**
  Restrict `ServiceAccountSchema` in [`firebaseConfigService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/firebaseConfigService.ts#L7-L22) to strip unknown keys (removing `.passthrough()`) and enforce strict allowlists on `token_uri` (`https://oauth2.googleapis.com/token`), `auth_uri`, and `project_id`. Additionally, sanitize error messages in [`routes/admin/firebase.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/firebase.ts#L41-L50).

```diff
--- a/server/src/services/firebaseConfigService.ts
+++ b/server/src/services/firebaseConfigService.ts
@@ -6,20 +6,26 @@
 
 export const ServiceAccountSchema = z.object({
   type: z.literal('service_account'),
-  project_id: z.string().min(1),
+  project_id: z.string().min(1).max(64).regex(/^[a-z0-9-]+$/, {
+    message: 'project_id must contain only lowercase letters, digits, and hyphens',
+  }),
   private_key_id: z.string().optional(),
   private_key: z.string().refine((k) => k.includes('-----BEGIN PRIVATE KEY-----'), {
     message: 'Must contain -----BEGIN PRIVATE KEY-----',
   }),
   client_email: z.string().refine((e) => e.endsWith('.iam.gserviceaccount.com'), {
     message: 'Must end with .iam.gserviceaccount.com',
   }),
   client_id: z.string().optional(),
-  auth_uri: z.string().optional(),
-  token_uri: z.string().optional(),
-  auth_provider_x509_cert_url: z.string().optional(),
-  client_x509_cert_url: z.string().optional(),
-}).passthrough();
+  auth_uri: z.literal('https://accounts.google.com/o/oauth2/auth').optional(),
+  token_uri: z.literal('https://oauth2.googleapis.com/token').optional(),
+  auth_provider_x509_cert_url: z
+    .literal('https://www.googleapis.com/oauth2/v1/certs')
+    .optional(),
+  client_x509_cert_url: z
+    .string()
+    .startsWith('https://www.googleapis.com/')
+    .optional(),
+});
 
 export type ServiceAccount = z.infer<typeof ServiceAccountSchema>;
```

```diff
--- a/server/src/routes/admin/firebase.ts
+++ b/server/src/routes/admin/firebase.ts
@@ -41,10 +41,11 @@
       // Verify the service account credentials with Firebase Admin SDK
       try {
         await firebaseAdminService.verifyServiceAccount(sa);
-      } catch (err: any) {
+      } catch {
         res.status(422).json({
           error: 'firebase_credential_rejected',
-          detail: err?.message || 'Firebase Admin SDK rejected the service account credentials',
+          detail: 'Firebase Admin SDK rejected the service account credentials',
         });
         return;
       }
```

---

### [SEC-05] [HIGH] Async Race Condition & Host Header Poisoning in First-Run Admin Setup (`POST /admin/api/setup/admin`)
- **Component:** Server API
- **Target File(s) & Line Numbers:**
  - [`server/src/routes/admin/setup.ts#L27-L60`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/setup.ts#L27-L60)
  - [`server/src/services/authService.ts#L50-L63`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/authService.ts#L50-L63)
- **Vulnerability Mechanics:**
  1. **TOCTOU Race Condition on Single-Admin Creation:** In [`routes/admin/setup.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/setup.ts#L29-L58), `setupService.getState() !== 'NEEDS_ADMIN'` and `setupService.verifySetupToken(setupToken)` are checked *before* `await authService.createAdmin(username, password)`. Because [`authService.createAdmin()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/authService.ts#L50-L63) awaits asynchronous Argon2id password hashing (`await this.hashPassword(password)`), execution yields back to the Node.js event loop for ~50–200 ms before inserting into `admin_users` and before `setupService.discardSetupToken()` is called at line 58. Concurrent requests bearing the setup token with distinct usernames pass both checks simultaneously and insert multiple admin rows into `admin_users` (while [`AccountPage`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/authService.ts#L43-L48) only displays `SELECT ... FROM admin_users LIMIT 1`, hiding any secondary admin account).
  2. **Unvalidated Host/Origin Header Persistence:** At [`routes/admin/setup.ts#L44-L55`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/setup.ts#L44-L55), if `public_url` is unset, it is populated directly from `req.headers.origin` or `${protocol}://${req.get('host')}` without URL validation, persisting any spoofed `Origin` or `Host` header into `settings.public_url` (which is later embedded into `cn-relay` QR codes).
- **Impact & Threat Scenario:**
  - During initial setup, concurrent requests can create a hidden secondary administrator account in `admin_users` that persists invisible to `getAdminUser()` (`LIMIT 1`) and remains unaffected when the primary admin changes their password.
  - A crafted `Origin` header during setup poisons `public_url` with an arbitrary URI.
- **Remediation Patch:**
  Atomically enforce a single admin account inside a synchronous SQLite transaction in [`authService.createAdmin()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/authService.ts#L50-L63), discard the setup token immediately upon verification (restoring only on error), and validate `inferredUrl` in [`routes/admin/setup.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/admin/setup.ts#L36-L59).

```diff
--- a/server/src/services/authService.ts
+++ b/server/src/services/authService.ts
@@ -50,18 +50,24 @@
   async createAdmin(username: string, password: string): Promise<AdminUser> {
     const passwordHash = await this.hashPassword(password);
     const now = Date.now();
-    const result = this.db
-      .prepare('INSERT INTO admin_users (username, password_hash, created_at) VALUES (?, ?, ?)')
-      .run(username, passwordHash, now);
 
-    return {
-      id: Number(result.lastInsertRowid),
-      username,
-      created_at: now,
-      last_login_at: null,
-    };
+    const insertAdminTx = this.db.transaction(() => {
+      if (this.hasAdminUser()) {
+        throw { status: 409, code: 'admin_already_exists', message: 'An admin user already exists' };
+      }
+      const result = this.db
+        .prepare('INSERT INTO admin_users (username, password_hash, created_at) VALUES (?, ?, ?)')
+        .run(username, passwordHash, now);
+
+      return {
+        id: Number(result.lastInsertRowid),
+        username,
+        created_at: now,
+        last_login_at: null,
+      };
+    });
+
+    return insertAdminTx();
   }
```

```diff
--- a/server/src/routes/admin/setup.ts
+++ b/server/src/routes/admin/setup.ts
@@ -38,25 +38,31 @@
         res.status(403).json({ error: 'invalid_setup_token', message: 'The provided setup token is invalid' });
         return;
       }
 
+      // Consume setup token immediately before yielding to async Argon2 hashing
+      setupService.discardSetupToken();
+
       const admin = await authService.createAdmin(username, password);
 
       // Default public_url from request if not set
       if (!settingsService.get('public_url')) {
-        let inferredUrl: string;
-        if (req.headers.origin) {
-          inferredUrl = req.headers.origin;
-        } else {
-          const protocol = req.secure ? 'https' : req.protocol || 'http';
-          inferredUrl = `${protocol}://${req.get('host')}`;
+        const protocol = req.secure ? 'https' : req.protocol || 'http';
+        const candidate = req.headers.origin || `${protocol}://${req.get('host')}`;
+        try {
+          const parsed = new URL(candidate);
+          if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
+            settingsService.set('public_url', parsed.origin);
+          }
+        } catch {
+          // Ignore malformed candidate URL
         }
-        // Strip trailing slash if present
-        inferredUrl = inferredUrl.replace(/\/+$/, '');
-        settingsService.set('public_url', inferredUrl);
       }
 
-      // Discard setup token permanently
-      setupService.discardSetupToken();
-
       // Create session & set cookie
```

---

### [SEC-06] [MEDIUM] Permissive Global CORS (`*`) & Port-Ignoring `originCheck` Allow Same-Host Cross-Port / Bodyless CSRF on Admin API
- **Component:** Server API
- **Target File(s) & Line Numbers:**
  - [`server/src/app.ts#L114`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L114)
  - [`server/src/middleware/originCheck.ts#L3-L45`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/originCheck.ts#L3-L45)
- **Vulnerability Mechanics:**
  1. [`server/src/app.ts#L114`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L114) mounts `app.use(cors())` globally with default `Access-Control-Allow-Origin: *`, including on `/admin/api`.
  2. In [`server/src/middleware/originCheck.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/originCheck.ts#L10-L19), `Content-Type: application/json` is only enforced `if (hasBody || contentType)`. Bodyless mutating admin endpoints—such as `POST /admin/api/relays/:channelId/regenerate`, `DELETE /admin/api/channels/:channelId`, and `POST /admin/api/auth/logout`—accept requests with no body and no `Content-Type` header.
  3. At [`originCheck.ts#L31-L35`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/originCheck.ts#L31-L35), the origin check allows the request unless *all three* comparisons fail (`originHost !== requestHost && originHostname !== requestHostname && originHostname !== requestHost`). Because `originHostname` and `requestHostname` strip the port number, **any web service running on a different port of the same hostname/IP** (e.g., `http://homelab.local:8080` vs CrossNotify on `http://homelab.local:3000`) has `originHostname === requestHostname`! Since HTTP cookies (`cn_admin`, `SameSite=Lax`) are neither port-isolated nor scheme-isolated under RFC 6265, another web service on the same host (common in self-hosted Docker/homelab environments) can send credentialed `fetch(..., { credentials: 'include' })` or form requests that pass `originCheck` and `SameSite=Lax`.
- **Impact & Threat Scenario:**
  - In a self-hosted homelab where CrossNotify shares an IP or domain with other containers on different ports, a compromised or untrusted web UI on another port can make authenticated mutating calls to `/admin/api` (creating channels, revoking relays, or uploading a Firebase service account).
- **Remediation Patch:**
  Restrict `cors()` strictly to `/api/v1` (which uses `Authorization: Bearer` headers, not cookies) and tighten [`originCheck.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/originCheck.ts#L3-L45) to require exact `originHost === requestHost` (preserving port matching) and reject cross-site fetches indicated by `Sec-Fetch-Site`.

```diff
--- a/server/src/app.ts
+++ b/server/src/app.ts
@@ -111,7 +111,6 @@
     })
   );
 
-  app.use(cors());
   app.use(cookieParser());
   app.use(express.json({ limit: '64kb' }));
   app.use(express.urlencoded({ extended: false }));
@@ -150,6 +149,7 @@
 
   // Device API routes: /api/v1/* (only in READY)
   const deviceApi = express.Router();
+  deviceApi.use(cors());
   deviceApi.use(requireState(setupService, 'READY'));
   deviceApi.use(deviceIpLimiter);
   deviceApi.use(deviceKeyLimiter);
```

```diff
--- a/server/src/middleware/originCheck.ts
+++ b/server/src/middleware/originCheck.ts
@@ -18,22 +18,25 @@
     }
   }
 
-  // 2. Origin check if header is present
+  // 2. Reject cross-site browser requests reported via Fetch Metadata
+  const secFetchSite = req.headers['sec-fetch-site'];
+  if (secFetchSite && secFetchSite !== 'same-origin' && secFetchSite !== 'none') {
+    res.status(403).json({ error: 'invalid_origin', message: 'Cross-site requests are not permitted' });
+    return;
+  }
+
+  // 3. Strict Origin host (including port) validation when Origin header is present
   const origin = req.headers.origin;
   if (origin) {
     try {
       const originUrl = new URL(origin);
       const originHost = originUrl.host.toLowerCase();
-      const originHostname = originUrl.hostname.toLowerCase();
       const requestHost = (req.get('host') || '').toLowerCase();
-      const requestHostname = (req.hostname || '').toLowerCase();
 
-      if (
-        originHost !== requestHost &&
-        originHostname !== requestHostname &&
-        originHostname !== requestHost
-      ) {
+      if (originHost !== requestHost) {
         res.status(403).json({ error: 'invalid_origin', message: 'Origin header does not match Host' });
         return;
       }
     } catch {
```

---

### [SEC-07] [MEDIUM] `deviceKeyLimiter` Middleware Ordering Bug & Unthrottled Device Init Endpoints
- **Component:** Server API
- **Target File(s) & Line Numbers:**
  - [`server/src/app.ts#L151-L161`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L151-L161)
  - [`server/src/middleware/rateLimits.ts#L39-L51`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/rateLimits.ts#L39-L51)
  - [`server/src/middleware/requireDeviceKey.ts#L98-L145`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/requireDeviceKey.ts#L98-L145)
- **Vulnerability Mechanics:**
  1. In [`server/src/app.ts#L154-L155`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/app.ts#L154-L155), `deviceApi.use(deviceKeyLimiter)` executes *before* the route-level authentication middlewares (`requireRelayKey`, `requireSubscriberKey`, `requireAnyDeviceKey`). Consequently, when [`deviceKeyLimiter`'s `keyGenerator`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/rateLimits.ts#L47-L49) runs, `(req as DeviceRequest).deviceKeyHash` is **always `undefined`**, causing `deviceKeyLimiter` to always fall back to `req.ip || 'anonymous'` instead of rate-limiting per device API key hash.
  2. Furthermore, [`requireAnyDeviceKey`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/requireDeviceKey.ts#L98-L145) never assigns `req.deviceKeyHash = hash`.
- **Impact & Threat Scenario:**
  - Per-device API key rate limiting is non-functional (`req.deviceKeyHash` is never populated when `deviceKeyLimiter` runs), and multiple devices behind a shared NAT/VPN share a single IP bucket in `deviceKeyLimiter`.
- **Remediation Patch:**
  Derive the SHA-256 bearer token hash directly inside `deviceKeyLimiter`'s `keyGenerator` in [`server/src/middleware/rateLimits.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/rateLimits.ts#L39-L51), and assign `req.deviceKeyHash = hash` in [`requireAnyDeviceKey`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/middleware/requireDeviceKey.ts#L98-L145).

```diff
--- a/server/src/middleware/rateLimits.ts
+++ b/server/src/middleware/rateLimits.ts
@@ -1,6 +1,7 @@
 import rateLimit from 'express-rate-limit';
 import type { Request, Response, NextFunction } from 'express';
-import type { DeviceRequest } from './requireDeviceKey.js';
+import { parseBearerKey, type DeviceRequest } from './requireDeviceKey.js';
+import { sha256Hex } from '../crypto/tokens.js';
 
 const isTest = process.env.NODE_ENV === 'test';
 
@@ -45,7 +46,12 @@
       legacyHeaders: false,
       validate: { default: false },
       keyGenerator: (req) => {
-        return (req as DeviceRequest).deviceKeyHash || req.ip || 'anonymous';
+        const existingHash = (req as DeviceRequest).deviceKeyHash;
+        if (existingHash) return existingHash;
+        const rawKey = parseBearerKey(req);
+        if (rawKey && (rawKey.startsWith('cnr_') || rawKey.startsWith('cns_'))) {
+          return sha256Hex(rawKey);
+        }
+        return req.ip || 'anonymous';
       },
       message: { error: 'too_many_requests', detail: 'Device key rate limit exceeded' },
     });
```

```diff
--- a/server/src/middleware/requireDeviceKey.ts
+++ b/server/src/middleware/requireDeviceKey.ts
@@ -117,6 +117,8 @@
         return;
       }
       req.channel = channel;
+      req.deviceKeyHash = hash;
+      req.rawApiKey = rawKey;
       return next();
     }
 
@@ -138,6 +140,8 @@
       }
       req.subscriber = subscriber;
       req.channel = channel;
+      req.deviceKeyHash = hash;
+      req.rawApiKey = rawKey;
       return next();
     }
```

---

### [SEC-08] [MEDIUM] Cross-Channel Notification ID Collision & Timestamp Oracle + Serving Expired Notifications Before TTL Cleanup
- **Component:** Server API / SQLite DB
- **Target File(s) & Line Numbers:**
  - [`server/src/services/notificationService.ts#L30-L116`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L30-L116)
  - [`server/src/routes/device/notifications.ts#L15-L35`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L15-L35)
- **Vulnerability Mechanics:**
  1. **Cross-Channel Notification ID Collision & Metadata Leak:** In [`notificationService.publish(channelId, input)`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L34-L40), the deduplication query runs `SELECT * FROM notifications WHERE notification_id = ?` without checking `existing.channel_id === channelId`. If a relay on `Channel A` submits a `notificationId` that already exists on `Channel B`, `publish()` returns `{ created: false, notification: existing }`, and [`routes/device/notifications.ts#L70-L73`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L70-L73) responds with HTTP 200 and `receivedAt: existing.created_at` from `Channel B`! Conversely, if a relay pre-registers predictable `notificationId` strings, it can block another channel from publishing with that `notificationId`. Additionally, `PublishNotificationSchema` ([`notifications.ts#L16`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L16)) only checks `notificationId: z.string().min(1)` with no maximum length.
  2. **Serving Expired Notifications:** [`CleanerService`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/cleanerService.ts#L47) deletes expired notifications only once every 10 minutes. However, neither [`notificationService.catchUp()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L86-L91) nor [`notificationService.getNotification()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L109-L115) filters by `AND expires_at > ?`, allowing subscribers to retrieve notifications up to 10 minutes after their configured TTL has expired.
- **Impact & Threat Scenario:**
  - A relay key holder on one channel can probe whether a given `notificationId` exists on another channel and learn its exact `created_at` timestamp, or cause denial-of-service on predictable notification IDs.
  - Notifications remain accessible via catch-up and direct fetch after their TTL expiration window until the next 10-minute cleaner sweep.
- **Remediation Patch:**
  Bound `notificationId` length in [`routes/device/notifications.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/notifications.ts#L15-L17), verify `existing.channel_id === channelId` in [`notificationService.publish()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/notificationService.ts#L30-L41), and filter by `expires_at > ?` in `catchUp()` and `getNotification()`.

```diff
--- a/server/src/routes/device/notifications.ts
+++ b/server/src/routes/device/notifications.ts
@@ -13,7 +13,7 @@
 import type { SubscriberService } from '../../services/subscriberService.js';
 
 const PublishNotificationSchema = z.object({
-  notificationId: z.string().min(1),
+  notificationId: z.string().min(1).max(128),
   iv: z.string().refine((val) => {
```

```diff
--- a/server/src/services/notificationService.ts
+++ b/server/src/services/notificationService.ts
@@ -36,6 +36,9 @@
     `).get(input.notificationId) as NotificationRow | undefined;
 
     if (existing) {
+      if (existing.channel_id !== channelId) {
+        throw { status: 409, code: 'notification_id_conflict', message: 'Notification ID already exists' };
+      }
       return { created: false, notification: existing };
     }
 
@@ -83,12 +86,13 @@
     hasMore: boolean;
   } {
     const fetchLimit = Math.min(Math.max(1, limit), 100);
+    const now = Date.now();
     const rows = this.db.prepare(`
       SELECT * FROM notifications
-      WHERE channel_id = ? AND created_at > ?
+      WHERE channel_id = ? AND created_at > ? AND expires_at > ?
       ORDER BY created_at ASC
       LIMIT ?
-    `).all(channelId, since, fetchLimit + 1) as NotificationRow[];
+    `).all(channelId, since, now, fetchLimit + 1) as NotificationRow[];
 
     const hasMore = rows.length > fetchLimit;
     const items = rows.slice(0, fetchLimit);
@@ -107,10 +111,11 @@
   }
 
   getNotification(channelId: string, notificationId: string): NotificationRow | null {
+    const now = Date.now();
     const row = this.db.prepare(`
       SELECT * FROM notifications
-      WHERE channel_id = ? AND notification_id = ?
-    `).get(channelId, notificationId) as NotificationRow | undefined;
+      WHERE channel_id = ? AND notification_id = ? AND expires_at > ?
+    `).get(channelId, notificationId, now) as NotificationRow | undefined;
 
     return row || null;
   }
```

---

### [SEC-09] [MEDIUM] Destructive `removeToken` Deletes Entire Subscriber Account Across All Channels & Missing `:channelId` Verification in `initSubscriber`
- **Component:** Server API / SQLite DB
- **Target File(s) & Line Numbers:**
  - [`server/src/services/subscriberService.ts#L65-L153`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/subscriberService.ts#L65-L153), [`#L224-L226`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/subscriberService.ts#L224-L226)
  - [`server/src/routes/device/subscribers.ts#L51-L69`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/subscribers.ts#L51-L69)
  - [`server/src/services/pushService.ts#L32-L37`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L32-L37), [`#L130-L137`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L130-L137)
- **Vulnerability Mechanics:**
  1. **Destructive Subscriber Account Deletion on Invalid FCM Token:** When an Android subscriber pairs without active Google Play Services or while FCM token retrieval temporarily fails, [`CrossNotifyRepository.obtainFcmTokenForServer()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L528) registers a placeholder token `"fcm_pending_<uuid>"`. When the first notification is published to the channel, [`FcmPushService.dispatch()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L126-L137) sends an FCM push to `"fcm_pending_<uuid>"`, receives `messaging/invalid-registration-token` from Firebase, and calls [`this.subscriberService.removeToken(token)`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/subscriberService.ts#L224-L226). Instead of clearing `fcm_token = NULL`, `removeToken()` executes `DELETE FROM subscribers WHERE fcm_token = ?`, **permanently deleting the subscriber row and its `api_key_hash` from the database**! As a result, the subscriber is locked out of `GET /notifications` catch-up sync and `TokenRefreshWorker` (`PUT /subscribers/self`) fails with `401 invalid_api_key`.
  2. **Cross-Subscriber Deletion via Shared/Spoofed `fcm_token`:** Because `DELETE FROM subscribers WHERE fcm_token = ?` is not scoped to a specific `subscriber_id`, if Subscriber B registers an `fcmToken` value matching Subscriber A's `fcm_token` (or if one device subscribes to two channels and its token rotates), any FCM token error wipes all subscriber records sharing that `fcm_token`.
  3. **Missing `:channelId` Verification in `POST /channels/:channelId/subscribers/init`:** In [`routes/device/subscribers.ts#L51-L69`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/routes/device/subscribers.ts#L51-L69), `req.params.channelId` is ignored and never checked against `subscriber.channel_id`.
- **Impact & Threat Scenario:**
  - Any subscriber device that pairs while FCM is temporarily unreachable (`fcm_pending_*`) has its entire subscription account deleted from the server as soon as the first notification is published on the channel.
  - A subscriber key for Channel A can be initialized via `/api/v1/channels/<Channel-B>/subscribers/init` without triggering a `403 channel_mismatch` error.
- **Remediation Patch:**
  1. In [`subscriberService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/subscriberService.ts#L65-L85), verify `expectedChannelId` in `initSubscriber()` and change `removeToken()` to clear `fcm_token = NULL` rather than deleting the subscriber record.
  2. In [`pushService.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/services/pushService.ts#L32-L37), skip placeholder `"fcm_pending_"` tokens during push dispatch.

```diff
--- a/server/src/routes/device/subscribers.ts
+++ b/server/src/routes/device/subscribers.ts
@@ -60,7 +60,11 @@
         }
 
         const keyHash = sha256Hex(rawKey);
-        const result = subscriberService.initSubscriber(keyHash, req.body);
+        const result = subscriberService.initSubscriber(
+          keyHash,
+          req.body,
+          String(req.params.channelId)
+        );
         res.json(result);
       } catch (err) {
         next(err);
```

```diff
--- a/server/src/services/subscriberService.ts
+++ b/server/src/services/subscriberService.ts
@@ -64,7 +64,8 @@
 
   initSubscriber(
     apiKeyHash: string,
-    body: SubscriberInitInput
+    body: SubscriberInitInput,
+    expectedChannelId?: string
   ): {
     subscriberId: string;
     channelId: string;
@@ -78,6 +79,10 @@
       throw { status: 401, code: 'invalid_api_key', message: 'Invalid or revoked subscriber key' };
     }
 
+    if (expectedChannelId && subscriber.channel_id !== expectedChannelId) {
+      throw { status: 403, code: 'channel_mismatch', message: 'Subscriber key does not belong to this channel' };
+    }
+
     const channel = this.db.prepare('SELECT name, status FROM channels WHERE channel_id = ?').get(subscriber.channel_id) as
       | { name: string; status: string }
       | undefined;
@@ -222,7 +227,7 @@
   }
 
   removeToken(fcmToken: string): void {
-    this.db.prepare('DELETE FROM subscribers WHERE fcm_token = ?').run(fcmToken);
+    this.db.prepare('UPDATE subscribers SET fcm_token = NULL WHERE fcm_token = ?').run(fcmToken);
   }
 
   updateLastSeen(subscriberId: string): void {
```

```diff
--- a/server/src/services/pushService.ts
+++ b/server/src/services/pushService.ts
@@ -30,7 +30,9 @@
     }
 
     const subscribers = this.subscriberService.getActiveSubscribersForChannel(channel.channel_id);
-    const validSubscribers = subscribers.filter((s) => s.fcm_token && s.fcm_token.trim().length > 0);
+    const validSubscribers = subscribers.filter(
+      (s) => s.fcm_token && s.fcm_token.trim().length > 0 && !s.fcm_token.startsWith('fcm_pending_')
+    );
 
     if (validSubscribers.length === 0) {
       return { sent: 0, failed: 0, removed: 0 };
```

---

### [SEC-10] [MEDIUM] Exposed Container Port (`0.0.0.0:3000`) Bypassing Reverse Proxy & Missing `.dockerignore` Leaking Local Secrets into Build Context
- **Component:** Containers / CI-CD
- **Target File(s) & Line Numbers:**
  - [`server/docker-compose.yml#L6-L7`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/docker-compose.yml#L6-L7)
  - [`server/Dockerfile#L5-L21`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/Dockerfile#L5-L21)
- **Vulnerability Mechanics:**
  1. **Unconditional Public Port Binding (`0.0.0.0:3000`):** In [`server/docker-compose.yml#L6-L7`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/docker-compose.yml#L6-L7), `ports: - "3000:3000"` binds container port 3000 to all host network interfaces (`0.0.0.0`), even when deploying with the `https` Caddy reverse-proxy profile (`docker compose --profile https up -d`). If `TRUST_PROXY=true` is enabled for Caddy, an external attacker can connect directly to `<host-ip>:3000` (bypassing Caddy TLS termination) and send forged `X-Forwarded-For` headers to bypass `authLimiter` and `deviceIpLimiter` completely!
  2. **Missing `server/.dockerignore`:** There is no `.dockerignore` file in `server/`. Executing `docker compose build` uploads `server/data/secret.key`, `server/data/crossnotify.sqlite*`, and `server/.env` into the Docker build context.
- **Impact & Threat Scenario:**
  - Attackers can reach the Express backend directly on port 3000 over plaintext HTTP and spoof `X-Forwarded-For` to perform unlimited brute-force attempts against `/admin/api/auth/login` when `TRUST_PROXY=true`.
  - Sensitive local files (`data/secret.key`, `.env`) are sent to the Docker daemon during image builds.
- **Remediation Patch:**
  Bind port 3000 to `127.0.0.1` by default (configurable via `HOST_BIND_IP`) in [`server/docker-compose.yml`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/docker-compose.yml#L6-L7) and add `server/.dockerignore`.

```diff
--- a/server/docker-compose.yml
+++ b/server/docker-compose.yml
@@ -4,7 +4,7 @@
     build: .
     container_name: crossnotify
     ports:
-      - "3000:3000"
+      - "${HOST_BIND_IP:-127.0.0.1}:3000:3000"
     volumes:
       - ./data:/app/data
     environment:
```

Create `server/.dockerignore`:
```gitignore
node_modules
dashboard/node_modules
dist
public
data
*.sqlite
*.sqlite-shm
*.sqlite-wal
*.key
.env
.env.*
!.env.example
.git
test
```

---

### [SEC-11] [LOW] Disabled R8/ProGuard Minification & Legacy V1 Signing in Release Builds, Plus Permissive URL/Firebase Validation in `QrPayloadParser`
- **Component:** Android Client
- **Target File(s) & Line Numbers:**
  - [`android/app/build.gradle.kts#L46-L58`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/build.gradle.kts#L46-L58)
  - [`android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt#L140-L152`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt#L140-L152)
- **Vulnerability Mechanics:**
  1. In [`android/app/build.gradle.kts#L54`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/build.gradle.kts#L54), `isMinifyEnabled = false` is set for the `release` build type, leaving all symbol names, cryptographic routines, and unused library code unobfuscated and unshrunk. Additionally, line 46 sets `enableV1Signing = true` even though `minSdk = 26` natively supports APK Signature Scheme v2/v3.
  2. In [`QrPayloadParser.validateServerUrl()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt#L140-L152), URL validation only checks `startsWith("https://") || startsWith("http://")` without structural parsing (`HttpUrl`), allowing URLs with embedded credentials (`https://user:pass@host`), query strings, or fragments, and `validateFirebase()` does not validate `projectId` or `senderId` character sets.
- **Impact & Threat Scenario:**
  - Release APKs are trivially reverse-engineered and include unnecessary attack surface from unshrunk transitive dependencies.
  - Malformed QR URLs or crafted `projectId` strings can cause unexpected Retrofit base URL behavior or Firebase initialization errors.
- **Remediation Patch:**
  Enable R8 minification and resource shrinking in [`android/app/build.gradle.kts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/build.gradle.kts#L46-L58) (disabling legacy V1 JAR signing), and enforce strict URL and Firebase parameter validation in [`QrModels.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt#L140-L152).

```diff
--- a/android/app/build.gradle.kts
+++ b/android/app/build.gradle.kts
@@ -43,7 +43,7 @@
                 storePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
                 keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
                 keyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
-                enableV1Signing = true
+                enableV1Signing = false
                 enableV2Signing = true
                 enableV3Signing = true
             }
@@ -51,7 +51,8 @@
 
     buildTypes {
         release {
-            isMinifyEnabled = false
+            isMinifyEnabled = true
+            isShrinkResources = true
             if (keystoreFile.exists()) {
                 signingConfig = signingConfigs.getByName("release")
             }
```

```diff
--- a/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt
+++ b/android/app/src/main/java/com/crossnotify/app/qr/QrModels.kt
@@ -1,5 +1,6 @@
 package com.crossnotify.app.qr
 
+import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
 import kotlinx.serialization.SerialName
 import kotlinx.serialization.Serializable
 import kotlinx.serialization.json.Json
@@ -140,14 +141,24 @@
     private fun validateServerUrl(url: String) {
         val normalized = normalizeServerUrl(url)
         require(normalized.lowercase().startsWith("https://") || normalized.lowercase().startsWith("http://")) {
             "Server URL must start with https:// or http://"
         }
+        val parsed = normalized.toHttpUrlOrNull()
+            ?: throw IllegalArgumentException("Server URL is not a valid HTTP/HTTPS URL")
+        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
+            "Server URL must not contain embedded credentials"
+        }
+        require(parsed.query == null && parsed.fragment == null) {
+            "Server URL must not contain query parameters or fragments"
+        }
     }
 
     private fun validateFirebase(fb: QrFirebaseInfo) {
-        require(fb.projectId.isNotBlank()) { "Firebase projectId is missing" }
+        require(fb.projectId.matches(Regex("^[a-z0-9-]{1,64}$"))) { "Invalid Firebase projectId" }
         require(fb.appId.isNotBlank()) { "Firebase appId is missing" }
         require(fb.apiKey.isNotBlank()) { "Firebase apiKey is missing" }
-        require(fb.senderId.isNotBlank()) { "Firebase senderId is missing" }
+        require(fb.senderId.matches(Regex("^[0-9]{1,32}$"))) { "Invalid Firebase senderId" }
     }
 }
```

---

### [SEC-12] [LOW] Unzeroed E2E Key Material in JVM Heap & Residual Deleted Key Blobs in SQLite WAL (`secure_delete` Disabled)
- **Component:** Android Client / SQLite DB
- **Target File(s) & Line Numbers:**
  - [`android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L80-L119`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L80-L119)
  - [`android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L201-L210`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L201-L210), [`#L390-L405`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L390-L405)
  - [`android/app/src/main/java/com/crossnotify/app/di/AppModule.kt#L25-L31`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt#L25-L31)
- **Vulnerability Mechanics:**
  1. When `e2eKey` (`ByteArray`) is generated or unwrapped via [`CryptoManager.unwrapBytes()`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L92-L105) inside [`CrossNotifyRepository`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L390-L405), the raw 32-byte key array is never zeroed (`e2eKey.fill(0)`) in a `finally` block, leaving plaintext symmetric keys in JVM heap memory until garbage collection.
  2. Neither the Android Room database ([`AppModule.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt#L25-L31)) nor the server SQLite database ([`server/src/db/index.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/db/index.ts#L10-L16)) enables `PRAGMA secure_delete = ON`. When keys or notifications are deleted or wiped via `markRevokedAndWipeKeys`, the previous bytes remain in unallocated SQLite pages until overwritten.
- **Impact & Threat Scenario:**
  - Memory dumps or root-level forensic extraction after channel revocation can recover unzeroed `ByteArray` keys from heap memory or unallocated SQLite pages.
- **Remediation Patch:**
  Enable `PRAGMA secure_delete = ON` on database open in [`AppModule.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt#L25-L31) and [`server/src/db/index.ts`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/server/src/db/index.ts#L10-L16), and zero `ByteArray` key material in `finally` blocks in [`CrossNotifyRepository.kt`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt#L390-L405).

```diff
--- a/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt
+++ b/android/app/src/main/java/com/crossnotify/app/di/AppModule.kt
@@ -2,6 +2,8 @@
 
 import android.content.Context
 import androidx.room.Room
+import androidx.room.RoomDatabase
+import androidx.sqlite.db.SupportSQLiteDatabase
 import com.crossnotify.app.data.db.CrossNotifyDatabase
 import com.crossnotify.app.data.db.InboxDao
 import com.crossnotify.app.data.db.OutboxDao
@@ -27,7 +29,14 @@
             context,
             CrossNotifyDatabase::class.java,
             "crossnotify.db"
-        ).fallbackToDestructiveMigration().build()
+        )
+            .addCallback(object : RoomDatabase.Callback() {
+                override fun onOpen(db: SupportSQLiteDatabase) {
+                    super.onOpen(db)
+                    db.query("PRAGMA secure_delete = ON").close()
+                }
+            })
+            .fallbackToDestructiveMigration()
+            .build()
     }
```

```diff
--- a/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt
+++ b/android/app/src/main/java/com/crossnotify/app/data/repository/CrossNotifyRepository.kt
@@ -391,15 +391,19 @@
 
         for (channel in activeChannels) {
             val e2eKey = runCatching { cryptoManager.unwrapBytes(channel.e2eKeyEnc) }.getOrNull() ?: continue
-            val notificationId = UUID.randomUUID().toString()
-            val aad = e2eCryptoEngine.buildAad(channel.channelId, notificationId)
-            val plainPayload = NotificationPlaintext(
-                app = appLabel,
-                pkg = pkg,
-                title = title,
-                body = body,
-                ts = timestamp,
-                icon = if (channel.includeIcons) iconBase64 else null,
-                originId = originId
-            )
-            val encrypted = runCatching { e2eCryptoEngine.encrypt(e2eKey, plainPayload, aad) }.getOrNull() ?: continue
+            val notificationId = UUID.randomUUID().toString()
+            val encrypted = try {
+                val aad = e2eCryptoEngine.buildAad(channel.channelId, notificationId)
+                val plainPayload = NotificationPlaintext(
+                    app = appLabel,
+                    pkg = pkg,
+                    title = title,
+                    body = body,
+                    ts = timestamp,
+                    icon = if (channel.includeIcons) iconBase64 else null,
+                    originId = originId
+                )
+                runCatching { e2eCryptoEngine.encrypt(e2eKey, plainPayload, aad) }.getOrNull()
+            } finally {
+                e2eKey.fill(0)
+            } ?: continue
```

---

## 3. Defense-in-Depth & Hardening Recommendations

1. **Trust-On-First-Use (TOFU) SPKI Certificate Pinning via QR Pairing (`CrossNotifyApiClient.kt`):**
   - Because CrossNotify pairs devices via QR codes (`cn-relay` and `cn-sub`), the server can optionally include its TLS leaf or SPKI SHA-256 fingerprint (`spkiPin: "sha256/..."`) inside the QR payload. [`CrossNotifyApiClient`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/api/CrossNotifyApiClient.kt#L35-L78) can then attach an `okhttp3.CertificatePinner` per `ServerEntity`, protecting self-hosted deployments with private CAs or dynamic DNS domains against TLS interception.
2. **Cryptographic Binding of `BiometricPrompt.CryptoObject` to Android Keystore:**
   - Currently, [`CryptoManager`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/crypto/CryptoManager.kt#L46-L74) creates `cn_master` with `setUserAuthenticationRequired(false)` so background services (`CrossNotifyListenerService`, `CrossNotifyMessagingService`, `OutboxWorker`) can encrypt and decrypt while the screen is locked. To cryptographically enforce biometric authentication when exporting a `cn-sub` QR code on a Relay device, store a second copy of the channel's `e2eKey` wrapped under a separate Keystore key (`cn_qr_export_key`) configured with `setUserAuthenticationRequired(true)`, and pass a `BiometricPrompt.CryptoObject(cipher)` into `BiometricPrompt.authenticate()`.
3. **Zero-Knowledge App Name Option (`sendAppName` Default `false`):**
   - In [`RelayChannelEntity`](file:///c:/Users/Pas/Documents/Programmazione/Android/CrossNotify/android/app/src/main/java/com/crossnotify/app/data/db/Entities.kt#L47), `sendAppName` defaults to `true`, which exposes the originating application label (`"Signal"`, `"Authenticator"`, etc.) in plaintext to the server and FCM in `battery_saver` mode. Consider defaulting `sendAppName` to `false` (sending generic `"New notification"` in `battery_saver` pushes while keeping the real `app` and `pkg` strictly inside the AES-256-GCM encrypted payload).
4. **Automated CI/CD Secret Scanning & Dependency Auditing:**
   - Add a GitHub Actions workflow (`.github/workflows/security.yml`) running `gitleaks`, `npm audit --audit-level=high` on `server/` and `server/dashboard/`, and `./gradlew lintRelease` on `android/` on every push and pull request.
package com.crossnotify.app.util

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Extracts, compresses, and decodes media images attached to system notifications
 * (BigPictureStyle pictures, expanded large icons, and MessagingStyle image attachments),
 * and provides shared formatting helpers for handled notification states.
 */
object NotificationMediaHelper {

    private const val MAX_DIMENSION_PX = 720
    private const val FALLBACK_DIMENSION_PX = 480
    private const val MAX_RAW_JPEG_BYTES = 150 * 1024 // ~200 KB base64, fits cleanly within 256 KiB server limit

    // Byte-bounded LRU cache (~8 MiB) dedicated to notification media images
    private val imageCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Attempts to extract a media image from [notification] and encode it as a compressed
     * base64 JPEG string suitable for inclusion inside the E2E-encrypted payload.
     */
    fun extractNotificationImageBase64(context: Context, notification: Notification): String? {
        val bitmap = extractBitmap(context, notification) ?: return null
        return runCatching {
            encodeBitmapToBase64Jpeg(bitmap)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun extractBitmap(context: Context, notification: Notification): Bitmap? {
        val extras = notification.extras ?: return null

        // 1. API 31+ BigPictureStyle EXTRA_PICTURE_ICON
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pictureIcon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            if (pictureIcon != null) {
                iconToBitmap(context, pictureIcon)?.let { return it }
            }
        }

        // 2. Classic BigPictureStyle EXTRA_PICTURE (Bitmap)
        val extraPicture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        }
        if (extraPicture != null && !extraPicture.isRecycled) {
            return extraPicture
        }

        // 3. Expanded large icon (EXTRA_LARGE_ICON_BIG — can be Bitmap or Icon)
        val largeIconBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
        when (largeIconBig) {
            is Bitmap -> if (!largeIconBig.isRecycled) return largeIconBig
            is Icon -> iconToBitmap(context, largeIconBig)?.let { return it }
        }

        // 4. MessagingStyle image attachment (best-effort; some apps restrict URI read permission)
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!rawMessages.isNullOrEmpty()) {
            for (i in rawMessages.indices.reversed()) {
                val bundle = rawMessages[i] as? Bundle ?: continue
                val mimeType = bundle.getString("type").orEmpty()
                if (!mimeType.startsWith("image/", ignoreCase = true)) continue
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getParcelable("uri", Uri::class.java)
                } else {
                    bundle.getParcelable<Uri>("uri")
                } ?: continue
                decodeUriToBitmap(context, uri)?.let { return it }
            }
        }

        return null
    }

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? {
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return null
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
            val (targetW, targetH) = computeScaledDimensions(w, h, MAX_DIMENSION_PX)
            AppIconHelper.drawableToBitmap(drawable, targetW, targetH)
        }.getOrNull()
    }

    private fun decodeUriToBitmap(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun encodeBitmapToBase64Jpeg(source: Bitmap): String? {
        val scaled = scaleDownIfNeeded(source, MAX_DIMENSION_PX)
        val firstPass = compressJpeg(scaled, quality = 78)
        if (firstPass.size <= MAX_RAW_JPEG_BYTES) {
            return Base64.encodeToString(firstPass, Base64.NO_WRAP)
        }

        val secondPass = compressJpeg(scaled, quality = 60)
        if (secondPass.size <= MAX_RAW_JPEG_BYTES) {
            return Base64.encodeToString(secondPass, Base64.NO_WRAP)
        }

        val smaller = scaleDownIfNeeded(source, FALLBACK_DIMENSION_PX)
        val thirdPass = compressJpeg(smaller, quality = 55)
        if (thirdPass.size <= MAX_RAW_JPEG_BYTES) {
            return Base64.encodeToString(thirdPass, Base64.NO_WRAP)
        }

        return null
    }

    private fun scaleDownIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) return source
        val (targetW, targetH) = computeScaledDimensions(w, h, maxDim)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun computeScaledDimensions(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxDim) return w to h
        val scale = maxDim.toFloat() / longest.toFloat()
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun decodeBase64ToBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        val cacheKey = "${base64Str.length}:${base64Str.hashCode()}"
        imageCache.get(cacheKey)?.let { return it }

        return runCatching {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                imageCache.put(cacheKey, bitmap)
            }
            bitmap
        }.getOrNull()
    }

    /**
     * Returns concise, human-readable wording for a notification that has already been
     * dismissed, opened, or actioned on the relay or another subscriber.
     */
    fun formatHandledStatus(reason: String?): String {
        return when (reason?.lowercase()) {
            "actioned" -> "Handled • Action completed"
            "opened" -> "Handled • Opened on relay"
            "dismissed" -> "Handled • Dismissed on relay"
            else -> "Handled"
        }
    }
}

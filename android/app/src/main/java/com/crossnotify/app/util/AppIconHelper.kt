package com.crossnotify.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Utility for loading, caching, encoding, and rendering application icons.
 * Provides LRU caches for bitmaps and base64 payloads to minimize CPU/memory overhead.
 */
object AppIconHelper {

    private val bitmapCache = LruCache<String, Bitmap>(250)
    private val base64Cache = LruCache<String, String>(250)
    private val decodedBitmapCache = LruCache<String, Bitmap>(100)

    fun getCachedBitmap(packageName: String): Bitmap? = bitmapCache.get(packageName)

    /**
     * Retrieves or renders the application icon as a [Bitmap] of size [sizePx] x [sizePx].
     */
    fun getAppIconBitmap(context: Context, packageName: String, sizePx: Int = 96): Bitmap? {
        val cacheKey = "$packageName:$sizePx"
        bitmapCache.get(cacheKey)?.let { return it }

        return runCatching {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, sizePx, sizePx)
            bitmapCache.put(cacheKey, bitmap)
            bitmap
        }.getOrNull()
    }

    /**
     * Converts a [Drawable] into a square [Bitmap] of specified dimensions,
     * safely handling AdaptiveIconDrawable, VectorDrawable, and BitmapDrawable.
     */
    fun drawableToBitmap(drawable: Drawable, width: Int = 96, height: Int = 96): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width == width && bmp.height == height) {
                return bmp
            }
            return Bitmap.createScaledBitmap(bmp, width, height, true)
        }

        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else width
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return if (w != width || h != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
    }

    /**
     * Returns a compressed PNG base64 string of the app icon (48x48) for inclusion
     * in the encrypted notification payload.
     */
    fun getAppIconBase64(context: Context, packageName: String): String? {
        base64Cache.get(packageName)?.let { return it }

        return runCatching {
            val bitmap = getAppIconBitmap(context, packageName, sizePx = 48) ?: return null
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            base64Cache.put(packageName, base64)
            base64
        }.getOrNull()
    }

    /**
     * Decodes a base64 encoded PNG/JPEG image into an Android [Bitmap].
     * Uses an LRU cache to avoid re-decoding across Compose recompositions.
     */
    fun decodeBase64ToBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        decodedBitmapCache.get(base64Str)?.let { return it }

        return runCatching {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                decodedBitmapCache.put(base64Str, bitmap)
            }
            bitmap
        }.getOrNull()
    }
}

/**
 * Composable that displays an installed app's icon asynchronously with local caching.
 */
@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) {
        mutableStateOf(AppIconHelper.getCachedBitmap("$packageName:96"))
    }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                AppIconHelper.getAppIconBitmap(context, packageName, sizePx = 96)
            }
            bitmap = loaded
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

package com.vknxworks.callctrl.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.theme.CtrlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    AppLock.expectReturn()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Starts another app; that trip doesn't lock ContactCtrl. False when no app can do it. */
fun Context.launch(intent: Intent): Boolean = try {
    AppLock.expectReturn()
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** Small cache of decoded contact photos, so scrolling the list doesn't decode the same picture again. */
private object PhotoCache {
    val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

@Composable
fun rememberPhoto(uri: String?, key: Int = 0): Bitmap? {
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(initialValue = uri?.let { PhotoCache.cache.get(it) }, uri, key) {
        if (uri == null) { value = null; return@produceState }
        PhotoCache.cache.get(uri)?.let { if (key == 0) { value = it; return@produceState } }
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }?.also { PhotoCache.cache.put(uri, it) }
    }
    return photo
}

fun dropPhoto(uri: String?) {
    if (uri != null) PhotoCache.cache.remove(uri)
}

/** A contact's picture, or their initials on a plain square. */
@Composable
fun ContactAvatar(name: String, photoUri: String?, size: Dp = 44.dp, modifier: Modifier = Modifier, photoKey: Int = 0) {
    val photo = rememberPhoto(photoUri, photoKey)
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier.size(size).clip(shape).background(CtrlColors.Raised).border(1.dp, CtrlColors.Outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else if (name.none { it.isLetter() }) {
            // A number without a contact: a plain person, not digits.
            Icon(Icons.Outlined.Person, null, Modifier.size(size * 0.5f), tint = CtrlColors.Muted)
        } else {
            Text(
                initials(name),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold),
                color = CtrlColors.Text,
            )
        }
    }
}

fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
    return when {
        words.isEmpty() -> name.trim().take(1).uppercase().ifEmpty { "#" }
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

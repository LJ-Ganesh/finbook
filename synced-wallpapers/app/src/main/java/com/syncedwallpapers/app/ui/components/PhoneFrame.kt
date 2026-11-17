package com.syncedwallpapers.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.syncedwallpapers.app.delivery.SafeZone

/** Aspect ratio (w/h) of this phone's screen in portrait. */
@Composable
fun screenAspect(): Float {
    val c = LocalConfiguration.current
    val w = minOf(c.screenWidthDp, c.screenHeightDp).toFloat()
    val h = maxOf(c.screenWidthDp, c.screenHeightDp).toFloat()
    // Configuration excludes system bars; real screens are ~8% taller.
    return w / (h * 1.08f)
}

/** Remote image for previews: memory cache only, never written to disk. */
@Composable
fun secureRequest(url: String?, key: String?) = ImageRequest.Builder(LocalContext.current)
    .data(url)
    .diskCachePolicy(CachePolicy.DISABLED)
    .memoryCacheKey(key)
    .build()

/**
 * The photo inside a frame with this phone's proportions, cropped around the focal
 * point the way the device will crop it, with the clock area marked and the reminder
 * text in its safe zone.
 */
@Composable
fun PhoneFrame(
    modifier: Modifier = Modifier,
    bitmap: Bitmap? = null,
    url: String? = null,
    cacheKey: String? = null,
    focalX: Float = 0.5f,
    focalY: Float = 0.5f,
    reminder: String? = null,
    reminderStyle: Int = 0,
    showClockArea: Boolean = true,
) {
    val alignment = BiasAlignment(focalX * 2 - 1, focalY * 2 - 1)
    Box(
        modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(screenAspect())
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF202322))
            .border(6.dp, Color(0xFF111312), RoundedCornerShape(28.dp)),
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = alignment, modifier = Modifier.fillMaxSize())
        } else if (url != null) {
            AsyncImage(secureRequest(url, cacheKey), null, contentScale = ContentScale.Crop, alignment = alignment, modifier = Modifier.fillMaxSize())
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            if (showClockArea) {
                Canvas(Modifier.fillMaxSize()) {
                    val top = size.height * SafeZone.CLOCK_TOP
                    val bottom = size.height * SafeZone.CLOCK_BOTTOM
                    drawRoundRect(Color.White.copy(alpha = 0.75f), topLeft = Offset(size.width * 0.12f, top), size = Size(size.width * 0.76f, bottom - top),
                        cornerRadius = CornerRadius(18f), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
                }
                Text("12:45", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 8f)),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = h * 0.12f))
            }
            if (!reminder.isNullOrBlank()) {
                val (bg, fg, size, weight) = when (reminderStyle) {
                    1 -> Quad(Color.Transparent, Color.White, 18.sp, FontWeight.Bold)
                    2 -> Quad(Color(0xE6FAFAF8), Color(0xFF1C1E1D), 11.sp, FontWeight.Medium)
                    else -> Quad(Color(0x78000000), Color.White, 13.sp, FontWeight.Medium)
                }
                Text(reminder, color = fg, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center,
                    style = TextStyle(shadow = if (reminderStyle == 1) Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f) else null),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = h * ((SafeZone.TEXT_TOP + SafeZone.TEXT_BOTTOM) / 2 - 0.04f))
                        .padding(horizontal = 24.dp)
                        .background(bg, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

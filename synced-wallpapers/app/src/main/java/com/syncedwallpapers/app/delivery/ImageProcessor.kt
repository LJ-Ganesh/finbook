package com.syncedwallpapers.app.delivery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Size
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
import com.syncedwallpapers.app.core.AppError
import com.syncedwallpapers.app.core.AppException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class ImageProcessor @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val MAX_INPUT_BYTES = 20L * 1024 * 1024
        const val UPLOAD_LONG_EDGE = 2560
        const val UPLOAD_MAX_BYTES = 2 * 1024 * 1024 - 64 * 1024 // stay safely under the 2 MB bucket limit
        val ACCEPTED = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
    }

    /**
     * Validates the picked photo and returns a re-encoded JPEG: long edge ≤ 2560 px,
     * < 2 MB. Re-encoding from pixels drops all EXIF, including location.
     */
    fun prepareUpload(uri: Uri): ByteArray {
        val type = context.contentResolver.getType(uri)?.lowercase()
        if (type != null && type !in ACCEPTED) fail("Use a JPEG, PNG, WebP or HEIC photo.")
        val size = querySize(uri)
        if (size != null && size > MAX_INPUT_BYTES) fail("That photo is larger than 20 MB.")
        val bitmap = decode(uri, UPLOAD_LONG_EDGE)
        try {
            return encodeUnder(bitmap, UPLOAD_MAX_BYTES)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes [uri] (orientation applied) with the long edge at most [maxLong]. */
    fun decode(uri: Uri, maxLong: Int): Bitmap {
        val decoded = try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val s = FocalCrop.downscale(info.size.width, info.size.height, maxLong)
                    if (s < 1f) decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                decodeLegacy(uri, maxLong)
            }
        } catch (e: AppException) {
            throw e
        } catch (e: Exception) {
            fail(if (Build.VERSION.SDK_INT < 28) "This photo format isn't supported on this Android version." else "Couldn't read that photo.")
        }
        val s = FocalCrop.downscale(decoded.width, decoded.height, maxLong)
        if (s >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * s).roundToInt(), (decoded.height * s).roundToInt(), true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    // Android 8.x: BitmapFactory (no HEIC) + manual EXIF orientation.
    private fun decodeLegacy(uri: Uri, maxLong: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) fail("Couldn't read that photo.")
        val opts = BitmapFactory.Options().apply { inSampleSize = FocalCrop.sampleSize(bounds.outWidth, bounds.outHeight, maxLong) }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: fail("Couldn't read that photo.")
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        bmp.recycle()
        return rotated
    }

    fun decodeBytes(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: fail("The wallpaper image is damaged.")

    /** This device's full screen size in pixels (the wallpaper target). */
    fun screenSize(): Size {
        val wm = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        // Wallpapers are portrait; use the portrait orientation even if we're in landscape.
        return Size(minOf(w, h), maxOf(w, h))
    }

    /** Crops [src] to the screen around the focal point and draws the reminder text. */
    fun renderForScreen(src: Bitmap, screen: Size, fx: Float, fy: Float, reminder: String?, style: Int): Bitmap {
        val crop = FocalCrop.compute(src.width, src.height, screen.width, screen.height, fx, fy)
        val out = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, Rect(crop.left, crop.top, crop.right, crop.bottom), Rect(0, 0, screen.width, screen.height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (!reminder.isNullOrBlank()) drawReminder(canvas, screen, reminder.trim().take(60), style)
        return out
    }

    private fun drawReminder(canvas: Canvas, screen: Size, text: String, style: Int) {
        val w = screen.width
        val density = w / 400f // scale typography to the screen width
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = when (style) { 1 -> 30f; 2 -> 18f; else -> 22f } * density
            typeface = when (style) { 1 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD); else -> Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            color = if (style == 2) Color.rgb(28, 30, 29) else Color.WHITE
            letterSpacing = if (style == 2) 0.06f else 0f
            if (style == 1) setShadowLayer(8f * density, 0f, 2f * density, Color.argb(160, 0, 0, 0))
        }
        val maxWidth = (w * 0.78f).roundToInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        val bandTop = screen.height * SafeZone.TEXT_TOP
        val bandBottom = screen.height * SafeZone.TEXT_BOTTOM
        val textTop = bandTop + ((bandBottom - bandTop) - layout.height) / 2f
        val left = (w - maxWidth) / 2f
        if (style != 1) {
            val pad = 14f * density
            val lineW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            val rect = RectF(w / 2f - lineW / 2 - pad * 1.4f, textTop - pad, w / 2f + lineW / 2 + pad * 1.4f, textTop + layout.height + pad)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (style == 2) Color.argb(225, 250, 250, 248) else Color.argb(120, 0, 0, 0) }
            canvas.drawRoundRect(rect, 22f * density, 22f * density, bg)
        }
        canvas.save()
        canvas.translate(left, textTop)
        layout.draw(canvas)
        canvas.restore()
    }

    fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray()
    }

    private fun encodeUnder(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var current = bitmap
        var quality = 90
        while (true) {
            val bytes = encodeJpeg(current, quality)
            if (bytes.size <= maxBytes) { if (current != bitmap) current.recycle(); return bytes }
            if (quality > 60) { quality -= 10; continue }
            // Still too big at quality 60: shrink by 15% and try again.
            val next = Bitmap.createScaledBitmap(current, (current.width * 0.85f).roundToInt(), (current.height * 0.85f).roundToInt(), true)
            if (current != bitmap) current.recycle()
            current = next
            quality = 80
            if (max(current.width, current.height) < 600) return encodeJpeg(current, 60)
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun fail(message: String): Nothing = throw AppException(AppError.Unknown(message))
}

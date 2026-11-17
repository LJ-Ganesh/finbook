package com.syncedwallpapers.app.delivery

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle (left, top, right, bottom) so this stays testable without Android. */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

object FocalCrop {
    /**
     * The largest crop of a [srcW]×[srcH] image with the aspect ratio of [dstW]×[dstH],
     * centred on the focal point (fx, fy in 0..1) and clamped inside the image.
     */
    fun compute(srcW: Int, srcH: Int, dstW: Int, dstH: Int, fx: Float, fy: Float): CropRect {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        val dstRatio = dstW.toDouble() / dstH
        val srcRatio = srcW.toDouble() / srcH
        val (cw, ch) = if (srcRatio > dstRatio) {
            ((srcH * dstRatio).roundToInt().coerceIn(1, srcW)) to srcH
        } else {
            srcW to ((srcW / dstRatio).roundToInt().coerceIn(1, srcH))
        }
        val cx = fx.coerceIn(0f, 1f) * srcW
        val cy = fy.coerceIn(0f, 1f) * srcH
        val left = (cx - cw / 2.0).roundToInt().coerceIn(0, srcW - cw)
        val top = (cy - ch / 2.0).roundToInt().coerceIn(0, srcH - ch)
        return CropRect(left, top, left + cw, top + ch)
    }

    /** Sample size for decoding so the bitmap is at least [reqLong] on its long edge. */
    fun sampleSize(w: Int, h: Int, reqLong: Int): Int {
        var s = 1
        val long = max(w, h)
        while (long / (s * 2) >= reqLong) s *= 2
        return s
    }

    /** Scale factor so the long edge is at most [maxLong]. */
    fun downscale(w: Int, h: Int, maxLong: Int): Float = min(1f, maxLong.toFloat() / max(w, h))
}

/**
 * Reminder text goes in a safe zone away from the clock: the lock-screen clock and
 * status bar live in roughly the top 35% of the screen on current Android versions,
 * and the bottom ~12% holds the navigation bar / shortcuts. We use the band between
 * 62% and 82% of the height.
 */
object SafeZone {
    const val CLOCK_TOP = 0.06f
    const val CLOCK_BOTTOM = 0.35f
    const val TEXT_TOP = 0.62f
    const val TEXT_BOTTOM = 0.82f
}

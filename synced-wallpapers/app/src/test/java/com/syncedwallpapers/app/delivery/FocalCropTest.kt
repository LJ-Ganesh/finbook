package com.syncedwallpapers.app.delivery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocalCropTest {
    @Test fun `landscape photo on a portrait phone keeps full height and follows the focal point`() {
        val c = FocalCrop.compute(4000, 3000, 1080, 2400, 0.8f, 0.5f)
        assertThat(c.height).isEqualTo(3000)
        assertThat(c.width).isEqualTo(1350) // 3000 * 1080/2400
        assertThat(c.left + c.width / 2).isWithin(1).of(3200)
    }

    @Test fun `focal point at the edge is clamped inside the image`() {
        val c = FocalCrop.compute(4000, 3000, 1080, 2400, 1f, 0f)
        assertThat(c.right).isEqualTo(4000)
        assertThat(c.top).isEqualTo(0)
    }

    @Test fun `tall photo on a phone crops the height`() {
        val c = FocalCrop.compute(1000, 5000, 1080, 2400, 0.5f, 0.1f)
        assertThat(c.width).isEqualTo(1000)
        assertThat(c.height).isEqualTo(2222)
        assertThat(c.top).isEqualTo(0)
    }

    @Test fun `downscale and sample size`() {
        assertThat(FocalCrop.downscale(5120, 2880, 2560)).isEqualTo(0.5f)
        assertThat(FocalCrop.downscale(1000, 800, 2560)).isEqualTo(1f)
        assertThat(FocalCrop.sampleSize(8000, 6000, 2560)).isEqualTo(2)
    }

    @Test fun `reminder text zone never touches the clock zone`() {
        assertThat(SafeZone.TEXT_TOP).isGreaterThan(SafeZone.CLOCK_BOTTOM)
    }
}

package com.syncedwallpapers.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syncedwallpapers.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Starts the real app (real Application, Hilt graph, WorkManager, Supabase client) and
 * walks the first onboarding screens. Catches startup crashes and wiring errors, and
 * saves screenshots to app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun `app starts signed out and onboarding works`() {
        val deadline = System.currentTimeMillis() + 20_000
        while (compose.onAllNodesWithTextCount("Get started") == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200); org.robolectric.shadows.ShadowLooper.idleMainLooper(); compose.waitForIdle()
        }
        if (compose.onAllNodesWithTextCount("Get started") == 0) {
            shot("0-stuck")
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
            error("stuck: auth=${vm.authState.value} settings=${vm.settings.value}")
        }
        compose.onNodeWithText("One photo, everyone's phone").assertExists()
        compose.onNodeWithText("Anyone can screenshot their own home screen.").assertExists()
        shot("1-concept")
        compose.onNodeWithText("Get started").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Continue with Google").assertExists()
        shot("2-sign-in")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}

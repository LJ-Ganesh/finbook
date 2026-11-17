package com.syncedwallpapers.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.syncedwallpapers.app.ui.AppViewModel
import com.syncedwallpapers.app.ui.nav.AppRoot
import com.syncedwallpapers.app.ui.theme.SyncedTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()
    private val route = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.onForeground() } }
        setContent {
            SyncedTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(app, route.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // syncedwallpapers://join/CODE
        intent.data?.takeIf { it.scheme == "syncedwallpapers" && it.host == "join" }?.lastPathSegment?.let { app.onInviteLink(it) }
        intent.getStringExtra(EXTRA_ROUTE)?.let { route.value = it }
    }

    companion object { const val EXTRA_ROUTE = "route" }
}

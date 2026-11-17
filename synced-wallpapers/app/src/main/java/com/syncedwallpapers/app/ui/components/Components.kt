package com.syncedwallpapers.app.ui.components

import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syncedwallpapers.app.core.AppError
import com.syncedwallpapers.app.health.Health
import com.syncedwallpapers.app.ui.theme.Amber
import com.syncedwallpapers.app.ui.theme.Green

/** Blocks screenshots and the recents thumbnail while a screen shows a proposal image. */
@Composable
fun SecureScreen() {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun SwCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    outlined: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = if (outlined) MaterialTheme.colorScheme.surface else container)
    val border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = colors, border = border, content = inner)
    else Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = colors, border = border, content = inner)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ErrorState(error: AppError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val icon = if (error is AppError.Network || error is AppError.ServerWaking) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline
    EmptyState(icon, if (error is AppError.ServerWaking) "One moment" else "Couldn't load this", error.userMessage, modifier) {
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer, action: (@Composable () -> Unit)? = null) {
    Surface(color = color, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/** "6 of 8 approved": the ring fills as votes arrive. */
@Composable
fun ProgressRing(done: Int, total: Int, label: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val fraction by animateFloatAsState(if (total <= 0) 0f else done.toFloat() / total, label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, false, style = stroke)
                drawArc(fill, -90f, 360f * fraction, false, style = stroke)
            }
            Text("$done/$total", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HealthDot(status: Health, modifier: Modifier = Modifier) {
    val c = when (status) { Health.GREEN -> Green; Health.AMBER -> Amber; Health.RED -> MaterialTheme.colorScheme.error }
    Surface(shape = CircleShape, color = c, modifier = modifier.size(12.dp)) {}
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Confirmation for anything destructive. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(onClick = onConfirm, colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Optional-reason dialog used for Reject. */
@Composable
fun ReasonDialog(title: String, body: String, confirm: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                OutlinedTextField(reason, { if (it.length <= 200) reason = it }, label = { Text("Reason (optional)") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(reason.trim().ifBlank { null }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().height(52.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text)
    }
}

@Composable
fun ClickableRow(title: String, subtitle: String? = null, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun rememberToggle(initial: Boolean = false) = remember { mutableStateOf(initial) }

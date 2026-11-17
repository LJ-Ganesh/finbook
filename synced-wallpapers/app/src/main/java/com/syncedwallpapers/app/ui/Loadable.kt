package com.syncedwallpapers.app.ui

import com.syncedwallpapers.app.core.AppError
import com.syncedwallpapers.app.core.appError

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val error: AppError) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Ready)?.value

suspend fun <T> load(block: suspend () -> T): Loadable<T> =
    try { Loadable.Ready(block()) } catch (t: Throwable) { if (t is kotlinx.coroutines.CancellationException) throw t; Loadable.Failed(t.appError) }

/** Runs an action and returns a user-facing error message, or null on success. */
suspend fun runAction(block: suspend () -> Unit): String? =
    try { block(); null } catch (t: Throwable) { if (t is kotlinx.coroutines.CancellationException) throw t; t.appError.userMessage }

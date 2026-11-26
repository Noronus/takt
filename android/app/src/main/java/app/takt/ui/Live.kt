package app.takt.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.takt.TaktApp
import app.takt.data.DeparturesResponse
import app.takt.data.Timed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

/** Live state of something that is loaded and refreshed periodically. */
class Live<T> {
    var data by mutableStateOf<Timed<T>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Departures of a station: shows the cached answer immediately, then refreshes every
 * [intervalMs] while the screen is visible. Never blocks the UI.
 */
@Composable
fun rememberDepartures(station: String, intervalMs: Long = 20_000, stops: String? = null): Pair<Live<DeparturesResponse>, () -> Unit> {
    val repo = TaktApp.instance.repo
    val live = remember(station, stops) { Live<DeparturesResponse>().also { it.data = repo.cachedDepartures(station, stops) } }
    val scope = rememberCoroutineScope()
    var job by remember(station, stops) { mutableStateOf<Job?>(null) }
    val load: () -> Unit = {
        if (job?.isActive != true) job = scope.launch {
            live.loading = true
            try {
                live.data = repo.departures(station, stops = stops)
                live.error = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Takt", "departures $station", e)
                live.error = e.message ?: e.javaClass.simpleName
            } finally { live.loading = false }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(station, stops) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                load()
                // retry sooner after an error
                // retry sooner after an error, and refetch quickly when the server answered with old data
                // (it polls slowly while nobody uses the app and speeds up on our request)
                val stale = (live.data?.ageSeconds() ?: 0) > 90
                delay(if (live.error != null) 8_000 else if (stale) 5_000 else intervalMs)
            }
        }
    }
    return live to load
}

/** Age of the server's realtime data, refreshed every [intervalMs] while the screen is visible. */
@Composable
fun rememberRtAge(intervalMs: Long = 20_000): Live<app.takt.data.RtResponse> {
    val repo = TaktApp.instance.repo
    val live = remember { Live<app.takt.data.RtResponse>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                live.loading = true
                try { live.data = repo.rt(); live.error = null }
                catch (e: CancellationException) { throw e } catch (e: Exception) { live.error = e.message ?: e.javaClass.simpleName }
                finally { live.loading = false }
                delay(if (live.error != null) 8_000 else intervalMs)
            }
        }
    }
    return live
}

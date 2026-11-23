package app.takt.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** What the guide service currently shows, for the in-app banner. */
data class GuideStatus(val title: String, val text: String)

@Serializable
data class GuidedJourney(
    val itinerary: Itinerary,
    val startedAt: Long,
    val alerted: Set<Int> = emptySet(),
    /** Last delay (minutes) we told the user about, per key: "b<leg>" departure, "a<leg>" arrival. */
    val delays: Map<String, Int> = emptyMap(),
    /** Flags already notified: "x<leg>" cancelled, "c<leg>" connection at risk. */
    val flags: Set<String> = emptySet(),
)

/**
 * "Fahrt begleiten": keeps the chosen journey on disk (survives process death) and
 * starts/stops the foreground service that wakes the user before each exit stop.
 */
class Guide(private val ctx: Context, private val api: Api) {
    private val file = File(ctx.filesDir, "guide.json")
    private val _active = MutableStateFlow<GuidedJourney?>(load())
    val active: StateFlow<GuidedJourney?> = _active
    val status = MutableStateFlow<GuideStatus?>(null)

    private fun load(): GuidedJourney? = runCatching {
        if (file.exists()) api.json.decodeFromString(GuidedJourney.serializer(), file.readText()) else null
    }.getOrNull()

    fun save(g: GuidedJourney) {
        _active.value = g
        runCatching { file.writeText(api.json.encodeToString(GuidedJourney.serializer(), g)) }
    }

    fun start(it: Itinerary) {
        save(GuidedJourney(it, System.currentTimeMillis() / 1000))
        ContextCompat.startForegroundService(ctx, Intent(ctx, app.takt.GuideService::class.java))
    }

    /** Restarts the service for a saved journey (no-op if it is running; onStartCommand is idempotent). */
    fun ensureRunning() {
        if (_active.value == null) return
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, app.takt.GuideService::class.java)) }
    }

    fun stop() {
        android.util.Log.i("TaktGuide", "guide stopped", Throwable("stack"))
        _active.value = null
        status.value = null
        file.delete()
        ctx.stopService(Intent(ctx, app.takt.GuideService::class.java))
    }

    fun isActive(it: Itinerary) = _active.value?.itinerary?.let { a -> a.id == it.id && a.legs.firstOrNull()?.trip == it.legs.firstOrNull()?.trip } == true
}

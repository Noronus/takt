package app.takt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads live data and keeps the last answer per station on disk, so a station opens instantly
 * (marked with its age) and still shows something without network.
 */
class Repo(ctx: Context, private val api: Api, private val prefs: Prefs) {
    private val dir = File(ctx.cacheDir, "deps").apply { mkdirs() }
    private val mem = HashMap<String, Timed<DeparturesResponse>>()

    private data class Wrap(val at: Long, val body: String)

    /** Cache key: the station, or one platform of it ([stops] = its MOTIS stop ids, comma separated). */
    private fun key(station: String, stops: String?) = if (stops == null) station else "$station~${stops.hashCode().toUInt()}"

    fun cachedDepartures(station: String, stops: String? = null): Timed<DeparturesResponse>? {
        val k = key(station, stops)
        mem[k]?.let { return it }
        val f = File(dir, "$k.json")
        if (!f.exists()) return null
        return runCatching {
            val lines = f.readText().split('\n', limit = 2)
            val r = api.json.decodeFromString(DeparturesResponse.serializer(), lines[1])
            Timed(r, lines[0].toLong(), r.rt.feedAge, fromCache = true)
        }.getOrNull()
    }

    suspend fun departures(station: String, n: Int = 40, stops: String? = null): Timed<DeparturesResponse> {
        // start the list earlier when departures of the last minutes should stay visible
        val past = prefs.pastMin
        val from = if (past > 0) (System.currentTimeMillis() / 1000 - past * 60L).toString() else null
        val (body, _) = api.get("/api/v1/departures", mapOf("station" to station, "n" to n.toString(), "time" to from, "stops" to stops))
        val now = System.currentTimeMillis()
        val r = withContext(Dispatchers.Default) { api.json.decodeFromString(DeparturesResponse.serializer(), body ?: throw ApiException("leere Antwort")) }
        val t = Timed(r, now, if (r.source == "motis") r.rt.feedAge else 0)
        val k = key(station, stops)
        mem[k] = t
        withContext(Dispatchers.IO) { runCatching { File(dir, "$k.json").writeText("$now\n$body") } }
        return t
    }

    suspend fun plan(from: String, to: String, time: Long?, arriveBy: Boolean, cursor: String?): Timed<PlanResponse> {
        val r: PlanResponse = api.getJson("/api/v1/plan", mapOf(
            "from" to from, "to" to to, "time" to time?.toString(),
            "arriveBy" to if (arriveBy) "1" else null, "cursor" to cursor,
            "prefer" to prefs.preferred.value.mapNotNull { it.station }.take(3).joinToString(",").ifBlank { null },
            "slack" to if (prefs.preferred.value.isNotEmpty()) prefs.preferSlack.toString() else null,
        ), attempts = 2)
        return Timed(r, System.currentTimeMillis(), r.rt.feedAge)
    }

    suspend fun trip(id: String): Timed<TripResponse> {
        val r: TripResponse = api.getJson("/api/v1/trip", mapOf("id" to id))
        return Timed(r, System.currentTimeMillis(), r.rt.feedAge)
    }

    /** Running vehicles in a box ("lat,lon"); one try only, the map asks again soon anyway. */
    suspend fun vehicles(min: String, max: String, zoom: Int): VehiclesResponse =
        api.getJson("/api/v1/vehicles", mapOf("min" to min, "max" to max, "zoom" to zoom.toString()), attempts = 1)

    /** Next line per platform of up to 40 stations, for the platform dots; one try only, the map asks again soon. */
    suspend fun next(stations: List<String>): NextResponse =
        api.getJson("/api/v1/next", mapOf("stations" to stations.joinToString(",")), attempts = 1)

    /** Age of the server's realtime data only (for the nearby list, which has no departures). */
    suspend fun rt(): Timed<RtResponse> {
        val r: RtResponse = api.getJson("/api/v1/rt", attempts = 1)
        return Timed(r, System.currentTimeMillis(), r.rt.feedAge)
    }

    /** The server's data sources with their age (settings screen); one try only, it is asked again soon. */
    suspend fun sources(): SourcesResponse = api.getJson("/api/v1/sources", attempts = 1)

    suspend fun alerts(): Timed<AlertsResponse> {
        val r: AlertsResponse = api.getJson("/api/v1/alerts")
        return Timed(r, System.currentTimeMillis(), r.age)
    }

    suspend fun geocode(text: String): List<GeoMatch> = api.getJson("/api/v1/geocode", mapOf("text" to text), attempts = 1)
}

package app.takt.ui

import app.takt.data.ModeFilter
import app.takt.TaktApp
import app.takt.data.NextDep
import app.takt.data.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

/**
 * Labels the dots on the map with the line that leaves there next: station dots from [LABEL_ZOOM] on, platform
 * dots once they are shown (their platform number by default, see settings). Asks the server for the visible stations
 * every 20 s and when the map moved.
 */
object PlatformLines {
    private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    const val LABEL_ZOOM = 14.5
    private const val PER_REQUEST = 40   // server limit
    private const val MAX = 120          // stations per round (three requests)

    suspend fun run(holder: MapHolder) {
        var shown: List<String> = emptyList()
        var fetchedAt = 0L
        var codes = TaktApp.instance.prefs.platCode
        var empty = true
        while (true) {
            val m = holder.map
            if (m == null || m.cameraPosition.zoom < LABEL_ZOOM) {
                if (!empty) { holder.setPlatformLines(EMPTY); empty = true; shown = emptyList() }
                delay(1_000); continue
            }
            val vis = m.projection.visibleRegion.latLngBounds
            val dLat = vis.latitudeSpan * 0.2; val dLon = vis.longitudeSpan * 0.2
            val cLat = vis.center.latitude; val cLon = vis.center.longitude
            val hidden = TaktApp.instance.prefs.hiddenModes.value
            val list = ModeFilter.stations(TaktApp.instance.stations.list.value, hidden).asSequence()
                .filter { it.lat in (vis.latitudeSouth - dLat)..(vis.latitudeNorth + dLat) && it.lon in (vis.longitudeWest - dLon)..(vis.longitudeEast + dLon) }
                .sortedBy { (it.lat - cLat) * (it.lat - cLat) + (it.lon - cLon) * (it.lon - cLon) }
                .take(MAX).toList()
            val ids = list.map { it.id }
            val nowCodes = TaktApp.instance.prefs.platCode
            if (ids.isNotEmpty() && (ids.toSet() != shown.toSet() || nowCodes != codes || System.currentTimeMillis() - fetchedAt > 20_000)) {
                try {
                    val next = coroutineScope {
                        ids.chunked(PER_REQUEST).map { c -> async { TaktApp.instance.repo.next(c).stations } }.awaitAll()
                    }.fold(emptyMap<String, Map<String, NextDep>>()) { a, b -> a + b }
                    holder.setPlatformLines(geojson(list, next, nowCodes))
                    empty = false
                    shown = ids; fetchedAt = System.currentTimeMillis(); codes = nowCodes
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w("Takt", "next: ${e.message}")
                    fetchedAt = System.currentTimeMillis() - 15_000   // try again in ~5 s
                }
            }
            delay(1_000)
        }
    }

    private fun StringBuilder.feature(lat: Double, lon: Double, props: String) {
        if (last() != '[') append(',')
        append("{\"type\":\"Feature\",\"properties\":{").append(props)
            .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(lon).append(',').append(lat).append("]}}")
    }

    private fun geojson(list: List<Station>, next: Map<String, Map<String, NextDep>>, codes: Boolean): String {
        val sb = StringBuilder(EMPTY.removeSuffix("]}"))
        for (s in list) {
            val hidden = TaktApp.instance.prefs.hiddenModes.value
            val byStop = next[s.id].orEmpty().filterValues { ModeFilter.shown(it.cat, it.mode, hidden) }
            // station dot: the earliest departure of all its platforms
            byStop.values.minByOrNull { it.rt ?: it.t }?.let { d ->
                sb.feature(s.lat, s.lon, "\"kind\":\"st\",\"pl\":${if (s.plats.isNotEmpty()) 1 else 0},\"line\":${JsonPrimitive(d.line)}")
            }
            for (p in s.plats) {
                // a merged dot can have several stop ids: the earliest departure of them
                val label = if (codes) p.code.ifBlank { null } else p.ids.mapNotNull { byStop[it] }.minByOrNull { it.rt ?: it.t }?.line
                if (label != null) sb.feature(p.lat, p.lon, "\"kind\":\"pl\",\"line\":${JsonPrimitive(label)}")
            }
        }
        return sb.append("]}").toString()
    }
}

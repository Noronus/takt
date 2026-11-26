package app.takt.ui

import app.takt.TaktApp
import app.takt.data.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

/**
 * Labels the platform dots with the line that leaves there next. Runs while the map is shown: when zoomed in
 * far enough for platform dots, it asks the server for the visible stations every 20 s and when the map moved.
 */
object PlatformLines {
    private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    private const val MAX = 40   // stations per request (server limit)

    suspend fun run(holder: MapHolder) {
        var shown: List<String> = emptyList()
        var fetchedAt = 0L
        var empty = true
        while (true) {
            val m = holder.map
            if (m == null || m.cameraPosition.zoom < holder.PLAT_ZOOM) {
                if (!empty) { holder.setPlatformLines(EMPTY); empty = true; shown = emptyList() }
                delay(1_000); continue
            }
            val vis = m.projection.visibleRegion.latLngBounds
            val dLat = vis.latitudeSpan * 0.2; val dLon = vis.longitudeSpan * 0.2
            val cLat = vis.center.latitude; val cLon = vis.center.longitude
            val list = TaktApp.instance.stations.list.value.asSequence()
                .filter { it.plats.isNotEmpty() && it.lat in (vis.latitudeSouth - dLat)..(vis.latitudeNorth + dLat) && it.lon in (vis.longitudeWest - dLon)..(vis.longitudeEast + dLon) }
                .sortedBy { (it.lat - cLat) * (it.lat - cLat) + (it.lon - cLon) * (it.lon - cLon) }
                .take(MAX).toList()
            val ids = list.map { it.id }
            if (ids.isNotEmpty() && (ids.toSet() != shown.toSet() || System.currentTimeMillis() - fetchedAt > 20_000)) {
                try {
                    val r = TaktApp.instance.repo.next(ids)
                    holder.setPlatformLines(geojson(list, r.stations))
                    empty = false
                    shown = ids; fetchedAt = System.currentTimeMillis()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w("Takt", "next: ${e.message}")
                    fetchedAt = System.currentTimeMillis() - 15_000   // try again in ~5 s
                }
            }
            delay(1_000)
        }
    }

    private fun geojson(list: List<Station>, next: Map<String, Map<String, app.takt.data.NextDep>>): String {
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (s in list) {
            val byStop = next[s.id] ?: continue
            for (p in s.plats) {
                // a merged dot can have several stop ids: the earliest departure of them
                val d = p.ids.mapNotNull { byStop[it] }.minByOrNull { it.rt ?: it.t } ?: continue
                if (!first) sb.append(','); first = false
                sb.append("{\"type\":\"Feature\",\"properties\":{\"line\":").append(JsonPrimitive(d.line))
                    .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(p.lon).append(',').append(p.lat).append("]}}")
            }
        }
        return sb.append("]}").toString()
    }
}

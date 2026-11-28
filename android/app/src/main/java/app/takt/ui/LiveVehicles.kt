package app.takt.ui

import app.takt.data.ModeFilter
import app.takt.TaktApp
import app.takt.data.Geometry
import app.takt.data.VehicleSeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Live vehicles: the server sends stop-to-stop pieces of the running trips with (realtime) departure,
 * arrival and path. Each vehicle is moved along its piece by the clock, like geOps does; there are no
 * GPS positions. Between two pieces (a stop) it waits at the stop.
 */
object LiveVehicles {
    const val MIN_ZOOM = 12.5

    private class Seg(val s: VehicleSeg, val pts: List<Pair<Double, Double>>, val cum: DoubleArray)
    private class Track(val trip: String, var segs: List<Seg>)

    private val tracks = HashMap<String, Track>()
    private var fetched: LatLngBounds? = null
    private var fetchedAt = 0L

    private fun seg(v: VehicleSeg): Seg? {
        val pts = Geometry(v.poly, v.precision).decode().takeIf { it.size >= 2 } ?: return null
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            val (a1, o1) = pts[i - 1]; val (a2, o2) = pts[i]
            val dx = (o2 - o1) * cos(Math.toRadians(a1)); val dy = a2 - a1
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        return Seg(v, pts, cum)
    }

    private fun merge(list: List<VehicleSeg>, nowS: Long) {
        for ((trip, vs) in list.groupBy { it.trip }) {
            val segs = vs.mapNotNull(::seg).sortedBy { it.s.dep }
            if (segs.isNotEmpty()) tracks[trip] = Track(trip, segs)
        }
        tracks.values.removeAll { it.segs.last().s.arr < nowS - 60 }
    }

    fun clear() { tracks.clear(); fetched = null; fetchedAt = 0 }

    /** Point of a piece at time t, by distance along its path. */
    private fun at(g: Seg, t: Long): Pair<Double, Double> {
        val span = (g.s.arr - g.s.dep).coerceAtLeast(1)
        val f = ((t - g.s.dep).toDouble() / span).coerceIn(0.0, 1.0)
        val target = f * g.cum.last()
        var i = g.cum.indexOfFirst { it >= target }.coerceAtLeast(1)
        val seg = g.cum[i] - g.cum[i - 1]
        val k = if (seg <= 0) 0.0 else (target - g.cum[i - 1]) / seg
        val (a1, o1) = g.pts[i - 1]; val (a2, o2) = g.pts[i]
        return (a1 + (a2 - a1) * k) to (o1 + (o2 - o1) * k)
    }

    /** GeoJSON of all vehicles at [nowMs]. */
    fun geojson(nowMs: Long): String {
        val t = nowMs / 1000
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        val hidden = TaktApp.instance.prefs.hiddenModes.value
        for (tr in tracks.values) {
            if (!tr.segs.first().s.let { ModeFilter.shown(it.cat, it.mode, hidden) }) continue
            val segs = tr.segs
            // running piece, or waiting at the stop between two pieces; not yet started / long arrived: hidden
            val running = segs.firstOrNull { t >= it.s.dep && t <= it.s.arr }
            val pos = when {
                running != null -> at(running, t)
                else -> {
                    val before = segs.lastOrNull { it.s.arr < t }
                    val next = segs.firstOrNull { it.s.dep > t }
                    when {
                        before != null && next != null -> before.pts.last()
                        before != null && t - before.s.arr <= 30 -> before.pts.last()
                        next != null && before == null && next.s.dep - t <= 60 -> next.pts.first()
                        else -> null
                    }
                }
            } ?: continue
            val v = (running ?: segs.first()).s
            if (!first) sb.append(','); first = false
            val color = v.color?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) && it.uppercase() != "#FFFFFF" } ?: modeHexLive(v.mode)
            sb.append("{\"type\":\"Feature\",\"properties\":{\"trip\":").append(kotlinx.serialization.json.JsonPrimitive(tr.trip))
                .append(",\"line\":").append(kotlinx.serialization.json.JsonPrimitive(v.line))
                .append(",\"c\":\"").append(color).append("\",\"rt\":").append(if (v.rt) 1 else 0)
                .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(pos.second).append(',').append(pos.first).append("]}}")
        }
        return sb.append("]}").toString()
    }

    /**
     * Runs while live vehicles are on: fetches the visible area every 15 s (and when the map moved out of
     * the fetched area), moves the dots every second.
     */
    suspend fun run(holder: MapHolder) = coroutineScope {
        launch {
            while (isActive) {
                val m = holder.map
                if (m != null && m.cameraPosition.zoom >= MIN_ZOOM) {
                    val vis = m.projection.visibleRegion.latLngBounds
                    val stale = System.currentTimeMillis() - fetchedAt > 15_000
                    val moved = fetched?.let { f -> !(f.contains(vis.northEast) && f.contains(vis.southWest)) } ?: true
                    if (stale || moved) {
                        // a margin around the screen, so short pans need no new request
                        val dLat = (vis.latitudeSpan * 0.25).coerceAtMost(0.05); val dLon = (vis.longitudeSpan * 0.25).coerceAtMost(0.08)
                        val s = (vis.latitudeSouth - dLat); val w = (vis.longitudeWest - dLon)
                        val n = (vis.latitudeNorth + dLat).coerceAtMost(s + 0.34); val e = (vis.longitudeEast + dLon).coerceAtMost(w + 0.54)
                        try {
                            val r = TaktApp.instance.repo.vehicles("%.5f,%.5f".format(java.util.Locale.ROOT, s, w), "%.5f,%.5f".format(java.util.Locale.ROOT, n, e), m.cameraPosition.zoom.toInt())
                            merge(r.vehicles, System.currentTimeMillis() / 1000)
                            fetched = LatLngBounds.from(n, e, s, w); fetchedAt = System.currentTimeMillis()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            android.util.Log.w("Takt", "vehicles: ${e.message}")
                            fetchedAt = System.currentTimeMillis() - 10_000   // try again in ~5 s
                        }
                    }
                }
                delay(2_000)
            }
        }
        while (isActive) {
            val m = holder.map
            holder.setVehicles(if (m != null && m.cameraPosition.zoom >= MIN_ZOOM) geojson(System.currentTimeMillis()) else EMPTY)
            delay(1_000)
        }
    }

    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    private fun modeHexLive(m: String) = when (m) {
        "tram" -> "#E5243B"; "bus" -> "#8E4FD1"; "sbahn" -> "#17A35B"; "train" -> "#64748B"; else -> "#475569"
    }
}

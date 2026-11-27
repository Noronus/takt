package app.takt.data

import kotlinx.serialization.Serializable

@Serializable data class RtInfo(val feedAge: Long? = null, val fetchedAt: Long? = null)
@Serializable data class StationRef(val id: String, val name: String)

@Serializable
data class Dep(
    val trip: String = "",
    val line: String = "",
    val mode: String = "other",
    val color: String? = null,
    val textColor: String? = null,
    val dir: String = "",
    val t: Long = 0,
    val rt: Long? = null,
    val cancelled: Boolean = false,
    val track: String? = null,
    val plannedTrack: String? = null,
    val stop: String = "",
    val src: String? = null,
    val notes: List<String> = emptyList(),
    val info: List<String> = emptyList(),
    /** Platform (MOTIS stop id) it leaves from. */
    val stopId: String? = null,
)

/** Next departure per platform: stations[stationId][platformStopId]. */
@Serializable data class NextDep(val line: String = "", val mode: String = "other", val color: String? = null, val t: Long = 0, val rt: Long? = null)
@Serializable data class NextResponse(val now: Long = 0, val stations: Map<String, Map<String, NextDep>> = emptyMap())

@Serializable
data class DeparturesResponse(
    val station: StationRef,
    val now: Long,
    val source: String = "motis",
    val rt: RtInfo = RtInfo(),
    val departures: List<Dep> = emptyList(),
)

@Serializable
data class LegPlace(
    val name: String = "",
    val station: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val t: Long? = null,
    val rt: Long? = null,
    val track: String? = null,
    val plannedTrack: String? = null,
    val cancelled: Boolean = false,
)

@Serializable
data class Leg(
    val mode: String = "other",
    val line: String = "",
    val trip: String? = null,
    val color: String? = null,
    val textColor: String? = null,
    val dir: String = "",
    val from: LegPlace = LegPlace(),
    val to: LegPlace = LegPlace(),
    val realtime: Boolean = false,
    val src: String? = null,
    val cancelled: Boolean = false,
    val duration: Long = 0,
    val distance: Long? = null,
    val stops: Int = 0,
    /** Stops in between (transit legs). */
    val via: List<ViaStop> = emptyList(),
    val geometry: Geometry? = null,
    val notes: List<String> = emptyList(),
    val info: List<String> = emptyList(),
)

@Serializable data class ViaStop(val name: String = "", val station: String? = null, val lat: Double = 0.0, val lon: Double = 0.0)

@Serializable data class Geometry(val points: String = "", val precision: Int = 6) {
    /** Decodes the encoded polyline into (lat, lon) pairs. */
    fun decode(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val f = Math.pow(10.0, precision.toDouble())
        var i = 0; var lat = 0L; var lon = 0L
        while (i < points.length) {
            for (k in 0..1) {
                var shift = 0; var result = 0L; var b: Int
                do { b = points[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20 && i < points.length)
                val d = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
                if (k == 0) lat += d else lon += d
            }
            out += (lat / f) to (lon / f)
        }
        return out
    }
}

@Serializable
data class Itinerary(
    val id: String = "", val transfers: Int = 0, val duration: Long = 0, val legs: List<Leg> = emptyList(),
    /** Name of the preferred transfer stop this journey uses (server, "bevorzugte Umstiege"). */
    val via: String? = null,
)

@Serializable
data class PlanResponse(
    val now: Long,
    val rt: RtInfo = RtInfo(),
    val prev: String? = null,
    val next: String? = null,
    val itineraries: List<Itinerary> = emptyList(),
)

@Serializable data class TimePair(val t: Long? = null, val rt: Long? = null)

@Serializable
data class TripStop(
    val name: String = "",
    val station: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val arr: TimePair? = null,
    val dep: TimePair? = null,
    val track: String? = null,
    val cancelled: Boolean = false,
)

@Serializable
data class TripResponse(
    val line: String = "",
    val mode: String = "other",
    val color: String? = null,
    val dir: String = "",
    val realtime: Boolean = false,
    val cancelled: Boolean = false,
    val rt: RtInfo = RtInfo(),
    val stops: List<TripStop> = emptyList(),
)

@Serializable
data class Alert(
    val id: String = "",
    val title: String = "",
    val text: String = "",
    val company: String = "",
    val lines: List<String> = emptyList(),
    val validFrom: String? = null,
    val validUntil: String? = null,
    val products: List<String> = emptyList(),
    val stops: List<String> = emptyList(),
)

/** One stop-to-stop piece of a running trip (server: /api/v1/vehicles, from MOTIS railviz). */
@Serializable
data class VehicleSeg(
    val trip: String, val line: String = "", val mode: String = "other", val color: String? = null,
    val dep: Long, val arr: Long, val rt: Boolean = false, val to: String? = null,
    val poly: String, val precision: Int = 5,
)
@Serializable data class VehiclesResponse(val now: Long = 0, val rt: RtInfo = RtInfo(), val vehicles: List<VehicleSeg> = emptyList())

/** [age]: seconds since the server fetched these messages from INSA (it keeps them a few minutes). */
@Serializable data class RtResponse(val rt: RtInfo = RtInfo())

/** One data source of the server (settings screen). [updated]: unix s of the data itself, [fetched]: last successful fetch. */
@Serializable
data class DataSource(
    val name: String = "", val role: String = "", val active: Boolean = false,
    val updated: Long? = null, val fetched: Long? = null, val info: String? = null, val error: String? = null,
)
@Serializable data class SourcesResponse(val now: Long = 0, val sources: List<DataSource> = emptyList())

@Serializable data class AlertsResponse(val alerts: List<Alert> = emptyList(), val age: Long? = null)

@Serializable
data class GeoMatch(
    val type: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val area: String = "",
    val station: String? = null,
)

/** A loaded result plus when it arrived; [ageSeconds] is the age of the realtime data. */
data class Timed<T>(val value: T, val receivedAtMs: Long, val feedAgeAtReceive: Long?, val fromCache: Boolean = false) {
    fun ageSeconds(nowMs: Long = System.currentTimeMillis()): Long =
        (feedAgeAtReceive ?: 0) + ((nowMs - receivedAtMs) / 1000).coerceAtLeast(0)
}

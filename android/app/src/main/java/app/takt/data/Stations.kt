package app.takt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.sqrt

fun stationColor(m: Int): String = when {
    m and Modes.TRAM != 0 -> "#E5243B"
    m and Modes.SUBURBAN != 0 -> "#17A35B"
    m and Modes.RAIL != 0 -> "#64748B"
    m and Modes.BUS != 0 -> "#8E4FD1"
    else -> "#475569"
}

/**
 * Which kinds of transport are shown. Categories come from the server ([Dep.cat], [Station.cats]): buses are split
 * into city, regional and long-distance buses; rail replacement buses count as the rail line. An older server sends
 * none, then the category follows the mode (a bus is a city bus, a bus stop serves every kind of bus).
 */
object ModeFilter {
    val OPTIONS = listOf(
        "tram" to "Straßenbahn", "sbahn" to "S-Bahn", "train" to "Regionalzug",
        "citybus" to "Stadtbus", "regiobus" to "Regionalbus", "coach" to "Fernbus", "ferry" to "Fähre",
    )
    private val BITS = mapOf("tram" to 1, "sbahn" to 2, "train" to 4, "citybus" to 8, "regiobus" to 16, "coach" to 32, "ferry" to 64, "other" to 128)
    private const val ALL_BUS = 8 or 16 or 32

    fun bits(hidden: Set<String>) = hidden.fold(0) { a, m -> a or (BITS[m] ?: 0) }
    fun cat(cat: String?, mode: String) = cat ?: when (mode) { "bus" -> "citybus"; "tram", "sbahn", "train", "ferry" -> mode; else -> "other" }
    /** a departure, vehicle or line */
    fun shown(cat: String?, mode: String, hidden: Set<String>) = hidden.isEmpty() || cat(cat, mode) !in hidden
    private fun fromModes(m: Int): Int {
        var c = 0
        if (m and Modes.TRAM != 0) c = c or 1
        if (m and Modes.SUBURBAN != 0) c = c or 2
        if (m and Modes.RAIL != 0) c = c or 4
        if (m and Modes.BUS != 0) c = c or ALL_BUS
        if (m and Modes.FERRY != 0) c = c or 64
        if (m and (Modes.SUBWAY or Modes.OTHER) != 0) c = c or 128
        return c
    }
    fun shownStation(cats: Int, modes: Int, hiddenBits: Int): Boolean {
        if (hiddenBits == 0) return true
        val c = if (cats != 0) cats else fromModes(modes)
        return c == 0 || c and hiddenBits.inv() != 0
    }
    /** stations without hidden-only ones, and their platforms likewise */
    fun stations(list: List<Station>, hidden: Set<String>): List<Station> {
        val hb = bits(hidden)
        if (hb == 0) return list
        return list.filter { shownStation(it.cats, it.modes, hb) }.map { s ->
            if (s.plats.isEmpty()) s else s.copy(plats = s.plats.filter { shownStation(it.cats, it.modes, hb) }.takeIf { it.size > 1 } ?: emptyList())
        }
    }
}

object Modes {
    const val TRAM = 1; const val SUBWAY = 2; const val RAIL = 4; const val BUS = 8
    const val FERRY = 16; const val OTHER = 32; const val SUBURBAN = 64
}

data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val modes: Int,
    val rank: Int,
    /** Single stopping points (platforms) of the station; empty when there is only one. */
    val plats: List<Platform> = emptyList(),
    /** category bits (see [ModeFilter]); 0 from an older server */
    val cats: Int = 0,
) {
    /** Normalized name for search; computed on first search, not at startup. */
    val key: String by lazy(LazyThreadSafetyMode.NONE) { Stations.norm(name) }
    /** Name without the city prefix "Leipzig, ". */
    val shortName: String = name.removePrefix("Leipzig, ").removeSuffix(", Leipzig")
}

/** One stopping point of a station; [code] is the platform label ("2", "1/3") or empty, [ids] its MOTIS stop ids. */
data class Platform(val lat: Double, val lon: Double, val code: String, val modes: Int, val ids: List<String>, val cats: Int = 0) {
    /** Key for the server's platform filter and for map features. */
    val key: String get() = ids.joinToString(",")
}

data class Nearby(val station: Station, val meters: Int)

/** Station list kept on the device: works without network (nearby, search, map). */
class Stations(private val ctx: Context, private val api: Api) {
    private val file = File(ctx.filesDir, "stations.json")
    private val _list = MutableStateFlow<List<Station>>(emptyList())
    val list: StateFlow<List<Station>> = _list
    private var byId: Map<String, Station> = emptyMap()
    private val _geojson = MutableStateFlow<String?>(null)
    /** All stations as GeoJSON for the map, prepared off the main thread. */
    val geojson: StateFlow<String?> = _geojson
    private var etag: String? = null
    var version: String = ""; private set

    fun get(id: String?): Station? = id?.let { byId[it] }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (_list.value.isNotEmpty()) return@withContext
        val text = if (file.exists()) file.readText()
        else runCatching { ctx.assets.open("stations.json").bufferedReader().readText() }.getOrNull()
        if (text != null) runCatching { parse(text) }
        etag = ctx.getSharedPreferences("takt", Context.MODE_PRIVATE).getString("stationsEtag", null)
            ?.takeIf { file.exists() }
    }

    private var lastCheck = 0L

    /** [refresh], but at most every [minGapMs]: for the checks when the app comes to the front and while it is open. */
    suspend fun refreshIfDue(minGapMs: Long): Boolean =
        if (System.currentTimeMillis() - lastCheck < minGapMs) false else refresh()

    /** Fetches a newer list if the server has one (ETag, so an unchanged list costs one small request). Silent on errors. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lastCheck = System.currentTimeMillis()
        try {
            val (body, newTag) = api.get("/api/v1/stations", etag = etag, attempts = 2)
            if (body == null) return@withContext false
            parse(body)
            file.writeText(body)
            etag = newTag
            ctx.getSharedPreferences("takt", Context.MODE_PRIVATE).edit().putString("stationsEtag", newTag).apply()
            true
        } catch (_: Exception) { false }
    }

    private fun parse(text: String) {
        val root = Json.parseToJsonElement(text) as JsonObject
        version = root["version"]?.jsonPrimitive?.content ?: ""
        val arr = root["stations"]!!.jsonArray
        val out = ArrayList<Station>(arr.size)
        arr.forEachIndexed { i, e ->
            val a = e as JsonArray
            out += Station(
                id = a[0].jsonPrimitive.content,
                name = a[1].jsonPrimitive.content,
                lat = a[2].jsonPrimitive.double,
                lon = a[3].jsonPrimitive.double,
                modes = a[4].jsonPrimitive.int,
                rank = i,
                plats = (a.getOrNull(7) as? JsonArray)?.map { p ->
                    val q = p as JsonArray
                    Platform(q[0].jsonPrimitive.double, q[1].jsonPrimitive.double, q[2].jsonPrimitive.content, q[3].jsonPrimitive.int,
                        (q.getOrNull(4) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        (q.getOrNull(5) as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0)
                } ?: emptyList(),
                cats = (a.getOrNull(8) as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        byId = out.associateBy { it.id }
        _list.value = out
        // keep a ready-made file for the map, so the next start shows stations with the first frame
        if (geoFileVersion() != version) {
            val g = buildGeoJson(out)
            runCatching { geoFile.writeText(g); geoVersionFile.writeText(version) }
            _geojson.value = g
        } else if (_geojson.value == null) {
            _geojson.value = runCatching { geoFile.readText() }.getOrNull() ?: buildGeoJson(out)
        }
    }

    // "3": format with platform points and their stop ids; a new name makes older cached files unused
    val geoFile = File(ctx.filesDir, "stations3.geojson")
    private val geoVersionFile = File(ctx.filesDir, "stations3.geojson.version")
    /** Version of the GeoJSON file the map can load directly, or null if there is none yet. */
    fun geoFileVersion(): String? = geoVersionFile.takeIf { it.exists() && geoFile.exists() }?.readText()

    fun buildGeoJson(list: List<Station>): String {
        val sb = StringBuilder(list.size * 160)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        val big = { m: Int -> if (m and (Modes.TRAM or Modes.SUBURBAN or Modes.RAIL) != 0) 1 else 0 }
        list.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            val id = Json.encodeToString(String.serializer(), s.id)
            // "pl": the station has platform points; the map then shows those instead of the station dot when zoomed in
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(s.lon).append(',').append(s.lat).append("]},\"properties\":{\"id\":")
                .append(id).append(",\"name\":")
                .append(Json.encodeToString(String.serializer(), s.shortName)).append(",\"c\":\"")
                .append(stationColor(s.modes)).append("\",\"big\":").append(big(s.modes)).append(",\"r\":").append(s.rank)
                .append(",\"pl\":").append(if (s.plats.isNotEmpty()) 1 else 0).append("}}")
            for (p in s.plats) {
                sb.append(",{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    .append(p.lon).append(',').append(p.lat).append("]},\"properties\":{\"id\":")
                    .append(id).append(",\"plat\":1,\"pk\":")
                    .append(Json.encodeToString(String.serializer(), p.key)).append(",\"code\":")
                    .append(Json.encodeToString(String.serializer(), p.code)).append(",\"c\":\"")
                    .append(stationColor(p.modes)).append("\",\"big\":").append(big(p.modes)).append("}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    /** [hiddenBits]: stations serving only these modes are left out (see [ModeFilter]). */
    fun nearby(lat: Double, lon: Double, n: Int = 20, modes: Int = 0, hiddenBits: Int = 0): List<Nearby> {
        val k = cos(Math.toRadians(lat))
        return _list.value.asSequence()
            .filter { (modes == 0 || it.modes and modes != 0) && ModeFilter.shownStation(it.cats, it.modes, hiddenBits) }
            .map { s ->
                val dy = (s.lat - lat) * 111_320.0
                val dx = (s.lon - lon) * 111_320.0 * k
                Nearby(s, sqrt(dx * dx + dy * dy).toInt())
            }
            .sortedBy { it.meters }
            .take(n)
            .toList()
    }

    fun search(q: String, n: Int = 30): List<Station> {
        val tokens = norm(q).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return _list.value.asSequence()
            .mapNotNull { s ->
                val words = s.key.split(' ')
                var score = 0
                for (t in tokens) {
                    score += when {
                        words.any { it == t } -> 3
                        words.any { it.startsWith(t) } -> 2
                        s.key.contains(t) -> 1
                        else -> return@mapNotNull null
                    }
                }
                s to score
            }
            .sortedWith(compareByDescending<Pair<Station, Int>> { it.second }.thenBy { it.first.rank })
            .take(n)
            .map { it.first }
            .toList()
    }

    companion object {
        private val marks = Regex("\\p{M}")
        private val nonAlnum = Regex("[^a-z0-9]+")
        fun norm(s: String): String {
            val x = s.lowercase()
                .replace("ß", "ss")
                .replace("str.", "strasse ")
                .replace("str ", "strasse ")
            return Normalizer.normalize(x, Normalizer.Form.NFD)
                .replace(marks, "")
                .replace(nonAlnum, " ")
                .trim()
        }
    }
}


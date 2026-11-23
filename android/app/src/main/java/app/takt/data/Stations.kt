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
) {
    /** Normalized name for search; computed on first search, not at startup. */
    val key: String by lazy(LazyThreadSafetyMode.NONE) { Stations.norm(name) }
    /** Name without the city prefix "Leipzig, ". */
    val shortName: String = name.removePrefix("Leipzig, ").removeSuffix(", Leipzig")
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

    /** Fetches a newer list if the server has one (ETag). Silent on errors. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
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

    val geoFile = File(ctx.filesDir, "stations.geojson")
    private val geoVersionFile = File(ctx.filesDir, "stations.geojson.version")
    /** Version of the GeoJSON file the map can load directly, or null if there is none yet. */
    fun geoFileVersion(): String? = geoVersionFile.takeIf { it.exists() && geoFile.exists() }?.readText()

    private fun buildGeoJson(list: List<Station>): String {
        val sb = StringBuilder(list.size * 130)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        list.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            val big = if (s.modes and (Modes.TRAM or Modes.SUBURBAN or Modes.RAIL) != 0) 1 else 0
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(s.lon).append(',').append(s.lat).append("]},\"properties\":{\"id\":")
                .append(Json.encodeToString(String.serializer(), s.id)).append(",\"name\":")
                .append(Json.encodeToString(String.serializer(), s.shortName)).append(",\"c\":\"")
                .append(stationColor(s.modes)).append("\",\"big\":").append(big).append(",\"r\":").append(s.rank).append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun nearby(lat: Double, lon: Double, n: Int = 20, modes: Int = 0): List<Nearby> {
        val k = cos(Math.toRadians(lat))
        return _list.value.asSequence()
            .filter { modes == 0 || it.modes and modes != 0 }
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


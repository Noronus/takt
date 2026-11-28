package app.takt.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.takt.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SavedPlace(val name: String, val station: String? = null, val lat: Double? = null, val lon: Double? = null) {
    /** Parameter for the backend: station id or "lat,lon". */
    fun param(): String = station ?: "$lat,$lon"
}

@Serializable data class FavRoute(val from: SavedPlace, val to: SavedPlace)

/**
 * A station picked in a list or search (not on the map), or an address ([place], [station] empty), with the time
 * it was last picked (epoch ms).
 */
@Serializable data class HistoryEntry(val station: String = "", val at: Long = 0, val place: SavedPlace? = null) {
    /** Same entry = same station, or the same address. */
    val key: String get() = station.ifBlank { place?.param() ?: "" }
}

/** Everything that exists only on this device, for "Einstellungen exportieren". The token only on request. */
@Serializable
data class ConfigExport(
    val app: String = "takt",
    val version: Int = 1,
    val server: String? = null,
    val token: String? = null,
    val animScale: Float? = null,
    val mapDark: Boolean? = null,
    val mapSat: Boolean? = null,
    val mapLive: Boolean? = null,
    val pastMin: Int? = null,
    val favStations: List<String> = emptyList(),
    val favRoutes: List<FavRoute> = emptyList(),
    val preferred: List<SavedPlace> = emptyList(),
    val preferSlack: Int? = null,
    val alertLines: List<String> = emptyList(),
    val hiddenModes: List<String>? = null,
    val accent: String? = null,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("takt", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trim()).apply()

    init {
        // keep a build-time default server once seen, so a later build without one still works
        if (!sp.contains("server") && BuildConfig.DEFAULT_SERVER.isNotBlank()) sp.edit().putString("server", BuildConfig.DEFAULT_SERVER).apply()
    }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Animation duration factor: 0 = off, 0.5 fast, 1 normal, 1.6 slow. Written at once: an app update may kill us right after. */
    var animScale: Float
        get() = sp.getFloat("animScale", 1f)
        set(v) { sp.edit().putFloat("animScale", v).commit() }

    /** Departures that left up to this many minutes ago stay in the lists. */
    var pastMin by mutableIntStateOf(sp.getInt("pastMin", 0))
        private set
    fun changePastMin(v: Int) { pastMin = v; sp.edit().putInt("pastMin", v).commit() }

    /** Preferred transfer stops: used when they cost at most [preferSlack] minutes more. */
    private val placeSer = ListSerializer(SavedPlace.serializer())
    private val _preferred = MutableStateFlow(runCatching { json.decodeFromString(placeSer, sp.getString("preferred", "[]")!!) }.getOrDefault(emptyList()))
    val preferred: StateFlow<List<SavedPlace>> = _preferred
    fun setPreferred(l: List<SavedPlace>) { _preferred.value = l; sp.edit().putString("preferred", json.encodeToString(placeSer, l)).commit() }
    var preferSlack by mutableIntStateOf(sp.getInt("preferSlack", 5))
        private set
    fun changePreferSlack(v: Int) { preferSlack = v; sp.edit().putInt("preferSlack", v).commit() }

    /** Lines picked in the disruption filter (as INSA names them, e.g. "Str 10", "Bus 76"). */
    private val _alertLines = MutableStateFlow(sp.getString("alertLines", "")!!.split('\n').filter { it.isNotBlank() }.toSet())
    val alertLines: StateFlow<Set<String>> = _alertLines
    fun setAlertLines(s: Set<String>) { _alertLines.value = s; sp.edit().putString("alertLines", s.joinToString("\n")).apply() }

    /** Journey fields and the route on the map, so an app update or a killed process keeps them. */
    fun loadPlace(key: String): SavedPlace? = sp.getString(key, null)?.let { runCatching { json.decodeFromString(SavedPlace.serializer(), it) }.getOrNull() }
    fun savePlace(key: String, p: SavedPlace?) { sp.edit().apply { if (p == null) remove(key) else putString(key, json.encodeToString(SavedPlace.serializer(), p)) }.apply() }
    fun loadRoute(): Itinerary? = sp.getString("mapRoute", null)?.let { runCatching { json.decodeFromString(Itinerary.serializer(), it) }.getOrNull() }
    fun saveRoute(it: Itinerary?) { sp.edit().apply { if (it == null) remove("mapRoute") else putString("mapRoute", json.encodeToString(Itinerary.serializer(), it)) }.apply() }

    private val _mapDark = MutableStateFlow(sp.getBoolean("mapDark", true))
    val mapDark: StateFlow<Boolean> = _mapDark
    fun toggleMapDark() { _mapDark.value = !_mapDark.value; sp.edit().putBoolean("mapDark", _mapDark.value).apply() }

    /** Aerial images on the map (kept across restarts). */
    private val _mapSat = MutableStateFlow(sp.getBoolean("mapSat", false))
    val mapSat: StateFlow<Boolean> = _mapSat
    fun toggleMapSat() { _mapSat.value = !_mapSat.value; sp.edit().putBoolean("mapSat", _mapSat.value).commit() }

    /** Live vehicles on the map (kept across restarts). */
    private val _mapLive = MutableStateFlow(sp.getBoolean("mapLive", false))
    val mapLive: StateFlow<Boolean> = _mapLive
    fun toggleMapLive() { _mapLive.value = !_mapLive.value; sp.edit().putBoolean("mapLive", _mapLive.value).commit() }

    /** Modes the user does not want to see (map, live vehicles, departures, nearby): keys of [ModeFilter.OPTIONS]. */
    private val _hiddenModes = MutableStateFlow(sp.getString("hiddenModes", "")!!.split(',').filter { it.isNotBlank() }
        .flatMap { if (it == "bus") listOf("citybus", "regiobus", "coach") else listOf(it) }.toSet())
    val hiddenModes: StateFlow<Set<String>> = _hiddenModes
    fun setModeShown(mode: String, shown: Boolean) {
        val s = if (shown) _hiddenModes.value - mode else _hiddenModes.value + mode
        _hiddenModes.value = s; sp.edit().putString("hiddenModes", s.joinToString(",")).commit()
    }

    /** Accent colour: a key of ACCENTS in Theme.kt, or "system" (wallpaper colours). */
    private val _accent = MutableStateFlow(sp.getString("accent", "green")!!)
    val accent: StateFlow<String> = _accent
    fun setAccent(k: String) { _accent.value = k; sp.edit().putString("accent", k).commit() }

    /** Recently picked stations, newest first, each once, at most [HISTORY_MAX]. */
    private val histSer = ListSerializer(HistoryEntry.serializer())
    private val _history = MutableStateFlow(runCatching { json.decodeFromString(histSer, sp.getString("history", "[]")!!) }.getOrDefault(emptyList()))
    val history: StateFlow<List<HistoryEntry>> = _history

    fun recordStation(id: String) = record(HistoryEntry(id, System.currentTimeMillis()))

    /** A picked place: a station by its id, an address with its name and position. */
    fun recordPlace(p: SavedPlace) =
        if (p.station != null) recordStation(p.station) else record(HistoryEntry("", System.currentTimeMillis(), p))

    private fun record(e: HistoryEntry) {
        val l = listOf(e) + _history.value.filter { it.key != e.key }
        _history.value = l.take(HISTORY_MAX)
        sp.edit().putString("history", json.encodeToString(histSer, _history.value)).apply()
    }

    /** Platform dots on the map show the platform number (default) instead of the next line. */
    var platCode by androidx.compose.runtime.mutableStateOf(sp.getBoolean("platCode", true))
        private set
    fun changePlatCode(v: Boolean) { platCode = v; sp.edit().putBoolean("platCode", v).apply() }

    /** Favourites section on the nearby tab folded away (the stars in the lists stay). */
    private val _favCollapsed = MutableStateFlow(sp.getBoolean("favCollapsed", false))
    val favCollapsed: StateFlow<Boolean> = _favCollapsed
    fun toggleFavCollapsed() { _favCollapsed.value = !_favCollapsed.value; sp.edit().putBoolean("favCollapsed", _favCollapsed.value).apply() }

    private val _favStations = MutableStateFlow(sp.getString("favStations", "")!!.split('\n').filter { it.isNotBlank() })
    val favStations: StateFlow<List<String>> = _favStations

    fun toggleFavStation(id: String) {
        val l = _favStations.value.toMutableList()
        if (!l.remove(id)) l.add(id)
        _favStations.value = l
        sp.edit().putString("favStations", l.joinToString("\n")).apply()
    }

    private val routeSer = ListSerializer(FavRoute.serializer())
    private val _favRoutes = MutableStateFlow(
        runCatching { json.decodeFromString(routeSer, sp.getString("favRoutes", "[]")!!) }.getOrDefault(emptyList())
    )
    val favRoutes: StateFlow<List<FavRoute>> = _favRoutes

    fun toggleFavRoute(r: FavRoute) {
        val l = _favRoutes.value.toMutableList()
        val existing = l.indexOfFirst { it.from.param() == r.from.param() && it.to.param() == r.to.param() }
        if (existing >= 0) l.removeAt(existing) else l.add(0, r)
        _favRoutes.value = l
        sp.edit().putString("favRoutes", json.encodeToString(routeSer, l)).apply()
    }

    fun isFavRoute(from: SavedPlace, to: SavedPlace) =
        _favRoutes.value.any { it.from.param() == from.param() && it.to.param() == to.param() }

    companion object { const val HISTORY_MAX = 20 }

    private val pretty = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }

    fun exportJson(withToken: Boolean): String = pretty.encodeToString(ConfigExport.serializer(), ConfigExport(
        server = server, token = if (withToken) token.ifBlank { null } else null,
        animScale = animScale, mapDark = _mapDark.value, mapSat = _mapSat.value, mapLive = _mapLive.value, pastMin = pastMin,
        favStations = _favStations.value, favRoutes = _favRoutes.value,
        preferred = _preferred.value, preferSlack = preferSlack, alertLines = _alertLines.value.toList(),
        hiddenModes = _hiddenModes.value.toList(), accent = _accent.value,
    ))

    /** Applies an export; favourites are merged, settings replaced. Returns a short summary. */
    fun importJson(text: String): String {
        val c = pretty.decodeFromString(ConfigExport.serializer(), text)
        require(c.app == "takt") { "Keine Takt-Datei" }
        c.server?.takeIf { it.isNotBlank() }?.let { server = it }
        c.token?.takeIf { it.isNotBlank() }?.let { token = it }
        c.animScale?.let { animScale = it }
        c.mapDark?.let { if (it != _mapDark.value) toggleMapDark() }
        c.mapSat?.let { if (it != _mapSat.value) toggleMapSat() }
        c.mapLive?.let { if (it != _mapLive.value) toggleMapLive() }
        c.pastMin?.let { changePastMin(it) }
        c.preferSlack?.let { changePreferSlack(it) }
        c.accent?.let { setAccent(it) }
        c.hiddenModes?.let { h -> _hiddenModes.value = h.toSet(); sp.edit().putString("hiddenModes", h.joinToString(",")).commit() }
        val st = (_favStations.value + c.favStations).distinct()
        _favStations.value = st; sp.edit().putString("favStations", st.joinToString("\n")).commit()
        val rt = (_favRoutes.value + c.favRoutes).distinctBy { it.from.param() + ">" + it.to.param() }
        _favRoutes.value = rt; sp.edit().putString("favRoutes", json.encodeToString(routeSer, rt)).commit()
        setPreferred((_preferred.value + c.preferred).distinctBy { it.param() })
        if (c.alertLines.isNotEmpty()) setAlertLines(_alertLines.value + c.alertLines)
        return "${c.favStations.size} Haltestellen, ${c.favRoutes.size} Routen, ${c.preferred.size} Umstiege" + (if (c.token != null) ", Token" else "") + " übernommen"
    }
}

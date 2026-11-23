package app.takt.data

import android.content.Context
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

    /** Animation duration factor: 0 = off, 0.5 fast, 1 normal, 1.6 slow. */
    var animScale: Float
        get() = sp.getFloat("animScale", 1f)
        set(v) = sp.edit().putFloat("animScale", v).apply()

    private val _mapDark = MutableStateFlow(sp.getBoolean("mapDark", true))
    val mapDark: StateFlow<Boolean> = _mapDark
    fun toggleMapDark() { _mapDark.value = !_mapDark.value; sp.edit().putBoolean("mapDark", _mapDark.value).apply() }

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
}

package app.takt.ui

import android.content.Context
import android.graphics.RectF
import android.view.ViewGroup
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.takt.TaktApp
import app.takt.data.SavedPlace
import app.takt.data.Station
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * One MapView for the whole activity: it survives tab switches, so coming back to the map
 * is instant and the style/tiles are not loaded again.
 */
class MapHolder(ctx: Context) {
    val view: MapView = MapView(ctx).apply { onCreate(null) }
    var map by mutableStateOf<MapLibreMap?>(null); private set
    var style by mutableStateOf<Style?>(null); private set
    private var styleDark: Boolean? = null
    private var styleSat: Boolean? = null
    var onStationClick: (String?) -> Unit = {}
    private var stationsLoaded = false
    /** Map is shown once stations are in it (or after a timeout), so tiles and stops appear together. */
    var revealed by mutableStateOf(false)
    /** True once the camera was moved by the user or centred on the position. */
    var cameraSettled = false

    init {
        view.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            val l = Position.loc
            m.cameraPosition = CameraPosition.Builder()
                .target(if (l != null) LatLng(l.latitude, l.longitude) else LatLng(51.3397, 12.3731))
                .zoom(15.0).build()
            if (l != null) cameraSettled = true
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) cameraSettled = true }
            m.addOnMapClickListener { ll ->
                val p = m.projection.toScreenLocation(ll)
                val r = 30f
                val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), "st-dot")
                val best = hit.minByOrNull {
                    val pt = it.geometry() as Point
                    val sp = m.projection.toScreenLocation(LatLng(pt.latitude(), pt.longitude()))
                    (sp.x - p.x) * (sp.x - p.x) + (sp.y - p.y) * (sp.y - p.y)
                }
                onStationClick(best?.getStringProperty("id"))
                true
            }
            map = m
            TaktApp.mark("map ready")
            view.addOnSourceChangedListener { id -> if (id == "stations") { stationsLoaded = true; TaktApp.mark("source stations loaded") } }
            view.addOnDidFinishRenderingFrameListener { _, _, _ -> if (stationsLoaded && !revealed) revealed = true }
        }
    }

    /** Sets the base style (bundled, no network round trip) together with the station layers. */
    // points only: tiling to z14 is enough, MapLibre overzooms above that
    private val geoOpts = org.maplibre.android.style.sources.GeoJsonOptions().withMaxZoom(14).withBuffer(32).withTolerance(0f)

    /** Station data version currently in the map source. */
    var shownVersion: String? = null

    fun applyStyle(dark: Boolean, geojson: String?, sat: Boolean = false) {
        val m = map ?: return
        if (styleDark == dark && styleSat == sat && style != null) return
        styleDark = dark
        styleSat = sat
        style = null
        val st = TaktApp.instance.stations
        val fileVersion = st.geoFileVersion()
        val source = when {
            geojson != null -> { shownVersion = st.version; GeoJsonSource("stations", geojson, geoOpts) }
            fileVersion != null -> { shownVersion = fileVersion; GeoJsonSource("stations", java.net.URI("file://" + st.geoFile.absolutePath), geoOpts) }
            else -> GeoJsonSource("stations", geoOpts)
        }
        val stroke = if (dark) "#0D0F12" else "#FFFFFF"
        val b = Style.Builder().fromUri(if (dark) "asset://style-dark.json" else "asset://style-positron.json")
            .withSource(source)
            .withSource(GeoJsonSource("sel"))
            .withLayer(CircleLayer("st-dot", "stations").withProperties(
                circleColor(toColor(get("c"))),
                circleRadius(interpolate(linear(), zoom(),
                    stop(10, sum(literal(1.6), product(literal(1.2), get("big")))),
                    stop(14, sum(literal(4.5), product(literal(2), get("big")))),
                    stop(17, sum(literal(8), product(literal(2), get("big")))))),
                circleStrokeColor(stroke),
                circleStrokeWidth(interpolate(linear(), zoom(), stop(10, 0.5f), stop(15, 2f))),
            ).apply { minZoom = 9.5f })
            .withLayer(CircleLayer("sel-ring", "sel").withProperties(
                circleRadius(15f), circleColor("#3DDC97"), circleOpacity(0.25f),
                circleStrokeColor("#3DDC97"), circleStrokeWidth(3f),
            ))
            .withLayer(SymbolLayer("st-name", "stations").withProperties(
                textField(get("name")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(interpolate(linear(), zoom(), stop(14, 11f), stop(17, 13.5f))),
                textOffset(arrayOf(0f, 1.0f)),
                textAnchor("top"),
                textColor(if (dark) "#E5E7EB" else "#1F2937"),
                textHaloColor(stroke),
                textHaloWidth(1.6f),
                textOptional(true),
                symbolSortKey(get("r")),
            ).apply {
                minZoom = 13.5f
                setFilter(any(eq(get("big"), 1), gte(zoom(), 15.5)))
            })
        TaktApp.mark("applyStyle geojson=${geojson != null} file=$fileVersion")
        m.setStyle(b) { s ->
            if (sat) addAerial(s)
            style = s; TaktApp.mark("style loaded"); setRoute(MapRoute.itinerary)
        }
        view.addOnDidFinishRenderingMapListener { full -> if (full) TaktApp.mark("map fully rendered") }
    }

    /**
     * Aerial images from the open orthophotos of the three states of the MDV area, as WMS tiles.
     * Each service is transparent outside its state, so they stack; outside all three the normal map shows.
     * They go below the first label layer: street names and the stops stay readable on top.
     */
    private fun addAerial(s: Style) {
        fun wms(base: String, layer: String) =
            "$base?v=$AERIAL_VERSION&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512&FORMAT=image/png&TRANSPARENT=TRUE"
        val sources = listOf(
            Triple("aerial-th", wms("https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP", "th_dop200rgb"), 17),
            Triple("aerial-st", wms("https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest", "lsa_lvermgeo_dop20_2"), 20),
            Triple("aerial-sn", wms("https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest", "sn_dop_020"), 20),
        )
        // above every area and line of the base map (buildings, roads, water), below its labels:
        // the base style interleaves labels early, so "below the first label" would leave buildings on top
        // the base map is everything below our stop dots; later layers (stops, route, the location marker,
        // which may already be in place when the style is swapped) must stay above the images
        val all = s.layers
        val base = all.take(all.indexOfFirst { it.id == "st-dot" }.takeIf { it >= 0 } ?: all.size)
        val lastShape = base.lastOrNull { it !is SymbolLayer }?.id
        var anchor = lastShape
        for ((id, url, maxZoom) in sources) {
            val ts = org.maplibre.android.style.sources.TileSet("2.2.0", url).apply { this.maxZoom = maxZoom.toFloat(); minZoom = 8f }
            s.addSource(org.maplibre.android.style.sources.RasterSource(id, ts, 256))
            // 512 px images in 256 px tiles: sharp on high density screens. Colours are corrected on download (TaktApp).
            val layer = org.maplibre.android.style.layers.RasterLayer(id, id).withProperties(rasterFadeDuration(150f))
            if (anchor != null) s.addLayerAbove(layer, anchor) else s.addLayerBelow(layer, "st-dot")
            anchor = id
        }
        // labels of the base map that sat between its shapes are now under the images: move them on top
        // (same order, below the stops), so street and place names stay readable
        for (l in base.filterIsInstance<SymbolLayer>()) {
            if (base.indexOf(l) > base.indexOfFirst { it.id == lastShape }) continue
            runCatching { s.removeLayer(l); s.addLayerBelow(l, "st-dot") }
        }
        // light text with a dark rim reads on any photo
        for (l in base.filterIsInstance<SymbolLayer>()) runCatching {
            l.setProperties(textColor("#FFFFFF"), textHaloColor("#000000"), textHaloWidth(1.4f))
        }
        (s.getLayer("st-name") as? SymbolLayer)?.setProperties(textColor("#FFFFFF"), textHaloColor("#000000"))
    }

    /** Draws a journey: transit legs in line colour, walking legs dashed, stops at the leg ends. */
    fun setRoute(it: app.takt.data.Itinerary?) {
        val st = style ?: return
        val lines = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        val pts = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true; var firstPt = true
        it?.legs?.forEach { l ->
            val walk = l.mode == "walk"
            val coords = l.geometry?.decode()?.takeIf { c -> c.size >= 2 }
                ?: listOf(l.from.lat to l.from.lon, l.to.lat to l.to.lon)
            if (!first) lines.append(','); first = false
            val color = parseColorHex(l.color) ?: modeHex(l.mode)
            lines.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"").append(color).append("\",\"walk\":").append(if (walk) 1 else 0)
                .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            coords.forEachIndexed { k, (la, lo) -> if (k > 0) lines.append(','); lines.append('[').append(lo).append(',').append(la).append(']') }
            lines.append("]}}")
            if (!walk) for (pl in listOf(l.from, l.to)) {
                if (!firstPt) pts.append(','); firstPt = false
                pts.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"").append(color).append("\",\"n\":")
                    .append(kotlinx.serialization.json.JsonPrimitive(shortName(pl.name)).toString())
                    .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(pl.lon).append(',').append(pl.lat).append("]}}")
            }
        }
        // start and end of the whole journey (addresses included)
        it?.legs?.firstOrNull()?.from?.let { p -> if (!firstPt) pts.append(','); firstPt = false
            pts.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"#3DDC97\",\"n\":\"\",\"end\":1},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(p.lon).append(',').append(p.lat).append("]}}") }
        it?.legs?.lastOrNull()?.to?.let { p -> if (!firstPt) pts.append(','); firstPt = false
            pts.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"#FFFFFF\",\"n\":\"\",\"end\":2},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(p.lon).append(',').append(p.lat).append("]}}") }
        lines.append("]}"); pts.append("]}")

        val dark = styleDark == true
        if (st.getSource("route") == null) {
            st.addSource(GeoJsonSource("route"))
            st.addSource(GeoJsonSource("route-pts"))
            st.addLayerBelow(org.maplibre.android.style.layers.LineLayer("route-casing", "route").withProperties(
                lineColor(if (dark) "#0D0F12" else "#FFFFFF"), lineWidth(9f), lineCap("round"), lineJoin("round"),
            ).apply { setFilter(eq(get("walk"), 0)) }, "st-dot")
            st.addLayerBelow(org.maplibre.android.style.layers.LineLayer("route-line", "route").withProperties(
                lineColor(toColor(get("c"))), lineWidth(5.5f), lineCap("round"), lineJoin("round"),
            ).apply { setFilter(eq(get("walk"), 0)) }, "st-dot")
            st.addLayerBelow(org.maplibre.android.style.layers.LineLayer("route-walk", "route").withProperties(
                lineColor(if (dark) "#E5E7EB" else "#334155"), lineWidth(3.5f), lineDasharray(arrayOf(0.1f, 1.8f)), lineCap("round"),
            ).apply { setFilter(eq(get("walk"), 1)) }, "st-dot")
            st.addLayer(CircleLayer("route-stop", "route-pts").withProperties(
                circleRadius(switchCase(has("end"), literal(8f), literal(5.5f))),
                circleColor(switchCase(has("end"), toColor(get("c")), literal(if (dark) "#0D0F12" else "#FFFFFF"))),
                circleStrokeColor(switchCase(has("end"), literal(if (dark) "#0D0F12" else "#1F2937"), toColor(get("c")))),
                circleStrokeWidth(3f),
            ))
        }
        // dim the other stops while a journey is shown
        (st.getLayer("st-dot") as? CircleLayer)?.setProperties(circleOpacity(if (it != null) 0.3f else 1f), circleStrokeOpacity(if (it != null) 0.3f else 1f))
        (st.getLayer("st-name") as? SymbolLayer)?.setProperties(textOpacity(if (it != null) 0.45f else 1f))
        (st.getSource("route") as GeoJsonSource).setGeoJson(lines.toString())
        (st.getSource("route-pts") as GeoJsonSource).setGeoJson(pts.toString())
    }

    fun fitRoute(it: app.takt.data.Itinerary, bottomPx: Int, topPx: Int) {
        val m = map ?: return
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        var n = 0
        for (l in it.legs) {
            (l.geometry?.decode() ?: emptyList()).forEach { (la, lo) -> b.include(LatLng(la, lo)); n++ }
            b.include(LatLng(l.from.lat, l.from.lon)); b.include(LatLng(l.to.lat, l.to.lon)); n += 2
        }
        if (n < 2) return
        cameraSettled = true
        val u = CameraUpdateFactory.newLatLngBounds(b.build(), 80, topPx, 80, bottomPx)
        runCatching { if (Anim.ms(600) > 0) m.animateCamera(u, Anim.ms(600)) else m.moveCamera(u) }
    }

    fun moveTo(ll: LatLng, zoom: Double) {
        val u = CameraUpdateFactory.newLatLngZoom(ll, zoom)
        val d = Anim.ms(500)
        if (d > 0) map?.animateCamera(u, d) else map?.moveCamera(u)
    }

    fun setStations(geojson: String, version: String) {
        if (version == shownVersion) return
        (style?.getSource("stations") as? GeoJsonSource)?.setGeoJson(geojson)
        TaktApp.mark("setStations")
        shownVersion = version
    }

    fun setSelected(s: Station?) {
        val src = style?.getSource("sel") as? GeoJsonSource ?: return
        if (s == null) src.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        else src.setGeoJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${s.lon},${s.lat}]},"properties":{}}""")
    }

    @Suppress("MissingPermission")
    fun enableLocation(ctx: Context) {
        val s = style ?: return
        val m = map ?: return
        if (!TaktApp.instance.loc.hasPermission()) return
        runCatching {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, s).useDefaultLocationEngine(true).build())
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.COMPASS
        }
    }
}

val LocalMapHolder = staticCompositionLocalOf<MapHolder> { error("no map") }

/** Raise when the states publish new flights: every phone then loads the aerial tiles again once. */
const val AERIAL_VERSION = 6

/** Journey shown on the map ("Auf Karte zeigen"). Saved like the journey fields. */
object MapRoute {
    private var _it by mutableStateOf(TaktApp.instance.prefs.loadRoute())
    var itinerary: app.takt.data.Itinerary?
        get() = _it
        set(v) { _it = v; TaktApp.instance.prefs.saveRoute(v) }
}

private fun modeHex(m: String) = when (m) {
    "tram" -> "#E5243B"; "bus" -> "#8E4FD1"; "sbahn" -> "#17A35B"; "train" -> "#64748B"; "walk" -> "#94A3B8"; else -> "#475569"
}
private fun parseColorHex(c: String?) = c?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) && it.uppercase() != "#FFFFFF" }

/** Previous start, so "Hierhin" can undo the automatic start from a tap. */
private object MapSel {
    var selected by mutableStateOf<String?>(null)
    var prevFrom: SavedPlace? = null
}

@Composable
fun MapScreen(nav: Nav) {
    val app = TaktApp.instance
    val ctx = LocalContext.current
    val holder = LocalMapHolder.current
    val geojson by app.stations.geojson.collectAsState()
    val mapDark by app.prefs.mapDark.collectAsState()
    val mapSat by app.prefs.mapSat.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val askPerm = rememberLocationPermission { permTick++ }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<Int?>(null) }

    // back on the map: hide the shown route, then empty the fields, then the app closes.
    // After a jump (e.g. "Auf Karte zeigen") back returns to where it came from instead.
    androidx.activity.compose.BackHandler(
        enabled = !nav.canReturn && (MapRoute.itinerary != null || JourneyState.from != null || JourneyState.to != null),
    ) {
        if (MapRoute.itinerary != null) MapRoute.itinerary = null
        else { clearJourney(); MapSel.prevFrom = null }
    }
    // back closes the station panel first (registered last, so it wins)
    androidx.activity.compose.BackHandler(enabled = MapSel.selected != null) { MapSel.selected = null }
    SideEffect {
        holder.onStationClick = { id ->
            val st = app.stations.get(id)
            if (st != null) {
                if (JourneyState.from?.station != st.id) MapSel.prevFrom = JourneyState.from
                JourneyState.from = SavedPlace(st.name, st.id)
            }
            MapSel.selected = st?.id
        }
    }
    LaunchedEffect(holder.map, mapDark, mapSat) {
        // wait briefly for the station data (normally ready within ~50 ms) so it is part of the first frame
        val g = geojson ?: kotlinx.coroutines.withTimeoutOrNull(400) { app.stations.geojson.filterNotNull().first() }
        holder.applyStyle(mapDark, g, mapSat)
    }
    LaunchedEffect(holder.style, geojson) { geojson?.let { holder.setStations(it, app.stations.version) } }
    LaunchedEffect(holder.style, MapSel.selected) { holder.setSelected(app.stations.get(MapSel.selected)) }
    LaunchedEffect(holder.style, permTick) { holder.enableLocation(ctx) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bottomInset = LocalBottomInset.current
    LaunchedEffect(holder.style, MapRoute.itinerary) {
        holder.setRoute(MapRoute.itinerary)
    }
    LaunchedEffect(holder.map, MapRoute.itinerary) {
        val it = MapRoute.itinerary ?: return@LaunchedEffect
        MapSel.selected = null
        with(density) { holder.fitRoute(it, (bottomInset + 230.dp).roundToPx(), 160.dp.roundToPx()) }
    }
    // centre on the user once the first position arrives (unless they already moved the map)
    LaunchedEffect(holder.map, permTick) {
        val m = holder.map ?: return@LaunchedEffect
        launch { refreshPosition() }
        val l = snapshotFlow { Position.loc }.filterNotNull().first()
        if (!holder.cameraSettled) {
            holder.cameraSettled = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), 15.5))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ (holder.view.parent as? ViewGroup)?.removeView(holder.view); holder.view }, Modifier.fillMaxSize())
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); holder.revealed = true }
        val cover by androidx.compose.animation.core.animateFloatAsState(if (holder.revealed) 0f else 1f, Anim.spec(180), label = "cover")
        if (cover > 0f) Box(Modifier.fillMaxSize().alpha(cover).background(if (mapDark) androidx.compose.ui.graphics.Color(0xFF0E0E0E) else androidx.compose.ui.graphics.Color(0xFFF2F2F0)))


        GuideBanner(nav, Modifier.statusBarsPadding().padding(12.dp))
        val sel = app.stations.get(MapSel.selected)
        // keep showing the last station while the panel slides out
        var lastSel by remember { mutableStateOf<Station?>(null) }
        if (sel != null) lastSel = sel
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomInset.current)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.Bottom) {
                RoundButton(androidx.compose.material.icons.Icons.Rounded.Settings, "Einstellungen", { nav.settings() })
                RoundButton(if (mapSat) androidx.compose.material.icons.Icons.Rounded.Map else androidx.compose.material.icons.Icons.Rounded.SatelliteAlt,
                    if (mapSat) "Luftbild aus" else "Luftbild an", { app.prefs.toggleMapSat() })
                RoundButton(if (mapDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Kartenstil wechseln", { app.prefs.toggleMapDark() })
                RoundButton(Icons.Rounded.MyLocation, "Mein Standort", {
                    if (!app.loc.hasPermission()) askPerm()
                    else scope.launch {
                        Position.loc?.let { holder.moveTo(LatLng(it.latitude, it.longitude), 16.0) }
                        refreshPosition()
                        Position.loc?.let { holder.moveTo(LatLng(it.latitude, it.longitude), 16.0) }
                    }
                })
            }
            // panel and journey input share one slot: the panel slides in over the input instead of stacking,
            // so nothing jumps when one of them leaves
            Box(contentAlignment = Alignment.BottomCenter) {
                val shownRoute = MapRoute.itinerary
                if (sel == null && shownRoute != null) RouteSummary(shownRoute, nav)
                else if (sel == null) RouteCard(
                    from = JourneyState.from?.let { shortName(it.name) }, to = JourneyState.to?.let { shortName(it.name) },
                    fromHint = "Haltestelle antippen", toHint = "Wohin?",
                    onFrom = { picking = 0 }, onTo = { picking = 1 },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                    onClear = { clearJourney(); MapSel.prevFrom = null },
                )
                androidx.compose.animation.AnimatedVisibility(visible = sel != null, enter = slideInVertically(Anim.offset(260)) { it }, exit = slideOutVertically(Anim.offset(220)) { it }) {
                    val shown = sel ?: lastSel
                    if (shown != null) StationPanel(shown, nav,
                        onClose = { MapSel.selected = null },
                        onPickDest = { picking = 1 },
                        onHere = {
                            if (JourneyState.from?.station == shown.id) JourneyState.from = MapSel.prevFrom ?: Position.loc?.let { SavedPlace("Mein Standort", null, it.latitude, it.longitude) }
                            MapSel.selected = null
                            nav.setDest(SavedPlace(shown.name, shown.id))
                        })
                }
            }
        }
    }

    picking?.let { which ->
        PlacePicker(
            title = if (which == 0) "Start" else "Ziel",
            onDismiss = { picking = null },
            onPick = { p ->
                if (which == 0) JourneyState.from = p else JourneyState.to = p
                picking = null
                if (JourneyState.from != null && JourneyState.to != null) { MapSel.selected = null; nav.journey() }
            },
        )
    }
}

@Composable
private fun StationPanel(s: Station, nav: Nav, onClose: () -> Unit, onPickDest: () -> Unit, onHere: () -> Unit) {
    val app = TaktApp.instance
    val (live, _) = rememberDepartures(s.id)
    val favs by app.prefs.favStations.collectAsState()
    val now = nowMs() / 1000
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.shortName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeDots(s.modes, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        AgeChip(live.data, live.loading, live.error)
                    }
                }
                IconButton(onClick = { app.prefs.toggleFavStation(s.id) }) {
                    Icon(if (s.id in favs) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorit", tint = if (s.id in favs) Palette.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Schließen") }
            }
            ErrorLine(live.error)
            val deps = upcoming(live.data?.value?.departures.orEmpty(), now).take(4)
            if (deps.isEmpty() && live.data != null) Text("Keine Abfahrten in den nächsten 90 Minuten.", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            // reserve the room of four rows while loading, so the panel does not grow afterwards
            if (deps.isEmpty() && live.data == null) Spacer(Modifier.height(284.dp))
            deps.forEachIndexed { i, d ->
                if (i > 0) RowDivider(70.dp)
                DepartureRow(d, now, onClick = {
                    if (d.trip.isNotBlank() && live.data?.value?.source != "insa") nav.trip(d.trip, d.line) else nav.station(s.id)
                })
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Ziel wählen", Icons.Rounded.Search, primary = true, onClick = onPickDest, modifier = Modifier.weight(1f))
                PillButton("Hierhin", Icons.Rounded.Place, primary = false, onClick = onHere)
                TextButton(onClick = { nav.station(s.id) }) { Text("Alle") }
            }
        }
    }
}


@Composable
private fun RouteSummary(it: app.takt.data.Itinerary, nav: Nav) {
    val legs = it.legs
    val start = legs.firstOrNull()?.let { l -> l.from.rt ?: l.from.t } ?: 0
    val end = legs.lastOrNull()?.let { l -> l.to.rt ?: l.to.t } ?: 0
    val startGuide = rememberGuideStarter()
    val guide = TaktApp.instance.guide
    val active by guide.active.collectAsState()
    Surface(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${app.takt.data.hhmm(start)} – ${app.takt.data.hhmm(end)} · ${app.takt.data.durationText(end - start)}", style = MaterialTheme.typography.titleMedium.merge(Tnum))
                    Text("${placeName(legs.first().from.name, true)} → ${placeName(legs.last().to.name, false)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { MapRoute.itinerary = null }) { Icon(Icons.Rounded.Close, "Route ausblenden") }
            }
            Row(Modifier.padding(top = 8.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active == null || !guide.isActive(it)) PillButton("Begleiten", androidx.compose.material.icons.Icons.Rounded.NotificationsActive, primary = true, onClick = { startGuide(it) }, modifier = Modifier.weight(1f))
                else PillButton("Begleitung beenden", androidx.compose.material.icons.Icons.Rounded.NotificationsOff, primary = false, onClick = { guide.stop() }, modifier = Modifier.weight(1f))
                PillButton("Details", null, primary = false, onClick = { nav.journey() })
            }
        }
    }
}

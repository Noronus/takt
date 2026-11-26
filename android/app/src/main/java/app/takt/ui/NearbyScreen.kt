package app.takt.ui

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.SavedPlace
import app.takt.data.Station
import app.takt.data.ageText
import app.takt.data.distText
import app.takt.data.lastUsedText

/** Last known position, shared by the nearby list, the map and the journey picker. */
object Position {
    var loc by mutableStateOf<Location?>(null)
    var locating by mutableStateOf(false)
}

/** The own position as a journey place, or null while it is unknown. */
fun myLocationPlace(): SavedPlace? = Position.loc?.let { SavedPlace("Mein Standort", null, it.latitude, it.longitude) }

/** The start that is filled in by itself (own position): counts as an empty field, e.g. for back. */
fun SavedPlace.isMyLocation() = station == null && name == "Mein Standort"

/** An empty start means the own position (as soon as it is known). Called by the map and the route tab. */
@androidx.compose.runtime.Composable
fun DefaultStartIsMyLocation() {
    val haveLoc = Position.loc != null
    androidx.compose.runtime.LaunchedEffect(JourneyState.from == null, haveLoc) {
        if (JourneyState.from == null) myLocationPlace()?.let { JourneyState.from = it }
    }
}

suspend fun refreshPosition() {
    val l = TaktApp.instance.loc
    if (!l.hasPermission()) return
    if (Position.loc == null) Position.loc = l.lastKnown()
    Position.locating = true
    try { l.current()?.let { Position.loc = it } } finally { Position.locating = false }
}

@Composable
fun rememberLocationPermission(onGranted: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) onGranted()
    }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(nav: Nav) {
    val app = TaktApp.instance
    val stations by app.stations.list.collectAsState()
    val favIds by app.prefs.favStations.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val history by app.prefs.history.collectAsState()
    // age of the realtime data, like on the other tabs (the list itself has none)
    val rt = rememberRtAge()
    // back leaves the search field first
    androidx.activity.compose.BackHandler(enabled = searching) { query = ""; focusManager.clearFocus() }
    // keyboard closed (swiped away, back key): leave the field, so the nearby list returns and the cursor stops
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var imeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) {
        if (imeWasOpen && !imeOpen) focusManager.clearFocus()
        imeWasOpen = imeOpen
    }
    var permTick by remember { mutableIntStateOf(0) }
    val askPerm = rememberLocationPermission { permTick++ }
    val hasPerm = remember(permTick) { app.loc.hasPermission() }
    LaunchedEffect(permTick) { refreshPosition() }
    val now = nowMs()
    val favs = favIds.mapNotNull { app.stations.get(it) }
    val mode = when { query.isNotBlank() -> "Suche"; searching -> "Zuletzt gewählt"; else -> null }
    // "Haltestellen" stays under the search field once its place in the list has scrolled up there
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val favCollapsed by app.prefs.favCollapsed.collectAsState()
    val hdrIndex = (if (stations.isEmpty()) 1 else 0) + (if (favs.isNotEmpty()) 1 + (if (favCollapsed) 0 else favs.size) else 0)
    val stationsPinned by remember(hdrIndex) {
        androidx.compose.runtime.derivedStateOf {
            val hdr = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hdr-st" }
            if (hdr != null) hdr.offset < 0 else listState.firstVisibleItemIndex > hdrIndex
        }
    }

    // pull down: fetch a new position (replaces the old GPS button)
    val (pulling, onPull) = rememberPullRefresh { refreshPosition() }
    Column(Modifier.fillMaxSize().statusBarsPadding()
        // a tap on empty space (not on a row) leaves the search field
        .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
    // title and search: fixed above the list (only the list scrolls and stretches)
    ScreenTitle("In der Nähe", age = { AgeChip(rt.data, rt.loading, rt.error, compact = true) }) { SettingsButton(nav) }
    SearchField(query, { query = it }, "Haltestelle suchen", fieldModifier = Modifier.onFocusChanged { searching = it.isFocused })
    if (mode != null) SectionHeader(mode, now)
    // the list ends above the keyboard, so rows hidden by it can be scrolled into view
    Box(Modifier.fillMaxWidth().weight(1f).imePadding()) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = pulling, onRefresh = onPull, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, overscrollEffect = rememberListOverscroll(listState),
        // with the keyboard open the bottom bar is covered anyway: no room kept for it
        contentPadding = PaddingValues(bottom = if (imeOpen) 8.dp else LocalBottomInset.current + 8.dp)) {
        if (stations.isEmpty()) item { Text("Haltestellenliste fehlt noch. Server in den Einstellungen prüfen.", Modifier.padding(20.dp)) }

        val lastPicked = history.associate { it.station to it.at }

        if (query.isNotBlank()) {
            val res = app.stations.search(query)
            itemsIndexed(res, key = { _, s -> "s" + s.id }) { i, s ->
                GroupedStationRow(s, Position.loc?.let { dist(it, s) }, nav, i, res.size, note = lastPicked[s.id]?.let { lastUsedText(it) })
            }
            return@LazyColumn
        }
        // search field tapped, nothing typed yet: the stations picked before, nearest first
        if (searching) {
            val l = Position.loc
            val recent = history.mapNotNull { h -> app.stations.get(h.station)?.let { Triple(it, h.at, l?.let { p -> dist(p, it) }) } }
                .let { list -> if (l != null) list.sortedBy { it.third } else list }
            if (recent.isEmpty()) item { Text("Noch keine Haltestelle gewählt.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(recent, key = { _, r -> "h" + r.first.id }) { i, (s, at, m) -> GroupedStationRow(s, m, nav, i, recent.size, note = lastUsedText(at)) }
            return@LazyColumn
        }
        if (favs.isNotEmpty()) {
            // tap on the title folds the favourites away (and back); the stars in the lists stay
            item(key = "hdr-fav") {
                Row(Modifier.fillMaxWidth().clickableRow { app.prefs.toggleFavCollapsed() }, verticalAlignment = Alignment.Bottom) {
                    SectionTitle(if (favCollapsed) "Favoriten (${favs.size})" else "Favoriten")
                    Icon(if (favCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, if (favCollapsed) "Favoriten einblenden" else "Favoriten ausblenden",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).size(18.dp))
                }
            }
            if (!favCollapsed) itemsIndexed(favs, key = { _, s -> "f" + s.id }) { i, s -> GroupedStationRow(s, Position.loc?.let { dist(it, s) }, nav, i, favs.size) }
        }
        item(key = "hdr-st") { SectionHeader("Haltestellen", now) }
        if (!hasPerm) item {
            Group {
                Column(Modifier.padding(18.dp)) {
                    Text("Standort erlauben", style = MaterialTheme.typography.titleMedium)
                    Text("Für Haltestellen in der Nähe. Der Standort bleibt auf dem Gerät.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Erlauben", Icons.Rounded.MyLocation, primary = true, onClick = askPerm)
                }
            }
        }
        val l = Position.loc
        if (l != null) {
            val list = app.stations.nearby(l.latitude, l.longitude, 25)
            itemsIndexed(list, key = { _, n -> "n" + n.station.id }) { i, n -> GroupedStationRow(n.station, n.meters, nav, i, list.size) }
        } else if (hasPerm) item { Text("Warte auf Standort …", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    }
    // pinned copy of "Haltestellen" over the list (does not change the list size, so nothing jumps)
    if (mode == null && stationsPinned) Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) { SectionHeader("Haltestellen", now) }
    }
    }
}

/** Section title with the position info (accuracy and age) on the right. */
@Composable
private fun SectionHeader(title: String, now: Long) {
    Row(Modifier.fillMaxWidth().padding(end = 24.dp), verticalAlignment = Alignment.Bottom) {
        SectionTitle(title, Modifier.weight(1f))
        val l = Position.loc
        Text(
            when {
                Position.locating -> "orte …"
                l != null -> "±${l.accuracy.toInt()} m · ${ageText(((now - l.time) / 1000).coerceAtLeast(0))} alt"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

fun dist(l: Location, s: Station): Int {
    val r = FloatArray(1)
    Location.distanceBetween(l.latitude, l.longitude, s.lat, s.lon, r)
    return r[0].toInt()
}

/** Station row drawn as part of a rounded group (first/last rows get the rounded corners). */
@Composable
fun GroupedStationRow(s: Station, meters: Int?, nav: Nav, index: Int, count: Int, note: String? = null) {
    val shape = groupShape(index, count)
    val favIds by TaktApp.instance.prefs.favStations.collectAsState()
    // every pick from these lists goes into the history (the map does not)
    val record = { TaktApp.instance.prefs.recordStation(s.id) }
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickableRow { record(); nav.station(s.id) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.shortName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeDots(s.modes, 18.dp)
                        val info = listOfNotNull(meters?.let { distText(it) }, note).joinToString(" · ")
                        if (info.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                // favourite on/off, on every row
                val isFav = s.id in favIds
                IconButton(onClick = { TaktApp.instance.prefs.toggleFavStation(s.id) }) {
                    Icon(if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (isFav) "Favorit entfernen" else "Als Favorit",
                        tint = if (isFav) Palette.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // same marks as in the map panel: ring = as start, pin = as destination
                IconButton(onClick = { record(); nav.setStart(SavedPlace(s.name, s.id)) }) { Icon(Icons.Rounded.TripOrigin, "Als Start", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { record(); nav.setDest(SavedPlace(s.name, s.id)) }) { Icon(Icons.Rounded.Place, "Als Ziel", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (index < count - 1) RowDivider()
        }
    }
}

fun groupShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
    val r = 22.dp
    return androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
}

package app.takt.ui

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.SavedPlace
import app.takt.data.Station
import app.takt.data.ageText
import app.takt.data.distText
import kotlinx.coroutines.launch

/** Last known position, shared by the nearby list, the map and the journey picker. */
object Position {
    var loc by mutableStateOf<Location?>(null)
    var locating by mutableStateOf(false)
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

@Composable
fun NearbyScreen(nav: Nav) {
    val app = TaktApp.instance
    val stations by app.stations.list.collectAsState()
    val favIds by app.prefs.favStations.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var permTick by remember { mutableIntStateOf(0) }
    val askPerm = rememberLocationPermission { permTick++ }
    val hasPerm = remember(permTick) { app.loc.hasPermission() }
    LaunchedEffect(permTick) { refreshPosition() }
    val now = nowMs()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 8.dp)) {
        item {
            ScreenTitle("In der Nähe") {
                IconButton(onClick = { scope.launch { refreshPosition() } }) { Icon(Icons.Rounded.MyLocation, "Standort aktualisieren") }
                IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Einstellungen") }
            }
        }
        item { SearchField(query, { query = it }, "Haltestelle suchen") }
        if (stations.isEmpty()) item { Text("Haltestellenliste fehlt noch. Server in den Einstellungen prüfen.", Modifier.padding(20.dp)) }

        if (query.isNotBlank()) {
            val res = app.stations.search(query)
            item { Spacer(Modifier.height(8.dp)) }
            itemsIndexed(res, key = { _, s -> "s" + s.id }) { i, s -> GroupedStationRow(s, null, nav, i, res.size) }
            return@LazyColumn
        }
        val favs = favIds.mapNotNull { app.stations.get(it) }
        if (favs.isNotEmpty()) {
            item { SectionTitle("Favoriten") }
            itemsIndexed(favs, key = { _, s -> "f" + s.id }) { i, s -> GroupedStationRow(s, Position.loc?.let { dist(it, s) }, nav, i, favs.size, fav = true) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 24.dp), verticalAlignment = Alignment.Bottom) {
                SectionTitle("Haltestellen", Modifier.weight(1f))
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

fun dist(l: Location, s: Station): Int {
    val r = FloatArray(1)
    Location.distanceBetween(l.latitude, l.longitude, s.lat, s.lon, r)
    return r[0].toInt()
}

/** Station row drawn as part of a rounded group (first/last rows get the rounded corners). */
@Composable
fun GroupedStationRow(s: Station, meters: Int?, nav: Nav, index: Int, count: Int, fav: Boolean = false) {
    val shape = groupShape(index, count)
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickableRow { nav.station(s.id) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (fav) { Icon(Icons.Rounded.Star, null, tint = Palette.warn); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(s.shortName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeDots(s.modes, 18.dp)
                        if (meters != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(distText(meters), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = { nav.setStart(SavedPlace(s.name, s.id)) }) { Icon(Icons.Rounded.TripOrigin, "Als Start", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { nav.setDest(SavedPlace(s.name, s.id)) }) { Icon(Icons.Rounded.Place, "Als Ziel", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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

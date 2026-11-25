package app.takt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.SavedPlace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationScreen(stationId: String, nav: Nav) {
    val app = TaktApp.instance
    val st = app.stations.get(stationId)
    val (live, reload) = rememberDepartures(stationId)
    val favs by app.prefs.favStations.collectAsState()
    val name = live.data?.value?.station?.name ?: st?.name ?: stationId
    val now = nowMs() / 1000
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
            Spacer(Modifier.weight(1f))
            AgeChip(live.data, live.loading, live.error, compact = true)
            IconButton(onClick = { app.prefs.toggleFavStation(stationId) }) {
                Icon(if (stationId in favs) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorit", tint = if (stationId in favs) Palette.warn else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(shortName(name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st != null) ModeDots(st.modes)
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            PillButton("Von hier", Icons.Rounded.TripOrigin, primary = true, onClick = { nav.setStart(SavedPlace(name, stationId)) })
            Spacer(Modifier.width(8.dp))
            PillButton("Hierhin", Icons.Rounded.Place, primary = false, onClick = { nav.setDest(SavedPlace(name, stationId)) })
        }
        if (live.data?.value?.source == "insa") Text("Ausweichquelle INSA – MOTIS nicht erreichbar", style = MaterialTheme.typography.labelSmall, color = Palette.warn, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        ErrorLine(live.error, Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        PullToRefreshBox(isRefreshing = live.loading && live.data != null, onRefresh = reload, modifier = Modifier.fillMaxSize()) {
            val deps = upcoming(live.data?.value?.departures.orEmpty(), now)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (deps.isEmpty() && live.data != null) item {
                    Text("Keine Abfahrten in den nächsten 90 Minuten.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(deps, key = { _, d -> "${d.trip}|${d.t}" }) { i, d ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = groupShape(i, deps.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            DepartureRow(d, now, onClick = if (d.trip.isNotBlank() && live.data?.value?.source != "insa") ({ nav.trip(d.trip, d.line) }) else null)
                            if (i < deps.size - 1) RowDivider(70.dp)
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

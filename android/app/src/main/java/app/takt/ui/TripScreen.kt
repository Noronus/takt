package app.takt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.TripResponse
import app.takt.data.hhmm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun TripScreen(tripId: String, line: String, at: String?, nav: Nav) {
    val app = TaktApp.instance
    val live = remember(tripId) { Live<TripResponse>() }
    LaunchedEffect(tripId) {
        while (true) {
            live.loading = true
            try { live.data = app.repo.trip(tripId); live.error = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { live.error = e.message }
            finally { live.loading = false }
            delay(30_000)
        }
    }
    val t = live.data?.value
    val now = nowMs() / 1000
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val stopsNow = t?.stops.orEmpty()
    // next stop the vehicle leaves (realtime if known): while it waits at a stop, that stop; the one before is where it last was
    fun stopTime(s: app.takt.data.TripStop) = (s.dep ?: s.arr)?.let { it.rt ?: it.t }
    val next = stopsNow.indexOfFirst { s -> (stopTime(s) ?: 0) >= now }.let { if (it < 0) stopsNow.size else it }
    // open once at the stop it came from, or where the vehicle is; later refreshes do not move the list
    var scrolled by remember(tripId) { mutableStateOf(false) }
    LaunchedEffect(t != null) {
        if (t == null || scrolled) return@LaunchedEffect
        scrolled = true
        val target = at?.let { a -> stopsNow.indexOfFirst { it.station == a } }?.takeIf { it >= 0 } ?: next
        // item 0 is the header, stop i is item i + 1; one stop above the target stays in view.
        // Wait until the list holds the stops, else the position is cut short.
        val item = target  // = 1 + (target - 1)
        if (item > 1) {
            kotlinx.coroutines.withTimeoutOrNull(2000) {
                androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > item }
            }
            listState.scrollToItem(item)
        }
    }
    val accent = parseColor(t?.color) ?: t?.mode?.let(::modeColor)?.takeIf { t.mode != "other" } ?: MaterialTheme.colorScheme.primary
    // top bar outside the list: back and data age stay visible while scrolling the stops
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
        Spacer(Modifier.weight(1f))
        AgeChip(live.data, live.loading, live.error, compact = true)
    }
    // room below the last stop, so a stop near the end of the line can still scroll to the top
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val tail = (maxHeight - 200.dp).coerceAtLeast(32.dp)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = tail)) {
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                LineBadge(t?.line ?: line, t?.mode ?: "other", t?.color)
                Spacer(Modifier.width(12.dp))
                Text(t?.dir ?: "", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(10.dp))
            ErrorLine(live.error)
            if (t != null && !t.realtime) Text("Keine Echtzeit für diese Fahrt – Soll-Fahrplan.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t?.cancelled == true) Text("Diese Fahrt fällt aus.", Modifier.padding(horizontal = 20.dp), color = Palette.bad, style = MaterialTheme.typography.titleSmall)
        }
        val stops = t?.stops.orEmpty()
        itemsIndexed(stops) { i, s ->
            val tp = s.dep ?: s.arr
            val time = tp?.rt ?: tp?.t
            val past = time != null && time < now - 30
            val color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickableRow { s.station?.let(nav::station) }.padding(horizontal = 20.dp)) {
                Column(Modifier.widthIn(min = 58.dp).padding(vertical = 10.dp)) {
                    Text(hhmm(time), style = MaterialTheme.typography.titleSmall.merge(Tnum), color = color, textDecoration = if (s.cancelled) TextDecoration.LineThrough else null)
                    if (tp?.t != null && tp.rt != null) DelayChip(tp.t, tp.rt, s.cancelled, true)
                }
                // timeline
                Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(3.dp).weight(1f).background(if (i == 0) androidx.compose.ui.graphics.Color.Transparent else accent.copy(alpha = if (past) 0.35f else 1f)))
                        // the vehicle: a filled dot on its next stop (or at the stop while it waits there)
                        if (i == next && t?.cancelled != true && next < stops.size) Box(Modifier.size(16.dp).clip(CircleShape).background(accent).border(2.dp, MaterialTheme.colorScheme.background, CircleShape))
                        else Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(3.dp, accent.copy(alpha = if (past) 0.35f else 1f), CircleShape))
                        Box(Modifier.width(3.dp).weight(1f).background(if (i == stops.size - 1) androidx.compose.ui.graphics.Color.Transparent else accent.copy(alpha = if (past) 0.35f else 1f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortName(s.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color,
                        fontWeight = if (i == next || s.station == at) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                        textDecoration = if (s.cancelled) TextDecoration.LineThrough else null)
                    s.track?.let { Text("Steig $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    }
    }
}

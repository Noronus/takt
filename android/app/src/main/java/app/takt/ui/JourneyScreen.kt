package app.takt.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.takt.TaktApp
import app.takt.data.FavRoute
import app.takt.data.GeoMatch
import app.takt.data.Itinerary
import app.takt.data.Leg
import app.takt.data.PlanResponse
import app.takt.data.SavedPlace
import app.takt.data.Timed
import app.takt.data.countdown
import app.takt.data.delayMin
import app.takt.data.distText
import app.takt.data.durationText
import app.takt.data.hhmm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId

private object PlanState {
    var time by mutableStateOf<Long?>(null)   // null = now
    var arriveBy by mutableStateOf(false)
    var result by mutableStateOf<Timed<PlanResponse>?>(null)   // all loaded pages merged
    var resultKey by mutableStateOf("")
    /** Loaded pages, top to bottom: cursor (null = first search) and answer. The auto refresh reloads all of them. */
    var cursors: List<String?> = listOf(null)
    var pages: List<Timed<PlanResponse>> = emptyList()
    var loadingMore by mutableIntStateOf(0)    // -1 earlier, 1 later
    var expanded by mutableStateOf<String?>(null)
}

private const val MAX_PAGES = 6

/** Empties start, destination and the results (✕ on map and journey tab, back on the map). */
fun clearJourney() {
    JourneyState.from = null; JourneyState.to = null
    PlanState.result = null; PlanState.resultKey = ""; PlanState.cursors = listOf(null); PlanState.pages = emptyList(); PlanState.expanded = null
}

/** Same journey on two pages (pages overlap a little). */
private fun sig(it: Itinerary) = it.legs.joinToString("|") { l -> "${l.mode}:${l.trip}:${l.from.t}" }

/** One list from all pages; its age is that of the oldest page. */
private fun merge(pages: List<Timed<PlanResponse>>): Timed<PlanResponse> {
    val seen = HashSet<String>()
    val its = pages.flatMap { it.value.itineraries }.filter { seen.add(sig(it)) }
    val oldest = pages.maxBy { it.ageSeconds() }
    val v = pages.first().value.copy(prev = pages.first().value.prev, next = pages.last().value.next, itineraries = its)
    return Timed(v, oldest.receivedAtMs, oldest.feedAgeAtReceive)
}

@Composable
fun JourneyScreen(nav: Nav) {
    val app = TaktApp.instance
    val scope = rememberCoroutineScope()
    val live = remember { Live<PlanResponse>().also { it.data = PlanState.result } }
    var picking by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    val favRoutes by app.prefs.favRoutes.collectAsState()
    val listState = rememberLazyListState()

    fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            live.loading = true
            try { block(); live.error = null } catch (e: CancellationException) { throw e } catch (e: Exception) {
                live.error = e.message
            } finally { live.loading = false; PlanState.loadingMore = 0 }
        }
    }
    fun show(pages: List<Timed<PlanResponse>>, cursors: List<String?>, key: String) {
        PlanState.pages = pages; PlanState.cursors = cursors
        val m = merge(pages)
        live.data = m; PlanState.result = m; PlanState.resultKey = key
    }
    fun plan(f: SavedPlace, t: SavedPlace, c: String?) = suspend { app.repo.plan(f.param(), t.param(), PlanState.time, PlanState.arriveBy, c) }
    /** Loads the given pages again (refresh) or a new search ([null]). */
    fun load(cursors: List<String?>) {
        val f = JourneyState.from ?: return
        val t = JourneyState.to ?: return
        run { show(cursors.map { c -> plan(f, t, c)() }, cursors, "${f.param()}>${t.param()}") }
    }
    fun search() = load(listOf(null))
    /** Früher/Später: adds a page above or below and keeps the visible connections where they are. */
    fun more(earlier: Boolean) {
        val f = JourneyState.from ?: return
        val t = JourneyState.to ?: return
        val res = live.data?.value ?: return
        val c = (if (earlier) res.prev else res.next) ?: return
        val pages = PlanState.pages.ifEmpty { listOfNotNull(live.data) }
        val cursors = if (PlanState.pages.isEmpty()) listOf<String?>(null) else PlanState.cursors
        // first visible connection and its offset, to put it back after the list grew above it
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("it:") == true }
        PlanState.loadingMore = if (earlier) -1 else 1
        run {
            val p = plan(f, t, c)()
            var ps = if (earlier) listOf(p) + pages else pages + p
            var cs = if (earlier) listOf(c) + cursors else cursors + c
            // keep the refresh cheap: drop the page farthest from the new one
            if (ps.size > MAX_PAGES) { if (earlier) { ps = ps.dropLast(1); cs = cs.dropLast(1) } else { ps = ps.drop(1); cs = cs.drop(1) } }
            val hadPrev = res.prev != null
            val countBefore = listState.layoutInfo.totalItemsCount
            show(ps, cs, PlanState.resultKey)
            // scroll only once the list has been measured with the new items
            if (earlier && anchor != null) kotlinx.coroutines.withTimeoutOrNull(1500) {
                androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it != countBefore }
            }
            if (earlier && anchor != null) {
                val key = anchor.key as String
                val its = live.data!!.value.itineraries
                val newPos = its.indexOfFirst { "it:" + sig(it) == key }
                val oldPos = res.itineraries.indexOfFirst { "it:" + sig(it) == key }
                if (newPos >= 0 && oldPos >= 0) {
                    val button = (if (live.data!!.value.prev != null) 1 else 0) - (if (hadPrev) 1 else 0)
                    listState.scrollToItem((anchor.index + newPos - oldPos + button).coerceAtLeast(0))
                    // then put the remembered connection exactly where it was
                    androidx.compose.runtime.withFrameNanos { }
                    val now = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                    if (now != null) listState.scrollBy((now.offset - anchor.offset).toFloat())
                    else listState.scrollBy(-anchor.offset.toFloat())
                }
            }
        }
    }

    LaunchedEffect(JourneyState.from, JourneyState.to) {
        val f = JourneyState.from; val t = JourneyState.to
        if (f != null && t != null && PlanState.resultKey != "${f.param()}>${t.param()}") { live.data = null; search() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            // results older than 30 s (e.g. back from another tab) are refreshed right away, then every 30 s;
            // while the server's data itself is old, more often until it catches up. All loaded pages stay.
            val age = live.data?.let { (System.currentTimeMillis() - it.receivedAtMs) / 1000 } ?: 0
            val stale = (live.data?.ageSeconds() ?: 0) > 90
            if (PlanState.time == null && live.data != null && !live.loading && (age >= 30 || stale && age >= 6)) load(PlanState.cursors)
            delay(if (stale) 6_000 else 3_000)
        }
    }

    val f = JourneyState.from; val t = JourneyState.to
    val res = live.data?.value
    val guidedState by app.guide.active.collectAsState()
    @Suppress("UNUSED_VARIABLE") val g0 = guidedState
    val nowS = nowMs() / 1000
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = LocalBottomInset.current + 8.dp)) {
        stickyHeader {
            ScreenTitle("Route", age = if (live.data != null || live.loading || live.error != null) ({ AgeChip(live.data, live.loading, live.error, compact = true) }) else null) {
                if (f != null && t != null) {
                    val fav = favRoutes.any { it.from.param() == f.param() && it.to.param() == t.param() }
                    IconButton(onClick = { app.prefs.toggleFavRoute(FavRoute(f, t)) }) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Route merken", tint = if (fav) Palette.warn else MaterialTheme.colorScheme.onSurface)
                    }
                }
                SettingsButton(nav)
            }
        }
        item { GuideBanner(nav, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) }
        item {
            RouteCard(
                from = f?.let { shortName(it.name) }, to = t?.let { shortName(it.name) },
                fromHint = "Start", toHint = "Ziel",
                onFrom = { picking = 0 }, onTo = { picking = 1 },
                modifier = Modifier.padding(horizontal = 12.dp),
                trailing = {
                    IconButton(onClick = { val x = JourneyState.from; JourneyState.from = JourneyState.to; JourneyState.to = x }) {
                        Icon(Icons.Rounded.SwapVert, "Tauschen")
                    }
                },
                onClear = { job?.cancel(); clearJourney(); live.data = null; live.error = null },
            )
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("Ab", "An"), if (PlanState.arriveBy) 1 else 0) { PlanState.arriveBy = it == 1; search() }
                PillButton(PlanState.time?.let { dayTime(it) } ?: "Jetzt", Icons.Rounded.Schedule, primary = false, onClick = { timeDialog = true })
                if (PlanState.time != null) TextButton(onClick = { PlanState.time = null; search() }) { Text("Jetzt") }
            }
        }
        item { ErrorLine(live.error) }

        val guided = app.guide.active.value?.itinerary
        if (guided != null) {
            item { SectionTitle("Begleitete Fahrt") }
            item(key = "guided") {
                ItineraryCard(guided, nowS, expanded = PlanState.expanded == "guided:" + guided.id, nav = nav) {
                    PlanState.expanded = if (PlanState.expanded == "guided:" + guided.id) null else "guided:" + guided.id
                }
            }
            item { SectionTitle("Weitere Verbindungen") }
        }
        if (res == null) {
            if (favRoutes.isNotEmpty()) {
                item { SectionTitle("Gemerkte Routen") }
                itemsIndexed(favRoutes, key = { _, r -> r.from.param() + ">" + r.to.param() }) { i, r ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = groupShape(i, favRoutes.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            Row(Modifier.fillMaxWidth().clickableRow { JourneyState.from = r.from; JourneyState.to = r.to }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, null, tint = Palette.warn)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(shortName(r.from.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("→ " + shortName(r.to.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (i < favRoutes.size - 1) RowDivider(52.dp)
                        }
                    }
                }
            } else if (!live.loading) item {
                Text("Tipp: Auf der Karte eine Haltestelle antippen – sie wird automatisch Start.",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            if (res.prev != null) item(key = "earlier") { Centered(if (PlanState.loadingMore == -1) "lädt …" else "Früher") { more(true) } }
            if (res.itineraries.isEmpty()) item { Text("Keine Verbindung gefunden.", Modifier.padding(20.dp)) }
            items(res.itineraries, key = { "it:" + sig(it) }) { iti ->
                val k = sig(iti)
                ItineraryCard(iti, nowS, expanded = PlanState.expanded == k, nav = nav) {
                    PlanState.expanded = if (PlanState.expanded == k) null else k
                }
            }
            if (res.next != null) item(key = "later") { Centered(if (PlanState.loadingMore == 1) "lädt …" else "Später") { more(false) } }
        }
    }

    picking?.let { which ->
        PlacePicker(
            title = if (which == 0) "Start" else "Ziel",
            onDismiss = { picking = null },
            onPick = { p -> if (which == 0) JourneyState.from = p else JourneyState.to = p; picking = null },
        )
    }
    if (timeDialog) TimeDialog(PlanState.time, onDismiss = { timeDialog = false }) { t ->
        timeDialog = false
        PlanState.time = t
        search()
    }
}

/** Date and time in the app's own look: day chips and the Material clock, 24 h. null = now. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(current: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = current?.let { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDateTime() } ?: LocalDateTime.now()
    val today = java.time.LocalDate.now()
    var day by remember { mutableStateOf(start.toLocalDate().let { if (it.isBefore(today)) today else it }) }
    val state = androidx.compose.material3.rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Abfahrtszeit", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0L..13L).map { today.plusDays(it) }) { d ->
                        val label = when (d) {
                            today -> "Heute"; today.plusDays(1) -> "Morgen"
                            else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMAN).removeSuffix(".") + " %02d.%02d.".format(d.dayOfMonth, d.monthValue)
                        }
                        androidx.compose.material3.FilterChip(selected = d == day, onClick = { day = d }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.TimePicker(state)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onPick(null) }) { Text("Jetzt") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    TextButton(onClick = { onPick(day.atTime(state.hour, state.minute).atZone(zone).toEpochSecond()) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickableRow { onSelect(i) }.padding(horizontal = 16.dp, vertical = 7.dp),
            ) { Text(o, style = MaterialTheme.typography.labelLarge, color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Centered(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onClick) { Text(text) } }
}

private fun dayTime(s: Long): String {
    val t = java.time.Instant.ofEpochSecond(s).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val d = t.toLocalDate()
    val day = when (d) { today -> "heute"; today.plusDays(1) -> "morgen"; else -> "%02d.%02d.".format(d.dayOfMonth, d.monthValue) }
    return "$day ${hhmm(s)}"
}

private fun legStart(l: Leg) = l.from.rt ?: l.from.t ?: 0
private fun legEnd(l: Leg) = l.to.rt ?: l.to.t ?: 0

@Composable
private fun ItineraryCard(it: Itinerary, nowS: Long, expanded: Boolean, nav: Nav, onClick: () -> Unit) {
    val first = it.legs.firstOrNull() ?: return
    val last = it.legs.last()
    val firstTransit = it.legs.firstOrNull { l -> l.mode != "walk" }
    val cancelled = it.legs.any { l -> l.cancelled }
    val noRt = it.legs.any { l -> l.mode != "walk" && !l.realtime }
    val start = legStart(first); val end = legEnd(last)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).animateContentSize(Anim.size(220)),
        shape = MaterialTheme.shapes.large,
        color = if (cancelled) Palette.bad.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hhmm(start)} – ${hhmm(end)}", style = MaterialTheme.typography.titleLarge.merge(Tnum), modifier = Modifier.weight(1f))
                Text(durationText(end - start), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(2.dp))
            val sub = buildList {
                if (firstTransit != null) add("${firstTransit.line} ${countdown(legStart(firstTransit), nowS)}")
                add(if (it.transfers == 0) "direkt" else "${it.transfers} Umstieg${if (it.transfers == 1) "" else "e"}")
                it.via?.let { v -> add("★ über ${shortName(v)}") }
                if (first.from.t != null && first.from.rt != null && delayMin(first.from.t, first.from.rt) != 0) add("Plan ${hhmm(first.from.t)}")
            }
            Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LegBar(it.legs, start, end)
            if (cancelled) Text("Eine Fahrt fällt aus – Verbindung nicht möglich", color = Palette.bad, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            else if (noRt) Text("Teilweise ohne Echtzeit", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            AnimatedVisibility(expanded, enter = androidx.compose.animation.expandVertically(Anim.size(220)) + androidx.compose.animation.fadeIn(Anim.spec(220)),
                exit = androidx.compose.animation.shrinkVertically(Anim.size(200)) + androidx.compose.animation.fadeOut(Anim.spec(200))) {
                Column(Modifier.padding(top = 10.dp)) {
                    for (l in it.legs) LegDetail(l, nav)
                    val guide = TaktApp.instance.guide
                    val active by guide.active.collectAsState()
                    val isThis = active != null && guide.isActive(it)
                    val startGuide = rememberGuideStarter()
                    Spacer(Modifier.height(10.dp))
                    PillButton("Auf Karte zeigen", Icons.Rounded.Map, primary = false, onClick = { MapRoute.itinerary = it; nav.showOnMap() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (isThis) PillButton("Begleitung beenden", Icons.Rounded.NotificationsOff, primary = false, onClick = { guide.stop() }, modifier = Modifier.fillMaxWidth())
                    else PillButton(if (active != null) "Stattdessen diese begleiten" else "Fahrt begleiten", Icons.Rounded.NotificationsActive, primary = true,
                        onClick = { startGuide(it) }, modifier = Modifier.fillMaxWidth())
                    Text("Weckt dich kurz vor jedem Ausstieg, auch bei gesperrtem Handy.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
            }
        }
    }
}

/** Legs as colored segments proportional to their duration, with line labels. */
@Composable
private fun LegBar(legs: List<Leg>, start: Long, end: Long) {
    val total = (end - start).coerceAtLeast(60).toFloat()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (l in legs) {
            val w = ((legEnd(l) - legStart(l)).coerceAtLeast(30) / total).coerceAtLeast(0.06f)
            if (l.mode == "walk") {
                Box(Modifier.weight(w).height(4.dp).padding(horizontal = 1.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            } else {
                Box(Modifier.weight(w).padding(horizontal = 1.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(8.dp)).background((parseColor(l.color) ?: modeColor(l.mode)).copy(alpha = if (l.cancelled) 0.35f else 1f)))
                    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(modeIcon(l.mode), null, tint = Color.White, modifier = Modifier.size(14.dp))
                        // narrow segments: icon only, so the line number is never cut
                        if (w >= 0.16f) {
                            Spacer(Modifier.width(3.dp))
                            Text(l.line, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWithDelay(t: Long?, rt: Long?, cancelled: Boolean) {
    Column(Modifier.width(58.dp)) {
        Text(hhmm(rt ?: t), style = MaterialTheme.typography.titleSmall.merge(Tnum), textDecoration = if (cancelled) TextDecoration.LineThrough else null)
        if (t != null && (rt != null || cancelled)) DelayChip(t, rt, cancelled, rt != null)
    }
}

@Composable
private fun LegDetail(l: Leg, nav: Nav) {
    if (l.mode == "walk") {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(58.dp))
            Icon(modeIcon("walk"), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("${durationText(l.duration)} Fußweg" + (l.distance?.let { " · ${distText(it.toInt())}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val c = parseColor(l.color) ?: modeColor(l.mode)
    val trackWord = if (l.mode == "sbahn" || l.mode == "train") "Gl." else "Steig"
    Column(Modifier.fillMaxWidth().clickableRow { l.trip?.let { nav.trip(it, l.line) } }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TimeWithDelay(l.from.t, l.from.rt, l.cancelled)
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(10.dp))
            Text(placeName(l.from.name, true), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            (l.from.track ?: l.from.plannedTrack)?.let { Text("$trackWord $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.padding(start = 58.dp, top = 4.dp, bottom = 4.dp)) {
            Box(Modifier.padding(start = 3.5.dp).width(3.dp).height(44.dp).background(c))
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(l.line, l.mode, l.color, l.textColor, small = true)
                    Spacer(Modifier.width(6.dp))
                    Text(l.dir, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val info = buildList {
                    add("${l.stops + 1} Halt${if (l.stops == 0) "" else "e"} · ${durationText(l.duration)}")
                    if (!l.realtime) add("keine Echtzeit")
                    if (l.src == "insa") add("INSA, Ankunft geschätzt")
                    addAll(l.info)
                }
                Text(info.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (n in l.notes.take(2)) Text(n, style = MaterialTheme.typography.labelSmall, color = Palette.warn)
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            TimeWithDelay(l.to.t, l.to.rt, l.cancelled)
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(10.dp))
            Text(placeName(l.to.name, false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            (l.to.track ?: l.to.plannedTrack)?.let { Text("$trackWord $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Full-screen picker: current position, favourites, nearby stops, stop search, addresses. */
@Composable
fun PlacePicker(title: String, onDismiss: () -> Unit, onPick: (SavedPlace) -> Unit) {
    val app = TaktApp.instance
    var q by remember { mutableStateOf("") }
    var geo by remember { mutableStateOf<List<GeoMatch>>(emptyList()) }
    val favIds by app.prefs.favStations.collectAsState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(q) {
        geo = emptyList()
        if (q.trim().length < 3) return@LaunchedEffect
        delay(450)
        geo = runCatching { app.repo.geocode(q.trim()) }.getOrDefault(emptyList()).filter { it.type != "STOP" }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                SearchField(q, { q = it }, "Haltestelle oder Adresse", fieldModifier = Modifier.focusRequester(focus))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (q.isBlank()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            PickRow(Icons.Rounded.MyLocation, "Aktueller Standort", "nächste Haltestellen automatisch", MaterialTheme.colorScheme.primary) {
                                scope.launch {
                                    refreshPosition()
                                    Position.loc?.let { onPick(SavedPlace("Mein Standort", null, it.latitude, it.longitude)) }
                                }
                            }
                        }
                        val favs = favIds.mapNotNull { app.stations.get(it) }
                        if (favs.isNotEmpty()) {
                            item { SectionTitle("Favoriten") }
                            items(favs, key = { "f" + it.id }) { s -> PickRow(Icons.Rounded.Star, s.shortName, null, Palette.warn) { onPick(SavedPlace(s.name, s.id)) } }
                        }
                        val l = Position.loc
                        if (l != null) {
                            item { SectionTitle("In der Nähe") }
                            items(app.stations.nearby(l.latitude, l.longitude, 8), key = { "n" + it.station.id }) { n ->
                                val m = stationModes(n.station.modes).firstOrNull() ?: "bus"
                                PickRow(modeIcon(m), n.station.shortName, distText(n.meters), modeColor(m)) { onPick(SavedPlace(n.station.name, n.station.id)) }
                            }
                        }
                    } else {
                        items(app.stations.search(q, 15), key = { "s" + it.id }) { s ->
                            val m = stationModes(s.modes).firstOrNull() ?: "bus"
                            PickRow(modeIcon(m), s.shortName, null, modeColor(m)) { onPick(SavedPlace(s.name, s.id)) }
                        }
                        if (geo.isNotEmpty()) {
                            item { SectionTitle("Adressen und Orte") }
                            items(geo, key = { "g${it.lat},${it.lon},${it.name}" }) { g ->
                                PickRow(Icons.Rounded.Home, g.name, g.area.ifBlank { null }, MaterialTheme.colorScheme.onSurfaceVariant) {
                                    onPick(SavedPlace(g.name + if (g.area.isNotBlank()) ", ${g.area}" else "", g.station, g.lat, g.lon))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, title: String, sub: String?, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

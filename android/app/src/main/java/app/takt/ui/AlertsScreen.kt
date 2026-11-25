package app.takt.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.AlertsResponse
import app.takt.data.Stations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private object AlertCache { val live = Live<AlertsResponse>() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(@Suppress("UNUSED_PARAMETER") nav: Nav) {
    val app = TaktApp.instance
    val live = AlertCache.live
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val load: () -> Unit = {
        scope.launch {
            live.loading = true
            try { live.data = app.repo.alerts(); live.error = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { live.error = e.message }
            finally { live.loading = false }
        }
    }
    LaunchedEffect(Unit) {
        val d = live.data
        if (d == null || d.ageSeconds() > 120) load()
    }
    PullToRefreshBox(isRefreshing = live.loading && live.data != null, onRefresh = load, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        val nq = Stations.norm(q)
        val picked by app.prefs.alertLines.collectAsState()
        val all = live.data?.value?.alerts.orEmpty()
        // every line that has a message, trams first, then S-Bahn, buses, the rest; numbers in numeric order
        val lines = all.flatMap { it.lines }.distinct().sortedWith(compareBy<String>({ lineRank(it) }, { lineLabel(it).filter(Char::isDigit).toIntOrNull() ?: 9999 }, { it }))
        val list = all.filter { a ->
            (picked.isEmpty() || a.lines.any { it in picked }) &&
                (nq.isBlank() || Stations.norm("${a.title} ${a.text} ${a.company} ${a.lines.joinToString(" ")} ${a.stops.joinToString(" ")}").contains(nq))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 8.dp)) {
            item { ScreenTitle("Störungen") { SettingsButton(nav) } }
            item { Row(Modifier.padding(horizontal = 20.dp)) { AgeChip(live.data, live.loading, live.error) } }
            item { ErrorLine(live.error) }
            item { SearchField(q, { q = it }, "Linie, Haltestelle, Stichwort") }
            if (lines.isNotEmpty() || picked.isNotEmpty()) item {
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val label = if (picked.isEmpty()) "Alle Linien" else "Linien: " + picked.map(::lineLabel).sorted().joinToString(", ")
                    androidx.compose.material3.FilterChip(
                        selected = picked.isNotEmpty(), onClick = { menu = true },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDropDown, null) },
                    )
                    // stays open while ticking lines; tap outside or back closes it
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 420.dp)) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Alle Linien") },
                            leadingIcon = { androidx.compose.material3.Checkbox(picked.isEmpty(), null) },
                            onClick = { app.prefs.setAlertLines(emptySet()) },
                        )
                        androidx.compose.material3.HorizontalDivider()
                        // picked lines without a message right now stay in the list, so they can be unticked
                        for (l in picked.filter { it !in lines }.sorted() + lines) androidx.compose.material3.DropdownMenuItem(
                            text = { Text(lineLabel(l)) },
                            leadingIcon = { androidx.compose.material3.Checkbox(l in picked, null) },
                            trailingIcon = { androidx.compose.material3.Icon(modeIcon(lineMode(l)), null, modifier = Modifier.size(18.dp)) },
                            onClick = { app.prefs.setAlertLines(if (l in picked) picked - l else picked + l) },
                        )
                    }
                }
            }
            if (list.isEmpty() && live.data != null) item { Text("Keine Meldungen.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id }) { a ->
                val accent = if (a.products.any { it == "tram" || it == "suburban" || it == "regional" }) Palette.tram else Palette.warn
                Surface(
                    onClick = { open = if (open == a.id) null else a.id },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).animateContentSize(Anim.size(200)),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
                        Column(Modifier.padding(14.dp)) {
                            if (a.lines.isNotEmpty()) Row {
                                for (l in a.lines.take(6)) {
                                    LineBadge(lineLabel(l), lineMode(l), small = true)
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                            if (a.lines.isNotEmpty()) Spacer(Modifier.height(6.dp))
                            Text(a.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(a.company.substringAfter(" - ", a.company), validity(a.validFrom, a.validUntil)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (open == a.id) {
                                Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                                if (a.stops.isNotEmpty()) Text("Betrifft: ${a.stops.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                Text(a.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            item { Text("Quelle: INSA / Nahverkehr Sachsen-Anhalt (inoffiziell)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        }
    }
}

private fun lineMode(l: String) = when { l.startsWith("Bus") -> "bus"; l.startsWith("Str") -> "tram"; l.startsWith("S") -> "sbahn"; else -> "train" }
private fun lineLabel(l: String) = l.removePrefix("Bus ").removePrefix("Str ")
private fun lineRank(l: String) = when (lineMode(l)) { "tram" -> 0; "sbahn" -> 1; "bus" -> 2; else -> 3 }

private fun validity(from: String?, until: String?): String {
    fun d(s: String?) = s?.take(10)?.split('-')?.let { if (it.size == 3) "${it[2]}.${it[1]}.${it[0].takeLast(2)}" else null }
    val f = d(from); val u = d(until)
    return when {
        f != null && u != null -> "$f – $u"
        u != null -> "bis $u"
        f != null -> "seit $f"
        else -> ""
    }
}

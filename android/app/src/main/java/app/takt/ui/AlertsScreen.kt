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
        val list = live.data?.value?.alerts.orEmpty().filter { a ->
            nq.isBlank() || Stations.norm("${a.title} ${a.text} ${a.company} ${a.lines.joinToString(" ")} ${a.stops.joinToString(" ")}").contains(nq)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 8.dp)) {
            item { ScreenTitle("Störungen") }
            item { Row(Modifier.padding(horizontal = 20.dp)) { AgeChip(live.data, live.loading, live.error) } }
            item { ErrorLine(live.error) }
            item { SearchField(q, { q = it }, "Linie, Haltestelle, Stichwort") }
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
                                    val mode = when { l.startsWith("Bus") -> "bus"; l.startsWith("Str") -> "tram"; l.startsWith("S") -> "sbahn"; else -> "train" }
                                    LineBadge(l.removePrefix("Bus ").removePrefix("Str "), mode, small = true)
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

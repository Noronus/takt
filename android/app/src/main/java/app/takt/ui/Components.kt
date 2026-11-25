package app.takt.ui

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takt.data.Dep
import app.takt.data.Modes
import app.takt.data.Timed
import app.takt.data.ageText
import app.takt.data.delayMin
import app.takt.data.hhmm
import kotlinx.coroutines.delay

/** Space the floating bottom bar needs below scrollable content. */
val LocalBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** Current time, ticking every second while composed. */
@Composable
fun nowMs(): Long {
    val v by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    return v
}

object Palette {
    val ok = Color(0xFF2FBF71)
    val warn = Color(0xFFF2A516)
    val bad = Color(0xFFF0484B)
    val tram = Color(0xFFE5243B)
    val bus = Color(0xFF8E4FD1)
    val sbahn = Color(0xFF17A35B)
    val train = Color(0xFF64748B)
    val other = Color(0xFF475569)
}

fun modeColor(mode: String): Color = when (mode) {
    "tram" -> Palette.tram
    "bus" -> Palette.bus
    "sbahn" -> Palette.sbahn
    "train" -> Palette.train
    "subway" -> Color(0xFF2563EB)
    "ferry" -> Color(0xFF0891B2)
    "walk" -> Color(0xFF94A3B8)
    else -> Palette.other
}

fun modeIcon(mode: String): ImageVector = when (mode) {
    "tram" -> Icons.Filled.Tram
    "bus" -> Icons.Filled.DirectionsBus
    "sbahn" -> Icons.Filled.DirectionsRailway
    "train" -> Icons.Filled.Train
    "subway" -> Icons.Filled.Subway
    "ferry" -> Icons.Filled.DirectionsBoat
    "walk" -> Icons.Filled.DirectionsWalk
    else -> Icons.Filled.DirectionsBus
}

/** Mode flags of a station as list of app modes (for icons). */
fun stationModes(m: Int): List<String> = buildList {
    if (m and Modes.TRAM != 0) add("tram")
    if (m and Modes.SUBURBAN != 0) add("sbahn")
    if (m and Modes.RAIL != 0) add("train")
    if (m and Modes.BUS != 0) add("bus")
    if (m and Modes.SUBWAY != 0) add("subway")
    if (m and Modes.FERRY != 0) add("ferry")
}

fun parseColor(hex: String?): Color? = runCatching { hex?.let { Color(android.graphics.Color.parseColor(it)) } }.getOrNull()

@Composable
fun LineBadge(line: String, mode: String, color: String? = null, textColor: String? = null, modifier: Modifier = Modifier, small: Boolean = false) {
    val bg = parseColor(color)?.takeIf { it != Color.White && it != Color.Black } ?: modeColor(mode)
    val fg = parseColor(textColor)?.takeIf { color != null } ?: Color.White
    Box(
        modifier
            .widthIn(min = if (small) 30.dp else 42.dp)
            .clip(RoundedCornerShape(if (small) 7.dp else 10.dp))
            .background(bg)
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 2.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(line.ifBlank { "?" }, color = fg, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 12.sp else 15.sp, maxLines = 1)
    }
}

/** Small round mode icons, e.g. for a station. */
@Composable
fun ModeDots(modes: Int, size: Dp = 22.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in stationModes(modes)) Box(
            Modifier.size(size).clip(CircleShape).background(modeColor(m).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(modeIcon(m), null, tint = modeColor(m), modifier = Modifier.size(size * 0.62f)) }
    }
}

/**
 * Always-visible data age: how old the realtime data is, ticking every second.
 * Green < 90 s, amber < 5 min, red otherwise.
 */
@Composable
/** Age of the shown data. [compact]: only dot and age ("42 s"), for the title bar. */
fun AgeChip(data: Timed<*>?, loading: Boolean, error: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val now = nowMs()
    val (text, color) = when {
        data == null && loading -> "lädt …" to MaterialTheme.colorScheme.outline
        data == null -> (if (compact) "keine" else "keine Daten") to Palette.bad
        else -> {
            val age = data.ageSeconds(now)
            val c = when { age < 90 -> Palette.ok; age < 300 -> Palette.warn; else -> Palette.bad }
            val offline = data.fromCache || error != null
            if (compact) (if (offline) "offline · " else "") + ageText(age) to c
            else "${if (offline) "offline · " else ""}Daten ${ageText(age)} alt" to c
        }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (loading) MaterialTheme.colorScheme.primary else color))
        Spacer(Modifier.width(6.dp))
        Text(
            if (loading && data != null && !compact) "$text · lädt" else text,
            style = MaterialTheme.typography.labelMedium.merge(Tnum), color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun ErrorLine(error: String?, modifier: Modifier = Modifier) {
    if (error == null) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small).background(Palette.bad.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.bad, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DelayChip(planned: Long, real: Long?, cancelled: Boolean, hasRt: Boolean) {
    val (txt, c) = when {
        cancelled -> "fällt aus" to Palette.bad
        !hasRt -> "Plan" to MaterialTheme.colorScheme.outline
        else -> {
            val d = delayMin(planned, real) ?: 0
            (if (d > 0) "+$d" else if (d < 0) "$d" else "pünktlich") to when { d <= 1 -> Palette.ok; d <= 5 -> Palette.warn; else -> Palette.bad }
        }
    }
    Text(txt, color = c, style = MaterialTheme.typography.labelMedium.merge(Tnum))
}

/** Big countdown: "weg" (gone), "jetzt", "4 min", or a clock time for later departures. */
@Composable
fun Countdown(epochS: Long, nowS: Long, cancelled: Boolean) {
    val m = Math.floorDiv(epochS - nowS, 60L)
    val strike = if (cancelled) TextDecoration.LineThrough else null
    val color = if (cancelled) Palette.bad else MaterialTheme.colorScheme.onSurface
    when {
        m < -1 -> Text("weg", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline, textDecoration = strike)
        m <= 0 -> Text("jetzt", style = MaterialTheme.typography.titleLarge, color = if (cancelled) color else MaterialTheme.colorScheme.primary, textDecoration = strike)
        m < 60 -> Text(buildAnnotatedString {
            append("$m")
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) { append(" min") }
        }, style = MaterialTheme.typography.titleLarge.merge(Tnum), color = color, textDecoration = strike)
        else -> Text(hhmm(epochS), style = MaterialTheme.typography.titleLarge.merge(Tnum), color = color, textDecoration = strike)
    }
}

@Composable
fun DepartureRow(d: Dep, nowS: Long, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val real = d.rt ?: d.t
    val clickMod = if (onClick != null) Modifier.clickableRow(onClick) else Modifier
    Row(
        modifier.then(clickMod).fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(d.line, d.mode, d.color, d.textColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                d.dir, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                textDecoration = if (d.cancelled) TextDecoration.LineThrough else null,
            )
            val track = d.track ?: d.plannedTrack
            val sub = buildList {
                add(hhmm(d.t))
                val word = if (d.mode == "sbahn" || d.mode == "train") "Gleis" else "Steig"
                if (track != null) add(if (d.plannedTrack != null && d.track != null && d.track != d.plannedTrack) "$word $track statt ${d.plannedTrack}" else "$word $track")
                if (d.src == "insa") add("INSA")
                addAll(d.info)
            }
            Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            for (n in d.notes.take(2)) Text(n, style = MaterialTheme.typography.bodySmall, color = Palette.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Countdown(real, nowS, d.cancelled)
            DelayChip(d.t, d.rt, d.cancelled, d.src != null)
        }
    }
}

/** Rounded container for a group of rows. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

@Composable
fun RowDivider(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(t.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun ScreenTitle(t: String, modifier: Modifier = Modifier, age: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    // own background: the bar stays on top while the list scrolls under it
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (age != null) { age(); Spacer(Modifier.width(4.dp)) }
        actions()
    }
}

/** Pill-shaped search field without outline. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, fieldModifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp).height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = fieldModifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Leeren") }
    }
}

/** Two-line From/To card used on the map and the journey screen. */
@Composable
fun RouteCard(
    from: String?, to: String?, fromHint: String, toHint: String,
    onFrom: () -> Unit, onTo: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {},
    onClear: (() -> Unit)? = null,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                RouteLine(true, from, fromHint, onFrom)
                HorizontalDivider(Modifier.padding(start = 44.dp, end = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                RouteLine(false, to, toHint, onTo)
            }
            trailing()
            if (onClear != null && (from != null || to != null)) {
                androidx.compose.material3.IconButton(onClick = onClear) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Felder leeren", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RouteLine(start: Boolean, value: String?, hint: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (start) Box(Modifier.size(12.dp).clip(CircleShape).border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
        else Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface))
        Spacer(Modifier.width(16.dp))
        Text(
            value ?: hint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (value != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Primary or tonal pill button. */
@Composable
fun PillButton(text: String, icon: ImageVector?, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50), modifier = modifier,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Round floating icon button for map overlays. */
@Composable
fun RoundButton(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.size(48.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

fun shortName(n: String) = n.removePrefix("Leipzig, ").removeSuffix(", Leipzig")

/** Name of a leg end; coordinate places (no name from the server) get the user's own label. */
fun placeName(n: String, first: Boolean): String = when {
    n.isNotBlank() && n != "START" && n != "END" -> shortName(n)
    first -> JourneyState.from?.name?.let(::shortName) ?: "Start"
    else -> JourneyState.to?.name?.let(::shortName) ?: "Ziel"
}

/** Drops departures that already left (matters when showing cached data). */
/** Departures still to come, plus those that left within the "vergangene Fahrten" setting. */
fun upcoming(deps: List<Dep>, nowS: Long): List<Dep> {
    val keep = maxOf(30L, app.takt.TaktApp.instance.prefs.pastMin * 60L)
    return deps.filter { (it.rt ?: it.t) >= nowS - keep }
}

/** Settings gear, top right on every main screen. */
@Composable
fun SettingsButton(nav: Nav) {
    androidx.compose.material3.IconButton(onClick = nav::settings) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Settings, "Einstellungen")
    }
}

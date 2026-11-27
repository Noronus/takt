package app.takt.ui

import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takt.BuildConfig
import app.takt.TaktApp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val app = TaktApp.instance
    var server by remember { mutableStateOf(app.prefs.server) }
    var token by remember { mutableStateOf(app.prefs.token) }
    var status by remember { mutableStateOf<String?>(null) }
    val stations by app.stations.list.collectAsState()
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Server", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(server, { server = it }, label = { Text("Adresse, z. B. https://takt.example.de") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Zugangs-Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    app.prefs.server = server; app.prefs.token = token
                    status = "prüfe …"
                    scope.launch {
                        status = try {
                            val (body, _) = app.api.get("/api/v1/status", attempts = 1)
                            val j = app.api.json.parseToJsonElement(body!!).jsonObject
                            val rt = j["rt"] as? JsonObject
                            "Verbunden. Haltestellen: ${j["stations"]}, MOTIS: ${j["motis"]?.jsonPrimitive?.content}, " +
                                "Echtzeit-Fahrten: ${rt?.get("tripUpdates")}"
                        } catch (e: Exception) { "Fehler: ${e.message}" }
                        app.stations.refresh()
                    }
                }) { Text("Speichern & testen") }
            }
            status?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Text("${stations.size} Haltestellen offline gespeichert (Stand ${app.stations.version.ifBlank { "–" }})", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.height(24.dp))
            DataSources()

            Spacer(Modifier.height(24.dp))
            Text("Vergangene Fahrten", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            var pastText by remember { mutableStateOf(app.prefs.pastMin.toString()) }
            OutlinedTextField(
                pastText,
                { v ->
                    // digits only, at most 120 min; an empty field counts as 0
                    val d = v.filter(Char::isDigit).take(3)
                    pastText = d
                    app.prefs.changePastMin((d.toIntOrNull() ?: 0).coerceIn(0, 120))
                },
                label = { Text("Minuten") }, suffix = { Text("min") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.padding(top = 8.dp).width(160.dp),
            )
            Text("So lange bleiben abgefahrene Fahrten in den Abfahrtslisten stehen. 0 = aus, höchstens 120.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))
            Text("Steige auf der Karte", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = !app.prefs.platCode, onClick = { app.prefs.changePlatCode(false) }, label = { Text("Nächste Linie") })
                androidx.compose.material3.FilterChip(selected = app.prefs.platCode, onClick = { app.prefs.changePlatCode(true) }, label = { Text("Steignummer") })
            }
            Text("Was in den einzelnen Steig-Punkten steht, wenn man nah heranzoomt. Haltestellen-Punkte zeigen immer die nächste Linie.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))
            Text("Bevorzugte Umstiege", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            val preferred by app.prefs.preferred.collectAsState()
            var pickPreferred by remember { mutableStateOf(false) }
            for (p in preferred) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(shortName(p.name), modifier = Modifier.weight(1f))
                IconButton(onClick = { app.prefs.setPreferred(preferred - p) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Entfernen") }
            }
            if (preferred.size < 3) OutlinedButton(onClick = { pickPreferred = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Haltestelle hinzufügen") }
            Text("Höchstens so viel länger unterwegs:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for (v in listOf(2, 5, 10, 15)) androidx.compose.material3.FilterChip(
                    selected = app.prefs.preferSlack == v, onClick = { app.prefs.changePreferSlack(v) }, label = { Text("$v min") },
                )
            }
            Text("Verbindungen mit Umstieg an diesen Haltestellen werden vorgezogen, solange sie nicht mehr als die gewählte Zeit kosten. Direkte Verbindungen bleiben immer.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            if (pickPreferred) PlacePicker("Bevorzugter Umstieg", onDismiss = { pickPreferred = false }, record = false) { p ->
                pickPreferred = false
                if (p.station != null && preferred.none { it.station == p.station }) app.prefs.setPreferred(preferred + p)
                else if (p.station == null) status = "Bitte eine Haltestelle wählen, keine Adresse."
            }

            Spacer(Modifier.height(24.dp))
            Text("Animationen", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for ((v, label) in Anim.options) androidx.compose.material3.FilterChip(
                    selected = Anim.scale == v, onClick = { Anim.set(v) }, label = { Text(label) },
                )
            }
            Text("Gilt für Panels, Listen und Kamerafahrten der Karte.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))
            Text("Kartenspeicher", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            var cacheMsg by remember { mutableStateOf<String?>(null) }
            var confirmClear by remember { mutableStateOf(false) }
            val ctx0 = androidx.compose.ui.platform.LocalContext.current
            // MapLibre keeps tiles in one SQLite file; its size is what the phone really spends
            var cacheBytes by remember { mutableStateOf<Long?>(null) }
            fun measure() {
                val dir = java.io.File(org.maplibre.android.storage.FileSource.getResourcesCachePath(ctx0))
                cacheBytes = (dir.listFiles { f -> f.name.startsWith("mbgl-offline.db") }?.sumOf { it.length() } ?: 0L)
            }
            LaunchedEffect(Unit) { measure() }
            val used = cacheBytes?.let { sizeText(it) } ?: "…"
            Text("Luftbilder und Kartenkacheln bleiben bis 5 GB auf dem Handy und werden nur einmal geladen.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 6.dp)) { Text("Kartenspeicher leeren ($used)") }
            cacheMsg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (confirmClear) androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Kartenspeicher leeren?") },
                text = { Text("$used werden gelöscht. Luftbilder und Kartenkacheln werden beim nächsten Ansehen neu geladen.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmClear = false
                        cacheMsg = "wird geleert …"
                        val om = org.maplibre.android.offline.OfflineManager.getInstance(ctx0)
                        om.clearAmbientCache(object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                            override fun onSuccess() {
                                // deleting rows does not shrink the file; compact it so the space is really free
                                om.packDatabase(object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                                    override fun onSuccess() { measure(); cacheMsg = "Kartenspeicher geleert" }
                                    override fun onError(message: String) { measure(); cacheMsg = "Geleert, Verkleinern fehlgeschlagen: $message" }
                                })
                            }
                            override fun onError(message: String) { cacheMsg = "Fehler: $message" }
                        })
                    }) { Text("Leeren") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
            )

            Spacer(Modifier.height(24.dp))
            Text("Sichern", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            var withToken by remember { mutableStateOf(false) }
            var backupMsg by remember { mutableStateOf<String?>(null) }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) backupMsg = runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(app.prefs.exportJson(withToken).toByteArray()) }
                    "Exportiert" + if (withToken) " (mit Token – Datei privat halten)" else ""
                }.getOrElse { "Export fehlgeschlagen: ${it.message}" }
            }
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) backupMsg = runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    app.prefs.importJson(text).also { server = app.prefs.server; token = app.prefs.token; Anim.set(app.prefs.animScale) }
                }.getOrElse { "Import fehlgeschlagen: ${it.message}" }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(withToken, { withToken = it })
                Text("Token mit exportieren", fontSize = 14.sp)
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("takt-einstellungen.json") }) { Text("Exportieren") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Importieren") }
            }
            Text("Favoriten, gemerkte Routen, bevorzugte Umstiege und Einstellungen. Beim Import werden Favoriten ergänzt.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            backupMsg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }

            Spacer(Modifier.height(24.dp))
            Text("Info", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Takt ${BuildConfig.VERSION_NAME}\n\n" +
                    "Fahrplan und Echtzeit: DELFI e.V. über gtfs.de (CC BY 4.0, Echtzeit CC BY-SA 4.0).\n" +
                    "S-Bahn-/Regionalzug-Echtzeit und Störungsmeldungen: INSA / Nahverkehr Sachsen-Anhalt (inoffizielle Schnittstelle).\n" +
                    "Verbindungssuche: MOTIS (motis-project.de).\n" +
                    "Karte: © OpenStreetMap-Mitwirkende (ODbL), Kacheln OpenFreeMap, MapLibre.\n" +
                    "Luftbilder: © GeoSN, © GeoBasis-DE/LVermGeo LSA (dl-de/by-2-0), © GDI-Th (CC BY 4.0).\n\n" +
                    "Ohne Google-Dienste. Standort bleibt auf dem Gerät.",
                fontSize = 13.sp,
            )
        }
    }
}

/** Which sources the server uses right now and how fresh each is; refreshed every 10 s, ages tick every second. */
@Composable
private fun DataSources() {
    val repo = TaktApp.instance.repo
    var list by remember { mutableStateOf<app.takt.data.SourcesResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // server clock minus phone clock, so ages are right even if the phone's clock is off
    var skew by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val r = repo.sources()
                list = r; error = null; skew = r.now - System.currentTimeMillis() / 1000
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
            repeat(10) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(1_000) }
        }
    }
    Text("Datenquellen", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    val l = list
    when {
        l == null && error != null -> Text("Nicht abrufbar: $error", fontSize = 13.sp, color = Palette.bad, modifier = Modifier.padding(top = 4.dp))
        l == null -> Text("lädt …", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        else -> for (s in l.sources) {
            val serverNow = now + skew
            val age = s.updated?.let { (serverNow - it).coerceAtLeast(0) }
            val fetchedAge = s.fetched?.let { (serverNow - it).coerceAtLeast(0) }
            // the timetable is days old by design: only realtime sources get the traffic-light colours
            val realtime = age == null || age < 86_400
            val color = when {
                s.error != null || !s.active -> Palette.bad
                !realtime -> Palette.ok
                (age ?: fetchedAge ?: Long.MAX_VALUE) < 90 -> Palette.ok
                (age ?: fetchedAge ?: Long.MAX_VALUE) < 300 -> Palette.warn
                else -> Palette.bad
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(top = 5.dp, end = 10.dp).size(10.dp)
                        .background(color, androidx.compose.foundation.shape.CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(s.name + if (s.active) "" else " (inaktiv)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (s.role.isNotBlank()) Text(s.role, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    val times = buildList {
                        if (s.updated != null) add(if (realtime) "Daten ${app.takt.data.ageText(age!!)} alt" else "Stand ${app.takt.data.lastUsedText(s.updated * 1000)}")
                        if (fetchedAge != null && realtime) add("abgerufen vor ${app.takt.data.ageText(fetchedAge)}")
                        else if (s.fetched != null && !realtime) add("geprüft ${app.takt.data.lastUsedText(s.fetched * 1000)}")
                        if (s.updated == null && s.fetched == null) add("noch nie abgerufen")
                    }
                    Text(times.joinToString(" · "), fontSize = 13.sp, color = color)
                    s.info?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
                    s.error?.let { Text("Fehler: $it", fontSize = 12.sp, color = Palette.bad) }
                }
            }
        }
    }
    if (l != null && error != null) Text("Letzte Aktualisierung fehlgeschlagen: $error", fontSize = 12.sp, color = Palette.warn, modifier = Modifier.padding(top = 6.dp))
}

private fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%d MB".format(b shr 20)
    else -> "%d KB".format(b shr 10)
}

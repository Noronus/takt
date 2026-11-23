package app.takt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
            OutlinedTextField(server, { server = it }, label = { Text("Adresse, z. B. http://10.x.x.x:8097") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("API-Token (optional)") }, singleLine = true,
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
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { scope.launch { status = if (app.stations.refresh()) "Haltestellenliste aktualisiert" else "Liste ist aktuell (oder Server nicht erreichbar)" } }) {
                    Text("Haltestellen laden")
                }
            }
            status?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Text("${stations.size} Haltestellen offline gespeichert (Stand ${app.stations.version.ifBlank { "–" }})", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.height(24.dp))
            Text("Animationen", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for ((v, label) in Anim.options) androidx.compose.material3.FilterChip(
                    selected = Anim.scale == v, onClick = { Anim.set(v) }, label = { Text(label) },
                )
            }
            Text("Gilt für Panels, Listen und Kamerafahrten der Karte.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))
            Text("Info", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Takt ${BuildConfig.VERSION_NAME}\n\n" +
                    "Fahrplan und Echtzeit: DELFI e.V. über gtfs.de (CC BY 4.0, Echtzeit CC BY-SA 4.0).\n" +
                    "S-Bahn-/Regionalzug-Echtzeit und Störungsmeldungen: INSA / Nahverkehr Sachsen-Anhalt (inoffizielle Schnittstelle).\n" +
                    "Verbindungssuche: MOTIS (motis-project.de).\n" +
                    "Karte: © OpenStreetMap-Mitwirkende (ODbL), Kacheln OpenFreeMap, MapLibre.\n\n" +
                    "Ohne Google-Dienste. Standort bleibt auf dem Gerät.",
                fontSize = 13.sp,
            )
        }
    }
}

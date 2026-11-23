package app.takt.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.takt.data.SavedPlace

/** Journey search fields, shared between screens ("als Start/Ziel" from anywhere). */
object JourneyState {
    var from by mutableStateOf<SavedPlace?>(null)
    var to by mutableStateOf<SavedPlace?>(null)
}

object Tabs { const val MAP = 0; const val NEARBY = 1; const val JOURNEY = 2; const val ALERTS = 3 }

class Nav(private val nc: NavHostController, private val tab: MutableIntState) {
    fun back() { nc.popBackStack() }
    fun station(id: String) = nc.navigate("station/${Uri.encode(id)}")
    fun trip(id: String, line: String) = nc.navigate("trip?id=${Uri.encode(id)}&line=${Uri.encode(line)}")
    fun settings() = nc.navigate("settings")
    fun setStart(p: SavedPlace) { JourneyState.from = p; journey() }
    fun setDest(p: SavedPlace) { JourneyState.to = p; journey() }
    fun journey() { nc.popBackStack("home", false); tab.intValue = Tabs.JOURNEY }
    fun showOnMap() { nc.popBackStack("home", false); tab.intValue = Tabs.MAP }
}

/** Requests from outside (notification tap) to open a certain screen. */
object OpenRequest { var target by mutableStateOf<String?>(null) }

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "app.takt.open"
        const val OPEN_GUIDE = "guide"
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { OpenRequest.target = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_OPEN)?.let { OpenRequest.target = it }
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent { TaktTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val holder = remember { MapHolder(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                Lifecycle.Event.ON_DESTROY -> holder.view.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val nc = rememberNavController()
    val tab = rememberSaveable { mutableIntStateOf(Tabs.MAP) }
    val nav = remember(nc) { Nav(nc, tab) }
    val app = app.takt.TaktApp.instance
    val guided by app.guide.active.collectAsState()
    // a running guide restores its journey on the map and in the journey fields
    androidx.compose.runtime.LaunchedEffect(guided?.itinerary?.id) {
        val g = guided ?: return@LaunchedEffect
        // the service may have been killed (app update, low memory): start it again, it reloads the journey
        app.guide.ensureRunning()
        if (MapRoute.itinerary == null) MapRoute.itinerary = g.itinerary
        val legs = g.itinerary.legs
        if (JourneyState.from == null) legs.firstOrNull()?.from?.let { p -> JourneyState.from = SavedPlace(p.name.ifBlank { "Start" }, p.station, p.lat, p.lon) }
        if (JourneyState.to == null) legs.lastOrNull()?.to?.let { p -> JourneyState.to = SavedPlace(p.name.ifBlank { "Ziel" }, p.station, p.lat, p.lon) }
    }
    androidx.compose.runtime.LaunchedEffect(OpenRequest.target) {
        if (OpenRequest.target == MainActivity.OPEN_GUIDE) nav.journey()
        OpenRequest.target = null
    }
    CompositionLocalProvider(LocalMapHolder provides holder) {
        // Surface sets the content color, so plain Text/Icon get onBackground instead of black
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(nc, startDestination = "home") {
                composable("home") { Home(nav, tab) }
                composable("station/{id}") { StationScreen(it.arguments?.getString("id")!!, nav) }
                composable("trip?id={id}&line={line}") {
                    TripScreen(it.arguments?.getString("id")!!, it.arguments?.getString("line") ?: "", nav)
                }
                composable("settings") { SettingsScreen(nav) }
            }
        }
    }
}

@Composable
private fun Home(nav: Nav, tab: MutableIntState) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // back from another tab goes to the map instead of closing the app
    androidx.activity.compose.BackHandler(enabled = tab.intValue != Tabs.MAP) { tab.intValue = Tabs.MAP }
    CompositionLocalProvider(LocalBottomInset provides navBottom + 88.dp) {
        Box(Modifier.fillMaxSize()) {
            when (tab.intValue) {
                Tabs.MAP -> MapScreen(nav)
                Tabs.NEARBY -> NearbyScreen(nav)
                Tabs.JOURNEY -> JourneyScreen(nav)
                else -> AlertsScreen(nav)
            }
            FloatingNav(tab.intValue, { tab.intValue = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        Triple("Karte", Icons.Rounded.Map, Tabs.MAP),
        Triple("Nähe", Icons.Rounded.NearMe, Tabs.NEARBY),
        Triple("Route", Icons.Rounded.Route, Tabs.JOURNEY),
        Triple("Störungen", Icons.Rounded.WarningAmber, Tabs.ALERTS),
    )
    Surface(
        modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((label, icon, i) in items) {
                val sel = selected == i
                val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, Anim.spec(200), label = "nav")
                val fg = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg).clickableRow { onSelect(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
                }
            }
        }
    }
}

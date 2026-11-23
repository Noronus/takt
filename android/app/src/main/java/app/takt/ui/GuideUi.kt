package app.takt.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.takt.TaktApp
import app.takt.data.Itinerary

/** Returns a function that asks for notification (and location) permission, then starts guiding. */
@Composable
fun rememberGuideStarter(): (Itinerary) -> Unit {
    val app = TaktApp.instance
    // must survive recomposition while the permission dialog is open
    val pending = androidx.compose.runtime.remember { arrayOfNulls<Itinerary>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending[0]?.let { p -> app.guide.start(p) }
        pending[0] = null
    }
    return { it ->
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (!app.loc.hasPermission()) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        }
        MapRoute.itinerary = it
        if (perms.isEmpty()) app.guide.start(it)
        else { pending[0] = it; launcher.launch(perms.toTypedArray()) }
    }
}

/** Small status strip while a journey is being guided. */
@Composable
fun GuideBanner(nav: Nav, modifier: Modifier = Modifier) {
    val app = TaktApp.instance
    val active by app.guide.active.collectAsState()
    val status by app.guide.status.collectAsState()
    if (active == null) return
    Surface(
        onClick = { nav.journey() },
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(status?.title ?: "Fahrt wird begleitet", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { app.guide.stop() }) { Icon(Icons.Rounded.Close, "Begleitung beenden") }
        }
    }
}

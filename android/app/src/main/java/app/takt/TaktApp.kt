package app.takt

import android.app.Application
import kotlinx.coroutines.launch
import app.takt.data.Api
import app.takt.data.Loc
import app.takt.data.Prefs
import app.takt.data.Stations
import app.takt.data.Repo

class TaktApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var api: Api; private set
    lateinit var stations: Stations; private set
    lateinit var loc: Loc; private set
    lateinit var repo: Repo; private set
    lateinit var guide: app.takt.data.Guide; private set

    override fun onCreate() {
        super.onCreate()
        t0 = android.os.SystemClock.uptimeMillis()
        instance = this
        prefs = Prefs(this)
        api = Api(prefs)
        stations = Stations(this, api)
        loc = Loc(this)
        repo = Repo(this, api)
        guide = app.takt.data.Guide(this, api)
        // start loading everything the first screen (map) needs right away
        org.maplibre.android.MapLibre.getInstance(this)
        app.takt.ui.Position.loc = loc.lastKnown()
        scope.launch {
            stations.load()
            mark("stations parsed")
            stations.refresh()
        }
    }

    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    companion object {
        lateinit var instance: TaktApp; private set
        var t0 = 0L
        fun mark(what: String) = android.util.Log.i("TaktT", "${android.os.SystemClock.uptimeMillis() - t0} ms $what")
    }
}

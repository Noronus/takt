package app.takt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.takt.data.GuideStatus
import app.takt.data.Itinerary
import app.takt.data.Leg
import app.takt.data.Timed
import app.takt.data.TripResponse
import app.takt.data.ageText
import app.takt.data.hhmm
import app.takt.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Fahrt begleiten": ongoing notification with the next step of the journey and an alarm
 * shortly before each exit stop. Triggers (whichever comes first):
 *  1. realtime says the vehicle left the stop before the exit stop,
 *  2. GPS is within 500 m of the exit stop,
 *  3. 150 s before the (realtime) arrival at the exit stop.
 * Uses only the Android LocationManager, no Google services.
 */
class GuideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val listeners = mutableListOf<LocationListener>()
    @Volatile private var lastLoc: Location? = null
    private val trips = HashMap<String, Timed<TripResponse>>()
    private val fetchedAt = HashMap<String, Long>()
    private val app get() = TaktApp.instance
    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Fahrtbegleitung", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Laufende Anzeige der begleiteten Fahrt"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DELAY, "Verspätungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Änderungen bei Verspätung, Ausfall und Anschluss der begleiteten Fahrt"
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 150, 250)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Aussteigen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Weckt kurz vor dem Ausstieg"
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 700)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { android.util.Log.i("TaktGuide", "stopped via notification button"); app.guide.stop(); stopSelf(); return START_NOT_STICKY }
        val g = app.guide.active.value
        if (g == null) { stopSelf(); return START_NOT_STICKY }
        val hasLoc = app.loc.hasPermission()
        val type = if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, ID_ONGOING, ongoing("Fahrt wird begleitet", "lädt …", null), type)

        val end = endTime(g.itinerary)
        val pm = getSystemService(PowerManager::class.java)
        wake?.let { if (it.isHeld) it.release() }
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "takt:guide").apply {
            acquire(((end - now() + 15 * 60).coerceIn(60, 6 * 3600)) * 1000L)
        }
        if (hasLoc) startLocation()
        job?.cancel()
        job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopLocation()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- location ----------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        stopLocation()
        val lm = getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val l = LocationListener { lastLoc = it }
            runCatching { lm.requestLocationUpdates(p, 5000L, 15f, l, Looper.getMainLooper()); listeners += l }
        }
    }

    private fun stopLocation() {
        val lm = getSystemService(LocationManager::class.java)
        listeners.forEach { runCatching { lm.removeUpdates(it) } }
        listeners.clear()
    }

    // ---------- journey logic ----------

    private fun now() = System.currentTimeMillis() / 1000

    private fun endTime(it: Itinerary) = it.legs.lastOrNull()?.let { l -> l.to.rt ?: l.to.t } ?: (now() + 3600)

    /** Times of one transit leg, refined with the trip's realtime when available. */
    private inner class LegTimes(val leg: Leg) {
        private val trip = leg.trip?.let { trips[it]?.value }
        private val stops = trip?.stops.orEmpty()
        val board: Int = stops.indexOfBest(leg.from.station, leg.from.name, leg.from.t, after = -1)
        val exit: Int = stops.indexOfBest(leg.to.station, leg.to.name, leg.to.t, after = board)
        val boardDep: Long = stops.getOrNull(board)?.dep?.let { it.rt ?: it.t } ?: leg.from.rt ?: leg.from.t ?: 0
        val exitArr: Long = stops.getOrNull(exit)?.arr?.let { it.rt ?: it.t } ?: leg.to.rt ?: leg.to.t ?: 0
        val exitPlanned: Long = stops.getOrNull(exit)?.arr?.t ?: leg.to.t ?: exitArr
        val boardPlanned: Long = stops.getOrNull(board)?.dep?.t ?: leg.from.t ?: boardDep
        val hasRt: Boolean = trip?.realtime == true || leg.from.rt != null
        val cancelled: Boolean = trip?.cancelled == true || leg.cancelled ||
            stops.getOrNull(board)?.cancelled == true || stops.getOrNull(exit)?.cancelled == true
        /** Departure from the stop before the exit stop. */
        val prevDep: Long? = if (exit > 0 && exit - 1 >= board && board >= 0) stops[exit - 1].dep?.let { it.rt ?: it.t } else null
        val exitLat = stops.getOrNull(exit)?.lat?.takeIf { it != 0.0 } ?: leg.to.lat
        val exitLon = stops.getOrNull(exit)?.lon?.takeIf { it != 0.0 } ?: leg.to.lon
        fun remainingStops(now: Long): Int? {
            if (exit < 0) return null
            return (0..exit).count { i -> i > board && ((stops[i].arr?.let { it.rt ?: it.t }) ?: 0) > now }
        }
        val ageS: Long? = leg.trip?.let { trips[it]?.ageSeconds() }
    }

    private fun List<app.takt.data.TripStop>.indexOfBest(station: String?, name: String, t: Long?, after: Int): Int {
        val range = (after + 1) until size
        range.firstOrNull { station != null && this[it].station == station }?.let { return it }
        range.firstOrNull { this[it].name == name }?.let { return it }
        if (t == null || isEmpty()) return -1
        return range.minByOrNull { i -> kotlin.math.abs(((this[i].arr ?: this[i].dep)?.t ?: Long.MAX_VALUE / 2) - t) } ?: -1
    }

    private suspend fun refresh(trip: String, maxAge: Long) {
        if (now() - (fetchedAt[trip] ?: 0) < maxAge) return
        fetchedAt[trip] = now()
        runCatching { app.repo.trip(trip) }.onSuccess { trips[trip] = it }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val g = app.guide.active.value ?: break
            val legs = g.itinerary.legs
            val transit = legs.indices.filter { legs[it].mode != "walk" && legs[it].trip != null }
            val now = now()
            if (transit.isEmpty() || now > g.startedAt + 6 * 3600) { finish("no transit legs or older than 6 h"); break }

            // current leg: first transit leg we have not yet left
            for (i in transit) legs[i].trip?.let { refresh(it, if (i == transit.firstOrNull { j -> LegTimes(legs[j]).exitArr + 90 > now }) 30 else 60) }
            val cur = transit.firstOrNull { LegTimes(legs[it]).exitArr + 90 > now }
            if (cur == null) { finish("journey completed"); break }
            val t = LegTimes(legs[cur])
            val leg = legs[cur]

            if (cur !in g.alerted && now >= t.boardDep - 30) {
                val loc = lastLoc
                val near = loc != null && FloatArray(1).also { Location.distanceBetween(loc.latitude, loc.longitude, t.exitLat, t.exitLon, it) }[0] < 500f
                val prevLeft = t.prevDep != null && now >= t.prevDep + 10
                val soon = now >= t.exitArr - 150
                if (prevLeft || near || soon) {
                    alert(legs, transit, cur, t)
                    app.guide.save(g.copy(alerted = g.alerted + cur))
                }
            }
            checkChanges(app.guide.active.value ?: g, legs, transit, cur, now)
            showOngoing(leg, t, now)
            delay(10_000)
        }
    }

    private fun trackWord(l: Leg) = if (l.mode == "sbahn" || l.mode == "train") "Gleis" else "Steig"
    private fun delayTxt(planned: Long?, real: Long?): String {
        if (planned == null || real == null) return ""
        val d = Math.round((real - planned) / 60.0).toInt()
        return if (d > 0) " (+$d)" else if (d < 0) " ($d)" else ""
    }
    private fun inMin(t: Long, now: Long): String {
        val m = Math.floorDiv(t - now, 60L)
        return if (m <= 0) "(jetzt)" else "(in $m min)"
    }
    private fun short(n: String) = if (n.isBlank()) "Ziel" else n.removePrefix("Leipzig, ").removeSuffix(", Leipzig")
    private fun ageTxt(t: LegTimes) = t.ageS?.let { " · Daten ${ageText(it)} alt" } ?: ""

    private fun showOngoing(leg: Leg, t: LegTimes, now: Long) {
        val (title, text, `when`) = if (now < t.boardDep) {
            val track = (leg.from.track ?: leg.from.plannedTrack)?.let { " · ${trackWord(leg)} $it" } ?: ""
            Triple("Einsteigen: ${leg.line} → ${leg.dir}",
                "${short(leg.from.name)}$track · ab ${hhmm(t.boardDep)}${delayTxt(leg.from.t, t.boardDep)} ${inMin(t.boardDep, now)}${ageTxt(t)}", t.boardDep)
        } else {
            val left = t.remainingStops(now)?.let { if (it <= 1) " · nächster Halt" else " · noch $it Halte" } ?: ""
            Triple("Aussteigen: ${short(leg.to.name)}",
                "${leg.line} · an ${hhmm(t.exitArr)}${delayTxt(t.exitPlanned, t.exitArr)} ${inMin(t.exitArr, now)}$left${ageTxt(t)}", t.exitArr)
        }
        app.guide.status.value = GuideStatus(title, text)
        nm.notify(ID_ONGOING, ongoing(title, text, `when`))
    }

    private fun alert(legs: List<Leg>, transit: List<Int>, cur: Int, t: LegTimes) {
        val leg = legs[cur]
        val next = transit.firstOrNull { it > cur }?.let { legs[it] }
        val body = if (next != null) {
            val track = (next.from.track ?: next.from.plannedTrack)?.let { " · ${trackWord(next)} $it" } ?: ""
            val dep = next.from.rt ?: next.from.t
            "Dann ${next.line} → ${next.dir}$track · ab ${hhmm(dep)}${delayTxt(next.from.t, dep)}" +
                (if (short(next.from.name) != short(leg.to.name)) " an ${short(next.from.name)}" else "")
        } else "Ziel: ${short(legs.last().to.name)}"
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_tram)
            .setContentTitle("Nächster Halt: ${short(leg.to.name)} – aussteigen")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setWhen(t.exitArr * 1000).setShowWhen(true)
            .build()
        nm.notify(ID_ALERT_BASE + cur, n)
    }

    /**
     * Notifies when a delay appears or changes by >= 2 min, when a trip is cancelled, and when a
     * connection becomes impossible (or possible again). Baseline is the state when guiding started.
     */
    private fun checkChanges(g: app.takt.data.GuidedJourney, legs: List<Leg>, transit: List<Int>, cur: Int, now: Long) {
        var delays = g.delays
        var flags = g.flags
        val first = g.delays.isEmpty() && g.flags.isEmpty() && "init" !in g.flags
        fun mins(real: Long, planned: Long) = Math.round((real - planned) / 60.0).toInt()
        fun changed(key: String, d: Int): Boolean {
            val last = delays[key] ?: 0
            return kotlin.math.abs(d - last) >= 2 || (last >= 2 && d < 2 && d != last)
        }
        for (i in transit.filter { it >= cur }) {
            val l = legs[i]
            val t = LegTimes(l)
            if (t.cancelled && "x$i" !in flags) {
                flags = flags + "x$i"
                if (!first) notifyChange(i, "${l.line} → ${l.dir} fällt aus",
                    "Ab ${short(l.from.name)} ${hhmm(t.boardPlanned)}. Öffne Takt für eine neue Verbindung.")
                continue
            }
            if (!t.hasRt) continue
            if (now < t.boardDep) {
                val d = mins(t.boardDep, t.boardPlanned)
                if (first) delays = delays + ("b$i" to d)
                else if (changed("b$i", d)) {
                    delays = delays + ("b$i" to d)
                    val title = if (d >= 2) "${l.line} → ${l.dir}: jetzt +$d min" else "${l.line} → ${l.dir}: wieder pünktlich"
                    notifyChange(i, title, "Ab ${short(l.from.name)} um ${hhmm(t.boardDep)} statt ${hhmm(t.boardPlanned)}")
                }
            } else if (i == cur) {
                val d = mins(t.exitArr, t.exitPlanned)
                if (first || "a$i" !in delays) delays = delays + ("a$i" to (delays["b$i"] ?: d))
                if (!first && changed("a$i", d)) {
                    delays = delays + ("a$i" to d)
                    val title = if (d >= 2) "Ankunft ${short(l.to.name)}: +$d min" else "Ankunft ${short(l.to.name)}: wieder pünktlich"
                    notifyChange(i, title, "${l.line} kommt um ${hhmm(t.exitArr)} statt ${hhmm(t.exitPlanned)} an")
                }
            }
            // connection to the next transit leg
            val j = transit.firstOrNull { it > i } ?: continue
            val n = LegTimes(legs[j])
            val walk = legs.subList(i + 1, j).sumOf { it.duration }.coerceAtLeast(60)
            val atRisk = t.exitArr + walk > n.boardDep
            if (atRisk && "c$i" !in flags) {
                flags = flags + "c$i"
                if (!first) notifyChange(j, "Anschluss gefährdet: ${legs[j].line} → ${legs[j].dir}",
                    "Ankunft ${hhmm(t.exitArr)} + ${(walk + 59) / 60} min Weg, Abfahrt ${hhmm(n.boardDep)} an ${short(legs[j].from.name)}")
            } else if (!atRisk && "c$i" in flags) {
                flags = flags - "c$i"
                if (!first) notifyChange(j, "Anschluss klappt wieder: ${legs[j].line}",
                    "Abfahrt ${hhmm(n.boardDep)} an ${short(legs[j].from.name)}, Ankunft vorher ${hhmm(t.exitArr)}")
            }
        }
        if (first) flags = flags + "init"
        if (delays != g.delays || flags != g.flags) app.guide.save(g.copy(delays = delays, flags = flags))
    }

    private fun notifyChange(leg: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_DELAY)
            .setSmallIcon(R.drawable.ic_stat_tram)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(ID_DELAY_BASE + leg, n)
    }

    private fun finish(reason: String) {
        android.util.Log.i("TaktGuide", "finished: $reason")
        app.guide.stop()
        stopSelf()
    }

    /** Opens the app on the journey screen with the guided journey. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 2, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_GUIDE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(title: String, text: String, whenS: Long?): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, GuideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_tram)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp())
            .addAction(0, "Beenden", stop)
            .apply {
                // no chronometer: its mm:ss countdown looks like a clock time; the text says "in N min"
                setShowWhen(false)
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.takt.guide.STOP"
        private const val CH_ONGOING = "guide"
        private const val CH_ALERT = "guide_alert"
        private const val CH_DELAY = "guide_delay"
        private const val ID_DELAY_BASE = 200
        private const val ID_ONGOING = 41
        private const val ID_ALERT_BASE = 100
    }
}

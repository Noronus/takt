package app.takt.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun hhmm(epochS: Long?): String = if (epochS == null) "--:--" else hm.format(Instant.ofEpochSecond(epochS))

/** "42 s", "3 min 05 s", "1 h 12 min". */
fun ageText(s: Long): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min %02d s".format(s % 60)
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

/** Countdown for a departure: "weg" (gone), "jetzt", "4 min", or clock time for later. */
fun countdown(epochS: Long, nowS: Long): String {
    val m = Math.floorDiv(epochS - nowS, 60L)
    return when {
        m < -1 -> "weg"
        m <= 0 -> "jetzt"
        m < 60 -> "$m min"
        else -> hhmm(epochS)
    }
}

fun delayMin(planned: Long, real: Long?): Int? = real?.let { Math.round((it - planned) / 60.0).toInt() }

fun durationText(s: Long): String {
    val m = (s + 30) / 60
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

fun distText(m: Int): String = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

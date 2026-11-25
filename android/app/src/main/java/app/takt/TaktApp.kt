package app.takt

import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
        repo = Repo(this, api, prefs)
        guide = app.takt.data.Guide(this, api)
        // start loading everything the first screen (map) needs right away
        org.maplibre.android.MapLibre.getInstance(this)
        setupMapCache()
        app.takt.ui.Position.loc = loc.lastKnown()
        scope.launch {
            stations.load()
            mark("stations parsed")
            stations.refresh()
        }
    }

    /**
     * Aerial images stay on the phone: MapLibre's cache may grow to 5 GB, and the images of the state
     * services (sent without cache headers) count as valid for a year. They are loaded once per tile;
     * a new flight gets a new version in the URL (MapScreen.AERIAL_VERSION) and is loaded again.
     */
    private fun setupMapCache() {
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
            okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val r = chain.proceed(chain.request())
                val fix = AERIAL_COLOR[chain.request().url.host]
                if (!r.isSuccessful || fix == null) return@addNetworkInterceptor r
                // once per tile, on download: correct the colour cast of the flight, store as WebP
                val type = r.body?.contentType()
                val raw = r.body?.bytes() ?: return@addNetworkInterceptor r
                val keep = { body: ByteArray, t: okhttp3.MediaType? ->
                    r.newBuilder().removeHeader("Pragma").removeHeader("Expires").removeHeader("Content-Length")
                        .header("Cache-Control", "public, max-age=31536000").body(okhttp3.ResponseBody.create(t, body)).build()
                }
                // some services gzip the PNG; a network interceptor sees it still packed
                val gz = r.header("Content-Encoding").equals("gzip", ignoreCase = true)
                val out = runCatching { recolor(if (gz) java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw, fix) }.onFailure { if (raw.size > 1000) android.util.Log.w("Takt", "aerial recolor: $it ${chain.request().url.host} ${raw.size} B ${type} head=${raw.take(16).joinToString(" ") { "%02x".format(it) }}") }.getOrNull()
                    ?: return@addNetworkInterceptor when {
                        // empty tile outside that state (tiny PNG Android cannot decode): keep it as it is
                        type?.type == "image" -> keep(raw, type)
                        // a service error page: pass it on, do not keep it
                        else -> r.newBuilder().header("Cache-Control", "no-store").body(okhttp3.ResponseBody.create(type, raw)).build()
                    }
                r.newBuilder().removeHeader("Pragma").removeHeader("Expires").removeHeader("Content-Length").removeHeader("Content-Encoding")
                    .header("Cache-Control", "public, max-age=31536000").header("Content-Type", "image/webp")
                    .body(okhttp3.ResponseBody.create("image/webp".toMediaTypeOrNull(), out)).build()
            }.build()
        )
        org.maplibre.android.offline.OfflineManager.getInstance(this).setMaximumAmbientCacheSize(MAP_CACHE_BYTES,
            object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) { android.util.Log.w("Takt", "map cache size: $message") }
            })
    }

    /** Per channel: gain and offset (0..255), then a gamma curve against haze; then saturation. Transparency stays. */
    private fun recolor(bytes: ByteArray, m: FloatArray): ByteArray {
        val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inPremultiplied = false }
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("no image")
        val gamma = m[6]
        val lut = Array(3) { c -> IntArray(256) { v ->
            val lin = (v * m[c * 2] + m[c * 2 + 1]).coerceIn(0f, 255f) / 255f
            (Math.pow(lin.toDouble(), gamma.toDouble()) * 255 + 0.5).toInt().coerceIn(0, 255)
        } }
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val sat = m[7]
        for (i in px.indices) {
            val p = px[i]
            val r = lut[0][(p shr 16) and 0xFF]; val g = lut[1][(p shr 8) and 0xFF]; val b = lut[2][p and 0xFF]
            // saturation around the luma of the pixel
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val r2 = (l + (r - l) * sat).toInt().coerceIn(0, 255)
            val g2 = (l + (g - l) * sat).toInt().coerceIn(0, 255)
            val b2 = (l + (b - l) * sat).toInt().coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r2 shl 16) or (g2 shl 8) or b2
        }
        // straight (not premultiplied) pixels in and out: no Canvas, it refuses such bitmaps
        val dst = src
        dst.setPixels(px, 0, w, 0, 0, w, h)
        val o = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP
        dst.compress(fmt, 82, o)
        dst.recycle()
        return o.toByteArray()
    }

    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    companion object {
        const val MAP_CACHE_BYTES = 5L * 1024 * 1024 * 1024
        /**
         * Colour correction per aerial service, tuned on sample tiles against summer photos:
         * gain/offset for red, green, blue, then gamma (> 1 darkens the hazy mid tones) and saturation.
         * Sachsen: early-spring flight with a blue-white haze. Sachsen-Anhalt: dark and slightly warm.
         */
        val AERIAL_COLOR = mapOf(
            "geodienste.sachsen.de" to floatArrayOf(1.30f, -38f, 1.20f, -30f, 1.08f, -28f, 1.35f, 1.15f),
            "www.geodatenportal.sachsen-anhalt.de" to floatArrayOf(1.22f, -4f, 1.22f, -4f, 1.32f, -6f, 1.2f, 1.1f),
            "www.geoproxy.geoportal-th.de" to floatArrayOf(1.10f, -10f, 1.10f, -10f, 1.10f, -10f, 1.2f, 1.1f),
        )
        lateinit var instance: TaktApp; private set
        var t0 = 0L
        fun mark(what: String) = android.util.Log.i("TaktT", "${android.os.SystemClock.uptimeMillis() - t0} ms $what")
    }
}

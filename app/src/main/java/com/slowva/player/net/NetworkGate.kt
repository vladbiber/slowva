package com.slowva.player.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.slowva.player.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Everything that may touch the network. Each request carries one as its tag. */
enum class Feature(val label: String, val key: String) {
    STREAMING("Online songs", "streaming"),
    MUSICBRAINZ("Album info (MusicBrainz)", "musicbrainz"),
    VERSION_CHECK("Version check", "version_check"),
    LASTFM("Genres and creators (Last.fm)", "lastfm"),
    LYRICS("Lyrics (LRCLIB)", "lyrics"),
    LISTENBRAINZ("ListenBrainz", "listenbrainz"),
}

/** What a request is for; streams obey "Wi-Fi only", small lookups don't. */
enum class Traffic { LOOKUP, STREAM }

class NetworkBlockedException(val feature: Feature, reason: String) : IOException(reason)

/**
 * The one way out to the internet. Offline mode, per-feature switches and "Wi-Fi only"
 * are enforced here, so no feature can forget them.
 */
object NetworkGate {
    private lateinit var app: Context

    val offline = MutableStateFlow(false)
    val enabled = MutableStateFlow(Feature.entries.associateWith { true })
    val wifiOnlyStreaming = MutableStateFlow(false)

    /** MusicBrainz and LRCLIB ask for a User-Agent that names the app. */
    val userAgent = "Slowva/${BuildConfig.VERSION_NAME} (private build)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(GateInterceptor)
            .build()
    }

    /**
     * Client for ExoPlayer's HTTP data source, which builds its own requests: they are tagged
     * from the X-Slowva-Feature header (default [Feature.STREAMING]) before the gate sees them.
     */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder().apply {
            interceptors().add(0, Interceptor { chain ->
                val req = chain.request()
                val feature = req.header(FEATURE_HEADER)?.let { h -> Feature.entries.firstOrNull { it.key == h } } ?: Feature.STREAMING
                chain.proceed(
                    req.newBuilder().removeHeader(FEATURE_HEADER)
                        .tag(Feature::class.java, feature)
                        .tag(Traffic::class.java, Traffic.STREAM)
                        .build()
                )
            })
        }.build()
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        offline.value = p.getBoolean("offline", false)
        enabled.value = Feature.entries.associateWith { p.getBoolean("feature_${it.key}", true) }
        wifiOnlyStreaming.value = p.getBoolean("wifi_only_streaming", false)
    }

    fun setOffline(v: Boolean) { offline.value = v; save() }
    fun setEnabled(f: Feature, v: Boolean) { enabled.value = enabled.value + (f to v); save() }
    fun setWifiOnlyStreaming(v: Boolean) { wifiOnlyStreaming.value = v; save() }

    /** null if [feature] may use the network right now, otherwise why not, in plain words. */
    fun blockReason(feature: Feature, traffic: Traffic = Traffic.LOOKUP): String? = when {
        offline.value -> "Offline mode is on"
        enabled.value[feature] == false -> "${feature.label} is switched off in Settings"
        traffic == Traffic.STREAM && wifiOnlyStreaming.value && isMetered() -> "Streaming is set to Wi-Fi only"
        else -> null
    }

    fun request(url: String, feature: Feature, traffic: Traffic = Traffic.LOOKUP): Request.Builder =
        Request.Builder().url(url).tag(Feature::class.java, feature).tag(Traffic::class.java, traffic)

    fun isMetered(): Boolean {
        if (!::app.isInitialized) return false
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private object GateInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            // an untagged request is a bug: refuse it rather than let it slip past the switches
            val feature = req.tag(Feature::class.java)
                ?: throw IOException("Request to ${req.url.host} has no feature tag")
            val traffic = req.tag(Traffic::class.java) ?: Traffic.LOOKUP
            blockReason(feature, traffic)?.let { throw NetworkBlockedException(feature, it) }
            val out = req.newBuilder().apply {
                if (req.header("User-Agent") == null) header("User-Agent", userAgent)
            }.build()
            return chain.proceed(out)
        }
    }

    private fun save() {
        if (!::app.isInitialized) return
        val e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("offline", offline.value)
            .putBoolean("wifi_only_streaming", wifiOnlyStreaming.value)
        enabled.value.forEach { (f, on) -> e.putBoolean("feature_${f.key}", on) }
        e.apply()
    }

    const val PREFS = "slowva-net"
    const val FEATURE_HEADER = "X-Slowva-Feature"
}

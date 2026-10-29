package com.slowva.player.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.WebView
import com.slowva.player.BuildConfig
import com.slowva.player.data.db.DiagnosticEventEntity
import com.slowva.player.data.db.DiagnosticIssueEntity
import com.slowva.player.data.db.DiagnosticsDao
import com.slowva.player.net.NetworkBlockedException
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Whose move it is when something fails (§12.3 of the plan). */
enum class Verdict { FIX_YOUR_SIDE, TEMPORARY, WAIT_FOR_UPDATE, UPDATE_AVAILABLE, NOT_A_BREAKAGE, APP_BUG, UNKNOWN }

/** One step of a fallback chain and how it ended, e.g. "android_vr → 403". */
data class Attempt(val method: String, val outcome: String)

/** A failure as the features report it; [record] redacts, groups and stores it. */
data class Failure(
    val feature: String,
    val action: String,
    val component: String,
    val item: String? = null,
    val attempts: List<Attempt> = emptyList(),
    val httpStatus: Int? = null,
    val error: Throwable? = null,
    val errorClass: String = classOf(error, httpStatus),
)

/** Collects failures locally. No analytics, no crash-report servers. */
object Diagnostics {
    private lateinit var app: Context
    private lateinit var dao: DiagnosticsDao
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private const val KEEP_EVENTS = 1000

    fun init(context: Context, dao: DiagnosticsDao) {
        if (::app.isInitialized) return
        app = context.applicationContext
        this.dao = dao
        installCrashHandler()
        io.launch {
            importLastCrash()
            recordLastExit()
        }
    }

    fun record(f: Failure) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        val fingerprint = listOf(f.feature, f.action, f.component, f.errorClass).joinToString("|")
        val event = DiagnosticEventEntity(
            fingerprint = fingerprint,
            at = now,
            item = f.item,
            attempts = f.attempts.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.method} → ${redact(it.outcome)}" },
            httpStatus = f.httpStatus,
            message = f.error?.message?.let(::redact)?.take(500),
            stackTrace = f.error?.stackTraceToString()?.let(::redact)?.take(8000),
            environment = environment(),
        )
        val template = DiagnosticIssueEntity(
            fingerprint = fingerprint,
            feature = f.feature,
            action = f.action,
            component = f.component,
            errorClass = f.errorClass,
            verdict = verdictFor(f.errorClass).name,
            count = 0,
            distinctItems = 0,
            firstSeen = now,
            lastSeen = now,
        )
        io.launch { runCatching { dao.record(event, template, KEEP_EVENTS) } }
    }

    /** First-pass verdict from the error alone; the on-demand self-check refines it later. */
    fun verdictFor(errorClass: String): Verdict = when (errorClass) {
        "blocked_by_settings", "no_network", "tls_or_clock" -> Verdict.FIX_YOUR_SIDE
        "http_429", "http_5xx", "timeout" -> Verdict.TEMPORARY
        "http_404", "http_410", "unavailable" -> Verdict.NOT_A_BREAKAGE
        "crash" -> Verdict.APP_BUG
        else -> Verdict.UNKNOWN
    }

    /** Strips secrets: cookies, tokens, visitorData, PO tokens and signed URL parts. */
    fun redact(text: String): String = text
        .replace(Regex("(?i)(cookie|set-cookie|authorization)\\s*[:=]\\s*[^\\n]+"), "$1: [redacted]")
        .replace(Regex("(?i)(?<![a-z0-9_])(token|potoken|po_token|visitor_?data|sig|signature|key|expire|lsig|n)=([^&\\s\"']+)"), "$1=[redacted]")

    /** Phone state that decides whose move a failure is. */
    private fun environment(): String {
        val parts = mutableListOf("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "Slowva ${BuildConfig.VERSION_NAME}")
        runCatching { WebView.getCurrentWebViewPackage()?.let { parts += "WebView ${it.versionName}" } }
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            if (caps == null) parts += "no network"
            else {
                parts += when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "other network"
                }
                parts += if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet OK" else "internet not validated"
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) parts += "captive portal"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN on"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cm.getLinkProperties(net)?.isPrivateDnsActive == true) parts += "Private DNS on"
            if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) parts += "Data Saver on"
        }
        if (NetworkGate.offline.value) parts += "Offline mode on"
        parts += "free storage ${app.filesDir.usableSpace / (1024 * 1024)} MB"
        return parts.joinToString(", ")
    }

    // ---- crashes ----

    private val crashFile get() = File(app.filesDir, "last-crash.txt")

    /** Writes the crash to a file before the process dies; it is filed as an issue on the next launch. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile.writeText("${System.currentTimeMillis()}\n${thread.name}\n${e.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun importLastCrash() {
        val f = crashFile
        if (!f.exists()) return
        val lines = runCatching { f.readText() }.getOrNull()?.lines().orEmpty()
        f.delete()
        if (lines.size < 3) return
        val stack = lines.drop(2).joinToString("\n")
        val top = stack.lineSequence().firstOrNull().orEmpty()
        record(
            Failure(
                feature = "app",
                action = "crash on thread ${lines[1]}",
                component = "Slowva ${BuildConfig.VERSION_NAME}",
                item = top.take(200),
                error = RuntimeException(stack),
                errorClass = "crash",
            )
        )
    }

    /** Records how the previous run ended when it wasn't a normal exit (Android 11+). */
    private fun recordLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val last = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 1) }.getOrNull()?.firstOrNull() ?: return
        val prefs = app.getSharedPreferences("slowva-diag", Context.MODE_PRIVATE)
        if (prefs.getLong("last_exit_seen", 0L) == last.timestamp) return
        prefs.edit().putLong("last_exit_seen", last.timestamp).apply()
        val kind = when (last.reason) {
            ApplicationExitInfo.REASON_ANR -> "froze (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
            else -> return // normal exits, user swipes and plain crashes (already filed) aren't news
        }
        record(
            Failure(
                feature = "app",
                action = "previous run $kind",
                component = "Slowva ${BuildConfig.VERSION_NAME}",
                item = last.description?.take(200),
                errorClass = if (last.reason == ApplicationExitInfo.REASON_LOW_MEMORY) "system_kill" else "crash",
            )
        )
    }
}

/** A short, stable name for an error, used in fingerprints and verdicts. */
fun classOf(error: Throwable?, httpStatus: Int?): String = when {
    httpStatus == 429 -> "http_429"
    httpStatus != null && httpStatus >= 500 -> "http_5xx"
    httpStatus != null && httpStatus >= 400 -> "http_$httpStatus"
    error is NetworkBlockedException -> "blocked_by_settings"
    error is UnknownHostException -> "no_network"
    error is SSLException -> "tls_or_clock"
    error is InterruptedIOException -> "timeout"
    error != null -> error.javaClass.simpleName.ifEmpty { "error" }
    else -> "unknown"
}

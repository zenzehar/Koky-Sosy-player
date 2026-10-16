/*
 * Koky & Sosy temporary diagnostic instrumentation for TV alpha hardware testing.
 * GPL-3.0-or-later. No URLs, title names, headers, authentication or raw error text.
 */
package app.opentv.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately small, allowlisted event log. Report text is meant to be scanned as a
 * plain-text QR from the television and pasted into a support conversation.
 *
 * Credentials are embedded in Xtream URLs. Never accept arbitrary string data from a
 * stream, Throwable.message, HTTP headers, MediaItem, provider or channel title.
 */
object PlaybackDiagnostics {
    private const val PREFS = "tv_alpha_playback_diagnostics_v1"
    private const val SAVED = "recent_diagnostic"
    private const val CRASH = "last_fatal_diagnostic"
    private const val CRASH_PENDING = "crash_pending"
    private const val MAX_EVENTS = 18
    private const val MAX_QR_CHARACTERS = 950
    private val installed = AtomicBoolean(false)
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var startedMs = SystemClock.elapsedRealtime()
    private var lastPersistAt = 0L
    private var engine = "none"
    private var lastPositionSec = 0L
    private var lastDurationSec = 0L
    private var rebufferCount = 0

    private val allowedEventNames = setOf(
        "OPEN", "VLC_START", "VLC_READY", "VLC_BUFFER", "VLC_STALL",
        "VLC_ERROR", "VLC_SEEK", "VLC_END", "VLC_EXIT", "VLC_RETRY",
        "VLC_TO_MEDIA3", "VLC_EXTERNAL", "MEDIA3_START", "MEDIA3_ERROR",
        "MEDIA3_STATE", "MEDIA3_EXIT", "UI_BACK", "REPORT", "CRASH",
    )

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            // Never swallow a fatal crash; allow Android to terminate the process as normal.
            // Only Throwable class and method signatures are recorded, never its message.
            try {
                synchronized(lock) {
                    push("CRASH " + safeType(failure.javaClass.simpleName))
                    val stack = failure.stackTrace.take(4).joinToString(";") {
                        safeType(it.className.substringAfterLast('.')) + "." +
                            safeType(it.methodName)
                    }
                    val report = composeReport(app, stack)
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(CRASH, report)
                        .putBoolean(CRASH_PENDING, true)
                        .commit() // Synchronous, before process termination.
                }
            } catch (_: Throwable) {
                // Avoid recursion inside the uncaught-exception handler.
            } finally {
                prior?.uncaughtException(thread, failure)
                    ?: android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    fun begin(engineName: String, mode: String) {
        synchronized(lock) {
            engine = if (engineName == "VLC") "VLC" else "Media3"
            events.clear()
            startedMs = SystemClock.elapsedRealtime()
            lastPositionSec = 0L
            lastDurationSec = 0L
            rebufferCount = 0
            push("OPEN " + safeType(mode).take(18))
        }
    }

    fun event(context: Context, name: String, detail: Int = 0, persistNow: Boolean = false) {
        if (name !in allowedEventNames) return
        synchronized(lock) {
            if (name == "VLC_BUFFER" && detail < 100) rebufferCount = maxOf(rebufferCount, detail / 1000)
            push("$name ${detail.coerceIn(-999_999, 999_999)}")
            val now = SystemClock.elapsedRealtime()
            // Buffering and stall reports survive ordinary Back and app-process death, while
            // normal progress events never write data to flash every half-second.
            if (persistNow || now - lastPersistAt > 3_000L) {
                val report = composeReport(context.applicationContext)
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(SAVED, report).apply()
                lastPersistAt = now
            }
        }
    }

    fun sample(positionMs: Long, durationMs: Long, interruptions: Int) {
        synchronized(lock) {
            lastPositionSec = positionMs.coerceAtLeast(0L) / 1000
            lastDurationSec = durationMs.coerceAtLeast(0L) / 1000
            rebufferCount = interruptions.coerceAtLeast(0)
        }
    }

    /** Snapshot all current details on demand, including low-memory indicators. */
    fun capture(context: Context): String = synchronized(lock) {
        val value = composeReport(context.applicationContext)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SAVED, value).apply()
        value
    }

    /** Crash wins over session report until user dismisses the recovered crash banner. */
    fun recoveredCrash(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .takeIf { it.getBoolean(CRASH_PENDING, false) }
            ?.getString(CRASH, null)

    fun acknowledgeCrash(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CRASH_PENDING, false).apply()
    }

    fun lastReport(context: Context): String = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CRASH, null)
            ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SAVED, null)
            ?: composeReport(context.applicationContext)
    }

    private fun push(message: String) {
        val sec = ((SystemClock.elapsedRealtime() - startedMs) / 1000).coerceAtLeast(0)
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast("${sec}s:$message")
    }

    private fun composeReport(context: Context, crashStack: String = ""): String {
        val runtime = Runtime.getRuntime()
        val heapMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeapMiB = runtime.maxMemory() / (1024 * 1024)
        val mem = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getMemoryInfo(mem)
        val freeSystemMiB = mem.availMem / (1024 * 1024)
        val head = buildString {
            append("KOKY-DIAG/1\n")
            append("build=0.1.10.8 alpha;libvlc=3.7.7\n")
            append("sdk=${Build.VERSION.SDK_INT};device=${safeType(Build.MANUFACTURER)}-")
            append(safeType(Build.MODEL))
            append(";engine=$engine\n")
            append("heap=${heapMiB}/${maxHeapMiB}MiB;freeRAM=${freeSystemMiB}MiB\n")
            append("position=${lastPositionSec}s;duration=${lastDurationSec}s;interruptions=$rebufferCount\n")
            if (crashStack.isNotBlank()) append("fatalStack=${crashStack.take(170)}\n")
        }
        val tail = events.joinToString("|")
        val remaining = (MAX_QR_CHARACTERS - head.length - 7).coerceAtLeast(0)
        // If full, keep newest events, not the first/oldest. Never truncate stack into
        // anything interpreted as executable text; the QR content is plain text only.
        return head + "events=" + tail.takeLast(remaining)
    }

    private fun safeType(raw: String?): String = (raw ?: "unknown")
        .replace(Regex("[^A-Za-z0-9_.$-]"), "_")
        .take(40)
}

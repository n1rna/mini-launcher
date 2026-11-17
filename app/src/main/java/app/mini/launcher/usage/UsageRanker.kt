package app.mini.launcher.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import app.mini.launcher.settings.SortMode
import kotlin.math.pow

/**
 * Decides which apps go on the home screen. Two signals:
 *
 *  1. Our own launch log (always available, no permission): each launch adds 1 to an
 *     exponentially-decaying score and stores the last-launch time.
 *  2. System usage events (needs the "Usage access" special permission): how often each app
 *     came to the foreground recently — including opens from notifications/recents — and when.
 *
 * [SortMode.MOST_USED] uses decayed open counts (half-life = how fast it adapts to new habits);
 * [SortMode.RECENT] uses the last time the app was opened.
 */
class UsageRanker(private val context: Context) {

    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    /** Other launchers (e.g. the one you switched from) are "used" constantly — never rank them. */
    private val homePackages: Set<String> by lazy {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet() + context.packageName
    }

    fun recordLaunch(id: String, halfLifeDays: Double) {
        val now = System.currentTimeMillis()
        val (score, at) = read(id)
        prefs.edit().putString(id, "${decay(score, now - at, halfLifeDays) + 1.0}|$now").apply()
    }

    fun clearHistory() = prefs.edit().clear().apply()

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Score per app id (higher = show first). Call off the main thread. */
    fun scores(
        ids: Collection<Pair<String, String>>, /* id to packageName */
        mode: SortMode,
        halfLifeDays: Double,
    ): Map<String, Double> {
        val now = System.currentTimeMillis()
        val sys = if (hasUsageAccess()) systemScan(now, halfLifeDays) else null
        return ids.associate { (id, pkg) ->
            if (pkg in homePackages) return@associate id to 0.0
            val (s, at) = read(id)
            id to when (mode) {
                SortMode.RECENT -> maxOf(at, sys?.lastUsed?.get(pkg) ?: 0L).toDouble()
                SortMode.MOST_USED, SortMode.MOST_USED_AZ -> {
                    val own = decay(s, now - at, halfLifeDays)
                    // System data already includes our launches; then our score only breaks ties.
                    if (sys == null || sys.opens.isEmpty()) own else (sys.opens[pkg] ?: 0.0) + own * 0.25
                }
            }
        }
    }

    private class SystemUsage(val opens: Map<String, Double>, val lastUsed: Map<String, Long>)

    private fun systemScan(now: Long, halfLifeDays: Double): SystemUsage {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val windowMs = (maxOf(21.0, halfLifeDays * 3) * DAY_MS).toLong()
        val events = usm.queryEvents(now - windowMs, now) ?: return SystemUsage(emptyMap(), emptyMap())
        val opens = HashMap<String, Double>()
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName
            if (pkg in homePackages) { lastPkg = pkg; continue }
            last[pkg] = e.timeStamp
            // Several activities of the same app in a row = one "open".
            if (pkg == lastPkg) continue
            lastPkg = pkg
            opens[pkg] = (opens[pkg] ?: 0.0) + decay(1.0, now - e.timeStamp, halfLifeDays)
        }
        return SystemUsage(opens, last)
    }

    private fun read(id: String): Pair<Double, Long> {
        val raw = prefs.getString(id, null) ?: return 0.0 to 0L
        val i = raw.indexOf('|')
        return (raw.substring(0, i).toDoubleOrNull() ?: 0.0) to (raw.substring(i + 1).toLongOrNull() ?: 0L)
    }

    private fun decay(score: Double, ageMs: Long, halfLifeDays: Double): Double =
        if (score == 0.0) 0.0 else score * 0.5.pow(ageMs.toDouble() / (halfLifeDays * DAY_MS))

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

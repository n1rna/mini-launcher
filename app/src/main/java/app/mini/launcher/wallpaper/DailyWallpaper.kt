package app.mini.launcher.wallpaper

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mini.launcher.settings.Settings
import app.mini.launcher.settings.SettingsStore
import app.mini.launcher.settings.WallpaperSource
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Runs once a day (and as a catch-up) to set today's wallpaper if it hasn't been set yet. */
class DailyWallpaperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SettingsStore.get(applicationContext).current.wallpaperEnabled) return Result.success()
        return when {
            WallpaperChanger.ensureToday(applicationContext) -> Result.success()
            runAttemptCount < 4 -> Result.retry()
            else -> Result.failure()
        }
    }
}

object WallpaperScheduler {
    private const val DAILY = "daily-wallpaper"
    private const val CATCH_UP = "daily-wallpaper-catch-up"
    private const val CHANGE_AT_HOUR = 5 // local time: new picture is ready when you wake up

    /** Make the scheduled job match the settings. Cheap; call whenever wallpaper settings change. */
    fun sync(context: Context) {
        val s = SettingsStore.get(context).current
        val wm = WorkManager.getInstance(context)
        if (!s.wallpaperEnabled) {
            wm.cancelUniqueWork(DAILY)
            wm.cancelUniqueWork(CATCH_UP)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailyWallpaperWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(s))
            .setInitialDelay(delayUntilNext(CHANGE_AT_HOUR).toMinutes(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Called when the home screen shows: if today's wallpaper is missing (phone was off at 5 AM,
     * no network, …) do it now — at most one attempt every 30 minutes.
     */
    fun catchUp(context: Context) {
        val s = SettingsStore.get(context).current
        if (!s.wallpaperEnabled) return
        val st = WallpaperChanger.state(context).value
        if (st.day == WallpaperChanger.today() || st.busy) return
        if (System.currentTimeMillis() - st.lastAttemptAt < 30 * 60_000L) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyWallpaperWorker>().setConstraints(constraints(s)).build(),
        )
    }

    private fun constraints(s: Settings) = Constraints.Builder()
        .setRequiredNetworkType(
            when {
                s.wallpaperSource == WallpaperSource.FOLDER -> NetworkType.NOT_REQUIRED
                s.wallpaperWifiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            }
        )
        .build()

    private fun delayUntilNext(hour: Int): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}

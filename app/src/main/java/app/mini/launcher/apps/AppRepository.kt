package app.mini.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppEntry(
    /** Stable id: "pkg/.Activity#userSerial". */
    val id: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWorkProfile: Boolean,
    /** Preinstalled (system) app — can't be uninstalled, only disabled. */
    val isSystem: Boolean,
)

/**
 * Lists launchable activities for every profile (personal + work) through [LauncherApps],
 * and keeps the list fresh when packages are installed/removed/updated.
 * Icons are not part of the list — they load lazily (and only in icon styles) via [icons].
 */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private var reloadJob: Job? = null

    /** Icons are only ever loaded if the user picks an icon style in settings. */
    val icons = IconCache(launcherApps)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = scheduleReload()
    }

    fun start() {
        launcherApps.registerCallback(callback)
        scheduleReload(0)
    }

    /** Debounced: an app update fires several callbacks in a row. */
    fun scheduleReload(delayMs: Long = 300) {
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            icons.clear()
            _apps.value = load()
        }
    }

    private fun load(): List<AppEntry> = query(context)

    fun launch(app: AppEntry, bounds: Rect? = null) {
        launcherApps.startMainActivity(app.component, app.user, bounds, null)
    }

    fun uninstall(app: AppEntry) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                .putExtra(Intent.EXTRA_USER, app.user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    companion object {
        /** All launchable activities across profiles, sorted by label. Blocking — call off the main thread. */
        fun query(context: Context): List<AppEntry> {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val userManager = context.getSystemService(UserManager::class.java)
            val me = Process.myUserHandle()
            val ownPkg = context.packageName
            return launcherApps.profiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                launcherApps.getActivityList(null, user)
                    .filter { it.applicationInfo.packageName != ownPkg }
                    .map { info ->
                        AppEntry(
                            id = "${info.componentName.flattenToShortString()}#$serial",
                            label = info.label.toString().trim(),
                            packageName = info.applicationInfo.packageName,
                            component = info.componentName,
                            user = user,
                            isWorkProfile = user != me,
                            isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        )
                    }
            }.sortedBy { it.label.lowercase() }
        }
    }
}

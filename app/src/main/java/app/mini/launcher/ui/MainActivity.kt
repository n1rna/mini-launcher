package app.mini.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.mini.launcher.settings.SettingsActivity
import app.mini.launcher.wallpaper.WallpaperScheduler

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCalendar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WallpaperScheduler.sync(this) // (re)schedule the daily job, e.g. after an app update
        // A launcher must never finish on back. (Compose BackHandlers registered later win.)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = vm.resetUi()
        })
        setContent {
            LauncherUi(
                vm = vm,
                requestCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                expandNotifications = ::expandNotifications,
                openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        WallpaperScheduler.catchUp(this)
    }

    /** Leaving the launcher (e.g. an app opened) → come back to a clean home screen. */
    override fun onStop() {
        super.onStop()
        vm.resetUi()
    }

    /** Home pressed while already home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.resetUi()
    }

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }
}

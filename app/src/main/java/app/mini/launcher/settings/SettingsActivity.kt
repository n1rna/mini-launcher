package app.mini.launcher.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mini.launcher.BuildConfig
import android.net.Uri
import android.provider.DocumentsContract
import app.mini.launcher.apps.AppRepository
import app.mini.launcher.wallpaper.WallpaperChanger
import app.mini.launcher.wallpaper.WallpaperScheduler
import app.mini.launcher.usage.UsageRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0B0D)
private val Fg = Color.White
private val Muted = Color(0x99FFFFFF)
private val Faint = Color(0x55FFFFFF)
private val Line = Color(0x1FFFFFFF)
private val Accent = Color(0xFF7BD88F)

/** Opened by long-pressing empty space on the home screen. */
class SettingsActivity : ComponentActivity() {

    private data class SystemState(val calendar: Boolean, val usage: Boolean, val isDefaultHome: Boolean)

    private var system by mutableStateOf(SystemState(false, false, false))
    private val usage by lazy { UsageRanker(this) }

    /**
     * Android's own "Set Mini as your default home app?" dialog. If Android refuses to show it
     * (it auto-denies after the user declined twice), the result comes back instantly — then fall
     * back to the system Home-app settings page.
     */
    private var roleRequestedAt = 0L
    private val homeRoleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshSystem()
            val instant = System.currentTimeMillis() - roleRequestedAt < 600
            if (!system.isDefaultHome && instant) open(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
        }

    private fun makeDefaultHome() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleRequestedAt = System.currentTimeMillis()
            homeRoleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            open(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
        }
    }

    private val store by lazy { SettingsStore.get(this) }

    /** Every settings write goes through here, so side effects (wallpaper scheduling) stay in sync. */
    private fun update(transform: (Settings) -> Settings) {
        val old = store.current
        store.update(transform)
        val new = store.current
        val wpOld = listOf(old.wallpaperEnabled, old.wallpaperSource, old.wallpaperTarget, old.wallpaperWifiOnly, old.wallpaperFolder)
        val wpNew = listOf(new.wallpaperEnabled, new.wallpaperSource, new.wallpaperTarget, new.wallpaperWifiOnly, new.wallpaperFolder)
        if (wpOld == wpNew) return
        WallpaperScheduler.sync(this)
        val needsNewImage = new.wallpaperEnabled && (!old.wallpaperEnabled ||
            old.wallpaperSource != new.wallpaperSource || old.wallpaperFolder != new.wallpaperFolder ||
            old.wallpaperTarget != new.wallpaperTarget)
        if (!needsNewImage) return
        if (new.wallpaperSource == WallpaperSource.FOLDER && new.wallpaperFolder == null) pickFolder.launch(null)
        else WallpaperChanger.applyFreshAsync(this)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // drop access to the previously chosen folder
        store.current.wallpaperFolder?.takeIf { it != uri.toString() }?.let { old ->
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        update { it.copy(wallpaperFolder = uri.toString(), wallpaperSource = WallpaperSource.FOLDER) }
    }

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSystem() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val s by store.settings.collectAsStateWithLifecycle()
            SettingsScreen(
                s = s,
                sys = system,
                update = ::update,
                requestCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                openUsageAccess = { open(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) },
                openHomeSettings = ::makeDefaultHome,
                clearHistory = { usage.clearHistory() },
                resetAll = store::reset,
                close = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystem()
    }

    private fun refreshSystem() {
        system = SystemState(
            calendar = checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            usage = usage.hasUsageAccess(),
            isDefaultHome = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
        )
    }

    private fun open(intent: Intent) = runCatching { startActivity(intent) }

    private fun folderDisplayName(tree: String): String? = runCatching {
        val uri = Uri.parse(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    @Composable
    private fun SettingsScreen(
        s: Settings,
        sys: SystemState,
        update: ((Settings) -> Settings) -> Unit,
        requestCalendar: () -> Unit,
        openUsageAccess: () -> Unit,
        openHomeSettings: () -> Unit,
        clearHistory: () -> Unit,
        resetAll: () -> Unit,
        close: () -> Unit,
    ) {
        var historyCleared by androidx.compose.runtime.remember { mutableStateOf(false) }
        val wallpaper by WallpaperChanger.state(this@SettingsActivity).collectAsStateWithLifecycle()
        val folderName by produceState<String?>(null, s.wallpaperFolder) {
            value = s.wallpaperFolder?.let { withContext(Dispatchers.IO) { folderDisplayName(it) } }
        }
        // id -> label for the pinned list (same ids the home screen uses)
        val labels by produceState(emptyMap<String, String>()) {
            value = withContext(Dispatchers.IO) { AppRepository.query(this@SettingsActivity).associate { it.id to it.label } }
        }
        fun movePin(id: String, delta: Int) = update { st ->
            val list = st.pinned.toMutableList()
            val i = list.indexOf(id)
            val j = i + delta
            if (i < 0 || j !in list.indices) st else st.copy(pinned = list.apply { add(j, removeAt(i)) })
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    BasicText(
                        "←", style = TextStyle(color = Fg, fontSize = 24.sp),
                        modifier = Modifier.clickable(onClick = close).padding(end = 16.dp),
                    )
                    BasicText("Settings", style = TextStyle(color = Fg, fontSize = 30.sp, fontWeight = FontWeight.Light))
                }
            }
            if (!sys.isDefaultHome) item {
                // Prominent until Mini is the home app; afterwards it lives quietly under "System".
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(Accent, RoundedCornerShape(18.dp))
                        .clickable(interactionSource = null, indication = null, onClick = openHomeSettings)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Set as default launcher", style = TextStyle(color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Medium))
                        Spacer(Modifier.height(2.dp))
                        BasicText("Make Mini open when you press Home", style = TextStyle(color = Color(0xB3000000), fontSize = 13.sp))
                    }
                    BasicText("→", style = TextStyle(color = Color.Black, fontSize = 20.sp))
                }
            }

            // ── Home apps ──
            item { Section("Home apps") }
            item {
                Stepper("Number of apps", s.homeAppCount, 3..16, note = "Pinned apps count toward this") { v ->
                    update { it.copy(homeAppCount = v) }
                }
            }
            item {
                Choice("Sort by", SortMode.entries, s.sortMode, { it.title }) { v -> update { it.copy(sortMode = v) } }
            }
            if (s.sortMode != SortMode.RECENT) item {
                Choice(
                    "Adapts to new habits", Adapt.entries, s.adapt, { it.title },
                    note = "How quickly old usage fades: ${s.adapt.halfLifeDays.toInt()}-day half-life",
                ) { v -> update { it.copy(adapt = v) } }
            }
            item { Choice("Style", AppStyle.entries, s.appStyle, { it.title }) { v -> update { it.copy(appStyle = v) } } }
            if (s.appStyle != AppStyle.ICONS) {
                item { Choice("Text size", TextSize.entries, s.textSize, { it.title }) { v -> update { it.copy(textSize = v) } } }
                item { Choice("Alignment", Align.entries, s.align, { it.title }) { v -> update { it.copy(align = v) } } }
            }
            item {
                Toggle("Shortcut labels", s.showHints, note = "Shows the fewest letters that open each app") { v ->
                    update { it.copy(showHints = v) }
                }
            }

            // ── Pinned apps ──
            item { Section("Pinned apps") }
            if (s.pinned.isEmpty()) item {
                BasicText(
                    "Long-press any app on the home screen or in search, then “Pin to home”.",
                    style = TextStyle(color = Muted, fontSize = 14.sp),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                s.pinned.forEachIndexed { i, id ->
                    item(key = "pin:$id") {
                        PinnedRow(
                            label = labels[id] ?: if (labels.isEmpty()) "…" else "(not installed)",
                            canUp = i > 0,
                            canDown = i < s.pinned.size - 1,
                            onUp = { movePin(id, -1) },
                            onDown = { movePin(id, +1) },
                            onRemove = { update { it.copy(pinned = it.pinned - id) } },
                        )
                    }
                }
                item {
                    Choice("Position", PinnedPosition.entries, s.pinnedPosition, { it.title }) { v ->
                        update { it.copy(pinnedPosition = v) }
                    }
                }
            }

            // ── Search ──
            item { Section("Search") }
            item {
                Toggle("Open on unique match", s.autoLaunch, note = "Launch as soon as only one app matches what you typed") { v ->
                    update { it.copy(autoLaunch = v) }
                }
            }

            // ── Clock ──
            item { Section("Clock") }
            item { Toggle("Show clock", s.showClock) { v -> update { it.copy(showClock = v) } } }
            if (s.showClock) item {
                Choice("Format", ClockFormat.entries, s.clockFormat, { it.title }) { v -> update { it.copy(clockFormat = v) } }
            }
            item { Toggle("Show date", s.showDate) { v -> update { it.copy(showDate = v) } } }

            // ── Prices ──
            item { Section("Prices") }
            item { Toggle("Show prices", s.showPrices) { v -> update { it.copy(showPrices = v) } } }
            if (s.showPrices) {
                item {
                    MultiChoice("Coins (in USD)", SUPPORTED_COINS.keys.toList(), s.coins, { SUPPORTED_COINS.getValue(it) }) { id ->
                        update { st ->
                            val next = if (id in st.coins) st.coins - id else st.coins + id
                            st.copy(coins = next.ifEmpty { st.coins }) // keep at least one
                        }
                    }
                }
                item {
                    Choice("Refresh every", listOf(5, 15, 30, 60), s.priceRefreshMin, { "$it min" },
                        note = "Only while the home screen is open — never in the background") { v ->
                        update { it.copy(priceRefreshMin = v) }
                    }
                }
            }

            // ── Calendar ──
            item { Section("Calendar") }
            item { Toggle("Show calendar", s.showCalendar) { v -> update { it.copy(showCalendar = v) } } }
            if (s.showCalendar) {
                if (!sys.calendar) item { Action("Allow calendar access", "Needed to show your events", requestCalendar) }
                item {
                    Choice("Show events for", listOf(1, 2, 3, 7), s.calendarDays,
                        { when (it) { 1 -> "Today"; 2 -> "+ Tomorrow"; 3 -> "3 days"; else -> "Week" } }) { v ->
                        update { it.copy(calendarDays = v) }
                    }
                }
                item { Stepper("Max events", s.calendarMaxEvents, 1..8) { v -> update { it.copy(calendarMaxEvents = v) } } }
            }

            // ── Wallpaper ──
            item { Section("Wallpaper") }
            item {
                Toggle(
                    "New wallpaper every day", s.wallpaperEnabled,
                    note = if (s.wallpaperEnabled) "Changes around 5 AM, or when you next open home"
                    else "Replaces your current wallpaper",
                ) { v -> update { it.copy(wallpaperEnabled = v) } }
            }
            if (s.wallpaperEnabled) {
                item {
                    Choice("Source", WallpaperSource.entries, s.wallpaperSource, { it.title }) { v ->
                        update { it.copy(wallpaperSource = v) }
                    }
                }
                if (s.wallpaperSource == WallpaperSource.FOLDER) item {
                    Action(
                        "Folder",
                        folderName?.let { "$it — one photo per day, in name order" } ?: "Choose a folder of photos",
                        { pickFolder.launch(s.wallpaperFolder?.let(Uri::parse)) },
                    )
                }
                item {
                    Choice("Apply to", WallpaperTarget.entries, s.wallpaperTarget, { it.title }) { v ->
                        update { it.copy(wallpaperTarget = v) }
                    }
                }
                if (s.wallpaperSource != WallpaperSource.FOLDER) item {
                    Toggle("Wi-Fi only", s.wallpaperWifiOnly, note = "Wait for Wi-Fi before downloading") { v ->
                        update { it.copy(wallpaperWifiOnly = v) }
                    }
                }
                item {
                    val note = when {
                        wallpaper.busy -> "Changing…"
                        wallpaper.error != null -> "Couldn't change: ${wallpaper.error}"
                        wallpaper.day >= 0 && wallpaper.title != null -> "Now: ${wallpaper.title}"
                        else -> "Get a different picture right away"
                    }
                    Action("Change now", note, { WallpaperChanger.nextAsync(this@SettingsActivity) })
                }
            }
            item {
                Choice("Darken behind text", Dim.entries, s.dim, { it.title },
                    note = "Keeps the clock and app names readable on bright pictures") { v ->
                    update { it.copy(dim = v) }
                }
            }

            // ── System ──
            item { Section("System") }
            item {
                Action(
                    "Usage access",
                    if (sys.usage) "Granted — most-used apps include opens from anywhere"
                    else "Not granted — only launches from this launcher are counted",
                    openUsageAccess, done = sys.usage,
                )
            }
            item {
                Action(
                    "Default home app",
                    if (sys.isDefaultHome) "Mini is your home app — tap to change" else "Tap to make Mini your home app",
                    openHomeSettings, done = sys.isDefaultHome,
                )
            }
            item {
                Action(
                    "Reset usage history",
                    if (historyCleared) "Cleared" else "Forget launches recorded by this launcher",
                    { clearHistory(); historyCleared = true },
                )
            }
            item { Action("Reset all settings", "Back to defaults", resetAll) }
            item {
                Spacer(Modifier.height(24.dp))
                BasicText("Mini ${BuildConfig.VERSION_NAME}", style = TextStyle(color = Faint, fontSize = 12.sp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ───────────────────────────── building blocks ─────────────────────────────

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 28.dp, bottom = 6.dp)) {
        BasicText(title.uppercase(), style = TextStyle(color = Faint, fontSize = 12.sp, letterSpacing = 2.sp))
    }
}

@Composable
private fun Label(title: String, note: String?) {
    Column {
        BasicText(title, style = TextStyle(color = Fg, fontSize = 17.sp))
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            BasicText(note, style = TextStyle(color = Muted, fontSize = 13.sp))
        }
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null) { onChange(!value) }
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.weight(1f)) { Label(title, note) }
        Spacer(Modifier.width(16.dp))
        val knob by animateDpAsState(if (value) 20.dp else 2.dp, label = "knob")
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .background(if (value) Accent else Line, RoundedCornerShape(13.dp))
        ) {
            Box(
                Modifier
                    .offset(x = knob, y = 2.dp)
                    .size(22.dp)
                    .background(if (value) Color.Black else Muted, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    note: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Label(title, note)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> Chip(label(o), o == selected) { onSelect(o) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> MultiChoice(
    title: String,
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Label(title, null)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> Chip(label(o), o in selected) { onToggle(o) } }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    BasicText(
        text,
        style = TextStyle(color = if (selected) Color.Black else Fg, fontSize = 14.sp),
        modifier = Modifier
            .background(if (selected) Fg else Color.Transparent, shape)
            .border(1.dp, if (selected) Fg else Line, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PinnedRow(
    label: String,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        BasicText(
            label, style = TextStyle(color = Fg, fontSize = 17.sp), maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        StepButton("↑", enabled = canUp, onClick = onUp)
        Spacer(Modifier.width(8.dp))
        StepButton("↓", enabled = canDown, onClick = onDown)
        Spacer(Modifier.width(8.dp))
        StepButton("✕", enabled = true, onClick = onRemove)
    }
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, note: String? = null, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Box(Modifier.weight(1f)) { Label(title, note) }
        StepButton("−", enabled = value > range.first) { onChange(value - 1) }
        BasicText(
            value.toString(),
            style = TextStyle(color = Fg, fontSize = 18.sp, textAlign = TextAlign.Center),
            modifier = Modifier.width(48.dp),
        )
        StepButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .border(1.dp, Line, CircleShape)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
    ) {
        BasicText(text, style = TextStyle(color = if (enabled) Fg else Faint, fontSize = 18.sp))
    }
}

@Composable
private fun Action(title: String, note: String, onClick: () -> Unit, done: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.weight(1f)) { Label(title, note) }
        BasicText(if (done) "✓" else "→", style = TextStyle(color = if (done) Accent else Muted, fontSize = 18.sp))
    }
}

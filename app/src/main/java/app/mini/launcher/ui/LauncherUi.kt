package app.mini.launcher.ui

import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.mini.launcher.apps.AppEntry
import app.mini.launcher.apps.IconCache
import app.mini.launcher.search.SearchIndex
import app.mini.launcher.settings.Align
import app.mini.launcher.settings.AppStyle
import app.mini.launcher.settings.ClockFormat
import app.mini.launcher.settings.PinnedPosition
import app.mini.launcher.settings.Settings
import app.mini.launcher.widgets.CalEvent
import app.mini.launcher.widgets.Quotes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val Fg = Color.White
private val Dim = Color(0x99FFFFFF)
private val Faint = Color(0x55FFFFFF)
private val Up = Color(0xFF7BD88F)
private val Down = Color(0xFFFF7A7A)

private fun Align.horizontal(): Alignment.Horizontal = when (this) {
    Align.START -> Alignment.Start
    Align.CENTER -> Alignment.CenterHorizontally
    Align.END -> Alignment.End
}

private fun Align.arrangement(): Arrangement.Horizontal = when (this) {
    Align.START -> Arrangement.Start
    Align.CENTER -> Arrangement.Center
    Align.END -> Arrangement.End
}

@Composable
fun LauncherUi(
    vm: HomeViewModel,
    requestCalendar: () -> Unit,
    expandNotifications: () -> Unit,
    openSettings: () -> Unit,
) {
    val index by vm.index.collectAsStateWithLifecycle()
    val reset by vm.reset.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(reset) { searching = false; query = ""; menuApp = null }
    val haptics = LocalHapticFeedback.current
    val openMenu: (AppEntry) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuApp = it
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = settings.dim.alpha))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp)
        ) {
            if (!searching) {
                HomeScreen(vm, settings, index, requestCalendar, onSearch = { searching = true }, expandNotifications, openSettings, openMenu)
            } else {
                BackHandler { searching = false; query = "" }
                SearchScreen(vm, settings, index, query, onQuery = { query = it }, openMenu)
            }
        }
        menuApp?.let { app ->
            BackHandler { menuApp = null }
            AppMenu(app, settings, vm, onDismiss = { menuApp = null })
        }
    }
}

// ─────────────────────────────── App menu ───────────────────────────────

/** Long-press menu for an app: pin/unpin, reorder pins, app info, uninstall. */
@Composable
private fun AppMenu(app: AppEntry, settings: Settings, vm: HomeViewModel, onDismiss: () -> Unit) {
    val pinIndex = settings.pinned.indexOf(app.id)
    val pinned = pinIndex >= 0
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(interactionSource = null, indication = null) {} // swallow taps
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            BasicText(app.label, style = TextStyle(color = Fg, fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            MenuItem(if (pinned) "Unpin from home" else "Pin to home") { vm.togglePin(app); onDismiss() }
            if (pinned && settings.pinned.size > 1) {
                // "up" means visually up on the home screen = earlier in the pinned list
                if (pinIndex > 0) MenuItem("Move up") { vm.movePin(app, -1) }
                if (pinIndex < settings.pinned.size - 1) MenuItem("Move down") { vm.movePin(app, +1) }
            }
            MenuItem("App info") { vm.appInfo(app); onDismiss() }
            if (!app.isSystem) MenuItem("Uninstall") { vm.uninstall(app); onDismiss() }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Dim, fontSize = 18.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

// ───────────────────────────────── Home ─────────────────────────────────

/**
 * Long-press on *empty* space. Children that handle their own presses (app rows, calendar…)
 * consume the down event, so this never fires on top of an app's long-press.
 */
private fun Modifier.onEmptyLongPress(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val slop = viewConfiguration.touchSlop
        var cancelled = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!cancelled) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || change.isConsumed ||
                    (change.position - down.position).getDistance() > slop
                ) cancelled = true
            }
        }
        if (!cancelled) action()
    }
}

@Composable
private fun HomeScreen(
    vm: HomeViewModel,
    settings: Settings,
    index: SearchIndex,
    requestCalendar: () -> Unit,
    onSearch: () -> Unit,
    expandNotifications: () -> Unit,
    openSettings: () -> Unit,
    openMenu: (AppEntry) -> Unit,
) {
    val home by vm.homeApps.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val calendarAllowed by vm.calendarAllowed.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val pinnedFirst = settings.pinnedPosition == PinnedPosition.TOP
    val groups = if (pinnedFirst) listOf(home.pinned, home.auto) else listOf(home.auto, home.pinned)

    Column(
        horizontalAlignment = settings.align.horizontal(),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                val threshold = 60.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -threshold) onSearch()
                        else if (total > threshold) expandNotifications()
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
            .onEmptyLongPress {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                openSettings()
            }
    ) {
        Spacer(Modifier.height(32.dp))
        if (settings.showClock || settings.showDate) {
            ClockHeader(settings, onDateClick = vm::openCalendar)
            Spacer(Modifier.height(20.dp))
        }
        if (settings.showPrices) {
            quotes?.let { PriceRow(it, settings.align, onClick = { vm.refreshPrices() }) }
            Spacer(Modifier.height(16.dp))
        }
        if (settings.showCalendar) {
            CalendarBlock(events, calendarAllowed, requestCalendar, vm::openEvent)
        }

        Spacer(Modifier.weight(1f))

        if (!usageAccess) {
            SmallLink("Allow usage access to detect your most-used apps →", vm::openUsageAccessSettings)
            Spacer(Modifier.height(12.dp))
        }
        if (settings.appStyle == AppStyle.ICONS) {
            IconGrid(groups.flatten(), vm.icons, index, settings, onClick = vm::launch, onLongClick = openMenu)
        } else {
            groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
                // a little air between pinned and most-used
                if (i > 0) Spacer(Modifier.height(14.dp))
                group.forEach { app ->
                    key(app.id) {
                        AppRow(app, settings, vm.icons, hint = index.hint(app.id), typed = "", highlight = null,
                            onClick = { vm.launch(app) }, onLongClick = { openMenu(app) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            "search",
            style = TextStyle(color = Faint, fontSize = 13.sp, letterSpacing = 2.sp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(interactionSource = null, indication = null, onClick = onSearch)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ClockHeader(settings: Settings, onDateClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Tick on the minute boundary, only while visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000 - now % 60_000 + 50)
            }
        }
    }
    val fmt = settings.clockFormat
    val time = remember(now, fmt) {
        when (fmt) {
            ClockFormat.SYSTEM -> DateFormat.getTimeFormat(context)
            ClockFormat.H24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
            ClockFormat.H12 -> SimpleDateFormat("h:mm", Locale.getDefault())
        }.format(Date(now))
    }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)) }
    if (settings.showClock) {
        BasicText(
            time,
            style = TextStyle(color = Fg, fontSize = 64.sp, fontWeight = FontWeight.Light),
            modifier = Modifier.clickable(interactionSource = null, indication = null) {
                runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
    if (settings.showDate) {
        BasicText(
            date,
            style = TextStyle(color = Dim, fontSize = 16.sp),
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onDateClick),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriceRow(q: Quotes, align: Align, onClick: () -> Unit) {
    val fmt = remember { NumberFormat.getCurrencyInstance(Locale.US) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp, align.horizontal()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        q.items.forEach { quote ->
            // Cents only where they matter (DOGE, ADA, XRP…).
            fmt.maximumFractionDigits = if (quote.usd < 10) 3 else if (quote.usd < 1000) 2 else 0
            fmt.minimumFractionDigits = if (quote.usd < 1000) 2 else 0
            BasicText(buildAnnotatedString {
                withStyle(SpanStyle(color = Dim, fontSize = 13.sp, letterSpacing = 1.sp)) { append(quote.symbol); append("  ") }
                withStyle(SpanStyle(color = Fg, fontSize = 16.sp)) { append(fmt.format(quote.usd)) }
                quote.change24hPct?.let { ch ->
                    withStyle(SpanStyle(color = if (ch >= 0) Up else Down, fontSize = 13.sp)) {
                        append("  "); append(if (ch >= 0) "▲" else "▼"); append(String.format(Locale.US, "%.1f%%", abs(ch)))
                    }
                }
            })
        }
    }
}

@Composable
private fun CalendarBlock(
    events: List<CalEvent>,
    allowed: Boolean,
    requestPermission: () -> Unit,
    onEvent: (CalEvent) -> Unit,
) {
    if (!allowed) {
        SmallLink("Show calendar →", requestPermission)
        return
    }
    if (events.isEmpty()) {
        BasicText("Nothing on the calendar", style = TextStyle(color = Faint, fontSize = 14.sp))
        return
    }
    val context = LocalContext.current
    val timeFmt = remember { DateFormat.getTimeFormat(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        events.forEach { e ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(interactionSource = null, indication = null) { onEvent(e) },
            ) {
                Box(Modifier.size(6.dp).background(Color(e.color).copy(alpha = 1f), CircleShape))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    whenLabel(e, timeFmt),
                    style = TextStyle(color = Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = 92.dp),
                )
                Spacer(Modifier.width(12.dp))
                BasicText(
                    e.title, style = TextStyle(color = Fg, fontSize = 15.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun whenLabel(e: CalEvent, timeFmt: java.text.DateFormat): String {
    val now = System.currentTimeMillis()
    val today = Calendar.getInstance()
    val day = Calendar.getInstance().apply { timeInMillis = maxOf(e.begin, now) }
    val dayDiff = (day.get(Calendar.YEAR) * 400 + day.get(Calendar.DAY_OF_YEAR)) -
        (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR))
    val t = when {
        e.allDay -> "all day"
        e.begin <= now -> "now"
        else -> timeFmt.format(Date(e.begin))
    }
    return when (dayDiff) {
        0 -> t
        1 -> "tmrw $t"
        else -> SimpleDateFormat("EEE", Locale.getDefault()).format(day.time) + " " + t
    }
}

@Composable
private fun IconGrid(
    apps: List<AppEntry>,
    icons: IconCache,
    index: SearchIndex,
    settings: Settings,
    onClick: (AppEntry) -> Unit,
    onLongClick: (AppEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { app -> key(app.id) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(68.dp)
                            .combinedClickable(interactionSource = null, indication = null,
                                onClick = { onClick(app) }, onLongClick = { onLongClick(app) }),
                    ) {
                        AppIcon(app, icons, 52.dp)
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            (if (settings.showHints) index.hint(app.id) else null) ?: " ",
                            style = TextStyle(color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        )
                    }
                } }
                // keep a short last row aligned to the same columns
                repeat(4 - row.size) { Spacer(Modifier.width(68.dp)) }
            }
        }
    }
}

// ──────────────────────────────── Search ────────────────────────────────

@Composable
private fun SearchScreen(
    vm: HomeViewModel,
    settings: Settings,
    index: SearchIndex,
    query: String,
    onQuery: (String) -> Unit,
    openMenu: (AppEntry) -> Unit,
) {
    val appsById by vm.appsById.collectAsStateWithLifecycle()
    val scores by vm.scores.collectAsStateWithLifecycle()
    val results = remember(index, query, scores, appsById) {
        val ids = if (query.isBlank()) index.search("").sortedByDescending { scores[it] ?: 0.0 }
        else index.search(query) { scores[it] ?: 0.0 }
        ids.mapNotNull { appsById[it] }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    // The search list always needs labels; "Icons" style shows icon + text here.
    val rowSettings = if (settings.appStyle == AppStyle.ICONS) settings.copy(appStyle = AppStyle.ICON_TEXT) else settings

    Column(Modifier.fillMaxSize().imePadding()) {
        // Results grow upward from the input so the best match sits right above your thumb.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(results, key = { it.id }) { app ->
                AppRow(
                    app, rowSettings, vm.icons, hint = index.hint(app.id), typed = SearchIndex.normalize(query),
                    highlight = index.matchRange(app.id, query),
                    onClick = { vm.launch(app) }, onLongClick = { openMenu(app) },
                )
            }
        }
        BasicTextField(
            value = query,
            onValueChange = { new ->
                val grew = SearchIndex.normalize(new).length > SearchIndex.normalize(query).length
                onQuery(new)
                // Only auto-open while typing forward — never while deleting.
                if (settings.autoLaunch && grew) {
                    index.resolve(new)?.let { id -> appsById[id]?.let(vm::launch) }
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = Fg, fontSize = 26.sp),
            cursorBrush = SolidColor(Fg),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(vm::launch) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) BasicText("type to search", style = TextStyle(color = Faint, fontSize = 26.sp))
                    inner()
                }
            },
        )
    }
}

// ──────────────────────────────── Shared ────────────────────────────────

@Composable
private fun AppIcon(app: AppEntry, icons: IconCache, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    // State is keyed by the app: when a slot is reused for a different app (the list re-sorts once
    // usage scores arrive), it must never keep showing the previous app's bitmap.
    var bmp by remember(app.id, px) { mutableStateOf(icons.peek(app, px)) }
    LaunchedEffect(app.id, px) { if (bmp == null) bmp = icons.load(app, px) }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(size))
    else Spacer(Modifier.size(size))
}

/**
 * One app line: [icon] label + its typing shortcut (e.g. "YouTube  tub").
 * While searching, the already-typed part of the shortcut lights up.
 */
@Composable
private fun AppRow(
    app: AppEntry,
    settings: Settings,
    icons: IconCache,
    hint: String?,
    typed: String,
    highlight: IntRange?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fontSize = settings.textSize.sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = settings.align.arrangement(),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = (fontSize * 0.38f).dp),
    ) {
        if (settings.appStyle == AppStyle.ICON_TEXT) {
            AppIcon(app, icons, (fontSize * 1.25f).dp)
            Spacer(Modifier.width(14.dp))
        }
        BasicText(
            labelText(app, highlight),
            style = TextStyle(fontSize = fontSize.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hint != null && settings.showHints) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                hintText(hint, typed),
                style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun labelText(app: AppEntry, highlight: IntRange?): AnnotatedString = buildAnnotatedString {
    val base = if (highlight == null) Fg else Dim
    withStyle(SpanStyle(color = base)) { append(app.label) }
    if (highlight != null) addStyle(SpanStyle(color = Fg, fontWeight = FontWeight.Medium), highlight.first, highlight.last + 1)
    if (app.isWorkProfile) withStyle(SpanStyle(color = Faint, fontSize = 13.sp)) { append("  work") }
}

private fun hintText(hint: String, typed: String): AnnotatedString = buildAnnotatedString {
    val done = if (typed.isNotEmpty() && hint.startsWith(typed)) typed.length else 0
    withStyle(SpanStyle(color = Fg)) { append(hint.substring(0, done)) }
    withStyle(SpanStyle(color = Dim)) { append(hint.substring(done)) }
}

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Dim, fontSize = 14.sp),
        modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick).padding(vertical = 4.dp),
    )
}

package app.mini.launcher.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mini.launcher.apps.AppEntry
import app.mini.launcher.apps.AppRepository
import app.mini.launcher.apps.IconCache
import app.mini.launcher.search.SearchIndex
import app.mini.launcher.settings.Settings
import app.mini.launcher.settings.SettingsStore
import app.mini.launcher.settings.SortMode
import app.mini.launcher.usage.UsageRanker
import app.mini.launcher.widgets.CalEvent
import app.mini.launcher.widgets.CalendarRepository
import app.mini.launcher.widgets.PriceRepository
import app.mini.launcher.widgets.Quotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore.get(app)
    val settings: StateFlow<Settings> = store.settings
    private val s get() = store.current

    private val repo = AppRepository(app, viewModelScope).also { it.start() }
    private val usage = UsageRanker(app)
    private val calendar = CalendarRepository(app)
    private val prices = PriceRepository(app)

    val icons: IconCache get() = repo.icons

    val appsById: StateFlow<Map<String, AppEntry>> = repo.apps
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Rebuilt (off the main thread) only when the installed app set changes. */
    val index: StateFlow<SearchIndex> = repo.apps
        .map { list -> SearchIndex(list.associate { it.id to it.label }) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchIndex(emptyMap()))

    private val _scores = MutableStateFlow<Map<String, Double>>(emptyMap())
    val scores: StateFlow<Map<String, Double>> = _scores

    val homeApps: StateFlow<HomeApps<AppEntry>> = combine(repo.apps, _scores, settings) { list, sc, st ->
        pickHomeApps(list, { it.id }, { it.label }, sc, st)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeApps(emptyList(), emptyList()))

    private val _events = MutableStateFlow<List<CalEvent>>(emptyList())
    val events: StateFlow<List<CalEvent>> = _events

    private val _calendarAllowed = MutableStateFlow(calendar.hasPermission())
    val calendarAllowed: StateFlow<Boolean> = _calendarAllowed

    private val _usageAccess = MutableStateFlow(usage.hasUsageAccess())
    val usageAccess: StateFlow<Boolean> = _usageAccess

    private val _quotes = MutableStateFlow(prices.cached(s.orderedCoins))
    val quotes: StateFlow<Quotes?> = _quotes

    /** Bumped to tell the UI to go back to the clean home state (home button, app launched). */
    private val _reset = MutableStateFlow(0)
    val reset: StateFlow<Int> = _reset

    init {
        // Scores need the app list; recompute when it first arrives / changes.
        viewModelScope.launch { repo.apps.collect { if (it.isNotEmpty()) refreshScores() } }
        // React to settings changes that need new data.
        viewModelScope.launch {
            settings.map { it.sortMode to it.adapt }.distinctUntilChanged().drop(1).collect { refreshScores() }
        }
        viewModelScope.launch {
            settings.map { Triple(it.showCalendar, it.calendarDays, it.calendarMaxEvents) }
                .distinctUntilChanged().drop(1).collect { refreshCalendar() }
        }
        viewModelScope.launch {
            settings.map { it.showPrices to it.orderedCoins }.distinctUntilChanged().drop(1).collect {
                _quotes.value = prices.cached(s.orderedCoins)
                refreshPrices(force = false)
            }
        }
    }

    fun onResume() {
        _usageAccess.value = usage.hasUsageAccess()
        refreshScores()
        refreshCalendar()
        refreshPrices(force = false)
    }

    fun resetUi() { _reset.value++ }

    fun refreshCalendar() {
        _calendarAllowed.value = calendar.hasPermission()
        if (!s.showCalendar) return
        val (days, max) = s.calendarDays to s.calendarMaxEvents
        viewModelScope.launch(Dispatchers.IO) { _events.value = calendar.upcoming(days, max) }
    }

    private var priceJob: Job? = null
    fun refreshPrices(force: Boolean = true) {
        if (!s.showPrices) return
        val coins = s.orderedCoins
        val maxAge = s.priceRefreshMin * 60_000L
        priceJob?.cancel()
        priceJob = viewModelScope.launch {
            prices.refreshIfStale(coins, maxAge, force)?.let { _quotes.value = it }
        }
    }

    private fun refreshScores() {
        val list = repo.apps.value
        if (list.isEmpty()) return
        val (mode, halfLife) = s.sortMode to s.adapt.halfLifeDays
        viewModelScope.launch(Dispatchers.IO) {
            _scores.value = usage.scores(list.map { it.id to it.packageName }, mode, halfLife)
        }
    }

    fun launch(app: AppEntry) {
        usage.recordLaunch(app.id, s.adapt.halfLifeDays)
        runCatching { repo.launch(app) }.onFailure { repo.scheduleReload(0) }
    }

    fun appInfo(app: AppEntry) = runCatching { repo.openAppInfo(app) }
    fun uninstall(app: AppEntry) = runCatching { repo.uninstall(app) }

    fun isPinned(app: AppEntry) = app.id in s.pinned

    fun togglePin(app: AppEntry) = store.update {
        it.copy(pinned = if (app.id in it.pinned) it.pinned - app.id else it.pinned + app.id)
    }

    /** Move a pinned app [delta] places within the pinned list. */
    fun movePin(app: AppEntry, delta: Int) = store.update { st ->
        val list = st.pinned.toMutableList()
        val i = list.indexOf(app.id)
        val j = i + delta
        if (i < 0 || j !in list.indices) return@update st
        list.add(j, list.removeAt(i))
        st.copy(pinned = list)
    }

    fun openCalendar() = calendar.openCalendar()
    fun openEvent(e: CalEvent) = calendar.openEvent(e)

    fun openUsageAccessSettings() {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

package app.mini.launcher.ui

import app.mini.launcher.settings.Settings
import app.mini.launcher.settings.SortMode

data class HomeApps<T>(val pinned: List<T>, val auto: List<T>) {
    val all: List<T> get() = pinned + auto
}

/**
 * Picks what the home screen shows (pure, so it's unit-testable):
 * pinned apps first (in the user's order), then the best-ranked other apps fill the remaining
 * [Settings.homeAppCount] slots. With no usage history yet, [all] order (alphabetical) pads it.
 */
fun <T> pickHomeApps(
    all: List<T>,
    id: (T) -> String,
    label: (T) -> String,
    scores: Map<String, Double>,
    s: Settings,
): HomeApps<T> {
    val byId = all.associateBy(id)
    val pinned = s.pinned.mapNotNull { byId[it] } // uninstalled pins are skipped (and come back if reinstalled)
    val pinnedIds = pinned.mapTo(HashSet(), id)
    val rest = all.filter { id(it) !in pinnedIds }
    val score = { t: T -> scores[id(t)] ?: 0.0 }
    val ranked = rest.filter { score(it) > 0.0 }.sortedByDescending(score) + rest.filter { score(it) <= 0.0 }
    var auto = ranked.take((s.homeAppCount - pinned.size).coerceAtLeast(0))
    if (s.sortMode == SortMode.MOST_USED_AZ) auto = auto.sortedBy { label(it).lowercase() }
    return HomeApps(pinned, auto)
}

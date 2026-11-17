package app.mini.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SortMode(val title: String) { MOST_USED("Most used"), RECENT("Recent"), MOST_USED_AZ("Most used, A–Z") }
enum class Adapt(val title: String, val halfLifeDays: Double) { FAST("Fast", 3.0), NORMAL("Normal", 7.0), SLOW("Slow", 21.0) }
enum class AppStyle(val title: String) { TEXT("Text"), ICON_TEXT("Icon + text"), ICONS("Icons") }
enum class TextSize(val title: String, val sp: Int) { S("S", 20), M("M", 24), L("L", 30) }
enum class Align(val title: String) { START("Left"), CENTER("Center"), END("Right") }
enum class PinnedPosition(val title: String) { TOP("Above most-used"), BOTTOM("Below most-used") }
enum class WallpaperSource(val title: String) { BING("Bing photo of the day"), PICSUM("Random photo"), FOLDER("My folder") }
enum class WallpaperTarget(val title: String) { HOME("Home"), LOCK("Lock"), BOTH("Both") }
/** How much the launcher darkens the wallpaper so text stays readable. */
enum class Dim(val title: String, val alpha: Float) { NONE("Off", 0f), LIGHT("Light", 0.3f), MEDIUM("Medium", 0.55f), STRONG("Strong", 0.75f) }
enum class ClockFormat(val title: String) { SYSTEM("System"), H24("24h"), H12("12h") }

/** CoinGecko id -> ticker symbol. Order here is the order shown on the home screen. */
val SUPPORTED_COINS = linkedMapOf(
    "bitcoin" to "BTC", "ethereum" to "ETH", "solana" to "SOL", "binancecoin" to "BNB",
    "ripple" to "XRP", "cardano" to "ADA", "dogecoin" to "DOGE", "tron" to "TRX",
)

data class Settings(
    // Home apps
    val homeAppCount: Int = 8,
    val sortMode: SortMode = SortMode.MOST_USED,
    val adapt: Adapt = Adapt.NORMAL,
    val appStyle: AppStyle = AppStyle.TEXT,
    val textSize: TextSize = TextSize.M,
    val align: Align = Align.START,
    val showHints: Boolean = true,
    /** Pinned app ids, in display order. Always on home; they use up slots first. */
    val pinned: List<String> = emptyList(),
    val pinnedPosition: PinnedPosition = PinnedPosition.TOP,
    // Search
    val autoLaunch: Boolean = true,
    // Clock
    val showClock: Boolean = true,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val showDate: Boolean = true,
    // Prices
    val showPrices: Boolean = true,
    val coins: Set<String> = setOf("bitcoin"),
    val priceRefreshMin: Int = 15,
    // Calendar
    val showCalendar: Boolean = true,
    val calendarDays: Int = 2,
    val calendarMaxEvents: Int = 4,
    // Wallpaper
    val wallpaperEnabled: Boolean = false,
    val wallpaperSource: WallpaperSource = WallpaperSource.BING,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.BOTH,
    val wallpaperWifiOnly: Boolean = false,
    /** Persisted SAF tree URI for [WallpaperSource.FOLDER]. */
    val wallpaperFolder: String? = null,
    val dim: Dim = Dim.MEDIUM,
) {
    /** Coins in display order. */
    val orderedCoins: List<String> get() = SUPPORTED_COINS.keys.filter { it in coins }
}

/**
 * Settings persisted in SharedPreferences, exposed as a [StateFlow]. One process-wide instance,
 * so a change on the settings page shows up on the home screen immediately.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    val current: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        write(next)
    }

    /** Resets everything except pinned apps (those are content, not preferences). */
    fun reset() = update { Settings(pinned = it.pinned, wallpaperFolder = it.wallpaperFolder) }

    private fun read(): Settings = with(prefs) {
        val d = Settings()
        Settings(
            homeAppCount = getInt("homeAppCount", d.homeAppCount),
            sortMode = enum("sortMode", d.sortMode),
            adapt = enum("adapt", d.adapt),
            appStyle = enum("appStyle", d.appStyle),
            textSize = enum("textSize", d.textSize),
            align = enum("align", d.align),
            showHints = getBoolean("showHints", d.showHints),
            pinned = getString("pinned", "")!!.split('\n').filter { it.isNotEmpty() },
            pinnedPosition = enum("pinnedPosition", d.pinnedPosition),
            autoLaunch = getBoolean("autoLaunch", d.autoLaunch),
            showClock = getBoolean("showClock", d.showClock),
            clockFormat = enum("clockFormat", d.clockFormat),
            showDate = getBoolean("showDate", d.showDate),
            showPrices = getBoolean("showPrices", d.showPrices),
            coins = getStringSet("coins", null)?.filter { it in SUPPORTED_COINS }?.toSet() ?: d.coins,
            priceRefreshMin = getInt("priceRefreshMin", d.priceRefreshMin),
            showCalendar = getBoolean("showCalendar", d.showCalendar),
            calendarDays = getInt("calendarDays", d.calendarDays),
            calendarMaxEvents = getInt("calendarMaxEvents", d.calendarMaxEvents),
            wallpaperEnabled = getBoolean("wallpaperEnabled", d.wallpaperEnabled),
            wallpaperSource = enum("wallpaperSource", d.wallpaperSource),
            wallpaperTarget = enum("wallpaperTarget", d.wallpaperTarget),
            wallpaperWifiOnly = getBoolean("wallpaperWifiOnly", d.wallpaperWifiOnly),
            wallpaperFolder = getString("wallpaperFolder", null),
            dim = enum("dim", d.dim),
        )
    }

    private fun write(s: Settings) = prefs.edit()
        .putInt("homeAppCount", s.homeAppCount)
        .putString("sortMode", s.sortMode.name)
        .putString("adapt", s.adapt.name)
        .putString("appStyle", s.appStyle.name)
        .putString("textSize", s.textSize.name)
        .putString("align", s.align.name)
        .putBoolean("showHints", s.showHints)
        .putString("pinned", s.pinned.joinToString("\n"))
        .putString("pinnedPosition", s.pinnedPosition.name)
        .putBoolean("autoLaunch", s.autoLaunch)
        .putBoolean("showClock", s.showClock)
        .putString("clockFormat", s.clockFormat.name)
        .putBoolean("showDate", s.showDate)
        .putBoolean("showPrices", s.showPrices)
        .putStringSet("coins", s.coins)
        .putInt("priceRefreshMin", s.priceRefreshMin)
        .putBoolean("showCalendar", s.showCalendar)
        .putInt("calendarDays", s.calendarDays)
        .putInt("calendarMaxEvents", s.calendarMaxEvents)
        .putBoolean("wallpaperEnabled", s.wallpaperEnabled)
        .putString("wallpaperSource", s.wallpaperSource.name)
        .putString("wallpaperTarget", s.wallpaperTarget.name)
        .putBoolean("wallpaperWifiOnly", s.wallpaperWifiOnly)
        .putString("wallpaperFolder", s.wallpaperFolder)
        .putString("dim", s.dim.name)
        .apply()

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}

package app.mini.launcher.widgets

import android.content.Context
import app.mini.launcher.settings.SUPPORTED_COINS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Quote(val symbol: String, val usd: Double, val change24hPct: Double?)
data class Quotes(val items: List<Quote>, val fetchedAt: Long)

/**
 * Crypto prices in USD from CoinGecko's free public endpoint (no API key).
 * Cached in SharedPreferences so the home screen shows the last value instantly, and only
 * re-fetched when older than the refresh interval — the launcher never polls in the background.
 */
class PriceRepository(context: Context) {

    private val prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE)

    /** Last fetched quotes, if they were fetched for exactly these [coins] (CoinGecko ids, display order). */
    fun cached(coins: List<String>): Quotes? {
        if (prefs.getString("coins", null) != coins.joinToString(",")) return null
        return prefs.getString("json", null)?.let { runCatching { parse(it, prefs.getLong("at", 0), coins) }.getOrNull() }
    }

    suspend fun refreshIfStale(coins: List<String>, maxAgeMs: Long, force: Boolean = false): Quotes? {
        if (coins.isEmpty()) return null
        val c = cached(coins)
        if (!force && c != null && System.currentTimeMillis() - c.fetchedAt < maxAgeMs) return c
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    "https://api.coingecko.com/api/v3/simple/price?ids=${coins.joinToString(",")}" +
                        "&vs_currencies=usd&include_24hr_change=true"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000; readTimeout = 5_000
                    setRequestProperty("Accept", "application/json")
                }
                val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val now = System.currentTimeMillis()
                val q = parse(body, now, coins) // validate before caching
                prefs.edit().putString("json", body).putLong("at", now)
                    .putString("coins", coins.joinToString(",")).apply()
                q
            }.getOrElse { c }
        }
    }

    private fun parse(json: String, at: Long, coins: List<String>): Quotes {
        val o = JSONObject(json)
        val items = coins.mapNotNull { id ->
            val sym = SUPPORTED_COINS[id] ?: id.uppercase()
            val c = o.optJSONObject(id) ?: return@mapNotNull null
            Quote(sym, c.getDouble("usd"), c.optDouble("usd_24h_change").takeUnless { it.isNaN() })
        }
        return Quotes(items, at)
    }
}

package app.mini.launcher.apps

import android.content.pm.LauncherApps
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazily loads (badged, so work apps get their briefcase) app icons as bitmaps, with an LRU cache. */
class IconCache(private val launcherApps: LauncherApps) {

    private val cache = LruCache<String, ImageBitmap>(300)

    fun peek(app: AppEntry, px: Int): ImageBitmap? = cache.get("${app.id}@$px")

    suspend fun load(app: AppEntry, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${app.id}@$px"
        cache.get(key) ?: runCatching {
            launcherApps.getActivityList(app.packageName, app.user)
                .firstOrNull { it.componentName == app.component }
                ?.getBadgedIcon(0)
                ?.toBitmap(px, px)
                ?.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }

    fun clear() = cache.evictAll()
}

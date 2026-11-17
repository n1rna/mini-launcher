package app.mini.launcher.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.WindowManager
import app.mini.launcher.settings.SettingsStore
import app.mini.launcher.settings.WallpaperSource
import app.mini.launcher.settings.WallpaperTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Fetches an image for "today" from the configured source and sets it as the wallpaper,
 * center-cropped to the screen. Each day has an [offset] that starts at 0 and is bumped by
 * "Change now", so the same day can step through alternatives:
 *  - Bing: today's photo, then the previous days' (Bing keeps 8),
 *  - Picsum: a photo seeded by date + offset,
 *  - Folder: the next photo in the folder (sorted by name), advancing one per day.
 */
object WallpaperChanger {

    data class State(
        val busy: Boolean = false,
        /** Day (epoch day) of the last successful change. */
        val day: Long = -1,
        val offset: Int = 0,
        val title: String? = null,
        val error: String? = null,
        val lastAttemptAt: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<State?>(null)

    fun state(context: Context): StateFlow<State> {
        if (_state.value == null) _state.value = load(context)
        @Suppress("UNCHECKED_CAST")
        return _state as StateFlow<State>
    }

    fun today(): Long = LocalDate.now().toEpochDay()

    /** For the daily job: change only if today's wallpaper hasn't been set yet. */
    suspend fun ensureToday(context: Context): Boolean {
        val st = state(context).value
        if (st.day == today()) return true
        return apply(context, offset = 0)
    }

    /** Settings changed (source/folder/target) or feature just enabled: today's image from the new config. */
    fun applyFreshAsync(context: Context) {
        scope.launch { apply(context.applicationContext, offset = 0) }
    }

    /** "Change now": the next alternative for today. */
    fun nextAsync(context: Context) {
        val st = state(context).value
        val next = if (st.day == today()) st.offset + 1 else 1
        scope.launch { apply(context.applicationContext, next) }
    }

    private suspend fun apply(context: Context, offset: Int): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prev = state(context).value
            _state.value = prev.copy(busy = true, error = null, lastAttemptAt = System.currentTimeMillis())
            val settings = SettingsStore.get(context).current
            val result = runCatching {
                val (w, h) = screenSize(context)
                val (bytes, title) = when (settings.wallpaperSource) {
                    WallpaperSource.BING -> bing(offset)
                    WallpaperSource.PICSUM -> picsum(offset, w, h)
                    WallpaperSource.FOLDER -> folder(context, settings.wallpaperFolder, offset)
                }
                setWallpaper(context, bytes, w, h, settings.wallpaperTarget)
                title
            }
            val next = result.fold(
                onSuccess = { title -> State(day = today(), offset = offset, title = title, lastAttemptAt = System.currentTimeMillis()) },
                onFailure = { e -> prev.copy(error = e.message ?: e.javaClass.simpleName, lastAttemptAt = System.currentTimeMillis()) },
            )
            _state.value = next
            save(context, next)
            result.isSuccess
        }
    }

    // ───────────────────────────── sources ─────────────────────────────

    private fun bing(offset: Int): Pair<ByteArray, String> {
        val json = String(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=${offset % 8}&n=1&mkt=en-US"))
        val img = JSONObject(json).getJSONArray("images").getJSONObject(0)
        val base = "https://www.bing.com" + img.getString("urlbase")
        // Bing publishes a portrait crop for phones; fall back to the 4K landscape one.
        val bytes = runCatching { get("${base}_1080x1920.jpg") }.getOrElse { get("${base}_UHD.jpg") }
        return bytes to img.optString("copyright").ifBlank { img.optString("title") }
    }

    private fun picsum(offset: Int, w: Int, h: Int): Pair<ByteArray, String> {
        // Cap the size (Picsum's limit is 5000px); the bitmap gets scaled to the screen anyway.
        val scale = minOf(1f, 1440f / w)
        val url = "https://picsum.photos/seed/mini-${today()}-$offset/${(w * scale).toInt()}/${(h * scale).toInt()}"
        return get(url) to "Random photo from Picsum (Unsplash)"
    }

    private fun folder(context: Context, treeUri: String?, offset: Int): Pair<ByteArray, String> {
        val tree = Uri.parse(treeUri ?: error("Choose a folder first"))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val images = ArrayList<Pair<String, String>>() // docId to name
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(2)?.startsWith("image/") == true) images += c.getString(0) to (c.getString(1) ?: "")
            }
        } ?: error("Folder is not accessible — choose it again")
        if (images.isEmpty()) error("No images in that folder")
        images.sortBy { it.second.lowercase() }
        val (docId, name) = images[pickIndex(today(), offset, images.size)]
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't read $name")
        return bytes to name
    }

    // ───────────────────────────── applying ─────────────────────────────

    private fun setWallpaper(context: Context, bytes: ByteArray, screenW: Int, screenH: Int, target: WallpaperTarget) {
        // Decode no bigger than needed (photos can be 50 MP).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) error("Not an image")
        val rotation = exifRotation(bytes)
        val (srcW, srcH) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
        var sample = 1
        while (srcW / (sample * 2) >= screenW && srcH / (sample * 2) >= screenH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn't decode image")
        if (rotation != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        // Crop to the screen's aspect ratio ourselves, so every Android version shows the same thing.
        val (l, t, r, b) = centerCrop(bmp.width, bmp.height, screenW, screenH)
        val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        val final = if (cropped.width > screenW) Bitmap.createScaledBitmap(cropped, screenW, screenH, true) else cropped

        val flags = when (target) {
            WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(context).setBitmap(final, null, true, flags)
    }

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Physical screen size in portrait (width < height). */
    private fun screenSize(context: Context): Pair<Int, Int> {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        return minOf(w, h) to maxOf(w, h)
    }

    private fun get(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────────────────── persistence ─────────────────────────────

    private fun load(context: Context): State {
        val p = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        return State(
            day = p.getLong("day", -1),
            offset = p.getInt("offset", 0),
            title = p.getString("title", null),
            error = p.getString("error", null),
            lastAttemptAt = p.getLong("lastAttemptAt", 0),
        )
    }

    private fun save(context: Context, s: State) {
        context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit()
            .putLong("day", s.day).putInt("offset", s.offset)
            .putString("title", s.title).putString("error", s.error)
            .putLong("lastAttemptAt", s.lastAttemptAt)
            .apply()
    }
}

/** Which photo of a folder of [count] to show: advances one per day, "Change now" steps further. */
internal fun pickIndex(day: Long, offset: Int, count: Int): Int = Math.floorMod(day + offset, count.toLong()).toInt()

/** Largest rect with the target aspect ratio, centered in a [w]×[h] image: (left, top, right, bottom). */
internal fun centerCrop(w: Int, h: Int, targetW: Int, targetH: Int): List<Int> {
    val target = targetW.toDouble() / targetH
    return if (w.toDouble() / h > target) {
        val cw = (h * target).toInt().coerceIn(1, w)
        val left = (w - cw) / 2
        listOf(left, 0, left + cw, h)
    } else {
        val ch = (w / target).toInt().coerceIn(1, h)
        val top = (h - ch) / 2
        listOf(0, top, w, top + ch)
    }
}

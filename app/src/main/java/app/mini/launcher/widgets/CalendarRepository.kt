package app.mini.launcher.widgets

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import java.util.Calendar

data class CalEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

/** Reads upcoming events from the on-device calendar provider (Google/Exchange/etc. all sync into it). */
class CalendarRepository(private val context: Context) {

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events from now until the end of the [days]-day window (1 = today only). */
    fun upcoming(days: Int, limit: Int): List<CalEvent> {
        if (!hasPermission()) return emptyList()
        val now = System.currentTimeMillis()
        val rangeEnd = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 86_400_000L) // all-day events are stored in UTC
            ContentUris.appendId(it, rangeEnd)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.DISPLAY_COLOR,
        )
        val out = ArrayList<CalEvent>()
        context.contentResolver.query(
            uri, projection,
            "${Instances.VISIBLE} = 1 AND ${Instances.STATUS} != ${Instances.STATUS_CANCELED}",
            null, "${Instances.BEGIN} ASC",
        )?.use { c ->
            val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                // all-day events: BEGIN/END are UTC midnights; shift into local time
                val begin = c.getLong(2) - if (allDay) tzOffset else 0
                val end = c.getLong(3) - if (allDay) tzOffset else 0
                if (end <= now || begin >= rangeEnd) continue
                out += CalEvent(c.getLong(0), c.getString(1) ?: "(no title)", begin, end, allDay, c.getInt(5))
            }
        }
        // All-day events first within a day, then timed ones
        return out.sortedWith(compareBy({ dayIndex(it.begin.coerceAtLeast(now)) }, { !it.allDay }, { it.begin }))
            .take(limit)
    }

    fun openEvent(e: CalEvent) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .also { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun dayIndex(t: Long): Int =
        Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
}

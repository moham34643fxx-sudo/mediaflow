package com.zdownloader.app.ui

import java.util.Calendar
import java.util.TimeZone

internal data class MediaDaySection<T>(val dayStart:Long?,val entries:List<T>)
internal enum class MediaDayKind { TODAY, YESTERDAY, DATE, UNKNOWN }

internal fun mediaDayStart(timestamp:Long,zone:TimeZone):Long = Calendar.getInstance(zone).apply {
    timeInMillis=timestamp
    set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
}.timeInMillis

internal fun <T> mediaDaySections(entries:List<T>,addedAt:(T)->Long?,zone:TimeZone):List<MediaDaySection<T>> {
    if (entries.isEmpty()) return emptyList()
    val cal = Calendar.getInstance(zone)
    fun dayStart(timestamp: Long): Long {
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    // Missing or nonpositive provider dates stay unknown, never substituted with modification time.
    return entries.sortedByDescending {addedAt(it)?.takeIf {stamp->stamp>0} ?: Long.MIN_VALUE}
        .groupBy {entry->addedAt(entry)?.takeIf {it>0}?.let {dayStart(it)}}
        .map {(day,rows)->MediaDaySection(day,rows)}
}

internal fun mediaDayKind(day:Long?,now:Long,zone:TimeZone):MediaDayKind {
    if(day==null) return MediaDayKind.UNKNOWN
    val cal = Calendar.getInstance(zone)
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.timeInMillis
    return when(day) {today->MediaDayKind.TODAY;yesterday->MediaDayKind.YESTERDAY;else->MediaDayKind.DATE}
}

package com.zdownloader.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MediaDateSectionsTest {
    private data class Entry(val id:String,val addedAt:Long?)
    private fun timestamp(zone:TimeZone,local:String):Long = SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT)
        .apply {timeZone=zone;isLenient=false}.parse(local)!!.time

    @Test fun newestDaysAndNewestEntriesComeFirstWithoutChangingInput() {
        val zone=TimeZone.getTimeZone("Africa/Cairo")
        val older=Entry("older",timestamp(zone,"2026-10-31 22:00"))
        val early=Entry("early",timestamp(zone,"2026-11-01 08:00"))
        val latest=Entry("latest",timestamp(zone,"2026-11-01 15:00"))
        val input=listOf(older,early,latest)
        val sections=mediaDaySections(input,{it.addedAt},zone)
        assertEquals(listOf(listOf("latest","early"),listOf("older")),sections.map {it.entries.map(Entry::id)})
        assertEquals(listOf(older,early,latest),input)
    }

    @Test fun localMidnightRatherThanUtcDeterminesTheDay() {
        val utc=TimeZone.getTimeZone("UTC")
        val cairo=TimeZone.getTimeZone("Africa/Cairo")
        val entry=Entry("near-midnight",timestamp(utc,"2026-11-01 22:30"))
        val cairoDay=mediaDaySections(listOf(entry),{it.addedAt},cairo).single().dayStart
        val utcDay=mediaDaySections(listOf(entry),{it.addedAt},utc).single().dayStart
        assertEquals(timestamp(cairo,"2026-11-02 00:00"),cairoDay)
        assertEquals(timestamp(utc,"2026-11-01 00:00"),utcDay)
        assertEquals(MediaDayKind.TODAY,mediaDayKind(cairoDay,timestamp(cairo,"2026-11-02 01:00"),cairo))
    }

    @Test fun yesterdaySurvivesDaylightSavingAndYearBoundaries() {
        val zone=TimeZone.getTimeZone("America/New_York")
        val springNow=timestamp(zone,"2026-03-09 08:00")
        val yesterday=timestamp(zone,"2026-03-08 00:00")
        assertEquals(23*60*60*1000L,timestamp(zone,"2026-03-09 00:00")-yesterday)
        assertEquals(MediaDayKind.YESTERDAY,mediaDayKind(yesterday,springNow,zone))
        assertEquals(MediaDayKind.YESTERDAY,mediaDayKind(timestamp(zone,"2025-12-31 00:00"),timestamp(zone,"2026-01-01 01:00"),zone))
        assertEquals(MediaDayKind.DATE,mediaDayKind(timestamp(zone,"2026-03-07 00:00"),springNow,zone))
    }

    @Test fun missingOrInvalidDatesStayUnknownAndAreListedLast() {
        val zone=TimeZone.getTimeZone("UTC")
        val known=Entry("known",timestamp(zone,"2026-11-01 00:00"))
        val input=listOf(Entry("missing",null),Entry("zero",0),known,Entry("negative",-1))
        val sections=mediaDaySections(input,{it.addedAt},zone)
        assertEquals(listOf("known"),sections.first().entries.map(Entry::id))
        assertNull(sections.last().dayStart)
        assertEquals(listOf("missing","zero","negative"),sections.last().entries.map(Entry::id))
        assertEquals(MediaDayKind.UNKNOWN,mediaDayKind(null,known.addedAt!!,zone))
        assertTrue(mediaDaySections(emptyList<Entry>(),{it.addedAt},zone).isEmpty())
    }
}

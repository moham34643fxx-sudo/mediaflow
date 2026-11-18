package com.zdownloader.app.ui

import com.zdownloader.app.domain.MediaSize
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SizeLabelCacheTest {
    @Test fun equalValuesShareOneLabelButEstimatesAndUnknownRemainDistinct() {
        val calls=AtomicInteger()
        val cache=SizeLabelCache({value->calls.incrementAndGet();value?.let {"${it.bytes}:${it.estimated}"} ?: "unknown"})
        assertEquals("1000:false",cache.label(MediaSize(1000,false)))
        assertEquals("1000:false",cache.label(MediaSize(1000,false)))
        assertEquals("1000:true",cache.label(MediaSize(1000,true)))
        assertEquals("unknown",cache.label(null));assertEquals("unknown",cache.label(null))
        assertEquals(3,calls.get())
    }
    @Test fun backgroundPreparationAndMainReadsSharePublishedValues() {
        val cache=SizeLabelCache({value->"size:${value?.bytes}"})
        val pool=Executors.newFixedThreadPool(4)
        try {
            val work=(0 until 32).map {pool.submit<String> {cache.label(MediaSize(12345,false))}}
            assertEquals(setOf("size:12345"),work.map {it.get()}.toSet())
            assertEquals(1,cache.entryCount)
        } finally {pool.shutdownNow()}
    }
    @Test fun labelsNeverCrossLocaleInstancesAndStorageIsBounded() {
        val arabic=SizeLabelCache({"ar:${it?.bytes}"},4)
        val english=SizeLabelCache({"en:${it?.bytes}"},4)
        assertNotEquals(arabic.label(MediaSize(1000,false)),english.label(MediaSize(1000,false)))
        repeat(100) {arabic.label(MediaSize(it.toLong(),false))}
        assertEquals(4,arabic.entryCount)
    }
}

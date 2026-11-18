package com.zdownloader.app.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** The native slot belongs to actual work, not the coroutine waiting for it. */
internal class NativeAdmission {
    private val slot=Semaphore(1)
    private val state=Any()
    private var ordinaryWaiters=0
    private var optional:OptionalLease?=null

    class Permit internal constructor(private val releaseSlot:()->Unit) {
        private val released=AtomicBoolean(false)
        fun release() {if(released.compareAndSet(false,true)) releaseSlot()}
    }

    suspend fun acquire():Permit {
        synchronized(state) {ordinaryWaiters++;optional?.cancel()}
        try {
            slot.acquire()
            return Permit {slot.release()}
        } finally {synchronized(state) {ordinaryWaiters--}}
    }

    suspend fun <T> withPermit(block:suspend ()->T):T {
        val permit=acquire()
        try {return block()} finally {permit.release()}
    }

    fun tryAcquireOptional():OptionalLease? = synchronized(state) {
        if(ordinaryWaiters>0||optional!=null||!slot.tryAcquire()) null
        else OptionalLease().also {optional=it}
    }

    inner class OptionalLease internal constructor() {
        private val stopRequested=CompletableDeferred<Unit>()
        val yieldRequested:Deferred<Unit> get()=stopRequested
        private var closed=false
        private var activeOperations=0

        fun tryStart():Permit? = synchronized(state) {
            // Two source decoders and one final-copy operation share one bundle.
            if(closed||activeOperations>=3) null
            else {activeOperations++;Permit {settleOperation()}}
        }

        fun finish() {synchronized(state) {closed=true;releaseIfSettledLocked()}}
        fun cancel() {synchronized(state) {yieldLocked()}}

        private fun yieldLocked() {
            closed=true
            stopRequested.complete(Unit)
            releaseIfSettledLocked()
        }

        private fun settleOperation() {
            synchronized(state) {activeOperations--;releaseIfSettledLocked()}
        }

        private fun releaseIfSettledLocked() {
            if(closed&&activeOperations==0&&optional===this) {
                optional=null
                slot.release()
            }
        }
    }
}

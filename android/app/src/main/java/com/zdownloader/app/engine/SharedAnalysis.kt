package com.zdownloader.app.engine

import kotlinx.coroutines.*

internal enum class AnalysisSubscriber { PREVIEW, DURABLE }

/** A process-scoped extraction has subscribers, not an Activity owner. Last departure cancels it. */
internal class SharedAnalysis<T>(private val scope:CoroutineScope) {
    private class Flight<T>(val task:Deferred<T>,val listeners:MutableMap<Any,(T)->Unit>,val holds:MutableSet<Any> = mutableSetOf())
    private val flights=mutableMapOf<String,Flight<T>>()
    /** A bounded, synchronous bridge across UI departure and durable queue attachment.
     * Does not start discovery, does not outlive the extraction, and is opt-in per caller.
     */
    fun retain(key:String,timeoutMillis:Long=15_000):AutoCloseable? {
        require(timeoutMillis in 1L..15_000L)
        val owner=Any()
        val flight=synchronized(flights) {
            flights[key]?.takeUnless {it.task.isCompleted||it.task.isCancelled}?.also {it.holds+=owner}
        } ?: return null
        fun release()=synchronized(flights) {
            flight.holds.remove(owner)
            if(flight.holds.isEmpty()&&flight.listeners.isEmpty()) {
                if(flights[key]===flight) flights.remove(key)
                flight.task.cancel()
            }
        }
        val expiry=scope.launch {delay(timeoutMillis);release()}
        flight.task.invokeOnCompletion {expiry.cancel();release()}
        return AutoCloseable {expiry.cancel();release()}
    }
    suspend fun run(key:String,onValue:(T)->Unit,load:suspend ((T)->Unit)->T):T=
        run(key,onValue,AnalysisSubscriber.PREVIEW,load)
    suspend fun run(key:String,onValue:(T)->Unit,subscriberKind:AnalysisSubscriber,load:suspend ((T)->Unit)->T):T {
        val subscriber=Any()
        val flight=synchronized(flights) {
            // Lazy producers are joinable before starting; cancelling producers are not.
            val selected=flights[key]?.takeUnless {it.task.isCompleted||it.task.isCancelled} ?: run {
                val listeners=mutableMapOf<Any,(T)->Unit>()
                val task=scope.async(start=CoroutineStart.LAZY) {load {value ->
                    val callbacks=synchronized(flights) {listeners.values.toList()}
                    callbacks.forEach {runCatching {it(value)}}
                }}
                Flight(task,listeners).also {flights[key]=it}
            }
            // Subscribe in the same critical section as lookup. Otherwise the
            // previous last waiter could cancel the flight between these steps.
            selected.listeners[subscriber]=onValue
            // Attachment and lease adoption must be atomic; releasing before
            // subscription could cancel the producer in the zero-subscriber gap.
            if(subscriberKind==AnalysisSubscriber.DURABLE) selected.holds.clear()
            selected
        }
        try {return flight.task.await()}
        finally {synchronized(flights) {
            flight.listeners.remove(subscriber)
            if(flight.listeners.isEmpty()&&flight.holds.isEmpty()) {
                if(flights[key]===flight) flights.remove(key)
                flight.task.cancel()
            }
        }}
    }
}

package com.zdownloader.app.engine

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A cancelled coroutine is not proof that FFmpeg/Python has released its files. */
internal object NativeWork {
    private class Entry(val directory:File,val reader:ReadOwner?,val finished:CompletableDeferred<Unit> = CompletableDeferred())
    private val leases = ConcurrentHashMap<Any, Entry>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    class ReadOwner
    class Lease internal constructor(private val key: Any) {
        fun release() { leases.remove(key)?.finished?.complete(Unit) }
    }
    private fun normalizedPath(directory:File)=directory.canonicalFile.normalize()
    fun acquire(directory: File): Lease = register(directory,null)
    fun acquireRead(directory:File,owner:ReadOwner):Lease = register(directory,owner)
    private fun register(directory:File,reader:ReadOwner?):Lease {
        val key = Any()
        leases[key] = Entry(normalizedPath(directory),reader)
        return Lease(key)
    }
    /** Reserve before enqueueing; a cancelled queued task must never start later. */
    class Reservation(directory: File) {
        private val lease=acquire(directory)
        private val state=java.util.concurrent.atomic.AtomicInteger(0)
        fun start():Boolean=state.compareAndSet(0,1)
        fun cancelPending() {if(state.compareAndSet(0,2)) lease.release()}
        fun finish() {if(state.getAndSet(2)!=2) lease.release()}
    }
    private fun pending(directory:File,reader:ReadOwner? = null):List<CompletableDeferred<Unit>> {
        val path=normalizedPath(directory).path
        return leases.values.filter {
            val owned=it.directory.path
            (reader==null||it.reader!==reader)&&
                (owned==path||owned.startsWith(path+File.separator)||path.startsWith(owned+File.separator))
        }.map {it.finished}
    }
    fun busy(directory: File) = pending(directory).isNotEmpty()
    suspend fun awaitIdle(directory: File) = awaitPending(directory,null)
    // Only a live attempt's read-only leases may be excluded; writers always block.
    suspend fun awaitOtherWork(directory:File,reader:ReadOwner) = awaitPending(directory,reader)
    private suspend fun awaitPending(directory:File,reader:ReadOwner?) {
        while (true) {
            val pending = pending(directory,reader)
            if (pending.isEmpty()) return
            pending.forEach { it.await() }
        }
    }
    fun afterIdle(directory: File, action: () -> Unit) {
        cleanup.launch { awaitIdle(directory); action() }
    }
}

/** Also cancels waiting for a mutex or a native callback when the user presses Pause. */
internal suspend fun <T> withControlCancellation(control: TransferControl, block: suspend CoroutineScope.() -> T): T = coroutineScope {
    control.check()
    val owner = currentCoroutineContext().job
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        control.stopRequested.await()
        owner.cancel(CancellationException("Transfer stopped"))
    }
    try { block() } finally { watcher.cancel() }
}

package com.zdownloader.app.engine

import java.io.DataOutputStream
import java.io.File

internal data class ContainerClock(val numerator:Long,val denominator:Long)
internal data class ContainerInventory(val clock:ContainerClock,val packets:Long,val end:Long)

/** Fixed-size binary records and a fixed hash buffer; no per-track sample arrays. */
internal class ContainerPacketCatalog(file:File,private val checkpoint:()->Unit):AutoCloseable {
    private val output=DataOutputStream(file.outputStream().buffered())
    var packets=0L
        private set

    fun append(media:BoundedMediaFile,packet:Packet) {
        checkpoint()
        media.need(packets<2_000_000&&packet.pts>=0&&packet.duration>0&&packet.size in 1..8L*1024*1024,"packet_contract")
        media.bounds(packet.offset,packet.size)
        output.writeLong(packet.pts);output.writeLong(packet.duration);output.writeLong(packet.size)
        output.writeBoolean(packet.sync)
        output.write(media.hash(packet.offset,packet.size));packets++
    }
    data class Packet(val pts:Long,val duration:Long,val size:Long,val offset:Long,val sync:Boolean)
    override fun close() {output.close()}
}

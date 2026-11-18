package com.zdownloader.app.engine

/** Close the already-registered callback even if registering its companion fails. */
internal class NetworkCallbackRegistration(register:()->Unit,private val unregister:()->Unit):AutoCloseable {
    init {register()}
    override fun close() {unregister()}
}

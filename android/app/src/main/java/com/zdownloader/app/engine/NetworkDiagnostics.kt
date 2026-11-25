package com.zdownloader.app.engine

import android.content.Context
import android.net.*
import android.os.Build
import com.zdownloader.app.domain.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.*
import java.net.Proxy
import javax.net.ssl.SSLException

/** No URLs, addresses, headers, credentials or exception messages are persisted. */
object NetworkDiagnostics {
    @Volatile private var app: android.app.Application? = null
    @Volatile private var networkJson = "{}"
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val writeFailures = java.util.concurrent.atomic.AtomicLong()
    private var observed: Network? = null
    @Volatile internal var tlsFailureEvidenceCaptureForTests=false
    private val dropped = java.util.concurrent.atomic.AtomicLong(0)
    private val writer=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(128),java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    init {
        writer.setRejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }
    }
    fun droppedCount(): Long = dropped.get()
    /** Observational failures must never change the outcome of a media operation. */
    internal fun captureObservation(observe:()->Unit) {
        if(!PerformanceCapture.active) return
        try {observe()} catch(_:Exception) {writeFailures.incrementAndGet()}
    }
    fun httpError(status:Int):ErrorCode?=when(status) {
        401->ErrorCode.AUTHENTICATION_REQUIRED;403->ErrorCode.HTTP_FORBIDDEN;404->ErrorCode.VIDEO_NOT_FOUND
        408->ErrorCode.READ_TIMEOUT;410->ErrorCode.MEDIA_URL_EXPIRED;429->ErrorCode.RATE_LIMITED
        in 500..599->ErrorCode.SERVER_UNAVAILABLE;in 400..499->ErrorCode.HTTP_ERROR;else->null
    }
    fun connectionIssue():ErrorCode? {
        val context=app ?: return null
        val manager=context.getSystemService(ConnectivityManager::class.java)
        return connectionIssue(manager)
    }
    /** A validated VPN can outlive its physical bearer during radio loss.
     * The bearer must exist, but need not independently validate outside the VPN:
     * VPN/lockdown policy can prevent that probe while the active route works. */
    fun connectionIssue(manager:ConnectivityManager):ErrorCode? {
        val caps=manager.getNetworkCapabilities(manager.activeNetwork) ?: return ErrorCode.NETWORK_UNAVAILABLE
        return NetworkRoutePolicy.issue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),underlyingPresent(manager),underlyingReady(manager))
    }
    private fun underlyingPresent(cm:ConnectivityManager)=cm.allNetworks.any {network->cm.getNetworkCapabilities(network)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }==true}
    private fun underlyingReady(cm:ConnectivityManager)=cm.allNetworks.any {network->cm.getNetworkCapabilities(network)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }==true}
    fun start(context: Context) {
        if (app != null) return
        app = context.applicationContext as android.app.Application
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                if (observed != value) {
                    observed = value
                    generation.incrementAndGet()
                    // Drop idle pooled sockets on a route change; active transfers retain resumable data.
                    Transfer.networkClient.connectionPool.evictAll()
                }
                snapshot(cm, value)
            }
            override fun onLost(value: Network) { if (observed == value) { observed = null; generation.incrementAndGet(); networkJson = "{\"available\":false}";record("android","network_lost",ErrorCode.NETWORK_UNAVAILABLE) } }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) { snapshot(cm,value) }
            override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) { snapshot(cm,value) }
        })
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),object:ConnectivityManager.NetworkCallback() {
            override fun onLost(network:Network) {cm.activeNetwork?.let {snapshot(cm,it)}}
            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) {cm.activeNetwork?.let {snapshot(cm,it)}}
        })
        cm.activeNetwork?.let { snapshot(cm,it) }
    }
    private fun snapshot(cm: ConnectivityManager, value: Network) {
        val c = cm.getNetworkCapabilities(value)
        val p = cm.getLinkProperties(value)
        val snapshot = JSONObject().put("available",true)
            .put("validated",c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
            .put("captive",c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true)
            .put("wifi",c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
            .put("cellular",c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)
            .put("vpn",c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true)
            .put("proxy",p?.httpProxy!=null).put("dnsServers",p?.dnsServers?.size ?: 0)
             .put("underlyingValidated",underlyingReady(cm))
             .put("underlyingInternetPresent",underlyingPresent(cm))
            .put("privateDns",Build.VERSION.SDK_INT>=28 && p?.isPrivateDnsActive==true)
        networkJson = snapshot.toString()
        record("android","network",connectionIssue(cm))
    }
    fun record(stack: String, stage: String, code: ErrorCode?, elapsed: Long? = null, operation:String?=null, attempt:Int?=null, httpStatus:Int?=null, details:JSONObject?=null) {
        // Copy the volatile snapshot so concurrent network callbacks cannot mutate a logged row.
        val snapshot = JSONObject(networkJson)
        val row = JSONObject().put("time",System.currentTimeMillis()).put("stack",stack)
            .put("stage",stage).put("code",code?.name ?: "OK").put("elapsedMs",elapsed).put("network",snapshot).put("operation",operation).put("dropped",dropped.get())
            .put("networkGeneration",generation.get()).put("attempt",attempt).put("httpStatus",httpStatus)
            .put("cancelled",stage=="cancelled").put("recoverable",code?.recoverable ?: false).put("writeFailures",writeFailures.get()).put("details",details)
        PerformanceCapture.decorate(row)
        if(com.zdownloader.app.BuildConfig.DEBUG) android.util.Log.i("ZNetwork",row.toString())
        app?.let { context -> writer.execute {runCatching {
            val file=File(context.filesDir,"network-diagnostics.jsonl")
            if(file.length()>256*1024) { val previous=File(context.filesDir,"network-diagnostics.previous.jsonl");previous.delete();file.renameTo(previous) }
            file.appendText("$row\n")
        }.onFailure {writeFailures.incrementAndGet()} } }
    }
    /** Snapshot on the existing writer, never race rotation or block processing threads. */
    suspend fun exportSnapshot(): List<String> {
        check(com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS) {"Internal diagnostics unavailable"}
        val context=app ?: throw java.io.IOException("Diagnostics not initialized")
        val result=kotlinx.coroutines.CompletableDeferred<List<String>>()
        // execute() uses a drop handler: submit directly so a full queue fails visibly.
        if(!writer.queue.offer(java.util.concurrent.FutureTask<Unit>({
            try {
                val rows=listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl")
                    .flatMap {name->snapshotRows(File(context.filesDir,name))}
                result.complete(rows)
            } catch(error:Exception) {result.completeExceptionally(error)}
        },Unit))) throw java.io.IOException("Diagnostics busy")
        writer.prestartCoreThread()
        return kotlinx.coroutines.withTimeout(10_000) {result.await()}
    }
    private fun snapshotRows(file:File):List<String> {
        if(!file.isFile) return emptyList()
        if(file.length()>320*1024) throw java.io.IOException("Diagnostics snapshot exceeds size limit")
        return file.readText(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.toList()
    }
    fun classify(error: Throwable, stage: String): ErrorCode {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var next:Throwable? = error
        while(next != null && seen.add(next)) {
        if(next is PolicyAddressException) return ErrorCode.URL_POLICY_REJECTED
        val classified = when(val error=next) {
        is UnknownHostException -> ErrorCode.DNS_FAILED
        is javax.net.ssl.SSLPeerUnverifiedException -> ErrorCode.TLS_FAILED
        is SSLException -> {
            val causes=generateSequence(error as Throwable?) {it.cause}.take(12).toList()
            val message=error.message.orEmpty().lowercase(java.util.Locale.ROOT)
            when {
                causes.any {it is java.security.cert.CertificateException}||message.contains("certificate")||message.contains("hostname") -> ErrorCode.TLS_FAILED
                causes.any {it is SocketTimeoutException} -> ErrorCode.TLS_TIMEOUT
                causes.any {it is java.io.EOFException}||listOf("unexpected eof","connection reset","connection closed","closed connection").any {message.contains(it)} -> ErrorCode.DOWNLOAD_INTERRUPTED
                else -> ErrorCode.TLS_FAILED
            }
        }
        is SocketTimeoutException -> if(stage=="tcp") ErrorCode.CONNECT_TIMEOUT else if(stage=="tls") ErrorCode.TLS_TIMEOUT else ErrorCode.READ_TIMEOUT
        is ConnectException -> if(error.message.orEmpty().contains("ECONNREFUSED",true)||error.message.orEmpty().contains("refused",true)) ErrorCode.CONNECTION_REFUSED else ErrorCode.CONNECTION_FAILED
        is NoRouteToHostException -> ErrorCode.NETWORK_UNAVAILABLE
        is SocketException -> ErrorCode.DOWNLOAD_INTERRUPTED
        else -> null
        }
        if(classified!=null) return classified
        next=next.cause
        }
        return ErrorCode.NETWORK_UNAVAILABLE
    }
    private fun safeTlsFailureEvidence(host:String,error:Throwable):JSONObject {
        val report=JSONObject()
        val safeHost=Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
        if(host.length in 1..253&&host.matches(safeHost)&&!host.matches(Regex("[0-9.]+")))
            report.put("endpoint_host",host.lowercase(java.util.Locale.ROOT))
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var current:Throwable?=error
        while(current!=null&&seen.size<12&&seen.add(current)) {
            if(current is java.security.cert.CertPathValidatorException) {
                val path=current.certPath?.certificates.orEmpty()
                if(current.index in -1..32) report.put("certificate_path_index",current.index)
                report.put("certificate_path_length",path.size.coerceAtMost(32))
                val indexes=(0 until minOf(path.size,8)).toMutableList()
                if(current.index in 8 until path.size) indexes+=current.index
                val certificates=org.json.JSONArray()
                for(index in indexes) runCatching {
                    val certificate=path[index] as? java.security.cert.X509Certificate ?: return@runCatching
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    certificates.put(JSONObject().put("path_index",index)
                        .put("sha256",digest.joinToString("") {"%02X".format(it)}))
                }
                report.put("certificates_sha256",certificates)
                break
            }
            current=current.cause
        }
        return report
    }
    class Trace(val operation:String=java.util.UUID.randomUUID().toString()) : EventListener() {
        @Volatile var stage="request"
        private val started=System.nanoTime()/1_000_000
        private var phaseStarted=started
        private var attempt=0
        private fun phase(name:String) { if(stage!=name) { record("okhttp",stage,null,System.nanoTime()/1_000_000-phaseStarted,operation,attempt);stage=name;phaseStarted=System.nanoTime()/1_000_000 } }
        override fun dnsStart(call:Call,domainName:String) {phase("dns")}
        override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>) {phase("tcp")}
        override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy) {attempt++;phase("tcp")}
        override fun secureConnectStart(call:Call) {phase("tls")}
        override fun secureConnectEnd(call:Call,handshake:Handshake?) {phase("http")}
        override fun connectionAcquired(call:Call,connection:Connection) {
            record("okhttp","connection_acquired",null,operation=operation,attempt=attempt,
                details=JSONObject().put("pooled",attempt==0).put("protocol",connection.protocol().toString())
                    .put("proxy_type",connection.route().proxy.type().name)
                    .put("tls_version",connection.handshake()?.tlsVersion?.javaName))
        }
        override fun requestHeadersStart(call:Call) {AnalysisTrace.mark(operation,"first_network_request");phase("http")}
        override fun responseHeadersEnd(call:Call,response:Response) { record("okhttp","http_${response.code}",httpCode(response.code),operation=operation,attempt=attempt,httpStatus=response.code);phase("body") }
        override fun connectFailed(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?,ioe:IOException) {record("okhttp",stage,classify(ioe,stage),operation=operation)}
        override fun callFailed(call:Call,ioe:IOException) {
            val code=if(call.isCanceled()) ErrorCode.DOWNLOAD_INTERRUPTED else classify(ioe,stage)
            val details=if(tlsFailureEvidenceCaptureForTests&&com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS&&code==ErrorCode.TLS_FAILED)
                runCatching {safeTlsFailureEvidence(call.request().url.host,ioe)}.getOrNull() else null
            record("okhttp",if(call.isCanceled()) "cancelled" else stage,code,System.nanoTime()/1_000_000-started,operation,details=details)
        }
        override fun callEnd(call:Call) {record("okhttp","complete",null,System.nanoTime()/1_000_000-started,operation)}
    }
    fun httpCode(code: Int): ErrorCode? = when(code) {
        in 200..399 -> null
        401 -> ErrorCode.AUTHENTICATION_REQUIRED
        403 -> ErrorCode.HTTP_FORBIDDEN
        404 -> ErrorCode.VIDEO_NOT_FOUND
        408 -> ErrorCode.READ_TIMEOUT
        410 -> ErrorCode.MEDIA_URL_EXPIRED
        429 -> ErrorCode.RATE_LIMITED
        in 500..599 -> ErrorCode.SERVER_UNAVAILABLE
        else -> ErrorCode.HTTP_ERROR
    }
}

/** Python uses Android's per-URI ProxySelector, including PAC/exclusion decisions. */
object AndroidNetworkBridge {
    internal val proxyHosts=ThreadLocal<Set<String>>()
    internal fun configuredProxyHosts(url:String):Set<String> = ProxySelector.getDefault()?.select(URI(url)).orEmpty().mapNotNull { (it.address() as? InetSocketAddress)?.hostString }.toSet()
    internal fun selector(): ProxySelector {
        val system = ProxySelector.getDefault()
        return object: ProxySelector() {
            override fun select(uri:URI):List<Proxy> {
                val routes=system?.select(uri).orEmpty().ifEmpty {listOf(Proxy.NO_PROXY)}
                proxyHosts.set(routes.mapNotNull {(it.address() as? InetSocketAddress)?.hostString}.filter {it!=uri.host}.toSet())
                return routes
            }
            override fun connectFailed(uri:URI,address:SocketAddress,error:IOException) {system?.connectFailed(uri,address,error)}
        }
    }
    @JvmStatic fun proxyFor(url:String):String {
        val selected=ProxySelector.getDefault()?.select(URI(url))?.firstOrNull() ?: return ""
        if(selected.type()==Proxy.Type.DIRECT) return ""
        val address=selected.address() as? InetSocketAddress ?: return ""
        val scheme=if(selected.type()==Proxy.Type.SOCKS) "socks5" else "http"
        val host=address.hostString.let {if(':' in it) "[$it]" else it}
        return "$scheme://$host:${address.port}"
    }
}
class PolicyAddressException:UnknownHostException("Address rejected by media URL policy")

package com.zdownloader.app.adblock

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.IDN
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

private const val MAX_COSMETIC_SELECTORS = 256

internal fun prioritizeCosmeticSelectors(
    elementMatches: List<String>,
    pageSelectors: List<String>
): Sequence<String> =
    // A full page-wide batch must not displace selectors known to match this DOM.
    (elementMatches.asSequence() + pageSelectors.asSequence()).distinct().take(MAX_COSMETIC_SELECTORS)

internal data class BrowserAdblockState(
    val enabled: Boolean = true,
    val excludedHosts: Set<String> = emptySet(),
    val engineReady: Boolean = false,
    val loading: Boolean = true,
    val nativeAvailable: Boolean = true,
    val networkRules: Int = 0,
    val cosmeticRules: Int = 0,
    val lastUpdateMillis: Long = 0L
)

internal object BrowserAdblock {
    internal data class RuleDecision(
        val blocked: Boolean,
        val ruleId: String? = null,
        val attribution: String = "none"
    )

    private const val PREFS_NAME = "mediaflow_browser_adblock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXCLUDED_HOSTS = "excluded_hosts"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val SNAPSHOT_MAGIC = 0x4D464142 // MFAB
    private const val SNAPSHOT_VERSION = 1
    private const val MAX_COMPILED_BYTES = 32 * 1024 * 1024
    private const val MAX_STATS_BYTES = 16 * 1024
    private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    private const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
    private const val UPDATE_WORK_NAME = "mediaflow-adblock-filter-update"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationStarted = AtomicBoolean(false)
    private val blockedRequests = AtomicLong(0L)
    private val blockedPopups = AtomicLong(0L)
    private val blockedRedirects = AtomicLong(0L)
    private val activeYoutubeAdHost = AtomicReference<String?>(null)
    private val engineLock = Any()
    private val cosmeticResourceCache = LinkedHashMap<String, CosmeticBaseResources>()
    private val cosmeticQueryCounts = LinkedHashMap<String, Int>()
    private val stateFlow = MutableStateFlow(BrowserAdblockState())
    val state: StateFlow<BrowserAdblockState> = stateFlow.asStateFlow()

    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var applicationContext: Context? = null
    private var activeHandle = 0L

    fun initialize(context: Context) {
        val app = context.applicationContext
        applicationContext = app
        if (!initializationStarted.compareAndSet(false, true)) return

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        publishPreferences(prefs)
        prefs.registerOnSharedPreferenceChangeListener { changed, _ -> publishPreferences(changed) }

        scope.launch {
            loadSnapshot(app)
            scheduleFilterUpdates(app)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        preferences?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        if (!enabled && activeYoutubeAdHost.getAndSet(null) != null) {
            BrowserAdblockDiagnostics.playerState(active = false)
        }
        stateFlow.value = stateFlow.value.copy(enabled = enabled)
    }

    fun setHostExcluded(context: Context, host: String, excluded: Boolean) {
        initialize(context)
        val normalized = normalizeHost(host) ?: return
        val prefs = preferences ?: return
        val hosts = prefs.getStringSet(KEY_EXCLUDED_HOSTS, emptySet()).orEmpty().toMutableSet()
        if (excluded) hosts.add(normalized) else hosts.remove(normalized)
        prefs.edit().putStringSet(KEY_EXCLUDED_HOSTS, hosts).apply()
        if (excluded && isYoutubeHost(normalized) &&
            activeYoutubeAdHost.compareAndSet(normalized, null)
        ) {
            BrowserAdblockDiagnostics.playerState(active = false)
        }
        stateFlow.value = stateFlow.value.copy(excludedHosts = hosts.toSet())
    }

    fun isEnabledForPage(pageUrl: String?): Boolean {
        val host = hostFromUrl(pageUrl) ?: return false
        val current = stateFlow.value
        return current.enabled && current.engineReady && host !in current.excludedHosts
    }

    fun youtubeAdHandlingEnabled(pageUrl: String?): Boolean {
        val host = hostFromUrl(pageUrl) ?: return false
        return isYoutubeHost(host) && isEnabledForPage(pageUrl)
    }

    fun setYoutubeAdActive(pageUrl: String?, active: Boolean) {
        val host = hostFromUrl(pageUrl) ?: return
        if (!isYoutubeHost(host)) return
        val shouldBeActive = active && isEnabledForPage(pageUrl)
        if (shouldBeActive) {
            val previous = activeYoutubeAdHost.getAndSet(host)
            if (previous != host) BrowserAdblockDiagnostics.playerState(active = true)
        } else if (activeYoutubeAdHost.compareAndSet(host, null)) {
            BrowserAdblockDiagnostics.playerState(active = false)
        }
    }

    fun shouldBlock(
        pageUrl: String?,
        requestUrl: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): Boolean = evaluateRequest(pageUrl, requestUrl, sourceUrl, requestType, method).blocked

    fun evaluateRequest(
        pageUrl: String?,
        requestUrl: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): RuleDecision {
        if (!isEnabledForPage(pageUrl) || requestUrl.length > 8_192 ||
            sourceUrl.length > 8_192
        ) {
            return RuleDecision(blocked = false, attribution = "not_evaluated")
        }
        val result = synchronized(engineLock) {
            val handle = activeHandle
            if (handle == 0L) {
                RuleDecision(blocked = false, attribution = "engine_not_ready")
            } else {
                evaluateNative(handle, requestUrl, sourceUrl, requestType, method)
            }
        }
        if (result.blocked) blockedRequests.incrementAndGet()
        return result
    }

    fun blockedRequestCount(): Long = blockedRequests.get()
    fun blockedPopupCount(): Long = blockedPopups.get()
    fun blockedRedirectCount(): Long = blockedRedirects.get()
    fun resetSessionCounts() {
        blockedRequests.set(0L)
        blockedPopups.set(0L)
        blockedRedirects.set(0L)
    }
    fun recordBlockedPopup() {
        blockedPopups.incrementAndGet()
        BrowserAdblockDiagnostics.popupBlocked()
    }

    fun shouldBlockRedirect(pageUrl: String?, targetUrl: String, sourceUrl: String, method: String): Boolean {
        return evaluateRedirect(pageUrl, targetUrl, sourceUrl, method).blocked
    }

    fun evaluateRedirect(
        pageUrl: String?,
        targetUrl: String,
        sourceUrl: String,
        method: String
    ): RuleDecision {
        if (!isEnabledForPage(pageUrl) || targetUrl.length > 8_192 ||
            sourceUrl.length > 8_192
        ) {
            return RuleDecision(blocked = false, attribution = "not_evaluated")
        }
        val result = synchronized(engineLock) {
            val handle = activeHandle
            if (handle == 0L) {
                RuleDecision(blocked = false, attribution = "engine_not_ready")
            } else {
                evaluateNative(handle, targetUrl, sourceUrl, "document", method)
            }
        }
        if (result.blocked) blockedRedirects.incrementAndGet()
        return result
    }

    fun pageStarted(url: String) {
        if (activeYoutubeAdHost.getAndSet(null) != null) {
            BrowserAdblockDiagnostics.playerState(active = false)
        }
        synchronized(engineLock) {
            cosmeticResourceCache.remove(url)
            cosmeticQueryCounts.remove(url)
        }
    }

    private fun parseNativeRuleDecision(json: String): RuleDecision {
        val result = JSONObject(json)
        val ruleId = result.optString("matchedRuleId")
            .takeUnless { it.isBlank() || it == "null" }
        return RuleDecision(
            blocked = result.optBoolean("blocked", false),
            ruleId = ruleId,
            attribution = result.optString("ruleAttribution", "native_match")
        )
    }

    private fun evaluateNative(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): RuleDecision {
        val diagnosticsEnabled = BrowserAdblockDiagnostics.isEnabledForQa()
        if (diagnosticsEnabled) {
            val detailed = runCatching {
                NativeAdblock.nativeShouldBlockDetailed(
                    handle,
                    url,
                    sourceUrl,
                    requestType,
                    method
                )?.let(::parseNativeRuleDecision)
            }.getOrNull()
            if (detailed != null) return detailed
        }

        return RuleDecision(
            blocked = runCatching {
                NativeAdblock.nativeShouldBlock(handle, url, sourceUrl, requestType, method)
            }.getOrDefault(false),
            attribution = if (diagnosticsEnabled) {
                "native_diagnostic_unavailable"
            } else {
                "native_filter"
            }
        )
    }

    fun cosmeticSelectors(
        pageUrl: String,
        frameUrl: String,
        classesJson: String,
        idsJson: String
    ): String {
        val pageHost = hostFromUrl(pageUrl)
        val frameHost = hostFromUrl(frameUrl)
        if (pageHost == null || pageHost != frameHost ||
            classesJson.length > 32_768 || idsJson.length > 32_768
        ) return emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.INVALID_CONTEXT)
        val current = stateFlow.value
        if (!current.enabled || pageHost in current.excludedHosts) {
            return emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.FILTER_DISABLED)
        }
        if (!current.engineReady) return emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.ENGINE_NOT_READY)

        return synchronized(engineLock) {
            val handle = activeHandle
            if (handle == 0L) return@synchronized emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.ENGINE_NOT_READY)
            val calls = cosmeticQueryCounts[pageUrl] ?: 0
            if (calls >= MAX_COSMETIC_QUERIES_PER_PAGE) {
                return@synchronized emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.QUERY_LIMIT)
            }
            if (pageUrl !in cosmeticQueryCounts &&
                cosmeticQueryCounts.size >= MAX_CACHED_COSMETIC_PAGES
            ) {
                cosmeticQueryCounts.remove(cosmeticQueryCounts.keys.first())
            }
            cosmeticQueryCounts[pageUrl] = calls + 1
            runCatching {
                val resources = cosmeticResourceCache[pageUrl] ?: run {
                    val rawResources = NativeAdblock.nativeCosmeticResources(handle, pageUrl)
                    if (rawResources == null || rawResources.length > MAX_STATS_BYTES * 4) {
                        return@runCatching emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.BASE_UNAVAILABLE)
                    }
                    val parsed = JSONObject(rawResources)
                    val hide = parsed.optJSONArray("hide_selectors") ?: JSONArray()
                    val hideSelectors = buildList {
                        repeat(minOf(hide.length(), MAX_COSMETIC_SELECTORS)) { index ->
                            hide.optString(index).takeIf(::isSafeSelector)?.let(::add)
                        }
                    }
                    val exceptions = parsed.optJSONArray("exceptions") ?: JSONArray()
                    CosmeticBaseResources(
                        hideSelectors = hideSelectors,
                        exceptionsJson = exceptions.toString(),
                        generichide = parsed.optBoolean("generichide", false)
                    ).also {
                        if (cosmeticResourceCache.size >= MAX_CACHED_COSMETIC_PAGES) {
                            cosmeticResourceCache.remove(cosmeticResourceCache.keys.first())
                        }
                        cosmeticResourceCache[pageUrl] = it
                    }
                }
                val elementMatches = mutableListOf<String>()

                val lookup = if (resources.generichide) {
                    BrowserAdblockDiagnostics.CosmeticLookup.GENERIC_DISABLED
                } else {
                    val generic = NativeAdblock.nativeHiddenSelectors(
                        handle,
                        pageUrl,
                        classesJson,
                        idsJson,
                        resources.exceptionsJson
                    )?.takeIf { it.length <= MAX_COSMETIC_JSON_BYTES }
                    if (generic != null) {
                        val genericSelectors = JSONArray(generic)
                        repeat(minOf(genericSelectors.length(), MAX_COSMETIC_SELECTORS)) { index ->
                            genericSelectors.optString(index).takeIf(::isSafeSelector)?.let(elementMatches::add)
                        }
                        if (genericSelectors.length() == 0) {
                            BrowserAdblockDiagnostics.CosmeticLookup.NO_GENERIC_MATCH
                        } else {
                            BrowserAdblockDiagnostics.CosmeticLookup.GENERIC_MATCH
                        }
                    } else {
                        BrowserAdblockDiagnostics.CosmeticLookup.GENERIC_UNAVAILABLE
                    }
                }
                BrowserAdblockDiagnostics.cosmetic(lookup)

                val output = JSONArray()
                var totalBytes = 0
                prioritizeCosmeticSelectors(elementMatches, resources.hideSelectors).forEach { selector ->
                    val cost = selector.length + 2
                    if (totalBytes + cost <= MAX_COSMETIC_JSON_BYTES) {
                        output.put(selector)
                        totalBytes += cost
                    }
                }
                output.toString()
            }.getOrElse { emptyCosmetics(BrowserAdblockDiagnostics.CosmeticLookup.BASE_UNAVAILABLE) }
        }
    }

    private fun emptyCosmetics(lookup: BrowserAdblockDiagnostics.CosmeticLookup): String {
        BrowserAdblockDiagnostics.cosmetic(lookup)
        return "[]"
    }

    suspend fun replaceFromLists(easylist: ByteArray, easyprivacy: ByteArray) = withContext(Dispatchers.IO) {
        require(easylist.isValidFilterList() && easyprivacy.isValidFilterList()) {
            "Downloaded filter lists failed HTTPS payload size/header validation."
        }
        NativeAdblock.ensureLoaded()
        val candidate = NativeAdblock.nativeCreateFromLists(easylist, easyprivacy)
        check(candidate != 0L) { "Downloaded filter lists failed native parser and baseline validation." }
        var installed = false
        try {
            installCandidate(candidate, lastUpdateMillis = System.currentTimeMillis())
            installed = true
        } finally {
            if (!installed) runCatching { NativeAdblock.nativeDestroy(candidate) }
        }
    }

    private suspend fun loadSnapshot(context: Context) {
        try {
            NativeAdblock.ensureLoaded()
            val saved = readSnapshot(context)
            val loaded = saved?.let { createValidatedEngine(it.engineBytes, it.statsJson) }
                ?: loadBundledSnapshot(context)
            if (loaded != null) {
                synchronized(engineLock) {
                    val previous = activeHandle
                    activeHandle = loaded.handle
                    if (previous != 0L) runCatching { NativeAdblock.nativeDestroy(previous) }
                }
                stateFlow.value = stateFlow.value.copy(
                    engineReady = true,
                    loading = false,
                    nativeAvailable = true,
                    networkRules = loaded.stats.networkRules,
                    cosmeticRules = loaded.stats.cosmeticRules,
                    lastUpdateMillis = preferences?.getLong(KEY_LAST_UPDATE, 0L) ?: 0L
                )
            } else {
                stateFlow.value = stateFlow.value.copy(loading = false, nativeAvailable = true)
            }
        } catch (_: UnsatisfiedLinkError) {
            stateFlow.value = stateFlow.value.copy(loading = false, nativeAvailable = false)
        } catch (_: Exception) {
            stateFlow.value = stateFlow.value.copy(loading = false, nativeAvailable = false)
        }
    }

    private fun loadBundledSnapshot(context: Context): LoadedEngine? {
        val engineBytes = context.assets.open("adblock/easylist-easyprivacy.dat").use { input ->
            input.readBounded(MAX_COMPILED_BYTES)
        }
        val statsJson = context.assets.open("adblock/filter-stats.json").bufferedReader(Charsets.UTF_8).use {
            it.readText().take(MAX_STATS_BYTES + 1)
        }
        val loaded = createValidatedEngine(engineBytes, statsJson) ?: return null
        runCatching { persistSnapshot(context, engineBytes, statsJson) }
        return loaded
    }

    private fun createValidatedEngine(engineBytes: ByteArray, statsJson: String): LoadedEngine? {
        if (engineBytes.isEmpty() || engineBytes.size > MAX_COMPILED_BYTES ||
            statsJson.toByteArray(Charsets.UTF_8).size > MAX_STATS_BYTES
        ) return null
        val stats = parseStats(statsJson) ?: return null
        val handle = NativeAdblock.nativeCreateFromCompiled(engineBytes, statsJson)
        if (handle == 0L) return null
        val actualStats = NativeAdblock.nativeStats(handle)?.let(::parseStats)
        if (actualStats != stats) {
            runCatching { NativeAdblock.nativeDestroy(handle) }
            return null
        }
        return LoadedEngine(handle, stats)
    }

    private fun installCandidate(handle: Long, lastUpdateMillis: Long) {
        val context = applicationContext ?: error("Ad blocker has not been initialized.")
        val engineBytes = NativeAdblock.nativeSerialize(handle)
            ?: error("The native engine did not produce a compiled snapshot.")
        check(engineBytes.isNotEmpty() && engineBytes.size <= MAX_COMPILED_BYTES) {
            "The compiled filter snapshot exceeded its size limit."
        }
        val statsJson = NativeAdblock.nativeStats(handle)
            ?: error("The native engine did not report validated filter statistics.")
        val stats = parseStats(statsJson)
            ?: error("The compiled filter snapshot did not meet the minimum rule baseline.")
        persistSnapshot(context, engineBytes, statsJson)

        synchronized(engineLock) {
            val previous = activeHandle
            activeHandle = handle
            cosmeticResourceCache.clear()
            cosmeticQueryCounts.clear()
            if (previous != 0L && previous != handle) runCatching { NativeAdblock.nativeDestroy(previous) }
        }
        preferences?.edit()?.putLong(KEY_LAST_UPDATE, lastUpdateMillis)?.apply()
        stateFlow.value = stateFlow.value.copy(
            engineReady = true,
            loading = false,
            nativeAvailable = true,
            networkRules = stats.networkRules,
            cosmeticRules = stats.cosmeticRules,
            lastUpdateMillis = lastUpdateMillis
        )
    }

    private fun persistSnapshot(context: Context, engineBytes: ByteArray, statsJson: String) {
        require(engineBytes.isNotEmpty() && engineBytes.size <= MAX_COMPILED_BYTES)
        val statsBytes = statsJson.toByteArray(Charsets.UTF_8)
        require(statsBytes.isNotEmpty() && statsBytes.size <= MAX_STATS_BYTES)
        val base = File(context.filesDir, "adblock/compiled.snapshot")
        base.parentFile?.mkdirs()
        val atomic = AtomicFile(base)
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(SNAPSHOT_MAGIC)
            data.writeInt(SNAPSHOT_VERSION)
            data.writeInt(statsBytes.size)
            data.write(statsBytes)
            data.writeInt(engineBytes.size)
            data.write(engineBytes)
            data.flush()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readSnapshot(context: Context): StoredSnapshot? {
        val atomic = AtomicFile(File(context.filesDir, "adblock/compiled.snapshot"))
        return runCatching {
            DataInputStream(atomic.openRead()).use { input ->
                require(input.readInt() == SNAPSHOT_MAGIC && input.readInt() == SNAPSHOT_VERSION)
                val statsSize = input.readInt()
                require(statsSize in 1..MAX_STATS_BYTES)
                val stats = ByteArray(statsSize).also(input::readFully).toString(Charsets.UTF_8)
                val engineSize = input.readInt()
                require(engineSize in 1..MAX_COMPILED_BYTES)
                val engine = ByteArray(engineSize).also(input::readFully)
                require(input.read() == -1)
                StoredSnapshot(engine, stats)
            }
        }.getOrNull()
    }

    private fun publishPreferences(prefs: SharedPreferences) {
        stateFlow.value = stateFlow.value.copy(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            excludedHosts = prefs.getStringSet(KEY_EXCLUDED_HOSTS, emptySet()).orEmpty()
                .mapNotNull(::normalizeHost).toSet(),
            lastUpdateMillis = prefs.getLong(KEY_LAST_UPDATE, 0L)
        )
    }

    private fun scheduleFilterUpdates(context: Context) {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AdblockFilterUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 3, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UPDATE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    private fun parseStats(json: String): FilterStats? = runCatching {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_STATS_BYTES) return null
        val lists = JSONObject(json).getJSONArray("lists")
        if (lists.length() != 2) return null
        var network = 0
        var cosmetic = 0
        var invalid = 0
        repeat(lists.length()) { index ->
            val list = lists.getJSONObject(index)
            val listNetwork = list.getInt("network_rules")
            val listCosmetic = list.getInt("cosmetic_rules")
            val listInvalid = list.getInt("invalid_rules")
            val total = listNetwork + listCosmetic + listInvalid
            if (listNetwork < 1_000 || total <= 0 || listInvalid.toLong() * 4 > total) return null
            network += listNetwork
            cosmetic += listCosmetic
            invalid += listInvalid
        }
        if (network < 10_000 || cosmetic < 500) return null
        FilterStats(network, cosmetic, invalid)
    }.getOrNull()

    private fun ByteArray.isValidFilterList(): Boolean =
        size in MIN_FILTER_LIST_BYTES..MAX_FILTER_LIST_BYTES &&
            copyOfRange(0, minOf(size, FILTER_HEADER_BYTES)).toString(Charsets.UTF_8)
                .startsWith("[Adblock Plus")

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Bundled filter snapshot exceeded its size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hostFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        return normalizeHost(parsed.host.orEmpty())
    }

    private fun normalizeHost(host: String): String? = runCatching {
        val clean = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean.length > 253 || clean.any { it.isWhitespace() }) return null
        IDN.toASCII(clean, IDN.USE_STD3_ASCII_RULES).takeIf { it.isNotEmpty() && it.length <= 253 }
    }.getOrNull()

    private fun isYoutubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com")

    private fun isSafeSelector(selector: String): Boolean =
        selector.isNotBlank() && selector.length <= MAX_COSMETIC_SELECTOR_BYTES &&
            selector.none { it == '\u0000' }

    private data class FilterStats(
        val networkRules: Int,
        val cosmeticRules: Int,
        val invalidRules: Int
    )

    private data class LoadedEngine(val handle: Long, val stats: FilterStats)
    private data class StoredSnapshot(val engineBytes: ByteArray, val statsJson: String)
    private data class CosmeticBaseResources(
        val hideSelectors: List<String>,
        val exceptionsJson: String,
        val generichide: Boolean
    )

    private const val MAX_FILTER_LIST_BYTES = 8 * 1024 * 1024
    private const val MIN_FILTER_LIST_BYTES = 100 * 1024
    private const val FILTER_HEADER_BYTES = 512
    private const val MAX_COSMETIC_SELECTOR_BYTES = 512
    private const val MAX_COSMETIC_JSON_BYTES = 32 * 1024
    private const val MAX_CACHED_COSMETIC_PAGES = 32
    private const val MAX_COSMETIC_QUERIES_PER_PAGE = 32
}

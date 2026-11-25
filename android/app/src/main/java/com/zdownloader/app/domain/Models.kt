package com.zdownloader.app.domain

import org.json.JSONObject
import java.net.URI
import java.net.InetAddress

enum class DownloadStatus { PENDING, ANALYZING, AWAITING_SELECTION, QUEUED, WAITING_FOR_NETWORK, RETRYING, DOWNLOADING, DOWNLOADING_VIDEO, DOWNLOADING_AUDIO, PAUSING, PAUSED, RESUMING, PROCESSING, WAITING_FOR_PROCESSOR, MERGING, CONVERTING, VALIDATING, SAVING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
val supportedMediaProtocols=setOf("https","http","m3u8_native","m3u8","http_dash_segments","dash")
enum class ErrorCode(val recoverable: Boolean = false) {
    INVALID_URL, PLAYLIST_DETECTED, BATCH_LIMIT_EXCEEDED, URL_POLICY_REJECTED, UNSUPPORTED_PLATFORM, VIDEO_NOT_FOUND, PRIVATE_CONTENT, AUTHENTICATION_REQUIRED,
    DRM_PROTECTED, GEO_RESTRICTED, BOT_DETECTED, RATE_LIMITED(true), NETWORK_UNAVAILABLE(true), NETWORK_UNVALIDATED(true), CAPTIVE_PORTAL(true), NETWORK_TIMEOUT(true),
    DNS_FAILED(true), CONNECTION_REFUSED(true), CONNECTION_FAILED(true), CONNECT_TIMEOUT(true), READ_TIMEOUT(true), TLS_TIMEOUT(true), TLS_FAILED, HTTP_FORBIDDEN, MEDIA_URL_EXPIRED, EXTRACTION_TIMEOUT(true),
    WIFI_REQUIRED(true), SCHEDULING_FAILED, START_DELAYED,
    HTTP_ERROR, SERVER_UNAVAILABLE(true), EXTRACTION_FAILED, ENGINE_BUSY(true), FORMAT_UNAVAILABLE, INSUFFICIENT_STORAGE, STORAGE_PERMISSION_ERROR, STORAGE_DESTINATION_UNAVAILABLE,
    DOWNLOAD_INTERRUPTED(true), SOURCE_CORRUPT(true), ENCODER_UNAVAILABLE, DECODER_UNAVAILABLE, MERGE_FAILED, CONVERSION_FAILED, OUTPUT_VALIDATION_FAILED, MEDIA_METADATA_MISMATCH, FILE_WRITE_FAILED, INTERNAL_ENGINE_ERROR,
    LIVE_STREAM_DETECTED, QUALITY_UNAVAILABLE, FILE_IN_USE, FILE_NAME_CONFLICT;
    companion object { fun parse(value: String?) = entries.find { it.name == value } ?: INTERNAL_ENGINE_ERROR }
}
/** Explicit audio choices shared by request validation and both format surfaces. */
internal object AudioOutputFormats {
    val picker=listOf("original","mp3","m4a","aac","flac","opus")
    val quickShare=listOf("mp3","m4a","aac","opus","flac","original")
    private val supported=picker.toSet()

    fun supports(output:String)=output in supported
}
class MediaException(val code: ErrorCode, val retryAfterMillis: Long? = null, val validationCheck:String?=null) : Exception(code.name)

object UrlPolicy {
    const val MAX_URL_LENGTH=64*1024
    const val MAX_INPUT_LENGTH=256*1024
    fun normalize(input: String): String {
        if(input.length>MAX_URL_LENGTH) throw MediaException(ErrorCode.INVALID_URL)
        val trimmed=input.trim()
        if(trimmed.any {it.code<32||it.code==127}) throw MediaException(ErrorCode.INVALID_URL)
        val uri = runCatching { URI(input.trim()) }.getOrElse { throw MediaException(ErrorCode.INVALID_URL) }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            (uri.port != -1 && uri.port !in 1..65535) || uri.rawAuthority.orEmpty().endsWith(':')) throw MediaException(ErrorCode.INVALID_URL)
        val host=uri.host.removeSurrounding("[","]")
        if(':' in host || host.all {it.isDigit() || it=='.'}) {
            val address=runCatching {InetAddress.getByName(host)}.getOrElse {throw MediaException(ErrorCode.INVALID_URL)}
            if(!publicAddress(address)) throw MediaException(ErrorCode.URL_POLICY_REJECTED)
        }
        val hostLower = uri.host.lowercase(java.util.Locale.ROOT)
        if (hostLower in setOf("instagram.com", "www.instagram.com")) {
            var path = uri.rawPath.ifBlank { "/" }
            path = path.replace("/share/reel/", "/reel/")
                .replace("/share/p/", "/p/")
                .replace("/share/r/", "/reel/")
                .replace("/reels/", "/reel/")
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val queryPart = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
            return "https://$hostLower$portPart$path$queryPart"
        }
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.rawPath.ifBlank { "/" }, uri.rawQuery, null).let {
            // Preserve percent escapes and media-identifying query parameters exactly.
            input.trim().substringBefore('#')
        }
    }
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            if (b[0] == 0 || b[0] == 127 || b[0] >= 224 || b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] in 16..31 || b[0] == 169 && b[1] == 254 || b[0] == 100 && b[1] in 64..127) return false
            if (b[0] == 192 && b[1] == 0 || b[0] == 198 && b[1] in 18..19 || b[0] == 198 && b[1] == 51 && b[2] == 100 || b[0] == 203 && b[1] == 0 && b[2] == 113) return false
        } else if (b.size == 16) {
            if(b.take(12)==listOf(0,100,255,155,0,0,0,0,0,0,0,0)) return publicAddress(InetAddress.getByAddress(address.address.copyOfRange(12,16)))
            if(b[0] and 224 != 32) return false
            if(b[0]==32 && b[1]==1 && (b[2] in 0..1 || b[2]==13 && b[3]==184)) return false
            if(b[0]==32 && b[1]==2 || b[0]==63 && b[1]==255 && b[2] and 240==0) return false
        }
        return true
    }
    fun links(text: String): List<String> {
        if(text.length>MAX_INPUT_LENGTH) throw MediaException(ErrorCode.INVALID_URL)
        // A pasted URL is already delimited. Do not cut valid apostrophes or
        // strip punctuation from a signed path/query as if it were prose.
        val whole=text.trim()
        if(whole.startsWith("https://",ignoreCase=true)&&whole.none {it.isWhitespace()}&&
            runCatching {normalize(whole)}.isSuccess) return listOf(whole)
        return Regex("https://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(text).take(201).map {
            var link=it.value.trimEnd('.', ',', '،')
            if(it.range.first>0&&text[it.range.first-1]=='\''&&link.endsWith('\'')) link=link.dropLast(1)
            for((close,open) in listOf(')' to '(',']' to '[','}' to '{')) {
                while(link.endsWith(close)&&link.count {c->c==close}>link.count {c->c==open}) link=link.dropLast(1)
            }
            link
        }.distinct().toList()
    }
    fun inputLinks(text:String):List<String> {
        val candidates=links(text).ifEmpty {listOf(text.trim())}
        return candidates.map(SharedLinks::normalize).distinct()
    }
}

object FileNames {
    fun sanitize(value: String): String {
        val safe = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\p{Cf}]"), "_").trim().trim('.')
        var end = 0
        var bytes = 0
        while(end < safe.length && end < 160) {
            val cp = safe.codePointAt(end)
            val count = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if(bytes + count > 200) break
            bytes += count
            end += Character.charCount(cp)
        }
        return safe.substring(0,end).ifBlank { "Media" }
    }
    fun output(title: String, extension: String, template: String):String {
        if(!extension.matches(Regex("[a-zA-Z0-9]{1,12}"))) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        val name=sanitize(template.replace("{title}",title).replace("{ext}",extension))
        return (if(name.endsWith(".$extension",ignoreCase=true)) name.dropLast(extension.length+1) else name)+".$extension"
    }
}

data class MediaSize(val bytes: Long, val estimated: Boolean)
enum class ProcessingPlan { REMUX, MERGE_COPY, TRANSCODE_AUDIO, TRANSCODE_VIDEO }
fun codecFamily(codec:String?):String = codec.orEmpty().lowercase().substringBefore('.').let {
    when { it.startsWith("avc")||it=="h264"->"h264";it.startsWith("hev")||it.startsWith("hvc")||it=="h265"->"hevc";it.startsWith("av01")||it=="av1"->"av1";it.startsWith("vp09")||it.startsWith("vp9")->"vp9";it.startsWith("vp08")||it=="vp8"->"vp8";it.startsWith("mp4a")||it=="aac"->"aac";else->it }
}
data class Format(val id: String, val ext: String, val height: Int?, val fps: Double?, val videoCodec: String?, val audioCodec: String?, val bytes: Long?, val bitrate: Double?, val protocol: String, val payload: String, val sizeEstimated: Boolean = false, val width: Int? = null, val audioBitrate: Double? = null, val videoBitrate: Double? = null, val combinedBitrate: Double? = null, val dynamicRange: String? = null, val language: String? = null, val languagePreference: Double? = null, val audioChannels: Int? = null, val sampleRate: Int? = null,val sourceQualityLabel:String?=null) {
    val transportValid:Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {FormatAvailability.transportValid(this)}
    val generatedSlideshow get()=protocol==SlideshowPlan.PROTOCOL
    val image:Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ext in setOf("jpg","jpeg","png","webp")&&videoCodec=="none"&&audioCodec=="none"&&
            runCatching {JSONObject(payload).optString("mediaflow_image")=="original"}.getOrDefault(false)
    }
    val expiresAt:Long? by lazy(LazyThreadSafetyMode.PUBLICATION) {FormatAvailability.expiresAt(payload)}
    val dimensionsVerified:Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {JSONObject(payload).optString("mediaflow_dimensions")!="unverified_label"}.getOrDefault(true)
    }
    val video get() = ext !in nonVideoExtensions && videoCodec !in setOf("none","images") &&
        (videoCodec!=null || height!=null || width!=null || id in setOf("sd","hd") || sourceQualityLabel!=null ||
         runCatching { JSONObject(payload).optString("video_ext") !in listOf("","none") }.getOrDefault(false))
    // Missing codec metadata does not establish an audio track in an unknown
    // video. Explicit audio-only metadata (vcodec=none) remains usable.
    val audio get() = ext !in nonVideoExtensions && audioCodec != "none" && (audioCodec != null || videoCodec == "none")
    val streamOnly get() = !video && audio
    companion object {
        private val nonVideoExtensions=setOf("jpg","jpeg","png","webp","gif","avif","heic","heif","bmp","tif","tiff","svg","ico","mhtml")
        fun parse(f: JSONObject): Format {
            // Only explicit per-stream dimensions (or an exact WxH field) are
            // evidence. IDs, notes, bitrate and thumbnail geometry never are.
            fun dimension(key:String)=f.finiteNumber(key)?.takeIf {it in 1.0..32768.0&&it==kotlin.math.floor(it)}?.toInt()
            val resolution = Regex("^(\\d{1,5})[x×](\\d{1,5})$").matchEntire(f.optString("resolution").trim())
            val explicit=!f.isNull("width")||!f.isNull("height")
            val invalid=(!f.isNull("width")&&dimension("width")==null)||(!f.isNull("height")&&dimension("height")==null)
            val w=if(invalid) null else dimension("width") ?: if(!explicit) resolution?.groupValues?.get(1)?.toIntOrNull()?.takeIf {it in 1..32768} else null
            val h=if(invalid) null else dimension("height") ?: if(!explicit) resolution?.groupValues?.get(2)?.toIntOrNull()?.takeIf {it in 1..32768} else null
            val extension=listOfNotNull(f.text("ext"),f.text("video_ext"),f.text("audio_ext")).map {it.lowercase(java.util.Locale.ROOT)}.firstOrNull {it !in listOf("none","unknown")} ?: "mp4"
            if(!extension.matches(Regex("[a-zA-Z0-9]{1,12}"))) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val tbr = f.number("tbr") ?: f.number("bitrate")
            val abr = f.number("abr") ?: if(f.text("vcodec") in listOf("none",null,"") && f.text("acodec") !in listOf("none",null,"")) tbr else null
            val vbr = f.number("vbr") ?: if(f.text("acodec") in listOf("none",null,"") && f.text("vcodec") !in listOf("none",null,"")) tbr else null
            return Format(f.optString("format_id", "direct"), extension, h, f.number("fps"), f.text("vcodec"), f.text("acodec"), (f.number("filesize") ?: f.number("filesize_approx"))?.takeIf {it<Long.MAX_VALUE.toDouble()}?.toLong(), tbr, f.text("protocol") ?: "https", f.toString(), f.number("filesize") == null && f.number("filesize_approx") != null, w, abr, vbr,f.number("combined_tbr"), f.text("dynamic_range"),f.text("language"),f.finiteNumber("language_preference"),f.number("audio_channels")?.toInt(),f.number("asr")?.toInt(),f.text("mediaflow_source_quality_label")?.takeIf {it.matches(Regex("[1-9][0-9]{1,4}p"))})
        }
    }
    fun size(duration: Double?): MediaSize? {
        if(generatedSlideshow) return null
        bytes?.takeIf { it > 0 }?.let { return MediaSize(it,sizeEstimated) }
        // HLS master rates may include the separately downloaded audio. Do not
        // count that aggregate again as video bytes plus an audio estimate.
        val rate = if(streamOnly) audioBitrate ?: bitrate else if(combinedBitrate!=null&&!audio&&videoBitrate==null) null
            else videoBitrate?.let {it+(if(audio) audioBitrate ?: 0.0 else 0.0)} ?: bitrate
        return estimateBytes(duration,rate)?.let { MediaSize(it,true) }
    }
}
data class Media(val url: String, val id: String, val title: String, val source: String, val uploader: String?, val thumbnail: String?, val duration: Double?, val formats: List<Format>,val subtitles:List<SubtitleTrack> = emptyList()) {
    companion object { fun parse(j: JSONObject, url: String): Media {
        if (j.has("error")) throw MediaException(ErrorCode.parse(j.optString("error")))
        val a = j.optJSONArray("formats") ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        var expired=false
        val formats=(0 until a.length()).mapNotNull {index->runCatching {
            val entry=a.getJSONObject(index)
            if(entry.text("format_id").isNullOrBlank()) return@runCatching null
            UrlPolicy.normalize(entry.getString("url"))
            Format.parse(entry).takeIf {
                if(it.expiresAt?.let {deadline->deadline<=System.currentTimeMillis()+5_000}==true) expired=true
                FormatAvailability.eligible(it)
            }
        }.getOrNull()}
        // Identical duplicate rows are harmless. Conflicting tracks with one ID
        // cannot be selected unambiguously by the persisted selection contract.
        val unique=formats.groupBy {it.id}.values.mapNotNull {rows ->
            rows.first().takeIf {rows.map {listOf(JSONObject(it.payload).optString("url"),it.width,it.height,it.videoCodec,it.audioCodec,it.ext,it.fps,it.language,it.dynamicRange,it.audioChannels,it.sampleRate,it.protocol)}.distinct().size==1}
        }
        if(unique.isEmpty()) throw MediaException(if(expired) ErrorCode.MEDIA_URL_EXPIRED else ErrorCode.FORMAT_UNAVAILABLE)
        val subtitles=j.optJSONArray("subtitles")?.let {a->(0 until minOf(a.length(),100)).mapNotNull {i->runCatching {SubtitleTrack.parse(a.getJSONObject(i))}.getOrNull()}}.orEmpty()
        return Media(url, j.text("id") ?: url, j.text("title") ?: "Media", j.text("source")?.takeUnless { it in listOf("Generic","Media") } ?: URI(url).host.removePrefix("www."), j.text("uploader"), j.text("thumbnail"), j.number("duration"),unique,subtitles)
    } }
}
/** A URL refresh may update transport data, never the user's known track identity. */
fun Format.preservesSelection(old:Format):Boolean = id==old.id && ext==old.ext &&
    (!old.dimensionsVerified || old.width==null || width==old.width) && (!old.dimensionsVerified || old.height==null || height==old.height) &&
    (old.videoCodec==null || codecFamily(videoCodec)==codecFamily(old.videoCodec)) &&
    (old.audioCodec==null || codecFamily(audioCodec)==codecFamily(old.audioCodec)) &&
    (old.fps==null || fps!=null && kotlin.math.abs(fps-old.fps)<0.5) &&
    (old.language==null || language==old.language) &&
    (old.audioChannels==null || audioChannels==old.audioChannels) &&
    (old.sampleRate==null || sampleRate==old.sampleRate) &&
    (old.dynamicRange==null || dynamicRange.equals(old.dynamicRange,true))
data class Selection(val primary: Format, val secondary: Format? = null, val audioOnly: Boolean = false, val output: String = primary.ext, val bitrate: Int = 192, val convertAudio: Boolean = false, val duration: Double? = null,val videoOnly:Boolean=false,val subtitle:SubtitleTrack?=null,val embedMetadata:Boolean=true,val creator:String?=null) {
    init {
        if(primary.image&&(audioOnly||secondary!=null||output!=primary.ext||convertAudio||videoOnly||subtitle!=null||embedMetadata))
            throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
    }
    val imageOnly:Boolean get()=primary.image&&!audioOnly&&secondary==null
    val transcodeVideo: Boolean get() = !imageOnly && !audioOnly && VideoContainerPolicy.transcodeVideo(primary.videoCodec,output)
    val transcodeAudio: Boolean get() = !videoOnly&&(if(audioOnly) convertAudio else output=="mp4" && (secondary ?: primary).let { it.audio && codecFamily(it.audioCodec) !in listOf("aac","none","") })
    val plan: ProcessingPlan get() = when {transcodeVideo->ProcessingPlan.TRANSCODE_VIDEO;transcodeAudio->ProcessingPlan.TRANSCODE_AUDIO;secondary!=null->ProcessingPlan.MERGE_COPY;else->ProcessingPlan.REMUX}
    val transferSize: MediaSize? get() {
        val first = primary.size(duration)
        val second = secondary?.size(duration)
        if(first==null || secondary != null && second == null) {
            return if(secondary!=null && primary.combinedBitrate!=null) estimateBytes(duration,primary.combinedBitrate)?.let { MediaSize(it,true) } else null
        }
        val sum = first.bytes.toDouble() + (second?.bytes ?: 0)
        return sum.takeIf { it < Long.MAX_VALUE }?.let { MediaSize(it.toLong(),first.estimated || second?.estimated == true) }
    }
    val outputSize: MediaSize? get() {
        if(imageOnly) return transferSize
        if(subtitle!=null) return null
        if(!audioOnly&&(videoOnly||transcodeVideo||transcodeAudio)) {
            // Match Processor's source-derived H.264 target and 192 kb/s AAC
            // target. Never estimate a combined file's video from its audio+video
            // bitrate, or use the encoder's generic fallback as measured evidence.
            val video=if(transcodeVideo) {
                val rate=primary.videoBitrate ?: primary.bitrate?.takeIf {!primary.audio} ?: return null
                estimateBytes(duration,rate.coerceAtLeast(500.0))?.let {MediaSize(it,true)}
            } else if(primary.audio) estimateBytes(duration,primary.videoBitrate)?.let {MediaSize(it,true)}
                else primary.size(duration)
            val audio=when {
                videoOnly||!(secondary ?: primary).audio->MediaSize(0,false)
                transcodeAudio->estimateBytes(duration,192.0)?.let {MediaSize(it,true)}
                secondary!=null->secondary.size(duration)
                else->estimateBytes(duration,(primary.audioBitrate ?: primary.bitrate))?.let {MediaSize(it,true)}
            }
            return sumMediaSizes(listOf(video,audio))?.copy(estimated=true)
        }
        if(audioOnly && (convertAudio || !primary.streamOnly)) {
            if(convertAudio && bitrate==0) return null
            val rate = if(convertAudio) { if(output == "flac") return null else bitrate.toDouble() } else (primary.audioBitrate ?: primary.bitrate) ?: return null
            return estimateBytes(duration,rate)?.let { MediaSize(it,true) }
        }
        // Even a stream-copy remux can change container overhead and metadata.
        return transferSize?.let { MediaSize(it.bytes,true) }
    }
    val size: Long? get() = transferSize?.bytes
    fun json() = JSONObject().put("primary", JSONObject(primary.payload)).put("secondary", secondary?.let { JSONObject(it.payload) }).put("audio", audioOnly).put("output", output).put("bitrate", bitrate).put("convert",convertAudio).put("duration",duration).put("videoOnly",videoOnly).put("subtitle",subtitle?.json()).put("embedMetadata",embedMetadata).put("creator",creator).toString()
    /** Completed batch history needs media properties, not cookies or fragment plans. */
    fun historyJson():String {
        val value=JSONObject(json())
        value.optJSONObject("subtitle")?.remove("payload")
        val fields=setOf("format_id","ext","width","height","fps","vcodec","acodec","filesize","filesize_approx","tbr","abr","vbr","combined_tbr","protocol","dynamic_range","language","language_preference","audio_channels","asr","mediaflow_dimensions","mediaflow_image","mediaflow_companion_audio")
        for(key in listOf("primary","secondary")) value.optJSONObject(key)?.let {format->
            format.keys().asSequence().toList().filter {it !in fields}.forEach(format::remove)
        }
        return value.toString()
    }
    companion object {
        private val selectionCache = object : java.util.LinkedHashMap<String, Selection>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Selection>?): Boolean = size > 256
        }
        fun image(media:Media,format:Format):Selection {
            if(format !in media.formats||!format.image||!FormatAvailability.eligible(format)) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            return Selection(format,output=format.ext,embedMetadata=false)
        }
        fun parse(value: String): Selection {
            synchronized(selectionCache) {
                selectionCache[value]?.let { return it }
            }
            val j = JSONObject(value)
            val parsed = Selection(Format.parse(j.getJSONObject("primary")), j.optJSONObject("secondary")?.let { Format.parse(it) }, j.optBoolean("audio"), j.getString("output"), j.optInt("bitrate", 192),j.optBoolean("convert",false),j.number("duration"),j.optBoolean("videoOnly"),j.optJSONObject("subtitle")?.takeIf {it.has("payload")}?.let(SubtitleTrack::parse),j.optBoolean("embedMetadata",true),j.text("creator"))
            synchronized(selectionCache) {
                selectionCache[value] = parsed
            }
            return parsed
        }
        fun video(media: Media, format: Format, output: String? = null): Selection {
            if(!format.video || format !in media.formats || !FormatAvailability.eligible(format)) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val preferredAudio = if (!format.audio) media.audioOptions().filter { it.streamOnly }.maxWithOrNull(compareBy<Format> { it.languagePreference ?: 0.0 }.thenBy { if(format.ext=="webm") it.ext in listOf("webm","opus","ogg") else it.ext in listOf("m4a","aac") }.thenBy { it.protocol !in setOf("m3u8", "m3u8_native") }.thenBy { it.audioBitrate ?: it.bitrate ?: 0.0 }) else null
            val audio=if(output=="webm" && preferredAudio!=null && codecFamily(format.videoCodec) in setOf("vp8","vp9","av1"))
                WebmCompanionAudio.select(media.audioOptions(),preferredAudio) ?: preferredAudio else preferredAudio
            // Genuinely silent media remain usable, but are explicitly labelled without audio.
            val compatible = videoOutputs(format,audio)
            val container = output ?: compatible.first()
            if(container !in compatible) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val companion=if(container=="mp4"&&audio!=null) Mp4CompanionAudio.select(media.audioOptions(),audio) else audio
            return Selection(format, companion, output=container,duration=media.duration,creator=media.uploader?.take(512))
        }
        fun audio(media: Media, output: String, selectedSource: Format? = null, bitrate:Int=192): Selection {
            val options=media.audioOptions()
            if(selectedSource!=null&&selectedSource !in options) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            if(!AudioOutputFormats.supports(output)) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val rank=compareBy<Format> {it.languagePreference ?: 0.0}
                .thenBy {it.protocol !in setOf("m3u8","m3u8_native")}
                .thenBy {it.audioBitrate ?: if(it.streamOnly) it.bitrate ?: 0.0 else 0.0}
                .thenBy {it.streamOnly}
            val preferred=selectedSource ?: options.maxWithOrNull(rank) ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val original = if(preferred.streamOnly) preferred.ext else when(codecFamily(preferred.audioCodec)) {
                "opus"->"opus";"vorbis"->"ogg";"mp3"->"mp3";"aac"->"m4a";"flac"->"flac"
                else->if(preferred.ext=="mp4") "m4a" else preferred.ext
            }
            val target=if(output=="original") original else output
            // Automatic output selection should use an already-compatible source
            // when one exists, but never switch away from the preferred language.
            val source=selectedSource ?: if(output=="original") preferred else options.asSequence()
                .filter {it.language==preferred.language&&canCopyAudioTo(it,target,bitrate)}.maxWithOrNull(rank) ?: preferred
            val convert=output!="original"&&!canCopyAudioTo(source,target,bitrate)
            return Selection(source,audioOnly=true,output=target,bitrate=bitrate,convertAudio=convert,duration=media.duration,creator=media.uploader?.take(512))
        }
        private fun canCopyAudioTo(source:Format,output:String,bitrate:Int):Boolean {
            val family=codecFamily(source.audioCodec)
            if(output=="flac"&&family=="flac") return true
            if(output=="m4a"&&family=="alac") return true
            val targetFamily=when(output) {"mp3"->"mp3";"m4a","aac"->"aac";"opus"->"opus";else->return false}
            if(family!=targetFamily) return false
            if(bitrate<=0) return true
            val sourceRate=(source.audioBitrate ?: if(source.streamOnly) source.bitrate else null)
                ?.takeIf {it.isFinite()&&it>0} ?: return false
            return kotlin.math.abs(sourceRate-bitrate.toDouble())<1.0
        }
    }
}
fun estimateBytes(duration: Double?, bitrateKbps: Double?): Long? {
    if(duration == null || bitrateKbps == null || duration <= 0 || bitrateKbps <= 0) return null
    val value = duration * bitrateKbps * 1000 / 8
    return value.takeIf { it.isFinite() && it in 1.0..<Long.MAX_VALUE.toDouble() }?.toLong()
}
fun videoOutputs(video: Format, audio: Format?,platformSdk:Int=android.os.Build.VERSION.SDK_INT): List<String> {
    if(video.generatedSlideshow) return listOf("mp4","mkv")
    val v=video.videoCodec.orEmpty().lowercase(); val a=(audio?.audioCodec ?: video.audioCodec).orEmpty().lowercase()
    val mp4 = video.ext=="mp4" && (audio==null || audio.ext in listOf("m4a","aac","mp4")) || (v.startsWith("avc") || v.startsWith("h264") || v.startsWith("hev") || v.startsWith("hvc")) && (a in listOf("none","aac") || a.startsWith("mp4a"))
    val webmVideo=codecFamily(video.videoCodec) in setOf("vp8","vp9","av1")
    val webmAudio=when {
        audio!=null -> audio.streamOnly && codecFamily(audio.audioCodec) in setOf("opus","vorbis")
        video.audio -> codecFamily(video.audioCodec) in setOf("opus","vorbis")
        else -> video.audioCodec=="none"
    }
    // A source extension alone cannot make incompatible combined tracks valid.
    val webm=webmVideo&&webmAudio
    val preferOriginal=codecFamily(video.videoCodec)=="vp9"&&!VideoContainerPolicy.canCopyMp4Video(video.videoCodec,platformSdk)
    return buildList { if(mp4&&!preferOriginal) add("mp4"); if(webm) add("webm"); if(video.ext !in listOf("mp4","webm","mkv") && audio==null) add(video.ext); add("mkv"); add("mp4") }.distinct()
}
/** Include containers with a compatible companion, not just the initial default audio pairing. */
fun Media.videoOutputs(format:Format):List<String> {
    val selected=Selection.video(this,format)
    val outputs=videoOutputs(format,selected.secondary)
    if("webm" in outputs || format.audio || codecFamily(format.videoCodec) !in setOf("vp8","vp9","av1")) return outputs
    val webm=try {Selection.video(this,format,"webm")} catch(_:MediaException) {return outputs}
    return outputs + webm.output
}
fun Media.audioOptions():List<Format> = formats.filter {it.audio&&FormatAvailability.eligible(it)}
fun Media.imageOptions():List<Format> = formats.filter {it.image&&FormatAvailability.eligible(it)}
fun Media.originalImage():Format? = imageOptions().maxByOrNull {(it.width ?: 0).toLong()*(it.height ?: 0)}
fun Media.videoOptions(): List<Format> = formats.filter { it.video && FormatAvailability.eligible(it) }
    .groupBy { listOf(it.height,it.width,it.ext,it.videoCodec,it.fps,it.dynamicRange,it.language,it.audioCodec) }
    .values.map { rows -> rows.maxWith(
        compareBy<Format> { it.audio }
            .thenBy { it.protocol !in setOf("m3u8", "m3u8_native") }
            .thenBy { it.videoBitrate ?: it.bitrate ?: 0.0 }
    ) }
    .sortedWith(compareByDescending<Format> { it.height ?: 0 }.thenBy { it.ext != "mp4" }.thenByDescending { it.fps ?: 0.0 })
/** Raw verified short edge. A lone width cannot establish portrait/landscape quality. */
fun verifiedQualityEdge(format:Format):Int? = if(!format.video||!format.dimensionsVerified) null else
    if(format.width!=null&&format.height!=null) minOf(format.width,format.height).takeIf {it in 1..32768}
    else format.height?.takeIf {it in 1..32768}
/**
 * Logical representation tier. An explicit source representation label (e.g. DASH FBQualityLabel)
 * names the genuine quality even when several tiers share one container viewport; otherwise the
 * verified pixel edge is used. Container geometry itself is never rewritten (see AGENTS.md rule 2).
 */
fun qualityLabel(format:Format):Int? = if(!format.video) null else
    format.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull()?.takeIf {it in 1..32768} ?: verifiedQualityEdge(format)
/** Conservative consumer category: round DOWN only, preserve exact dimensions in details. */
fun qualityCategory(format: Format): Int? = (qualityLabel(format) ?: verifiedQualityEdge(format))?.let {edge->
    listOf(144,240,360,480,720,1080,1440,2160,4320).lastOrNull {it<=edge}
}
fun qualityCategoryName(resolution:Int):String=when(resolution) {1440->"2K";2160->"4K";4320->"8K";else->"${resolution}p"}
fun fpsBucket(fps: Double?): Int? = fps?.let { kotlin.math.round(it/5).toInt()*5 }
fun fpsLabel(fps: Double?): String? = fps?.let { value ->
    if(kotlin.math.abs(value-kotlin.math.round(value))<0.005) kotlin.math.round(value).toInt().toString()
    else String.format(java.util.Locale.ROOT,"%.2f",value).trimEnd('0').trimEnd('.')
}
fun isHdr(format: Format): Boolean = format.dynamicRange?.let { it.isNotBlank() && !it.equals("SDR",true) } == true
/** Consumer-facing grouping: one resolution row; meaningful FPS/HDR variants stay selectable inside. */
data class QualityGroup(val resolution: Int?, val candidates: List<Format>) {
    val recommended: Format get() = candidates.first()
    val variantChoices: List<Format> get() = candidates.distinctBy {
        listOf(it.width,it.height,fpsLabel(it.fps),it.dynamicRange,it.videoCodec,it.ext,it.audio,it.audioCodec,it.language,
            (it.videoBitrate ?: it.bitrate)?.takeIf {rate->rate.isFinite()&&rate>0}?.toInt())
    }
    val hasMeaningfulVariants: Boolean get() = variantChoices.size>1
}
@JvmOverloads
fun Media.qualityGroups(capabilities: MediaIntelligence.CodecCapabilities = MediaIntelligence.platformCapabilities): List<QualityGroup> = formats.filter { it.video && FormatAvailability.eligible(it) }
    .groupBy { qualityLabel(it) }
    .values.map { rows ->
        val preferences=rows.associate {it.id to MediaIntelligence.recommendationPreference(this,it,capabilities)}
        // Prefer actual video fidelity over mux convenience. A lower-bitrate
        // combined stream must not silently beat a better video-only stream.
        val ranked = rows.sortedWith(compareByDescending<Format> { (it.width ?: 0).toLong()*(it.height ?: 0) }
            .thenByDescending { isHdr(it) }
            .thenByDescending { it.fps ?: 0.0 }
            .thenBy { preferences.getValue(it.id) }
            .thenBy { it.protocol in setOf("m3u8", "m3u8_native") }
            .thenByDescending { it.videoBitrate ?: it.bitrate ?: 0.0 }
            .thenByDescending { it.audio }
            .thenBy { it.ext != "mp4" }
            .thenBy { it.id })
        QualityGroup(qualityLabel(rows.first()),ranked)
    }
    .sortedWith(compareByDescending<QualityGroup> { it.resolution ?: 0 }.thenBy { it.recommended.ext != "mp4" })
@JvmOverloads
fun Media.recommendedVideo(preferredHeight: Int = 0, capabilities: MediaIntelligence.CodecCapabilities = MediaIntelligence.platformCapabilities): Format? {
    return MediaIntelligence.recommend(this,preferredHeight,capabilities)
}
fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONObject.finiteNumber(key: String): Double? = if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0 }

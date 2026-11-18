package com.zdownloader.app.domain

/** The share surface uses the existing format/selection contract without inventing candidates. */
data class QuickShareChoice(val audio: Boolean, val formatId: String, val output: String = "") {
    fun resolve(media: Media): Selection? = runCatching {
        val usable=QuickShareFormats.usable(media)
        val source=usable.formats.firstOrNull {it.id==formatId} ?: return@runCatching null
        if(audio) {
            if(!source.audio) return@runCatching null
            QuickShareFormats.audioSelection(usable,output.ifBlank {"original"},source)
        } else {
            if(source.image) return@runCatching Selection.image(usable,source).takeIf {output.isBlank()||output==source.ext}
            if(!source.video) return@runCatching null
            Selection.video(usable,source,output.takeIf {it.isNotBlank()})
        }
    }.getOrNull()
}

object QuickShareFormats {
    /** A preference never creates a candidate or falls UP to an unavailable cap. */
    fun preferenceChoice(media:Media,cap:Int):QuickShareChoice? {
        val groups=usable(media).qualityGroups()
        val candidate=if(cap==0) groups.firstOrNull()?.recommended else groups.firstOrNull {
            (it.resolution ?: Int.MAX_VALUE)<=cap
        }?.recommended
        return candidate?.let {QuickShareChoice(false,it.id)}
    }
    fun hasExactPreference(media:Media,cap:Int)=media.formats.any {it.video&&FormatAvailability.eligible(it)&&qualityLabel(it)==cap}
    private val protocols=supportedMediaProtocols
    fun usable(media:Media)=media.copy(formats=media.formats.filter {FormatAvailability.eligible(it)})
    fun defaultChoice(media:Media,preferredHeight:Int=0,downloadType:String="ask"):QuickShareChoice? {
        val usable=usable(media)
        if(downloadType=="audio") runCatching {Selection.audio(usable,"original").primary}.getOrNull()?.let {return QuickShareChoice(true,it.id,"original")}
        if(usable.videoOptions().isEmpty()) usable.originalImage()?.let {return QuickShareChoice(false,it.id)}
        val video=usable.recommendedVideo(preferredHeight)?.let {QuickShareChoice(false,it.id)}
        if(video!=null||preferredHeight>0&&usable.videoOptions().isNotEmpty()) return video
        return runCatching {Selection.audio(usable,"original").primary}.getOrNull()?.let {QuickShareChoice(true,it.id,"original")}
    }
    fun compactGroups(media:Media,preferredHeight:Int,choice:QuickShareChoice?):List<QualityGroup> {
        return compactGroups(usable(media).qualityGroups(),preferredHeight,choice)
    }
    fun compactGroups(groups:List<QualityGroup>,preferredHeight:Int,choice:QuickShareChoice?):List<QualityGroup> {
        return QuickShareCatalog.compactGroups(groups,choice?.takeUnless {it.audio}?.formatId)
    }
    fun audioChoices(media:Media,source:Format,mp3Available:Boolean):List<QuickShareChoice> {
        val usable=usable(media)
        val options=usable.audioOptions()
        return listOf("mp3","m4a","aac","opus","flac","original").mapNotNull {output->
            val selected=if(output=="original"||copyCompatible(source,output)) source
                else compatibleAudioSource(options,source,output) ?: source
            val selection=runCatching {audioSelection(usable,output,selected)}.getOrNull() ?: return@mapNotNull null
            if(output=="mp3"&&!mp3Available&&selection.convertAudio) return@mapNotNull null
            QuickShareChoice(true,selected.id,output) to selection
        }.distinctBy {(choice,_)->choice.output}
            .map {it.first}
    }

    internal fun audioSelection(media:Media,output:String,source:Format):Selection {
        val direct=Selection.audio(media,output,source,bitrate=0)
        return if(output!="original"&&!direct.convertAudio) direct else Selection.audio(media,output,source)
    }

    private fun compatibleAudioSource(options:List<Format>,source:Format,output:String):Format? {
        val requestedRate=source.audioBitrate?.takeIf {it.isFinite()&&it>0}
            ?: source.bitrate?.takeIf {source.streamOnly&&it.isFinite()&&it>0}
        return options.asSequence().filter {candidate->
            candidate.language==source.language&&copyCompatible(candidate,output)&&
                (requestedRate==null||run {
                    val candidateRate=candidate.audioBitrate?.takeIf {it.isFinite()&&it>0}
                        ?: candidate.bitrate?.takeIf {candidate.streamOnly&&it.isFinite()&&it>0}
                    candidateRate!=null&&kotlin.math.abs(candidateRate-requestedRate)<1.0
                })
        }
            .maxWithOrNull(compareBy<Format> {it.streamOnly}
            .thenBy {it.languagePreference ?: 0.0}
            .thenBy {it.protocol !in setOf("m3u8","m3u8_native")}
            .thenBy {it.audioBitrate ?: if(it.streamOnly) it.bitrate ?: 0.0 else 0.0})
    }

    private fun copyCompatible(source:Format,output:String):Boolean = when(output) {
        "mp3"->codecFamily(source.audioCodec)=="mp3"
        "m4a"->codecFamily(source.audioCodec) in setOf("aac","alac")
        "aac"->codecFamily(source.audioCodec)=="aac"
        "opus"->codecFamily(source.audioCodec)=="opus"
        "flac"->codecFamily(source.audioCodec)=="flac"
        else->false
    }
    fun hasMoreVideo(media:Media):Boolean {
        val groups=media.qualityGroups()
        val compact=compactGroups(groups,0,null)
        return groups.any {it !in compact||it.hasMeaningfulVariants||runCatching {
            val selection=Selection.video(media,it.recommended)
            videoOutputs(selection.primary,selection.secondary).size>1
        }.getOrDefault(false)}
    }
    fun sharedUrl(texts:List<String>):String? {
        val links=texts.flatMap(UrlPolicy::links).mapNotNull {runCatching {SharedLinks.normalize(it)}.getOrNull()}.distinct()
        return links.singleOrNull()
    }
    fun isLikelyImageUrl(url:String?):Boolean {
        if(url.isNullOrBlank()) return false
        val uri=runCatching {java.net.URI(url.trim())}.getOrNull() ?: return false
        val host=uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        val path=uri.rawPath?.lowercase(java.util.Locale.ROOT).orEmpty()
        val query=uri.rawQuery?.lowercase(java.util.Locale.ROOT).orEmpty()
        val cleanPath=path.substringBefore(';')
        if(setOf(".jpg",".jpeg",".png",".webp",".gif",".bmp",".heic",".heif",".avif").any {cleanPath.endsWith(it)}) return true
        if(host in setOf("facebook.com","www.facebook.com","m.facebook.com","web.facebook.com","fb.com")) {
            if(path.startsWith("/photo")||path.contains("/photos/")||path.contains("/photo/")||query.contains("fbid=")) return true
        }
        if((host in setOf("twitter.com","www.twitter.com","x.com","www.x.com"))&&path.contains("/photo/")) return true
        if(host in setOf("i.redd.it","preview.redd.it")||path.contains("/gallery/")) return true
        if(host.contains("pinterest.com")||host=="pin.it") return true
        if(host in setOf("i.imgur.com")) return true
        if(host in setOf("instagram.com","www.instagram.com")&&path.startsWith("/p/")) return true
        return false
    }
}

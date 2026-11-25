package com.zdownloader.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import com.zdownloader.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class PlaybackOpenDestination { FULL_SCREEN_PLAYER, EXTERNAL_FLOATING_PREVIEW }

/** In-app playback opens the full player; only an external audio open starts the floating preview. */
internal object PlaybackOpenPolicy {
    fun destination(audio:Boolean,external:Boolean):PlaybackOpenDestination=
        if(audio&&external) PlaybackOpenDestination.EXTERNAL_FLOATING_PREVIEW
        else PlaybackOpenDestination.FULL_SCREEN_PLAYER
}

internal data class PlaybackState(val current:PlaybackMedia?=null,val queue:List<PlaybackMedia> = emptyList(),val index:Int=0,
    val position:Long=0,val duration:Long=0,val playing:Boolean=false,val ready:Boolean=false,val buffering:Boolean=false,
    val shuffle:Boolean=false,val repeat:Int=Player.REPEAT_MODE_OFF,val speed:Float=1f,val tracks:Tracks=Tracks.EMPTY,
    val extras:Bundle=Bundle.EMPTY,val error:Int?=null,val aspect:Float=16f/9f,val videoDisplayAspect:Float?=null)

internal data class ExternalMediaResolution(val requestId:String?=null,val complete:Boolean=false,val media:PlaybackMedia?=null)

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class PlaybackConnection(context:Context) {
    var diagnostic:String="idle"
        private set
    private val app=context.applicationContext
    val repository=PlaybackRepository(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(PlaybackState())
    val state=mutable.asStateFlow()
    private val mutablePlayer=MutableStateFlow<MediaController?>(null)
    val player=mutablePlayer.asStateFlow()
    private val mutableExternalMediaResolution=MutableStateFlow(ExternalMediaResolution())
    val externalMediaResolution=mutableExternalMediaResolution.asStateFlow()
    private var future:ListenableFuture<MediaController>?=null
    private var pending:CompletableDeferred<MediaController>?=null
    private var ticker:Job?=null
    private var idleDisconnect:Job?=null
    private var clients=0
    private var actions=0
    private var queueGeneration=0L
    private var previewHandoffUri:String?=null
    private var previewHandoffGeneration=0L
    private var videoExitGeneration=0L
    private var subtitleRequest=0L
    private var pendingExternalSubtitle=false
    private var pendingExternalRequestId:String?=null
    private var playRequest:Job?=null
    private val listener=object:Player.Listener {
        override fun onEvents(player:Player,events:Player.Events) {snapshot()}
        override fun onTracksChanged(tracks:Tracks) {
            if(pendingExternalSubtitle) tracks.groups.filter {it.type==C.TRACK_TYPE_TEXT}.forEach {group->
                for(index in 0 until group.length) if(group.getTrackFormat(index).id?.contains("mediaflow_external")==true||group.getTrackFormat(index).label==app.getString(R.string.player_external_subtitle)) {
                    pendingExternalSubtitle=false;diagnostic="subtitle_selected";track(C.TRACK_TYPE_TEXT,group,index);return
                }
            }
        }
    }
    fun attach() {idleDisconnect?.cancel();idleDisconnect=null;clients++;if(PlaybackRuntime.active) scope.launch {runCatching {connect()}}}
    fun detach() {
        clients=(clients-1).coerceAtLeast(0)
        releaseWhenUnowned()
    }
    private fun releaseWhenUnowned() {
        idleDisconnect?.cancel();idleDisconnect=null
        if(clients==0&&actions==0) {
            // Provider validation is an operation owner, independent of visible
            // Activity owners. Release only after both kinds of work are done.
            idleDisconnect=scope.launch {delay(500);if(clients==0&&actions==0) disconnect()}
        }
    }
    suspend fun connect():MediaController {
        mutablePlayer.value?.let {if(it.isConnected) return it}
        pending?.let {return it.await()}
        val deferred=CompletableDeferred<MediaController>();pending=deferred
        diagnostic="connecting"
        val request=MediaController.Builder(app,SessionToken(app,ComponentName(app,MediaPlaybackService::class.java)))
                .setListener(object:MediaController.Listener {
                override fun onExtrasChanged(controller:MediaController,extras:Bundle) {snapshot()}
                override fun onDisconnected(controller:MediaController) {if(mutablePlayer.value===controller) {
                    mutablePlayer.value=null;future=null;ticker?.cancel()
                    mutable.update {it.copy(ready=false,playing=false,buffering=false,error=R.string.player_error_unavailable)}
                }}
            }).buildAsync()
        future=request
        request.addListener({
            try {
                val controller=request.get()
                if(future!==request) {controller.release();deferred.cancel();return@addListener}
                mutablePlayer.value=controller;pending=null;controller.addListener(listener);snapshot();diagnostic="connected";deferred.complete(controller)
                ticker?.cancel();ticker=scope.launch {while(isActive) {tick();delay(if(controller.isPlaying) 250 else 1000)}}
            } catch(e:Exception) {
                if(future!==request) {deferred.cancel();return@addListener}
                diagnostic="connect:${e.javaClass.simpleName}:${e.cause?.javaClass?.simpleName}";android.util.Log.w("MediaFlowPlayback",diagnostic);pending=null;future=null;deferred.completeExceptionally(e);mutable.update {it.copy(error=R.string.player_error_unavailable)}
            }
        },ContextCompat.getMainExecutor(app))
        return deferred.await()
    }
    fun disconnect() {
        idleDisconnect?.cancel();idleDisconnect=null
        ticker?.cancel();ticker=null
        val old=mutablePlayer.value;mutablePlayer.value=null
        old?.removeListener(listener)
        val released=future;future=null
        val waiting=pending;pending=null
        released?.let {MediaController.releaseFuture(it)}
        waiting?.cancel()
    }
    private fun tick() {
        val p=mutablePlayer.value ?: return
        mutable.update {it.copy(position=p.currentPosition.coerceAtLeast(0),duration=p.duration.coerceAtLeast(0),playing=p.isPlaying)}
    }
    private fun snapshot() {
        val p=mutablePlayer.value ?: return
        val size=p.videoSize
        val item=p.currentMediaItem
        val current=item?.let {
            PlaybackMedia.from(it).copy(video=it.mediaMetadata.extras?.getBoolean("video")==true||p.currentTracks.groups.any {group->group.type==C.TRACK_TYPE_VIDEO},
                title=p.mediaMetadata.title?.toString() ?: it.mediaMetadata.title?.toString().orEmpty(),
                artist=p.mediaMetadata.artist?.toString(),album=p.mediaMetadata.albumTitle?.toString(),
                artwork=p.mediaMetadata.artworkUri?.toString() ?: it.mediaMetadata.artworkUri?.toString(),
                videoWidth=it.mediaMetadata.extras?.getInt("video_width")?.takeIf {width->width>0} ?: size.width,
                videoHeight=it.mediaMetadata.extras?.getInt("video_height")?.takeIf {height->height>0} ?: size.height,
                pixelWidthHeightRatio=it.mediaMetadata.extras?.getFloat("video_pixel_ratio",1f)
                    ?.takeIf {ratio->ratio.isFinite()&&ratio>0f&&ratio!=1f}
                    ?: size.pixelWidthHeightRatio.takeIf {ratio->ratio.isFinite()&&ratio>0f}
                    ?: it.mediaMetadata.extras?.getFloat("video_pixel_ratio",1f) ?: 1f,
                rotationDegrees=it.mediaMetadata.extras?.getInt("video_rotation")?.takeIf {rotation->rotation%360!=0}
                    ?: size.unappliedRotationDegrees)
        }
        val videoDisplayAspect=current?.displayAspect
        mutable.value=PlaybackState(current,
            (0 until p.mediaItemCount).map {PlaybackMedia.from(p.getMediaItemAt(it))},p.currentMediaItemIndex.coerceAtLeast(0),p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),p.isPlaying,
            p.playbackState==Player.STATE_READY,p.playbackState==Player.STATE_BUFFERING,p.shuffleModeEnabled,p.repeatMode,p.playbackParameters.speed,p.currentTracks,p.sessionExtras,
            p.playerError?.let {problem(it.errorCode)},videoDisplayAspect ?: 16f/9f,videoDisplayAspect)
    }
    fun clearError() {mutable.update {it.copy(error=null)}}
    fun editLibrary(block:suspend ()->Unit) {scope.launch {
        try {block()} catch(error:CancellationException) {throw error}
        catch(error:Exception) {diagnostic="library:${error.javaClass.simpleName}";mutable.update {it.copy(error=R.string.player_error_library)}}
    }}
    private fun runAction(block:suspend ()->Unit):Job=scope.launch {
        actions++;idleDisconnect?.cancel();idleDisconnect=null
        try {block()} catch(_:CancellationException) {} catch(e:Exception) {
            currentCoroutineContext().ensureActive()
            diagnostic="action:${e.javaClass.simpleName}";android.util.Log.w("MediaFlowPlayback",diagnostic)
            mutable.update {it.copy(error=if(e is SecurityException) R.string.player_error_permission else if(e is java.io.FileNotFoundException) R.string.player_error_missing else R.string.player_error_unavailable)}
        } finally {actions=(actions-1).coerceAtLeast(0);releaseWhenUnowned()}
    }
    private fun action(block:suspend (MediaController)->Unit):Job=runAction {block(connect())}
    private fun launch(block:suspend (MediaController)->Unit) {action(block)}
    private fun supersedePendingExternalRequest() {
        pendingExternalRequestId?.let(repository::markExternalRequestHandled)
        pendingExternalRequestId=null
        mutableExternalMediaResolution.value=ExternalMediaResolution()
    }
    fun play(items:List<PlaybackMedia>,index:Int=0,open:Boolean=true,audioOnly:Boolean=false) {
        val videoOwner=videoExitGeneration
        supersedePendingExternalRequest()
        queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false
        playRequest?.cancel()
        playRequest=runAction {
        val (queue,selected)=LocalPlaybackPolicy.queue(items,index)
        if(queue.isEmpty()) return@runAction
        val uri=android.net.Uri.parse(queue[selected].uri)
        val image=withContext(Dispatchers.IO) {app.contentResolver.getType(uri)?.startsWith("image/")==true}
        if(image) {
            if(open) app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return@runAction
        }
        diagnostic="resolving"
        val media=repository.resolve(queue[selected].uri,queue[selected].title,queue[selected].video)
        currentCoroutineContext().ensureActive()
        if(media.video&&!audioOnly&&videoOwner!=videoExitGeneration) return@runAction
        // Resolve before retaining a controller: metadata I/O may span a lifecycle
        // detach, so commands must target the current connection after that I/O.
        val p=connect()
        if(media.video&&!audioOnly&&videoOwner!=videoExitGeneration) return@runAction
        // Do not inherit an old session's playWhenReady while this video waits
        // for its asynchronous item/mode handshake and may lose its UI owner.
        if(media.video&&!audioOnly) p.pause()
        val sameItem=p.currentMediaItem?.mediaId==media.uri
        val sameQueue=p.mediaItemCount==queue.size&&queue.indices.all {p.getMediaItemAt(it).mediaId==queue[it].uri}
        if(!sameItem||!sameQueue||p.playbackState==Player.STATE_ENDED||p.playbackState==Player.STATE_IDLE) {
            val position=LocalPlaybackPolicy.resume(if(sameItem&&p.playbackState!=Player.STATE_ENDED) p.currentPosition else repository.position(media.uri),media.duration)
            p.setMediaItems(queue.mapIndexed {i,row->if(i==selected) media.item() else row.item()},selected,position)
            p.prepare()
        }
        setAudioMode(p,audioOnly&&media.video,media.uri,newRequest=true)
        if(media.video&&!audioOnly&&videoOwner!=videoExitGeneration) {
            commandResult(p,PlaybackCommands.CLOSE_VIDEO,Bundle().apply {putString("media_id",media.uri)})
            return@runAction
        }
        p.play();snapshot();diagnostic="play_requested"
        if(open) {
            val destination=PlaybackOpenPolicy.destination(audio=!media.video||audioOnly,external=false)
            val activity=when(destination) {
                PlaybackOpenDestination.FULL_SCREEN_PLAYER->PlayerActivity::class.java
                PlaybackOpenDestination.EXTERNAL_FLOATING_PREVIEW->error("Internal playback cannot open the external preview")
            }
            app.startActivity(Intent(app,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }}
    fun playUri(uri:String,title:String?=null,video:Boolean=false,open:Boolean=true)=play(listOf(PlaybackMedia(uri,title.orEmpty(),video)),open=open)
    fun playExternal(intent:Intent,requestId:String?=null)=playExternalRequest(intent,requestId) {context,incoming->ExternalMediaOpen.contentUri(context,incoming)}
    internal suspend fun handoffExternalPreview(media:PlaybackMedia,position:Long,stillOwned:()->Boolean={true}) {
        currentCoroutineContext().ensureActive()
        if(!stillOwned()) throw CancellationException("Preview superseded")
        diagnostic="preview_handoff_grant"
        preparePreviewHandoff(media)
        val handoffGeneration=queueGeneration
        previewHandoffUri=media.uri;previewHandoffGeneration=handoffGeneration
        fun checkOwner() {
            if(!stillOwned()||handoffGeneration!=queueGeneration) throw CancellationException("Preview superseded")
        }
        diagnostic="preview_handoff_connecting"
        val controller=connect()
        currentCoroutineContext().ensureActive();checkOwner()
        val seekPosition=position.coerceAtLeast(0)
        val reusingCurrent=controller.currentMediaItem?.mediaId==media.uri&&
            controller.playbackState!=Player.STATE_IDLE&&controller.playbackState!=Player.STATE_ENDED
        if(reusingCurrent) controller.seekTo(seekPosition) else {
            controller.setMediaItem(media.item(),seekPosition)
            controller.prepare()
        }
        diagnostic="preview_handoff_audio_mode"
        setAudioMode(controller,false,media.uri,newRequest=true)
        currentCoroutineContext().ensureActive();checkOwner()
        diagnostic="preview_handoff_foreground"
        val foregroundRequest=commandResult(controller,PlaybackCommands.EXTERNAL_PREVIEW_HANDOFF,
            Bundle().apply {putString("media_id",media.uri)})
        check(foregroundRequest.resultCode==SessionResult.RESULT_SUCCESS) {"Playback handoff foreground request rejected"}
        currentCoroutineContext().ensureActive();checkOwner()
        diagnostic="preview_handoff_play_requested"
        controller.play()
        diagnostic="preview_handoff_waiting"
        awaitHandoffPlayback(controller,media.uri)
        currentCoroutineContext().ensureActive();checkOwner()
        persistHandoff(controller,media)
        snapshot();diagnostic="external_preview_handoff_playing"
    }
    private fun preparePreviewHandoff(media:PlaybackMedia) {
        require(!media.video&&LocalPlaybackPolicy.accepts(media.uri)) {"Preview handoff accepts local audio only"}
        supersedePendingExternalRequest();queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false
        playRequest?.cancel()
        retainUriGrant(Uri.parse(media.uri))
    }
    private suspend fun awaitHandoffPlayback(controller:MediaController,uri:String) = withTimeout(15_000) {
        while(true) {
            controller.playerError?.let {throw IllegalStateException("Normal player rejected preview handoff")}
            if(controller.currentMediaItem?.mediaId==uri&&controller.isPlaying) return@withTimeout
            delay(50)
        }
    }
    private fun persistHandoff(controller:MediaController,media:PlaybackMedia) {
        repository.savePosition(media.uri,controller.currentPosition)
        val queue=(0 until controller.mediaItemCount).map {index->
            if(index==controller.currentMediaItemIndex) media else PlaybackMedia.from(controller.getMediaItemAt(index))
        }
        repository.saveQueue(queue,controller.currentMediaItemIndex.coerceAtLeast(0))
    }
    private fun playExternalRequest(intent:Intent,requestId:String?,resolveUri:(Context,Intent)->Uri) {
        val videoOwner=videoExitGeneration
        val externalId=requestId ?: java.util.UUID.randomUUID().toString()
        if(pendingExternalRequestId!=externalId) supersedePendingExternalRequest()
        pendingExternalRequestId=externalId
        mutableExternalMediaResolution.value=ExternalMediaResolution(externalId)
        queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false;playRequest?.cancel()
        mutable.update {it.copy(error=null)}
        playRequest=runAction {
          try {
            val uri=withContext(Dispatchers.IO) {resolveUri(app,intent)}
             val mime=withContext(Dispatchers.IO) {ExternalMediaOpen.mimeType(app,intent)}
            withContext(Dispatchers.IO) {repository.savePendingExternalOpen(externalId,uri,mime)}
            if(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0) runCatching {
                app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
              val media=repository.resolve(uri.toString())
              val video=ExternalMediaOpen.videoMime(mime)
              require(if(video) media.video&&(media.mime==null||ExternalMediaOpen.videoMime(media.mime))
                  else !media.video&&(media.mime==null||ExternalAudioOpen.acceptsMime(media.mime)))
              if(pendingExternalRequestId==externalId) mutableExternalMediaResolution.value=ExternalMediaResolution(externalId,true,media)
              currentCoroutineContext().ensureActive()
             if(video&&videoOwner!=videoExitGeneration) {
                 repository.markExternalRequestHandled(externalId)
                 if(pendingExternalRequestId==externalId) pendingExternalRequestId=null
                 return@runAction
             }
             val controller=connect()
             if(video&&videoOwner!=videoExitGeneration) {
                 repository.markExternalRequestHandled(externalId)
                 if(pendingExternalRequestId==externalId) pendingExternalRequestId=null
                 return@runAction
             }
            // A service-owned grant outlives the external Activity's temporary grant.
             retainUriGrant(uri)
             if(video) controller.pause()
             controller.setMediaItem(media.item(),LocalPlaybackPolicy.resume(repository.position(media.uri),media.duration))
             setAudioMode(controller,false,media.uri,newRequest=true)
             if(video&&videoOwner!=videoExitGeneration) {
                 commandResult(controller,PlaybackCommands.CLOSE_VIDEO,Bundle().apply {putString("media_id",media.uri)})
                 repository.markExternalRequestHandled(externalId)
                 if(pendingExternalRequestId==externalId) pendingExternalRequestId=null
                 return@runAction
             }
            controller.prepare();controller.play()
            repository.saveQueue(listOf(media),0,externalId)
            if(pendingExternalRequestId==externalId) pendingExternalRequestId=null
            snapshot();diagnostic=if(video) "external_video_play_requested" else "external_audio_play_requested"
          } catch(error:CancellationException) {throw error}
          catch(error:Exception) {
              if(pendingExternalRequestId==externalId)
                  mutableExternalMediaResolution.value=ExternalMediaResolution(externalId,true,null)
              repository.markExternalRequestHandled(externalId)
              if(pendingExternalRequestId==externalId) pendingExternalRequestId=null
              throw error
          }
        }
    }
    private fun retainUriGrant(uri:Uri) {
        val grantToken=RetainedUriGrantAuthorizations.issue(uri.toString())
        try {
            check(app.startService(Intent(app,MediaPlaybackService::class.java).setAction(MediaPlaybackService.RETAIN_URI)
                .setData(uri).putExtra("grant_token",grantToken).apply {
                    clipData=android.content.ClipData.newRawUri("Audio",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })!=null) {"Playback grant service could not be started"}
        } catch(error:Exception) {
            RetainedUriGrantAuthorizations.cancel(grantToken)
            throw error
        }
    }
    fun open(context:Context) {context.startActivity(Intent(context,PlayerActivity::class.java).apply {if(context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})}
    fun refreshSession()=launch {snapshot()}
    fun toggle()=launch {if(it.playWhenReady) it.pause() else {if(it.playbackState==Player.STATE_ENDED) it.seekToDefaultPosition();it.prepare();it.play()}}
    fun pause() {mutablePlayer.value?.pause()}
    fun seek(position:Long) {mutablePlayer.value?.let {if(it.isCurrentMediaItemSeekable) it.seekTo(position.coerceIn(0,it.duration.coerceAtLeast(0)))}}
    fun jump(delta:Long)=seek(state.value.position+delta)
    fun next() {subtitleRequest++;pendingExternalSubtitle=false;mutablePlayer.value?.seekToNextMediaItem()}
    fun previous() {subtitleRequest++;pendingExternalSubtitle=false;mutablePlayer.value?.seekToPrevious()}
    fun speed(value:Float) {mutablePlayer.value?.setPlaybackSpeed(value.coerceIn(.25f,3f))}
    fun shuffle() {mutablePlayer.value?.let {it.shuffleModeEnabled=!it.shuffleModeEnabled}}
    fun repeat() {mutablePlayer.value?.let {it.repeatMode=when(it.repeatMode) {Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}}}
    fun select(index:Int) {mutablePlayer.value?.let {if(index in 0 until it.mediaItemCount) {subtitleRequest++;pendingExternalSubtitle=false;it.seekToDefaultPosition(index);it.prepare();it.play()}}}
    fun remove(index:Int) {mutablePlayer.value?.let {if(index in 0 until it.mediaItemCount) it.removeMediaItem(index)}}
    fun move(index:Int,to:Int) {mutablePlayer.value?.let {if(index in 0 until it.mediaItemCount&&to in 0 until it.mediaItemCount) it.moveMediaItem(index,to)}}
    fun clear() {supersedePendingExternalRequest();queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false;playRequest?.cancel();mutablePlayer.value?.let {it.pause();it.clearMediaItems()};mutable.value=PlaybackState()}
    internal suspend fun fileChanged(oldUri:String,newUri:String?,name:String)=withContext(Dispatchers.Main.immediate) {
        val position=repository.position(oldUri)
        if(newUri!=null&&newUri!=oldUri&&position>0) repository.savePosition(newUri,position)
        val saved=repository.savedQueue();val current=saved.first.getOrNull(saved.second)?.uri
        if(saved.first.any {it.uri==oldUri}) {
            val updated=saved.first.mapNotNull {if(it.uri!=oldUri) it else newUri?.let {uri->it.copy(uri=uri,title=name)}}
            repository.saveQueue(updated,updated.indexOfFirst {it.uri==(if(current==oldUri) newUri else current)}.coerceAtLeast(0))
        }
        mutablePlayer.value?.let {player->for(index in player.mediaItemCount-1 downTo 0) {
            val item=player.getMediaItemAt(index)
            if(item.mediaId==oldUri) {if(newUri==null) player.removeMediaItem(index) else player.replaceMediaItem(index,item.buildUpon().setMediaId(newUri).setUri(newUri).setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(name).build()).build())}
        }}
    }
    fun enqueue(media:PlaybackMedia,next:Boolean=false) {
      val generation=queueGeneration
      runAction {
        val resolved=repository.resolve(media.uri,media.title,media.video)
        currentCoroutineContext().ensureActive()
        if(generation!=queueGeneration) return@runAction
        val p=connect()
        if(p.mediaItemCount>=LocalPlaybackPolicy.MAX_QUEUE) {mutable.update {it.copy(error=R.string.player_error_queue_full)};return@runAction}
        if(next) p.addMediaItem((p.currentMediaItemIndex+1).coerceIn(0,p.mediaItemCount),resolved.item()) else p.addMediaItem(resolved.item())
      }
    }
    fun retry()=launch {it.prepare();it.play()}
    fun command(name:String,args:Bundle=Bundle.EMPTY)=launch {it.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),args)}
    suspend fun enableFloatingAudio():Boolean = commandResult(connect(),PlaybackCommands.FLOATING_AUDIO,Bundle.EMPTY).resultCode==SessionResult.RESULT_SUCCESS
    suspend fun endNormalForExternalPreview() {
        supersedePendingExternalRequest();queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false;playRequest?.cancel()
        val controller=connect()
        val ended=commandResult(controller,PlaybackCommands.END_NORMAL_FOR_PREVIEW,Bundle.EMPTY)
        check(ended.resultCode==SessionResult.RESULT_SUCCESS)
        disconnect()
        mutable.update {it.copy(playing=false,ready=false,buffering=false)}
        withTimeout(10_000) {while(PlaybackRuntime.active) delay(25)}
    }
    suspend fun backgroundAudio():Boolean {
        val p=connect()
        val id=p.currentMediaItem?.mediaId ?: return false
        setAudioMode(p,true,id)
        check(commandResult(p,PlaybackCommands.BACKGROUND_AUDIO,Bundle.EMPTY).resultCode==SessionResult.RESULT_SUCCESS)
        p.play();snapshot()
        return true
    }
    internal fun cancelPreviewHandoff(uri:String) {
        runAction {stopPreviewHandoff(uri)}
    }
    internal suspend fun stopPreviewHandoff(uri:String):Boolean {
        // A newer request may still be resolving while the old item is visible.
        if(previewHandoffUri!=uri||previewHandoffGeneration!=queueGeneration) return false
        return stopAudio(uri)
    }
    suspend fun stopAudio(expectedUri:String?=null):Boolean {
        if(expectedUri!=null&&state.value.current?.uri!=expectedUri) return false
        val owner=queueGeneration
        if(PlaybackRuntime.active) {
            val controller=connect()
            currentCoroutineContext().ensureActive()
            if(owner!=queueGeneration) return false
            if(expectedUri!=null&&controller.currentMediaItem?.mediaId!=expectedUri) return false
            supersedePendingExternalRequest();queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false;playRequest?.cancel()
            val stopGeneration=queueGeneration
            val result=commandResult(controller,PlaybackCommands.STOP_AUDIO,Bundle().apply {expectedUri?.let {putString("media_id",it)}})
            if(result.resultCode==SessionResult.RESULT_SUCCESS&&stopGeneration==queueGeneration) disconnect()
            return result.resultCode==SessionResult.RESULT_SUCCESS
        }
        supersedePendingExternalRequest();queueGeneration++;subtitleRequest++;pendingExternalSubtitle=false;playRequest?.cancel()
        return true
    }
    fun sleep(minutes:Int)=command(PlaybackCommands.SLEEP,Bundle().apply {putInt("minutes",minutes)})
    fun option(key:String,value:Boolean)=command(PlaybackCommands.OPTIONS,Bundle().apply {putBoolean(key,value)})
    private suspend fun commandResult(p:MediaController,name:String,args:Bundle):SessionResult {
        val done=CompletableDeferred<SessionResult>()
        val result=p.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),args)
        result.addListener({try {done.complete(result.get())} catch(error:Exception) {done.completeExceptionally(error)}},ContextCompat.getMainExecutor(app))
        return withTimeout(10000) {done.await()}
    }
    private suspend fun setAudioMode(p:MediaController,audioOnly:Boolean,id:String,newRequest:Boolean=false) {
        val result=commandResult(p,PlaybackCommands.OPTIONS,Bundle().apply {putString("media_id",id);putBoolean("audio_only",audioOnly);putBoolean("play_request",newRequest)})
        check(result.resultCode==SessionResult.RESULT_SUCCESS) {"Playback mode request rejected"}
    }
    suspend fun useAudioOnly():Boolean {
        val p=connect()
        val id=p.currentMediaItem?.mediaId ?: return false
        setAudioMode(p,true,id)
        p.play();snapshot()
        return true
    }
    fun closeVideo() {
        // Closing an empty/loading screen must also invalidate work still inside
        // provider I/O. Audio requests intentionally survive this ownership fence.
        videoExitGeneration++
        val current=state.value
        val player=mutablePlayer.value
        val item=player?.currentMediaItem
        val id=if(item!=null) item.takeIf {it.mediaMetadata.extras?.getBoolean("video")==true||player.currentTracks.groups.any {group->group.type==C.TRACK_TYPE_VIDEO}}?.mediaId
            else current.current?.takeIf {it.video}?.uri
        if(id==null) return
        // The service checks the actual item and explicit audio-only mode. Its
        // answer must not depend on a detached controller's cached UI snapshot.
        launch {p->commandResult(p,PlaybackCommands.CLOSE_VIDEO,Bundle().apply {putString("media_id",id)})}
    }
    fun track(type:Int,group:Tracks.Group?=null,index:Int=0,off:Boolean=false) {
        mutablePlayer.value?.let {p->
            val builder=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,off)
            if(group!=null) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index))
            p.trackSelectionParameters=builder.build()
        }
    }
    fun subtitle(uri:Uri) {
      val token=++subtitleRequest
      val intended=mutablePlayer.value?.currentMediaItem?.mediaId ?: state.value.current?.uri ?: return
      runAction {
        try {
            val mime=repository.subtitle(uri)
            currentCoroutineContext().ensureActive()
            if(token!=subtitleRequest) return@runAction
            val p=connect()
            if(p.currentMediaItem?.mediaId!=intended) return@runAction
            val index=p.currentMediaItemIndex;val position=p.currentPosition;val playing=p.playWhenReady
            val item=p.currentMediaItem ?: return@runAction
            val subtitle=MediaItem.SubtitleConfiguration.Builder(uri).setId("mediaflow_external").setLanguage("und").setMimeType(mime).setLabel(app.getString(R.string.player_external_subtitle)).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            pendingExternalSubtitle=true;diagnostic="subtitle_validated"
            // Session timelines intentionally omit localConfiguration in controller
            // snapshots. Reattach our validated content URI before replacement.
            val replacement=item.buildUpon().setUri(item.mediaId).setSubtitleConfigurations(listOf(subtitle)).build()
            val queue=(0 until p.mediaItemCount).map {i->if(i==index) replacement else p.getMediaItemAt(i).let {it.buildUpon().setUri(it.mediaId).build()}}
            // Rebuild the source so a new side-loaded subtitle is actually merged,
            // rather than treating this as a metadata-only source update.
            p.setMediaItems(queue,index,position);diagnostic="subtitle_requested"
            p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setSelectUndeterminedTextLanguage(true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            p.seekTo(index,position);p.prepare();p.playWhenReady=playing
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(token==subtitleRequest) {pendingExternalSubtitle=false;diagnostic="subtitle_failed:${e.javaClass.simpleName}";mutable.update {it.copy(error=R.string.player_error_subtitle)}}}
      }
    }
    companion object {
        fun problem(code:Int):Int=when(code) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND->R.string.player_error_missing
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION->R.string.player_error_permission
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES->R.string.player_error_codec
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,PlaybackException.ERROR_CODE_DECODING_FAILED->R.string.player_error_decoder
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED->R.string.player_error_corrupt
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED->R.string.player_error_audio
            else->R.string.player_error_unavailable
        }
    }
}

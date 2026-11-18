package com.zdownloader.app.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.zdownloader.app.R
import kotlinx.coroutines.*

internal object PlaybackCommands {
    const val SLEEP="mediaflow.sleep"
    const val OPTIONS="mediaflow.playback.options"
    const val EFFECTS="mediaflow.audio.effects"
    const val CLOSE_VIDEO="mediaflow.playback.close_video"
    const val FLOATING_AUDIO="mediaflow.playback.floating_audio"
    const val BACKGROUND_AUDIO="mediaflow.playback.background_audio"
    const val STOP_AUDIO="mediaflow.playback.stop_audio"
    const val END_NORMAL_FOR_PREVIEW="mediaflow.playback.end_normal_for_preview"
    const val EXTERNAL_PREVIEW_HANDOFF="mediaflow.playback.external_preview_handoff"
    val all=listOf(SLEEP,OPTIONS,EFFECTS,CLOSE_VIDEO,FLOATING_AUDIO,BACKGROUND_AUDIO,STOP_AUDIO,END_NORMAL_FOR_PREVIEW,EXTERNAL_PREVIEW_HANDOFF)
}
internal object PlaybackRuntime {var active=false}
internal object RetainedUriGrantAuthorizations {
    private val pending=LinkedHashMap<String,String>()
    @Synchronized fun issue(uri:String):String {
        val token=java.util.UUID.randomUUID().toString()
        pending[token]=uri
        while(pending.size>64) pending.remove(pending.keys.first())
        return token
    }
    @Synchronized fun consume(token:String?,uri:String?):Boolean {
        if(token==null||uri==null) return false
        val expected=pending.remove(token) ?: return false
        return expected==uri&&uri.startsWith("content://")
    }
    @Synchronized fun cancel(token:String) {pending.remove(token)}
}

/** The only decoder/session owner in MediaFlow. No Activity owns an ExoPlayer. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class MediaPlaybackService:MediaSessionService() {
    companion object {
        internal const val RETAIN_URI="com.zdownloader.app.playback.RETAIN_URI"
        private const val PLAYBACK_NOTIFICATION_ID=57001
        private const val PLAYBACK_NOTIFICATION_CHANNEL="mediaflow_playback"
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==RETAIN_URI) {
            val accepted=RetainedUriGrantAuthorizations.consume(intent.getStringExtra("grant_token"),intent.dataString)
            // An external caller's rejected start must not stop a session that already
            // owns queued or playing media. Only discard a truly idle service start.
            if(!accepted) {
                if(engine.mediaItemCount==0&&!engine.isPlaying) {stopSelfResult(startId);return START_NOT_STICKY}
                return START_STICKY
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent,flags,startId)
    }
    private lateinit var engine:ExoPlayer
    private lateinit var floatingAudio:FloatingAudioWindow
    private var session:MediaSession?=null
    private lateinit var store:PlaybackRepository
    private val handler=Handler(Looper.getMainLooper())
    private var savedId:String?=null
    private var sleepDeadline=0L
    private var endTrack=false
    private var backgroundVideo=false
    private var audioOnly=false
    private var modeOwner:String?=null
    private var explicitAudioUri:String?=null
    private var externalHandoffForegroundRequested=false
    private data class PendingMode(val uri:String,val audioOnly:Boolean,val result:SettableFuture<SessionResult>)
    private var pendingMode:PendingMode?=null
    private var equalizer:Equalizer?=null
    private var bass:BassBoost?=null
    private var effectsRequested=false
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var metadataJob:Job?=null
    private var artworkJob:Job?=null
    private val enriched=mutableSetOf<String>()
    private val sleepTask=Runnable {engine.pause();sleepDeadline=0;publishExtras()}
    private val checkpoint=object:Runnable {
        override fun run() {persistPosition();if(engine.isPlaying) handler.postDelayed(this,15000)}
    }
    private fun persistPosition() {engine.currentMediaItem?.mediaId?.let {store.savePosition(it,if(engine.playbackState==Player.STATE_ENDED) 0 else engine.currentPosition)}}
    private fun persistQueue() {
        val queue=(0 until engine.mediaItemCount).map {PlaybackMedia.from(engine.getMediaItemAt(it))}
        PlaybackGrantReferences.update(queue.map {it.uri})
        store.saveQueue(queue,engine.currentMediaItemIndex.coerceAtLeast(0))
    }
    override fun onCreate() {
        super.onCreate()
        store=PlaybackRepository(this)
        // Background audio is an explicit choice for one media URI, never a
        // global preference inherited by another video.
        explicitAudioUri=store.text("audio_only_media","").takeIf {it.isNotBlank()}
        val source=DefaultMediaSourceFactory(DataSource.Factory {LocalPlaybackDataSource(this)})
        engine=ExoPlayer.Builder(this).setMediaSourceFactory(source).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            setSeekParameters(SeekParameters.EXACT)
        }
        floatingAudio=FloatingAudioWindow(this,engine)
        val notification=DefaultMediaNotificationProvider.Builder(this).setNotificationId(PLAYBACK_NOTIFICATION_ID)
            .setChannelId(PLAYBACK_NOTIFICATION_CHANNEL).setChannelName(R.string.player_notification_channel).build()
        notification.setSmallIcon(R.drawable.ic_zd_notification)
        setMediaNotificationProvider(notification)
        session=MediaSession.Builder(this,engine).setCallback(SessionCallback()).setSessionActivity(
            PendingIntent.getActivity(this,57002,Intent(this,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        engine.addListener(object:Player.Listener {
            override fun onMediaItemTransition(item:MediaItem?,reason:Int) {
                val waiting=pendingMode
                if(waiting!=null&&waiting.uri==item?.mediaId) {
                    pendingMode=null
                    setAudioMode(waiting.audioOnly,waiting.uri)
                    waiting.result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } else if(item?.mediaId!=modeOwner) setAudioMode(item?.mediaId==explicitAudioUri&&item!=null,item?.mediaId)
                artworkJob?.cancel()
                savedId=item?.mediaId;lastPosition=0;persistQueue()
                metadataJob?.cancel()
                if(item!=null&&enriched.add(item.mediaId)) metadataJob=scope.launch {
                    runCatching {store.resolve(item.mediaId,item.mediaMetadata.title?.toString(),item.mediaMetadata.extras?.getBoolean("video")==true)}.onSuccess {media->
                        if(engine.currentMediaItem?.mediaId==item.mediaId) engine.replaceMediaItem(engine.currentMediaItemIndex,item.buildUpon().setMediaMetadata(media.item().mediaMetadata).build())
                    }
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata:MediaMetadata) {
                val item=engine.currentMediaItem ?: return
                val bytes=mediaMetadata.artworkData ?: return
                if(item.mediaMetadata.artworkUri!=null||bytes.size>EmbeddedArtwork.MAX_ENCODED_BYTES) return
                artworkJob?.cancel()
                artworkJob=scope.launch {
                    val uri=withContext(Dispatchers.IO) {EmbeddedArtwork.cache(this@MediaPlaybackService,bytes)} ?: return@launch
                    if(engine.currentMediaItem?.mediaId==item.mediaId) engine.replaceMediaItem(engine.currentMediaItemIndex,
                        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(android.net.Uri.parse(uri)).build()).build())
                }
            }
            override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int) {
                old.mediaItem?.mediaId?.let {store.savePosition(it,if(reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION) 0 else old.positionMs)}
                new.mediaItem?.mediaId?.let {store.savePosition(it,new.positionMs)}
                lastPosition=new.positionMs
            }
            override fun onTimelineChanged(timeline:Timeline,reason:Int) {persistQueue()}
            override fun onIsPlayingChanged(isPlaying:Boolean) {
                if(isPlaying) externalHandoffForegroundRequested=false
                if(isPlaying) ExternalAudioPreview.stop()
                handler.removeCallbacks(checkpoint);persistPosition()
                if(isPlaying) handler.postDelayed(checkpoint,15000)
            }
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                if(playWhenReady) ExternalAudioPreview.stop()
                if(playWhenReady&&engine.playbackState==Player.STATE_IDLE&&engine.mediaItemCount>0) engine.prepare()
                if(!playWhenReady&&endTrack&&reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    endTrack=false;engine.pauseAtEndOfMediaItems=false;publishExtras()
                }
                if(!playWhenReady) persistPosition()
            }
            override fun onPlaybackStateChanged(playbackState:Int) {if(playbackState==Player.STATE_ENDED) {savedId?.let {store.savePosition(it,0)}}}
            override fun onAudioSessionIdChanged(audioSessionId:Int) {releaseEffects();if(effectsRequested) probeEffects();publishExtras()}
            override fun onPlayerError(error:PlaybackException) {
                // Codes are useful for diagnostics; URI, filename and provider exception are not logged.
                android.util.Log.w("MediaFlowPlayback","Playback failed with Media3 code ${error.errorCode}")
                persistPosition()
            }
        })
        val restored=store.savedQueue()
        PlaybackGrantReferences.update(restored.first.map {it.uri})
        if(restored.first.isNotEmpty()) {
            val selected=restored.first[restored.second]
            engine.setMediaItems(restored.first.map {it.item()},restored.second,LocalPlaybackPolicy.resume(store.position(selected.uri),selected.duration))
            savedId=selected.uri
        }
        PlaybackRuntime.active=true
        publishExtras()
    }
    private var lastPosition=0L
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession?=session
    override fun onUpdateNotificationAsync(session:MediaSession,startInForegroundRequired:Boolean):ListenableFuture<Void?> =
        super.onUpdateNotificationAsync(session,startInForegroundRequired||externalHandoffForegroundRequested)

    private inner class SessionCallback:MediaSession.Callback {
        override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
            val own=controller.uid==Process.myUid()
            val commands=if(own) MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                else Player.Commands.Builder().addAll(*ExternalControllerPolicy.commands.toIntArray()).build()
            val custom=(if(own) MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS else androidx.media3.session.SessionCommands.EMPTY).buildUpon()
            if(own) PlaybackCommands.all.forEach {custom.add(SessionCommand(it,Bundle.EMPTY))}
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).setAvailableSessionCommands(custom.build()).build()
        }
        override fun onAddMediaItems(session:MediaSession,controller:MediaSession.ControllerInfo,mediaItems:List<MediaItem>):ListenableFuture<List<MediaItem>> {
            if(controller.uid!=Process.myUid()||mediaItems.size>LocalPlaybackPolicy.MAX_QUEUE||mediaItems.any {!LocalPlaybackPolicy.accepts(it.localConfiguration?.uri?.toString().orEmpty())})
                return Futures.immediateFailedFuture(SecurityException("Local playback request denied"))
            return Futures.immediateFuture(mediaItems)
        }
        override fun onCustomCommand(session:MediaSession,controller:MediaSession.ControllerInfo,customCommand:SessionCommand,args:Bundle):ListenableFuture<SessionResult> {
            if(controller.uid!=Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            when(customCommand.customAction) {
                PlaybackCommands.BACKGROUND_AUDIO->floatingAudio.disable()
                PlaybackCommands.END_NORMAL_FOR_PREVIEW->{
                    // A deliberate mode exit, not an overlay-visibility notification trick.
                    externalHandoffForegroundRequested=false
                    floatingAudio.disable()
                    engine.pause()
                    persistPosition();persistQueue()
                    engine.stop()
                    pauseAllPlayersAndStopSelf()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).cancel(PLAYBACK_NOTIFICATION_ID)
                }
                PlaybackCommands.EXTERNAL_PREVIEW_HANDOFF->{
                    val requestedMediaId=args.getString("media_id")
                    if(requestedMediaId.isNullOrEmpty()||requestedMediaId!=engine.currentMediaItem?.mediaId||
                        !LocalPlaybackPolicy.accepts(requestedMediaId))
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    externalHandoffForegroundRequested=true
                    val notificationUpdate=this@MediaPlaybackService.onUpdateNotificationAsync(session,true)
                    notificationUpdate.addListener({
                        if(notificationUpdate.isCancelled||runCatching {notificationUpdate.get()}.isFailure)
                            externalHandoffForegroundRequested=false
                    },MoreExecutors.directExecutor())
                    return Futures.transform(notificationUpdate,
                        {SessionResult(SessionResult.RESULT_SUCCESS)},MoreExecutors.directExecutor())
                }
                PlaybackCommands.STOP_AUDIO->{
                    if(args.containsKey("media_id")&&args.getString("media_id")!=engine.currentMediaItem?.mediaId)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    floatingAudio.disable()
                    externalHandoffForegroundRequested=false
                    persistPosition()
                    handler.removeCallbacks(sleepTask);sleepDeadline=0;endTrack=false
                    engine.pauseAtEndOfMediaItems=false
                    engine.pause();engine.stop();engine.clearMediaItems()
                    setAudioMode(false,null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).cancel(PLAYBACK_NOTIFICATION_ID)
                    stopSelf()
                }
                PlaybackCommands.FLOATING_AUDIO->{
                    val video=engine.currentMediaItem?.mediaMetadata?.extras?.getBoolean("video")==true||engine.currentTracks.groups.any {it.type==C.TRACK_TYPE_VIDEO}
                    if(video&&!audioOnly||!floatingAudio.enable()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                PlaybackCommands.SLEEP->{
                    handler.removeCallbacks(sleepTask);sleepDeadline=0
                    val minutes=args.getInt("minutes")
                    endTrack=minutes==-1;engine.pauseAtEndOfMediaItems=endTrack
                    if(minutes in listOf(5,15,30,60)) {sleepDeadline=SystemClock.elapsedRealtime()+minutes*60000L;handler.postDelayed(sleepTask,minutes*60000L)}
                }
                PlaybackCommands.OPTIONS->{
                    if(args.containsKey("audio_only")) {
                        val requested=args.getString("media_id")
                        if(requested!=null&&requested!=engine.currentMediaItem?.mediaId) {
                            if(!args.getBoolean("play_request")||!LocalPlaybackPolicy.accepts(requested)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                            // setMediaItems resolves asynchronously in MediaSession. Complete
                            // the mode handshake on its actual item transition, not on the
                            // controller's optimistic timeline or a timed retry.
                            pendingMode?.result?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                            val result=SettableFuture.create<SessionResult>()
                            pendingMode=PendingMode(requested,args.getBoolean("audio_only"),result)
                            return result
                        }
                        setAudioMode(args.getBoolean("audio_only"),engine.currentMediaItem?.mediaId)
                    }
                }
                PlaybackCommands.CLOSE_VIDEO->{
                    val item=engine.currentMediaItem
                    val video=item?.mediaMetadata?.extras?.getBoolean("video")==true||engine.currentTracks.groups.any {it.type==C.TRACK_TYPE_VIDEO}
                    if(pendingMode==null&&item!=null&&item.mediaId==args.getString("media_id")&&video&&!audioOnly) {
                        externalHandoffForegroundRequested=false
                        persistPosition()
                        handler.removeCallbacks(sleepTask);sleepDeadline=0;endTrack=false;engine.pauseAtEndOfMediaItems=false
                        engine.pause();engine.stop();engine.clearMediaItems()
                        setAudioMode(false,null);persistQueue()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        getSystemService(NotificationManager::class.java).cancel(PLAYBACK_NOTIFICATION_ID)
                        stopSelf()
                    }
                }
                PlaybackCommands.EFFECTS->{
                    effectsRequested=true;probeEffects()
                    runCatching {
                        if(args.containsKey("enabled")) {val enabled=args.getBoolean("enabled");equalizer?.enabled=enabled;bass?.enabled=enabled}
                        if(args.containsKey("band")) equalizer?.let {effect->
                            val band=args.getInt("band");if(band in 0 until effect.numberOfBands) effect.setBandLevel(band.toShort(),args.getInt("level").coerceIn(effect.bandLevelRange[0].toInt(),effect.bandLevelRange[1].toInt()).toShort())
                        }
                        if(args.containsKey("bass")) bass?.takeIf {it.strengthSupported}?.setStrength(args.getInt("bass").coerceIn(0,1000).toShort())
                    }.onFailure {releaseEffects()}
                }
                else->return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            publishExtras()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    private fun probeEffects() {
        if(engine.audioSessionId==C.AUDIO_SESSION_ID_UNSET||engine.audioSessionId==0) return
        if(equalizer==null) equalizer=runCatching {Equalizer(0,engine.audioSessionId).let {if(it.hasControl()) it.apply {enabled=false} else {it.release();null}}}.getOrNull()
        if(bass==null) bass=runCatching {BassBoost(0,engine.audioSessionId).let {if(it.hasControl()&&it.strengthSupported) it.apply {enabled=false} else {it.release();null}}}.getOrNull()
    }
    private fun releaseEffects() {runCatching {equalizer?.release()};runCatching {bass?.release()};equalizer=null;bass=null}
    private fun setAudioMode(enabled:Boolean,uri:String?) {
        modeOwner=uri
        audioOnly=enabled&&uri!=null
        backgroundVideo=false
        explicitAudioUri=uri.takeIf {audioOnly}
        store.text("audio_only_media",explicitAudioUri.orEmpty())
        engine.trackSelectionParameters=engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO,audioOnly).build()
        publishExtras()
    }
    private fun publishExtras() {
        val extras=Bundle().apply {
            putLong("sleep_deadline",sleepDeadline);putBoolean("sleep_end",endTrack);putBoolean("background_video",backgroundVideo);putBoolean("audio_only",audioOnly)
            equalizer?.let {effect->runCatching {
                putBoolean("effects_enabled",effect.enabled)
                putIntArray("eq_levels",IntArray(effect.numberOfBands.toInt()) {effect.getBandLevel(it.toShort()).toInt()})
                putIntArray("eq_frequencies",IntArray(effect.numberOfBands.toInt()) {effect.getCenterFreq(it.toShort())/1000})
                putInt("eq_min",effect.bandLevelRange[0].toInt());putInt("eq_max",effect.bandLevelRange[1].toInt())
            }}
            bass?.let {putBoolean("bass_available",true);putInt("bass_strength",it.roundedStrength.toInt());if(equalizer==null) putBoolean("effects_enabled",it.enabled)}
            putBoolean("effects_probed",effectsRequested)
        }
        session?.setSessionExtras(extras)
    }
    override fun onDestroy() {
        floatingAudio.release()
        pendingMode?.result?.set(SessionResult(SessionError.ERROR_INVALID_STATE));pendingMode=null
        persistPosition();persistQueue();handler.removeCallbacksAndMessages(null);scope.cancel();releaseEffects()
        session?.release();session=null;engine.release();PlaybackRuntime.active=false
        super.onDestroy()
    }
}

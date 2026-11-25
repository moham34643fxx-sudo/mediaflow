package com.zdownloader.app.background

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zdownloader.app.MainActivity
import com.zdownloader.app.R
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context, private val settings: SettingsRepository,private val dao:DownloadDao) {
    @Volatile private var language = "ar"
    init {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        scope.launch { settings.flow.collect { language = it.language } }
        scope.launch {
            val previous=mutableMapOf<String,String>()
            dao.batchSummaries().distinctUntilChanged().collect {summaries->
                val ids=summaries.map {it.batch.id}.toSet()
                previous.keys.filter {it !in ids}.forEach {dismiss("batch:$it");previous.remove(it)}
                val prefs=settings.current()
                for(batch in summaries) {
                    val old=previous.put(batch.batch.id,batch.status)
                    val id="batch:${batch.batch.id}"
                    if(batch.status in setOf("PAUSED","CANCELLED")) {dismiss(id);continue}
                    val terminal=batch.finished
                    if(terminal&&old==null) {dismiss(id);continue}
                    if(terminal&&old==batch.status) continue
                    val result=if(terminal) batchTerminalNotice(batch.completed,batch.failed,prefs) else null
                    if(terminal&&result==null) {dismiss(id);continue}
                    val channel=if(!terminal) CHANNEL else if(result==BatchNotice.FAILED) FAILED_CHANNEL else COMPLETED_CHANNEL
                    if(terminal&&!optionalAllowed(channel)) {dismiss(id);continue}
                    if(terminal) dismiss(id)
                    val progress=batch.progress
                    fun size(bytes:Long)=android.text.format.Formatter.formatShortFileSize(context,bytes)
                     val amount=progress.expected?.let {text(R.string.pl_transfer_amount,size(progress.bytes),(if(progress.estimated) "≈ " else "")+size(it))} ?: text(R.string.pl_transferred,size(progress.bytes))
                     val phase=when(batch.currentState) {
                         "ANALYZING"->R.string.analyzing;"DOWNLOADING_AUDIO"->R.string.downloading_audio;"DOWNLOADING_VIDEO","DOWNLOADING"->R.string.downloading_video
                         "WAITING_FOR_PROCESSOR"->R.string.waiting_processing;"CONVERTING"->R.string.converting;"MERGING"->R.string.merging
                         "VALIDATING"->R.string.validating;"SAVING"->R.string.saving;"PROCESSING"->R.string.processing;else->R.string.queued
                     }
                     val notice=base(batch.batch.title,"$amount · ${text(R.string.pl_completed_fraction,batch.completed,batch.selected)}",channel)
                          .setSubText(text(if(batch.status=="FAILED") R.string.notification_failed_title else if(batch.status=="PARTIAL") R.string.pl_partial else if(terminal) R.string.completed else phase))
                          .setStyle(NotificationCompat.BigTextStyle().bigText(
                              if(terminal) text(R.string.batch_result_counts,batch.completed,batch.failed,batch.cancelled)
                              else listOfNotNull(amount,batch.currentTitle?.let {text(R.string.pl_current,text(phase),it)}).joinToString("\n")))
                        .setOngoing(!terminal).setAutoCancel(terminal)
                        .setProgress(if(terminal) 0 else 100,if(terminal) 0 else progress.percent ?: 0,!terminal&&progress.indeterminate)
                    if(!terminal) notice.addAction(0,text(R.string.pause),action(batch.batch.id,"batch_pause"))
                        .addAction(0,text(R.string.cancel),action(batch.batch.id,"batch_cancel"))
                    notify(id,notice.build())
                }
            }
        }
    }
    private fun text(id: Int,vararg args:Any): String { val config = android.content.res.Configuration(context.resources.configuration);config.setLocale(Locale.forLanguageTag(language));return context.createConfigurationContext(config).getString(id,*args) }
    private fun plural(id:Int,count:Int,vararg args:Any):String {val config=android.content.res.Configuration(context.resources.configuration);config.setLocale(Locale.forLanguageTag(language));return context.createConfigurationContext(config).resources.getQuantityString(id,count,*args)}
    private fun quality(format:Format):String {
        val edge=verifiedQualityEdge(format) ?: return text(R.string.share_unknown_quality)
        return qualityCategoryName(edge)
    }
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun init() { if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannels(listOf(
        NotificationChannel(CHANNEL,text(R.string.downloads),NotificationManager.IMPORTANCE_LOW),
        NotificationChannel(COMPLETED_CHANNEL,text(R.string.notification_completed_title),NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannel(FAILED_CHANNEL,text(R.string.notification_failed_title),NotificationManager.IMPORTANCE_DEFAULT))) }
    private fun launch() = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun base(title: String, status: String,channel:String=CHANNEL): NotificationCompat.Builder { init(); return NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_zd_notification).setContentTitle(title).setContentText(status).setContentIntent(launch()).setOnlyAlertOnce(true) }
    private fun optionalAllowed(channel:String):Boolean {
        init()
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT<26 || manager.getNotificationChannel(channel)?.importance!=NotificationManager.IMPORTANCE_NONE)
    }
    fun summary(): Notification {
        // The execution owner registers ID 411. Do not overwrite it via notify():
        // Android adds UIDT/FGS lifecycle flags which an ordinary update can lose.
        // This is a neutral group header; live progress/actions belong to children.
        return base(text(R.string.app_name),text(R.string.downloads)).setGroup(ACTIVE_GROUP).setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setOngoing(true).build()
    }
    private fun phase(entity:DownloadEntity):Int {
        val selection=runCatching {Selection.parse(entity.selection)}.getOrNull()
        val audio=selection?.audioOnly==true
        if(selection?.imageOnly==true) return if(entity.status.startsWith("DOWNLOADING")) R.string.downloading_photo else R.string.preparing_photo
        return if(entity.status.startsWith("DOWNLOADING")) {
            if(audio||entity.status=="DOWNLOADING_AUDIO") R.string.downloading_audio else R.string.downloading_video
        } else if(audio) R.string.preparing_audio else R.string.preparing_video
    }
    private fun action(id: String, action: String): PendingIntent = PendingIntent.getBroadcast(context,"$id:$action".hashCode(),Intent(context,DownloadActionReceiver::class.java).putExtra("id",id).putExtra("action",action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun progress(entity: DownloadEntity, bytes: Long, total: Long?, speed: Long = 0) {
        if(entity.batchId!=null) return
        val percent = if(total != null && total > 0) ((bytes.toDouble() * 100 / total).coerceIn(0.0,100.0)).toInt() else 0
        val size=android.text.format.Formatter.formatShortFileSize(context,bytes)
        val selection=runCatching { Selection.parse(entity.selection) }.getOrNull()
        val estimated=entity.sizeKind!="EXACT"
        val amount="$size${if(total!=null) " / ${if(estimated) "≈ " else ""}${android.text.format.Formatter.formatShortFileSize(context,total)}" else ""}"
        val format=selection?.let {listOfNotNull(if(it.audioOnly) null else quality(it.primary),it.output.uppercase()).joinToString(" · ")}.orEmpty()
        val rate=if(speed>0) " · ${android.text.format.Formatter.formatShortFileSize(context,speed)}/${if(language=="ar") "ث" else "s"}" else ""
        val phase=if(selection==null) R.string.analyzing else phase(entity)
        val notification = base(entity.title,"${text(phase)} · $amount$rate").setSubText(format).setGroup(ACTIVE_GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setOngoing(true).setProgress(100,percent,total == null || estimated)
            .addAction(0,text(R.string.pause),action(entity.id,"pause")).addAction(0,text(R.string.cancel),action(entity.id,"cancel")).build()
        notify(entity.id,notification)
    }
    fun processing(entity: DownloadEntity, stage: String = "PROCESSING") { if(entity.batchId!=null) return;notify(entity.id,base(entity.title,text(phase(entity.copy(status=stage)))).setSubText(text(when(stage) { "WAITING_FOR_PROCESSOR"->R.string.waiting_processing;"MERGING"->R.string.merging;"CONVERTING"->R.string.converting;"VALIDATING"->R.string.validating;"SAVING"->R.string.saving;else->R.string.processing })).setGroup(ACTIVE_GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setOngoing(true).setProgress(0,0,true).addAction(0,text(R.string.cancel),action(entity.id,"cancel")).build()) }
    fun waiting(entity:DownloadEntity,reason:ErrorCode) {
        if(entity.batchId!=null) return
        notify(entity.id,base(entity.title,text(errorResource(reason))).setGroup(ACTIVE_GROUP).setOngoing(true)
            .setProgress(0,0,true).addAction(0,text(R.string.pause),action(entity.id,"pause")).addAction(0,text(R.string.cancel),action(entity.id,"cancel")).build())
    }
    fun analyzing(entity:DownloadEntity) {
        notify(entity.id,base(entity.title,text(R.string.analyzing)).setGroup(ACTIVE_GROUP).setOngoing(true)
            .setProgress(0,0,true).addAction(0,text(R.string.pause),action(entity.id,"pause"))
            .addAction(0,text(R.string.cancel),action(entity.id,"cancel")).build())
    }
    suspend fun completed(entity: DownloadEntity, uri: Uri, selection: Selection) {
        if(entity.batchId!=null) {dismiss(entity.id);dao.compactBatchHistory(entity.id,selection.historyJson());dao.notificationDelivered(entity.id);return}
        val prefs=settings.current()
        language=prefs.language
        val current=dao.get(entity.id) ?: return
        if(current.status!="COMPLETED"||current.uri!=uri.toString()) return
        if(!prefs.completionNotifications || !prefs.notifications || !optionalAllowed(COMPLETED_CHANNEL)) {dismiss(entity.id);dao.notificationDelivered(entity.id);return}
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri,when {selection.imageOnly->"image/*";selection.audioOnly->"audio/*";else->"video/*"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val open = PendingIntent.getActivity(context,entity.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details=listOfNotNull(if(selection.audioOnly) null else quality(selection.primary),
            if(selection.audioOnly) null else selection.primary.width?.let {w->selection.primary.height?.let {h->"\u2066$w×$h\u2069"}},selection.output.uppercase()).joinToString(" · ")
        val notice=base(current.title,text(when {selection.imageOnly->R.string.photo_download_completed;selection.audioOnly->R.string.audio_download_completed;else->R.string.video_download_completed}),COMPLETED_CHANNEL).setSubText(details).setContentIntent(open).setOngoing(false).setAutoCancel(true).addAction(0,text(R.string.open),open).build()
        // Detach the old ongoing group member before replacing its ID. The
        // execution owner may remove its group summary as the job finishes.
        manager.cancel(entity.id.hashCode())
        try {manager.notify(entity.id.hashCode(),notice)}
        catch(e:SecurityException) {com.zdownloader.app.engine.NetworkDiagnostics.record("notification","system_permission_blocked",null,operation=entity.id)}
        dao.notificationDelivered(entity.id)
    }
    suspend fun deliverPending():Boolean {
        for(entity in dao.pendingNotifications()) {
            val uri=entity.uri?.let(Uri::parse) ?: continue
            // Replay uses the same notification ID and never re-runs media processing.
            try {
                context.contentResolver.openFileDescriptor(uri,"r")?.use {completed(entity,uri,Selection.parse(entity.selection))}
                    ?: throw java.io.FileNotFoundException()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {com.zdownloader.app.engine.NetworkDiagnostics.record("notification","completion_deferred",ErrorCode.FILE_WRITE_FAILED,operation=entity.id)}
        }
        return dao.pendingNotifications().isEmpty()
    }
    suspend fun failed(entity: DownloadEntity, code: ErrorCode) {
        if(entity.batchId!=null) {dismiss(entity.id);return}
        val prefs=settings.current();language=prefs.language
        val current=dao.get(entity.id) ?: return
        // A delayed failure callback must not notify over a resumed/completed row.
        if(current.status!="FAILED"||current.error!=code.name) return
        if(!prefs.notifications||!prefs.failureNotifications||!optionalAllowed(FAILED_CHANNEL)) {dismiss(entity.id);return}
        manager.cancel(entity.id.hashCode())
        notify(entity.id,base(current.title,text(errorResource(code)),FAILED_CHANNEL).setOngoing(false).setAutoCancel(true).build())
    }
    fun dismiss(id: String) { manager.cancel(id.hashCode()) }
    fun recovery() { notify("recovery",base(text(R.string.app_name),text(R.string.continue_queue)).setAutoCancel(true).build()) }
    private fun notify(id: String, notification: Notification) {
        try {manager.notify(id.hashCode(),notification)}
        catch(e:SecurityException) {com.zdownloader.app.engine.NetworkDiagnostics.record("notification","system_permission_blocked",null,operation=id)}
    }
    companion object {
        const val CHANNEL = "z_downloads";const val COMPLETED_CHANNEL="z_completed";const val FAILED_CHANNEL="z_failed"
        const val ACTIVE_GROUP="z_active_downloads"
    }
}
fun errorResource(code: ErrorCode): Int = when(code) {
    ErrorCode.FILE_IN_USE -> R.string.fm_file_in_use
    ErrorCode.FILE_NAME_CONFLICT -> R.string.fm_name_conflict
    ErrorCode.PLAYLIST_DETECTED -> R.string.pl_title
    ErrorCode.BATCH_LIMIT_EXCEEDED -> R.string.error_batch_limit
    ErrorCode.WIFI_REQUIRED -> R.string.error_wifi_required
    ErrorCode.SCHEDULING_FAILED -> R.string.error_scheduling
    ErrorCode.START_DELAYED -> R.string.error_start_delayed
    ErrorCode.DNS_FAILED -> R.string.error_dns
    ErrorCode.CONNECTION_REFUSED, ErrorCode.CONNECTION_FAILED -> R.string.error_connect
    ErrorCode.CONNECT_TIMEOUT -> R.string.error_connect_timeout
    ErrorCode.READ_TIMEOUT -> R.string.error_read_timeout
    ErrorCode.TLS_FAILED, ErrorCode.TLS_TIMEOUT -> R.string.error_tls
    ErrorCode.HTTP_FORBIDDEN -> R.string.error_forbidden
    ErrorCode.BOT_DETECTED -> R.string.error_bot_detected
    ErrorCode.MEDIA_URL_EXPIRED -> R.string.error_expired
    ErrorCode.EXTRACTION_TIMEOUT -> R.string.error_extraction_timeout
    ErrorCode.URL_POLICY_REJECTED -> R.string.error_policy
    ErrorCode.ENGINE_BUSY -> R.string.error_busy
    ErrorCode.OUTPUT_VALIDATION_FAILED, ErrorCode.SOURCE_CORRUPT -> R.string.error_validation
    ErrorCode.ENCODER_UNAVAILABLE -> R.string.error_encoder
    ErrorCode.DECODER_UNAVAILABLE -> R.string.error_decoder
    ErrorCode.MEDIA_METADATA_MISMATCH -> R.string.error_metadata_mismatch
    ErrorCode.CAPTIVE_PORTAL -> R.string.error_captive
    ErrorCode.NETWORK_UNVALIDATED -> R.string.error_unvalidated
    ErrorCode.NETWORK_UNAVAILABLE -> R.string.error_network
    ErrorCode.LIVE_STREAM_DETECTED -> R.string.error_live_stream
    ErrorCode.QUALITY_UNAVAILABLE -> R.string.error_quality_unavailable
    ErrorCode.SERVER_UNAVAILABLE -> R.string.error_server_unavailable
    ErrorCode.INVALID_URL -> R.string.error_invalid_url; ErrorCode.UNSUPPORTED_PLATFORM -> R.string.error_unsupported; ErrorCode.VIDEO_NOT_FOUND -> R.string.error_not_found; ErrorCode.PRIVATE_CONTENT -> R.string.error_private; ErrorCode.AUTHENTICATION_REQUIRED -> R.string.error_auth; ErrorCode.DRM_PROTECTED -> R.string.error_drm; ErrorCode.GEO_RESTRICTED -> R.string.error_geo; ErrorCode.RATE_LIMITED -> R.string.error_rate; ErrorCode.NETWORK_TIMEOUT -> R.string.error_timeout; ErrorCode.HTTP_ERROR -> R.string.error_http; ErrorCode.EXTRACTION_FAILED -> R.string.error_extraction; ErrorCode.FORMAT_UNAVAILABLE -> R.string.error_format; ErrorCode.INSUFFICIENT_STORAGE -> R.string.error_space; ErrorCode.STORAGE_PERMISSION_ERROR -> R.string.error_storage; ErrorCode.STORAGE_DESTINATION_UNAVAILABLE -> R.string.error_destination_unavailable; ErrorCode.DOWNLOAD_INTERRUPTED -> R.string.error_interrupted; ErrorCode.MERGE_FAILED -> R.string.error_merge; ErrorCode.CONVERSION_FAILED -> R.string.error_conversion; ErrorCode.FILE_WRITE_FAILED -> R.string.error_write; ErrorCode.INTERNAL_ENGINE_ERROR -> R.string.error_internal
}

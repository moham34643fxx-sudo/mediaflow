package com.zdownloader.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zdownloader.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import com.zdownloader.app.ui.spectrumStringResource as text

internal data class MediaDayClock(val now:Long,val zone:TimeZone)
private fun currentMediaDayClock()=MediaDayClock(System.currentTimeMillis(),TimeZone.getDefault())
internal val LocalMediaDayClock = staticCompositionLocalOf<MediaDayClock?> { null }

@Composable internal fun rememberMediaDayClock():MediaDayClock {
    LocalMediaDayClock.current?.let { return it }
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var clock by remember {mutableStateOf(currentMediaDayClock())}
    DisposableEffect(context,owner) {
        val receiver=object:BroadcastReceiver() {override fun onReceive(context:Context?,intent:Intent?) {clock=currentMediaDayClock()}}
        val filter=IntentFilter().apply {addAction(Intent.ACTION_DATE_CHANGED);addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)}
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver,filter)
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) clock=currentMediaDayClock()}
        owner.lifecycle.addObserver(observer)
        onDispose {context.unregisterReceiver(receiver);owner.lifecycle.removeObserver(observer)}
    }
    return clock
}

@Composable internal fun MediaDayHeader(day:Long?,clock:MediaDayClock,modifier:Modifier=Modifier) {
    val locale=LocalConfiguration.current.locales[0]
    val formatted=remember(day, clock.now, clock.zone.id, locale) {
        day?.let {stamp->
            val cal=Calendar.getInstance(clock.zone)
            cal.timeInMillis=clock.now
            val currentYear=cal.get(Calendar.YEAR)
            cal.timeInMillis=stamp
            val year=cal.get(Calendar.YEAR)
            val pattern=android.text.format.DateFormat.getBestDateTimePattern(locale,if(year==currentYear) "d MMMM" else "d MMMM yyyy")
            SimpleDateFormat(pattern,locale).apply {timeZone=clock.zone}.format(Date(stamp))
        }.orEmpty()
    }
    val kind = remember(day, clock.now, clock.zone.id) { mediaDayKind(day, clock.now, clock.zone) }
    val label=when(kind) {
        MediaDayKind.TODAY->text(R.string.media_day_today,formatted)
        MediaDayKind.YESTERDAY->text(R.string.media_day_yesterday,formatted)
        MediaDayKind.DATE->formatted
        MediaDayKind.UNKNOWN->text(R.string.media_day_unknown)
    }
    Text(label,modifier.padding(top=14.dp,bottom=6.dp).testTag("media_day_${day ?: "unknown"}").semantics {heading()},
        style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
}

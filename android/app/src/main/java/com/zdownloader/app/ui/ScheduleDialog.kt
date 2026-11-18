package com.zdownloader.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.domain.*
import java.util.Calendar
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun ScheduleDialog(close:()->Unit,confirm:(DownloadPlan)->Unit) {
    val context=LocalSpectrumContext.current
    val locale=LocalConfiguration.current.locales[0]
    var at by remember {mutableLongStateOf(System.currentTimeMillis()+60*60_000)}
    var network by remember {mutableStateOf(DownloadNetwork.ANY)}
    var charging by remember {mutableStateOf(false)}
    var whenReady by remember {mutableStateOf(false)}
    var invalidTime by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,modifier=Modifier.testTag("schedule_dialog"),title={Text(text(R.string.schedule_download))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(whenReady) text(R.string.schedule_conditions) else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,locale).format(java.util.Date(at)))
        TextButton({val c=Calendar.getInstance().apply {timeInMillis=at};DatePickerDialog(context,{_,y,m,d->whenReady=false;invalidTime=false;at=Calendar.getInstance().apply {timeInMillis=at;set(y,m,d)}.timeInMillis},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).apply {datePicker.minDate=System.currentTimeMillis()}.show()}) {Text(text(R.string.schedule_date))}
        TextButton({val c=Calendar.getInstance().apply {timeInMillis=at};TimePickerDialog(context,{_,h,m->whenReady=false;invalidTime=false;at=Calendar.getInstance().apply {timeInMillis=at;set(Calendar.HOUR_OF_DAY,h);set(Calendar.MINUTE,m);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(context)).show()}) {Text(text(R.string.schedule_time))}
        TextButton({whenReady=true;invalidTime=false}) {Text(text(R.string.schedule_conditions))}
        DownloadNetwork.entries.forEach {value->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(network==value,role=Role.RadioButton) {network=value}.testTag("schedule_network_${value.name}"),verticalAlignment=Alignment.CenterVertically) {RadioButton(network==value,null);Text(text(when(value) {DownloadNetwork.ANY->R.string.schedule_any_network;DownloadNetwork.WIFI->R.string.wifi_only;DownloadNetwork.UNMETERED->R.string.schedule_unmetered}))}}
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(charging,role=Role.Checkbox,onValueChange={charging=it}).testTag("schedule_charging"),verticalAlignment=Alignment.CenterVertically) {Checkbox(charging,null);Text(text(R.string.schedule_charging))}
        Text(text(R.string.schedule_inexact),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(invalidTime) Text(text(R.string.schedule_future_required),color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({val now=System.currentTimeMillis();if(!whenReady&&at<now) invalidTime=true else confirm(DownloadPlan(if(whenReady) now else at,network,charging))},Modifier.testTag("schedule_confirm")) {Text(text(R.string.schedule_download))}},dismissButton={TextButton(close) {Text(text(R.string.cancel))}})
}

@Composable internal fun ScheduleSummary(at:Long,network:String,charging:Boolean) {
    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,LocalConfiguration.current.locales[0]).format(java.util.Date(at)),style=MaterialTheme.typography.bodyMedium)
    Text(listOfNotNull(text(when(network) {"WIFI"->R.string.wifi_only;"UNMETERED"->R.string.schedule_unmetered;else->R.string.schedule_any_network}),if(charging) text(R.string.schedule_charging) else null).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
    Text(text(R.string.schedule_inexact),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

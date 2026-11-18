package com.zdownloader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep Material's slot signature and modal/focus/back behavior across existing call sites.
 * Revisit this adapter if Material changes its dialog API. */
@Composable internal fun FlowAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,dismissButton:(@Composable ()->Unit)?=null,icon:(@Composable ()->Unit)?=null,
    title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null) {
    val shape=MaterialTheme.shapes.extraLarge
    AlertDialog(onDismissRequest,confirmButton,modifier.border(BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f)),shape),
        dismissButton=dismissButton,icon=icon,title=title,text=text,shape=shape,
        containerColor=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=0.dp)
}

@Composable internal fun FlowDropdownMenu(expanded:Boolean,onDismissRequest:()->Unit,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    DropdownMenu(expanded,onDismissRequest,modifier,shape=MaterialTheme.shapes.large,
        containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,tonalElevation=0.dp,shadowElevation=8.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)),content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FlowSheetHandle() {
    BottomSheetDefaults.DragHandle(Modifier.padding(vertical=4.dp),width=36.dp,height=4.dp,
        color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.4f))
}

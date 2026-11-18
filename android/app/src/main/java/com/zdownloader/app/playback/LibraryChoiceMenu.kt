package com.zdownloader.app.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdownloader.app.ui.spectrumStringResource as text

internal data class LibraryMenuOption(val key:String,val label:Int,val tag:String)

@Composable internal fun LibraryChoiceMenu(
    selectedKey:String,options:List<LibraryMenuOption>,onSelect:(String)->Unit,modifier:Modifier
) {
    var expanded by remember {mutableStateOf(false)}
    val label=text(options.firstOrNull {it.key==selectedKey}?.label ?: options.first().label)
    Box(modifier) {
        OutlinedButton({expanded=true},Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {stateDescription=label},shape=RoundedCornerShape(14.dp)) {
            Text(label,Modifier.weight(1f,fill=false),maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ArrowDropDown,null,Modifier.size(18.dp))
        }
        DropdownMenu(expanded,{expanded=false}) {
            options.forEach {option->LibraryChoiceItem(option,selectedKey) {onSelect(option.key);expanded=false}}
        }
    }
}

@Composable private fun LibraryChoiceItem(option:LibraryMenuOption,selectedKey:String,onClick:()->Unit) {
    val active=option.key==selectedKey
    DropdownMenuItem(text={Text(text(option.label))},onClick=onClick,
        trailingIcon=if(active) {{Icon(Icons.Outlined.Check,null,Modifier.size(18.dp))}} else null,
        modifier=Modifier.testTag(option.tag).semantics {selected=active})
}

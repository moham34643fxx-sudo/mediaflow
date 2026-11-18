package com.zdownloader.app.playback

import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.data.MediaFile
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text
import java.util.Locale

internal data class LibraryItemDecoration(val selection:Set<String>,val favorite:Boolean)

/** Keep file identity visible during selection without adding metadata work to rows. */
@Composable internal fun LibraryMediaListContent(file:MediaFile,decoration:LibraryItemDecoration,overflow:@Composable ()->Unit) {
    val directory=file.mime==DocumentsContract.Document.MIME_TYPE_DIR
    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(directory) FlowIconTile(FlowIcons.Folder)
        else LibraryThumbnail(file,decoration,if(file.video) Modifier.width(88.dp).height(56.dp) else Modifier.size(56.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis,
                style=MaterialTheme.typography.titleSmall.copy(textDirection=TextDirection.Content))
            LibrarySecondaryLabel(if(directory) text(R.string.fm_folder_kind) else librarySecondaryText(file))
        }
        if(!directory) overflow()
    }
}

@Composable internal fun LibraryMediaGridContent(file:MediaFile,decoration:LibraryItemDecoration,overflow:@Composable ()->Unit) {
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        LibraryThumbnail(file,decoration,Modifier.fillMaxWidth().aspectRatio(if(file.video) 16f/9f else 1f))
        Text(file.name,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis,
            style=MaterialTheme.typography.titleSmall.copy(textDirection=TextDirection.Content))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {LibrarySecondaryLabel(librarySecondaryText(file))}
            overflow()
        }
    }
}

@Composable private fun LibrarySecondaryLabel(label:String) {
    Text(label,maxLines=1,overflow=TextOverflow.Ellipsis,
        style=MaterialTheme.typography.bodySmall.copy(textDirection=TextDirection.Content),
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun librarySecondaryText(file:MediaFile):String {
    val extension=file.name.substringAfterLast('.',"").takeIf {value->
        value.length in 1..8&&value.all {it.isLetterOrDigit()}
    }?.uppercase(Locale.ROOT)
    val artist=file.artist?.takeIf {it.isNotBlank()&&it!="<unknown>"}
    val dimensions=if(file.video&&file.width?.let {it>0}==true&&file.height?.let {it>0}==true)
        "\u2066${file.width}×${file.height}\u2069" else null
    val identity=if(!file.video&&!file.image&&artist!=null) artist else dimensions ?: extension
    return listOfNotNull(identity,file.size?.let {bytesLabel(it)}).joinToString(" · ")
}

@Composable private fun LibraryThumbnail(file:MediaFile,decoration:LibraryItemDecoration,modifier:Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        LibraryFileArtwork(file)
        if(decoration.favorite) Icon(Icons.Outlined.Favorite,text(R.string.player_favorites),
            Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface,CircleShape).padding(3.dp).size(14.dp),
            tint=MaterialTheme.colorScheme.primary)
        file.duration?.takeIf {it>0&&!file.image}?.let {duration->
            LibraryDurationBadge(duration,Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        if(decoration.selection.isNotEmpty()) Checkbox(file.uri in decoration.selection,null,
            Modifier.align(Alignment.TopStart).padding(4.dp).size(26.dp).background(MaterialTheme.colorScheme.surface,CircleShape).clearAndSetSemantics {})
    }
}

@Composable private fun LibraryFileArtwork(file:MediaFile) {
    val artwork=Modifier.fillMaxSize().clearAndSetSemantics {}
    if(file.image) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Icon(FlowIcons.Photo,null,tint=MaterialTheme.colorScheme.primary)
            coil.compose.AsyncImage(file.uri,null,artwork,contentScale=ContentScale.Crop)
        }
    } else MediaArtwork(null,file.name,artwork,file.uri,!file.video)
}

@Composable private fun LibraryDurationBadge(duration:Long,modifier:Modifier) {
    val locale=LocalConfiguration.current.locales[0]
    Surface(modifier,shape=RoundedCornerShape(5.dp),color=Color.Black.copy(alpha=.78f)) {
        Text(playTime(duration,locale),Modifier.padding(horizontal=5.dp,vertical=2.dp),maxLines=1,
            style=MaterialTheme.typography.labelSmall.copy(textDirection=TextDirection.Ltr),color=Color.White)
    }
}

private val LibraryDetailOrder=listOf("name","type","duration","resolution","bytes","modified","location","codecs","uri")

/** Common facts first; identifiers last. Values and provider/metadata behavior are unchanged. */
@Composable internal fun LibraryFileDetails(values:Map<String,String>,locale:Locale) {
    val keys=remember(values) {LibraryDetailOrder.filter {it in values}+values.keys.filter {it !in LibraryDetailOrder}}
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        keys.forEach {key->
            if(key=="uri") HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            LibraryDetailProperty(key,values.getValue(key),locale)
        }
        Text(text(R.string.fm_compatibility),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LibraryDetailProperty(key:String,property:String,locale:Locale) {
    val label=when(key) {
        "name"->R.string.fm_file_name;"type"->R.string.fm_type;"bytes"->R.string.fm_file_size
        "uri"->R.string.fm_content_uri;"duration"->R.string.fm_sort_duration;"resolution"->R.string.fm_resolution
        "codecs"->R.string.fm_codecs;"modified"->R.string.fm_modified;else->R.string.fm_location
    }
    val formatted=when(key) {
        "bytes"->bytesLabel(property.toLong())
        "duration"->playTime(property.toLong(),locale)
        "modified"->java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,locale).format(java.util.Date(property.toLong()))
        else->property
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(text(label),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text(formatted,style=MaterialTheme.typography.bodyMedium.copy(
            textDirection=if(key in setOf("uri","type","duration","resolution","codecs")) TextDirection.Ltr else TextDirection.Content),
            color=MaterialTheme.colorScheme.onSurface)
    }
}

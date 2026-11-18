package com.zdownloader.app.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

/** Local files and explicitly saved playback references remain outside the download library. */
@Composable internal fun LocalMediaScreen(connection:PlaybackConnection,close:()->Unit,extractAudio:((String)->Unit)?=null) {
    val repo=connection.repository
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var source by rememberSaveable {mutableStateOf(repo.text("local_media_source","device"))}
    var audio by rememberSaveable {mutableStateOf(true)}
    var playlist by rememberSaveable {mutableStateOf<String?>(null)}
    var search by rememberSaveable {mutableStateOf("")}
    var add by remember {mutableStateOf<List<PlaybackMedia>?>(null)}
    var create by remember {mutableStateOf(false)}
    var rename by remember {mutableStateOf<PlaybackListSummary?>(null)}
    var delete by remember {mutableStateOf<PlaybackListSummary?>(null)}
    var name by remember {mutableStateOf("")}
    val favorites by remember(repo) {repo.library.favorites()}.collectAsStateWithLifecycle(emptyList())
    val lists by remember(repo) {repo.library.playlists()}.collectAsStateWithLifecycle(emptyList())
    val members by remember(playlist) {playlist?.let {repo.library.items(it)} ?: flowOf(emptyList())}.collectAsStateWithLifecycle(emptyList())
    BackHandler(playlist!=null) {playlist=null}
    if(playlist!=null) SecondaryNavigationOverlay()
    Column(Modifier.fillMaxSize().testTag("local_media_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(FlowIcons.Back,text(R.string.back),{if(playlist!=null) playlist=null else close()})
            Column(Modifier.weight(1f).padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(lists.firstOrNull {it.id==playlist}?.name ?: text(R.string.local_open),style=MaterialTheme.typography.headlineSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            LocalMediaSourcesMenu(connection)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            LibraryChoiceMenu(source,listOf(
                LibraryMenuOption("device",R.string.player_device_media,"library_source_device"),
                LibraryMenuOption("favorites",R.string.player_favorites,"library_source_favorites"),
                LibraryMenuOption("playlists",R.string.player_playlists,"library_source_playlists")
            ),{key->source=key;repo.setText("local_media_source",key);playlist=null;search=""},
                Modifier.fillMaxWidth().testTag("library_source_menu"))
        }
        if(source=="device") Box(Modifier.weight(1f)) {SmartMediaBrowser(connection,MediaBrowserSource.Device,extractAudio)}
        else {
            if(source!="playlists") Box(Modifier.padding(horizontal=20.dp,vertical=10.dp)) {MediaTypeControl(audio,true,true) {audio=it}}
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp).testTag("local_library_search"),singleLine=true,
                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
                placeholder={Text(text(R.string.search_library))},leadingIcon={Icon(FlowIcons.Search,null)},shape=RoundedCornerShape(16.dp))
            val rows=when(source) {"favorites"->favorites.map {it.media()}.filter {it.video!=audio};else->members.map {it.media()}}
                .filter {search.isBlank()||listOfNotNull(it.title,it.artist,it.album).any {value->value.contains(search,true)}}
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("local_library_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(source=="playlists"&&playlist==null) {
                    item {OutlinedButton({create=true;name=""},Modifier.fillMaxWidth().testTag("create_playback_playlist")) {Icon(Icons.AutoMirrored.Outlined.PlaylistAdd,null);Spacer(Modifier.width(8.dp));Text(text(R.string.player_create_playlist))}}
                    if(lists.isEmpty()) item {FlowEmptyState(text(R.string.player_playlists),text(R.string.aurora_playlist_empty),FlowIcons.Playlist)}
                    items(lists.filter {search.isBlank()||it.name.contains(search,true)},key={it.id}) {list->
                        Surface(onClick={playlist=list.id},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                FlowIconTile(FlowIcons.Playlist)
                                Column(Modifier.weight(1f)) {Text(list.name,style=MaterialTheme.typography.titleSmall);Text(itemCountLabel(list.items),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                FlowIconButton(FlowIcons.Edit,text(R.string.rename),{rename=list;name=list.name})
                                FlowIconButton(FlowIcons.Delete,text(R.string.delete),{delete=list})
                            }
                        }
                    }
                } else {
                    val visible=rows
                    if(visible.isEmpty()) item {FlowEmptyState(text(R.string.library_empty),text(R.string.player_local_empty),FlowIcons.Audio)}
                    items(visible,key={it.uri}) {media->
                        var menu by remember {mutableStateOf(false)}
                        Surface(onClick={connection.play(visible,visible.indexOf(media))},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.testTag("local_media_${media.uri.substringAfterLast('/')}")) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                FlowIconTile(if(media.video) FlowIcons.Video else FlowIcons.Audio)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text(media.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                    Text(listOfNotNull(media.artist,media.album,media.duration.takeIf {it>0}?.let(::playTime)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val favorite=favorites.any {it.uri==media.uri}
                                FlowIconButton(if(favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,text(R.string.player_favorites),{connection.editLibrary {repo.favorite(media,!favorite)}},accent=favorite)
                                Box {
                                    FlowIconButton(FlowIcons.More,text(R.string.details),{menu=true})
                            DropdownMenu(menu,{menu=false}) {
                                        if(media.video&&extractAudio!=null) {
                                            DropdownMenuItem(text={Text(text(R.string.extract_title))},onClick={menu=false;extractAudio(media.uri)})
                                        }
                                        DropdownMenuItem(text={Text(text(R.string.player_play_next))},onClick={menu=false;connection.enqueue(media,true)})
                                        DropdownMenuItem(text={Text(text(R.string.player_add_queue))},onClick={menu=false;connection.enqueue(media)})
                                        DropdownMenuItem(text={Text(text(R.string.player_add_playlist))},onClick={menu=false;add=listOf(media)})
                                        if(playlist!=null) {
                                            DropdownMenuItem(text={Text(text(R.string.player_move_up))},onClick={menu=false;val id=playlist!!;connection.editLibrary {repo.library.move(id,media.uri,-1)}})
                                            DropdownMenuItem(text={Text(text(R.string.player_move_down))},onClick={menu=false;val id=playlist!!;connection.editLibrary {repo.library.move(id,media.uri,1)}})
                                        }
                                        if(playlist!=null) DropdownMenuItem(text={Text(text(R.string.remove))},onClick={menu=false;val id=playlist!!;connection.editLibrary {repo.library.remove(id,media.uri)}})
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(create||rename!=null) AlertDialog(onDismissRequest={create=false;rename=null},title={Text(text(if(rename==null) R.string.player_create_playlist else R.string.rename))},text={OutlinedTextField(name,{name=it.take(80)},label={Text(text(R.string.player_playlist_name))},singleLine=true)},
        confirmButton={TextButton({val current=rename;val entered=name.trim();connection.editLibrary {if(current==null) repo.library.create(PlaybackList(UUID.randomUUID().toString(),entered)) else repo.library.rename(current.id,entered)};create=false;rename=null},enabled=name.isNotBlank()) {Text(text(R.string.save))}},dismissButton={TextButton({create=false;rename=null}) {Text(text(R.string.cancel))}})
    delete?.let {list->AlertDialog(onDismissRequest={delete=null},title={Text(text(R.string.delete))},text={Text(text(R.string.player_delete_playlist))},confirmButton={TextButton({connection.editLibrary {repo.library.delete(list.id)};delete=null}) {Text(text(R.string.delete))}},dismissButton={TextButton({delete=null}) {Text(text(R.string.cancel))}})}
    add?.let {PlaylistPicker(connection,it) {add=null}}
}

@Composable internal fun PlaylistPicker(connection:PlaybackConnection,media:List<PlaybackMedia>,close:()->Unit) {
    val scope=rememberCoroutineScope();val dao=connection.repository.library
    val lists by remember(dao) {dao.playlists()}.collectAsStateWithLifecycle(emptyList())
    var name by remember {mutableStateOf("")}
    var error by remember {mutableStateOf(false)}
    fun append(id:String) {scope.launch {runCatching {dao.appendAll(id,media)}.onSuccess {close()}.onFailure {error=true}}}
    AlertDialog(onDismissRequest=close,title={Text(text(R.string.player_add_playlist))},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        lists.forEach {list->TextButton({append(list.id)},Modifier.fillMaxWidth()) {Text(list.name)}}
        OutlinedTextField(name,{name=it.take(80)},label={Text(text(R.string.player_playlist_name))})
        if(error) Text(text(R.string.player_playlist_error),color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({scope.launch {runCatching {dao.createWithItems(PlaybackList(UUID.randomUUID().toString(),name.trim()),media)}.onSuccess {close()}.onFailure {error=true}}},enabled=name.isNotBlank()) {Text(text(R.string.player_create_playlist))}},dismissButton={TextButton(close) {Text(text(R.string.cancel))}})
}

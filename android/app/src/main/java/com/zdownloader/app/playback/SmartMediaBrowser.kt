package com.zdownloader.app.playback

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.MediaFile
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.LocalMediaKind
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.zdownloader.app.data.FileInUseException
import com.zdownloader.app.data.FileNameConflictException
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text
import kotlinx.coroutines.*

/** One manager for indexed device media, selected document folders and completed downloads. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SmartMediaBrowser(connection:PlaybackConnection,source:MediaBrowserSource,extractAudio:((String)->Unit)?=null) {
    val downloads=(source as? MediaBrowserSource.Downloads)?.entries
    val context=LocalContext.current;val app=context.applicationContext as ZApplication;val files=app.mediaFiles
    val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val locale=LocalConfiguration.current.locales[0]
    val gridMinimum=if(LocalConfiguration.current.fontScale>1.3f) 196.dp else 136.dp
    var kind by rememberSaveable {mutableStateOf(LocalMediaKind.VIDEO)}
    val audio=kind==LocalMediaKind.AUDIO
    var mode by rememberSaveable {mutableStateOf("files")}
    var search by rememberSaveable {mutableStateOf("")}
    var sort by rememberSaveable {mutableStateOf("date")}
    var grid by rememberSaveable {mutableStateOf(false)}
    var tree by rememberSaveable {mutableStateOf<String?>(null)}
    var parent by rememberSaveable {mutableStateOf<String?>(null)}
    var ancestors by rememberSaveable {mutableStateOf<List<String>>(emptyList())}
    var group by rememberSaveable {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    var limit by remember {mutableIntStateOf(500)}
    var rows by remember {mutableStateOf<List<MediaFile>>(
        downloads?.mapNotNull {row->row.uri?.let {uri->
            files.cachedMetadata(uri)?.forLibraryDownload(row)
        }}.orEmpty()
    )}
    var loading by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    var busy by remember {mutableStateOf(false)}
    var problem by remember {mutableStateOf<Int?>(null)}
    var batchLimit by remember {mutableStateOf(false)}
    var details by remember {mutableStateOf<Map<String,String>?>(null)}
    var rename by remember {mutableStateOf<MediaFile?>(null)}
    var name by remember {mutableStateOf("")}
    var delete by remember {mutableStateOf<List<MediaFile>?>(null)}
    var destinationAction by remember {mutableStateOf<String?>(null)}
    var pendingAction by remember {mutableStateOf<(suspend ()->Unit)?>(null)}
    var add by remember {mutableStateOf<List<PlaybackMedia>?>(null)}
    var image by remember {mutableStateOf<MediaFile?>(null)}
    var permissionAttempted by rememberSaveable {mutableStateOf<LocalMediaKind?>(null)}
    LaunchedEffect(audio,mode) {if(!audio&&mode in setOf("albums","artists")) {mode="files";group=null;selected=emptySet()}}
    val favorites by remember {connection.repository.library.favorites()}.collectAsStateWithLifecycle(emptyList())
    val favoriteUris=remember(favorites) {favorites.mapTo(HashSet()) {it.uri}}
    val downloadIds=remember(downloads) {downloads.orEmpty().associate {it.uri to it.id}}
    val access=kind.readPermission(Build.VERSION.SDK_INT)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {permissionAttempted=kind;revision++}
    val fullAccess=remember(kind,revision) {ContextCompat.checkSelfPermission(context,access)==PackageManager.PERMISSION_GRANTED}
    val selectedAccess=remember(kind,revision) {Build.VERSION.SDK_INT>=34&&!audio&&ContextCompat.checkSelfPermission(context,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED}
    val activity=LocalActivity.current
    fun showProblem(error:Exception?=null) {problem=when(error) {
        is FileInUseException->R.string.fm_file_in_use
        is FileNameConflictException->R.string.fm_name_conflict
        is SecurityException->R.string.error_storage
        is java.io.FileNotFoundException->R.string.file_missing
        else->R.string.fm_operation_failed
    }}
    lateinit var execute:(suspend ()->Unit)->Unit
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {result->
        val action=pendingAction;pendingAction=null
        if(result.resultCode==Activity.RESULT_OK&&action!=null) execute(action)
    }
    execute={action->if(!busy) {
        busy=true
        scope.launch {try {action();selected=emptySet();revision++} catch(e:CancellationException) {throw e} catch(e:Exception) {
            if(Build.VERSION.SDK_INT==29&&e is RecoverableSecurityException) {pendingAction=action;consent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())} else showProblem(e)
        } finally {busy=false}}
    }}
    fun mutate(targets:List<MediaFile>,deletion:Boolean=false,action:suspend (Boolean)->Unit) {
        if(busy||targets.isEmpty()) return
        if(Build.VERSION.SDK_INT>=30&&targets.count {it.mediaStore}>2000) {batchLimit=true;return}
        if(targets.any {it.uri==connection.state.value.current?.uri}) {showProblem(FileInUseException());return}
        try {
            val request=files.confirmation(targets,deletion)
            if(request!=null) {pendingAction={action(deletion)};consent.launch(IntentSenderRequest.Builder(request.intentSender).build())}
            else execute {action(false)}
        } catch(error:Exception) {showProblem(error)}
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->
        val action=destinationAction;destinationAction=null
        if(uri!=null) {
            try {
                val write=context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if(action in setOf("copy","move")&&!write) throw SecurityException()
                files.rememberFolder(uri)
                val targets=rows.filter {it.uri in selected}
                when(action) {
                    "copy"->execute {targets.forEach {files.copied(it,uri.toString())}}
                    "move"->{var next=0;mutate(targets) {while(next<targets.size) {files.moved(targets[next],uri.toString());next++}}}
                    else->if(source==MediaBrowserSource.Device) {tree=uri.toString();parent=null;ancestors=emptyList();mode="folders";group=null;revision++}
                }
            } catch(error:Exception) {showProblem(error)}
        }
    }
    DisposableEffect(lifecycle,context,downloads==null) {
        val handler=Handler(Looper.getMainLooper());val update=Runnable {revision++}
        val observer=object:ContentObserver(handler) {override fun onChange(selfChange:Boolean) {handler.removeCallbacks(update);handler.postDelayed(update,400)}}
        if(downloads==null) {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,true,observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,true,observer)
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer)
        }
        var wasPaused = false
        val resume=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP) wasPaused=true
            else if(event==Lifecycle.Event.ON_RESUME) {
                if(wasPaused) {wasPaused=false;revision++}
            }
        }
        lifecycle.lifecycle.addObserver(resume)
        onDispose {handler.removeCallbacks(update);context.contentResolver.unregisterContentObserver(observer);lifecycle.lifecycle.removeObserver(resume)}
    }
    DisposableEffect(tree,context,downloads==null) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean) {revision++}}
        val registered=downloads==null&&tree?.let {runCatching {context.contentResolver.registerContentObserver(Uri.parse(it),true,observer)}.isSuccess}==true
        onDispose {if(registered) context.contentResolver.unregisterContentObserver(observer)}
    }
    // Completed downloads use in-memory filters. Only indexed device queries
    // depend on category, search, sorting, and pagination.
    val indexed = downloads == null && tree == null
    LaunchedEffect(downloads,tree,parent,revision,kind.takeIf {indexed},search.takeIf {indexed},
        sort.takeIf {indexed},limit.takeIf {indexed}) {
        if(rows.isEmpty()) loading=true
        if(indexed && search.isNotBlank()) delay(250)
        try {
            rows=when(source) {
            is MediaBrowserSource.Downloads->withContext(Dispatchers.IO) {source.entries.mapNotNull {row->row.uri?.let {uri->
                ensureActive()
                try {(files.cachedMetadata(uri) ?: files.metadata(uri)).forLibraryDownload(row)} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
            }}}
            MediaBrowserSource.Device->if(tree!=null) files.documents(tree!!,parent) else files.indexed(kind,search,sort,limit)
            }
            selected=selected.intersect(rows.map {it.uri}.toSet())
        } catch(e:CancellationException) {throw e} catch(error:SecurityException) {
            rows=emptyList();selected=emptySet()
            // Missing category access is already explained by the in-context permission card.
            if(downloads!=null||tree!=null||fullAccess) showProblem(error)
        } catch(error:Exception) {rows=emptyList();selected=emptySet();showProblem(error)}
        finally {loading=false}
    }
    val unknownArtist=text(R.string.player_unknown_artist)
    val unknownAlbum=text(R.string.player_unknown_album)
    val unknownFolder=text(R.string.fm_unknown_folder)
    fun groupKey(file:MediaFile)=mode+":"+when(mode) {"albums"->file.album.orEmpty();"artists"->file.artist.orEmpty();else->file.location.orEmpty()}
    fun groupName(key:String)=key.substringAfter(':').ifBlank {when(mode) {"albums"->unknownAlbum;"artists"->unknownArtist;else->unknownFolder}}
    fun up() {selected=emptySet();when {group!=null->group=null;parent!=null->{parent=ancestors.lastOrNull()?.takeIf {it.isNotEmpty()};ancestors=ancestors.dropLast(1)};else->tree=null}}
    val shown=remember(rows,search,sort,mode,group,kind) {
        val filtered=rows.filter {file->(file.mime==DocumentsContract.Document.MIME_TYPE_DIR||kind.accepts(file.mime))&&(search.isBlank()||file.name.contains(search,true)||file.artist?.contains(search,true)==true||file.album?.contains(search,true)==true)&&
            (group==null||groupKey(file)==group)}
        when(sort) {"name"->filtered.sortedBy {it.name.lowercase(java.util.Locale.ROOT)};"size"->filtered.sortedByDescending {it.size ?: -1};"duration"->filtered.sortedByDescending {it.duration ?: -1};else->filtered.sortedByDescending {it.addedAt ?: 0}}
    }
    val clock=rememberMediaDayClock()
    val daySections=remember(shown,sort,clock.zone.id) {
        if(sort=="date") mediaDaySections(shown,{it.addedAt},clock.zone) else listOf(MediaDaySection(null,shown))
    }
    val groups=remember(shown,tree,group,mode) {if(tree==null&&group==null&&mode in setOf("folders","albums","artists")) shown.groupBy(::groupKey) else null}
    val selectable=remember(shown) {shown.filter {it.mime!=DocumentsContract.Document.MIME_TYPE_DIR}}
    BackHandler(selected.isNotEmpty()||group!=null||tree!=null) {if(selected.isNotEmpty()) selected=emptySet() else up()}
    Column(Modifier.fillMaxSize().testTag("smart_media_browser")) {
      LazyVerticalGrid(if(grid) GridCells.Adaptive(gridMinimum) else GridCells.Fixed(1),Modifier.weight(1f).testTag("local_library_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
         Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                LocalMediaKind.entries.forEach {category->
                    Surface(shape=RoundedCornerShape(16.dp),color=if(kind==category) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor=if(kind==category) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f)) {
                        Box(Modifier.heightIn(min=48.dp).selectable(kind==category,role=Role.Tab,onClick={kind=category;group=null;selected=emptySet();limit=500;if(category!=LocalMediaKind.AUDIO&&mode in setOf("albums","artists")) mode="files";if(category==LocalMediaKind.IMAGE&&sort=="duration") sort="date"})
                            .testTag(when(category) {LocalMediaKind.VIDEO->"media_type_video";LocalMediaKind.AUDIO->"media_type_audio";LocalMediaKind.IMAGE->"media_type_image"}).padding(horizontal=8.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
                            Text(text(when(category) {LocalMediaKind.VIDEO->R.string.video;LocalMediaKind.AUDIO->R.string.audio;LocalMediaKind.IMAGE->R.string.local_images}),style=MaterialTheme.typography.labelLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
         val searchLabel=text(if(downloads==null) R.string.local_search else R.string.search_library)
         OutlinedTextField(search,{search=it;limit=500},Modifier.fillMaxWidth().testTag(if(downloads==null) "local_library_search" else "library_search").semantics {contentDescription=searchLabel},singleLine=true,
            keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
              placeholder={Text(text(if(downloads==null) R.string.local_search else R.string.search_library),maxLines=1,overflow=TextOverflow.Ellipsis)},leadingIcon={Icon(FlowIcons.Search,null, tint=MaterialTheme.colorScheme.primary)},trailingIcon={if(search.isNotEmpty()) FlowIconButton(FlowIcons.Close,text(R.string.local_clear_search),{search=""},Modifier.testTag("local_clear_search"))},shape=RoundedCornerShape(20.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surfaceContainerHigh,unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant))
         Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            val views=listOf("files" to if(audio) R.string.player_songs else R.string.fm_files,"folders" to R.string.fm_folders,"recent" to R.string.fm_recent_files)
                .plus(if(audio&&downloads==null) listOf("albums" to R.string.player_albums,"artists" to R.string.player_artists) else emptyList())
            LibraryChoiceMenu(mode,views.map {(key,label)->LibraryMenuOption(key,label,"music_$key")},
                {key->mode=key;group=null;tree=null;selected=emptySet();if(key=="recent") sort="date"},
                Modifier.weight(1f).testTag("library_view_menu"))
            LibraryChoiceMenu(sort,listOf(
                LibraryMenuOption("date",R.string.sort_newest,"sort_date"),
                LibraryMenuOption("name",R.string.sort_name,"sort_name"),
                LibraryMenuOption("size",R.string.sort_size,"sort_size"),
                LibraryMenuOption("duration",R.string.fm_sort_duration,"sort_duration")
             ).filterNot {kind==LocalMediaKind.IMAGE&&it.key=="duration"},{sort=it},Modifier.weight(1f).testTag("library_sort_menu"))
             val layoutLabel=text(if(grid) R.string.library_layout_grid else R.string.library_layout_list)
             FlowIconButton(if(grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                 text(if(grid) R.string.library_show_list else R.string.library_show_grid),{grid=!grid},
                 Modifier.testTag("fm_view").semantics {stateDescription=layoutLabel})
        }
        if(downloads==null&&mode=="folders"&&tree==null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp)) {files.folders().forEach {saved->TextButton({tree=saved;parent=null;ancestors=emptyList()}) {Text(scopedFolderLabel(saved))}}}
        if(group!=null||tree!=null) Row(verticalAlignment=Alignment.CenterVertically) {FlowIconButton(FlowIcons.Back,text(R.string.back),::up);Text(group?.let(::groupName) ?: tree?.let {scopedFolderLabel(if(parent==null) it else DocumentsContract.buildDocumentUriUsingTree(Uri.parse(it),parent!!).toString())}.orEmpty(),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)}
         if(downloads==null&&tree==null&&!fullAccess) {
             Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.fillMaxWidth().testTag("library_access_card")) {
                 Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                     Text(text(R.string.local_access_title),style=MaterialTheme.typography.titleSmall)
                     Text(text(if(selectedAccess) R.string.local_access_limited else R.string.local_access_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                     val settingsRequired=permissionAttempted==kind&&!selectedAccess&&activity!=null&&!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity,access)
                     TextButton({
                         if(settingsRequired) context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
                         else permission.launch(kind.readPermissions(Build.VERSION.SDK_INT))
                     },Modifier.testTag("library_allow_media")) {
                         Text(text(if(settingsRequired) R.string.local_app_settings else if(selectedAccess) R.string.local_manage_access else when(kind) {LocalMediaKind.VIDEO->R.string.local_allow_video;LocalMediaKind.AUDIO->R.string.local_allow_audio;LocalMediaKind.IMAGE->R.string.local_allow_images}))
                     }
                 }
             }
         }
        if(selected.isNotEmpty()) Column(Modifier.padding(horizontal=16.dp).testTag("fm_selection")) {
            Row(verticalAlignment=Alignment.CenterVertically) {Text(spectrumPluralStringResource(R.plurals.selected_count,selected.size,selected.size),Modifier.weight(1f));TextButton({selected=emptySet()}) {Text(text(R.string.cancel))}}
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val targets=shown.filter {it.uri in selected&&it.mime!=DocumentsContract.Document.MIME_TYPE_DIR}
                TextButton({shareMediaFiles(context,targets)},enabled=!busy) {Text(text(R.string.share))}
                TextButton({destinationAction="copy";folder.launch(null)},enabled=!busy) {Text(text(R.string.fm_copy))}
                if(targets.isNotEmpty()&&targets.all {it.movable}) TextButton({destinationAction="move";folder.launch(null)},enabled=!busy) {Text(text(R.string.fm_move))}
                 TextButton({delete=targets},enabled=!busy&&targets.isNotEmpty()&&targets.all {it.deletable},
                     colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text(text(R.string.delete))}
                if(targets.none {it.image}) TextButton({add=targets.map {it.playback()}},enabled=!busy) {Text(text(R.string.player_add_playlist))}
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
              if(groups==null&&selectable.isNotEmpty()) TextButton({selected=selectable.map {it.uri}.toSet()},enabled=!busy,modifier=Modifier.weight(1f).testTag("fm_select_all"),
                  colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant)) {Text(text(R.string.fm_select_shown,selectable.size))}
             else if(groups==null) Spacer(Modifier.weight(1f))
            else Text(itemCountLabel(groups.size),Modifier.weight(1f).padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(downloads==null) FlowIconButton(FlowIcons.Folder,text(R.string.fm_connect_folder),{folder.launch(null)},Modifier.testTag("fm_connect_folder"))
        }
        if(loading||busy) FlowLoadingIndicator()
       }}
             if(groups!=null) items(groups.toList(),key={it.first}) {(key,items)->FeatureAccess(groupName(key),itemCountLabel(items.size),if(mode=="albums") Icons.Outlined.Album else if(mode=="artists") Icons.Outlined.Person else FlowIcons.Folder,MaterialTheme.colorScheme.primary,{group=key;selected=emptySet()})}
             else daySections.forEach {section->
               if(sort=="date") item(key="file-day:${section.dayStart}",span={GridItemSpan(maxLineSpan)}) {MediaDayHeader(section.dayStart,clock)}
               items(section.entries,key={it.uri}) {file->
                var menu by remember {mutableStateOf(false)}
                 val directory=file.mime==DocumentsContract.Document.MIME_TYPE_DIR
                 fun toggle() {selected=if(file.uri in selected) selected-file.uri else selected+file.uri}
                 val overflow:@Composable ()->Unit={
                     Box {
                          FlowIconButton(FlowIcons.More,text(R.string.library_file_actions,file.name),{menu=true},Modifier.testTag("fm_menu_${file.uri.substringAfterLast('/')}"))
                          DropdownMenu(menu,{menu=false}) {
                              if(file.video&&extractAudio!=null) DropdownMenuItem(text={Text(text(R.string.extract_title))},onClick={menu=false;extractAudio(file.uri)})
                             DropdownMenuItem(text={Text(text(R.string.select_download))},onClick={menu=false;toggle()})
                             DropdownMenuItem(text={Text(text(R.string.details))},onClick={menu=false;execute {details=files.details(file)}})
                             DropdownMenuItem(text={Text(text(R.string.share))},onClick={menu=false;shareMediaFiles(context,listOf(file))})
                             if(file.renamable) DropdownMenuItem(text={Text(text(R.string.rename))},onClick={menu=false;rename=file;name=file.name})
                             DropdownMenuItem(text={Text(text(R.string.fm_copy))},onClick={menu=false;selected=setOf(file.uri);destinationAction="copy";folder.launch(null)})
                             if(file.movable) DropdownMenuItem(text={Text(text(R.string.fm_move))},onClick={menu=false;selected=setOf(file.uri);destinationAction="move";folder.launch(null)})
                             if(!file.image) {
                             val favorite=favorites.any {it.uri==file.uri}
                             DropdownMenuItem(text={Text(text(if(favorite) R.string.fm_remove_favorite else R.string.fm_add_favorite))},onClick={menu=false;connection.editLibrary {connection.repository.favorite(file.playback(),!favorite)}})
                             DropdownMenuItem(text={Text(text(R.string.player_play_next))},onClick={menu=false;connection.enqueue(file.playback(),true)})
                             DropdownMenuItem(text={Text(text(R.string.player_add_queue))},onClick={menu=false;connection.enqueue(file.playback())})
                              DropdownMenuItem(text={Text(text(R.string.player_add_playlist))},onClick={menu=false;add=listOf(file.playback())})
                             }
                               if(downloads==null&&(file.parent!=null||file.tree!=null||file.volume=="external_primary"&&file.location!=null)) DropdownMenuItem(text={Text(text(R.string.fm_open_folder))},onClick={menu=false;folder.launch(file.parent?.let(Uri::parse) ?: file.tree?.let(Uri::parse) ?: if(Build.VERSION.SDK_INT>=26) DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+file.location?.trim('/')) else null)})
                               if(file.deletable) {
                                   HorizontalDivider(Modifier.padding(vertical=4.dp),color=MaterialTheme.colorScheme.outlineVariant)
                                   DropdownMenuItem(text={Text(text(R.string.delete),color=MaterialTheme.colorScheme.error)},
                                       leadingIcon={Icon(FlowIcons.Delete,null,tint=MaterialTheme.colorScheme.error)},
                                       onClick={menu=false;delete=listOf(file)})
                               }
                         }
                     }
                 }
                 Surface(shape=MaterialTheme.shapes.medium,color=if(file.uri in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                     border=BorderStroke(1.dp,if(file.uri in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)),
                     modifier=Modifier.testTag(downloadIds[file.uri]?.let {"library_item_$it"} ?: "local_media_${file.uri.substringAfterLast('/')}")
                         .semantics {if(selected.isNotEmpty()&&!directory) {
                             this.selected=file.uri in selected
                             toggleableState=if(file.uri in selected) ToggleableState.On else ToggleableState.Off
                         }}
                         .combinedClickable(enabled=!busy,role=if(selected.isNotEmpty()&&!directory) Role.Checkbox else Role.Button,onLongClick={if(!directory) toggle()},onClick={
                         if(directory) {ancestors=ancestors+parent.orEmpty();tree=file.tree;parent=DocumentsContract.getDocumentId(Uri.parse(file.uri))}
                         else if(selected.isNotEmpty()) toggle() else if(file.image) image=file else {val playable=shown.filter {it.mime!=DocumentsContract.Document.MIME_TYPE_DIR&&!it.image};connection.play(playable.map {it.playback()},playable.indexOf(file))}
                    })) {
                     val decoration=LibraryItemDecoration(selected,file.uri in favoriteUris)
                     if(grid&&!directory) LibraryMediaGridContent(file,decoration,overflow)
                     else LibraryMediaListContent(file,decoration,overflow)
                }
            }
             }
              if(shown.isEmpty()&&!loading) item(span={GridItemSpan(maxLineSpan)}) {
                  Column(Modifier.testTag("library_empty_state")) {
                      val emptyTitle=if(search.isNotBlank()) R.string.local_search_empty else when(kind) {
                          LocalMediaKind.VIDEO->R.string.library_no_videos;LocalMediaKind.AUDIO->R.string.library_no_audio;LocalMediaKind.IMAGE->R.string.library_no_images
                      }
                      FlowEmptyState(text(emptyTitle),text(if(search.isNotBlank()) R.string.search_hint else if(downloads!=null) R.string.local_downloads_empty_hint else R.string.local_empty_hint),when(kind) {LocalMediaKind.AUDIO->FlowIcons.Audio;LocalMediaKind.VIDEO->FlowIcons.Video;LocalMediaKind.IMAGE->FlowIcons.Photo})
                      if(search.isNotBlank()) TextButton({search=""},Modifier.align(Alignment.CenterHorizontally)) {Text(text(R.string.local_clear_search))}
                  }
              }
            if(downloads==null&&tree==null&&rows.size>=limit) item(span={GridItemSpan(maxLineSpan)}) {TextButton({limit+=500},Modifier.fillMaxWidth()) {Text(text(R.string.player_load_more))}}
        }
    }
    image?.let {current->LocalImagePreview(shown.filter {it.image},current.uri) {image=null}}
    rename?.let {file->RenameMediaDialog(name,{name=it.take(180)},{rename=null}) {val entered=name;rename=null;mutate(listOf(file)) {files.renamed(file,entered)}}}
        delete?.let {targets->AlertDialog(onDismissRequest={delete=null},title={Text(text(R.string.delete))},text={Text(spectrumPluralStringResource(R.plurals.fm_delete_confirm,targets.size,targets.size))},confirmButton={TextButton({delete=null;var next=0;mutate(targets,true) {platform->while(next<targets.size) {val file=targets[next];files.deleted(file,platform&&file.mediaStore);next++}}},
            colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text(text(R.string.delete))}},dismissButton={TextButton({delete=null}) {Text(text(R.string.cancel))}})}
    details?.let {values->SecondaryNavigationOverlay();AlertDialog(onDismissRequest={details=null},modifier=Modifier.testTag("file_details_dialog"),title={Text(text(R.string.details))},
        text={LibraryFileDetails(values,locale)},confirmButton={TextButton({details=null},Modifier.testTag("file_details_confirm")) {Text(text(R.string.ok))}})}
    problem?.let {message->AlertDialog(onDismissRequest={problem=null},title={Text(text(R.string.problem))},text={Text(text(message))},confirmButton={TextButton({problem=null}) {Text(text(R.string.ok))}})}
    if(batchLimit) AlertDialog(onDismissRequest={batchLimit=false},title={Text(text(R.string.fm_selection_limit_title))},text={Text(text(R.string.fm_selection_limit))},confirmButton={TextButton({batchLimit=false}) {Text(text(R.string.ok))}})
    add?.let {PlaylistPicker(connection,it) {add=null}}
}

private fun MediaFile.forLibraryDownload(row:DownloadEntity)=copy(
    duration=row.duration?.times(1000)?.toLong(),
    location=location ?: row.destination,
    tree=row.destination,
    addedAt=addedAt ?: row.finishedAt ?: row.createdAt
)

private fun shareMediaFiles(context:android.content.Context,files:List<MediaFile>) {
    if(files.isEmpty()) return
    val uris=ArrayList(files.map {Uri.parse(it.uri)})
    val type=files.map {it.mime}.distinct().singleOrNull() ?: "*/*"
    val intent=Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if(uris.size==1) intent.putExtra(Intent.EXTRA_STREAM,uris.first()) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
    intent.clipData=ClipData.newUri(context.contentResolver,"MediaFlow",uris.first()).apply {uris.drop(1).forEach {addItem(ClipData.Item(it))}}
    runCatching {context.startActivity(Intent.createChooser(intent,null))}
}

package com.zdownloader.app.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zdownloader.app.R
import com.zdownloader.app.data.MediaFile
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text

/** Images never enter the audio/video queue. Decode size and pan are bounded. */
@Composable internal fun LocalImagePreview(images: List<MediaFile>, initialUri: String, close: () -> Unit) {
    var selectedUri by rememberSaveable(initialUri) {mutableStateOf(initialUri)}
    val index=images.indexOfFirst {it.uri==selectedUri}
    if(index<0) {LaunchedEffect(selectedUri) {close()};return}
    val image=images[index]
    var zoom by remember(image.uri) {mutableFloatStateOf(1f)}
    var offset by remember(image.uri) {mutableStateOf(Offset.Zero)}
    var viewport by remember {mutableStateOf(IntSize.Zero)}
    var failed by remember(image.uri) {mutableStateOf(false)}
    var loading by remember(image.uri) {mutableStateOf(true)}
    val context=LocalContext.current
    val request=remember(context,image.uri) {ImageRequest.Builder(context).data(image.uri).size(1600).build()}
    fun changeZoom(factor: Float) {
        zoom=factor.coerceIn(1f,4f)
        val maxX=viewport.width*(zoom-1)/2f;val maxY=viewport.height*(zoom-1)/2f
        offset=Offset(offset.x.coerceIn(-maxX,maxX),offset.y.coerceIn(-maxY,maxY))
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().testTag("local_image_preview"),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    FlowIconButton(FlowIcons.Back,text(R.string.back),close,Modifier.testTag("image_preview_back"))
                    Text(image.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged {viewport=it}.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLowest),contentAlignment=Alignment.Center) {
                    AsyncImage(request,image.name,Modifier.fillMaxSize().testTag("local_image")
                        .pointerInput(image.uri) {detectTransformGestures {_,pan,scale,_->
                            changeZoom(zoom*scale)
                            val maxX=size.width*(zoom-1)/2f;val maxY=size.height*(zoom-1)/2f
                            offset=Offset((offset.x+pan.x).coerceIn(-maxX,maxX),(offset.y+pan.y).coerceIn(-maxY,maxY))
                        }}
                        .pointerInput(image.uri) {detectTapGestures(onDoubleTap={changeZoom(if(zoom>1f) 1f else 2f)})}
                        .graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y},
                        contentScale=ContentScale.Fit,onLoading={loading=true},onSuccess={loading=false;failed=false},onError={loading=false;failed=true})
                    if(loading) CircularProgressIndicator(Modifier.testTag("image_loading"))
                    if(failed) Text(text(R.string.local_image_error),Modifier.padding(24.dp),color=MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    FlowIconButton(FlowIcons.Back,text(R.string.local_previous_image),{selectedUri=images[index-1].uri},enabled=index>0)
                    Text(text(R.string.local_image_position,index+1,images.size),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    FlowIconButton(Icons.Outlined.ZoomOut,text(R.string.local_zoom_out),{changeZoom(zoom-.5f)},enabled=zoom>1f&&!failed)
                    FlowIconButton(Icons.Outlined.ZoomIn,text(R.string.local_zoom_in),{changeZoom(zoom+.5f)},enabled=zoom<4f&&!failed)
                    FlowIconButton(FlowIcons.Next,text(R.string.local_next_image),{selectedUri=images[index+1].uri},enabled=index<images.lastIndex)
                }
                if(zoom>1f) TextButton({changeZoom(1f)},Modifier.align(Alignment.CenterHorizontally)) {Text(text(R.string.local_reset_zoom))}
            }
        }
    }
}

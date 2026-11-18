package com.zdownloader.app.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.text.TextUtils
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.Uri
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import coil.Coil
import coil.request.ImageRequest
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import androidx.media3.common.Player
import java.io.File
import java.util.UUID
import androidx.compose.ui.graphics.toArgb
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.AudioPopupMetadata
import com.zdownloader.app.ui.Spectrum
import kotlinx.coroutines.*
import java.util.Locale

/** Shared card; its owner decides whether dismissal hides a service view or ends a preview. */
internal class FloatingAudioWindow(
    context:Context,private val player:Player,
    private val onPreviewClosed:(()->Unit)?=null,
    private val onPreviewOpen:(()->Unit)?=null
) {
    private val app=context.applicationContext as Application
    private val windows=context.getSystemService(WindowManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var preferences:Job?=null
    private var textContext:Context=context
    private var dark=true
    private var enabled=false
    private var owner:String?=null
    private var foreground=onPreviewClosed==null
    private val activities=mutableSetOf<Activity>()
    private var view:View?=null
    private var title:TextView?=null
    private var artist:TextView?=null
    private var elapsed:TextView?=null
    private var artwork:ImageView?=null
    private var artworkUri:String?=null
    private var artworkRequest:coil.request.Disposable?=null
    private var toggle:ImageButton?=null
    private var seek:SeekBar?=null
    private var seeking=false
    private val auditFile=File(app.cacheDir,"mediaflow-overlay-audit-${UUID.randomUUID()}.log")
    private fun auditState(event:String) {
        if(!BuildConfig.DEBUG) return
        val power=app.getSystemService(android.os.PowerManager::class.java)
        val keyguard=app.getSystemService(android.app.KeyguardManager::class.java)
        val state="$event enabled=$enabled foreground=$foreground tracked=${activities.size} view=${view!=null} " +
            "interactive=${power.isInteractive} keyguard=${keyguard.isKeyguardLocked} " +
            "permission=${Settings.canDrawOverlays(app)} playerState=${player.playbackState} playing=${player.isPlaying}"
        android.util.Log.d("MediaFlowOverlay",state)
        runCatching {auditFile.appendText("${android.os.SystemClock.elapsedRealtime()} $state\n")}
    }
    private val position=WindowManager.LayoutParams(
        1,WindowManager.LayoutParams.WRAP_CONTENT,
        if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
        PixelFormat.TRANSLUCENT).apply {gravity=Gravity.CENTER;x=0;y=0;dimAmount=.38f}
    private val progressTick=object:Runnable {
        override fun run() {updateProgress();if(view!=null&&player.isPlaying) handler.postDelayed(this,1000)}
    }
    private val screen=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            auditState("screen:${intent.action}")
            if(intent.action==Intent.ACTION_SCREEN_OFF) {
                if(onPreviewClosed!=null) disable() else hide()
            } else render()
        }
    }
    private val showWhenBackground=Runnable {
        auditState("background-reveal")
        if(enabled&&activities.isEmpty()) {foreground=false;render()}
    }
    private fun activityLeft(activity:Activity) {
        activities.remove(activity)
        auditState("activity-left:${activity.javaClass.simpleName}")
        if(activities.isEmpty()) {
            handler.removeCallbacks(showWhenBackground)
            handler.postDelayed(showWhenBackground,250)
        }
    }
    private val lifecycle=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity:Activity) {activities.add(activity);foreground=true;handler.removeCallbacks(showWhenBackground);hide();auditState("activity-started:${activity.javaClass.simpleName}")}
        override fun onActivityResumed(activity:Activity) {activities.add(activity);foreground=true;handler.removeCallbacks(showWhenBackground);hide();auditState("activity-resumed:${activity.javaClass.simpleName}")}
        override fun onActivityStopped(activity:Activity) {activityLeft(activity)}
        override fun onActivityDestroyed(activity:Activity) {activityLeft(activity)}
        override fun onActivityCreated(activity:Activity,state:Bundle?) {}
        override fun onActivityPaused(activity:Activity) {}
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
    }
    private val listener=object:Player.Listener {
        override fun onEvents(player:Player,events:Player.Events) {
            if(enabled&&(player.currentMediaItem?.mediaId!=owner||player.playerError!=null||player.playbackState==Player.STATE_ENDED||player.playbackState==Player.STATE_IDLE)) disable()
            else render()
        }
    }
    init {
        if(onPreviewClosed==null) app.registerActivityLifecycleCallbacks(lifecycle)
        player.addListener(listener)
        ContextCompat.registerReceiver(app,screen,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        auditState("created")
    }

    fun enable():Boolean {
        if(onPreviewClosed!=null&&(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked||
                !app.getSystemService(android.os.PowerManager::class.java).isInteractive)) return false
        if(!Settings.canDrawOverlays(app)||player.currentMediaItem==null||player.playerError!=null||player.playbackState==Player.STATE_IDLE||player.playbackState==Player.STATE_ENDED) {
            auditState("enable-rejected")
            return false
        }
        enabled=true;owner=player.currentMediaItem?.mediaId
        if(preferences==null) preferences=scope.launch {
            SettingsRepository(app).flow.collect {settings->
                val config=Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag(settings.language))}
                textContext=app.createConfigurationContext(config)
                dark=settings.theme=="dark"||settings.theme=="system"&&config.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
                hide();render()
            }
        }
        auditState("enable")
        render()
        return enabled
    }
    private fun dp(value:Int)=(value*app.resources.displayMetrics.density).toInt()
    private val layoutScale get()=app.resources.configuration.fontScale.coerceIn(1f,1.35f)
    private fun scaledDp(value:Int)=dp((value*layoutScale).toInt().coerceAtLeast(value))
    private fun render() {
        if(!enabled||foreground||app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked||!app.getSystemService(android.os.PowerManager::class.java).isInteractive) {
            if(enabled) auditState("render-suppressed")
            hide();return
        }
        if(!Settings.canDrawOverlays(app)) {disable();return}
        if(view==null) {
            val layout=createPanel()
            val preferredWidth=if(layoutScale>1.15f) dp(360) else dp(320)
            val availableWidth=(app.resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(dp(240))
            position.width=minOf(preferredWidth,availableWidth)
            try {windows.addView(layout,position);view=layout;auditState("view-added")}
            catch(_:SecurityException) {auditState("add-view-security-denied");disable();return}
            catch(_:WindowManager.BadTokenException) {auditState("add-view-bad-token");disable();return}
        }
        val metadata=player.mediaMetadata
        val titleLabel=AudioPopupMetadata.label(metadata.title?.toString()) ?: textContext.getString(R.string.audio_popup_untitled)
        title?.text=titleLabel
        title?.contentDescription=titleLabel
        val artistLabel=AudioPopupMetadata.label(metadata.artist?.toString())
            ?: AudioPopupMetadata.label(metadata.albumTitle?.toString())
        artist?.text=artistLabel.orEmpty()
        artist?.visibility=if(artistLabel.isNullOrBlank()) View.GONE else View.VISIBLE
        val safeArtwork=EmbeddedArtwork.safeUri(app,metadata.artworkUri?.toString())
        if(safeArtwork!=artworkUri) {
            artworkRequest?.dispose();artworkRequest=null;artworkUri=safeArtwork
            val image=artwork
            image?.setImageResource(R.drawable.ic_audio_note)
            image?.imageTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
            if(safeArtwork!=null) {
                val target=object:coil.target.Target {
                    override fun onStart(placeholder:android.graphics.drawable.Drawable?) {
                        if(artworkUri==safeArtwork) {
                            image?.imageTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
                            image?.setImageDrawable(placeholder ?: ContextCompat.getDrawable(app,R.drawable.ic_audio_note))
                        }
                    }
                    override fun onSuccess(result:android.graphics.drawable.Drawable) {
                        if(artworkUri==safeArtwork) {
                            image?.imageTintList=null
                            image?.setImageDrawable(result)
                        }
                    }
                    override fun onError(error:android.graphics.drawable.Drawable?) {
                        if(artworkUri==safeArtwork) {
                            image?.imageTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
                            image?.setImageDrawable(error ?: ContextCompat.getDrawable(app,R.drawable.ic_audio_note))
                        }
                    }
                }
                val request=ImageRequest.Builder(app).data(Uri.parse(safeArtwork)).size(dp(64),dp(64))
                    .target(target).build()
                artworkRequest=Coil.imageLoader(app).enqueue(request)
            }
        }
        toggle?.setImageResource(if(player.playWhenReady) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        toggle?.contentDescription=textContext.getString(if(player.playWhenReady) R.string.player_pause else R.string.player_play)
        updateProgress()
        handler.removeCallbacks(progressTick);progressTick.run()
    }
    private fun updateProgress() {
        val duration=player.duration.takeIf {it>0} ?: 0L
        seek?.isEnabled=duration>0&&player.isCurrentMediaItemSeekable
        if(!seeking) seek?.progress=if(duration>0) ((player.currentPosition.toDouble()/duration)*1000).toInt().coerceIn(0,1000) else 0
        val shownPosition=if(seeking&&duration>0) duration*(seek?.progress ?: 0)/1000 else player.currentPosition.coerceAtLeast(0)
        elapsed?.text="${DateUtils.formatElapsedTime(shownPosition/1000)} / ${DateUtils.formatElapsedTime(duration/1000)}"
    }
    private val colors get()=if(dark) Spectrum.Dark else Spectrum.Light
    private fun createPanel():LinearLayout {
        val palette=colors
        val panel=LinearLayout(textContext).apply {
            orientation=LinearLayout.VERTICAL;setPadding(scaledDp(16),scaledDp(14),scaledDp(16),scaledDp(14));minimumHeight=scaledDp(276)
            layoutDirection=textContext.resources.configuration.layoutDirection
            background=GradientDrawable().apply {setColor(palette.surfaceContainerLow.toArgb());cornerRadius=dp(24).toFloat();setStroke(dp(1),palette.outlineVariant.toArgb())}
            elevation=dp(16).toFloat();contentDescription=textContext.getString(R.string.audio_popup_floating_title)
        }
        val header=LinearLayout(textContext).apply {
            gravity=Gravity.CENTER_VERTICAL;layoutDirection=panel.layoutDirection
        }
        header.addView(ImageView(textContext).apply {
            setImageResource(R.drawable.ic_brand_mark);scaleType=ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(scaledDp(36),scaledDp(36)))
        header.addView(TextView(textContext).apply {
            text=textContext.getString(R.string.app_name);textSize=16f;setTypeface(typeface,android.graphics.Typeface.BOLD)
            setTextColor(colors.onSurface.toArgb());gravity=Gravity.CENTER_VERTICAL;maxLines=1;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false
            setPadding(scaledDp(10),0,scaledDp(8),0);contentDescription=textContext.getString(R.string.app_name)
        },LinearLayout.LayoutParams(0,scaledDp(40),1f))
        header.addView(controlButton(R.drawable.ic_audio_dismiss,
            if(onPreviewClosed!=null) R.string.audio_popup_stop else R.string.audio_popup_dismiss) {disable()},
            LinearLayout.LayoutParams(scaledDp(44),scaledDp(44)))
        panel.addView(header,LinearLayout.LayoutParams(-1,scaledDp(44)))

        val details=LinearLayout(textContext).apply {gravity=Gravity.CENTER_VERTICAL;layoutDirection=panel.layoutDirection}
        artwork=ImageView(textContext).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=GradientDrawable().apply {setColor(this@FloatingAudioWindow.colors.surfaceVariant.toArgb());cornerRadius=dp(10).toFloat()}
            clipToOutline=true;setImageResource(R.drawable.ic_audio_note)
            imageTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
            contentDescription=textContext.getString(R.string.audio_popup_artwork)
        }
        details.addView(requireNotNull(artwork),LinearLayout.LayoutParams(scaledDp(60),scaledDp(60)))
        val metadata=LinearLayout(textContext).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
            layoutDirection=panel.layoutDirection;setPaddingRelative(scaledDp(12),0,0,0)
        }
        title=createTitle()
        metadata.addView(requireNotNull(title),LinearLayout.LayoutParams(-1,scaledDp(38)))
        artist=TextView(textContext).apply {
            textSize=12f;maxLines=1;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false
            gravity=Gravity.CENTER_VERTICAL;textDirection=View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(colors.onSurfaceVariant.toArgb())
        }
        metadata.addView(requireNotNull(artist),LinearLayout.LayoutParams(-1,scaledDp(20)))
        details.addView(metadata,LinearLayout.LayoutParams(0,scaledDp(60),1f))
        panel.addView(details,LinearLayout.LayoutParams(-1,scaledDp(60)).apply {topMargin=scaledDp(10)})

        elapsed=TextView(textContext).apply {
            textSize=12f;gravity=Gravity.END or Gravity.CENTER_VERTICAL
            textDirection=View.TEXT_DIRECTION_LTR;setTextColor(colors.onSurfaceVariant.toArgb());includeFontPadding=false;maxLines=1
        }
        panel.addView(requireNotNull(elapsed),LinearLayout.LayoutParams(-1,scaledDp(20)).apply {topMargin=scaledDp(4)})

        val controls=LinearLayout(textContext).apply {gravity=Gravity.CENTER_VERTICAL;layoutDirection=panel.layoutDirection}
        toggle=controlButton(R.drawable.ic_player_pause,R.string.player_pause) {if(player.playWhenReady) player.pause() else player.play()}
        controls.addView(requireNotNull(toggle),LinearLayout.LayoutParams(scaledDp(48),scaledDp(48)))
        seek=createSeek()
        controls.addView(requireNotNull(seek),LinearLayout.LayoutParams(0,scaledDp(48),1f).apply {marginStart=scaledDp(4)})
        panel.addView(controls,LinearLayout.LayoutParams(-1,scaledDp(48)).apply {topMargin=scaledDp(2)})

        val open=TextView(textContext).apply {
            text=textContext.getString(R.string.audio_popup_open_in_app);textSize=14f
            gravity=Gravity.CENTER;setTextColor(palette.onSurface.toArgb());maxLines=1;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false
            contentDescription=textContext.getString(R.string.audio_popup_open_in_app)
            isClickable=true;isFocusable=true
            background=null
            setPadding(scaledDp(12),0,scaledDp(12),0)
            setOnClickListener {openFullPlayer()}
        }
        panel.addView(open,LinearLayout.LayoutParams(-1,scaledDp(42)).apply {topMargin=scaledDp(8)})
        installDrag(requireNotNull(title))
        return panel
    }
    private fun createTitle()=TextView(textContext).apply {
            setTextColor(colors.onSurface.toArgb());textSize=14f;setSingleLine(true);maxLines=1
            ellipsize=TextUtils.TruncateAt.MARQUEE;marqueeRepeatLimit=-1;isSelected=true;isHorizontalFadingEdgeEnabled=true
            setFadingEdgeLength(dp(12));includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL
            textDirection=View.TEXT_DIRECTION_FIRST_STRONG
            contentDescription=textContext.getString(R.string.audio_popup_untitled)
            setOnClickListener {openFullPlayer()}
    }
    private fun createSeek()=SeekBar(textContext).apply {
        max=1000;progressTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.outlineVariant.toArgb())
        thumbTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
        contentDescription=textContext.getString(R.string.audio_popup_seek)
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar) {seeking=true}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {if(fromUser) updateProgress()}
            override fun onStopTrackingTouch(bar:SeekBar) {
                val duration=player.duration
                seeking=false
                if(duration>0&&player.isCurrentMediaItemSeekable) player.seekTo(duration*bar.progress/1000)
                updateProgress()
            }
        })
    }
    private fun openFullPlayer() {
        if(onPreviewClosed!=null) {
            onPreviewOpen?.invoke()
            return
        }
        app.startActivity(Intent(app,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun controlButton(icon:Int,label:Int,click:()->Unit)=ImageButton(textContext).apply {
        setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
        contentDescription=textContext.getString(label);setPadding(scaledDp(13),scaledDp(13),scaledDp(13),scaledDp(13))
        val style=textContext.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background=style.getDrawable(0);style.recycle()
        setOnClickListener {click()}
    }
    private fun installDrag(handle:View) {
        var startX=0f;var startY=0f;var x=0;var y=0;var moved=false
        handle.setOnTouchListener {view,event->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{startX=event.rawX;startY=event.rawY;x=position.x;y=position.y;moved=false;true}
                MotionEvent.ACTION_MOVE->{
                    val dx=(event.rawX-startX).toInt();val dy=(event.rawY-startY).toInt()
                    moved=moved||kotlin.math.abs(dx)+kotlin.math.abs(dy)>ViewConfiguration.get(app).scaledTouchSlop
                    val metrics=app.resources.displayMetrics
                    val horizontal=(metrics.widthPixels-position.width).coerceAtLeast(0)/2
                    val vertical=(metrics.heightPixels-(this.view?.height ?: dp(280))).coerceAtLeast(0)/2
                    position.x=(x+dx).coerceIn(-horizontal,horizontal)
                    position.y=(y+dy).coerceIn(-vertical,vertical)
                    this.view?.let {try {windows.updateViewLayout(it,position)} catch(_:SecurityException) {disable()} catch(_:IllegalArgumentException) {disable()}}
                    true
                }
                MotionEvent.ACTION_UP->{if(!moved) view.performClick();true}
                MotionEvent.ACTION_CANCEL->true
                else->false
            }
        }
    }
    private fun hide() {
        handler.removeCallbacks(progressTick)
        artworkRequest?.dispose();artworkRequest=null;artworkUri=null;seeking=false
        view?.let {try {windows.removeViewImmediate(it)} catch(_:IllegalArgumentException) {}}
        view=null;title=null;artist=null;elapsed=null;artwork=null;toggle=null;seek=null
    }
    fun disable() {
        val notify=enabled
        enabled=false;owner=null;handler.removeCallbacks(showWhenBackground);preferences?.cancel();preferences=null;hide()
        if(notify) onPreviewClosed?.invoke()
    }
    fun release() {
        // Owner-initiated disposal/replacement is not a user dismissal.
        enabled=false
        disable();scope.cancel();player.removeListener(listener)
        if(onPreviewClosed==null) app.unregisterActivityLifecycleCallbacks(lifecycle)
        app.unregisterReceiver(screen);activities.clear()
    }
}

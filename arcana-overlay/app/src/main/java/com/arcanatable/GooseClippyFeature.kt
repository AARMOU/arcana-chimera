package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Independent master ON/OFF, not a property of Studio presets. Changing XP skins never resets it.
 * A value of false disables animation, contextual updates and any on-screen GooseClippy UI.
 */
internal object GooseClippyPreferences {
    const val FILE="gooseclippy_preferences"
    const val ENABLED="gooseclippy_enabled"
    const val DEFAULT_ENABLED=true

    fun read(prefs:SharedPreferences):Boolean=prefs.getBoolean(ENABLED,DEFAULT_ENABLED)
    fun write(prefs:SharedPreferences,enabled:Boolean){prefs.edit().putBoolean(ENABLED,enabled).apply()}
    fun prefs(context:Context):SharedPreferences=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
}

@Composable
internal fun rememberGooseClippySwitch():Pair<Boolean,(Boolean)->Unit> {
    val context=LocalContext.current
    val prefs=remember(context){GooseClippyPreferences.prefs(context)}
    var enabled by remember(prefs){mutableStateOf(GooseClippyPreferences.read(prefs))}
    DisposableEffect(prefs) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { changed,key ->
            if(key==GooseClippyPreferences.ENABLED) enabled=GooseClippyPreferences.read(changed)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val onChange: (Boolean)->Unit=remember(prefs) { { next:Boolean->
        enabled=next
        GooseClippyPreferences.write(prefs,next)
    }}
    return enabled to onChange
}

/** Studio can show this row in Layout / Presets. XP's Start menu may invoke the same row. */
@Composable
internal fun GooseClippySettingsToggle(modifier:Modifier=Modifier) {
    val (enabled,setEnabled)=rememberGooseClippySwitch()
    Row(modifier.fillMaxWidth().testTag("gooseclippy-toggle"),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("GooseClippy",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Text(if(enabled)"On — terrible advice enabled" else "Off — completely hidden and silent",
                fontSize=11.sp,color=Color.Gray)
        }
        Switch(checked=enabled,onCheckedChange=setEnabled,modifier=Modifier.testTag("gooseclippy-enabled"))
    }
}

/** Uses the same masked game state Arcana already displays; does not read engine internals. */
internal fun TableController.gooseClippyObservation():GooseObservation? {
    val state=argentumSnapshot ?: return null
    return GooseObservation(
        turn=state.turnNumber,
        activeSeat=state.activePlayer,
        localSeat=localSeat,
        prioritySeat=state.priorityPlayer,
        stepKey=state.stepKey,
        stackSize=state.stack.size,
        handSize=players.getOrNull(localSeat)?.hand?.size,
        life=players.getOrNull(localSeat)?.life,
        gameOver=state.gameOver
    )
}

/**
 * Displays the owner-approved transparent GIF assets, not a recreated paperclip.
 * Required: app/src/main/assets/gooseclippy_idle.gif and gooseclippy_react.gif.
 * The original artwork does not touch or filter Magic card images.
 */
@Composable
internal fun GooseClippyAnimatedSprite(talking:Boolean,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    // Decode both small animations once; event transitions must not decode GIFs on the UI thread.
    val animations=remember(context) {
        fun load(filename:String)=runCatching {
            if(Build.VERSION.SDK_INT>=28) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets,filename))
            } else {
                context.assets.open(filename).use { stream->
                    BitmapDrawable(context.resources, BitmapFactory.decodeStream(stream))
                }
            }
        }.getOrNull()
        load("gooseclippy_idle.gif") to load("gooseclippy_react.gif")
    }
    val drawable=if(talking) animations.second ?: animations.first else animations.first
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose {(drawable as? AnimatedImageDrawable)?.stop()}
    }
    if(drawable==null){
        // Deliberately obvious missing-art state: do not fake an approved image in a release.
        Text("GooseClippy asset missing",modifier=modifier.testTag("gooseclippy-assets-missing"),
            color=Color.Red,fontSize=9.sp)
    }else{
        AndroidView(
            modifier=modifier.testTag("gooseclippy-sprite"),
            factory={c->ImageView(c).apply {scaleType=ImageView.ScaleType.FIT_CENTER}},
            update={view->if(view.drawable!==drawable)view.setImageDrawable(drawable)}
        )
    }
}

/**
 * Standalone overlay: Claude's XP layout only needs one call inside its root Box:
 *
 *     GooseClippyAssistant(observation = controller.gooseClippyObservation())
 *
 * All positions and enabled state persist; never triggers Forge actions or modal alerts.
 * If the user turns GooseClippy off in Studio, the entire overlay disappears.
 */
@Composable
internal fun GooseClippyAssistant(observation:GooseObservation?,modifier:Modifier=Modifier){
    val (enabled,setEnabled)=rememberGooseClippySwitch()
    if(!enabled)return
    val context=LocalContext.current
    val prefs=remember(context){GooseClippyPreferences.prefs(context)}
    val deck=remember{GooseQuipDeck()}
    var quip by remember{mutableStateOf(deck.next(GooseMoment.WELCOME))}
    var previous by remember{mutableStateOf<GooseObservation?>(null)}
    var talking by remember{mutableStateOf(false)}
    var lastSpoke by remember{mutableLongStateOf(0L)}
    LaunchedEffect(observation){
        if(observation!=null){
            val moment=GooseMomentDetector.change(previous,observation)
            val now=SystemClock.elapsedRealtime()
            if(moment!=null && moment!=GooseMoment.WELCOME &&
                (moment==GooseMoment.LOW_LIFE || moment==GooseMoment.GAME_OVER || now-lastSpoke>=2500L)){
                quip=deck.next(moment)
                talking=true
                lastSpoke=now
            }
            previous=observation
        }
    }
    LaunchedEffect(talking){
        if(talking){delay(1600L);talking=false}
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("gooseclippy-overlay")) {
        val screenClass=if(maxWidth.value<550f)"cover" else "open"
        val xKey="assistant_x_$screenClass";val yKey="assistant_y_$screenClass"
        val maxX=max(0f,maxWidth.value-285f)
        val maxY=max(0f,maxHeight.value-175f)
        var x by remember(screenClass){mutableFloatStateOf(prefs.getFloat(xKey,0f).coerceIn(0f,maxX))}
        var y by remember(screenClass){mutableFloatStateOf(prefs.getFloat(yKey,0f).coerceIn(0f,maxY))}
        val density=LocalDensity.current.density
        LaunchedEffect(maxX,maxY){x=x.coerceIn(0f,maxX);y=y.coerceIn(0f,maxY)}
        Row(Modifier.align(Alignment.BottomStart).offset(x.dp,(-y).dp)
            .pointerInput(screenClass,density){
                detectDragGestures(onDragEnd={
                    prefs.edit().putFloat(xKey,x).putFloat(yKey,y).apply()
                }){change,drag->
                    change.consume()
                    x=(x+drag.x/density).coerceIn(0f,maxX)
                    y=(y-drag.y/density).coerceIn(0f,maxY)
                }
            }.testTag("gooseclippy-draggable"),
            verticalAlignment=Alignment.Bottom,
            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            GooseClippyAnimatedSprite(talking,Modifier.size(width=96.dp,height=144.dp).clickable {
                quip=deck.next(GooseMoment.GENERAL)
                talking=true
            })
            Surface(
                modifier=Modifier.width(182.dp).padding(bottom=30.dp),
                color=Color(0xFFFFF4B9),
                shape=RoundedCornerShape(9.dp),
                border=BorderStroke(2.dp,Color(0xFF615126)),
                shadowElevation=6.dp
            ){
                Column(Modifier.padding(start=9.dp,end=4.dp,top=3.dp,bottom=7.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                        TextButton(onClick={setEnabled(false)},modifier=Modifier.height(25.dp)
                            .testTag("gooseclippy-close")){
                            Text("✕",fontSize=12.sp,color=Color(0xFF61431A))
                        }
                    }
                    Text(quip,color=Color(0xFF171717),fontSize=12.sp,lineHeight=15.sp)
                }
            }
        }
    }
}

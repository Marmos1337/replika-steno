package ru.replika.steno.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import ru.replika.steno.ui.theme.Radius
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

enum class PlayerStatus { LOADING, READY, ERROR }

/** MediaPlayer with Compose-observable state. Created and released by [rememberAudioPlayer]. */
@Stable
class AudioPlayer internal constructor() {
    var status by mutableStateOf(PlayerStatus.LOADING)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set

    private var mp: MediaPlayer? = null
    private var pendingSeek: Long? = null
    private var pendingPlay = false

    internal suspend fun open(file: File) {
        val player = MediaPlayer()
        mp = player
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        player.setOnPreparedListener {
            durationMs = it.duration.toLong().coerceAtLeast(0)
            status = PlayerStatus.READY
            pendingSeek?.let { ms -> seek(ms) }
            pendingSeek = null
            if (pendingPlay) play()
            pendingPlay = false
        }
        player.setOnErrorListener { _, _, _ ->
            status = PlayerStatus.ERROR
            playing = false
            true
        }
        player.setOnCompletionListener {
            playing = false
            positionMs = durationMs
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { require(file.exists() && file.length() > 0); player.setDataSource(file.absolutePath) }.isSuccess
        }
        if (mp !== player) return // released while opening
        if (!ok) {
            status = PlayerStatus.ERROR
            return
        }
        runCatching { player.prepareAsync() }.onFailure { status = PlayerStatus.ERROR }
    }

    fun play() {
        val p = mp ?: return
        if (status != PlayerStatus.READY) {
            pendingPlay = status == PlayerStatus.LOADING
            return
        }
        if (durationMs > 0 && positionMs >= durationMs - 200) seek(0)
        runCatching { p.start(); playing = true }
    }

    fun pause() {
        val p = mp ?: return
        if (playing) runCatching { p.pause() }
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    fun seek(ms: Long) {
        val p = mp ?: return
        val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        if (status != PlayerStatus.READY) {
            pendingSeek = target
            return
        }
        runCatching { p.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
        positionMs = target
    }

    fun seekAndPlay(ms: Long) {
        seek(ms)
        play()
    }

    fun skip(deltaMs: Long) = seek(positionMs + deltaMs)

    internal fun tick() {
        val p = mp ?: return
        if (playing) runCatching { positionMs = p.currentPosition.toLong() }
    }

    internal fun release() {
        mp?.let { runCatching { it.release() } }
        mp = null
        playing = false
    }
}

@Composable
fun rememberAudioPlayer(file: File): AudioPlayer {
    val player = remember(file) { AudioPlayer() }
    LaunchedEffect(player) { player.open(file) }
    DisposableEffect(player) { onDispose { player.release() } }
    // Position ticks only while playing.
    LaunchedEffect(player, player.playing) {
        while (player.playing) withFrameMillis { player.tick() }
    }
    // Leaving the app pauses playback.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return player
}

/** Bottom player; keeps the same height while loading, ready or failed. */
@Composable
fun PlayerBar(player: AudioPlayer, side: Dp, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(Size.stroke).background(c.border))
        Fade(player.status == PlayerStatus.ERROR, Modifier.fillMaxWidth().navigationBarsPadding().height(Size.player).padding(horizontal = side)) { failed ->
            if (failed) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Ic.Alert, null, Modifier.size(Size.icon), tint = c.danger)
                    Spacer(Modifier.width(Space.s))
                    Text("Не удалось открыть аудио", style = Type.body, color = c.text)
                }
            } else {
                PlayerControls(player)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(player: AudioPlayer) {
    val c = Theme.colors
    val ready = player.status == PlayerStatus.READY
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = player.durationMs
    val frac = drag ?: if (dur > 0) (player.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        // thin monochrome scrubber: 4dp track, 14dp round thumb, full 48dp touch height
        Slider(
            value = frac,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { player.seek((it * dur).toLong()) }
                drag = null
            },
            enabled = ready && dur > 0,
            thumb = {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (ready) c.text else c.muted))
            },
            track = {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(Radius.pill).background(c.raised)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(Radius.pill).background(if (ready) c.text else c.muted))
                }
            },
            modifier = Modifier.fillMaxWidth().height(Size.touch),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val shown = drag?.let { (it * dur).toLong() } ?: player.positionMs
            Text(fmtTime(shown), style = Type.labelNum, color = c.text, modifier = Modifier.weight(1f))
            SkipButton(Ic.Rewind, "Назад на 10 секунд", ready) { player.skip(-10_000) }
            Spacer(Modifier.width(Space.s))
            IconBtn(
                if (player.playing) Ic.Pause else Ic.Play,
                if (player.playing) "Пауза" else "Слушать",
                player::toggle, enabled = ready, filled = ready,
            )
            Spacer(Modifier.width(Space.s))
            SkipButton(Ic.Forward, "Вперёд на 10 секунд", ready) { player.skip(10_000) }
            Text(
                if (ready) fmtTime(dur) else "--:--", style = Type.labelNum, color = c.muted,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    Box(contentAlignment = Alignment.Center) {
        IconBtn(icon, description, onClick, enabled = enabled, modifier = Modifier.widthIn(min = Size.touch))
        // tiny glyph inside the circular arrow, part of the icon rather than of the type scale
        Text("10", style = Type.labelNum.copy(fontSize = 8.sp, lineHeight = 8.sp), color = if (enabled) c.text else c.muted)
    }
}

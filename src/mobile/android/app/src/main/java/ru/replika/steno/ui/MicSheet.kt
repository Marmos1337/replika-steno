package ru.replika.steno.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

private enum class Perm { GRANTED, ASK, DENIED_FOREVER }

private const val BARS = 40
private val SHEET_BODY = 300.dp

/** Bottom sheet for recording from the microphone; the sheet keeps one height in every state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicSheet(vm: ReplikaViewModel, onClose: () -> Unit) {
    val c = Theme.colors
    val context = LocalContext.current
    val state by vm.micState.collectAsState()
    val recording by rememberUpdatedState(state is MicState.Recording || state is MicState.Saving)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !recording },
    )
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var perm by rememberSaveable { mutableStateOf(if (granted()) Perm.GRANTED else Perm.ASK) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        perm = when {
            ok -> Perm.GRANTED
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> Perm.ASK
            else -> Perm.DENIED_FOREVER
        }
        if (ok) vm.startMic()
    }
    // Coming back from system settings: pick up a permission granted there.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && perm != Perm.GRANTED && granted()) perm = Perm.GRANTED }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Recording successfully handed to the store -> the view model navigated; close the sheet.
    var wasSaving by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is MicState.Saving) wasSaving = true
        else if (wasSaving && state is MicState.Idle) onClose()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!recording) { vm.resetMicError(); onClose() } },
        sheetState = sheet,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = c.scrim,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(Modifier.padding(vertical = Space.m).width(Space.xxl).height(Space.xs).clip(Radius.pill).background(c.borderStrong))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.xl).padding(bottom = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Запись с микрофона", style = Type.heading, color = c.text)
            Spacer(Modifier.height(Space.l))
            val view = when {
                perm != Perm.GRANTED -> "perm"
                else -> when (state) {
                    MicState.Idle -> "idle"
                    is MicState.Recording -> "rec"
                    MicState.Saving -> "saving"
                    is MicState.Failed -> "failed"
                }
            }
            Fade(view, Modifier.fillMaxWidth().height(SHEET_BODY)) { v ->
                Column(Modifier.fillMaxSize().widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (v) {
                        "perm" -> PermissionBody(perm == Perm.DENIED_FOREVER,
                            onAsk = { request.launch(Manifest.permission.RECORD_AUDIO) },
                            onSettings = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            })
                        "idle" -> IdleBody(onStart = vm::startMic)
                        "rec" -> RecordingBody(vm, (state as? MicState.Recording)?.startedAt ?: SystemClock.elapsedRealtime())
                        "saving" -> CenterNote("Сохраняю запись…")
                        else -> FailedBody((state as? MicState.Failed)?.message.orEmpty(), onRetry = { vm.resetMicError(); vm.startMic() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionBody(forever: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    val c = Theme.colors
    Spacer(Modifier.height(Space.l))
    RoundIcon(Ic.MicOff)
    Spacer(Modifier.height(Space.l))
    Text("Нужен доступ к микрофону", style = Type.bodyStrong, color = c.text, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.s))
    Text(
        if (forever) "Доступ запрещён. Его можно включить в настройках приложения: Разрешения → Микрофон."
        else "Звук пишется только в память телефона и никуда не отправляется.",
        style = Type.body, color = c.muted, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.weight(1f))
    if (forever) PrimaryButton("Открыть настройки", onSettings, Modifier.fillMaxWidth())
    else PrimaryButton("Разрешить", onAsk, Modifier.fillMaxWidth(), Ic.Mic)
}

@Composable
private fun ColumnScope.IdleBody(onStart: () -> Unit) {
    val c = Theme.colors
    Spacer(Modifier.weight(1f))
    Box(
        Modifier.size(96.dp).clip(Radius.pill).background(c.text)
            .clickable(role = Role.Button, onClickLabel = "Начать запись", onClick = onStart),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(Space.xxl).clip(Radius.pill).background(c.danger)) }
    Spacer(Modifier.height(Space.l))
    Text("Нажмите, чтобы начать", style = Type.bodyStrong, color = c.text)
    Spacer(Modifier.height(Space.s))
    Text("Положите телефон ближе к говорящим.", style = Type.body, color = c.muted, textAlign = TextAlign.Center)
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.RecordingBody(vm: ReplikaViewModel, startedAt: Long) {
    val c = Theme.colors
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val levels = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            levels.removeAt(0)
            levels.add(sqrt(vm.micLevel()))
            delay(80)
        }
    }
    val pulse = if (Theme.reducedMotion) 1f else {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot").value
    }
    Spacer(Modifier.height(Space.s))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Space.m).scale(1f).clip(Radius.pill).background(c.danger.copy(alpha = pulse)))
        Spacer(Modifier.width(Space.s))
        Text("Идёт запись", style = Type.label, color = c.muted)
    }
    Spacer(Modifier.height(Space.s))
    Text(fmtTime(now - startedAt), style = Type.displayNum, color = c.text)
    Spacer(Modifier.height(Space.l))
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (BARS - 1)) / BARS
        levels.forEachIndexed { i, l ->
            val h = (size.height * l).coerceAtLeast(w)
            drawRoundRect(
                c.text.copy(alpha = 0.35f + 0.65f * (i + 1f) / BARS),
                Offset(i * (w + gap), (size.height - h) / 2f), GSize(w, h), CornerRadius(w / 2f, w / 2f),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    PrimaryButton("Остановить и расшифровать", vm::stopMic, Modifier.fillMaxWidth(), Ic.Stop)
    Spacer(Modifier.height(Space.s))
    TextAction("Отменить запись", vm::discardMic, color = c.muted)
}

@Composable
private fun ColumnScope.FailedBody(message: String, onRetry: () -> Unit) {
    val c = Theme.colors
    Spacer(Modifier.height(Space.l))
    RoundIcon(Ic.Alert, danger = true)
    Spacer(Modifier.height(Space.l))
    Text("Не получилось записать", style = Type.bodyStrong, color = c.text)
    Spacer(Modifier.height(Space.s))
    Text(message, style = Type.body, color = c.muted, textAlign = TextAlign.Center)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Попробовать снова", onRetry, Modifier.fillMaxWidth())
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Type.body, color = Theme.colors.muted)
    }
}

@Composable
fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false) = EmptyIcon(icon, danger)

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

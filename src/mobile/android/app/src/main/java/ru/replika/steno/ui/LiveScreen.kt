package ru.replika.steno.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.replika.steno.core.live.LiveLine
import ru.replika.steno.core.live.LivePartial
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.LiveStatus
import ru.replika.steno.core.live.liveVoiceLabel
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

/** Microphone permission as the screen knows it. ASK: not asked yet or asked once and refused. */
private enum class Perm { GRANTED, ASK, DENIED, DENIED_FOREVER }

/** What the screen shows; header and action bar keep their size in every phase. */
private enum class Phase { IDLE, PERMISSION, LIVE, ERROR }

/** A new speaker header after this much silence even when the voice is the same. */
private const val TURN_GAP_MS = 20_000L

/** Level history kept for the meter; the meter draws as many of the newest values as fit. */
private const val LEVEL_HISTORY = 96
private const val LEVEL_TICK_MS = 80L

/** The info line under the header always reserves two lines of text, so the list never moves when it changes. */
private val INFO_MIN = Type.label.lineHeight.value.dp * 2 + Space.m * 2
private val METER_HEIGHT = 40.dp

private const val UI_PREFS = "ui"
private const val ASKED_NOTIFICATIONS = "asked_notifications"

@Composable
fun LiveScreen(vm: ReplikaViewModel) {
    val c = Theme.colors
    val context = LocalContext.current
    val live by vm.live.collectAsState()
    val enginesReady by vm.enginesReady.collectAsState()
    val items by vm.recordings.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }
    /** Short-lived note shown in the info line (the recording notification is off). */
    var localNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(localNote) {
        if (localNote != null) { delay(8_000); localNote = null }
    }

    // Models load while the user is still looking at the start screen; freed again when it closes.
    DisposableEffect(Unit) {
        vm.prepareLive()
        onDispose { vm.leaveLive() }
    }

    // ---- permissions
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var perm by rememberSaveable { mutableStateOf(if (granted()) Perm.GRANTED else Perm.ASK) }
    val prefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    val notificationOff = "Уведомления выключены: значка записи в шторке не будет. Запись при этом идёт."
    val notifRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) localNote = notificationOff
    }
    fun begin() {
        if (!vm.startLive()) return
        if (vm.notificationPermissionNeeded() && Build.VERSION.SDK_INT >= 33) {
            // asked once; after that only the note says the notification is off
            if (!prefs.getBoolean(ASKED_NOTIFICATIONS, false)) {
                prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
                notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else localNote = notificationOff
        }
    }
    val micRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        perm = when {
            ok -> Perm.GRANTED
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> Perm.DENIED
            else -> Perm.DENIED_FOREVER
        }
        if (ok) begin()
    }
    // Back from the system settings: pick up a permission granted there.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && perm != Perm.GRANTED && granted()) perm = Perm.GRANTED }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val start = {
        vm.dismissLive()
        if (granted()) { perm = Perm.GRANTED; begin() } else micRequest.launch(Manifest.permission.RECORD_AUDIO)
    }
    val openSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    // ---- session end: the finished take goes on to the normal processing screen
    val s = live
    LaunchedEffect(s?.status, s?.recordingId) {
        if (s?.status == LiveStatus.DONE) {
            val id = s.recordingId
            vm.dismissLive()
            vm.navigate(if (id != null) Screen.Processing(id) else Screen.Library)
        }
    }

    // Screen stays on while capturing; the foreground service keeps the take going either way.
    val capturing = s != null && !s.status.terminal
    KeepScreenOn(capturing)

    val phase = when {
        s?.status == LiveStatus.ERROR -> Phase.ERROR
        s != null -> Phase.LIVE
        perm == Perm.DENIED || perm == Perm.DENIED_FOREVER -> Phase.PERMISSION
        else -> Phase.IDLE
    }
    val saved = s?.recordingId?.let { id -> items.any { it.id == id } } == true

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        val listState = rememberLazyListState()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(leading = { IconBtn(Ic.Back, "К записям", { vm.navigate(Screen.Library) }) }, side = side, title = "Живая запись")
            Column(Modifier.padding(horizontal = side)) {
                LiveHeader(s, enginesReady)
                Spacer(Modifier.height(Space.s))
                InfoLine(info(s, phase, saved, localNote))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LiveTranscript(
                    lines = s?.lines.orEmpty(),
                    partial = s?.partial,
                    state = listState,
                    side = side,
                    empty = emptyContent(s, phase, perm, enginesReady, saved),
                )
                // hairline under the fixed header while lines scroll beneath it
                val line by animateFloatAsState(if (listState.canScrollBackward) 1f else 0f, Motion.spec(Theme.reducedMotion, Motion.FAST), label = "liveDivider")
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(Size.stroke).alpha(line).background(c.border))
            }
            ActionBar(
                phase = phase,
                state = s,
                perm = perm,
                saved = saved,
                side = side,
                onStart = start,
                onSettings = openSettings,
                onPause = vm::pauseLive,
                onResume = vm::resumeLive,
                onStop = vm::stopLive,
                onDiscard = { confirmDiscard = true },
                onOpenSaved = {
                    val id = s?.recordingId
                    vm.dismissLive()
                    vm.navigate(if (id != null && saved) Screen.Processing(id) else Screen.Library)
                },
                onClose = {
                    vm.dismissLive()
                    vm.navigate(Screen.Library)
                },
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Удалить запись?",
            text = "Звук и текст этой записи не сохранятся. Отменить это нельзя.",
            confirm = "Удалить", danger = true,
            onConfirm = {
                vm.discardLive()
                vm.navigate(Screen.Library)
                vm.toast("Запись удалена")
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        val window = context.findActivity()?.window
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (on) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ---------------------------------------------------------------- header

private data class HeaderText(val title: String, val subtitle: String)

private fun headerText(s: LiveState?, enginesReady: Boolean): HeaderText = when (s?.status) {
    null -> HeaderText(
        "Готово к записи",
        if (enginesReady) "Распознавание готово · всё на устройстве" else "Готовлю распознавание…",
    )
    LiveStatus.STARTING -> HeaderText("Включаю микрофон", "Звук пишется только в память телефона")
    LiveStatus.RECORDING -> HeaderText(
        "Идёт запись",
        if (s.modelsReady) "Распознаётся на устройстве" else "Готовлю распознавание… звук уже пишется",
    )
    LiveStatus.PAUSED -> HeaderText("Пауза", "Микрофон выключен, пауза в запись не входит")
    LiveStatus.FINISHING -> HeaderText("Завершаю…", "Дораспознаю последние фразы")
    LiveStatus.DONE -> HeaderText("Запись сохранена", "Открываю обработку…")
    LiveStatus.ERROR -> HeaderText("Запись остановлена", "Подробности ниже")
}

/** Rec dot + state + timer, the level meter and one status line; the same size in every state. */
@Composable
private fun LiveHeader(s: LiveState?, enginesReady: Boolean) {
    val c = Theme.colors
    val text = headerText(s, enginesReady)
    val status = s?.status
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RecDot(status)
            Spacer(Modifier.width(Space.s))
            Fade(text.title, Modifier.weight(1f)) { t ->
                Text(t, style = Type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.m))
            Text(
                fmtTime(s?.elapsedMs ?: 0), style = Type.displayNum,
                color = if (status == LiveStatus.RECORDING) c.text else c.muted, maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Длительность ${fmtTime(s?.elapsedMs ?: 0)}" },
            )
        }
        Spacer(Modifier.height(Space.m))
        LevelMeter(level = s?.level ?: 0f, active = status == LiveStatus.RECORDING, modifier = Modifier.fillMaxWidth().height(METER_HEIGHT))
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Shield, null, Modifier.size(Size.iconSmall), tint = c.muted)
            Spacer(Modifier.width(Space.s))
            Fade(text.subtitle, Modifier.weight(1f)) { t ->
                Text(t, style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Red dot: pulses while recording (steady with reduced motion), grey when idle or paused. */
@Composable
fun RecDot(status: LiveStatus?, size: androidx.compose.ui.unit.Dp = Space.m) {
    val c = Theme.colors
    val recording = status == LiveStatus.RECORDING || status == LiveStatus.STARTING
    val alpha = if (recording && !Theme.reducedMotion) {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "recDot").value
    } else 1f
    val color = when (status) {
        LiveStatus.RECORDING, LiveStatus.STARTING, LiveStatus.ERROR -> c.danger
        else -> c.muted
    }
    Box(Modifier.size(size).clip(Radius.pill).background(color.copy(alpha = alpha)))
}

/**
 * Scrolling bar meter of the input level. Ticks only while recording; on pause the bars settle to the
 * floor and the loop stops, so nothing animates while idle.
 */
@Composable
private fun LevelMeter(level: Float, active: Boolean, modifier: Modifier) {
    val c = Theme.colors
    val history = remember { FloatArray(LEVEL_HISTORY) }
    var tick by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(level)
    LaunchedEffect(active) {
        var settle = LEVEL_HISTORY
        while (active || settle-- > 0) {
            System.arraycopy(history, 1, history, 0, LEVEL_HISTORY - 1)
            // speech sits around 0.45..0.9 of the dB scale; stretch it so quiet rooms still read as quiet
            history[LEVEL_HISTORY - 1] = if (active) ((current - 0.2f) / 0.65f).coerceIn(0f, 1f) else 0f
            tick++
            delay(LEVEL_TICK_MS)
            if (!active && history.all { it == 0f }) break
        }
    }
    Canvas(modifier.semantics { contentDescription = if (active) "Уровень сигнала" else "Микрофон не пишет" }) {
        @Suppress("UNUSED_EXPRESSION") tick // redraw on every tick
        val w = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val n = ((size.width + gap) / (w + gap)).toInt().coerceIn(1, LEVEL_HISTORY)
        val x0 = size.width - (n * (w + gap) - gap)
        for (i in 0 until n) {
            val v = history[LEVEL_HISTORY - n + i]
            val h = (size.height * v).coerceAtLeast(w)
            // older bars fade out to the left
            val a = 0.25f + 0.75f * (i + 1f) / n
            drawRoundRect(
                (if (v > 0f) c.text else c.borderStrong).copy(alpha = if (v > 0f) a else 1f),
                Offset(x0 + i * (w + gap), (size.height - h) / 2f), GSize(w, h), CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------- info line

private enum class InfoKind { CALM, WARN, ERROR }

private data class Info(val kind: InfoKind, val icon: ImageVector, val text: String)

private fun info(s: LiveState?, phase: Phase, saved: Boolean, localNote: String?): Info = when {
    phase == Phase.ERROR -> Info(
        InfoKind.ERROR, Ic.Alert,
        (s?.error ?: "Запись прервалась.") + if (saved) " Записанное до этого момента сохранено." else "",
    )
    s?.micSilenced == true -> Info(InfoKind.WARN, Ic.MicOff, "Микрофон не слышит звук — проверьте, не занят ли он другим приложением")
    s?.lagging == true && s.status == LiveStatus.RECORDING -> Info(
        InfoKind.WARN, Ic.Alert,
        "Распознавание отстаёт на ${(s.backlogMs + 500) / 1000} с — звук сохраняется, текст догонит",
    )
    s?.notice != null -> Info(InfoKind.CALM, Ic.Info, s.notice)
    localNote != null -> Info(InfoKind.CALM, Ic.Info, localNote)
    s != null -> Info(InfoKind.CALM, Ic.Question, "Голоса предварительные: участники и окончательный текст появятся после завершения")
    else -> Info(InfoKind.CALM, Ic.Info, "Текст идёт по ходу разговора, а после завершения — полная обработка с участниками и итогами.")
}

/** One fixed slot under the header: the voices legend by default, warnings and notices in its place. */
@Composable
private fun InfoLine(info: Info) {
    val c = Theme.colors
    // The key is the kind + icon, so a lag counter ticking up changes the text without a cross-fade.
    // Each key keeps the last text it showed, so the outgoing layer fades out with its own words.
    val key = info.kind to info.icon
    val shown = remember { HashMap<Pair<InfoKind, ImageVector>, Info>() }
    shown[key] = info
    Fade(key, Modifier.fillMaxWidth().heightIn(min = INFO_MIN)) { k ->
        val info = shown[k] ?: info
        val warn = info.kind != InfoKind.CALM
        Row(
            Modifier.fillMaxWidth().heightIn(min = INFO_MIN).clip(Radius.r14)
                .then(if (warn) Modifier.background(c.raised) else Modifier.border(Size.stroke, c.border, Radius.r14))
                .padding(horizontal = Space.m, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(info.icon, null, Modifier.size(Size.iconSmall), tint = if (info.kind == InfoKind.ERROR) c.danger else if (warn) c.text else c.muted)
            Spacer(Modifier.width(Space.s))
            Text(info.text, style = Type.label, color = if (warn) c.text else c.muted, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- transcript

private data class Empty(val key: String, val icon: ImageVector, val title: String, val text: String, val danger: Boolean = false)

private fun emptyContent(s: LiveState?, phase: Phase, perm: Perm, enginesReady: Boolean, saved: Boolean): Empty = when (phase) {
    Phase.PERMISSION -> if (perm == Perm.DENIED_FOREVER) {
        Empty("perm2", Ic.MicOff, "Доступ к микрофону запрещён", "Его можно включить в настройках приложения: Разрешения → Микрофон.")
    } else {
        Empty("perm", Ic.MicOff, "Нужен доступ к микрофону", "Без него записать встречу не получится. Звук пишется только в память телефона и никуда не отправляется.")
    }
    // the reason itself is in the info line above; here only what to do next
    Phase.ERROR -> if (saved) {
        Empty("errorSaved", Ic.Alert, "Запись прервалась", "Записанное сохранено. Откройте запись — она пройдёт полную обработку.", danger = true)
    } else {
        Empty("error", Ic.Alert, "Не получилось записать", "Ничего не сохранено. Можно начать запись заново.", danger = true)
    }
    Phase.IDLE -> Empty(
        "idle", Ic.Mic, "Текст появится здесь",
        if (enginesReady) "Нажмите «Начать запись» и говорите — фразы появятся по ходу разговора."
        else "Модели распознавания загружаются. Начать запись можно уже сейчас — ничего не потеряется.",
    )
    Phase.LIVE -> when {
        s?.status == LiveStatus.PAUSED -> Empty("paused", Ic.Pause, "Запись на паузе", "Нажмите «Продолжить», и текст снова пойдёт.")
        s?.status == LiveStatus.FINISHING || s?.status == LiveStatus.DONE -> Empty("finishing", Ic.Records, "Завершаю распознавание…", "Сейчас откроется обработка записи.")
        s?.modelsReady == false -> Empty("loading", Ic.Cpu, "Готовлю распознавание…", "Звук уже записывается. Текст появится через несколько секунд.")
        else -> Empty("speak", Ic.Mic, "Говорите — текст появится здесь", "Фраза появляется сразу, а после короткой паузы в речи подтверждается.")
    }
}

/**
 * Confirmed lines + the phrase still being spoken. Keys are stable (a line's start time), so a new
 * line only composes itself; the partial has its own item that stays in place while its text grows.
 * Follows the newest text while the user is at the bottom; scrolling up stops that until they return.
 */
@Composable
private fun LiveTranscript(lines: List<LiveLine>, partial: LivePartial?, state: LazyListState, side: androidx.compose.ui.unit.Dp, empty: Empty) {
    val scope = rememberCoroutineScope()
    val reduced = Theme.reducedMotion
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> stick = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (!state.canScrollForward) stick = true
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.canScrollForward }.distinctUntilChanged().collect { if (!it && !state.isScrollInProgress) stick = true }
    }
    LaunchedEffect(lines.size, partial?.text) {
        if (stick) state.scrollToEnd(reduced)
    }
    val hasText = lines.isNotEmpty() || partial != null
    val shownEmpty = remember { HashMap<String, Empty>() }
    shownEmpty[empty.key] = empty

    Box(Modifier.fillMaxSize()) {
        Fade(hasText, Modifier.fillMaxSize()) { text ->
            if (!text) {
                Fade(empty.key, Modifier.fillMaxSize()) { k ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyStateIcon(shownEmpty[k] ?: empty)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = side, end = side, top = Space.s, bottom = Space.xl),
                ) {
                    itemsIndexed(lines, key = { _, l -> "l${l.startMs}" }, contentType = { _, _ -> "line" }) { i, l ->
                        val prev = lines.getOrNull(i - 1)
                        LiveLineRow(l, showVoice = prev == null || prev.speaker != l.speaker || l.startMs - prev.endMs > TURN_GAP_MS, muted = false)
                    }
                    item(key = "partial", contentType = "partial") { PartialRow(partial) }
                }
            }
        }
        // lines slide under the action bar through a short fade instead of a hard cut
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Size.fade)
                .background(Brush.verticalGradient(listOf(Theme.colors.bg.copy(alpha = 0f), Theme.colors.bg))),
        )
        val follow by remember { derivedStateOf { !stick && state.canScrollForward } }
        AnimatedVisibility(
            visible = follow && hasText,
            enter = fadeIn(Motion.spec(Theme.reducedMotion)), exit = fadeOut(Motion.spec(Theme.reducedMotion, Motion.FAST)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.l),
        ) {
            PrimaryButton("К новым репликам", {
                stick = true
                scope.launch { state.scrollToEnd(reduced) }
            }, icon = Ic.Down)
        }
    }
}

@Composable
private fun EmptyStateIcon(e: Empty) {
    val c = Theme.colors
    Column(Modifier.padding(horizontal = Space.xl, vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyIcon(e.icon, danger = e.danger)
        Spacer(Modifier.height(Space.l))
        Text(e.title, style = Type.heading, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(e.text, style = Type.body, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
    }
}

/** Brings the bottom of the last item into view: a short animated nudge when it is near, a jump otherwise. */
private suspend fun LazyListState.scrollToEnd(reduced: Boolean) {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return
    val last = info.visibleItemsInfo.lastOrNull()
    if (last != null && last.index == total - 1) {
        val delta = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (delta > 0) { if (reduced) scrollBy(delta.toFloat()) else animateScrollBy(delta.toFloat()) }
    } else {
        scrollToItem(total - 1, Int.MAX_VALUE / 4)
    }
}

/**
 * One live line, also used for the draft on the processing screen. The voice chip is dashed and its
 * dot hollow: a provisional hint, unlike the solid speaker labels of the final transcript.
 */
@Composable
internal fun LiveLineRow(line: LiveLine, showVoice: Boolean, muted: Boolean, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Column(modifier.fillMaxWidth().padding(top = if (showVoice) Space.m else Space.xs)) {
        if (showVoice) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VoiceChip(line.speaker)
                Spacer(Modifier.width(Space.s))
                Text(fmtTime(line.startMs), style = Type.speakerTime, color = c.muted, maxLines = 1)
                if (line.lang == "en") {
                    Spacer(Modifier.width(Space.s))
                    Text("EN", style = Type.labelNum, color = c.muted)
                }
            }
            Spacer(Modifier.height(Space.xs))
        }
        Text(line.text, style = Type.body, color = if (muted) c.muted else c.text)
    }
}

/** «Голос N» as a dashed outline pill: visibly not a final speaker. */
@Composable
internal fun VoiceChip(speaker: Int?) {
    val c = Theme.colors
    val tone = if (speaker == null) c.muted else c.speakers[Math.floorMod(speaker - 1, c.speakers.size)]
    val dash = c.borderStrong
    Row(
        Modifier.height(Size.chip)
            .drawBehind {
                val r = size.height / 2f
                drawRoundRect(
                    dash, cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = Size.stroke.toPx() * 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(horizontal = Space.m)
            .semantics { contentDescription = "${liveVoiceLabel(speaker)}, предварительно" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Size.dot).border(Size.stroke * 1.5f, tone, Radius.pill))
        Spacer(Modifier.width(Space.s))
        Text(liveVoiceLabel(speaker), style = Type.label, color = c.text, maxLines = 1)
    }
}

/** The phrase still being spoken: muted text with a blinking caret. Empty (zero height) between phrases. */
@Composable
private fun PartialRow(partial: LivePartial?) {
    val c = Theme.colors
    if (partial == null) {
        Spacer(Modifier.height(0.dp))
        return
    }
    val blink = if (Theme.reducedMotion) 1f else {
        val t = rememberInfiniteTransition(label = "caret")
        t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caretAlpha").value
    }
    val text = remember(partial.text) {
        buildAnnotatedString {
            append(partial.text)
            append(" ")
            appendInlineContent("caret", "…")
        }
    }
    val caret = mapOf(
        "caret" to InlineTextContent(Placeholder(2.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize().alpha(blink).clip(Radius.pill).background(c.muted))
        },
    )
    Column(Modifier.fillMaxWidth().padding(top = Space.m).semantics { contentDescription = "Распознаётся: ${partial.text}" }) {
        Text(text, style = Type.body, color = c.muted, inlineContent = caret)
    }
}

// ---------------------------------------------------------------- action bar

@Composable
private fun ActionBar(
    phase: Phase,
    state: LiveState?,
    perm: Perm,
    saved: Boolean,
    side: androidx.compose.ui.unit.Dp,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSaved: () -> Unit,
    onClose: () -> Unit,
) {
    // one 48dp row in every phase; only its contents cross-fade
    Fade(
        phase,
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = side, end = side, top = Space.s, bottom = Space.l).height(Size.touch),
    ) { p ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            when (p) {
                Phase.IDLE -> PrimaryButton("Начать запись", onStart, Modifier.fillMaxWidth(), Ic.Mic)
                Phase.PERMISSION -> if (perm == Perm.DENIED_FOREVER) {
                    PrimaryButton("Открыть настройки", onSettings, Modifier.fillMaxWidth(), Ic.Settings)
                } else {
                    PrimaryButton("Разрешить доступ", onStart, Modifier.fillMaxWidth(), Ic.Mic)
                }
                Phase.LIVE -> {
                    val status = state?.status
                    val canControl = status == LiveStatus.RECORDING || status == LiveStatus.PAUSED
                    val paused = status == LiveStatus.PAUSED
                    PauseButton(paused, enabled = canControl, onClick = if (paused) onResume else onPause)
                    val ending = status == LiveStatus.FINISHING || status == LiveStatus.DONE
                    PrimaryButton(
                        if (ending) "Завершаю…" else "Завершить", onStop, Modifier.weight(1f), Ic.Stop,
                        enabled = canControl || status == LiveStatus.STARTING,
                    )
                    OutlineIcon(Ic.Trash, "Удалить запись", onDiscard, enabled = !ending)
                }
                Phase.ERROR -> {
                    PrimaryButton(if (saved) "Открыть запись" else "Начать заново", if (saved) onOpenSaved else onStart, Modifier.weight(1f), if (saved) Ic.Records else Ic.Mic)
                    OutlineIcon(Ic.Close, "Закрыть", onClose)
                }
            }
        }
    }
}

/** Outlined pause / continue pill; the glyph morphs between the two bars and the play triangle. */
@Composable
private fun PauseButton(paused: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    val label = if (paused) "Продолжить запись" else "Пауза"
    Box(
        Modifier.height(Size.touch).width(Size.touch + Space.xl).clip(Radius.pill)
            .border(Size.stroke, c.borderStrong, Radius.pill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(playing = !paused, tint = if (enabled) c.text else c.muted.copy(alpha = 0.5f), modifier = Modifier.size(Size.icon))
    }
}

@Composable
private fun OutlineIcon(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Theme.colors
    Box(
        Modifier.height(Size.touch).width(Size.touch + Space.xl).clip(Radius.pill)
            .border(Size.stroke, c.borderStrong, Radius.pill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(Size.icon), tint = if (enabled) c.text else c.muted.copy(alpha = 0.5f)) }
}

/**
 * Pause bars <-> play triangle on the 24 grid. Each bar is a quad that morphs into one half of the
 * triangle, so the change reads as one shape turning into the other (instant with reduced motion).
 */
@Composable
fun PlayPauseGlyph(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (playing) 0f else 1f, Motion.move(Theme.reducedMotion), label = "playPause")
    Canvas(modifier) {
        val k = size.minDimension / 24f
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        fun quad(pause: FloatArray, play: FloatArray) = Path().apply {
            for (i in 0 until 4) {
                val x = lerp(pause[i * 2], play[i * 2]) * k
                val y = lerp(pause[i * 2 + 1], play[i * 2 + 1]) * k
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val left = quad(floatArrayOf(6.5f, 5f, 10.5f, 5f, 10.5f, 19f, 6.5f, 19f), floatArrayOf(7f, 4.5f, 13.25f, 8.25f, 13.25f, 15.75f, 7f, 19.5f))
        val right = quad(floatArrayOf(13.5f, 5f, 17.5f, 5f, 17.5f, 19f, 13.5f, 19f), floatArrayOf(13.25f, 8.25f, 19.5f, 12f, 19.5f, 12f, 13.25f, 15.75f))
        listOf(left, right).forEach { p ->
            drawPath(p, tint, style = Fill)
            // a soft round outline rounds the corners like the other glyphs
            drawPath(p, tint, style = Stroke(width = 1.5f * k, join = StrokeJoin.Round))
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

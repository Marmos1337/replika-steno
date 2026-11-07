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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** Level history kept for the meter; the meter draws as many of the newest values as fit. */
private const val LEVEL_HISTORY = 96
private const val LEVEL_TICK_MS = 80L

/** The info line under the header is always two lines of text tall, so the list never moves when it changes. */
private val INFO_HEIGHT = Type.label.lineHeight.value.dp * 2 + Space.m * 2
private val METER_HEIGHT = 40.dp
private val METER_HEIGHT_COMPACT = 24.dp

/** Below this height (landscape tablets, wide windows) the header folds into one row. */
private val COMPACT_HEADER_MAX_HEIGHT = 700.dp
/** The one-row header needs room for the title column, a useful meter and the timer. */
private val COMPACT_HEADER_MIN_WIDTH = 560.dp
private val COMPACT_TITLE_WIDTH = 240.dp

/** Empty states hang from the same line in every state, a bit above the middle of the free space. */
private const val EMPTY_TOP = 0.28f

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
    val notificationOff = "Уведомления выключены — запись всё равно идёт."
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
    val lines = s?.lines.orEmpty()
    val partial = s?.partial
    val hasText = lines.isNotEmpty() || partial != null

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        val compact = maxHeight < COMPACT_HEADER_MAX_HEIGHT && maxWidth >= COMPACT_HEADER_MIN_WIDTH
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val reduced = Theme.reducedMotion
        /** Follow the newest text; false once the user scrolls up, until they come back down. */
        val stick = remember { mutableStateOf(true) }
        val follow by remember { derivedStateOf { !stick.value && listState.canScrollForward } }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(leading = { IconBtn(Ic.Back, "К записям", { vm.navigate(Screen.Library) }) }, side = side, title = "Живая запись")
            Column(Modifier.padding(horizontal = side)) {
                LiveHeader(s, headerText(s, phase, perm, enginesReady, saved), compact)
                Spacer(Modifier.height(Space.s))
                InfoLine(info(s, phase, perm, localNote))
                Spacer(Modifier.height(Space.s))
            }
            // rows fade out under the info card and above the action bar instead of being cut flat
            Box(
                Modifier.weight(1f).fillMaxWidth().edgeFade(
                    c.bg,
                    top = fadeWhen(hasText && listState.canScrollBackward),
                    bottom = fadeWhen(hasText && listState.canScrollForward),
                ),
            ) {
                LiveTranscript(
                    lines = lines,
                    partial = partial,
                    state = listState,
                    stick = stick,
                    side = side,
                    empty = emptyContent(s, phase, perm, saved),
                )
            }
            BottomBar(
                side = side,
                top = {
                    FloatingPill(follow && hasText, "К новым репликам") {
                        stick.value = true
                        scope.launch { listState.scrollToEnd(reduced) }
                    }
                },
            ) {
                ActionBar(
                    phase = phase,
                    state = s,
                    perm = perm,
                    saved = saved,
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
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Удалить запись?",
            text = "Звук и текст этой записи не сохранятся. Отменить это нельзя.",
            confirm = "Удалить", danger = true,
            onConfirm = {
                // the toast comes from the view model once the take is really gone
                vm.discardLive()
                vm.navigate(Screen.Library)
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

/** [onDevice]: the line is about privacy (audio stays on the phone) and gets the shield; others get [icon]. */
private data class HeaderText(val title: String, val subtitle: String, val onDevice: Boolean, val icon: ImageVector = Ic.Info) {
    val subtitleIcon: ImageVector get() = if (onDevice) Ic.Shield else icon
}

/** Header, info line and empty state each say something different: no message is shown twice. */
private fun headerText(s: LiveState?, phase: Phase, perm: Perm, enginesReady: Boolean, saved: Boolean): HeaderText = when (phase) {
    Phase.IDLE -> if (enginesReady) {
        HeaderText("Готово к записи", "Распознавание готово · всё на устройстве", onDevice = true)
    } else {
        HeaderText("Готово к записи", "Готовлю распознавание…", onDevice = false, icon = Ic.Cpu)
    }
    // what to do is in the empty state below; the header only says where the sound goes
    Phase.PERMISSION -> HeaderText("Нет доступа к микрофону", "Звук пишется только в телефон", onDevice = true)
    Phase.ERROR -> if (saved) {
        HeaderText("Запись остановлена", "Записанное сохранено на телефоне", onDevice = true)
    } else {
        HeaderText("Запись остановлена", "Ничего не сохранено", onDevice = false)
    }
    Phase.LIVE -> when (s?.status) {
        LiveStatus.STARTING -> HeaderText("Включаю микрофон", "Звук пишется только в память телефона", onDevice = true)
        LiveStatus.PAUSED -> HeaderText("Пауза", "Микрофон выключен", onDevice = false, icon = Ic.MicOff)
        LiveStatus.FINISHING, LiveStatus.DONE -> HeaderText("Завершаю…", "Сохраняю запись", onDevice = false, icon = Ic.Records)
        else -> HeaderText(
            "Идёт запись",
            if (s?.modelsReady == true) "Распознаётся на устройстве" else "Звук пишется в память телефона",
            onDevice = true,
        )
    }
}

/**
 * Rec dot + state + timer, the level meter and one status line; the same size in every state.
 * [compact]: one row (dot, title over subtitle, meter, timer) for screens short on height.
 */
@Composable
private fun LiveHeader(s: LiveState?, text: HeaderText, compact: Boolean) {
    val c = Theme.colors
    val status = s?.status
    val timer = @Composable {
        Text(
            fmtTime(s?.elapsedMs ?: 0), style = Type.displayNum,
            color = if (status == LiveStatus.RECORDING) c.text else c.muted, maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "Длительность ${fmtTime(s?.elapsedMs ?: 0)}" },
        )
    }
    val title = @Composable { m: Modifier ->
        Fade(text.title, m) { t ->
            Text(t, style = Type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    // icon and words change together, so the icon is part of the cross-fade
    val subtitle = @Composable { m: Modifier ->
        Fade(text.subtitle to text.subtitleIcon, m) { (t, icon) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(Size.iconSmall), tint = c.muted)
                Spacer(Modifier.width(Space.s))
                Text(t, style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RecDot(status)
                Spacer(Modifier.width(Space.s))
                // fixed width: the meter beside it never stretches or shrinks when the words change
                Column(Modifier.width(COMPACT_TITLE_WIDTH)) {
                    title(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.xs))
                    subtitle(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(Space.l))
                LevelMeter(level = s?.level ?: 0f, active = status == LiveStatus.RECORDING, modifier = Modifier.weight(1f).height(METER_HEIGHT_COMPACT))
                Spacer(Modifier.width(Space.l))
                timer()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RecDot(status)
                Spacer(Modifier.width(Space.s))
                title(Modifier.weight(1f))
                Spacer(Modifier.width(Space.m))
                timer()
            }
            Spacer(Modifier.height(Space.m))
            LevelMeter(level = s?.level ?: 0f, active = status == LiveStatus.RECORDING, modifier = Modifier.fillMaxWidth().height(METER_HEIGHT))
            Spacer(Modifier.height(Space.m))
            subtitle(Modifier.fillMaxWidth())
        }
    }
}

/** Red dot: pulses while recording (steady with reduced motion), grey when idle or paused. */
@Composable
fun RecDot(status: LiveStatus?, size: Dp = Space.m) {
    val c = Theme.colors
    val recording = status == LiveStatus.RECORDING || status == LiveStatus.STARTING
    val alpha = if (recording && !Theme.reducedMotion) {
        val t = rememberInfiniteTransition(label = "rec")
        // to half, not lower: a deeper pulse washes the red out to pink (light) or brown (dark)
        t.animateFloat(1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "recDot").value
    } else 1f
    val color = when (status) {
        LiveStatus.RECORDING, LiveStatus.STARTING, LiveStatus.ERROR -> c.danger
        else -> c.muted
    }
    Box(Modifier.size(size).clip(Radius.pill).background(color.copy(alpha = alpha)))
}

/**
 * Scrolling bar meter of the input level over a flat baseline. Silence (and every state that does not
 * record) is just the line; bars rise from it while there is sound. Ticks only while recording; on
 * pause the bars settle into the line and the loop stops, so nothing animates while idle.
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
        val mid = size.height / 2f
        val stroke = Size.stroke.toPx() * 1.5f
        drawLine(c.borderStrong, Offset(0f, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
        val n = ((size.width + gap) / (w + gap)).toInt().coerceIn(1, LEVEL_HISTORY)
        val x0 = size.width - (n * (w + gap) - gap)
        for (i in 0 until n) {
            val v = history[LEVEL_HISTORY - n + i]
            if (v <= 0f) continue
            val h = (size.height * v).coerceAtLeast(w * 2)
            // older bars fade out to the left
            val a = 0.25f + 0.75f * (i + 1f) / n
            drawRoundRect(
                c.text.copy(alpha = a),
                Offset(x0 + i * (w + gap), mid - h / 2f), GSize(w, h), CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------- info line

private enum class InfoKind { CALM, WARN, ERROR }

/** [legend] draws the provisional-voice mark (hollow dot) instead of an icon. */
private data class Info(val kind: InfoKind, val icon: ImageVector, val text: String, val legend: Boolean = false)

private fun info(s: LiveState?, phase: Phase, perm: Perm, localNote: String?): Info = when {
    phase == Phase.ERROR -> Info(InfoKind.ERROR, Ic.Alert, s?.error ?: "Запись прервалась.")
    phase == Phase.PERMISSION -> Info(
        InfoKind.CALM, Ic.Info,
        // the privacy line is already in the header subtitle; here only what else the user can do
        if (perm == Perm.DENIED_FOREVER) "Без микрофона живой записи не будет. Импорт файлов работает."
        else "После остановки — полная обработка: участники и итоги.",
    )
    s?.micSilenced == true -> Info(InfoKind.WARN, Ic.MicOff, "Микрофон не слышит звук — возможно, он занят другим приложением")
    // a notice explains why live text stopped or is missing; it goes before the lag warning
    s?.notice != null -> Info(InfoKind.CALM, Ic.Info, s.notice)
    s?.status == LiveStatus.FINISHING && !s.modelsReady -> Info(
        InfoKind.CALM, Ic.Cpu, "Жду загрузки моделей, чтобы распознать последние фразы",
    )
    s?.lagging == true && s.status == LiveStatus.RECORDING -> Info(
        InfoKind.WARN, Ic.Alert,
        "Текст отстаёт на ${(s.backlogMs + 500) / 1000} с — звук пишется, текст догонит",
    )
    localNote != null -> Info(InfoKind.CALM, Ic.Info, localNote)
    s != null -> Info(InfoKind.CALM, Ic.Question, "Голоса примерные: участники уточнятся после завершения", legend = true)
    else -> Info(InfoKind.CALM, Ic.Info, "После остановки — полная обработка: участники и итоги.")
}

/**
 * One fixed slot under the header: the voices legend by default, warnings and notices in its place.
 * Always exactly two lines tall (longer text ends in an ellipsis), so the list below never moves.
 */
@Composable
private fun InfoLine(info: Info) {
    val c = Theme.colors
    // The key is the kind + icon, so a lag counter ticking up changes the text without a cross-fade.
    // Each key keeps the last text it showed, so the outgoing layer fades out with its own words.
    val key = info.kind to info.icon
    val shown = remember { HashMap<Pair<InfoKind, ImageVector>, Info>() }
    shown[key] = info
    Fade(key, Modifier.fillMaxWidth().height(INFO_HEIGHT)) { k ->
        val info = shown[k] ?: info
        val warn = info.kind != InfoKind.CALM
        Row(
            Modifier.fillMaxWidth().height(INFO_HEIGHT).clip(Radius.r14)
                .then(if (warn) Modifier.background(c.raised) else Modifier.border(Size.stroke, c.border, Radius.r14))
                .padding(horizontal = Space.m, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.legend) {
                // a 12dp ring keeps the visual weight of the 18dp icons in the same slot
                Box(Modifier.size(Size.iconSmall), contentAlignment = Alignment.Center) { VoiceDot(null, Space.m) }
            } else {
                Icon(info.icon, null, Modifier.size(Size.iconSmall), tint = if (info.kind == InfoKind.ERROR) c.danger else if (warn) c.text else c.muted)
            }
            Spacer(Modifier.width(Space.s))
            Text(
                info.text, style = Type.label, color = if (warn) c.text else c.muted, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- transcript

private data class Empty(val key: String, val icon: ImageVector, val title: String, val text: String, val danger: Boolean = false)

private fun emptyContent(s: LiveState?, phase: Phase, perm: Perm, saved: Boolean): Empty = when (phase) {
    Phase.PERMISSION -> if (perm == Perm.DENIED_FOREVER) {
        Empty("perm2", Ic.Settings, "Включите доступ в настройках", "«Разрешения» → «Микрофон» → «Разрешить». Потом вернитесь сюда.")
    } else {
        Empty("perm", Ic.MicOff, "Разрешите запись звука", "Нажмите «Разрешить доступ» и подтвердите запрос телефона.")
    }
    // the reason itself is in the info line above; here only what to do next
    Phase.ERROR -> if (saved) {
        Empty("errorSaved", Ic.Records, "Откройте запись", "Она пройдёт полную обработку: участники, окончательный текст и итоги.")
    } else {
        Empty("error", Ic.Alert, "Попробуйте ещё раз", "Нажмите «Начать заново», когда будете готовы говорить.", danger = true)
    }
    Phase.IDLE -> Empty("idle", Ic.Mic, "Текст появится здесь", "Нажмите «Начать запись» и говорите — фразы будут появляться по ходу разговора.")
    Phase.LIVE -> when {
        s?.status == LiveStatus.PAUSED -> Empty("paused", Ic.Pause, "Запись на паузе", "Нажмите «Продолжить», и текст снова пойдёт.")
        s?.status == LiveStatus.FINISHING || s?.status == LiveStatus.DONE ->
            Empty("finishing", Ic.Records, "Скоро откроется обработка", "Записанный звук пройдёт полную обработку: участники, окончательный текст и итоги.")
        s?.modelsReady == false -> Empty("loading", Ic.Cpu, "Готовлю распознавание…", "Текст появится через несколько секунд — ничего из сказанного не потеряется.")
        else -> Empty("speak", Ic.Mic, "Говорите — текст появится здесь", "Фраза появляется сразу, а после короткой паузы в речи подтверждается.")
    }
}

/**
 * Confirmed lines + the phrase still being spoken. Keys are stable (a line's start time), so a new
 * line only composes itself; the partial has its own item of the same shape as a line, so promoting
 * it to a confirmed line does not move anything. Follows the newest text while the user is at the
 * bottom ([stick]); scrolling up stops that until they return.
 */
@Composable
private fun LiveTranscript(
    lines: List<LiveLine>,
    partial: LivePartial?,
    state: LazyListState,
    stick: MutableState<Boolean>,
    side: Dp,
    empty: Empty,
) {
    val reduced = Theme.reducedMotion
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> stick.value = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (!state.canScrollForward) stick.value = true
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.canScrollForward }.distinctUntilChanged().collect { if (!it && !state.isScrollInProgress) stick.value = true }
    }
    LaunchedEffect(lines.size, partial?.text) {
        if (stick.value) state.scrollToEnd(reduced)
    }
    val hasText = lines.isNotEmpty() || partial != null
    val shownEmpty = remember { HashMap<String, Empty>() }
    shownEmpty[empty.key] = empty

    Fade(hasText, Modifier.fillMaxSize()) { text ->
        if (!text) {
            Fade(empty.key, Modifier.fillMaxSize()) { k ->
                // anchored at the top of a fixed region: a title or text of another length grows the
                // block downwards, so the tile never moves between idle, permission and error
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = maxHeight * EMPTY_TOP),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyStateIcon(shownEmpty[k] ?: empty)
                    }
                }
            }
        } else {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = Space.xs, bottom = Space.s),
            ) {
                items(lines, key = { "l${it.startMs}" }, contentType = { "line" }) { LiveLineRow(it) }
                item(key = "partial", contentType = "partial") { PartialRow(partial) }
            }
        }
    }
}

@Composable
private fun EmptyStateIcon(e: Empty) {
    val c = Theme.colors
    Column(Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyIcon(e.icon, danger = e.danger)
        Spacer(Modifier.height(Space.l))
        Text(e.title, style = Type.heading, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(e.text, style = Type.body, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
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
 * Frame of a live row, the same geometry as an utterance of the transcript: a 48dp header line with
 * the speaker dot, name and time, the text under it and a [Space.m] gap below. [mark] is the dot.
 * The name sits in a slot as wide as «Голос 0» / «Голос …», so the time keeps its place when the phrase
 * being spoken («Голос …») becomes a confirmed line. [trailing] is a muted note after the time.
 */
@Composable
private fun LiveRowFrame(
    mark: @Composable () -> Unit,
    label: String,
    startMs: Long,
    lang: String,
    provisional: Boolean,
    description: String,
    trailing: String? = null,
    text: @Composable () -> Unit,
) {
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = Space.m).semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.heightIn(min = Size.touch).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(Space.s))
            Box(contentAlignment = Alignment.CenterStart) {
                NAME_TEMPLATES.forEach { Text(it, style = Type.speaker, maxLines = 1, modifier = Modifier.alpha(0f)) }
                Text(label, style = Type.speaker, color = if (provisional) c.muted else c.text, maxLines = 1)
            }
            Spacer(Modifier.width(Space.m))
            Text(fmtTime(startMs), style = Type.speakerTime, color = c.muted, maxLines = 1)
            if (lang == "en") {
                Spacer(Modifier.width(Space.s))
                Text("EN", style = Type.labelNum, color = c.muted)
            }
            if (trailing != null) {
                Spacer(Modifier.width(Space.s))
                Text(trailing, style = Type.label, color = c.muted, maxLines = 1)
            }
        }
        text()
    }
}

/** Width templates of the name slot of a live row: a one-digit voice and the phrase still being spoken. */
private val NAME_TEMPLATES = listOf("Голос 0", "Голос …")

/**
 * One confirmed live line, also used for the draft on the processing screen. The voice dot is hollow:
 * a provisional hint, unlike the solid speaker dots of the final transcript.
 */
@Composable
internal fun LiveLineRow(line: LiveLine) {
    val c = Theme.colors
    LiveRowFrame(
        mark = { VoiceDot(line.speaker) },
        label = liveVoiceLabel(line.speaker),
        startMs = line.startMs,
        lang = line.lang,
        provisional = false,
        description = "${liveVoiceLabel(line.speaker)}, предварительно, ${fmtTime(line.startMs)}: ${line.text}",
    ) { Text(line.text, style = Type.body, color = c.text) }
}

/** Hollow dot in the voice's tone (muted when the voice is unknown): the mark of a provisional speaker. */
@Composable
internal fun VoiceDot(speaker: Int?, size: Dp = Size.dot) {
    val c = Theme.colors
    val tone = if (speaker == null) c.muted else c.speakers[Math.floorMod(speaker - 1, c.speakers.size)]
    Box(Modifier.size(size).border(Size.stroke * 1.5f, tone, Radius.pill))
}

/**
 * The phrase still being spoken: the row of a line with a muted «Голос …», «распознаётся» after the
 * time, muted text and a blinking caret. Zero height between phrases.
 */
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
    LiveRowFrame(
        mark = { VoiceDot(null) },
        label = "Голос …",
        startMs = partial.startMs,
        lang = partial.lang,
        provisional = true,
        description = "Распознаётся: ${partial.text}",
        trailing = "распознаётся",
    ) { Text(text, style = Type.body, color = c.muted, inlineContent = caret) }
}

// ---------------------------------------------------------------- action bar

@Composable
private fun ActionBar(
    phase: Phase,
    state: LiveState?,
    perm: Perm,
    saved: Boolean,
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // on the narrowest phones the side pills shrink to 48dp, so «Завершить» keeps its full label
        val sideW = if (maxWidth < NARROW_BAR) Size.touch else Size.touch + Space.xl
        Fade(phase, Modifier.fillMaxWidth().height(Size.touch)) { p ->
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
                        PauseButton(paused, enabled = canControl, width = sideW, onClick = if (paused) onResume else onPause)
                        val ending = status == LiveStatus.FINISHING || status == LiveStatus.DONE
                        PrimaryButton(
                            if (ending) "Завершаю…" else "Завершить", onStop, Modifier.weight(1f), Ic.Stop,
                            enabled = canControl || status == LiveStatus.STARTING,
                        )
                        OutlineIcon(Ic.Trash, "Удалить запись", onDiscard, width = sideW, enabled = !ending)
                    }
                    Phase.ERROR -> {
                        PrimaryButton(if (saved) "Открыть запись" else "Начать заново", if (saved) onOpenSaved else onStart, Modifier.weight(1f), if (saved) Ic.Records else Ic.Mic)
                        OutlineIcon(Ic.Close, "Закрыть", onClose, width = sideW)
                    }
                }
            }
        }
    }
}

/** Content width under which the action bar's side pills shrink to a square touch target. */
private val NARROW_BAR = 300.dp

/** Outlined pause / continue pill; the glyph morphs between the two bars and the play triangle. */
@Composable
private fun PauseButton(paused: Boolean, enabled: Boolean, width: Dp, onClick: () -> Unit) {
    val c = Theme.colors
    val label = if (paused) "Продолжить запись" else "Пауза"
    Box(
        Modifier.height(Size.touch).width(width).clip(Radius.pill)
            .border(Size.stroke, c.borderStrong, Radius.pill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(playing = !paused, tint = if (enabled) c.text else c.muted.copy(alpha = 0.5f), modifier = Modifier.size(Size.icon))
    }
}

@Composable
private fun OutlineIcon(icon: ImageVector, description: String, onClick: () -> Unit, width: Dp, enabled: Boolean = true) {
    val c = Theme.colors
    Box(
        Modifier.height(Size.touch).width(width).clip(Radius.pill)
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

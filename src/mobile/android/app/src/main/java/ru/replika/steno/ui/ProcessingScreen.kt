package ru.replika.steno.ui

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.replika.steno.ActiveJob
import ru.replika.steno.core.LIVE_SOURCE
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.Stage
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

private enum class View { RUNNING, QUEUED, FAILED }

@Composable
fun ProcessingScreen(vm: ReplikaViewModel, id: String) {
    val c = Theme.colors
    val items by vm.recordings.collectAsState()
    val loaded by vm.graph.store.loaded.collectAsState()
    val job by vm.job.collectAsState()
    val started by vm.jobStartedAt.collectAsState()
    val live by vm.live.collectAsState()
    val rec = items.firstOrNull { it.id == id }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    LaunchedEffect(rec?.state, loaded) {
        when {
            loaded && rec == null -> vm.navigate(Screen.Library)
            rec?.state == RecordingState.READY -> vm.navigate(Screen.Transcript(id))
            rec?.state == RecordingState.RECORDING && vm.isLiveRecording(id) -> vm.navigate(Screen.Live)
        }
    }
    if (rec == null) return

    val mine = job?.takeIf { it.recordingId == id }
    val other = job?.takeIf { it.recordingId != id }
    // a live take holds the queue: anything new waits until it is saved
    val liveHolds = rec.state == RecordingState.NEW && mine == null && live?.status?.terminal == false
    val view = when {
        rec.state == RecordingState.FAILED -> View.FAILED
        rec.state == RecordingState.NEW && (other != null || liveHolds) -> View.QUEUED
        else -> View.RUNNING
    }
    val failure = failureKind(rec.error)
    val toLibrary = { vm.navigate(Screen.Library) }
    val scroll = rememberLazyListState()
    // the live text is only a draft: shown until the full pipeline's utterances exist, never exported
    val draft = if (rec.utterances.isEmpty()) rec.liveDraft else emptyList()

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(leading = { IconBtn(Ic.Back, "К записям", toLibrary) }, side = side, divider = scroll.canScrollBackward)
            // Fixed-size area between the top bar and the buttons; states cross-fade inside it.
            // A lazy list, so a long live draft (thousands of lines) below the progress stays cheap.
            LazyColumn(
                // under the top bar a hairline marks the edge (as on About); above the action bar rows fade out
                Modifier.weight(1f).fillMaxWidth().edgeFade(c.bg, top = 0f, bottom = fadeWhen(scroll.canScrollForward)),
                state = scroll,
                contentPadding = PaddingValues(start = side, end = side, bottom = Space.l),
            ) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth()) {
                        Text(rec.title, style = Type.display, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(Space.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.Shield, null, Modifier.size(Size.iconSmall), tint = c.muted)
                            Spacer(Modifier.width(Space.s))
                            Fade(view == View.FAILED, Modifier.weight(1f)) { failed ->
                                Text(
                                    if (failed) "Всё обрабатывается на устройстве" else "Обработка идёт на устройстве, без интернета",
                                    style = Type.label, color = c.muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.xl))
                        Fade(view, Modifier.fillMaxWidth()) { v ->
                            Column(Modifier.fillMaxWidth()) {
                                when (v) {
                                    View.FAILED -> FailedView(rec.error, failure)
                                    View.QUEUED -> QueuedView(items.firstOrNull { it.id == other?.recordingId }?.title, liveHolds && other == null)
                                    View.RUNNING -> RunningView(mine, started?.takeIf { it.first == id }?.second)
                                }
                            }
                        }
                    }
                }
                if (draft.isNotEmpty()) {
                    item(key = "draftTitle") { DraftTitle() }
                    items(draft, key = { "d${it.startMs}" }) { LiveLineRow(it) }
                }
            }
            // One 48dp row of actions in every state (as on the live screen), only its contents cross-fade.
            BottomBar(side = side) {
                Fade(view, Modifier.fillMaxWidth().height(Size.touch)) { v ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        when (v) {
                            View.RUNNING -> SecondaryButton("Отменить", vm::cancelJob, Modifier.fillMaxWidth(), Ic.Close, enabled = mine != null)
                            View.QUEUED -> SecondaryButton("К записям", toLibrary, Modifier.fillMaxWidth())
                            View.FAILED -> {
                                val m = Modifier.weight(1f)
                                when (failure) {
                                    FailureKind.STOPPED -> PrimaryButton("Обработать снова", { vm.reprocess(id) }, m, Ic.Refresh)
                                    FailureKind.OTHER -> PrimaryButton("Попробовать снова", { vm.reprocess(id) }, m, Ic.Refresh)
                                    // a live take has no file to swap: offer a new take instead
                                    FailureKind.NO_SPEECH, FailureKind.CORRUPT -> if (rec.sourceUri.startsWith(LIVE_SOURCE)) {
                                        PrimaryButton("Новая живая запись", { vm.navigate(Screen.Live) }, m, Ic.Mic)
                                    } else {
                                        PrimaryButton("Выбрать другой файл", { picker.launch(AUDIO_TYPES) }, m, Ic.File)
                                    }
                                }
                                OutlineIconButton(Ic.Close, "К записям", toLibrary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftTitle() {
    val c = Theme.colors
    // a section gap above, so the title groups with the rows below it (they bring their own 48dp header line)
    Column(Modifier.fillMaxWidth().padding(top = Space.xxl)) {
        SectionTitle("Черновик живой записи")
        Spacer(Modifier.height(Space.xs))
        Text(
            "Окончательный текст и участники появятся после обработки. В экспорт черновик не попадает.",
            style = Type.label, color = c.muted,
        )
    }
}

@Composable
private fun RunningView(job: ActiveJob?, startedAt: Long?) {
    val c = Theme.colors
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val overall = job?.progress?.let(::transcriptProgress) ?: 0f
    val stage = job?.progress?.stage
    val note = job?.note
    val preparing = note != null && stage == Stage.DECODE

    AppCard(Modifier.fillMaxWidth()) {
        Text("${(overall * 100).toInt()}%", style = Type.displayNum, color = c.text)
        Spacer(Modifier.height(Space.m))
        ProgressLine(overall)
        Spacer(Modifier.height(Space.m))
        // the stage itself is named in the list below; the caption says how long it has been going
        Text(
            note ?: if (startedAt != null) "прошло ${fmtTime(now - startedAt)}" else "Запускаю…",
            style = Type.labelNum, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(Space.m))
    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = Space.l, vertical = Space.s)) {
        FOREGROUND_STAGES.forEach { s ->
            val state = when {
                stage == null -> 0
                s.ordinal < stage.ordinal -> 2
                s == stage -> 1
                else -> 0
            }
            StageRow(s, state, if (s == stage) job?.progress?.stageFraction ?: 0f else 0f, waitingForModels = preparing && s == stage)
        }
    }
    Spacer(Modifier.height(Space.m))
    // One note slot sized for the longer text, so nothing below moves when the first-run note shows.
    Box(Modifier.fillMaxWidth()) {
        val firstRun by animateFloatAsState(if (preparing) 1f else 0f, Motion.spec(Theme.reducedMotion), label = "firstRun")
        Text(
            "Расшифровка откроется сразу после распознавания. Оформление текста и итоги встречи доделаются в фоне.",
            style = Type.label, color = c.muted, modifier = Modifier.alpha(1f - firstRun),
        )
        Text(
            "Первый запуск: языковая модель (около 550 МБ) копируется из приложения. Это займёт пару минут, дальше будет быстрее.",
            style = Type.label, color = c.muted, modifier = Modifier.alpha(firstRun),
        )
    }
}

@Composable
private fun StageRow(stage: Stage, state: Int, fraction: Float, waitingForModels: Boolean) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = Size.touch), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Size.icon), contentAlignment = Alignment.Center) {
            when (state) {
                2 -> Box(Modifier.size(Size.icon).clip(Radius.pill).background(c.text), contentAlignment = Alignment.Center) {
                    Icon(Ic.Check, "Готово", Modifier.size(14.dp), tint = c.onText)
                }
                1 -> {
                    val f by animateFloatAsState(fraction, Motion.spec(Theme.reducedMotion, Motion.SLOW), label = "stage")
                    if (waitingForModels || fraction <= 0f) {
                        CircularProgressIndicator(Modifier.size(Size.icon), color = c.text, trackColor = c.raised, strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { f }, modifier = Modifier.size(Size.icon), color = c.text, trackColor = c.raised, strokeWidth = 2.dp)
                    }
                }
                else -> Box(Modifier.size(Size.icon).border(Size.stroke * 1.5f, c.borderStrong, Radius.pill))
            }
        }
        Spacer(Modifier.width(Space.m))
        Text(
            stage.title, style = if (state == 1) Type.bodyStrong else Type.body,
            color = if (state == 0) c.muted else c.text, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Space.s))
        Text(
            when (state) {
                2 -> "100%"
                1 -> if (waitingForModels) "" else "${(fraction * 100).toInt()}%"
                else -> ""
            },
            style = Type.labelNum, color = c.muted,
        )
    }
}

@Composable
private fun FailedView(error: String?, kind: FailureKind) {
    val c = Theme.colors
    // a stop the user asked for is not an error: calm icon and wording
    val (title, text) = when (kind) {
        FailureKind.STOPPED -> "Обработка остановлена" to "Запись сохранена. Обработку можно запустить снова в любой момент."
        FailureKind.CORRUPT -> "Файл не открывается" to "Возможно, он повреждён или это не аудио."
        FailureKind.NO_SPEECH -> "Речь не найдена" to "Проверьте, что голос в записи слышно."
        FailureKind.OTHER -> "Не получилось обработать" to (error ?: "Не удалось обработать запись.")
    }
    AppCard(Modifier.fillMaxWidth()) {
        EmptyIcon(
            when (kind) {
                FailureKind.STOPPED -> Ic.Close
                FailureKind.NO_SPEECH -> Ic.MicOff
                FailureKind.CORRUPT -> Ic.File
                FailureKind.OTHER -> Ic.Alert
            },
            danger = kind != FailureKind.STOPPED,
        )
        Spacer(Modifier.height(Space.l))
        Text(title, style = Type.heading, color = c.text)
        Spacer(Modifier.height(Space.s))
        Text(text, style = Type.body, color = c.muted)
    }
}

@Composable
private fun QueuedView(otherTitle: String?, afterLive: Boolean) {
    val c = Theme.colors
    AppCard(Modifier.fillMaxWidth()) {
        EmptyIcon(Ic.Records)
        Spacer(Modifier.height(Space.l))
        Text("В очереди", style = Type.heading, color = c.text)
        Spacer(Modifier.height(Space.s))
        Text(
            when {
                otherTitle != null -> "Сейчас обрабатывается «$otherTitle». Эта запись начнётся сразу после неё."
                afterLive -> "Идёт живая запись. Эта запись начнёт обрабатываться, как только та будет сохранена."
                else -> "Запись начнёт обрабатываться, как только освободится движок."
            },
            style = Type.body, color = c.muted,
        )
    }
}

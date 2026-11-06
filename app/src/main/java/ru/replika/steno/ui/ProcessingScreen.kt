package ru.replika.steno.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.Stage
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

private const val STAGE_ROW = 56

@Composable
fun ProcessingScreen(vm: ReplikaViewModel, id: String) {
    val c = Theme.colors
    val items by vm.recordings.collectAsState()
    val loaded by vm.graph.store.loaded.collectAsState()
    val job by vm.job.collectAsState()
    val started by vm.jobStartedAt.collectAsState()
    val rec = items.firstOrNull { it.id == id }

    LaunchedEffect(rec?.state, loaded) {
        when {
            loaded && rec == null -> vm.navigate(Screen.Library)
            rec?.state == RecordingState.READY -> vm.navigate(Screen.Transcript(id))
        }
    }
    if (rec == null) return

    val mine = job?.takeIf { it.recordingId == id }
    val other = job?.takeIf { it.recordingId != id }
    val view = when {
        rec.state == RecordingState.FAILED -> "failed"
        rec.state == RecordingState.NEW && other != null -> "queued"
        else -> "running"
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(leading = { IconBtn(Ic.Back, "К записям", { vm.navigate(Screen.Library) }) })
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = side).navigationBarsPadding().padding(bottom = Space.xl),
            ) {
                Text(rec.title, style = Type.display, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Shield, null, Modifier.size(Size.iconSmall), tint = c.muted)
                    Spacer(Modifier.width(Space.s))
                    Text("Обработка идёт на устройстве, без интернета", style = Type.label, color = c.muted)
                }
                Spacer(Modifier.height(Space.xl))
                Fade(view, Modifier.fillMaxWidth()) { v ->
                    Column(Modifier.fillMaxWidth()) {
                        when (v) {
                            "failed" -> FailedView(rec.error ?: "Не удалось обработать запись.", onRetry = { vm.reprocess(id) }, onBack = { vm.navigate(Screen.Library) })
                            "queued" -> QueuedView(items.firstOrNull { it.id == other?.recordingId }?.title, onBack = { vm.navigate(Screen.Library) })
                            else -> RunningView(mine, started?.takeIf { it.first == id }?.second, onCancel = vm::cancelJob)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningView(job: ActiveJob?, startedAt: Long?, onCancel: () -> Unit) {
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(overall * 100).toInt()}%", style = Type.displayNum, color = c.text, modifier = Modifier.weight(1f))
            Text(if (startedAt != null) fmtTime(now - startedAt) else "00:00", style = Type.labelNum, color = c.muted,
                modifier = Modifier.padding(bottom = Space.xs))
        }
        Spacer(Modifier.height(Space.m))
        ProgressLine(overall)
        Spacer(Modifier.height(Space.m))
        Text(
            note ?: stage?.title ?: "Запускаю…",
            style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    Text(
        "Расшифровка откроется сразу после распознавания. Оформление текста и итоги встречи доделаются в фоне.",
        style = Type.label, color = c.muted, modifier = Modifier.padding(horizontal = Space.xs),
    )
    Spacer(Modifier.height(Space.s))
    // Reserved even when hidden, so the button below never moves.
    Text(
        "Первый запуск: языковая модель (около 550 МБ) копируется из приложения. Это займёт пару минут, дальше будет быстрее.",
        style = Type.label, color = c.muted, minLines = 2,
        modifier = Modifier.alpha(if (preparing) 1f else 0f).padding(horizontal = Space.xs),
    )
    Spacer(Modifier.height(Space.xl))
    SecondaryButton("Отменить", onCancel, Modifier.fillMaxWidth(), Ic.Close, enabled = job != null)
}

@Composable
private fun StageRow(stage: Stage, state: Int, fraction: Float, waitingForModels: Boolean) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = STAGE_ROW.dp), verticalAlignment = Alignment.CenterVertically) {
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
                2 -> "готово"
                1 -> if (waitingForModels) "подготовка" else "${(fraction * 100).toInt()}%"
                else -> ""
            },
            style = Type.labelNum, color = c.muted,
        )
    }
}

@Composable
private fun FailedView(error: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = Theme.colors
    AppCard(Modifier.fillMaxWidth()) {
        RoundIcon(Ic.Alert, danger = true)
        Spacer(Modifier.height(Space.l))
        Text("Не получилось обработать", style = Type.heading, color = c.text)
        Spacer(Modifier.height(Space.s))
        Text(error, style = Type.body, color = c.muted)
    }
    Spacer(Modifier.height(Space.xl))
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        PrimaryButton("Попробовать снова", onRetry, Modifier.fillMaxWidth(), Ic.Refresh)
        SecondaryButton("К записям", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun QueuedView(otherTitle: String?, onBack: () -> Unit) {
    val c = Theme.colors
    AppCard(Modifier.fillMaxWidth()) {
        RoundIcon(Ic.Records)
        Spacer(Modifier.height(Space.l))
        Text("В очереди", style = Type.heading, color = c.text)
        Spacer(Modifier.height(Space.s))
        Text(
            if (otherTitle != null) "Сейчас обрабатывается «$otherTitle». Эта запись начнётся сразу после неё."
            else "Запись начнёт обрабатываться, как только освободится движок.",
            style = Type.body, color = c.muted,
        )
    }
    Spacer(Modifier.height(Space.xl))
    SecondaryButton("К записям", onBack, Modifier.fillMaxWidth())
}

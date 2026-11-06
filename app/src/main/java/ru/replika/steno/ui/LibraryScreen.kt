package ru.replika.steno.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.replika.steno.ActiveJob
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

private val AUDIO_TYPES = arrayOf("audio/*", "video/mp4", "application/ogg")

@Composable
fun LibraryScreen(vm: ReplikaViewModel, wide: Boolean, onMenu: () -> Unit) {
    val c = Theme.colors
    val items by vm.recordings.collectAsState()
    val loaded by vm.graph.store.loaded.collectAsState()
    val job by vm.job.collectAsState()
    val importing by vm.importing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var micOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }
    var confirmRerun by remember { mutableStateOf<Recording?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val pick = { picker.launch(AUDIO_TYPES) }

    // Filtering touches every utterance of every recording, so it runs off the main thread.
    val filtered by produceState(items, items, query) {
        val q = query.trim()
        value = if (q.isEmpty()) items else withContext(Dispatchers.Default) {
            items.filter { r -> r.title.contains(q, true) || r.utterances.any { it.text.contains(q, true) } }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                leading = if (wide) null else ({ IconBtn(Ic.Menu, "Открыть меню", onMenu) }),
            )
            Column(Modifier.padding(horizontal = side)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Записи", style = Type.display, color = c.text, modifier = Modifier.weight(1f))
                    if (items.isNotEmpty()) {
                        Text(plural(items.size, "запись", "записи", "записей"), style = Type.label, color = c.muted,
                            modifier = Modifier.padding(bottom = Space.xs))
                    }
                }
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(Space.l))
                    SearchField(query, { query = it }, "Поиск по названию и тексту", Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(Space.l))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !loaded -> Unit
                    items.isEmpty() -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        EmptyState(
                            title = "Пока нет записей",
                            text = "Выберите запись встречи — Реплика разделит её по говорящим и расшифрует прямо на телефоне.",
                        ) {
                            PrimaryButton(if (importing) "Копирую файл…" else "Импортировать аудио", pick, Modifier.fillMaxWidth(), Ic.File, enabled = !importing)
                            SecondaryButton("Записать с микрофона", { micOpen = true }, Modifier.fillMaxWidth(), Ic.Mic)
                        }
                    }
                    filtered.isEmpty() -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState("Ничего не нашлось", "Нет записей, где встречается «${query.trim()}».", icon = Ic.Search) {
                            SecondaryButton("Сбросить поиск", { query = "" }, Modifier.fillMaxWidth())
                        }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = side, end = side, bottom = Size.bottomBar + Space.xxl + Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        items(filtered, key = { it.id }) { r ->
                            RecordingRow(
                                r, job,
                                onOpen = {
                                    vm.navigate(if (r.state == RecordingState.READY) Screen.Transcript(r.id) else Screen.Processing(r.id))
                                },
                                onDelete = { confirmDelete = r },
                                onRerun = { if (r.state == RecordingState.READY) confirmRerun = r else vm.reprocess(r.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        // Bottom action bar: always reachable while there are recordings. Fades into the page, no seam.
        if (loaded && items.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.35f to c.bg))
                    .navigationBarsPadding()
                    .padding(start = side, end = side, top = Space.xl, bottom = Space.l),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(if (importing) "Копирую файл…" else "Импортировать аудио", pick, Modifier.weight(1f), Ic.File, enabled = !importing)
                    OutlineIconButton(Ic.Mic, "Записать с микрофона", { micOpen = true })
                }
            }
        }
    }

    if (micOpen) MicSheet(vm, onClose = { micOpen = false })
    confirmDelete?.let { r ->
        ConfirmDialog(
            title = "Удалить запись?",
            text = "«${r.title}» и её расшифровка удалятся с устройства. Отменить это нельзя.",
            confirm = "Удалить", danger = true,
            onConfirm = { vm.delete(r.id) }, onDismiss = { confirmDelete = null },
        )
    }
    confirmRerun?.let { r ->
        ConfirmDialog(
            title = "Обработать заново?",
            text = "Расшифровка «${r.title}» будет построена с нуля. Ваши правки текста пропадут, имена участников сохранятся.",
            confirm = "Обработать",
            onConfirm = { vm.reprocess(r.id) }, onDismiss = { confirmRerun = null },
        )
    }
}

@Composable
private fun RecordingRow(
    r: Recording,
    job: ActiveJob?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    var menu by remember { mutableStateOf(false) }
    AppCard(
        modifier.fillMaxWidth(),
        onClick = onOpen,
        onLongClick = { menu = true },
        padding = PaddingValues(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.l),
    ) {
        Row {
            Column(Modifier.weight(1f).padding(top = Space.m)) {
                Text(r.title, style = Type.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.xs))
                val meta = buildList {
                    add(fmtDate(r.createdAtMs))
                    if (r.durationMs > 0) add(fmtTime(r.durationMs))
                }.joinToString(" · ")
                Text(meta, style = Type.labelNum, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconBtn(Ic.More, "Действия с записью", { menu = true }, tint = c.muted)
                AppMenu(
                    menu, { menu = false },
                    listOf(
                        MenuEntry("Обработать заново", Ic.Refresh, onClick = onRerun),
                        MenuEntry("Удалить", Ic.Trash, danger = true, onClick = onDelete),
                    ),
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(Modifier.padding(end = Space.m), verticalAlignment = Alignment.CenterVertically) {
            val mine = job?.takeIf { it.recordingId == r.id }
            when (r.state) {
                RecordingState.READY -> if (r.llmPending) StatusChip("Итоги готовятся", ChipKind.ACTIVE) else StatusChip("Готово")
                RecordingState.PROCESSING -> StatusChip(
                    if (mine != null) "Обработка ${(transcriptProgress(mine.progress) * 100).toInt()}%" else "Обработка",
                    ChipKind.ACTIVE,
                )
                RecordingState.FAILED -> StatusChip("Ошибка", ChipKind.ERROR)
                RecordingState.NEW -> StatusChip("В очереди")
            }
            Spacer(Modifier.weight(1f))
            if (r.state == RecordingState.READY) {
                val speakers = r.utterances.asSequence().map { it.speakerId }.distinct().count()
                Text(plural(speakers, "участник", "участника", "участников"), style = Type.label, color = c.muted, maxLines = 1)
            } else if (r.state == RecordingState.PROCESSING && mine != null) {
                CircularProgressIndicator(
                    progress = { transcriptProgress(mine.progress) },
                    modifier = Modifier.width(Size.iconSmall).height(Size.iconSmall),
                    color = c.text, trackColor = c.raised, strokeWidth = 2.dp,
                )
            }
        }
    }
}

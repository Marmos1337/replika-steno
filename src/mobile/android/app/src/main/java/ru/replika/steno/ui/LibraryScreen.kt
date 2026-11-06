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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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

internal val AUDIO_TYPES = arrayOf("audio/*", "video/mp4", "application/ogg")

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

    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Height the bottom action bar covers: fade + button + bottom padding + system navigation bar.
    val barHeight = Size.fade + Size.touch + Space.l + navBottom

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                leading = if (wide) null else ({ IconBtn(Ic.Menu, "Открыть меню", onMenu) }),
                side = side,
                divider = items.isNotEmpty() && filtered.isNotEmpty() && listState.canScrollBackward,
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
                    // Only the mark, a title and one line; the actions live in the bottom bar, as with a list.
                    // centred in the space above the bar; scrolls only if the screen is too short
                    items.isEmpty() -> Box(Modifier.fillMaxSize().padding(bottom = barHeight), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(
                                title = "Пока нет записей",
                                text = "Импортируйте запись встречи или запишите её с микрофона.",
                            )
                        }
                    }
                    filtered.isEmpty() -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = barHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState("Ничего не нашлось", "Нет записей, где встречается «${query.trim()}».", icon = Ic.Search) {
                            SecondaryButton("Сбросить поиск", { query = "" }, Modifier.fillMaxWidth())
                        }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        // the last card scrolls fully above the bar
                        contentPadding = PaddingValues(start = side, end = side, bottom = barHeight + Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
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

        // Bottom action bar: the same place in the empty state and with a list. A 24dp fade on top,
        // then a solid strip of the page color, so cards slide under it without a seam.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(Size.fade).background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg))))
            Row(
                Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()
                    .padding(start = side, end = side, bottom = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(if (importing) "Копирую файл…" else "Импортировать аудио", pick, Modifier.weight(1f), Ic.File, enabled = !importing)
                OutlineIconButton(Ic.Mic, "Записать с микрофона", { micOpen = true })
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
    val mine = job?.takeIf { it.recordingId == r.id }
    // Compact card: title, one meta line, status on the right, ⋮ with a 4dp inset (as in the top bar).
    AppCard(
        modifier.fillMaxWidth(),
        onClick = onOpen,
        onLongClick = { menu = true },
        padding = PaddingValues(start = Space.l, end = Space.xs, top = Space.m, bottom = Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = Type.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.xs))
                val meta = buildList {
                    add(fmtDate(r.createdAtMs))
                    if (r.durationMs > 0) add(fmtTime(r.durationMs))
                    if (r.state == RecordingState.READY) {
                        add(plural(r.utterances.asSequence().map { it.speakerId }.distinct().count(), "участник", "участника", "участников"))
                    }
                }.joinToString(" · ")
                Text(meta, style = Type.labelNum, color = c.muted)
            }
            Spacer(Modifier.width(Space.s))
            when (r.state) {
                RecordingState.READY -> if (r.llmPending) StatusChip("Итоги готовятся", ChipKind.ACTIVE) else StatusChip("Готово", ChipKind.DONE)
                RecordingState.PROCESSING -> StatusChip(
                    if (mine != null) "${(transcriptProgress(mine.progress) * 100).toInt()}%" else "Обработка",
                    ChipKind.ACTIVE,
                )
                RecordingState.FAILED -> when (failureKind(r.error)) {
                    FailureKind.STOPPED -> StatusChip("Остановлено")
                    FailureKind.NO_SPEECH -> StatusChip("Нет речи", ChipKind.ERROR)
                    FailureKind.CORRUPT -> StatusChip("Файл повреждён", ChipKind.ERROR)
                    FailureKind.OTHER -> StatusChip("Ошибка", ChipKind.ERROR)
                }
                RecordingState.NEW -> StatusChip("В очереди")
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
    }
}

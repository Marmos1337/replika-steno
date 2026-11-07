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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.replika.steno.ActiveJob
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.ui.theme.Motion
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

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        // wide screens have no menu button: no empty top bar, the title lines up with the drawer's logo row
        val bar = !wide
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (bar) {
                // large-title pattern: «Записи» scrolls with the list and moves up into the bar once it is gone
                TopBar(
                    leading = { IconBtn(Ic.Menu, "Открыть меню", onMenu) },
                    side = side,
                    title = "Записи",
                    titleVisible = listState.firstVisibleItemIndex > 0,
                    divider = loaded && items.isNotEmpty() && listState.canScrollBackward,
                )
            }
            val head = @Composable {
                Column(Modifier.fillMaxWidth().padding(top = if (bar) 0.dp else (Size.topBar - Type.display.lineHeight.value.dp) / 2)) {
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
                    // + the list's 8dp item gap = the standard 16dp under the search field
                    Spacer(Modifier.height(Space.s))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loaded && items.isNotEmpty()) {
                    // One list holds the title, the search field and the cards, so nothing is clipped under a
                    // fixed header; the search field keeps its focus while the results change.
                    LazyColumn(
                        Modifier.fillMaxSize().edgeFade(
                            c.bg,
                            // with a bar the hairline under it marks the top edge
                            top = if (bar) 0f else fadeWhen(listState.canScrollBackward),
                            bottom = fadeWhen(listState.canScrollForward),
                        ),
                        state = listState,
                        // the last card keeps the standard gap above the action bar
                        contentPadding = PaddingValues(start = side, end = side, bottom = Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        item(key = "head", contentType = "head") { head() }
                        if (filtered.isEmpty()) {
                            item(key = "none", contentType = "none") {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    EmptyState("Ничего не нашлось", "Нет записей, где встречается «${query.trim()}».", icon = Ic.Search) {
                                        SecondaryButton("Сбросить поиск", { query = "" }, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                        items(filtered, key = { it.id }, contentType = { "card" }) { r ->
                            RecordingRow(
                                r, job,
                                onOpen = {
                                    vm.navigate(
                                        when {
                                            vm.isLiveRecording(r.id) -> Screen.Live
                                            r.state == RecordingState.READY -> Screen.Transcript(r.id)
                                            else -> Screen.Processing(r.id)
                                        },
                                    )
                                },
                                onDelete = { confirmDelete = r },
                                onRerun = { if (r.state == RecordingState.READY) confirmRerun = r else vm.reprocess(r.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(horizontal = side)) { head() }
                        if (loaded) {
                            // Only the mark, a title and one line; the actions live in the bottom bar, as with a list.
                            // centred in the space above the bar; scrolls only if the screen is too short
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                                    EmptyState(
                                        title = "Пока нет записей",
                                        text = "Начните живую запись встречи — текст пойдёт сразу — или импортируйте готовый файл.",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom action bar: the same place in the empty state and with a list. The list ends at its
            // top edge (nothing slides under it) and fades out into it while there are more cards below.
            BottomBar(side = side) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Live recording leads (it records, keeps the audio and then processes it like an import).
                    // On a phone the import keeps a short label so both fit on one row without clipping.
                    val roomy = maxWidth >= 440.dp
                    val withIcon = maxWidth >= 340.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton("Живая запись", { vm.navigate(Screen.Live) }, Modifier.weight(1f), if (withIcon) Ic.Mic else null)
                        SecondaryButton(
                            when {
                                importing -> if (roomy) "Копирую файл…" else "Копирую…"
                                roomy -> "Импортировать аудио"
                                else -> "Импорт"
                            },
                            pick,
                            icon = if (withIcon) Ic.File else null,
                            enabled = !importing,
                        )
                    }
                }
            }
        }
    }

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
                // one line in every card, so all cards have the same height
                Text(meta, style = Type.labelNum, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.s))
            // a ready recording is the normal case and needs no label; chips mark only what differs
            when (r.state) {
                RecordingState.READY -> if (r.llmPending) StatusChip("Итоги готовятся", ChipKind.ACTIVE)
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
                RecordingState.RECORDING -> StatusChip("Идёт запись", ChipKind.LIVE)
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

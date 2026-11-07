package ru.replika.steno.ui

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import ru.replika.steno.core.PointKind
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.util.Locale
import androidx.compose.ui.text.style.TextAlign
import ru.replika.steno.core.live.LiveMetrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.replika.steno.core.ActionItem
import ru.replika.steno.core.MeetingSummary
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.Utterance
import ru.replika.steno.export.TranscriptJson
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type
import ru.replika.steno.ui.theme.speakerTone

/** The diarizer's own cosine similarity; below this the speaker assignment is flagged as doubtful. */
private const val LOW_CONFIDENCE = 0.5f

/** Items above the utterances in the transcript list: header + sticky tabs. */
private const val HEAD_ITEMS = 2

private const val HINTS_PREFS = "ui_hints"
private const val HINT_TAP_DONE = "transcript_tap_done"

@Composable
fun TranscriptScreen(vm: ReplikaViewModel, id: String) {
    val items by vm.recordings.collectAsState()
    val loaded by vm.graph.store.loaded.collectAsState()
    val rec = items.firstOrNull { it.id == id }
    LaunchedEffect(rec?.state, loaded) {
        when {
            loaded && rec == null -> vm.navigate(Screen.Library)
            rec != null && rec.state != RecordingState.READY -> vm.navigate(Screen.Processing(id))
        }
    }
    if (rec == null) return
    TranscriptContent(vm, rec)
}

private sealed interface Dlg {
    data class Edit(val index: Int) : Dlg
    data class Original(val index: Int) : Dlg
    data class Rename(val speakerId: String) : Dlg
    data class Confidence(val index: Int) : Dlg
    data class Problems(val list: List<String>) : Dlg
    data object Rerun : Dlg
    data object LiveInfo : Dlg
}

@Composable
private fun TranscriptContent(vm: ReplikaViewModel, rec: Recording) {
    val c = Theme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = rememberAudioPlayer(vm.graph.store.audioFile(rec.id))
    val utts = rec.utterances

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchPos by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val summaryState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { (if (tab == 0) listState else summaryState).canScrollBackward } }

    // «Нажмите на реплику…» is shown until the first tap on a replica (remembered across launches).
    val hints = remember { context.getSharedPreferences(HINTS_PREFS, Context.MODE_PRIVATE) }
    var hintDone by remember { mutableStateOf(hints.getBoolean(HINT_TAP_DONE, false)) }
    // The header slot (hint or the background-pass indicator) stays while the screen is open once shown,
    // so the list never jumps when the pass finishes or the hint goes away.
    var headerSlot by remember { mutableStateOf(rec.llmPending || !hintDone) }
    LaunchedEffect(rec.llmPending) { if (rec.llmPending) headerSlot = true }
    fun tapped() {
        if (hintDone) return
        hintDone = true
        hints.edit().putBoolean(HINT_TAP_DONE, true).apply()
    }

    val speakers = remember(utts) { utts.map { it.speakerId }.distinct().sortedBy { it.substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE } }
    val q = query.trim()
    val matches = remember(utts, q, rec.speakerNames) {
        if (q.isEmpty()) emptyList() else utts.indices.filter { i ->
            utts[i].text.contains(q, true) || rec.label(utts[i].speakerId).contains(q, true)
        }
    }
    val activeMatch = matches.getOrNull(matchPos.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
    val doubts = remember(utts) { utts.indices.filter { utts[it].speakerConfidence?.let { c -> c < LOW_CONFIDENCE } == true } }
    var doubtPos by rememberSaveable { mutableIntStateOf(-1) }
    // Utterance briefly highlighted after a jump from a summary chip or the doubt navigator.
    var flash by remember { mutableIntStateOf(-1) }
    LaunchedEffect(flash) {
        if (flash >= 0) { delay(1_600); flash = -1 }
    }

    // The utterance under the playhead: the last one that started, while its end (plus a short tail) is not past.
    val current by remember(utts) {
        derivedStateOf {
            val pos = player.positionMs
            var lo = 0
            var hi = utts.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (utts[mid].startMs <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (found >= 0 && pos <= utts[found].endMs + 800) found else -1
        }
    }

    fun jumpTo(index: Int) {
        val u = utts.getOrNull(index) ?: return
        follow = true
        player.seekAndPlay(u.startMs)
    }

    /** From outside the row (summary chip, doubt navigator): show it, flash it, play it. */
    fun reveal(index: Int) {
        if (index !in utts.indices) return
        tab = 0
        flash = index
        scope.launch {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > HEAD_ITEMS }
            listState.ensureVisible(index + HEAD_ITEMS)
        }
        jumpTo(index)
    }

    // The user dragging the list stops the auto-follow until they pick a line or press the pill.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(current, player.playing, follow, tab) {
        if (!player.playing || !follow || current < 0 || tab != 0) return@LaunchedEffect
        listState.ensureVisible(current + HEAD_ITEMS)
    }
    LaunchedEffect(activeMatch, tab) {
        if (activeMatch != null && tab == 0) {
            follow = false
            listState.ensureVisible(activeMatch + HEAD_ITEMS)
        }
    }

    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    fun saved(ok: Boolean) = vm.toast(if (ok) "Файл сохранён" else "Не удалось сохранить файл")
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mime)) { uri ->
        if (uri != null) scope.launch { saved(vm.writeExport(uri, rec, ExportFormat.JSON)) }
    }
    val txtExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.TXT.mime)) { uri ->
        if (uri != null) scope.launch { saved(vm.writeExport(uri, rec, ExportFormat.TXT)) }
    }
    // Both formats are built from the same utterances, so the schema check guards both.
    fun checked(block: () -> Unit) {
        val p = TranscriptJson.validate(utts, rec.durationMs.takeIf { it > 0 })
        if (p.isNotEmpty()) dlg = Dlg.Problems(p) else block()
    }
    fun export(f: ExportFormat) = checked {
        val name = ReplikaViewModel.exportName(rec, f)
        if (f == ExportFormat.JSON) jsonExporter.launch(name) else txtExporter.launch(name)
    }
    fun share(f: ExportFormat) = checked {
        scope.launch {
            val file = vm.writeShareFile(rec, f)
            if (file == null) { vm.toast("Не удалось подготовить файл"); return@launch }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, "Поделиться расшифровкой")) }
                .onFailure { vm.toast("Нет приложения, чтобы отправить файл") }
        }
    }
    fun copyAll() {
        val text = utts.joinToString("\n") { "${rec.label(it.speakerId)} [${fmtTime(it.startMs)}]: ${it.text}" }
        copy(context, "Расшифровка", text)
        vm.toast("Текст скопирован")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The top bar keeps its height when it turns into the search field; a hairline appears under it
            // while the list is scrolled beneath.
            Box(Modifier.fillMaxWidth().height(Size.topBar)) {
                Fade(searchOpen, Modifier.fillMaxSize()) { open ->
                    if (open) {
                        Row(Modifier.fillMaxSize().padding(horizontal = barInset(side)), verticalAlignment = Alignment.CenterVertically) {
                            IconBtn(Ic.Back, "Закрыть поиск", { searchOpen = false; query = "" })
                            SearchField(
                                query, { query = it; matchPos = 0 }, "Найти в расшифровке", Modifier.weight(1f),
                                onSearch = { if (matches.isNotEmpty()) matchPos = (matchPos + 1) % matches.size },
                            )
                            Spacer(Modifier.width(Space.xs))
                            Text(
                                if (q.isEmpty()) "" else if (matches.isEmpty()) "0" else "${matchPos + 1}/${matches.size}",
                                style = Type.labelNum, color = c.muted, modifier = Modifier.width(Space.xxl + Space.l),
                                maxLines = 1,
                            )
                            IconBtn(Ic.Up, "Предыдущее совпадение", { matchPos = (matchPos - 1 + matches.size) % matches.size }, enabled = matches.size > 1)
                            IconBtn(Ic.Down, "Следующее совпадение", { matchPos = (matchPos + 1) % matches.size }, enabled = matches.size > 1)
                        }
                    } else {
                        TopBar(leading = { IconBtn(Ic.Back, "К записям", { vm.navigate(Screen.Library) }) }, side = side) {
                            IconBtn(Ic.Search, "Поиск по расшифровке", { searchOpen = true; tab = 0 })
                            IconBtn(Ic.Share, "Поделиться JSON", { share(ExportFormat.JSON) })
                            Box {
                                IconBtn(Ic.More, "Ещё действия", { menu = true })
                                AppMenu(
                                    menu, { menu = false },
                                    listOfNotNull(
                                        MenuEntry("Сохранить JSON", Ic.Download, onClick = { export(ExportFormat.JSON) }),
                                        MenuEntry("Сохранить TXT", Ic.Download, onClick = { export(ExportFormat.TXT) }),
                                        MenuEntry("Поделиться JSON", Ic.Share, onClick = { share(ExportFormat.JSON) }),
                                        MenuEntry("Поделиться TXT", Ic.Share, onClick = { share(ExportFormat.TXT) }),
                                        MenuEntry("Скопировать текст", Ic.Copy, onClick = ::copyAll),
                                        MenuEntry("Обработать заново", Ic.Refresh, onClick = { dlg = Dlg.Rerun }),
                                        rec.liveMetrics?.let { MenuEntry("Замеры живой записи", Ic.Info, onClick = { dlg = Dlg.LiveInfo }) },
                                    ),
                                )
                            }
                        }
                    }
                }
                val line by androidx.compose.animation.core.animateFloatAsState(
                    if (scrolled) 1f else 0f, Motion.spec(Theme.reducedMotion, Motion.FAST), label = "topDivider",
                )
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Size.stroke).alpha(line).background(c.border))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val header: @Composable () -> Unit = {
                    Header(rec, speakers, side, slot = headerSlot, hint = !hintDone, onRename = { dlg = Dlg.Rename(it) })
                }
                val tabs: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = side, vertical = Space.xs)) {
                        Segmented(
                            listOf(0, 1), tab, { tab = it },
                            label = { if (it == 0) "Расшифровка" else "Итоги" }, modifier = Modifier.fillMaxWidth(),
                        )
                        if (tab == 0 && doubts.isNotEmpty()) {
                            DoubtNavigator(
                                count = doubts.size, pos = doubtPos,
                                onStep = { d ->
                                    doubtPos = if (doubtPos < 0) (if (d > 0) 0 else doubts.size - 1) else (doubtPos + d + doubts.size) % doubts.size
                                    reveal(doubts[doubtPos])
                                },
                                onInfo = { dlg = Dlg.Confidence(doubts[doubtPos.coerceAtLeast(0)]) },
                            )
                        }
                    }
                }
                Fade(tab, Modifier.fillMaxSize()) { t ->
                    if (t == 0) {
                        LazyColumn(
                            state = listState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = Space.xxl),
                        ) {
                            item(key = "header") { header() }
                            stickyHeader(key = "tabs") { tabs() }
                            itemsIndexed(utts, key = { i, _ -> "u$i" }) { i, u ->
                                UtteranceRow(
                                    rec, u, side,
                                    playing = i == current || i == flash,
                                    query = if (matches.isNotEmpty()) q else "",
                                    active = i == activeMatch,
                                    onPlay = { tapped(); jumpTo(i) },
                                    onRename = { dlg = Dlg.Rename(u.speakerId) },
                                    onEdit = { dlg = Dlg.Edit(i) },
                                    onOriginal = { dlg = Dlg.Original(i) },
                                    onConfidence = { dlg = Dlg.Confidence(i) },
                                    onCopy = {
                                        copy(context, "Реплика", "${rec.label(u.speakerId)} [${fmtTime(u.startMs)}]: ${u.text}")
                                        vm.toast("Реплика скопирована")
                                    },
                                )
                            }
                            if (utts.isEmpty()) item(key = "empty") {
                                EmptyState("Реплик нет", "В этой записи не осталось распознанного текста.", icon = Ic.Records)
                            }
                        }
                    } else {
                        SummaryList(rec, summaryState, side, header, tabs, vm, onJump = ::reveal)
                    }
                }
                FollowPill(
                    visible = tab == 0 && player.playing && !follow && current >= 0,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    follow = true
                    scope.launch { listState.ensureVisible(current + HEAD_ITEMS) }
                }
            }
            PlayerBar(player, side)
        }
    }

    when (val d = dlg) {
        is Dlg.Edit -> utts.getOrNull(d.index)?.let { u ->
            EditDialog(u, onSave = { vm.editUtterance(rec.id, d.index, it) }, onDismiss = { dlg = null })
        }
        is Dlg.Original -> utts.getOrNull(d.index)?.let { u ->
            AppDialog("Исходный текст", { dlg = null }, buttons = {
                if (u.text != u.rawText) TextAction("Вернуть его", { vm.editUtterance(rec.id, d.index, u.rawText); dlg = null }, color = c.muted)
                TextAction("Закрыть", { dlg = null })
            }) {
                Text("Так фразу записала модель распознавания, до оформления и правок:", style = Type.label, color = c.muted)
                Spacer(Modifier.height(Space.s))
                Text(u.rawText, style = Type.body, color = c.text)
                if (u.edited) {
                    Spacer(Modifier.height(Space.l))
                    Text("Сейчас в расшифровке — ваш вариант.", style = Type.label, color = c.muted)
                }
            }
        }
        is Dlg.Rename -> RenameDialog(
            speakerId = d.speakerId,
            current = rec.speakerNames[d.speakerId].orEmpty(),
            onSave = { vm.renameSpeaker(rec.id, d.speakerId, it) },
            onDismiss = { dlg = null },
        )
        is Dlg.Confidence -> utts.getOrNull(d.index)?.let { u ->
            AppDialog("Говорящий под вопросом", { dlg = null }, buttons = { TextAction("Понятно", { dlg = null }) }) {
                Text(
                    "Модель голосов сама оценила, насколько эта фраза похожа на остальные фразы участника " +
                        "«${rec.label(u.speakerId)}». Её оценка: ${String.format(Locale.ROOT, "%.2f", u.speakerConfidence ?: 0f)} " +
                        "по шкале от −1 до 1. Ниже 0,5 фраза могла принадлежать другому человеку — например, при наложении голосов или похожих тембрах.",
                    style = Type.body, color = c.text,
                )
                Spacer(Modifier.height(Space.m))
                Text("Это собственная оценка модели, приложение её не меняет и не придумывает.", style = Type.label, color = c.muted)
            }
        }
        is Dlg.Problems -> AppDialog("Экспорт остановлен", { dlg = null }, buttons = { TextAction("Закрыть", { dlg = null }) }) {
            Text("Файл не прошёл проверку формата. Обработайте запись заново.", style = Type.body, color = c.text)
            Spacer(Modifier.height(Space.m))
            d.list.take(20).forEach { Text(it, style = Type.labelNum, color = c.muted) }
        }
        Dlg.LiveInfo -> rec.liveMetrics?.let { m ->
            AppDialog("Живая запись", { dlg = null }, buttons = { TextAction("Закрыть", { dlg = null }) }) { LiveMetricsBody(m) }
        }
        Dlg.Rerun -> ConfirmDialog(
            title = "Обработать заново?",
            text = "Расшифровка будет построена с нуля. Ваши правки текста пропадут, имена участников сохранятся.",
            confirm = "Обработать",
            onConfirm = { vm.reprocess(rec.id) }, onDismiss = { dlg = null },
        )
        null -> Unit
    }
}

@Composable
private fun FollowPill(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.spec(Theme.reducedMotion)), exit = fadeOut(Motion.spec(Theme.reducedMotion, Motion.FAST)),
        modifier = modifier.padding(bottom = Space.l),
    ) { PrimaryButton("К текущей реплике", onClick, icon = Ic.Down) }
}

/** Scrolls so that [index] is fully visible below the sticky tabs; does nothing if it already is. */
private suspend fun LazyListState.ensureVisible(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val top = info.viewportStartOffset + (info.visibleItemsInfo.firstOrNull { it.key == "tabs" }?.size ?: 0)
    if (item != null && item.offset >= top && item.offset + item.size <= info.viewportEndOffset) return
    animateScrollToItem((index - 1).coerceAtLeast(HEAD_ITEMS))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Header(rec: Recording, speakers: List<String>, side: Dp, slot: Boolean, hint: Boolean, onRename: (String) -> Unit) {
    val c = Theme.colors
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = side)) {
            Text(rec.title, style = Type.display, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xs))
            Text(
                listOf(fmtTime(rec.durationMs), plural(speakers.size, "участник", "участника", "участников"), fmtDate(rec.createdAtMs)).joinToString(" · "),
                style = Type.labelNum, color = c.muted,
            )
            // One fixed-height slot: the background pass indicator, or the tap hint until the first tap.
            if (slot) {
                Spacer(Modifier.height(Space.s))
                val content = when {
                    rec.llmPending -> 1
                    hint -> 2
                    else -> 0
                }
                Fade(content, Modifier.fillMaxWidth().height(Size.iconSmall + Space.xs)) { k ->
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        when (k) {
                            1 -> {
                                CircularProgressIndicator(Modifier.size(Space.l - Space.xs), color = c.text, trackColor = c.raised, strokeWidth = 2.dp)
                                Spacer(Modifier.width(Space.s))
                                Text("Оформление и итоги готовятся…", style = Type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            2 -> Text("Нажмите на реплику, чтобы послушать с этого места", style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else -> Unit
                        }
                    }
                }
            }
        }
        // 48dp touch rows around 32dp pills already give the 8dp visual gaps above and below.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            speakers.forEach { s ->
                SpeakerChip(rec.label(s), c.speakerTone(s), "Переименовать участника", onClick = { onRename(s) }, trailing = Ic.Edit)
            }
        }
    }
}

@Composable
private fun SpeakerChip(name: String, tone: Color, actionLabel: String, onClick: () -> Unit, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = Theme.colors
    // 48dp touch area around a 32dp visual pill.
    Box(
        Modifier.touchTarget().clip(Radius.pill).clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(Space.xxl).clip(Radius.pill).background(tone.copy(alpha = if (c.dark) 0.16f else 0.12f))
                .padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(Size.dot).clip(Radius.pill).background(tone))
            Spacer(Modifier.width(Space.s))
            Text(name, style = Type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            if (trailing != null) {
                Spacer(Modifier.width(Space.s))
                Icon(trailing, null, Modifier.size(14.dp), tint = c.muted)
            }
        }
    }
}

@Composable
private fun UtteranceRow(
    rec: Recording,
    u: Utterance,
    side: Dp,
    playing: Boolean,
    query: String,
    active: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onOriginal: () -> Unit,
    onConfidence: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = Theme.colors
    var menu by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (playing) c.playing else c.bg, Motion.spec(Theme.reducedMotion), label = "row")
    val doubtful = u.speakerConfidence?.let { it < LOW_CONFIDENCE } == true
    val tone = c.speakerTone(u.speakerId)
    // The highlight extends 12dp past the text column, so the ⋮ lines up with the top bar's ⋮.
    Box(Modifier.fillMaxWidth().padding(horizontal = barInset(side))) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.r14).background(bg)
                .combinedClickable(onClickLabel = "Слушать с этого места", onClick = onPlay, onLongClick = { menu = true })
                .padding(start = side - barInset(side), bottom = Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // plain speaker line: colored dot + name (tap to rename) + time (tap to listen)
                Row(
                    Modifier.weight(1f, fill = false).heightIn(min = Size.touch).clip(Radius.r8)
                        .clickable(role = Role.Button, onClickLabel = "Переименовать участника", onClick = onRename)
                        .padding(end = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(Size.dot).clip(Radius.pill).background(tone))
                    Spacer(Modifier.width(Space.s))
                    Text(rec.label(u.speakerId), style = Type.speaker, color = c.text)
                }
                Box(
                    Modifier.touchTarget().clip(Radius.pill).clickable(role = Role.Button, onClickLabel = "Слушать с ${fmtTime(u.startMs)}", onClick = onPlay)
                        .padding(horizontal = Space.s),
                    contentAlignment = Alignment.Center,
                ) { Text(fmtTime(u.startMs), style = Type.speakerTime, color = if (playing) c.text else c.muted, maxLines = 1) }
                if (u.lang == "en") {
                    Text("EN", style = Type.labelNum, color = c.muted, modifier = Modifier.padding(end = Space.s))
                }
                if (doubtful) {
                    Box(
                        Modifier.size(Size.touch).clip(Radius.pill).clickable(role = Role.Button, onClickLabel = "Почему под вопросом", onClick = onConfidence),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Question, "Говорящий под вопросом", Modifier.size(Size.iconSmall), tint = c.muted) }
                }
                Spacer(Modifier.weight(1f))
                if (u.edited) {
                    Box(
                        Modifier.touchTarget().clip(Radius.pill).clickable(role = Role.Button, onClickLabel = "Показать исходный текст", onClick = onOriginal)
                            .padding(horizontal = Space.s),
                        contentAlignment = Alignment.Center,
                    ) { Text("изменено", style = Type.label, color = c.muted, maxLines = 1) }
                }
                Box {
                    IconBtn(Ic.More, "Действия с репликой", { menu = true }, tint = c.muted)
                    AppMenu(
                        menu, { menu = false },
                        buildList {
                            add(MenuEntry("Изменить текст", Ic.Edit, onClick = onEdit))
                            if (u.text != u.rawText) add(MenuEntry("Исходный текст", Ic.Info, onClick = onOriginal))
                            add(MenuEntry("Переименовать участника", Ic.Settings, onClick = onRename))
                            add(MenuEntry("Скопировать", Ic.Copy, onClick = onCopy))
                        },
                    )
                }
            }
            Text(
                highlight(u.text, query, if (active) c.matchActive else c.match),
                style = Type.body, color = c.text, modifier = Modifier.padding(end = side - barInset(side)),
            )
        }
    }
}

private fun highlight(text: String, query: String, bg: Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            append(text.substring(from, i))
            withStyle(SpanStyle(background = bg)) { append(text.substring(i, i + query.length)) }
            from = i + query.length
        }
        append(text.substring(from))
    }
}

@Composable
private fun SummaryList(
    rec: Recording,
    state: LazyListState,
    side: Dp,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    vm: ReplikaViewModel,
    onJump: (Int) -> Unit,
) {
    val c = Theme.colors
    val summary: MeetingSummary? = rec.summary
    val llmOn by vm.llmEnabled.collectAsState()
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
        item(key = "header") { header() }
        stickyHeader(key = "tabs") { tabs() }
        if (summary == null && rec.llmPending) {
            item(key = "skeleton") { SummarySkeleton(side) }
            return@LazyColumn
        }
        if (summary == null || (summary.points.isEmpty() && summary.actions.isEmpty())) {
            item(key = "none") {
                val status = vm.graph.llmStatus
                val (title, text) = when {
                    rec.llmNote != null -> "Итоги не построены" to rec.llmNote.removePrefix("Итоги не построены: ").replaceFirstChar { it.uppercase() }
                    !llmOn -> "Итоги не построены" to "Языковая модель выключена. Включите её в разделе «О приложении и модели» и обработайте запись заново."
                    status.startsWith("не загрузилась") -> "Итоги не построены: языковая модель недоступна" to "Модель $status. Расшифровка при этом полная."
                    else -> "В записи не нашлось итогов" to "Модель не нашла решений и договорённостей, которые можно подтвердить ссылкой на реплику."
                }
                EmptyState(title, text, icon = Ic.Records) {
                    if (!llmOn) SecondaryButton("Открыть настройки модели", { vm.navigate(Screen.About) }, Modifier.fillMaxWidth())
                }
            }
            return@LazyColumn
        }
        PointKind.entries.forEach { kind ->
            val points = summary.points.filter { it.kind == kind }
            if (points.isEmpty()) return@forEach
            item(key = "t_${kind.name}") { SummaryTitle(kind.title, side) }
            item(key = "p_${kind.name}") {
                AppCard(Modifier.fillMaxWidth().padding(horizontal = side), padding = PaddingValues(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xs)) {
                    points.forEachIndexed { i, p ->
                        if (i > 0) { Spacer(Modifier.height(Space.xs)); Hairline(); Spacer(Modifier.height(Space.m)) }
                        Text(rec.labelText(p.text), style = Type.body, color = c.text)
                        RefChips(rec, p.utteranceIndices, onJump)
                    }
                }
            }
        }
        if (summary.actions.isNotEmpty()) {
            item(key = "actionsTitle") { SummaryTitle("Договорённости", side) }
            items(summary.actions.size, key = { "a$it" }) { i ->
                ActionCard(
                    summary.actions[i], rec, side, onJump,
                    onConfirm = { vm.setActionConfirmed(rec.id, i, it) },
                )
                Spacer(Modifier.height(Space.m))
            }
        }
        item(key = "note") {
            Text(
                "Итоги собраны на устройстве. Отметки не попадают в экспорт.",
                style = Type.label, color = c.muted, modifier = Modifier.padding(horizontal = side, vertical = Space.s),
            )
        }
    }
}

@Composable
private fun SummaryTitle(text: String, side: Dp) {
    SectionTitle(text, Modifier.padding(start = side, end = side, top = Space.l, bottom = Space.m))
}

/** Time chips of the cited utterances; each one opens the transcript there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefChips(rec: Recording, indices: List<Int>, onJump: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        indices.filter { it in rec.utterances.indices }.take(8).forEach { idx ->
            RefChip(rec.utterances[idx].startMs) { onJump(idx) }
        }
    }
}

@Composable
private fun RefChip(startMs: Long, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        Modifier.touchTarget().clip(Radius.pill).clickable(role = Role.Button, onClickLabel = "К реплике ${fmtTime(startMs)}", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(Space.xxl - Space.xs).clip(Radius.pill).background(c.raised).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Play, null, Modifier.size(Space.m), tint = c.muted)
            Spacer(Modifier.width(Space.xs))
            Text(fmtTime(startMs), style = Type.labelNum, color = c.text, maxLines = 1)
        }
    }
}

@Composable
private fun ActionCard(a: ActionItem, rec: Recording, side: Dp, onJump: (Int) -> Unit, onConfirm: (Boolean) -> Unit) {
    val c = Theme.colors
    val bg by animateColorAsState(if (a.confirmed) c.raised else c.surface, Motion.spec(Theme.reducedMotion), label = "action")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = side).clip(Radius.r20).background(bg)
            .border(Size.stroke, if (a.confirmed) c.borderStrong else c.border, Radius.r20)
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.s),
    ) {
        Field("Что сделать", rec.labelText(a.text), strong = true)
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Field("Кто", a.assignee?.let(rec::labelText), Modifier.weight(1f))
            Field("Срок", a.due, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            rec.utterances.getOrNull(a.utteranceIndex)?.let { RefChip(it.startMs) { onJump(a.utteranceIndex) } }
            Spacer(Modifier.weight(1f))
            // checkbox-style square; the label stays the same so nothing shifts when it is ticked
            Row(
                Modifier.touchTarget().clip(Radius.pill)
                    .toggleable(a.confirmed, role = Role.Checkbox, onValueChange = onConfirm)
                    .padding(horizontal = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(Size.iconSmall + Space.xs - Size.stroke * 2).clip(Radius.r6)
                        .background(if (a.confirmed) c.text else c.bg)
                        .border(Size.stroke, if (a.confirmed) c.text else c.borderStrong, Radius.r6),
                    contentAlignment = Alignment.Center,
                ) { if (a.confirmed) Icon(Ic.Check, null, Modifier.size(14.dp), tint = c.onText) }
                Spacer(Modifier.width(Space.s))
                Text("Подтверждено", style = Type.label, color = if (a.confirmed) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?, modifier: Modifier = Modifier, strong: Boolean = false) {
    val c = Theme.colors
    Column(modifier) {
        Text(label, style = Type.label, color = c.muted)
        Spacer(Modifier.height(Space.xs))
        Text(
            value?.takeIf { it.isNotBlank() } ?: "—",
            style = if (strong) Type.bodyStrong else Type.body,
            color = if (value.isNullOrBlank()) c.muted else c.text,
        )
    }
}
/** Placeholder blocks shaped like the summary while the LLM pass is still running. */
@Composable
private fun SummarySkeleton(side: Dp) {
    val c = Theme.colors
    val alpha = if (Theme.reducedMotion) 1f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(1f, 0.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = side).alpha(alpha)) {
        Row(Modifier.padding(top = Space.l, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(Space.l), color = c.text, trackColor = c.raised, strokeWidth = 2.dp)
            Spacer(Modifier.width(Space.s))
            Text("Языковая модель собирает итоги…", style = Type.label, color = c.muted)
        }
        repeat(2) { block ->
            AppCard(Modifier.fillMaxWidth()) {
                repeat(3) { line ->
                    if (line > 0) Spacer(Modifier.height(Space.m))
                    Box(Modifier.fillMaxWidth(if (line == 2) 0.6f else 1f).height(Space.m).clip(Radius.r6).background(c.raised))
                }
                Spacer(Modifier.height(Space.m))
                Box(Modifier.width(64.dp).height(Space.xl).clip(Radius.pill).background(c.raised))
            }
            if (block == 0) Spacer(Modifier.height(Space.m))
        }
    }
}

/** «Под вопросом: N» with prev/next: walks the utterances whose speaker the diarizer was unsure of. */
@Composable
private fun DoubtNavigator(count: Int, pos: Int, onStep: (Int) -> Unit, onInfo: () -> Unit) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(Size.touch).clip(Radius.pill).clickable(role = Role.Button, onClickLabel = "Что значит «под вопросом»", onClick = onInfo)
                .padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Question, null, Modifier.size(Size.iconSmall), tint = c.muted)
            Spacer(Modifier.width(Space.s))
            Text("Под вопросом: $count", style = Type.label, color = c.text, maxLines = 1)
            if (pos >= 0) {
                Spacer(Modifier.width(Space.s))
                Text("${pos + 1}/$count", style = Type.labelNum, color = c.muted, maxLines = 1)
            }
        }
        IconBtn(Ic.Up, "Предыдущая под вопросом", { onStep(-1) })
        IconBtn(Ic.Down, "Следующая под вопросом", { onStep(1) })
    }
}

@Composable
private fun EditDialog(u: Utterance, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Theme.colors
    var text by rememberSaveable { mutableStateOf(u.text) }
    val blank = text.isBlank()
    AppDialog("Изменить текст", onDismiss, buttons = {
        TextAction("Отмена", onDismiss, color = c.muted)
        TextAction("Сохранить", { if (!blank) { onSave(text); onDismiss() } }, color = if (blank) c.muted else c.text)
    }) {
        DialogField(text, { text = it }, Modifier.fillMaxWidth(), error = if (blank) "Текст не может быть пустым" else null)
        Spacer(Modifier.height(Space.s))
        Text("Распознано моделью", style = Type.label, color = c.muted)
        Spacer(Modifier.height(Space.xs))
        Text(u.rawText, style = Type.body, color = c.muted)
        if (text != u.rawText) {
            TextAction("Вернуть распознанное", { text = u.rawText }, Modifier.padding(top = Space.xs), color = c.text)
        }
        Spacer(Modifier.height(Space.s))
        Text("Время и говорящий не меняются.", style = Type.label, color = c.muted)
    }
}

@Composable
private fun RenameDialog(speakerId: String, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Theme.colors
    var name by rememberSaveable { mutableStateOf(current) }
    AppDialog("Имя участника", onDismiss, buttons = {
        if (current.isNotEmpty()) TextAction("Сбросить", { onSave(""); onDismiss() }, color = c.muted)
        TextAction("Отмена", onDismiss, color = c.muted)
        TextAction("Сохранить", { onSave(name); onDismiss() })
    }) {
        DialogField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), placeholder = defaultSpeakerLabel(speakerId), singleLine = true)
        Text("Имя видно в приложении и в TXT. В JSON остаётся «$speakerId».", style = Type.label, color = c.muted)
    }
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Numbers measured on this phone during the live take; a missing measurement says so instead of a guess. */
@Composable
private fun LiveMetricsBody(m: LiveMetrics) {
    val c = Theme.colors
    val ru = Locale.forLanguageTag("ru")
    fun sec(ms: Long) = String.format(ru, "%.1f с", ms / 1000f)
    val rows = buildList {
        add("Записано" to fmtTime(m.audioMs))
        add("Первый текст" to (m.timeToFirstTextMs?.let { "через ${sec(it)}" } ?: "нет замера"))
        add(
            "Задержка обновления" to
                if (m.latencyMedianMs != null && m.latencyP95Ms != null) "медиана ${m.latencyMedianMs} мс · p95 ${m.latencyP95Ms} мс" else "нет замера",
        )
        add("Скорость распознавания" to (m.speedX?.let { String.format(ru, "×%.1f к реальному времени", it) } ?: "нет замера"))
        add("Наибольшее отставание" to sec(m.maxBacklogMs))
        add("Пик памяти" to (m.peakPssMb?.let { "$it МБ" } ?: "нет замера"))
        if (m.droppedAudioMs > 0) add("Пропущено живым текстом" to "${sec(m.droppedAudioMs)} (в расшифровке есть)")
    }
    rows.forEachIndexed { i, (label, value) ->
        if (i > 0) { Spacer(Modifier.height(Space.s)); Hairline(); Spacer(Modifier.height(Space.s)) }
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = Type.label, color = c.muted, modifier = Modifier.weight(0.8f))
            Spacer(Modifier.width(Space.m))
            Text(value, style = Type.labelNum, color = c.text, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
        }
    }
    Spacer(Modifier.height(Space.l))
    Text("Замерено на этом телефоне во время записи. В экспорт не попадает.", style = Type.label, color = c.muted)
}

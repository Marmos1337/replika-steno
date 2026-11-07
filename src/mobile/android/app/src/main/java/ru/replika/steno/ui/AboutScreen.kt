package ru.replika.steno.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.replika.steno.asr.ModelAssets
import ru.replika.steno.core.RecordingState
import ru.replika.steno.diar.VadDiarizer
import ru.replika.steno.llm.LlmFormatter
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

private data class ModelRow(val role: String, val name: String, val details: String, val assets: List<String>)

private val MODELS = listOf(
    ModelRow(
        "Распознавание (русский)", "GigaAM v3 CTC с пунктуацией (Сбер)",
        "int8, русский язык, ставит знаки препинания и заглавные буквы",
        listOf(ModelAssets.ASR_MODEL, ModelAssets.ASR_TOKENS),
    ),
    ModelRow(
        "Распознавание (английский)", "NVIDIA NeMo Parakeet TDT-CTC 110M",
        "int8, с пунктуацией. Включается сам для фраз, которые русская модель записала латиницей",
        listOf(ModelAssets.ASR_EN_MODEL, ModelAssets.ASR_EN_TOKENS),
    ),
    ModelRow("Поиск речи", "Silero VAD", "находит фразы и паузы между ними", listOf(ModelAssets.VAD)),
    ModelRow(
        "Голосовые отпечатки", "NVIDIA NeMo TitaNet-small",
        "16 кГц, по отпечатку на каждую фразу", listOf(ModelAssets.EMBEDDING),
    ),
    ModelRow(
        "Кластеризация", "Средняя связь по косинусному расстоянию",
        "порог ${fmtNum(VadDiarizer.CLUSTER_THRESHOLD)}, число говорящих определяется автоматически", emptyList(),
    ),
    ModelRow(
        "Языковая модель", "Qwen2.5-0.5B-Instruct q8",
        "через MediaPipe LLM Inference, окно ${LlmFormatter.CONTEXT_TOKENS} токенов", listOf(ModelAssets.LLM),
    ),
    ModelRow("Движок", "sherpa-onnx 2.0.1", "на ONNX Runtime, работает на процессоре", emptyList()),
)

private val STEPS = listOf(
    "Подготовка" to "Файл переводится в моно 16 кГц — так его понимают все модели.",
    "Участники" to "Запись режется на фразы по паузам, для каждой фразы снимается отпечаток голоса, похожие отпечатки собираются в участников.",
    "Распознавание" to "Каждая фраза расшифровывается отдельно, поэтому у текста точное время и говорящий. После этого расшифровку уже можно читать.",
    "Подготовка результата" to "В фоне языковая модель поправляет знаки препинания и заглавные буквы. Слова, говорящих и время она менять не может — это проверяется.",
    "Итоги встречи" to "Модель собирает кратко, решения, открытые вопросы и договорённости; каждый пункт ссылается на реплики, из которых взят.",
)

private val LIMITS = listOf(
    "Основной язык — русский. Английский распознаёт вторая модель; если в одной фразе смешаны языки, вся фраза уходит одной модели.",
    "Когда люди говорят одновременно, фраза достаётся одному из них.",
    "На очень похожих голосах и на шумной записи издалека участников разделить сложнее.",
    "Языковая модель только расставляет знаки и регистр и пишет итоги — слова, говорящих и время она изменить не может.",
    "Для итогов длинной встречи берутся только первые ~8 фрагментов записи: у модели маленькое окно.",
    "Кто и к какому сроку — заполняется, только если это прозвучало в реплике. Иначе поле остаётся пустым.",
    "Скорость зависит от телефона.",
)

private fun fmtNum(f: Float) = String.format(Locale.forLanguageTag("ru"), "%.1f", f)

@Composable
fun AboutScreen(vm: ReplikaViewModel, wide: Boolean, onMenu: () -> Unit) {
    val c = Theme.colors
    val context = LocalContext.current
    val llmOn by vm.llmEnabled.collectAsState()
    val items by vm.recordings.collectAsState()
    val job by vm.job.collectAsState()
    val version = remember { appVersion(context) }
    val sizes by produceState<Map<String, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            MODELS.flatMap { it.assets }.associateWith { path -> runCatching { context.assets.openFd(path).use { it.length } }.getOrDefault(-1L) }
        }
    }
    // llmStatus is a plain field updated by the engine; reading it again whenever the job changes keeps it fresh.
    val llmStatus = remember(job == null, llmOn) { vm.graph.llmStatus }
    val last = items.filter { it.state == RecordingState.READY && it.metrics != null }.maxByOrNull { it.createdAtMs }

    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val side = gutter(maxWidth)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(leading = if (wide) null else ({ IconBtn(Ic.Menu, "Открыть меню", onMenu) }), side = side, divider = scroll.canScrollBackward)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .padding(horizontal = side).navigationBarsPadding().padding(bottom = Space.xxl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoSquare(Size.touch + Space.s)
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        Text("Реплика", style = Type.display, color = c.text)
                        Text("Версия $version · Нейро-стенографист", style = Type.label, color = c.muted, maxLines = 2)
                    }
                }
                Spacer(Modifier.height(Space.l))
                Text(
                    "Расшифровывает запись встречи по говорящим и собирает итоги. Полностью на телефоне.",
                    style = Type.body, color = c.text,
                )
                Spacer(Modifier.height(Space.s))
                Text(
                    "Живая запись показывает текст прямо во время разговора — тоже на телефоне и теми же моделями. " +
                        "После остановки запись проходит полную обработку: участники, окончательный текст и итоги.",
                    style = Type.body, color = c.muted,
                )

                Block("Как это работает") {
                    AppCard(Modifier.fillMaxWidth()) {
                        STEPS.forEachIndexed { i, (title, text) ->
                            if (i > 0) Spacer(Modifier.height(Space.l))
                            Row {
                                // same style as the step title, so the number sits on the title's baseline
                                Text("${i + 1}", style = Type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = c.muted, modifier = Modifier.width(Space.xl))
                                Spacer(Modifier.width(Space.s))
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = Type.bodyStrong, color = c.text)
                                    Spacer(Modifier.height(Space.xs))
                                    Text(text, style = Type.body, color = c.muted)
                                }
                            }
                        }
                    }
                }

                Block("Приватность") {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row {
                            Icon(Ic.Shield, null, Modifier.size(Size.icon), tint = c.text)
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f)) {
                                Text("Всё остаётся на устройстве", style = Type.bodyStrong, color = c.text)
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    "У приложения вообще нет разрешения на доступ в интернет — система не даст ему ничего отправить. " +
                                        "Записи, расшифровки и модели хранятся только в памяти телефона.",
                                    style = Type.body, color = c.muted,
                                )
                            }
                        }
                    }
                }

                Block("Модели") {
                    AppCard(Modifier.fillMaxWidth()) {
                        MODELS.forEachIndexed { i, m ->
                            if (i > 0) { Spacer(Modifier.height(Space.m)); Hairline(); Spacer(Modifier.height(Space.m)) }
                            Text(m.role, style = Type.label, color = c.muted)
                            Spacer(Modifier.height(Space.xs))
                            Row(verticalAlignment = Alignment.Top) {
                                Text(m.name, style = Type.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                                if (m.assets.isNotEmpty()) {
                                    Spacer(Modifier.width(Space.m))
                                    Text(sizeLabel(sizes, m.assets), style = Type.labelNum, color = c.muted, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.height(Space.xs))
                            Text(m.details, style = Type.body, color = c.muted)
                            if (m.assets.contains(ModelAssets.LLM)) {
                                Spacer(Modifier.height(Space.s))
                                StatusChip(
                                    "Состояние: $llmStatus",
                                    when {
                                        llmStatus == "работает" -> ChipKind.ACTIVE
                                        llmStatus.startsWith("не загрузилась") -> ChipKind.ERROR
                                        else -> ChipKind.NEUTRAL
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    AppCard(
                        Modifier.fillMaxWidth().clip(Radius.r20)
                            .toggleable(llmOn, role = Role.Switch, onValueChange = vm::setLlmEnabled),
                        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.m),
                    ) {
                        Row(Modifier.heightIn(min = Size.touch), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Использовать языковую модель", style = Type.bodyStrong, color = c.text)
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    "Знаки препинания и итоги. Без неё обработка быстрее, итогов не будет.",
                                    style = Type.label, color = c.muted,
                                )
                            }
                            Spacer(Modifier.width(Space.m))
                            Switch(
                                checked = llmOn, onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = c.onText, checkedTrackColor = c.text, checkedBorderColor = c.text,
                                    uncheckedThumbColor = c.muted, uncheckedTrackColor = c.raised, uncheckedBorderColor = c.borderStrong,
                                ),
                            )
                        }
                    }
                }

                Block("Скорость на этом телефоне") {
                    AppCard(Modifier.fillMaxWidth()) {
                        val m = last?.metrics
                        if (m == null) {
                            Text("Пока нечего показать", style = Type.bodyStrong, color = c.text)
                            Spacer(Modifier.height(Space.xs))
                            Text("Обработайте первую запись — здесь появятся замеры: длина записи, время и скорость.", style = Type.body, color = c.muted)
                        } else {
                            Text(last.title, style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(Space.m))
                            // equal tiles; labels and values may wrap to a second line, never cut
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                Metric("Аудио", fmtTime(m.audioDurationMs), null, Modifier.weight(1f).fillMaxHeight())
                                Metric("Обработка", fmtTime(m.transcriptMs), null, Modifier.weight(1f).fillMaxHeight())
                                val (speed, relation) = speedLabel(m.realTimeFactor)
                                Metric("Скорость", speed, relation, Modifier.weight(1f).fillMaxHeight())
                            }
                            Spacer(Modifier.height(Space.m))
                            Text(
                                "Минута записи расшифровывается примерно за ${fmtDuration((60_000 * m.realTimeFactor).toLong())}. " +
                                    "Чтение — ${fmtDuration(m.decodeMs)}, участники — ${fmtDuration(m.diarizationMs)}, текст — ${fmtDuration(m.asrMs)}. " +
                                    "Языковая модель потом работала в фоне ещё ${fmtDuration(m.llmMs)}.",
                                style = Type.label, color = c.muted,
                            )
                        }
                        Spacer(Modifier.height(Space.m))
                        Text("${Build.MANUFACTURER} ${Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} ядер", style = Type.label, color = c.muted)
                    }
                }

                Block("Ограничения") {
                    AppCard(Modifier.fillMaxWidth()) {
                        LIMITS.forEachIndexed { i, t ->
                            if (i > 0) Spacer(Modifier.height(Space.m))
                            Row {
                                Text("—", style = Type.body, color = c.muted)
                                Spacer(Modifier.width(Space.s))
                                Text(t, style = Type.body, color = c.text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(Space.xxl))
    SectionTitle(title)
    Spacer(Modifier.height(Space.m))
    content()
}

@Composable
private fun Metric(label: String, value: String, caption: String?, modifier: Modifier) {
    val c = Theme.colors
    Column(modifier.clip(Radius.r14).background(c.bg).border(Size.stroke, c.border, Radius.r14).padding(Space.m)) {
        Text(label, style = Type.label, color = c.muted, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        Text(value, style = Type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 2)
        if (caption != null) Text(caption, style = Type.label, color = c.muted, maxLines = 2)
    }
}

/** «в 1,4 раза» + «быстрее записи» from the real-time factor (processing time / audio length). */
private fun speedLabel(rtf: Float): Pair<String, String> {
    val ru = Locale.forLanguageTag("ru")
    if (rtf <= 0f) return "—" to "нет замера"
    return if (rtf <= 1f) {
        "в ${String.format(ru, "%.1f", 1f / rtf)} раза" to "быстрее записи"
    } else {
        "в ${String.format(ru, "%.1f", rtf)} раза" to "медленнее записи"
    }
}

private fun sizeLabel(sizes: Map<String, Long>?, assets: List<String>): String {
    if (sizes == null) return "…"
    val known = assets.mapNotNull { sizes[it]?.takeIf { s -> s >= 0 } }
    if (known.isEmpty()) return "нет в сборке"
    val bytes = known.sum()
    val mb = bytes / 1_048_576.0
    return if (mb >= 10) "${mb.toInt()} МБ" else String.format(Locale.forLanguageTag("ru"), "%.1f МБ", mb)
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "—"

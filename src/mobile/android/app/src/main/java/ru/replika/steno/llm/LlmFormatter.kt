package ru.replika.steno.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.replika.steno.asr.ModelAssets
import ru.replika.steno.core.Formatter

/**
 * Qwen2.5-0.5B-Instruct (q8) running fully on-device through MediaPipe LLM Inference.
 * Greedy decoding (top-k 1, temperature 0) so the same transcript always gives the same result.
 */
class LlmFormatter private constructor(private val llm: LlmInference) : Formatter {

    override val name = "Qwen2.5-0.5B-Instruct q8 (MediaPipe LLM Inference)"

    private fun ask(system: String, user: String, example: Pair<String, String>? = null): String {
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .setTemperature(0f)
                .setRandomSeed(7)
                // the .task bundle carries its own chat template; we send the full Qwen ChatML ourselves
                .setPromptTemplates(
                    PromptTemplates.builder()
                        .setSystemPrefix("").setSystemSuffix("")
                        .setUserPrefix("").setUserSuffix("")
                        .setModelPrefix("").setModelSuffix("")
                        .build(),
                )
                .build(),
        )
        try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            session.addQueryChunk(
                buildString {
                    append("<|im_start|>system\n$system<|im_end|>\n")
                    if (example != null) {
                        append("<|im_start|>user\n${example.first}<|im_end|>\n<|im_start|>assistant\n${example.second}<|im_end|>\n")
                    }
                    append("<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n")
                },
            )
            val answer = session.generateResponse()
            android.util.Log.i("Replika", "llm ${android.os.SystemClock.elapsedRealtime() - t0} ms, prompt ${user.length} chars, answer ${answer.length} chars")
            return answer
        } finally {
            session.close()
        }
    }

    override suspend fun formatLines(lines: List<String>): List<String?> = withContext(Dispatchers.Default) {
        val user = buildString {
            appendLine("Расставь знаки препинания и заглавные буквы в каждой строке. Не меняй, не добавляй и не удаляй слова. Ответь строками в том же формате «N) текст».")
            lines.forEachIndexed { i, l -> appendLine("${i + 1}) $l") }
        }
        val answer = synchronized(this@LlmFormatter) {
            ask("Ты корректор стенограммы. Ты исправляешь только пунктуацию и регистр.", user)
        }
        val byNumber = HashMap<Int, String>()
        for (line in answer.lines()) {
            val m = LINE.find(line.trim()) ?: continue
            byNumber.putIfAbsent(m.groupValues[1].toInt(), m.groupValues[2].trim())
        }
        lines.indices.map { byNumber[it + 1] }
    }

    override suspend fun summarize(numberedTranscript: String): String = withContext(Dispatchers.Default) {
        synchronized(this@LlmFormatter) {
            ask(SUMMARY_SYSTEM, SUMMARY_ASK + numberedTranscript, SUMMARY_EXAMPLE)
        }
    }

    fun close() = llm.close()

    companion object {
        private val LINE = Regex("^(\\d+)[).:]\\s*(.+)$")

        private const val SUMMARY_SYSTEM =
            "Ты подводишь итоги рабочей встречи по стенограмме. Пиши только то, что сказано в стенограмме. " +
                "Каждый пункт заканчивай номерами реплик в квадратных скобках. Если исполнитель или срок не названы, ставь «-». " +
                "Пустой раздел оставляй пустым."
        private const val SUMMARY_ASK = "Стенограмма:\n"

        /** One-shot example with unrelated content; SummaryParser drops anything not grounded in the cited lines. */
        private val SUMMARY_EXAMPLE = SUMMARY_ASK +
            "[1] speaker_1: Нам нужно выбрать подрядчика для ремонта склада.\n" +
            "[2] speaker_2: Предлагаю компанию «Вектор», у них самая низкая цена.\n" +
            "[3] speaker_1: Согласен, берём «Вектор». Олег, подготовь договор до понедельника.\n" +
            "[4] speaker_2: А кто будет принимать работу, пока непонятно.\n" to
            "КРАТКО:\n- Выбирали подрядчика для ремонта склада [1, 2]\n" +
            "РЕШЕНИЯ:\n- Подрядчиком выбрана компания «Вектор» [2, 3]\n" +
            "ВОПРОСЫ:\n- Непонятно, кто будет принимать работу [4]\n" +
            "ПОРУЧЕНИЯ:\n- Подготовить договор | Олег | до понедельника [3]"
        /** KV cache of the bundled model: prompt + answer must fit into it. */
        const val CONTEXT_TOKENS = 1280

        /** MediaPipe needs a real file path, so the bundled model is copied out of the APK once. */
        suspend fun create(context: Context): LlmFormatter = withContext(Dispatchers.IO) {
            val dst = File(context.filesDir, "llm/" + ModelAssets.LLM.substringAfterLast('/'))
            val expected = context.assets.openFd(ModelAssets.LLM).use { it.length }
            if (!dst.exists() || dst.length() != expected) {
                dst.parentFile!!.mkdirs()
                val tmp = File(dst.path + ".part")
                context.assets.open(ModelAssets.LLM).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                tmp.renameTo(dst)
            }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(dst.absolutePath)
                .setMaxTokens(CONTEXT_TOKENS)
                .setMaxTopK(1)
                .build()
            LlmFormatter(LlmInference.createFromOptions(context, options))
        }
    }
}

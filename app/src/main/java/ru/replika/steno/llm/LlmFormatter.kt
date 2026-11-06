package ru.replika.steno.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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

    private fun ask(system: String, user: String): String {
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .setTemperature(0f)
                .setRandomSeed(7)
                .build(),
        )
        try {
            session.addQueryChunk(
                "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n",
            )
            return session.generateResponse()
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
            ask(
                "Ты помощник, который кратко подводит итоги рабочей встречи по стенограмме. Пиши только то, что есть в стенограмме.",
                buildString {
                    appendLine("Стенограмма (в квадратных скобках номер реплики):")
                    append(numberedTranscript)
                    appendLine()
                    appendLine("Ответь строго в формате, каждый пункт с номерами реплик в квадратных скобках:")
                    appendLine("КРАТКО:")
                    appendLine("- о чём говорили [1, 2]")
                    appendLine("РЕШЕНИЯ:")
                    appendLine("- что решили [3]")
                    appendLine("ВОПРОСЫ:")
                    appendLine("- что осталось нерешённым [4]")
                    appendLine("ПОРУЧЕНИЯ:")
                    appendLine("- что сделать | кто должен сделать | срок [5]")
                    appendLine("Пиши только то, что сказано в стенограмме. Если исполнитель или срок не названы, ставь «-». Пустой раздел оставь пустым.")
                },
            )
        }
    }

    fun close() = llm.close()

    companion object {
        private val LINE = Regex("^(\\d+)[).:]\\s*(.+)$")
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

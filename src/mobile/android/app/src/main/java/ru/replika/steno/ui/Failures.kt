package ru.replika.steno.ui

import ru.replika.steno.AppGraph

/** Why processing stopped, read from the error text AppGraph.fail stores. */
enum class FailureKind { STOPPED, NO_SPEECH, CORRUPT, OTHER }

fun failureKind(error: String?): FailureKind {
    val e = error.orEmpty()
    return when {
        e == AppGraph.CANCELLED || e == ru.replika.steno.data.RecordingStore.INTERRUPTED ||
            e == ru.replika.steno.data.RecordingStore.LIVE_INTERRUPTED -> FailureKind.STOPPED
        e.startsWith("В записи не найдено речи") -> FailureKind.NO_SPEECH
        e.startsWith("Не удалось открыть файл") || e.startsWith("В файле нет звуковой дорожки") ||
            e.startsWith("Файл повреждён") || e.startsWith("Формат") -> FailureKind.CORRUPT
        else -> FailureKind.OTHER
    }
}

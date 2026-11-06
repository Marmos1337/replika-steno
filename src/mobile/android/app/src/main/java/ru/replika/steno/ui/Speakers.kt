package ru.replika.steno.ui

import ru.replika.steno.core.Recording

/*
 * What the interface calls a speaker. The stable id ("speaker_3") stays in the data and in the JSON
 * export; on screen, in copied text and in the TXT export a speaker without a user-given name is
 * «Участник 3».
 */

private val SPEAKER_ID = Regex("""\bspeaker_(\d+)\b""")

/** «Участник N» for "speaker_N"; any other id is shown as is. */
fun defaultSpeakerLabel(speakerId: String): String =
    speakerId.removePrefix("speaker_").toIntOrNull()?.takeIf { speakerId.startsWith("speaker_") }?.let { "Участник $it" } ?: speakerId

/** The user's name for the speaker, or the default «Участник N». */
fun Recording.label(speakerId: String): String = speakerNames[speakerId]?.takeIf { it.isNotBlank() } ?: defaultSpeakerLabel(speakerId)

/** Replaces raw speaker ids inside free text (summary points, owners) with their labels. */
fun Recording.labelText(text: String): String = SPEAKER_ID.replace(text) { label(it.value) }

package ru.replika.steno

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.replika.steno.core.AsrResult
import ru.replika.steno.core.Fragment
import ru.replika.steno.core.PreviousText
import ru.replika.steno.core.Recording
import ru.replika.steno.core.Span
import ru.replika.steno.core.TextGuard
import ru.replika.steno.core.Utterance
import ru.replika.steno.data.RecordingStore
import ru.replika.steno.export.TranscriptJson

class FragmentTest {

    private val utts = listOf(
        Utterance("speaker_1", 500, 3_000, "a"),
        Utterance("speaker_2", 3_400, 6_000, "b"),
        Utterance("speaker_1", 8_000, 9_500, "c"),
    )

    @Test fun rangeWithoutWideningIsTheUtteranceItself() {
        assertEquals(Span(3_400, 6_000), Fragment.range(utts, 1, widen = false, durationMs = 10_000))
    }

    @Test fun wideningStopsAtNeighboursAndRecordingEdges() {
        // 400 ms gap before (neighbour ends at 3000), 2 s gap after: only 1 s is taken there
        assertEquals(Span(3_000, 7_000), Fragment.range(utts, 1, widen = true, durationMs = 10_000))
        // first utterance: 500 ms of audio before it, then the start of the recording
        assertEquals(Span(0, 3_400), Fragment.range(utts, 0, widen = true, durationMs = 10_000))
        // last utterance: the recording ends 300 ms after it
        assertEquals(Span(7_000, 9_800), Fragment.range(utts, 2, widen = true, durationMs = 9_800))
        // unknown duration: only the neighbour limits apply
        assertEquals(Span(7_000, 10_500), Fragment.range(utts, 2, widen = true, durationMs = 0))
    }

    @Test fun wideningNeverReachesIntoAnOverlappingNeighbour() {
        val overlap = listOf(Utterance("speaker_1", 0, 2_100, "a"), Utterance("speaker_2", 2_000, 4_000, "b"))
        assertEquals(Span(2_000, 4_000), Fragment.range(overlap, 1, widen = true, durationMs = 4_000))
    }

    @Test fun rangeRejectsBrokenInput() {
        assertNull(Fragment.range(utts, 5, widen = false, durationMs = 10_000))
        assertNull(Fragment.range(listOf(Utterance("speaker_1", 500, 500, "x")), 0, widen = true, durationMs = 1_000))
        // bounds past the recording are clamped; nothing left -> null
        assertNull(Fragment.range(listOf(Utterance("speaker_1", 2_000, 3_000, "x")), 0, widen = false, durationMs = 1_000))
    }

    @Test fun applyKeepsSpeakerAndBoundsAndRemembersThePreviousText() {
        val u = Utterance("speaker_2", 3_400, 6_000, "Привет, мир.", rawText = "привет мир", speakerConfidence = 0.7f, edited = true, lang = "ru")
        val next = Fragment.apply(u, "hello world", "en")
        assertEquals("speaker_2", next.speakerId)
        assertEquals(3_400, next.startMs)
        assertEquals(6_000, next.endMs)
        assertEquals(0.7f, next.speakerConfidence)
        assertEquals("hello world", next.rawText)
        assertEquals("en", next.lang)
        assertFalse(next.edited)
        assertEquals(PreviousText("Привет, мир.", "привет мир", "ru", true), next.previous)
    }

    @Test fun undoSwapsSoNeitherVersionIsLost() {
        val u = Utterance("speaker_1", 0, 1_000, "Своя правка", rawText = "своя", edited = true)
        val replaced = Fragment.apply(u, "новый текст", "ru")
        val back = Fragment.undo(replaced)!!
        assertEquals("Своя правка", back.text)
        assertEquals("своя", back.rawText)
        assertTrue(back.edited)
        assertEquals("новый текст", back.previous?.rawText)
        // undo again = redo
        val again = Fragment.undo(back)!!
        assertEquals(replaced.text, again.text)
        assertEquals(replaced.rawText, again.rawText)
        assertNull(Fragment.undo(u))
    }

    @Test fun sameRecognitionIsNotAReplacement() {
        val u = Utterance("speaker_1", 0, 1_000, "Привет.", rawText = "привет", lang = "ru")
        assertTrue(Fragment.unchanged(u, "привет", "ru"))
        assertFalse(Fragment.unchanged(u, "привет", "en"))
        assertFalse(Fragment.unchanged(u.copy(edited = true), "привет", "ru"))
        assertTrue(Fragment.same(u, u.copy(text = "другое")))
        assertFalse(Fragment.same(u, u.copy(endMs = 1_001)))
    }

    @Test fun mainLangIsTheOneWithMostText() {
        assertEquals("en", Fragment.mainLang(listOf(AsrResult("да", "ru"), AsrResult("this is longer", "en"))))
        assertEquals("ru", Fragment.mainLang(emptyList()))
    }

    @Test fun previousTextSurvivesJsonAndOldFilesStillLoad() {
        val u = Fragment.apply(Utterance("speaker_1", 10, 900, "Было.", rawText = "было", edited = true), "стало", "ru")
        val rec = Recording("id", "t", "content://x", 1L, 1_000, utterances = listOf(u))
        val back = RecordingStore.fromJson(JSONObject(RecordingStore.toJson(rec).toString()))
        assertEquals(u, back.utterances.single())

        val old = RecordingStore.toJson(rec)
        old.getJSONArray("utterances").getJSONObject(0).remove("previous")
        assertNull(RecordingStore.fromJson(old).utterances.single().previous)
    }

    @Test fun exportNeverContainsThePreviousText() {
        val u = Fragment.apply(Utterance("speaker_1", 10, 900, "Было."), "стало", "ru")
        val json = TranscriptJson.encode(listOf(u))
        assertFalse(json.contains("Было"))
        val item = JSONObject(json).getJSONArray("transcript").getJSONObject(0)
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keySet())
        assertEquals(TextGuard.tidy("стало"), item.getString("text"))
    }
}

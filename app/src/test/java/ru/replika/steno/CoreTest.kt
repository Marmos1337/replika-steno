package ru.replika.steno

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.replika.steno.core.Segments
import ru.replika.steno.core.SpeakerTurn
import ru.replika.steno.core.TextGuard
import ru.replika.steno.core.Utterance
import ru.replika.steno.export.TranscriptJson

class CoreTest {

    @Test fun exportMatchesOrganizerSchema() {
        val json = TranscriptJson.encode(listOf(Utterance("speaker_1", 1250, 4380, "Добрый день!")))
        val root = JSONObject(json)
        assertEquals(setOf("transcript"), root.keySet())
        val item = root.getJSONArray("transcript").getJSONObject(0)
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keySet())
        assertEquals("speaker_1", item.getString("speaker_id"))
        assertEquals(1250, item.get("start_ms"))
        assertEquals(4380, item.get("end_ms"))
        assertEquals("Добрый день!", item.getString("text"))
    }

    @Test fun validateCatchesBadTimes() {
        val bad = listOf(
            Utterance("speaker_1", -1, 10, "a"),
            Utterance("speaker_2", 50, 50, "b"),
            Utterance("speaker_3", 10, 9_999, "c"),
            Utterance("Иван", 0, 10, "d"),
        )
        assertEquals(4, TranscriptJson.validate(bad, 5_000).size)
        assertTrue(TranscriptJson.validate(listOf(Utterance("speaker_1", 0, 10, "x")), 10).isEmpty())
    }

    @Test fun normalizeMergesClampsAndResolvesOverlaps() {
        val turns = listOf(
            SpeakerTurn(-100, 1_000, 3),
            SpeakerTurn(1_200, 2_000, 3),      // same speaker, small gap -> merged
            SpeakerTurn(1_900, 4_000, 7),      // overlaps -> start trimmed to 2000
            SpeakerTurn(4_100, 4_150, 3),      // sliver -> dropped
            SpeakerTurn(4_500, 9_000, 3),      // runs past duration -> clamped
        )
        val n = Segments.normalize(turns, durationMs = 8_000)
        assertEquals(listOf(SpeakerTurn(0, 2_000, 3), SpeakerTurn(2_000, 4_000, 7), SpeakerTurn(4_500, 8_000, 3)), n)
        for (i in 1 until n.size) assertTrue(n[i].startMs >= n[i - 1].endMs)
        val ids = Segments.speakerIds(n)
        assertEquals("speaker_1", ids[3]); assertEquals("speaker_2", ids[7])
    }

    @Test fun splitKeepsBoundsAndCoversTurn() {
        val sr = 16_000
        val samples = FloatArray(sr * 60) { if ((it / 800) % 7 == 0) 0f else 0.3f }
        val parts = Segments.splitForAsr(SpeakerTurn(1_000, 55_000, 0), samples, sr, maxMs = 20_000)
        assertEquals(1_000L, parts.first().startMs)
        assertEquals(55_000L, parts.last().endMs)
        for (p in parts) assertTrue(p.endMs - p.startMs in 1..20_000)
        for (i in 1 until parts.size) assertEquals(parts[i - 1].endMs, parts[i].startMs)
    }

    @Test fun guardRejectsInventedWords() {
        assertEquals("Добрый день, коллеги!", TextGuard.accept("добрый день коллеги", "Добрый день, коллеги!"))
        assertEquals("добрый день коллеги", TextGuard.accept("добрый день коллеги", "Добрый день, уважаемые коллеги!"))
        assertEquals("Ну, все.", TextGuard.accept("ну всё", "Ну, все."))
        assertTrue(TextGuard.sameWords("ну всё", "Ну, все."))
        assertFalse(TextGuard.sameWords("один два", "два один"))
        assertEquals("Привет.", TextGuard.tidy("  привет "))
    }
}

class SummaryParserTest {
    private val utts = listOf(
        ru.replika.steno.core.Utterance("speaker_1", 0, 1000, "Давайте обсудим релиз."),
        ru.replika.steno.core.Utterance("speaker_2", 1000, 2000, "Я подготовлю сборку к пятнице."),
    )

    @Test fun keepsOnlyCitedLinesAndTakesOwnerFromTranscript() {
        val s = ru.replika.steno.core.SummaryParser.parse(
            """
            ИТОГИ:
            - Обсудили релиз [1]
            - Без ссылки пункт
            - Ссылка мимо [9]
            ПОРУЧЕНИЯ:
            1. Подготовить сборку к пятнице [2]
            """.trimIndent(), utts,
        )
        assertEquals(1, s.points.size)
        assertEquals(listOf(0), s.points[0].utteranceIndices)
        assertEquals(1, s.actions.size)
        assertEquals("speaker_2", s.actions[0].speakerId)
        assertEquals("Подготовить сборку к пятнице", s.actions[0].text)
    }
}

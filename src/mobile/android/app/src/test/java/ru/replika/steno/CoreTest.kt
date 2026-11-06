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

class ClusteringTest {
    @Test fun groupsByDirectionWithoutPresetCount() {
        val a = floatArrayOf(1f, 0.05f, 0f); val a2 = floatArrayOf(0.95f, 0f, 0.1f)
        val b = floatArrayOf(0f, 1f, 0.05f); val b2 = floatArrayOf(0.1f, 0.9f, 0f)
        val c = floatArrayOf(0f, 0f, 1f)
        val r = ru.replika.steno.diar.Clustering.cluster(listOf(a, b, a2, b2, c, a), threshold = 0.6f)
        assertEquals(listOf(0, 1, 0, 1, 2, 0), r.labels.toList())
        assertTrue(r.confidence.all { it > 0.9f })
        val one = ru.replika.steno.diar.Clustering.cluster(listOf(a, b, c), threshold = 2f)
        assertEquals(setOf(0), one.labels.toSet())
    }
}

class LanguageRoutingTest {
    @Test fun latinShareDecidesRouting() {
        assertTrue(ru.replika.steno.asr.SherpaRecognizer.latinShare("Tom Dedyo Finish the Cortely Report") > 0.9f)
        assertTrue(ru.replika.steno.asr.SherpaRecognizer.latinShare("Добрый день, коллеги. Релиз в пятницу") < 0.1f)
        assertTrue(ru.replika.steno.asr.SherpaRecognizer.latinShare("Обновим API и SDK завтра") < 0.5f)
        assertEquals(0f, ru.replika.steno.asr.SherpaRecognizer.latinShare("123 ..."))
    }
}

class ActionGuardTest {
    private val utts = listOf(
        ru.replika.steno.core.Utterance("speaker_1", 0, 1000, "Ещё нужно подготовить описание для магазина приложений."),
        ru.replika.steno.core.Utterance("speaker_2", 1000, 2000, "Описание напишет Марина, я ей передам скриншоты в среду."),
    )

    @Test fun assigneeAndDueOnlyWhenSpoken() {
        val s = ru.replika.steno.core.SummaryParser.parse(
            """
            КРАТКО:
            - Обсудили описание для магазина [1, 2]
            РЕШЕНИЯ:
            - Описание пишет Марина [2]
            ВОПРОСЫ:
            ПОРУЧЕНИЯ:
            - Написать описание | Марина | - [2]
            - Передать скриншоты | Иван | в среду [2]
            - Подготовить описание | - | завтра [1]
            """.trimIndent(), utts,
        )
        assertEquals(2, s.points.size)
        assertEquals(ru.replika.steno.core.PointKind.DECISION, s.points[1].kind)
        assertEquals(3, s.actions.size)
        assertEquals("Марина", s.actions[0].assignee); assertEquals(null, s.actions[0].due)
        assertEquals(null, s.actions[1].assignee); assertEquals("в среду", s.actions[1].due)   // Иван never said
        assertEquals(null, s.actions[2].due)                                                    // «завтра» never said
    }
}

class MissingRefsTest {
    @Test fun linksByContentWhenModelForgetsNumbers() {
        val utts = listOf(
            ru.replika.steno.core.Utterance("speaker_1", 0, 1000, "Доступ выдам сегодня до вечера."),
            ru.replika.steno.core.Utterance("speaker_2", 1000, 2000, "Описание напишет Марина, я передам скриншоты в среду."),
        )
        val s = ru.replika.steno.core.SummaryParser.parse(
            "КРАТКО:\n- Описание напишет Марина, скриншоты передадут в среду.\n- Обсудили погоду на выходных.\nПОРУЧЕНИЯ:\n- Написать описание | Марина | -",
            utts,
        )
        assertEquals(listOf(1), s.points.single().utteranceIndices)   // invented point about the weather is dropped
        assertEquals(1, s.actions.single().utteranceIndex)
        assertEquals("Марина", s.actions.single().assignee)
    }
}

class ResamplerTest {
    @Test fun downsamplesToTargetLengthAndKeepsSignal() {
        val sr = 48_000
        val input = FloatArray(sr) { kotlin.math.sin(2 * Math.PI * 440 * it / sr).toFloat() * 0.5f }
        val out = ru.replika.steno.core.AudioDecoder.resample(input, sr, 16_000)
        assertTrue(kotlin.math.abs(out.size - 16_000) <= 2)
        val rms = kotlin.math.sqrt(out.drop(100).map { it * it }.average())
        assertTrue("rms $rms", rms in 0.30..0.37)   // a 440 Hz tone passes the anti-alias filter almost unchanged
        assertTrue(ru.replika.steno.core.AudioDecoder.resample(input, sr, sr) === input)
    }
}

class DurationTest {
    @Test fun zeroTailNeverExtendsDuration() {
        val a = ru.replika.steno.core.DecodedAudio(FloatArray(16_000 * 3), decodedSamples = 16_000 * 2, containerDurationMs = 2_500)
        assertEquals(2_000L, a.durationMs)
        val b = ru.replika.steno.core.DecodedAudio(FloatArray(16_000 * 3), decodedSamples = 16_000 * 3, containerDurationMs = 2_500)
        assertEquals(2_500L, b.durationMs)
    }
}

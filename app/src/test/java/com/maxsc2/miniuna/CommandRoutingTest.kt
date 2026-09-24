package com.maxsc2.miniuna

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRoutingTest {

    private val engine = LocalIntentEngine()

    @Test
    fun bareTimerAsksDurationInsteadOfDeadIntent() {
        for (phrase in listOf("поставь таймер", "таймер", "таймерчик", "включи таймер")) {
            val r = engine.classify(phrase)
            assertEquals(phrase, "SET_TIMER_SECONDS", r.intent)
            assertTrue(phrase, r.arguments["seconds"].isNullOrBlank())
        }
    }

    @Test
    fun timerWithDurationKeepsSeconds() {
        val r = engine.classify("поставь таймер на 5 минут")
        assertEquals("SET_TIMER", r.intent)
        assertEquals("300", r.arguments["seconds"])
    }

    @Test
    fun settingsBranchReachable() {
        val wifi = engine.classify("открой настройки")
        assertEquals("OPEN_SETTINGS", wifi.intent)
        val wifiSection = engine.classify("открой настройки wifi")
        assertEquals("OPEN_SETTINGS", wifiSection.intent)
        assertTrue(wifiSection.arguments["section"].orEmpty().contains("wifi", ignoreCase = true))
    }

    @Test
    fun alarmDismissNeverFallsBackToSet() {
        val r = engine.classify("выключи будильник")
        assertEquals("ALARM_DISMISS", r.intent)
    }

    @Test
    fun musicSlangUnderstood() {
        assertEquals("PLAY_MUSIC", engine.classify("подруби музон").intent)
        assertEquals("MEDIA_PAUSE", engine.classify("выключи музыку").intent)
        assertEquals("WIFI_PANEL", engine.classify("включи вайфай").intent)
    }

    @Test
    fun russianNumbers() {
        assertEquals(1, wordNumber("одна минута"))
        assertEquals(30, wordNumber("тридцать секунд"))
        assertEquals(100, wordNumber("сто процентов"))
    }

    @Test
    fun timeParsing() {
        assertEquals(7 to 0, parseTimeRu("в 7 утра"))
        assertEquals(19 to 30, parseTimeRu("19:30"))
        assertEquals(20 to 0, parseTimeRu("в 8 вечера"))
    }

    @Test
    fun daysParsing() {
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            parseDaysRu("по будням")
        )
        assertEquals(listOf(Calendar.SUNDAY, Calendar.SATURDAY), parseDaysRu("в выходные"))
    }

    @Test
    fun durationAndPercent() {
        assertEquals(300, parseDurationSec("пять минут"))
        assertEquals(7200, parseDurationSec("2 часа"))
        assertEquals(50, parsePercent("громкость на 50"))
    }

    @Test
    fun slotGaps() {
        assertEquals(
            listOf("time"),
            SlotHelper.missing("SET_ALARM", mapOf("hour" to "", "minutes" to ""))
        )
        assertEquals(
            listOf("duration"),
            SlotHelper.missing("SET_TIMER_SECONDS", mapOf("seconds" to ""))
        )
        assertEquals(
            emptyList<String>(),
            SlotHelper.missing("SET_ALARM", mapOf("hour" to "7", "minutes" to "0", "days" to "2,3,4,5,6"))
        )
    }

    @Test
    fun dismissFlow() {
        val bare = engine.classify("выключи будильник")
        assertEquals("ALARM_DISMISS", bare.intent)
        assertEquals(listOf("dismiss_time"), SlotHelper.missing(bare.intent, bare.arguments))
        val full = engine.classify("выключи будильник на 7")
        assertEquals("ALARM_DISMISS", full.intent)
        assertEquals("7", full.arguments["hour"])
        assertEquals(
            listOf("dismiss_days"),
            SlotHelper.missing(full.intent, full.arguments)
        )
    }

    @Test
    fun allDaysWord() {
        assertEquals(7, parseDaysRu("все")?.size)
    }

    @Test
    fun exactAlarmSettings() {
        assertEquals("ALARM_SETTINGS", engine.classify("разреши точные будильники").intent)
    }

    @Test
    fun briefingFlow() {
        val bare = engine.classify("включи утреннюю сводку")
        assertEquals("BRIEFING_SET", bare.intent)
        assertEquals(listOf("brief_time"), SlotHelper.missing(bare.intent, bare.arguments))
        val timed = engine.classify("рассказывай планы в 8 утра")
        assertEquals("BRIEFING_SET", timed.intent)
        assertEquals("8", timed.arguments["hour"])
        assertEquals(emptyList<String>(), SlotHelper.missing(timed.intent, timed.arguments))
        assertEquals("BRIEFING_OFF", engine.classify("выключи сводку").intent)
        // Календарный вопрос не угоняется сводкой.
        assertEquals("CALENDAR_TODAY", engine.classify("какие планы на сегодня").intent)
    }

    @Test
    fun notesSyncIntents() {
        assertEquals("NOTES_EXPORT", engine.classify("выгрузи заметки").intent)
        assertEquals("NOTES_EXPORT", engine.classify("поделись заметками").intent)
        assertEquals("NOTES_IMPORT", engine.classify("загрузи заметки").intent)
        assertEquals("NOTE_LIST", engine.classify("покажи заметки").intent)
    }

    @Test
    fun notesExportRoundTrip() {
        val notes = listOf(
            NotesStore.Note("Купить \"молоко\"\nи хлеб", 1727000000000L),
            NotesStore.Note("back\\slash", 0L)
        )
        val lists = mapOf("покупки" to listOf("сыр", "чай с \"бергамотом\""), "пустой" to emptyList())
        val json = buildNotesExport(notes, lists)
        val back = parseNotesExport(json)
        assertEquals(notes, back?.notes)
        assertEquals(lists, back?.lists)
    }

    @Test
    fun notesExportRejectsGarbage() {
        assertEquals(null, parseNotesExport(""))
        assertEquals(null, parseNotesExport("{oops"))
        assertEquals(null, parseNotesExport("{\"app\":\"other\",\"v\":1}"))
        assertEquals(null, parseNotesExport(buildNotesExport(emptyList(), emptyMap()) + "trailing"))
        // \uXXXX-escape чужого парсера тоже читаем.
        val dartStyle = "{\"app\":\"mini-una\",\"v\":1,\"notes\":[{\"text\":\"\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\",\"time\":1}],\"lists\":{}}"
        assertEquals("Привет", parseNotesExport(dartStyle)?.notes?.firstOrNull()?.text)
    }
}

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
}

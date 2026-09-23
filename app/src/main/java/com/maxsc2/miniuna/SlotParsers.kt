package com.maxsc2.miniuna

import java.util.Calendar
import java.util.Locale

val RU_NUM_0_100: Map<String, Int> = mapOf(
    "ноль" to 0, "один" to 1, "одна" to 1, "одно" to 1,
    "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
    "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
    "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
    "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
    "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
    "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
    "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80,
    "девяносто" to 90, "сто" to 100
)

private val WEEKDAYS = mapOf(
    "понедельник" to Calendar.MONDAY, "пн" to Calendar.MONDAY,
    "вторник" to Calendar.TUESDAY, "вт" to Calendar.TUESDAY,
    "среду" to Calendar.WEDNESDAY, "среда" to Calendar.WEDNESDAY, "ср" to Calendar.WEDNESDAY,
    "четверг" to Calendar.THURSDAY, "чт" to Calendar.THURSDAY,
    "пятницу" to Calendar.FRIDAY, "пятница" to Calendar.FRIDAY, "пт" to Calendar.FRIDAY,
    "субботу" to Calendar.SATURDAY, "суббота" to Calendar.SATURDAY, "сб" to Calendar.SATURDAY,
    "воскресенье" to Calendar.SUNDAY, "вс" to Calendar.SUNDAY
)

fun containsWord(text: String, word: String): Boolean =
    Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(word) + "([^\\p{L}\\p{N}]|$)").containsMatchIn(text)

fun wordNumber(text: String): Int? {
    val t = text.lowercase(Locale.getDefault()).replace('ё', 'е')
    val hit = RU_NUM_0_100.keys.firstOrNull { containsWord(t, it) }
    return hit?.let { RU_NUM_0_100[it] }
}

fun parseTimeRu(raw: String): Pair<Int, Int>? {
    val t = raw.lowercase(Locale.getDefault()).replace('ё', 'е')
    Regex("(\\d{1,2})\\s*[:.](\\d{2})").find(t)?.let {
        val h = it.groupValues[1].toInt()
        val m = it.groupValues[2].toInt()
        if (h in 0..23 && m in 0..59) return h to m
    }
    val digit = Regex("(\\d{1,2})").find(t)?.groupValues?.get(1)?.toIntOrNull()
    val word = wordNumber(t)
    val n = digit ?: word ?: return null
    if (n !in 0..23) return null
    val morning = listOf("утра", "утром").any { containsWord(t, it) }
    val day = listOf("дня", "днем", "день").any { containsWord(t, it) }
    val evening = listOf("вечера", "вечером").any { containsWord(t, it) }
    val night = listOf("ночи", "ночью").any { containsWord(t, it) }
    var h = n
    if ((day || evening) && h < 12) h += 12
    if (night && h == 12) h = 0
    if (!morning && !day && !evening && !night) {
        // Без уточнений оставляем как есть: 7 = 7:00.
    }
    return h to 0
}

fun parseDaysRu(raw: String): List<Int>? {
    val t = raw.lowercase(Locale.getDefault()).replace('ё', 'е')
    if (listOf("будни", "будням", "буднях", "рабочие дни").any { containsWord(t, it) }) {
        return listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    }
    if (listOf("выходные", "выходным", "выходных").any { containsWord(t, it) }) {
        return listOf(Calendar.SUNDAY, Calendar.SATURDAY)
    }
    if (listOf("каждый день", "ежедневно", "всегда", "все дни").any { t.contains(it) }) {
        return listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
    }
    val days = WEEKDAYS.filterKeys { containsWord(t, it) }.values.distinct()
    return days.ifEmpty { null }
}

fun parseDurationSec(raw: String): Int? {
    val t = raw.lowercase(Locale.getDefault()).replace('ё', 'е')
    Regex("(\\d+)\\s*(секунд(?:а|ы)?|сек|с|минут(?:а|ы)?|мин|час(?:а|ов)?|ч)").find(t)?.let {
        val v = it.groupValues[1].toInt()
        return when (it.groupValues[2]) {
            "с", "сек", "секунда", "секунды", "секунд" -> v
            "ч", "час", "часа", "часов" -> v * 3600
            else -> v * 60
        }
    }
    val unit = when {
        t.contains("час") || Regex("(^|[^\\p{L}\\p{N}])ч([^\\p{L}\\p{N}]|$)").containsMatchIn(t) -> 3600
        t.contains("секунд") || t.contains("сек") -> 1
        t.contains("минут") || t.contains("мин") -> 60
        else -> return null
    }
    val w = wordNumber(t) ?: return null
    return w * unit
}

fun parsePercent(raw: String): Int? {
    val t = raw.lowercase(Locale.getDefault()).replace('ё', 'е')
    Regex("(\\d{1,3})\\s*%?").find(t)?.let {
        return it.groupValues[1].toInt().coerceIn(0, 100)
    }
    return wordNumber(t)?.coerceIn(0, 100)
}

data class PendingSlot(
    val intent: String,
    val args: MutableMap<String, String>,
    var missing: List<String>,
    var retries: Int = 0
)

object SlotHelper {
    fun missing(intent: String, args: Map<String, String>): List<String> = when (intent) {
        "SET_ALARM" -> {
            val out = mutableListOf<String>()
            if (args["hour"].isNullOrBlank() || args["minutes"].isNullOrBlank()) out.add("time")
            else if (args["days"].isNullOrBlank()) out.add("days")
            out
        }
        "SET_TIMER_SECONDS", "SET_TIMER_MINUTES", "SET_TIMER_HOURS" ->
            if (args.values.all { it.isBlank() }) listOf("duration") else emptyList()
        "REMIND" ->
            if (args["seconds"].isNullOrBlank()) listOf("delay") else emptyList()
        "ALARM_DISMISS" -> {
            val out = mutableListOf<String>()
            if (args["hour"].isNullOrBlank() || args["minutes"].isNullOrBlank()) out.add("dismiss_time")
            out
        }
        else -> emptyList()
    }

    fun question(slot: String): String = when (slot) {
        "time" -> "Во сколько поставить будильник?"
        "days" -> "На какие дни поставить: будни, выходные или каждый день?"
        "duration" -> "На сколько поставить таймер?"
        "delay" -> "Через сколько напомнить?"
        "dismiss_time" -> "Во сколько будильник выключить?"
        else -> "Уточни, пожалуйста."
    }

    fun fill(p: PendingSlot, answer: String): Boolean {
        var progressed = false
        if ("time" in p.missing) {
            parseTimeRu(answer)?.let { (h, m) ->
                p.args["hour"] = h.toString()
                p.args["minutes"] = m.toString()
                p.missing -= "time"
                progressed = true
            }
        }
        if ("days" in p.missing) {
            parseDaysRu(answer)?.let { days ->
                p.args["days"] = days.joinToString(",")
                p.missing -= "days"
                progressed = true
            }
            if ("time" in p.missing) {
                parseTimeRu(answer)?.let { (h, m) ->
                    p.args["hour"] = h.toString()
                    p.args["minutes"] = m.toString()
                    p.missing -= "time"
                    progressed = true
                }
            }
        }
        if ("duration" in p.missing) {
            parseDurationSec(answer)?.let { s ->
                p.args["seconds"] = s.toString()
                p.missing -= "duration"
                progressed = true
            }
        }
        if ("dismiss_time" in p.missing) {
            parseTimeRu(answer)?.let { (h, m) ->
                p.args["hour"] = h.toString()
                p.args["minutes"] = m.toString()
                p.missing -= "dismiss_time"
                progressed = true
            }
        }
        return progressed
    }

    fun isCancel(text: String): Boolean {
        val t = text.trim().lowercase(Locale.getDefault())
        return t == "отмена" || t == "отмени" || t == "стоп" || t == "хватит" ||
            t == "не надо" || t == "забудь" || t.startsWith("отмена ")
    }
}

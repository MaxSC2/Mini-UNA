package com.maxsc2.miniuna

import java.util.Locale

class LocalIntentEngine : IntentEngine {
    override fun classify(text: String): IntentResult {
        val raw = text.trim()
        val t = raw.lowercase(Locale.getDefault())

        return when {
            t.contains("время") || t.contains("который час") ->
                IntentResult("GET_TIME", 0.99f)
            t.contains("дата") || t.contains("какое сегодня число") || t.contains("число сегодня") ->
                IntentResult("GET_DATE", 0.99f)
            t.startsWith("открой ") || t.startsWith("запусти ") || t.startsWith("открой приложение ") ->
                IntentResult("OPEN_APP", 0.90f, mapOf("app" to raw.substringAfter(" ").trim()))
            t.startsWith("настройки") || t.contains("открой настройки") ->
                IntentResult("OPEN_SETTINGS", 0.92f, mapOf("section" to raw.replaceFirst(Regex("(?i)^.*?настройки"), "").trim()))
            t.startsWith("ищи ") || t.startsWith("найди ") || t.startsWith("поищи ") ->
                IntentResult("OPEN_WEB", 0.92f, mapOf("target" to raw.substringAfter(" ").trim()))
            t.startsWith("открой сайт ") || t.startsWith("зайди на ") ->
                IntentResult("OPEN_WEB", 0.94f, mapOf("target" to raw.substringAfter(" ").trim()))
            t.startsWith("посчитай ") || t.startsWith("вычисли ") ->
                IntentResult("CALCULATE", 0.96f, mapOf("expression" to raw.substringAfter(" ").trim()))
            t.startsWith("заметка ") || t.startsWith("запиши ") ->
                IntentResult("SAVE_NOTE", 0.95f, mapOf("note" to raw.substringAfter(" ").trim()))
            t.contains("таймер") || t.startsWith("поставь таймер") ->
                IntentResult("SET_TIMER", 0.92f, mapOf("seconds" to parseTimerSeconds(raw).toString()))
            t.contains("громче") || t.contains("увеличь громкость") ->
                IntentResult("VOLUME_UP", 0.95f)
            t.contains("тише") || t.contains("уменьши громкость") ->
                IntentResult("VOLUME_DOWN", 0.95f)
            t.contains("без звука") || t.contains("убери звук") ->
                IntentResult("VOLUME_MUTE", 0.95f)
            t == "назад" || t == "вернись назад" ->
                IntentResult("BACK", 0.96f)
            t == "домой" || t == "на главный экран" ->
                IntentResult("HOME", 0.96f)
            t.contains("недавние приложения") || t.contains("последние приложения") ->
                IntentResult("RECENTS", 0.95f)
            t.contains("заблокируй экран") || t.contains("заблокируй телефон") ->
                IntentResult("LOCK_SCREEN", 0.94f)
            t.contains("специальных возможностей") || t.contains("доступности") ->
                IntentResult("OPEN_ACCESSIBILITY_SETTINGS", 0.95f)
            t.contains("что ты умеешь") || t.contains("помощь") ->
                IntentResult("HELP", 0.98f)
            else -> IntentResult("UNKNOWN", 0.0f)
        }
    }

    private fun parseTimerSeconds(text: String): Int {
        val number = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val lower = text.lowercase(Locale.getDefault())
        return when {
            "час" in lower -> number * 3600
            "мин" in lower -> number * 60
            else -> number
        }
    }
}

package com.maxsc2.miniuna

import java.util.Locale

class LocalIntentEngine : IntentEngine {
    override fun classify(text: String): IntentResult {
        val raw = text.trim()
        val t = raw.lowercase(Locale.getDefault())

        return when {
            t == "привет" || t.startsWith("привет ") || t.contains("доброе утро") ||
                t.contains("добрый день") || t.contains("добрый вечер") ||
                t == "здравствуй" || t == "здравствуйте" ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "greeting"))
            t.contains("как дела") ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "howareyou"))
            t.contains("кто ты") || t.contains("расскажи о себе") ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "who"))
            t == "спасибо" || t.startsWith("спасибо ") ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "thanks"))
            t.contains("спокойной ночи") ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "night"))
            t == "пока" || t.contains("до связи") ->
                IntentResult("PERSONA", 0.98f, mapOf("key" to "bye"))
            t.contains("слушай юну") || t.contains("включи прослушку") || t.contains("начни слушать") ->
                IntentResult("LISTEN_ON", 0.95f)
            t.contains("хватит слушать") || t.contains("выключи прослушку") || t.contains("не слушай") ->
                IntentResult("LISTEN_OFF", 0.95f)
            t.contains("время") || t.contains("который час") ->
                IntentResult("GET_TIME", 0.99f)
            t.contains("дата") || t.contains("какое сегодня число") || t.contains("число сегодня") ->
                IntentResult("GET_DATE", 0.99f)
            t.startsWith("включи плейлист ") ->
                IntentResult(
                    "PLAY_MUSIC", 0.93f,
                    mapOf("app" to "NeonWave", "playlist" to raw.substringAfter("включи плейлист ").trim())
                )
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
            t.contains("будильник") || t.contains("будильника") ->
                if (t.contains("отмени") || t.contains("удали") || t.contains("выключи") || t.contains("убери")) {
                    IntentResult("ALARM_CANCEL", 0.93f)
                } else {
                    val args = mutableMapOf<String, String>()
                    parseTimeRu(t)?.let { (h, m) ->
                        args["hour"] = h.toString()
                        args["minutes"] = m.toString()
                    }
                    parseDaysRu(t)?.let { days ->
                        args["days"] = days.joinToString(",")
                    }
                    IntentResult("SET_ALARM", 0.93f, args)
                }
            t.contains("таймер") || t.startsWith("поставь таймер") ->
                if (t == "таймер" || t == "таймерчик" || t == "поставь таймер" || t == "включи таймер") {
                    IntentResult("SET_TIMER_SECONDS", 0.92f, mapOf("seconds" to ""))
                } else {
                    IntentResult("SET_TIMER", 0.92f, mapOf("seconds" to parseTimerSeconds(raw).toString()))
                }
            (t.contains("громкость") || t.contains("звук")) && parsePercent(raw) != null ->
                IntentResult("VOLUME_PERCENT", 0.93f, mapOf("percent" to parsePercent(raw).toString()))
            t.contains("громче") || t.contains("увеличь громкость") ->
                IntentResult("VOLUME_UP", 0.95f)
            t.contains("тише") || t.contains("уменьши громкость") ->
                IntentResult("VOLUME_DOWN", 0.95f)
            t.contains("без звука") || t.contains("убери звук") ->
                IntentResult("VOLUME_MUTE", 0.95f)
            t.startsWith("напомни ") ->
                IntentResult("REMIND", 0.9f, mapOf("seconds" to "", "text" to raw.substringAfter("напомни ").trim()))
            t.contains("что на экране") || t.contains("прочитай экран") ->
                IntentResult("SCREEN_READ", 0.95f)
            t.contains("прочитай уведомления") || t.contains("покажи уведомления") ||
                t.contains("что в уведомлениях") || t == "уведомления" ->
                IntentResult("NOTIF_READ", 0.95f)
            t.contains("не следи за ") ->
                IntentResult("NOTIF_UNFOLLOW", 0.9f, mapOf("app" to raw.substringAfter("не следи за ").trim()))
            t.contains("следи за ") ->
                IntentResult("NOTIF_FOLLOW", 0.9f, mapOf("app" to raw.substringAfter("следи за ").trim()))
            t.contains("на ютубе") || t.contains("на ютьюбе") || t.contains("в ютубе") ->
                IntentResult("YOUTUBE_SEARCH", 0.9f, mapOf("query" to raw))
            (t.startsWith("отправь ") || t.startsWith("напиши ")) && (t.contains("телеграм") || t.contains("телегу")) ->
                IntentResult("TG_SHARE", 0.9f, mapOf("text" to raw))
            t.contains("включи музыку") || t.contains("поставь музыку") ||
                t == "музыка" || t == "музыку" ->
                IntentResult("PLAY_MUSIC", 0.95f)
            t.contains("следующий трек") || t.contains("следующая песня") ->
                IntentResult("MEDIA_NEXT", 0.95f)
            t.contains("предыдущий трек") || t.contains("предыдущая песня") ->
                IntentResult("MEDIA_PREV", 0.95f)
            t == "пауза" || t.contains("на паузу") || t.contains("продолжи") ->
                IntentResult("MEDIA_TOGGLE", 0.95f)
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

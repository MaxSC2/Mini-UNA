package com.maxsc2.miniuna

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolRegistry(
    private val context: Context,
    private val saveNote: (String) -> Unit
) {
    private val android = AndroidTools(context)

    fun execute(result: IntentResult): String? = when (result.intent) {
        "GET_TIME" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()).let { "Сейчас " + it + "." }
        "GET_DATE" -> SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date()).let { "Сегодня " + it + "." }
        "OPEN_APP" -> android.openApp(result.arguments["app"].orEmpty())
        "OPEN_SETTINGS" -> android.openSettings(result.arguments["section"].orEmpty())
        "OPEN_WEB" -> android.openWeb(result.arguments["target"].orEmpty())
        "CALCULATE" -> android.calculate(result.arguments["expression"].orEmpty())
        "SAVE_NOTE" -> {
            val note = result.arguments["note"].orEmpty()
            saveNote(note)
            android.noteAdd(note)
        }
        "NOTE_LIST" -> android.noteList()
        "NOTE_CLEAR" -> android.noteClear()
        "NOTES_EXPORT" -> android.exportNotes()
        "NOTES_IMPORT" -> {
            (context as? MainActivity)?.openNotesImport()
            "Выбери файл выгрузки заметок."
        }
        "SHOP_ADD" -> android.shopAdd(result.arguments["list"].orEmpty(), result.arguments["item"].orEmpty())
        "SHOP_LIST" -> android.shopList(result.arguments["list"].orEmpty())
        "SHOP_REMOVE" -> android.shopRemove(result.arguments["list"].orEmpty(), result.arguments["item"].orEmpty())
        "CALENDAR_TODAY" -> android.calendarDay(0)
        "CALENDAR_TOMORROW" -> android.calendarDay(1)
        "CALENDAR_NEXT" -> android.calendarNext()
        "SET_TIMER_SECONDS" -> android.setTimer(result.arguments["seconds"]?.toIntOrNull() ?: 60)
        "SET_TIMER" -> android.setTimer(result.arguments["seconds"]?.toIntOrNull() ?: 60)
        "SET_ALARM" -> {
            val days = result.arguments["days"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }
            android.setAlarm(
                result.arguments["hour"]?.toIntOrNull() ?: 7,
                result.arguments["minutes"]?.toIntOrNull() ?: 0,
                days
            )
        }
        "ALARM_DISMISS" -> {
            val days = result.arguments["days"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            android.dismissAlarm(
                result.arguments["hour"]?.toIntOrNull(),
                result.arguments["minutes"]?.toIntOrNull(),
                result.arguments["snooze_minutes"]?.toIntOrNull(),
                days
            )
        }
        "TIMER_CANCEL" -> android.dismissTimer()
        "WIFI_PANEL" -> android.connectivityPanel("wifi")
        "BT_PANEL" -> android.connectivityPanel("bluetooth")
        "MEDIA_PAUSE" -> android.pauseMusic()
        "VOLUME_PERCENT" -> android.setVolumePercent(result.arguments["percent"]?.toIntOrNull() ?: 50)
        "LISTEN_ON" -> android.listenOn()
        "LISTEN_OFF" -> android.listenOff()
        "PERSONA" -> persona(result.arguments["key"].orEmpty())
        "SET_TIMER_MINUTES" -> android.setTimer((result.arguments["minutes"]?.toIntOrNull() ?: 1) * 60)
        "SET_TIMER_HOURS" -> android.setTimer((result.arguments["hours"]?.toIntOrNull() ?: 1) * 3600)
        "VOLUME_UP" -> android.volumeUp()
        "VOLUME_DOWN" -> android.volumeDown()
        "VOLUME_MUTE" -> android.volumeMute()
        "PLAY_MUSIC" -> android.playMusic(result.arguments["app"], result.arguments["playlist"])
        "MEDIA_TOGGLE" -> android.mediaToggle()
        "MEDIA_NEXT" -> android.mediaNext()
        "MEDIA_PREV" -> android.mediaPrevious()
        "SCREEN_READ" -> android.screenText()
        "NOTIF_READ" -> android.recentNotifications()
        "NOTIF_REPLY" -> android.replyNotification(result.arguments["app"].orEmpty(), result.arguments["text"].orEmpty())
        "SCREEN_TAP" -> android.screenTap(result.arguments["text"].orEmpty())
        "NOTIF_FOLLOW" -> android.followNotifications(result.arguments["app"].orEmpty())
        "NOTIF_UNFOLLOW" -> android.unfollowNotifications(result.arguments["app"].orEmpty())
        "YOUTUBE_SEARCH" -> android.youtubeSearch(result.arguments["query"].orEmpty())
        "TG_SHARE" -> android.tgShare(result.arguments["text"].orEmpty())
        "REMIND" -> android.remind(result.arguments["text"].orEmpty(), result.arguments["seconds"]?.toIntOrNull() ?: 3600)
        "ALARM_SETTINGS" -> if (android.openExactAlarmSettings()) {
            "Открываю настройки точных будильников."
        } else {
            "На этом Android точные будильники включены по умолчанию."
        }
        "BRIEFING_SET" -> {
            val h = result.arguments["hour"]?.toIntOrNull()
            val m = result.arguments["minutes"]?.toIntOrNull()
            if (h == null || m == null) "Во сколько рассказывать сводку?" else android.setBriefing(h, m)
        }
        "BRIEFING_OFF" -> android.stopBriefing()
        "BACK" -> android.accessibility("BACK")
        "HOME" -> android.accessibility("HOME")
        "RECENTS" -> android.accessibility("RECENTS")
        "LOCK_SCREEN" -> android.accessibility("LOCK_SCREEN")
        "OPEN_ACCESSIBILITY_SETTINGS" -> android.openSettings("accessibility")
        else -> null
    }

    private fun persona(key: String): String = personaLine(key, currentHour())

    companion object {
        fun currentHour(): Int {
            return try {
                java.text.SimpleDateFormat("H", Locale.getDefault()).format(Date()).toInt()
            } catch (_: Throwable) {
                12
            }
        }

        fun personaCacheKey(key: String, hour: Int = currentHour()): String = when (key) {
            "greeting" -> "persona_greeting_" + when (hour) {
                in 5..11 -> "morning"
                in 12..17 -> "day"
                in 18..22 -> "evening"
                else -> "night"
            }
            "howareyou" -> "persona_how_" + (if (hour % 2 == 0) "even" else "odd")
            else -> "persona_$key"
        }

        fun personaLine(key: String, hour: Int = currentHour()): String {
            return when (key) {
                "greeting" -> when (hour) {
                    in 5..11 -> "Доброе утро! Я на связи."
                    in 12..17 -> "Добрый день! Слушаю."
                    in 18..22 -> "Добрый вечер! Чем помочь?"
                    else -> "Привет! Я тут, даже ночью."
                }
                "who" -> "Я Юна — твой локальный помощник. Живу прямо в телефоне, интернет мне не нужен."
                "howareyou" -> if (hour % 2 == 0) {
                    "Отлично, все системы в норме. А у тебя как?"
                } else {
                    "Хорошо! Готова помогать. Что делаем?"
                }
                "thanks" -> "Всегда пожалуйста!"
                "bye" -> "До связи!"
                "night" -> "Спокойной ночи!"
                else -> "Привет!"
            }
        }
    }
}

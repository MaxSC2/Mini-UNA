package com.maxsc2.miniuna

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolRegistry(
    context: Context,
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
            saveNote(result.arguments["note"].orEmpty())
            "Записала. Заметка сохранена на устройстве."
        }
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
        "ALARM_CANCEL" -> "Будильники отключаются вручную: открой приложение Часы."
        "VOLUME_PERCENT" -> android.setVolumePercent(result.arguments["percent"]?.toIntOrNull() ?: 50)
        "LISTEN_ON" -> android.listenOn()
        "LISTEN_OFF" -> android.listenOff()
        "PERSONA" -> persona(result.arguments["key"].orEmpty())
        "SET_TIMER_MINUTES" -> android.setTimer((result.arguments["minutes"]?.toIntOrNull() ?: 1) * 60)
        "SET_TIMER_HOURS" -> android.setTimer((result.arguments["hours"]?.toIntOrNull() ?: 1) * 3600)
        "VOLUME_UP" -> android.volumeUp()
        "VOLUME_DOWN" -> android.volumeDown()
        "VOLUME_MUTE" -> android.volumeMute()
        "PLAY_MUSIC" -> android.playMusic()
        "MEDIA_TOGGLE" -> android.mediaToggle()
        "MEDIA_NEXT" -> android.mediaNext()
        "MEDIA_PREV" -> android.mediaPrevious()
        "SCREEN_READ" -> android.screenText()
        "NOTIF_READ" -> android.recentNotifications()
        "NOTIF_FOLLOW" -> android.followNotifications(result.arguments["app"].orEmpty())
        "NOTIF_UNFOLLOW" -> android.unfollowNotifications(result.arguments["app"].orEmpty())
        "YOUTUBE_SEARCH" -> android.youtubeSearch(result.arguments["query"].orEmpty())
        "TG_SHARE" -> android.tgShare(result.arguments["text"].orEmpty())
        "PLAY_MUSIC" -> android.playMusic()
        "MEDIA_TOGGLE" -> android.mediaToggle()
        "MEDIA_NEXT" -> android.mediaNext()
        "MEDIA_PREV" -> android.mediaPrevious()
        "BACK" -> android.accessibility("BACK")
        "HOME" -> android.accessibility("HOME")
        "RECENTS" -> android.accessibility("RECENTS")
        "LOCK_SCREEN" -> android.accessibility("LOCK_SCREEN")
        "OPEN_ACCESSIBILITY_SETTINGS" -> android.openSettings("accessibility")
        else -> null
    }

    private fun persona(key: String): String {
        val hour = try {
            java.text.SimpleDateFormat("H", Locale.getDefault()).format(Date()).toInt()
        } catch (_: Throwable) {
            12
        }
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

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
}

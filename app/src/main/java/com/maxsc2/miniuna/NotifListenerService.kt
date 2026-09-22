package com.maxsc2.miniuna

import android.speech.tts.TextToSpeech
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class NotifListenerService : NotificationListenerService() {

    data class Item(
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long
    )

    companion object {
        @Volatile
        var instance: NotifListenerService? = null
            private set

        private const val MAX_ITEMS = 20
        private val recent = ArrayDeque<Item>()

        @Synchronized
        fun push(item: Item) {
            recent.removeAll { it.packageName == item.packageName && it.title == item.title && it.text == item.text }
            recent.addFirst(item)
            while (recent.size > MAX_ITEMS) recent.removeLast()
        }

        @Synchronized
        fun snapshot(): List<Item> = recent.toList()
    }

    private var tts: TextToSpeech? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().trim()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        push(Item(sbn.packageName, title, text, System.currentTimeMillis()))
        announceIfFavorite(sbn.packageName, title, text)
    }

    private fun announceIfFavorite(pkg: String, title: String, text: String) {
        val prefs = try {
            getSharedPreferences("mini_una", MODE_PRIVATE)
        } catch (_: Throwable) {
            return
        }
        if (!prefs.getBoolean("voice", true)) return
        val favs = prefs.getStringSet("notif_fav", emptySet()).orEmpty()
        if (!favs.contains(pkg)) return
        val msg = ("Новое: " + title + ". " + text).trim().take(300)
        if (msg.length < 8) return
        try {
            var engine = tts
            if (engine == null) {
                engine = TextToSpeech(this) { code ->
                    if (code == TextToSpeech.SUCCESS) {
                        tts?.language = Locale("ru", "RU")
                    }
                }
                tts = engine
            }
            engine?.speak(msg, TextToSpeech.QUEUE_ADD, null, "mini_una_notif")
        } catch (_: Throwable) {
        }
    }
}

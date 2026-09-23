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

        private val lastByPkg = HashMap<String, StatusBarNotification>()

        @Synchronized
        fun remember(pkg: String, sbn: StatusBarNotification) {
            lastByPkg[pkg] = sbn
        }

        @Synchronized
        fun lastFor(pkg: String): StatusBarNotification? = lastByPkg[pkg]

        @Synchronized
        fun lastWithReply(): StatusBarNotification? {
            for ((_, sbn) in lastByPkg) {
                if (sbn.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true) {
                    return sbn
                }
            }
            return lastByPkg.values.firstOrNull()
        }

        var appContext: android.content.Context? = null
            private set

        fun replyTo(pkg: String?, text: String): Boolean {
            val sbn = if (pkg.isNullOrBlank()) lastWithReply()
            else lastFor(pkg) ?: lastWithReply() ?: return false
            val ctx = appContext ?: return false
            val actions = sbn?.notification?.actions ?: emptyArray()
            for (action in actions) {
                val inputs = action.remoteInputs ?: continue
                if (inputs.isEmpty()) continue
                return try {
                    val intent = android.content.Intent()
                    val bundle = android.os.Bundle()
                    inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
                    android.app.RemoteInput.addResultsToIntent(inputs, intent, bundle)
                    action.actionIntent.send(ctx, 0, intent)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }
    }

    private var tts: TextToSpeech? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        appContext = applicationContext
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        appContext = null
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
        remember(sbn.packageName, sbn)
        if (!announceIfFavorite(sbn.packageName, title, text)) {
            announceIfNew(sbn.packageName, title, text)
        }
    }

    private fun announceIfNew(pkg: String, title: String, text: String) {
        val prefs = try {
            getSharedPreferences("mini_una", MODE_PRIVATE)
        } catch (_: Throwable) {
            return
        }
        if (!prefs.getBoolean("voice", true)) return
        val known = prefs.getStringSet("notif_known", emptySet()).orEmpty()
        val key = pkg + "|" + title
        if (key in known) return
        prefs.edit().putStringSet("notif_known", known + key).apply()
        speak("Новый контакт: " + title + ". " + text)
    }

    private fun announceIfFavorite(pkg: String, title: String, text: String): Boolean {
        val prefs = try {
            getSharedPreferences("mini_una", MODE_PRIVATE)
        } catch (_: Throwable) {
            return false
        }
        if (!prefs.getBoolean("voice", true)) return false
        val favs = prefs.getStringSet("notif_fav", emptySet()).orEmpty()
        if (!favs.contains(pkg)) return false
        val msg = ("Новое: " + title + ". " + text).trim().take(300)
        if (msg.length < 8) return true
        speak(msg)
        return true
    }

    private fun speak(msg: String) {
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

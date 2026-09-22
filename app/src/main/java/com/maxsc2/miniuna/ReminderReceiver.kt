package com.maxsc2.miniuna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty().ifBlank { "Напоминание!" }
        val id = intent.getIntExtra("id", 0)
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("reminders", "Напоминания Юны", NotificationManager.IMPORTANCE_HIGH)
            )
            val notif = NotificationCompat.Builder(context, "reminders")
                .setContentTitle("Юна напоминает")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(9000 + id, notif)
        } catch (_: Throwable) {
        }
        try {
            val prefs = context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("voice", true)) return
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { code ->
                if (code == TextToSpeech.SUCCESS) {
                    engine?.language = Locale("ru", "RU")
                }
            }
            val tts = engine
            // Даём движку секунду на инициализацию, затем озвучиваем и гасим.
            Thread {
                try {
                    Thread.sleep(1200)
                    tts.speak("Напоминаю: $text", TextToSpeech.QUEUE_FLUSH, null, "mini_una_remind")
                    Thread.sleep(4000)
                } catch (_: Throwable) {
                }
                try {
                    tts.shutdown()
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
        } catch (_: Throwable) {
        }
    }
}

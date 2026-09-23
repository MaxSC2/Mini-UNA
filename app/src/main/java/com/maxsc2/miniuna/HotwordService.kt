package com.maxsc2.miniuna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class HotwordService : Service() {

    companion object {
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "hotword"
        private const val NOTIF_ID = 42
        private val KEYWORDS = listOf("юна", "уна")
    }

    @Volatile private var listening = false
    private var recognizer: SpeechRecognizer? = null
    private var restartDelayMs = 1000L
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        listening = true
        restartDelayMs = 1000L
        listenOnce()
        return START_STICKY
    }

    override fun onDestroy() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
        super.onDestroy()
    }

    private fun createChannel() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Юна слушает", NotificationManager.IMPORTANCE_LOW)
            )
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundNotif() {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Юна слушает")
            .setContentText("Скажи «Юна» и команду")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            Log.w("MiniUNA-Hotword", "startForeground: ${e.message}")
            stopSelf()
        }
    }

    private fun listenOnce() {
        if (!listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w("MiniUNA-Hotword", "no recognizer on device")
            stopSelf()
            return
        }
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                restartDelayMs = 1000L
                handleResults(results)
                scheduleNext()
            }

            override fun onError(error: Int) {
                Log.i("MiniUNA-Hotword", "recognizer error $error")
                scheduleNext()
            }
        })
        try {
            rec.startListening(intent)
        } catch (_: Throwable) {
            scheduleNext()
        }
    }

    private fun handleResults(results: Bundle?) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (text in texts) {
            val cmd = extractCommand(text) ?: continue
            deliver(cmd)
            return
        }
    }

    private fun extractCommand(text: String): String? {
        val t = text.trim().lowercase(Locale.getDefault())
        for (kw in KEYWORDS) {
            val i = t.indexOf(kw)
            if (i < 0) continue
            if (i > 0 && t[i - 1].isLetter()) continue
            return t.substring(i + kw.length).trim(' ', ',', '.', '!', '?', '…')
        }
        return null
    }

    private fun deliver(command: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                // Пустая команда = просто позвали: приложение ответит «я тут».
                putExtra(EXTRA_COMMAND, command)
            } ?: return
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun scheduleNext() {
        if (!listening) return
        val delay = restartDelayMs
        restartDelayMs = (restartDelayMs * 2).coerceAtMost(10_000L)
        try {
            handler.postDelayed({
                if (listening) listenOnce()
            }, delay)
        } catch (_: Throwable) {
        }
    }
}

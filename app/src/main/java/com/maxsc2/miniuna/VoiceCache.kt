package com.maxsc2.miniuna

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File

object VoiceCache {
    val ACK = listOf("Я тут.", "Слушаю.", "Ага?", "Что такое?", "На связи.")

    private const val VERSION = 1

    fun table(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ACK.forEachIndexed { i, text -> out.add("ack_$i" to text) }
        for (h in listOf(8, 14, 19, 2)) {
            out.add(ToolRegistry.personaCacheKey("greeting", h) to ToolRegistry.personaLine("greeting", h))
        }
        for (h in listOf(10, 11)) {
            out.add(ToolRegistry.personaCacheKey("howareyou", h) to ToolRegistry.personaLine("howareyou", h))
        }
        for (key in listOf("who", "thanks", "bye", "night")) {
            out.add(ToolRegistry.personaCacheKey(key, 12) to ToolRegistry.personaLine(key, 12))
        }
        for (slot in listOf("time", "days", "duration", "delay")) {
            out.add("slot_$slot" to SlotHelper.question(slot))
        }
        return out
    }

    fun fileFor(context: Context, key: String, ratePercent: Int): File =
        File(context.filesDir, "tts_${key}_r${ratePercent}_v$VERSION.wav")

    fun ratePercent(context: Context): Int {
        return try {
            (context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
                .getFloat("tts_rate", 1.1f) * 100).toInt().coerceIn(50, 150)
        } catch (_: Throwable) {
            110
        }
    }

    fun refresh(context: Context, tts: TextToSpeech) {
        try {
            val rate = ratePercent(context)
            val want = table().map { (key, _) -> fileFor(context, key, rate).name }.toSet()
            context.filesDir.listFiles { f -> f.name.startsWith("tts_") && f.name.endsWith(".wav") }
                ?.forEach { if (it.name !in want) try { it.delete() } catch (_: Throwable) {} }
            for ((key, text) in table()) {
                val file = fileFor(context, key, rate)
                if (file.exists() && file.length() > 0) continue
                try {
                    val rc = tts.synthesizeToFile(text, null, file, "cache_$key")
                    if (rc != TextToSpeech.SUCCESS) {
                        try {
                            file.delete()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.w("MiniUNA-TTS", "cache refresh: ${e.message}")
        }
    }

    fun play(context: Context, file: File): Boolean {
        return try {
            val mp = MediaPlayer()
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Throwable) {
                }
            }
            mp.setOnErrorListener { it, _, _ ->
                try {
                    it.release()
                } catch (_: Throwable) {
                }
                true
            }
            mp.prepare()
            mp.start()
            true
        } catch (_: Throwable) {
            false
        }
    }
}

package com.maxsc2.miniuna

import android.content.Context
import com.cactus.CactusJNI
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class NeedleEngine(
    private val context: Context,
    private val fallback: IntentEngine = LocalIntentEngine()
) : IntentEngine {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.70f
        private const val BUFFER_SIZE = 1024 * 1024
    }

    private var model: Long = 0L
    private var nativeReady = false
    private var toolsJson: String? = null

    override fun classify(text: String): IntentResult {
        if (!ensureNativeModel()) return fallback.classify(text)

        return try {
            val system = JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "Device: Android phone. Use only declared tools. Never invent missing arguments."
                )
            val user = JSONObject()
                .put("role", "user")
                .put("content", text)
            val messages = "[" + system + "," + user + "]"

            val buffer = ByteArray(BUFFER_SIZE)
            val rc = CactusJNI.nativeComplete(
                model,
                messages,
                buffer,
                null,
                toolsJson,
                null,
                null
            )

            if (rc < 0) {
                nativeReady = false
                return fallback.classify(text)
            }

            val end = buffer.indexOf(0)
            val json = String(
                if (end >= 0) buffer.copyOf(end) else buffer,
                StandardCharsets.UTF_8
            ).trim()

            parseNeedleResult(json) ?: fallback.classify(text)
        } catch (_: Throwable) {
            fallback.classify(text)
        }
    }

    fun isNativeReady(): Boolean = nativeReady && model != 0L

    private fun parseNeedleResult(json: String): IntentResult? {
        // Needle 3 can return a completed "respond" turn. Mini-UNA is intentionally
        // a command router here, so only function calls become executable intents.
        // Text answers belong to the higher-level assistant layer, not this router.

        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return null
        }

        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val reasoning = root.optString("reasoning", "")
        val calls = root.optJSONArray("function_calls")
        val suppressed = root.optJSONArray("suppressed_calls")

        if (calls != null && calls.length() > 0) {
            return parseCall(calls.getJSONObject(0), confidence, reasoning)
                .copy(requiresConfirmation = confidence < CONFIDENCE_THRESHOLD)
        }

        if (suppressed != null && suppressed.length() > 0) {
            return parseCall(suppressed.getJSONObject(0), confidence, reasoning)
                .copy(requiresConfirmation = true)
        }

        return IntentResult("UNKNOWN", confidence, reasoning = reasoning)
    }

    private fun parseCall(
        call: JSONObject,
        confidence: Float,
        reasoning: String
    ): IntentResult {
        val name = call.optString("name")
        val args = call.optJSONObject("arguments")
        val map = mutableMapOf<String, String>()

        if (args != null) {
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = args.opt(key)
                if (value != JSONObject.NULL) map[key] = value.toString()
            }
        }

        val intent = when (name) {
            "get_time" -> "GET_TIME"
            "get_date" -> "GET_DATE"
            "open_app" -> "OPEN_APP"
            "open_settings" -> "OPEN_SETTINGS"
            "open_web" -> "OPEN_WEB"
            "calculate" -> "CALCULATE"
            "save_note" -> "SAVE_NOTE"
            "set_timer_seconds" -> "SET_TIMER_SECONDS"
            "set_timer_minutes" -> "SET_TIMER_MINUTES"
            "set_timer_hours" -> "SET_TIMER_HOURS"
            "volume_up" -> "VOLUME_UP"
            "volume_down" -> "VOLUME_DOWN"
            "volume_mute" -> "VOLUME_MUTE"
            "go_back" -> "BACK"
            "go_home" -> "HOME"
            "open_recents" -> "RECENTS"
            "lock_screen" -> "LOCK_SCREEN"
            "open_accessibility_settings" -> "OPEN_ACCESSIBILITY_SETTINGS"
            else -> "UNKNOWN"
        }

        return IntentResult(
            intent = intent,
            confidence = confidence,
            arguments = map,
            reasoning = reasoning
        )
    }

    private fun ensureNativeModel(): Boolean {
        if (nativeReady && model != 0L) return true

        return try {
            if (toolsJson == null) {
                toolsJson = context.assets.open("needle_tools.json")
                    .bufferedReader()
                    .use { it.readText() }
            }

            val modelFile = File(context.filesDir, "needle3.cact")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                context.assets.open("needle3.cact").use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }

            model = CactusJNI.nativeInit(modelFile.absolutePath, null, false)
            nativeReady = model != 0L
            nativeReady
        } catch (_: Throwable) {
            nativeReady = false
            false
        }
    }

    fun close() {
        if (model != 0L) {
            try {
                CactusJNI.nativeDestroy(model)
            } catch (_: Throwable) {
            }
        }
        model = 0L
        nativeReady = false
    }
}

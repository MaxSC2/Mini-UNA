package com.maxsc2.miniuna

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

class NeedleServer(private val context: Context) {

    companion object {
        const val PORT = 18123
        private const val BINARY_NAME = "needle"
        private const val NATIVE_LIB_NAME = "libneedle.so"
        private const val MODEL_NAME = "needle3.cact"
        private const val TOOLS_NAME = "needle_tools.json"
        private const val START_TIMEOUT_MS = 90_000L
    }

    @Volatile var lastError: String = "not started"
        private set

    private val lock = Any()
    private val callLock = Any()
    private var process: Process? = null
    private var binaryPath: String? = null

    fun isAlive(): Boolean {
        synchronized(lock) {
            val p = process ?: return false
            return try {
                p.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
    }

    fun ensureStarted(): Boolean {
        synchronized(lock) {
            if (isAlive() && isPortOpen()) return true
            stopLocked()
            if (!stageFiles()) return false
            val bin = binaryPath ?: run {
                lastError = "нет файлов needle в APK"
                Log.w("MiniUNA-Needle", lastError)
                return false
            }
            return try {
                val dir = context.filesDir
                val proc = ProcessBuilder(
                    bin,
                    "--model", File(dir, MODEL_NAME).absolutePath,
                    "--tools", File(dir, TOOLS_NAME).absolutePath,
                    "--serve", "--port", PORT.toString()
                )
                    .directory(dir)
                    .redirectOutput(File(dir, "needle.log"))
                    .redirectErrorStream(true)
                    .start()
                process = proc
                if (!waitPortOpen()) {
                    lastError = "сервер needle не открыл порт $PORT"
                    Log.w("MiniUNA-Needle", lastError)
                    stopLocked()
                    return false
                }
                lastError = "ok"
                Log.i("MiniUNA-Needle", "needle server ready on port $PORT")
                true
            } catch (e: Throwable) {
                lastError = "не стартовал needle: ${e.message}"
                Log.w("MiniUNA-Needle", lastError)
                stopLocked()
                false
            }
        }
    }

    private fun stageFiles(): Boolean {
        return try {
            val bin = resolveBinary()
            if (bin == null) {
                // resolveBinary уже выставил точный lastError.
                return false
            }
            binaryPath = bin.absolutePath
            val dir = context.filesDir
            // Tools: always refresh (tiny).
            context.assets.open(TOOLS_NAME).use { input ->
                FileOutputStream(File(dir, TOOLS_NAME)).use { output -> input.copyTo(output) }
            }
            // Weights: copy once, they are big.
            val model = File(dir, MODEL_NAME)
            if (!model.exists() || model.length() == 0L) {
                context.assets.open(MODEL_NAME).use { input ->
                    FileOutputStream(model).use { output -> input.copyTo(output) }
                }
            }
            true
        } catch (e: Throwable) {
            lastError = "нет файлов needle в APK: ${e.message}"
            Log.w("MiniUNA-Needle", lastError)
            false
        }
    }

    private fun resolveBinary(): File? {
        // Шаг 1. Native lib dir: установщик сохраняет exec-права, это самый
        // надёжный источник (некоторые прошивки режут exec из filesDir).
        try {
            val native = File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB_NAME)
            if (native.exists()) {
                try {
                    native.setExecutable(true)
                } catch (_: Throwable) {
                }
                if (native.canExecute()) return native
                Log.w("MiniUNA-Needle", "libneedle.so не запускаемый: ${native.absolutePath}")
            }
        } catch (_: Throwable) {
        }
        // Шаг 2. Копия из assets в filesDir (нужен chmod; может не сработать).
        return try {
            val bin = File(context.filesDir, BINARY_NAME)
            context.assets.open(BINARY_NAME).use { input ->
                FileOutputStream(bin).use { output -> input.copyTo(output) }
            }
            try {
                bin.setExecutable(true)
            } catch (_: Throwable) {
            }
            if (bin.canExecute()) {
                bin
            } else {
                lastError = "бинарь needle не запускаемый (canExecute=false): " +
                    "прошивка запрещает exec из данных приложения"
                Log.w("MiniUNA-Needle", lastError)
                null
            }
        } catch (e: Throwable) {
            lastError = "нет файлов needle в APK: ${e.message}"
            Log.w("MiniUNA-Needle", lastError)
            null
        }
    }

    private fun isPortOpen(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun waitPortOpen(): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                val p = process
                if (p != null) {
                    try {
                        p.exitValue()
                        lastError = "процесс needle завершился при старте"
                        return false
                    } catch (_: IllegalThreadStateException) {
                    }
                }
            }
            if (isPortOpen()) return true
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isPortOpen()
    }

    fun complete(input: String): String? {
        synchronized(callLock) {
            synchronized(lock) {
                if (!isAlive()) {
                    lastError = "сервер needle не запущен"
                    return null
                }
            }
            var conn: HttpURLConnection? = null
            return try {
                val payload = JSONObject().put("input", input).toString()
                    .toByteArray(StandardCharsets.UTF_8)
                conn = (URL("http://127.0.0.1:$PORT/complete").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 120_000
                }
                conn.outputStream.use { it.write(payload) }
                if (conn.responseCode != 200) {
                    lastError = "needle http ${conn.responseCode}"
                    Log.w("MiniUNA-Needle", lastError)
                    return null
                }
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }.trim().ifBlank { null }
            } catch (e: Throwable) {
                lastError = "запрос к needle: ${e.message}"
                Log.w("MiniUNA-Needle", lastError)
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
    }
}

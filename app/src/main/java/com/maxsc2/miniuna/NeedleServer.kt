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
        // Лестница модели 2..20: меньше глубина — заметно быстрее инференс.
        // Значение — в настройках (needle_depth), применяется при (ре)старте.
        private const val DEFAULT_DEPTH = 12
    }

    private fun depth(): Int {
        return try {
            context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
                .getInt("needle_depth", DEFAULT_DEPTH).coerceIn(2, 20)
        } catch (_: Throwable) {
            DEFAULT_DEPTH
        }
    }

    fun restart(): Boolean {
        synchronized(lock) { stopLocked() }
        return ensureStarted()
    }

    @Volatile var lastError: String = "not started"
        private set

    private val lock = Any()
    private val callLock = Any()
    private val startLock = Any()
    private var process: Process? = null
    private var binaryPath: String? = null
    @Volatile private var abortGen = 0

    fun abort() {
        abortGen++
        try {
            synchronized(lock) { stopLocked() }
        } catch (_: Throwable) {
        }
    }

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
        synchronized(startLock) {
            synchronized(lock) {
                if (isAlive() && isPortOpen()) return true
                stopLocked()
            }
            val myGen = abortGen
            if (!stageFiles()) return false
            val bin = synchronized(lock) { binaryPath } ?: run {
                lastError = "нет файлов needle в APK"
                Log.w("MiniUNA-Needle", lastError)
                return false
            }
            val proc = try {
                val dir = context.filesDir
                ProcessBuilder(
                    bin,
                    "--model", File(dir, MODEL_NAME).absolutePath,
                    "--tools", File(dir, TOOLS_NAME).absolutePath,
                    "--depth", depth().toString(),
                    "--serve", "--port", PORT.toString()
                )
                    .directory(dir)
                    .redirectOutput(File(dir, "needle.log"))
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Throwable) {
                lastError = "не стартовал needle: ${e.message}"
                Log.w("MiniUNA-Needle", lastError)
                return false
            }
            synchronized(lock) {
                if (myGen != abortGen) {
                    try {
                        proc.destroy()
                    } catch (_: Throwable) {
                    }
                    return false
                }
                process = proc
            }
            if (!waitPortOpen(myGen)) {
                synchronized(lock) {
                    if (myGen == abortGen) stopLocked()
                }
                return false
            }
            lastError = "ok"
            Log.i("MiniUNA-Needle", "needle server ready on port $PORT")
            return true
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
            } else {
                Log.w("MiniUNA-Needle", "libneedle.so нет в nativeLibraryDir: ${native.absolutePath}")
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
        if (tryHost("127.0.0.1")) return true
        return tryHost("::1")
    }

    private fun tryHost(host: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, PORT), 1500)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun logTail(): String {
        return try {
            val log = File(context.filesDir, "needle.log")
            if (!log.exists()) return " (лог пуст)"
            val bytes = log.readBytes()
            val tail = String(bytes.takeLast(1500).toByteArray(), StandardCharsets.UTF_8)
            " лог: " + tail.replace(Regex("\\s+"), " ").trim().take(600)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun waitPortOpen(myGen: Int): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (myGen != abortGen) return false
            val p = synchronized(lock) { process } ?: return false
            var exitedCode: Int? = null
            val alive = try {
                exitedCode = p.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
            if (!alive) {
                lastError = "процесс needle завершился при старте (код $exitedCode)" + logTail()
                Log.w("MiniUNA-Needle", lastError)
                return false
            }
            if (isPortOpen()) return true
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return false
            }
        }
        lastError = "сервер needle не открыл порт $PORT" + logTail()
        Log.w("MiniUNA-Needle", lastError)
        return false
    }

    fun complete(input: String): String? {
        synchronized(callLock) {
            synchronized(lock) {
                if (!isAlive()) {
                    lastError = "сервер needle не запущен"
                    return null
                }
            }
            val answer = post("http://127.0.0.1:$PORT/complete", input)
                ?: post("http://[::1]:$PORT/complete", input)
            // Сервер помнит контекст между запросами (stateless-архитектуре
            // приложения это мешает: следующий вызов галлюцинирует из истории).
            // Сбрасываем сессию в фоне, ответу не мешаем.
            if (answer != null) resetAsync()
            return answer
        }
    }

    private fun resetAsync() {
        Thread {
            for (url in listOf("http://127.0.0.1:$PORT/reset", "http://[::1]:$PORT/reset")) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 3000
                        readTimeout = 5000
                    }
                    conn.outputStream.use { }
                    if (conn.responseCode == 200) break
                } catch (_: Throwable) {
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun post(url: String, input: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val payload = JSONObject().put("input", input).toString()
                .toByteArray(StandardCharsets.UTF_8)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
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

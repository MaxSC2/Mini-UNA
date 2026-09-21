package com.cactus

object CactusJNI {
    init { System.loadLibrary("cactus_engine") }
    @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeComplete(handle: Long, messagesJson: String, responseBuffer: ByteArray, optionsJson: String?, toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): Int
    @JvmStatic external fun nativeGetLastError(): String
    @JvmStatic external fun nativeSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?)
    @JvmStatic external fun nativeSetAppId(appId: String)
    @JvmStatic external fun nativeTelemetryShutdown()
}

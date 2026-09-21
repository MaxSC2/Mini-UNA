package com.maxsc2.miniuna

/**
 * Adapter boundary for Cactus Needle.
 *
 * The verified Needle runtime/model will be connected here.
 * UI and tools stay independent from the model implementation.
 */
class NeedleEngine(
    private val fallback: IntentEngine = LocalIntentEngine()
) : IntentEngine {
    override fun classify(text: String): IntentResult {
        // TODO: invoke the bundled Needle model.
        // Contract: bounded intent id + confidence + arguments.
        return fallback.classify(text)
    }
}

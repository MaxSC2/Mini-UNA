package com.maxsc2.miniuna

object SafetyPolicy {
    private val confirmationIntents = setOf(
        "SEND_MESSAGE", "MAKE_CALL", "DELETE_FILE", "INSTALL_APP", "PURCHASE"
    )

    enum class Decision { ALLOW, CONFIRM, BLOCK }

    fun decide(result: IntentResult): Decision {
        if (result.intent == "UNKNOWN") return Decision.BLOCK
        if (result.intent in confirmationIntents) return Decision.CONFIRM
        if (result.requiresConfirmation) return Decision.CONFIRM
        return Decision.ALLOW
    }
}

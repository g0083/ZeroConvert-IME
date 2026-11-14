package com.zeroconvert.ime.assist

import com.zeroconvert.ime.engine.WindowsEngine
import org.json.JSONArray
import org.json.JSONObject

/** Transport for the same Rust policy used directly by the Windows client. */
fun interface AssistPolicyTransport {
    fun request(request: JSONObject): JSONObject
}

object SharedAssistPolicy {
    val native = AssistPolicyTransport { WindowsEngine.assistPolicy(it) }

    data class RequestPlan(val correction: Boolean, val prediction: Boolean)

    fun requestPlan(correctionEnabled: Boolean, predictionEnabled: Boolean, applied: Boolean,
                    reading: String, baselineReading: String = "",
                    policy: AssistPolicyTransport = native): RequestPlan {
        val reply = policy.request(JSONObject().put("op", "plan")
            .put("correctionEnabled", correctionEnabled).put("predictionEnabled", predictionEnabled)
            .put("applied", applied).put("reading", reading).put("baselineReading", baselineReading))
        return RequestPlan(reply.getBoolean("correction"), reply.getBoolean("prediction"))
    }

    fun candidateId(type: AiAssistType): Long = when (type) {
        AiAssistType.TYPO_CORRECTION -> 999901L
        AiAssistType.PREDICTION -> 999902L
    }

    fun choose(correction: AiAssistResult?, prediction: AiAssistResult?, displayed: AiAssistResult? = null,
               policy: AssistPolicyTransport = native): AiAssistResult? {
        val results = listOfNotNull(correction, prediction)
        if (results.isEmpty()) return displayed
        val choices = JSONArray()
        results.forEach { choices.put(JSONArray().put(it.relevance).put(candidateId(it.type))) }
        val reply = policy.request(JSONObject().put("op", "choose").put("choices", choices)
            .put("displayed", displayed?.let { candidateId(it.type) }))
        if (reply.isNull("selected")) return null
        val id = reply.getLong("selected")
        return displayed?.takeIf { candidateId(it.type) == id }
            ?: results.firstOrNull { candidateId(it.type) == id }
    }

    fun ready(predictionDone: Boolean, correctionDone: Boolean, automatic: Boolean, elapsedMs: Long,
              policy: AssistPolicyTransport = native): Boolean = policy.request(JSONObject()
        .put("op", "ready").put("predictionDone", predictionDone).put("correctionDone", correctionDone)
        .put("automatic", automatic).put("elapsedMs", elapsedMs)).getBoolean("ready")

    fun presentationWaitMs(policy: AssistPolicyTransport = native): Long =
        policy.request(JSONObject().put("op", "ready")).getLong("waitMs")

    fun adoptionAction(result: AiAssistResult, explicit: Boolean, automatic: Boolean, applied: Boolean,
                       policy: AssistPolicyTransport = native): String = policy.request(JSONObject()
        .put("op", "adopt").put("id", candidateId(result.type)).put("explicit", explicit)
        .put("automatic", automatic).put("applied", applied)).getString("action")
}

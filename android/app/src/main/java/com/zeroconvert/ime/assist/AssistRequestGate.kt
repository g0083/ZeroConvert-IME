package com.zeroconvert.ime.assist

/** An edit followed by identical retyping is still a different request generation. */
class AssistRequestGate {
    data class Key(
        val reading: String,
        val cursor: Int,
        val context: String,
        val profile: String,
        val modelRevision: Long,
        val assistEnabled: Boolean,
        val autoCorrectionEnabled: Boolean = true,
        val predictionModel: String = "",
        val inputType: Int = 0,
        val imeOptions: Int = 0,
        val rawInput: List<com.zeroconvert.ime.azookey.input.ComposingText.InputElement> = emptyList(),
        val vocabularyRevision: Long = 0,
        val conversionRange: Boolean = false,
        val learningMode: String = "READ_WRITE",
        val dictionaryCorrectionEnabled: Boolean = false
    )
    data class Ticket(val generation: Long, val key: Key)
    private var generation = 0L
    fun invalidate() { generation++ }
    fun capture(key: Key) = Ticket(generation, key)
    fun isGenerationCurrent(ticket: Ticket) = ticket.generation == generation
    fun isCurrent(ticket: Ticket, key: Key) = ticket.generation == generation && ticket.key == key
}

package com.zeroconvert.ime

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Horizontal handedness alignment for one-handed mobile typing.
 */
enum class OneHandedMode {
    OFF,
    LEFT,
    RIGHT;

    companion object {
        fun fromName(value: String?): OneHandedMode =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * Width handling for one-handed typing: the keyboard keeps this share of the full width and the
 * side rail takes the rest.
 */
object OneHandedLayout {
    const val MIN_WIDTH_PERCENT = 60
    const val DEFAULT_WIDTH_PERCENT = 82
    const val MAX_WIDTH_PERCENT = 95

    fun clampWidthPercent(percent: Int): Int =
        percent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)

    fun keyboardWeight(percent: Int): Float = clampWidthPercent(percent) / 100f

    fun railWeight(percent: Int): Float = 1f - keyboardWeight(percent)
}

enum class FlickObservationKind(val wireCode: Char) {
    FLICK('f'),
    TOGGLE_FIRST('t'),
    TOGGLE_CYCLE('c'),
    FLICK_MOD('m'),
    TOGGLE_MOD('d')
}

data class FlickTouchObservation(
    val kind: FlickObservationKind,
    val nx: Float,
    val ny: Float,
    val dxNorm: Float,
    val dyNorm: Float
)

/**
 * Records a key press together with normalized proximity weights `[0f..1f]`
 * to neighboring keys and optional continuous 12-key gesture observations.
 */
data class TouchKeyBias(
    val typedChar: Char,
    val nearbyChars: List<Pair<Char, Float>> = emptyList(),
    val flickObservation: FlickTouchObservation? = null
)

/**
 * Predicts likely next characters `[0f..1f]` for bounded QWERTY hit-area expansion.
 */
object NextKeyPredictor {
    private val romajiPrefixWeights: Map<String, Map<Char, Float>> by lazy { buildRomajiPrefixMap() }

    private fun buildRomajiPrefixMap(): Map<String, Map<Char, Float>> {
        val rules = listOf(
            // Core gojuon + voiced + p-row
            "a" to 1.0f, "i" to 1.0f, "u" to 1.0f, "e" to 1.0f, "o" to 1.0f,
            "ka" to 1.0f, "ki" to 1.0f, "ku" to 1.0f, "ke" to 1.0f, "ko" to 1.0f,
            "ga" to 1.0f, "gi" to 1.0f, "gu" to 1.0f, "ge" to 1.0f, "go" to 1.0f,
            "sa" to 1.0f, "si" to 0.9f, "su" to 1.0f, "se" to 1.0f, "so" to 1.0f, "shi" to 1.0f,
            "za" to 1.0f, "zi" to 0.85f, "zu" to 1.0f, "ze" to 1.0f, "zo" to 1.0f, "ji" to 1.0f,
            "ta" to 1.0f, "ti" to 0.9f, "tu" to 0.9f, "te" to 1.0f, "to" to 1.0f, "chi" to 1.0f, "tsu" to 1.0f,
            "da" to 1.0f, "di" to 0.8f, "du" to 0.8f, "de" to 1.0f, "do" to 1.0f,
            "na" to 1.0f, "ni" to 1.0f, "nu" to 1.0f, "ne" to 1.0f, "no" to 1.0f, "nn" to 1.0f,
            "ha" to 1.0f, "hi" to 1.0f, "hu" to 0.9f, "fu" to 1.0f, "he" to 1.0f, "ho" to 1.0f,
            "ba" to 1.0f, "bi" to 1.0f, "bu" to 1.0f, "be" to 1.0f, "bo" to 1.0f,
            "pa" to 1.0f, "pi" to 1.0f, "pu" to 1.0f, "pe" to 1.0f, "po" to 1.0f,
            "ma" to 1.0f, "mi" to 1.0f, "mu" to 1.0f, "me" to 1.0f, "mo" to 1.0f,
            "ya" to 1.0f, "yu" to 1.0f, "yo" to 1.0f,
            "ra" to 1.0f, "ri" to 1.0f, "ru" to 1.0f, "re" to 1.0f, "ro" to 1.0f,
            "wa" to 1.0f, "wo" to 0.95f,
            // Palatalized syllables
            "kya" to 0.85f, "kyu" to 0.85f, "kyo" to 0.85f, "kye" to 0.55f,
            "gya" to 0.8f, "gyu" to 0.8f, "gyo" to 0.8f, "gye" to 0.5f,
            "sha" to 0.9f, "shu" to 0.9f, "sho" to 0.9f, "she" to 0.65f,
            "sya" to 0.8f, "syu" to 0.8f, "syo" to 0.8f, "sye" to 0.5f,
            "ja" to 0.95f, "ju" to 0.95f, "jo" to 0.95f, "je" to 0.65f,
            "jya" to 0.7f, "jyi" to 0.45f, "jyu" to 0.7f, "jye" to 0.45f, "jyo" to 0.7f,
            "zya" to 0.7f, "zyu" to 0.7f, "zyo" to 0.7f, "zye" to 0.45f,
            "cha" to 0.9f, "chu" to 0.9f, "cho" to 0.9f, "che" to 0.65f,
            "tya" to 0.75f, "tyi" to 0.5f, "tyu" to 0.75f, "tye" to 0.5f, "tyo" to 0.75f,
            "cya" to 0.6f, "cyi" to 0.4f, "cyu" to 0.6f, "cye" to 0.4f, "cyo" to 0.6f,
            "nya" to 0.8f, "nyi" to 0.45f, "nyu" to 0.8f, "nye" to 0.45f, "nyo" to 0.8f,
            "hya" to 0.8f, "hyi" to 0.45f, "hyu" to 0.8f, "hye" to 0.45f, "hyo" to 0.8f,
            "bya" to 0.8f, "byi" to 0.45f, "byu" to 0.8f, "bye" to 0.45f, "byo" to 0.8f,
            "pya" to 0.8f, "pyi" to 0.45f, "pyu" to 0.8f, "pye" to 0.45f, "pyo" to 0.8f,
            "mya" to 0.75f, "myi" to 0.4f, "myu" to 0.75f, "mye" to 0.4f, "myo" to 0.75f,
            "rya" to 0.8f, "ryi" to 0.4f, "ryu" to 0.8f, "rye" to 0.4f, "ryo" to 0.8f,
            // Extended / small kana romaji
            "fa" to 0.85f, "fi" to 0.85f, "fe" to 0.85f, "fo" to 0.85f,
            "fya" to 0.55f, "fyu" to 0.55f, "fyo" to 0.55f,
            "va" to 0.65f, "vi" to 0.65f, "vu" to 0.75f, "ve" to 0.65f, "vo" to 0.65f,
            "wi" to 0.75f, "we" to 0.75f, "wu" to 0.5f, "wyi" to 0.35f, "wye" to 0.35f,
            "wha" to 0.5f, "whi" to 0.5f, "whu" to 0.5f, "whe" to 0.5f, "who" to 0.5f,
            "thi" to 0.75f, "tha" to 0.5f, "thu" to 0.5f, "the" to 0.5f, "tho" to 0.5f,
            "dhi" to 0.75f, "dha" to 0.5f, "dhu" to 0.5f, "dhe" to 0.5f, "dho" to 0.5f,
            "dyi" to 0.45f, "dya" to 0.45f, "dyu" to 0.45f, "dye" to 0.45f, "dyo" to 0.45f,
            "twu" to 0.6f, "twa" to 0.45f, "twi" to 0.45f, "twe" to 0.45f, "two" to 0.45f,
            "dwu" to 0.6f, "dwa" to 0.45f, "dwi" to 0.45f, "dwe" to 0.45f, "dwo" to 0.45f,
            "tsa" to 0.6f, "tsi" to 0.6f, "tse" to 0.6f, "tso" to 0.6f,
            "swa" to 0.45f, "swi" to 0.45f, "swu" to 0.45f, "swe" to 0.45f, "swo" to 0.45f,
            "kwa" to 0.5f, "kwi" to 0.45f, "kwu" to 0.4f, "kwe" to 0.45f, "kwo" to 0.45f,
            "gwa" to 0.45f, "gwi" to 0.4f, "gwu" to 0.4f, "gwe" to 0.4f, "gwo" to 0.4f,
            "qa" to 0.55f, "qi" to 0.55f, "qu" to 0.55f, "qe" to 0.55f, "qo" to 0.55f,
            "qwa" to 0.4f, "qwi" to 0.4f, "qwu" to 0.4f, "qwe" to 0.4f, "qwo" to 0.4f,
            "hwa" to 0.45f, "hwi" to 0.45f, "hwe" to 0.45f, "hwo" to 0.45f,
            "fwa" to 0.45f, "fwi" to 0.45f, "fwu" to 0.45f, "fwe" to 0.45f, "fwo" to 0.45f,
            "ca" to 0.55f, "ci" to 0.55f, "cu" to 0.55f, "ce" to 0.55f, "co" to 0.55f,
            "ye" to 0.55f,
            "xa" to 0.7f, "xi" to 0.7f, "xu" to 0.7f, "xe" to 0.7f, "xo" to 0.7f,
            "xya" to 0.75f, "xyu" to 0.75f, "xyo" to 0.75f, "xtu" to 0.8f, "xtsu" to 0.75f,
            "xwa" to 0.5f, "xka" to 0.45f, "xke" to 0.45f, "xn" to 0.5f,
            "la" to 0.7f, "li" to 0.7f, "lu" to 0.7f, "le" to 0.7f, "lo" to 0.7f,
            "lya" to 0.75f, "lyu" to 0.75f, "lyo" to 0.75f, "ltu" to 0.8f, "ltsu" to 0.75f,
            "lwa" to 0.5f, "lka" to 0.45f, "lke" to 0.45f,
            "kk" to 0.55f, "ss" to 0.55f, "tt" to 0.55f, "pp" to 0.55f,
            "bb" to 0.4f, "cc" to 0.4f, "dd" to 0.4f, "ff" to 0.4f, "gg" to 0.4f,
            "hh" to 0.4f, "jj" to 0.4f, "ll" to 0.35f, "mm" to 0.4f, "qq" to 0.35f,
            "rr" to 0.4f, "vv" to 0.35f, "ww" to 0.4f, "xx" to 0.35f, "yy" to 0.35f, "zz" to 0.4f,
            "zh" to 0.3f, "zj" to 0.3f, "zk" to 0.3f, "zl" to 0.3f
        )
        val raw = LinkedHashMap<String, LinkedHashMap<Char, Float>>()
        for ((rule, weight) in rules) {
            for (len in 1 until rule.length) {
                val prefix = rule.substring(0, len)
                val nextCh = rule[len]
                val bucket = raw.getOrPut(prefix) { LinkedHashMap() }
                val prev = bucket[nextCh] ?: 0f
                if (weight > prev) bucket[nextCh] = weight
            }
        }
        return raw.mapValues { (_, map) ->
            val maxW = map.values.maxOrNull()?.takeIf { it > 0f } ?: 1f
            map.mapValues { (_, v) -> (v / maxW).coerceIn(0f, 1f) }
        }
    }

    /**
     * Returns predicted next-key continuation weights in `[0f..1f]` for pending Romaji
     * characters, or `emptyMap()` on clean syllable boundaries.
     */
    fun japaneseRomajiWeights(pendingAscii: String): Map<Char, Float> {
        if (pendingAscii.isEmpty()) return emptyMap()
        val lower = pendingAscii.lowercase()
        val maxLen = minOf(3, lower.length)
        for (len in maxLen downTo 1) {
            romajiPrefixWeights[lower.takeLast(len)]?.let { return it }
        }
        return emptyMap()
    }

    /**
     * Extracts trailing lowercase ASCII Romaji letters from a Japanese composing surface.
     */
    fun trailingPendingRomaji(surfaceBeforeCursor: String): String {
        if (surfaceBeforeCursor.isEmpty()) return ""
        var start = surfaceBeforeCursor.length
        while (start > 0) {
            val ch = surfaceBeforeCursor[start - 1]
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                start--
            } else {
                break
            }
        }
        return if (start == surfaceBeforeCursor.length) "" else surfaceBeforeCursor.substring(start).lowercase()
    }

    /**
     * Computes frequency-weighted next-character continuation weights `[0f..1f]`
     * from a frequency-ordered word collection matching [prefix].
     */
    fun computeEnglishNextCharWeights(
        prefix: String,
        words: Iterable<String>,
        maxMatches: Int = 64
    ): Map<Char, Float> {
        if (prefix.isBlank() || maxMatches <= 0) return emptyMap()
        val clean = prefix.lowercase()
        val scores = LinkedHashMap<Char, Float>()
        var matches = 0
        for (word in words) {
            if (word.length > clean.length && word.startsWith(clean, ignoreCase = true)) {
                val nextCh = word[clean.length].lowercaseChar()
                if (nextCh in 'a'..'z') {
                    val rankWeight = 1f / (1f + matches * 0.2f)
                    scores[nextCh] = (scores[nextCh] ?: 0f) + rankWeight
                    matches++
                    if (matches >= maxMatches) break
                }
            }
        }
        if (scores.isEmpty()) return emptyMap()
        val maxScore = scores.values.maxOrNull()?.takeIf { it > 0f } ?: return emptyMap()
        return scores.mapValues { (_, v) -> (v / maxScore).coerceIn(0f, 1f) }
    }
}

/**
 * Computes the next 5-way flick direction with hysteresis so minor tremor around the
 * threshold boundary or along 45-degree diagonals does not jitter between directions.
 */
fun calculateFlickDirectionWithHysteresis(
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    currentDirection: FlickDirection = FlickDirection.CENTER,
    hysteresisRatio: Float = 1.2f,
    centerReturnRatio: Float = 0.72f
): FlickDirection {
    val safeThreshold = thresholdPx.coerceAtLeast(1f)
    val exitThreshold = if (currentDirection == FlickDirection.CENTER) {
        safeThreshold
    } else {
        safeThreshold * centerReturnRatio
    }
    val absX = abs(dx)
    val absY = abs(dy)
    if (absX < exitThreshold && absY < exitThreshold) {
        return FlickDirection.CENTER
    }
    val preferHorizontal = when (currentDirection) {
        FlickDirection.LEFT, FlickDirection.RIGHT -> absY <= absX * hysteresisRatio
        FlickDirection.UP, FlickDirection.DOWN -> absX > absY * hysteresisRatio
        FlickDirection.CENTER -> absX > absY
    }
    return if (preferHorizontal) {
        if (dx < 0f) FlickDirection.LEFT else FlickDirection.RIGHT
    } else {
        if (dy < 0f) FlickDirection.UP else FlickDirection.DOWN
    }
}

/**
 * Computes QWERTY top-row upward-flick direction (CENTER vs UP) with a generous upward cone
 * and hysteresis so slightly angled upward flicks commit the secondary digit reliably.
 */
fun calculateQwertyUpFlickDirection(
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    currentDirection: FlickDirection = FlickDirection.CENTER,
    centerReturnRatio: Float = 0.72f
): FlickDirection {
    val safeThreshold = thresholdPx.coerceAtLeast(1f)
    return when (currentDirection) {
        FlickDirection.UP -> {
            if (dy > -safeThreshold * centerReturnRatio) FlickDirection.CENTER else FlickDirection.UP
        }
        else -> {
            if (dy <= -safeThreshold && -dy >= abs(dx) * 0.6f) {
                FlickDirection.UP
            } else {
                FlickDirection.CENTER
            }
        }
    }
}

/**
 * Gradually accelerates key auto-repeat intervals (Backspace, cursor arrows) on sustained hold
 * while preserving the initial cadence for short taps and early repeats.
 */
fun computeRepeatIntervalMs(repeatCount: Int, baseIntervalMs: Long = 50L): Long {
    val safeBase = baseIntervalMs.coerceAtLeast(16L)
    return when {
        repeatCount < 6 -> safeBase
        repeatCount < 16 -> (safeBase * 7L / 10L).coerceAtLeast(20L)
        else -> (safeBase * 44L / 100L).coerceAtLeast(18L)
    }
}

/**
 * Axis-aligned key bounding box in keyboard-layout coordinates.
 */
data class KeyRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val minDimension: Float get() = minOf(width, height)

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun edgeDxTo(x: Float): Float = when {
        x < left -> left - x
        x > right -> x - right
        else -> 0f
    }

    fun edgeDyTo(y: Float): Float = when {
        y < top -> top - y
        y > bottom -> y - bottom
        else -> 0f
    }

    fun distanceSquaredTo(x: Float, y: Float): Float {
        val dx = edgeDxTo(x)
        val dy = edgeDyTo(y)
        return dx * dx + dy * dy
    }

    /**
     * Signed Euclidean distance to this key boundary: negative inside the key, positive outside.
     */
    fun signedDistanceTo(x: Float, y: Float): Float =
        if (contains(x, y)) {
            -minOf(x - left, right - x, y - top, bottom - y)
        } else {
            sqrt(distanceSquaredTo(x, y))
        }

    fun centerDistanceSquaredTo(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy
    }
}

/**
 * Hitbox metadata registered by a single QWERTY character key.
 */
data class QwertyKeyTarget(
    val id: String,
    val hitRect: KeyRect,
    val supportsUpFlick: Boolean = false,
    val primaryChar: Char? = null
)

/**
 * Overlay preview status for one active (uncommitted) QWERTY finger.
 */
data class QwertyPreviewInfo(
    val pointerId: Long,
    val keyId: String,
    val isShifted: Boolean,
    val guideExpanded: Boolean,
    val flickDirection: FlickDirection
)

/**
 * Snapshot used to highlight pressed QWERTY keys and render previews/flick guides.
 */
data class QwertyVisualState(
    val pressedKeyIds: Set<String> = emptySet(),
    val previews: Map<Long, QwertyPreviewInfo> = emptyMap()
)

/**
 * Deterministic side effects emitted by [QwertyTouchTracker] for commit and press haptics.
 */
sealed class QwertyTouchEffect {
    data class CommitKey(
        val keyId: String,
        val upFlick: Boolean,
        val longPress: Boolean,
        val shiftState: KeyboardShiftState,
        val touchBias: TouchKeyBias? = null
    ) : QwertyTouchEffect()

    data class HapticTick(val keyId: String) : QwertyTouchEffect()
}

private data class ActiveQwertyPointer(
    val pointerId: Long,
    val sequence: Long,
    var keyId: String,
    var startX: Float,
    var startY: Float,
    var currentX: Float,
    var currentY: Float,
    val shiftStateAtDown: KeyboardShiftState,
    var supportsUpFlick: Boolean,
    var flickEngaged: Boolean = false,
    var longPressEngaged: Boolean = false,
    var currentFlickDirection: FlickDirection = FlickDirection.CENTER,
    var committed: Boolean = false,
    var touchBias: TouchKeyBias? = null
)

/**
 * Pure-Kotlin state machine coordinating QWERTY character-key multi-touch rollover,
 * drag re-selection across character keys, top-row upward flicks, and live press previews.
 */
class QwertyTouchTracker {
    private val orderedTargets = mutableListOf<QwertyKeyTarget>()
    private val targetsById = LinkedHashMap<String, QwertyKeyTarget>()
    private val activePointers = LinkedHashMap<Long, ActiveQwertyPointer>()
    private var nextSequence = 0L

    var visualState: QwertyVisualState = QwertyVisualState()
        private set

    fun replaceTargets(targets: List<QwertyKeyTarget>) {
        orderedTargets.clear()
        orderedTargets.addAll(targets)
        targetsById.clear()
        for (target in targets) {
            targetsById[target.id] = target
        }
    }

    fun upsertTarget(target: QwertyKeyTarget) {
        if (targetsById[target.id] == target) return
        val existingIndex = orderedTargets.indexOfFirst { it.id == target.id }
        if (existingIndex >= 0) {
            orderedTargets[existingIndex] = target
        } else {
            orderedTargets.add(target)
        }
        targetsById[target.id] = target
    }

    fun removeTarget(id: String) {
        targetsById.remove(id)
        orderedTargets.removeAll { it.id == id }
    }

    fun clearTargets() {
        orderedTargets.clear()
        targetsById.clear()
    }

    fun findTargetAt(
        x: Float,
        y: Float,
        maxSnapDistancePx: Float = Float.POSITIVE_INFINITY,
        preferredId: String? = null,
        nextCharWeights: Map<Char, Float> = emptyMap(),
        maxExpandPx: Float = 0f
    ): QwertyKeyTarget? {
        if (orderedTargets.isEmpty()) {
            return preferredId?.let { targetsById[it] }
        }
        val maxDistSq = if (maxSnapDistancePx.isFinite()) {
            maxSnapDistancePx * maxSnapDistancePx
        } else {
            Float.POSITIVE_INFINITY
        }

        if (nextCharWeights.isEmpty() || maxExpandPx <= 0f) {
            if (preferredId != null) {
                val preferred = targetsById[preferredId]
                if (preferred != null && preferred.hitRect.contains(x, y)) {
                    return preferred
                }
            }
            orderedTargets.firstOrNull { it.hitRect.contains(x, y) }?.let { return it }
            var best: QwertyKeyTarget? = null
            var bestEdgeDistSq = Float.POSITIVE_INFINITY
            var bestCenterDistSq = Float.POSITIVE_INFINITY
            for (candidate in orderedTargets) {
                val edgeDistSq = candidate.hitRect.distanceSquaredTo(x, y)
                if (edgeDistSq > maxDistSq) continue
                val centerDistSq = candidate.hitRect.centerDistanceSquaredTo(x, y)
                if (edgeDistSq < bestEdgeDistSq ||
                    (edgeDistSq == bestEdgeDistSq && centerDistSq < bestCenterDistSq)
                ) {
                    best = candidate
                    bestEdgeDistSq = edgeDistSq
                    bestCenterDistSq = centerDistSq
                }
            }
            return best ?: preferredId?.let { targetsById[it] }
        }

        val minKeyWidth = orderedTargets
            .minOfOrNull { it.hitRect.width.takeIf { w -> w > 0f } ?: Float.POSITIVE_INFINITY }
            ?: Float.POSITIVE_INFINITY
        val cappedExpandPx = if (minKeyWidth.isFinite()) {
            minOf(maxExpandPx, minKeyWidth * 0.25f)
        } else {
            maxExpandPx
        }

        var best: QwertyKeyTarget? = null
        var bestScore = Float.POSITIVE_INFINITY
        var bestContains = false
        var bestEdgeDistSq = Float.POSITIVE_INFINITY
        var bestCenterDistSq = Float.POSITIVE_INFINITY

        for (candidate in orderedTargets) {
            val edgeDistSq = candidate.hitRect.distanceSquaredTo(x, y)
            if (edgeDistSq > maxDistSq) continue
            val weight = candidate.primaryChar?.lowercaseChar()?.let { nextCharWeights[it] }?.coerceIn(0f, 1f) ?: 0f
            val score = candidate.hitRect.signedDistanceTo(x, y) - (weight * cappedExpandPx)
            val contains = candidate.hitRect.contains(x, y)
            val centerDistSq = candidate.hitRect.centerDistanceSquaredTo(x, y)

            val strictlyBetter = score < bestScore - 1e-4f
            val tied = abs(score - bestScore) <= 1e-4f
            val tieBroken = tied && (
                (contains && !bestContains) ||
                    (contains == bestContains && (
                        edgeDistSq < bestEdgeDistSq ||
                            (edgeDistSq == bestEdgeDistSq && centerDistSq < bestCenterDistSq)
                        ))
                )
            if (strictlyBetter || tieBroken) {
                best = candidate
                bestScore = score
                bestContains = contains
                bestEdgeDistSq = edgeDistSq
                bestCenterDistSq = centerDistSq
            }
        }
        return best ?: preferredId?.let { targetsById[it] }
    }

    fun onPointerDown(
        pointerId: Long,
        x: Float,
        y: Float,
        shiftState: KeyboardShiftState,
        preferredKeyId: String? = null,
        maxSnapDistancePx: Float = Float.POSITIVE_INFINITY,
        supportsUpFlickFallback: Boolean = false,
        nextCharWeights: Map<Char, Float> = emptyMap(),
        maxExpandPx: Float = 0f
    ): List<QwertyTouchEffect> {
        val target = findTargetAt(
            x = x,
            y = y,
            maxSnapDistancePx = maxSnapDistancePx,
            preferredId = preferredKeyId,
            nextCharWeights = nextCharWeights,
            maxExpandPx = maxExpandPx
        )
        val resolvedId = target?.id ?: preferredKeyId ?: return emptyList()
        val supportsUpFlick = target?.supportsUpFlick ?: supportsUpFlickFallback

        val effects = mutableListOf<QwertyTouchEffect>()
        // Rollover: pressing a new character key commits older held character keys in down-order.
        val pendingOlder = activePointers.values
            .filter { !it.committed && it.pointerId != pointerId }
            .sortedBy { it.sequence }
        for (older in pendingOlder) {
            older.committed = true
            effects.add(older.toCommitEffect())
        }

        val pointerShiftState = if (
            pendingOlder.isNotEmpty() && shiftState.mode == KeyboardShiftMode.ONCE
        ) {
            KeyboardShiftState(KeyboardShiftMode.OFF)
        } else {
            shiftState
        }

        val resolvedBias = target?.let { computeTouchKeyBias(it, x, y) }
        val pointer = ActiveQwertyPointer(
            pointerId = pointerId,
            sequence = nextSequence++,
            keyId = resolvedId,
            startX = x,
            startY = y,
            currentX = x,
            currentY = y,
            shiftStateAtDown = pointerShiftState,
            supportsUpFlick = supportsUpFlick,
            touchBias = resolvedBias
        )
        activePointers[pointerId] = pointer
        effects.add(QwertyTouchEffect.HapticTick(resolvedId))
        rebuildVisualState()
        return effects
    }

    private fun computeTouchKeyBias(target: QwertyKeyTarget, x: Float, y: Float): TouchKeyBias? {
        val typed = target.primaryChar?.lowercaseChar() ?: return null
        val refWidth = target.hitRect.width.coerceAtLeast(1f)
        val refHeight = target.hitRect.height.coerceAtLeast(1f)
        val maxNormDist = 0.34f
        val bestByChar = LinkedHashMap<Char, Float>()
        for (other in orderedTargets) {
            if (other.id == target.id) continue
            val otherChar = other.primaryChar?.lowercaseChar() ?: continue
            if (otherChar == typed) continue
            val nx = other.hitRect.edgeDxTo(x) / refWidth
            val ny = other.hitRect.edgeDyTo(y) / refHeight
            val normDist = sqrt(nx * nx + ny * ny)
            if (normDist <= maxNormDist) {
                val confidence = ((maxNormDist - normDist) / maxNormDist).coerceIn(0f, 1f)
                val prev = bestByChar[otherChar] ?: 0f
                if (confidence > prev) bestByChar[otherChar] = confidence
            }
        }
        val nearby = bestByChar.entries
            .sortedWith(compareByDescending<Map.Entry<Char, Float>> { it.value }.thenBy { it.key })
            .take(4)
            .map { it.key to it.value }
        return TouchKeyBias(typedChar = typed, nearbyChars = nearby)
    }

    fun onPointerMove(
        pointerId: Long,
        x: Float,
        y: Float,
        thresholdPx: Float
    ) {
        val pointer = activePointers[pointerId] ?: return
        if (pointer.committed) return

        pointer.currentX = x
        pointer.currentY = y
        val dx = x - pointer.startX
        val dy = y - pointer.startY
        val safeThreshold = thresholdPx.coerceAtLeast(1f)

        val upwardPullIntent = pointer.supportsUpFlick &&
            -dy >= safeThreshold * 0.55f &&
            -dy >= abs(dx) * 0.6f
        val lockedToKey = pointer.flickEngaged || pointer.longPressEngaged || upwardPullIntent

        if (!lockedToKey) {
            val hovered = orderedTargets.firstOrNull { it.hitRect.contains(x, y) }
            if (hovered != null && hovered.id != pointer.keyId) {
                pointer.keyId = hovered.id
                pointer.startX = x
                pointer.startY = y
                pointer.supportsUpFlick = hovered.supportsUpFlick
                pointer.currentFlickDirection = FlickDirection.CENTER
                pointer.touchBias = computeTouchKeyBias(hovered, x, y)
                rebuildVisualState()
                return
            }
        }

        if (pointer.supportsUpFlick) {
            val nextDir = calculateQwertyUpFlickDirection(
                dx = pointer.currentX - pointer.startX,
                dy = pointer.currentY - pointer.startY,
                thresholdPx = safeThreshold,
                currentDirection = pointer.currentFlickDirection
            )
            if (nextDir == FlickDirection.UP) {
                pointer.flickEngaged = true
            }
            if (nextDir != pointer.currentFlickDirection) {
                pointer.currentFlickDirection = nextDir
                rebuildVisualState()
            } else if (pointer.flickEngaged) {
                rebuildVisualState()
            }
        }
    }

    fun onLongPressTimeout(pointerId: Long) {
        val pointer = activePointers[pointerId] ?: return
        if (pointer.committed || !pointer.supportsUpFlick || pointer.longPressEngaged) return
        pointer.longPressEngaged = true
        rebuildVisualState()
    }

    fun onPointerUp(pointerId: Long): List<QwertyTouchEffect> {
        val pointer = activePointers.remove(pointerId) ?: return emptyList()
        val effects = if (!pointer.committed) {
            pointer.committed = true
            listOf(pointer.toCommitEffect())
        } else {
            emptyList()
        }
        rebuildVisualState()
        return effects
    }

    fun onPointerCancel(pointerId: Long) {
        if (activePointers.remove(pointerId) != null) {
            rebuildVisualState()
        }
    }

    /**
     * Commits all currently held uncommitted character keys in press order before an action key
     * (Space, Backspace, Enter, Shift, etc.) executes, preserving fast-typing order.
     */
    fun flushAllPending(): List<QwertyTouchEffect.CommitKey> {
        val pending = activePointers.values
            .filter { !it.committed }
            .sortedBy { it.sequence }
        if (pending.isEmpty()) return emptyList()
        val commits = pending.map { pointer ->
            pointer.committed = true
            pointer.toCommitEffect()
        }
        rebuildVisualState()
        return commits
    }

    fun isTrackingPointer(pointerId: Long): Boolean = activePointers.containsKey(pointerId)

    fun reset() {
        if (activePointers.isNotEmpty()) {
            activePointers.clear()
            rebuildVisualState()
        }
    }

    private fun ActiveQwertyPointer.toCommitEffect(): QwertyTouchEffect.CommitKey {
        val upFlick = supportsUpFlick && currentFlickDirection == FlickDirection.UP
        val effectiveBias = if (upFlick || longPressEngaged) null else touchBias
        return QwertyTouchEffect.CommitKey(
            keyId = keyId,
            upFlick = upFlick,
            longPress = longPressEngaged,
            shiftState = shiftStateAtDown,
            touchBias = effectiveBias
        )
    }

    private fun rebuildVisualState() {
        val pressed = LinkedHashSet<String>()
        val previews = LinkedHashMap<Long, QwertyPreviewInfo>()
        for (pointer in activePointers.values) {
            pressed.add(pointer.keyId)
            if (!pointer.committed) {
                val guideExpanded = pointer.supportsUpFlick &&
                    (pointer.flickEngaged || pointer.longPressEngaged)
                previews[pointer.pointerId] = QwertyPreviewInfo(
                    pointerId = pointer.pointerId,
                    keyId = pointer.keyId,
                    isShifted = pointer.shiftStateAtDown.isShifted,
                    guideExpanded = guideExpanded,
                    flickDirection = pointer.currentFlickDirection
                )
            }
        }
        visualState = QwertyVisualState(pressedKeyIds = pressed, previews = previews)
    }
}

/**
 * Decides whether a direct editor deletion (when IME composing buffers are empty) removed content
 * and should trigger delete haptic feedback.
 */
internal fun shouldVibrateOnEditorDelete(
    textBeforeCursor: CharSequence?,
    deleteSurroundingAccepted: Boolean
): Boolean = when {
    textBeforeCursor != null -> textBeforeCursor.isNotEmpty()
    else -> deleteSurroundingAccepted
}

/**
 * Decides whether moving the caret directly in the host editor (when IME composing buffers
 * are not handling the move) changed the caret/selection position and should trigger cursor
 * movement haptic feedback.
 */
internal fun shouldVibrateOnEditorCursorMove(
    textInDirection: CharSequence?,
    hasSelection: Boolean
): Boolean = hasSelection || !textInDirection.isNullOrEmpty()

/**
 * Pixel bounds for a floating QWERTY key preview bubble anchored above the pressed key.
 */
internal data class QwertyPreviewBounds(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
)

/**
 * Anchors the QWERTY key preview bubble centered above [targetRect] while clamping horizontally
 * only by its own width + [marginPx], keeping edge keys aligned directly above the pressed key.
 */
internal fun computeQwertyPreviewBubbleBounds(
    targetRect: KeyRect,
    bubbleWidthPx: Float,
    bubbleHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
    rootWidthPx: Float,
    rootHeightPx: Float
): QwertyPreviewBounds {
    val x = if (rootWidthPx > 0f) {
        (targetRect.centerX - bubbleWidthPx / 2f)
            .coerceIn(marginPx, (rootWidthPx - bubbleWidthPx - marginPx).coerceAtLeast(marginPx))
    } else {
        targetRect.centerX - bubbleWidthPx / 2f
    }
    // Never clamp against top edge (0f): Row 0 previews intentionally float above the key area.
    val rawY = targetRect.top - bubbleHeightPx - gapPx
    val y = if (rootHeightPx > 0f) {
        rawY.coerceAtMost(rootHeightPx - bubbleHeightPx - marginPx)
    } else {
        rawY
    }
    return QwertyPreviewBounds(
        leftPx = x,
        topPx = y,
        widthPx = bubbleWidthPx,
        heightPx = bubbleHeightPx
    )
}

data class FlickTouchGeometry(
    val dx: Float,
    val dy: Float,
    val thresholdPx: Float,
    val startX: Float,
    val startY: Float,
    val widthPx: Float,
    val heightPx: Float
)

private const val FLICK_CELL_MAX_NORM_DIST = 0.34f
private const val FLICK_CELL_MAX_WEIGHT = 0.85f
private const val FLICK_MIN_HINT_WEIGHT = 0.20f

private val flickKanaGridCoords: Map<Pair<Int, Int>, FlickKeyDef> = mapOf(
    (0 to 0) to FlickKeyDefinitions.keyA,
    (0 to 1) to FlickKeyDefinitions.keyKa,
    (0 to 2) to FlickKeyDefinitions.keySa,
    (1 to 0) to FlickKeyDefinitions.keyTa,
    (1 to 1) to FlickKeyDefinitions.keyNa,
    (1 to 2) to FlickKeyDefinitions.keyHa,
    (2 to 0) to FlickKeyDefinitions.keyMa,
    (2 to 1) to FlickKeyDefinitions.keyYa,
    (2 to 2) to FlickKeyDefinitions.keyRa,
    (3 to 1) to FlickKeyDefinitions.keyWa
)

private val flickKanaCellByCenter: Map<String, Pair<Int, Int>> =
    flickKanaGridCoords.entries.associate { (coord, def) -> def.center to coord } +
        (FlickKeyDefinitions.keyPunc.center to (3 to 2))

private val flickEnglishGridCoords: Map<Pair<Int, Int>, FlickKeyDef> = mapOf(
    (0 to 1) to FlickEnglishKeyDefinitions.getKey2(false),
    (0 to 2) to FlickEnglishKeyDefinitions.getKey3(false),
    (1 to 0) to FlickEnglishKeyDefinitions.getKey4(false),
    (1 to 1) to FlickEnglishKeyDefinitions.getKey5(false),
    (1 to 2) to FlickEnglishKeyDefinitions.getKey6(false),
    (2 to 0) to FlickEnglishKeyDefinitions.getKey7(false),
    (2 to 1) to FlickEnglishKeyDefinitions.getKey8(false),
    (2 to 2) to FlickEnglishKeyDefinitions.getKey9(false)
)

private val flickEnglishCellByCenter: Map<String, Pair<Int, Int>> =
    flickEnglishGridCoords.entries.associate { (coord, def) -> def.center.lowercase() to coord }

private fun flickKeyOutput(def: FlickKeyDef, dir: FlickDirection): Char? = when (dir) {
    FlickDirection.CENTER -> def.center
    FlickDirection.LEFT -> def.left
    FlickDirection.UP -> def.up
    FlickDirection.RIGHT -> def.right
    FlickDirection.DOWN -> def.down
}.singleOrNull()

private fun flickEnglishKeyOutput(def: FlickKeyDef, dir: FlickDirection): Char? =
    flickKeyOutput(def, dir)?.lowercaseChar()?.takeIf { it in 'a'..'z' }

private fun findFlickDirectionForChar(
    def: FlickKeyDef,
    ch: Char,
    ignoreCase: Boolean = false
): FlickDirection? {
    val directions = listOf(
        FlickDirection.CENTER to def.center,
        FlickDirection.LEFT to def.left,
        FlickDirection.UP to def.up,
        FlickDirection.RIGHT to def.right,
        FlickDirection.DOWN to def.down
    )
    for ((dir, label) in directions) {
        val candidate = label.singleOrNull() ?: continue
        if (candidate.equals(ch, ignoreCase = ignoreCase)) return dir
    }
    return null
}

private fun addFlickGridCellEdgeHints(
    sourceRow: Int,
    sourceCol: Int,
    targetDir: FlickDirection,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float,
    grid: Map<Pair<Int, Int>, FlickKeyDef>,
    extractChar: (FlickKeyDef, FlickDirection) -> Char?,
    addAlt: (Char?, Float) -> Unit
) {
    if (widthPx <= 0f || heightPx <= 0f) return
    val nx = (startX / widthPx).coerceIn(0f, 1f)
    val ny = (startY / heightPx).coerceIn(0f, 1f)
    for ((coord, neighborDef) in grid) {
        val (nr, nc) = coord
        if (nr == sourceRow && nc == sourceCol) continue
        if (abs(nr - sourceRow) > 1 || abs(nc - sourceCol) > 1) continue
        val ex = when {
            nc < sourceCol -> nx
            nc > sourceCol -> 1f - nx
            else -> 0f
        }
        val ey = when {
            nr < sourceRow -> ny
            nr > sourceRow -> 1f - ny
            else -> 0f
        }
        val normDist = sqrt(ex * ex + ey * ey)
        if (normDist <= FLICK_CELL_MAX_NORM_DIST) {
            val weight = ((FLICK_CELL_MAX_NORM_DIST - normDist) / FLICK_CELL_MAX_NORM_DIST) *
                FLICK_CELL_MAX_WEIGHT
            if (weight >= FLICK_MIN_HINT_WEIGHT) {
                addAlt(extractChar(neighborDef, targetDir), weight)
            }
        }
    }
}

private fun computeFlickTouchBiasCore(
    keyDef: FlickKeyDef,
    cell: Pair<Int, Int>,
    committedChar: Char,
    committedDir: FlickDirection,
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float,
    grid: Map<Pair<Int, Int>, FlickKeyDef>,
    extractChar: (FlickKeyDef, FlickDirection) -> Char?,
    includeDirectionSignals: Boolean
): TouchKeyBias {
    val alts = LinkedHashMap<Char, Float>()
    fun addAlt(ch: Char?, weight: Float) {
        if (ch == null || ch == committedChar || !weight.isFinite()) return
        val clamped = weight.coerceIn(FLICK_MIN_HINT_WEIGHT, 1.0f)
        val prev = alts[ch]
        if (prev == null || clamped > prev) alts[ch] = clamped
    }

    if (includeDirectionSignals) {
        val safeThreshold = thresholdPx.coerceAtLeast(1f)
        val absX = abs(dx)
        val absY = abs(dy)
        val dist = maxOf(absX, absY)

        // Signal A: Flick-length threshold boundary (tentative center tap vs short flick)
        if (committedDir == FlickDirection.CENTER) {
            if (dist >= safeThreshold * 0.45f) {
                val dominantDir = if (absX > absY) {
                    if (dx < 0f) FlickDirection.LEFT else FlickDirection.RIGHT
                } else {
                    if (dy < 0f) FlickDirection.UP else FlickDirection.DOWN
                }
                val w = ((dist / safeThreshold - 0.45f) / 0.55f).coerceIn(0.25f, 1.0f)
                addAlt(extractChar(keyDef, dominantDir), w)
            }
        } else if (dist <= safeThreshold * 1.45f) {
            val w = ((1.45f - dist / safeThreshold) / 0.75f).coerceIn(0.25f, 0.90f)
            addAlt(extractChar(keyDef, FlickDirection.CENTER), w)
        }

        // Signal B: 45-degree diagonal flick boundary
        if (committedDir != FlickDirection.CENTER) {
            val major = maxOf(absX, absY)
            val minor = minOf(absX, absY)
            if (major > 0f) {
                val ratio = minor / major
                if (ratio >= 0.45f) {
                    val secondaryDir = when (committedDir) {
                        FlickDirection.LEFT, FlickDirection.RIGHT ->
                            if (dy < 0f) FlickDirection.UP else FlickDirection.DOWN
                        FlickDirection.UP, FlickDirection.DOWN ->
                            if (dx < 0f) FlickDirection.LEFT else FlickDirection.RIGHT
                        FlickDirection.CENTER -> FlickDirection.CENTER
                    }
                    val w = ((ratio - 0.45f) / 0.55f).coerceIn(0.25f, 1.0f)
                    addAlt(extractChar(keyDef, secondaryDir), w)
                }
            }
        }
    }

    // Signal C: 3x4 grid key-cell 2D edge & corner proximity
    addFlickGridCellEdgeHints(
        sourceRow = cell.first,
        sourceCol = cell.second,
        targetDir = committedDir,
        startX = startX,
        startY = startY,
        widthPx = widthPx,
        heightPx = heightPx,
        grid = grid,
        extractChar = extractChar,
        addAlt = ::addAlt
    )

    val nearby = alts.entries
        .sortedWith(compareByDescending<Map.Entry<Char, Float>> { it.value }.thenBy { it.key })
        .take(4)
        .map { it.key to it.value }
    return TouchKeyBias(typedChar = committedChar, nearbyChars = nearby)
}

/**
 * Extracts boundary-only flick gesture ambiguity hints (`nearbyChars`) for Japanese 12-key kana.
 * Clean center taps and confident cardinal flicks yield an empty hint list (never penalized).
 */
internal fun computeFlickTouchBias(
    keyDef: FlickKeyDef,
    committedDir: FlickDirection,
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float,
    isToggleMode: Boolean = false
): TouchKeyBias? {
    if (keyDef.isActionKey) return null
    val cell = flickKanaCellByCenter[keyDef.center] ?: return null
    val committedChar = flickKeyOutput(keyDef, committedDir) ?: return null
    val base = computeFlickTouchBiasCore(
        keyDef = keyDef,
        cell = cell,
        committedChar = committedChar,
        committedDir = committedDir,
        dx = dx,
        dy = dy,
        thresholdPx = thresholdPx,
        startX = startX,
        startY = startY,
        widthPx = widthPx,
        heightPx = heightPx,
        grid = flickKanaGridCoords,
        extractChar = ::flickKeyOutput,
        includeDirectionSignals = true
    )
    val obs = if (widthPx > 0f && heightPx > 0f) {
        val safeThreshold = thresholdPx.coerceAtLeast(1f)
        FlickTouchObservation(
            kind = if (isToggleMode && committedDir == FlickDirection.CENTER) {
                FlickObservationKind.TOGGLE_FIRST
            } else {
                FlickObservationKind.FLICK
            },
            nx = (startX / widthPx).coerceIn(0f, 1f),
            ny = (startY / heightPx).coerceIn(0f, 1f),
            dxNorm = (dx / safeThreshold).coerceIn(-3f, 3f),
            dyNorm = (dy / safeThreshold).coerceIn(-3f, 3f)
        )
    } else {
        null
    }
    return base.copy(flickObservation = obs)
}

/**
 * Extracts cell-edge tap-position hints for Japanese 12-key toggle-cycle replacements.
 */
internal fun computeFlickToggleTouchBias(
    keyDef: FlickKeyDef,
    committedChar: Char,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float
): TouchKeyBias? {
    if (keyDef.isActionKey) return null
    val cell = flickKanaCellByCenter[keyDef.center] ?: return null
    val dir = findFlickDirectionForChar(keyDef, committedChar) ?: return null
    val base = computeFlickTouchBiasCore(
        keyDef = keyDef,
        cell = cell,
        committedChar = committedChar,
        committedDir = dir,
        dx = 0f,
        dy = 0f,
        thresholdPx = 1f,
        startX = startX,
        startY = startY,
        widthPx = widthPx,
        heightPx = heightPx,
        grid = flickKanaGridCoords,
        extractChar = ::flickKeyOutput,
        includeDirectionSignals = false
    )
    val obs = if (widthPx > 0f && heightPx > 0f) {
        FlickTouchObservation(
            kind = FlickObservationKind.TOGGLE_CYCLE,
            nx = (startX / widthPx).coerceIn(0f, 1f),
            ny = (startY / heightPx).coerceIn(0f, 1f),
            dxNorm = 0f,
            dyNorm = 0f
        )
    } else {
        null
    }
    return base.copy(flickObservation = obs)
}

/**
 * Extracts boundary-only flick gesture ambiguity hints (`nearbyChars`) for English 12-key letters.
 */
internal fun computeEnglishFlickTouchBias(
    keyDef: FlickKeyDef,
    committedDir: FlickDirection,
    dx: Float,
    dy: Float,
    thresholdPx: Float,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float
): TouchKeyBias? {
    if (keyDef.isActionKey) return null
    val cell = flickEnglishCellByCenter[keyDef.center.lowercase()] ?: return null
    val committedChar = flickEnglishKeyOutput(keyDef, committedDir) ?: return null
    return computeFlickTouchBiasCore(
        keyDef = keyDef,
        cell = cell,
        committedChar = committedChar,
        committedDir = committedDir,
        dx = dx,
        dy = dy,
        thresholdPx = thresholdPx,
        startX = startX,
        startY = startY,
        widthPx = widthPx,
        heightPx = heightPx,
        grid = flickEnglishGridCoords,
        extractChar = ::flickEnglishKeyOutput,
        includeDirectionSignals = true
    )
}

/**
 * Extracts cell-edge tap-position hints for English 12-key toggle-cycle replacements.
 */
internal fun computeEnglishFlickToggleTouchBias(
    keyDef: FlickKeyDef,
    committedChar: Char,
    startX: Float,
    startY: Float,
    widthPx: Float,
    heightPx: Float
): TouchKeyBias? {
    if (keyDef.isActionKey) return null
    val lower = committedChar.lowercaseChar()
    if (lower !in 'a'..'z') return null
    val cell = flickEnglishCellByCenter[keyDef.center.lowercase()] ?: return null
    val dir = findFlickDirectionForChar(keyDef, lower, ignoreCase = true) ?: return null
    return computeFlickTouchBiasCore(
        keyDef = keyDef,
        cell = cell,
        committedChar = lower,
        committedDir = dir,
        dx = 0f,
        dy = 0f,
        thresholdPx = 1f,
        startX = startX,
        startY = startY,
        widthPx = widthPx,
        heightPx = heightPx,
        grid = flickEnglishGridCoords,
        extractChar = ::flickEnglishKeyOutput,
        includeDirectionSignals = false
    )
}

/**
 * Maintains suffix-aligned [TouchKeyBias] lists across modifier changes, toggle replacements,
 * backward deletions, and prefix commits.
 */
internal object TouchBiasBuffer {
    fun updateLastForModifier(
        biases: MutableList<TouchKeyBias>,
        charCount: Int,
        previousChar: Char,
        modifiedChar: Char,
        kind: KanaModifierKind
    ) {
        if (biases.isNotEmpty() && biases.size <= charCount && biases.last().typedChar == previousChar) {
            val prevBias = biases[biases.lastIndex]
            val mappedNearby = LinkedHashMap<Char, Float>()
            for ((alt, weight) in prevBias.nearbyChars) {
                val modAlt = KanaModifier.modify(alt, kind)
                if (modAlt != modifiedChar) {
                    val prevW = mappedNearby[modAlt]
                    if (prevW == null || weight > prevW) mappedNearby[modAlt] = weight
                }
            }
            val sortedNearby = mappedNearby.entries
                .sortedWith(compareByDescending<Map.Entry<Char, Float>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
            val updatedObs = prevBias.flickObservation?.let { obs ->
                val nextKind = when (obs.kind) {
                    FlickObservationKind.FLICK -> FlickObservationKind.FLICK_MOD
                    FlickObservationKind.TOGGLE_FIRST,
                    FlickObservationKind.TOGGLE_CYCLE -> FlickObservationKind.TOGGLE_MOD
                    FlickObservationKind.FLICK_MOD,
                    FlickObservationKind.TOGGLE_MOD -> obs.kind
                }
                obs.copy(kind = nextKind)
            }
            biases[biases.lastIndex] = TouchKeyBias(
                typedChar = modifiedChar,
                nearbyChars = sortedNearby,
                flickObservation = updatedObs
            )
        } else if (biases.isNotEmpty()) {
            biases.clear()
        }
    }

    fun updateLastForReplacement(
        biases: MutableList<TouchKeyBias>,
        charCount: Int,
        previousChar: Char?,
        replacementChar: Char,
        replacementBias: TouchKeyBias?
    ) {
        val entry = if (replacementBias != null && replacementBias.typedChar == replacementChar) {
            replacementBias
        } else {
            TouchKeyBias(replacementChar)
        }
        if (biases.isNotEmpty() &&
            biases.size <= charCount &&
            (previousChar == null || biases.last().typedChar == previousChar)
        ) {
            biases[biases.lastIndex] = entry
        } else {
            biases.clear()
            if (charCount > 0) {
                biases.add(entry)
            }
        }
    }

    fun trimForBackwardDelete(
        biases: MutableList<TouchKeyBias>,
        prevCharCount: Int,
        nextCharCount: Int
    ) {
        val removed = (prevCharCount - nextCharCount).coerceAtLeast(0)
        repeat(minOf(removed, biases.size)) {
            biases.removeAt(biases.lastIndex)
        }
        while (biases.size > nextCharCount) {
            biases.removeAt(biases.lastIndex)
        }
    }

    fun trimToMaxCharCountFromStart(
        biases: MutableList<TouchKeyBias>,
        remainingCharCount: Int
    ) {
        val safeCount = remainingCharCount.coerceAtLeast(0)
        while (biases.size > safeCount) {
            biases.removeAt(0)
        }
    }
}

/**
 * Turns a horizontal drag on the space key into per-character cursor steps.
 *
 * The first [startSlopPx] of travel is ignored so that a tap stays a tap. After that, every
 * [stepPx] of finger travel from the press point emits one step: +1 for right, -1 for left.
 * Reversing the finger emits the opposite steps, so the cursor tracks the finger 1:1 per character.
 */
class HorizontalCursorDragTracker(
    private val stepPx: Float,
    private val startSlopPx: Float,
    private val onStep: (Int) -> Unit,
    private val onDragStart: () -> Unit
) {
    private var originX = 0f
    private var anchorX = 0f

    var isDragging: Boolean = false
        private set

    fun begin(x: Float) {
        originX = x
        anchorX = x
        isDragging = false
    }

    /** Advances to the pointer position [x], emitting one [onStep] per character crossed. */
    fun move(x: Float) {
        if (!isDragging) {
            if (abs(x - originX) < startSlopPx) return
            isDragging = true
            onDragStart()
        }
        while (x - anchorX >= stepPx) {
            anchorX += stepPx
            onStep(1)
        }
        while (anchorX - x >= stepPx) {
            anchorX -= stepPx
            onStep(-1)
        }
    }
}

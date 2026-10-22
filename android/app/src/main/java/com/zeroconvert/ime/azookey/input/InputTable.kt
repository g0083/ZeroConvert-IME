package com.zeroconvert.ime.azookey.input

sealed interface KeyElement {
    data class Piece(val piece: InputPiece) : KeyElement
    object Any1 : KeyElement
}

sealed interface ValueElement {
    data class CharValue(val char: Char) : ValueElement
    object Any1 : ValueElement
}

class InputTable(baseMapping: Map<List<KeyElement>, List<ValueElement>>) {

    private class TrieNode(
        var output: List<ValueElement>? = null,
        val charChildren: MutableMap<Char, TrieNode> = mutableMapOf(),
        var separatorChild: TrieNode? = null,
        var any1Child: TrieNode? = null
    ) {
        val hasOutput: Boolean get() = output != null

        fun add(reversedKey: List<KeyElement>, output: List<ValueElement>) {
            if (reversedKey.isEmpty()) {
                this.output = output
                return
            }
            val head = reversedKey.first()
            val rest = reversedKey.drop(1)
            when (head) {
                is KeyElement.Any1 -> {
                    val next = any1Child ?: TrieNode().also { any1Child = it }
                    next.add(rest, output)
                }
                is KeyElement.Piece -> {
                    when (val p = head.piece) {
                        is InputPiece.CharPiece -> {
                            val next = charChildren.getOrPut(p.char) { TrieNode() }
                            next.add(rest, output)
                        }
                        is InputPiece.CompositionSeparator -> {
                            val next = separatorChild ?: TrieNode().also { separatorChild = it }
                            next.add(rest, output)
                        }
                    }
                }
            }
        }

        fun outputValue(resolvedAny1: InputPiece?): List<Char>? {
            val out = output ?: return null
            val result = mutableListOf<Char>()
            for (elem in out) {
                when (elem) {
                    is ValueElement.CharValue -> result.add(elem.char)
                    is ValueElement.Any1 -> {
                        when (resolvedAny1) {
                            is InputPiece.CharPiece -> result.add(resolvedAny1.char)
                            else -> return null
                        }
                    }
                }
            }
            return result
        }
    }

    private val trieRoot = TrieNode()
    private val maxKeyCount: Int

    init {
        var maxLen = 0
        for ((key, value) in baseMapping) {
            if (key.size > maxLen) maxLen = key.size
            trieRoot.add(key.reversed(), value)
        }
        maxKeyCount = maxLen
    }

    private data class MatchState(
        val node: TrieNode,
        val resolvedAny1: InputPiece?,
        val depth: Int,
        val any1Count: Int
    )

    private fun isBetter(candDepth: Int, candAny1: Int, best: MatchState?): Boolean {
        if (best == null) return true
        if (candDepth > best.depth) return true
        if (candDepth == best.depth && candAny1 < best.any1Count) return true
        return false
    }

    private fun matchGreedy(
        buffer: List<Char>,
        added: InputPiece
    ): MatchState? {
        val stack = ArrayDeque<MatchState>()
        stack.add(MatchState(trieRoot, null, 0, 0))

        var bestMatch: MatchState? = null

        fun pieceAt(depth: Int): InputPiece? {
            if (depth == 0) return added
            val idx = buffer.size - depth
            if (idx < 0 || idx >= buffer.size) return null
            return InputPiece.CharPiece(buffer[idx])
        }

        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top.depth >= maxKeyCount) continue
            val piece = pieceAt(top.depth) ?: continue

            // 1. Concrete edge
            when (piece) {
                is InputPiece.CharPiece -> {
                    top.node.charChildren[piece.char]?.let { next ->
                        val nextDepth = top.depth + 1
                        if (next.hasOutput && isBetter(nextDepth, top.any1Count, bestMatch)) {
                            bestMatch = MatchState(next, top.resolvedAny1, nextDepth, top.any1Count)
                        }
                        stack.add(MatchState(next, top.resolvedAny1, nextDepth, top.any1Count))
                    }
                }
                is InputPiece.CompositionSeparator -> {
                    top.node.separatorChild?.let { next ->
                        val nextDepth = top.depth + 1
                        if (next.hasOutput && isBetter(nextDepth, top.any1Count, bestMatch)) {
                            bestMatch = MatchState(next, top.resolvedAny1, nextDepth, top.any1Count)
                        }
                        stack.add(MatchState(next, top.resolvedAny1, nextDepth, top.any1Count))
                    }
                }
            }

            // 2. Any1 edge (wildcard for duplicate consonants)
            val nextAny = top.node.any1Child
            if (nextAny != null) {
                val compatible = (top.resolvedAny1 == null || top.resolvedAny1 == piece)
                if (compatible) {
                    val nextAny1 = top.resolvedAny1 ?: piece
                    val nextDepth = top.depth + 1
                    val nextAny1Count = top.any1Count + 1
                    if (nextAny.hasOutput && isBetter(nextDepth, nextAny1Count, bestMatch)) {
                        bestMatch = MatchState(nextAny, nextAny1, nextDepth, nextAny1Count)
                    }
                    stack.add(MatchState(nextAny, nextAny1, nextDepth, nextAny1Count))
                }
            }
        }

        return bestMatch
    }

    /**
     * Applies this table to mutate `buffer` with `added`.
     * Returns deletedCount from the end of `buffer` before appending output.
     */
    fun apply(buffer: MutableList<Char>, added: InputPiece): Int {
        val best = matchGreedy(buffer, added)
        if (best != null) {
            val output = best.node.outputValue(best.resolvedAny1)
            if (output != null) {
                val matchedDepth = best.depth
                // If matchedDepth == 1, 0 chars from buffer need to be removed (only 'added' matched).
                // If matchedDepth > 1, matchedDepth - 1 chars from end of buffer are removed.
                val removeCount = matchedDepth - 1
                if (removeCount > 0) {
                    val fromIdx = buffer.size - removeCount
                    if (fromIdx >= 0) {
                        for (i in 0 until removeCount) {
                            buffer.removeAt(buffer.size - 1)
                        }
                    }
                }
                buffer.addAll(output)
                return removeCount
            }
        }

        // Fallback: no rule matched
        when (added) {
            is InputPiece.CharPiece -> {
                buffer.add(added.char)
                return 0
            }
            is InputPiece.CompositionSeparator -> return 0
        }
    }
}

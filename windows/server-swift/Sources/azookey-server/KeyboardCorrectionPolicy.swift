import Foundation
import KanaKanjiConverterModule

/// Measured key proximity, supplied with the immutable input snapshot.
/// Weights are geometric evidence, rather than probabilities of a word.
struct KeyboardTouchEvidence: Sendable {
    let inputIndex: Int
    let typed: Character
    let alternatives: [(key: Character, weight: Float)]
}

nonisolated(unsafe) var currentKeyboardTouchEvidence: [KeyboardTouchEvidence] = []

struct KeyboardTouchCandidates {
    var candidates: [Candidate] = []
    var originalIsLexical = false
}

func isTouchWeightedKeyboardCandidate(_ candidate: Candidate) -> Bool {
    candidate.data.count == 1
        && candidate.data[0].metadata.contains(.isKeyboardTypoCorrection)
        && keyboardTypoDictionaryEntry(in: candidate) == nil
}

func keyboardCorrectionReading(_ candidate: Candidate, remainingReading: String = "") -> String? {
    guard !candidate.keyboardTypoCorrections.isEmpty
        || candidate.data.contains(where: { $0.metadata.contains(.isKeyboardTypoCorrection) }) else { return nil }
    let reading = candidate.data.map { correctedKeyboardTypoReading($0) }.joined() + remainingReading
    guard !reading.isEmpty, !reading.contains(where: { $0.isASCII && $0.isLetter }) else { return nil }
    return reading.applyingTransform(.hiraganaToKatakana, reverse: true)
}

/// Only the shared converter resolves a touch alternative into a reading and
/// dictionary candidate. The observed input remains the composition owner.
func touchWeightedKeyboardCandidates(
    composing: ComposingText,
    evidence: [KeyboardTouchEvidence],
    options: ConvertRequestOptions,
    lookup: (ComposingText, ConvertRequestOptions) -> [Candidate]
) -> KeyboardTouchCandidates {
    guard options.experimentalKeyboardTypoCorrection,
          composing.isAtEndIndex,
          (2 ... 128).contains(composing.input.count),
          !hasTrailingUncompletedRoman(in: composing),
          composing.input.allSatisfy({ $0.inputStyle == .roman2kana }) else { return .init() }
    var alternatives: [(index: Int, key: Character, weight: Float)] = []
    for entry in evidence.prefix(128) {
        guard composing.input.indices.contains(entry.inputIndex),
              case let .character(typed) = composing.input[entry.inputIndex].piece,
              typed.lowercased() == entry.typed.lowercased() else { continue }
        for alternative in entry.alternatives.prefix(4) {
            guard alternative.weight.isFinite, (0.2 ... 1).contains(alternative.weight),
                  alternative.key.isASCII, alternative.key.isLetter,
                  alternative.key.lowercased() != typed.lowercased() else { continue }
            alternatives.append((entry.inputIndex, alternative.key, alternative.weight))
        }
    }
    alternatives.sort {
        if $0.weight != $1.weight { return $0.weight > $1.weight }
        if $0.index != $1.index { return $0.index < $1.index }
        return $0.key < $1.key
    }
    var lookupOptions = options
    lookupOptions.zenzaiMode = .off
    lookupOptions.requireJapanesePrediction = .disabled
    lookupOptions.requireEnglishPrediction = .disabled
    lookupOptions.experimentalKeyboardTypoCorrection = false
    var seenReadings = Set<String>()
    var output: [Candidate] = []
    for alternative in alternatives.prefix(4) {
        var elements = composing.input
        elements[alternative.index] = .init(character: alternative.key, inputStyle: .roman2kana)
        var changed = ComposingText()
        changed.insertAtCursorPosition(elements)
        let reading = changed.convertTarget
        guard reading != composing.convertTarget,
              !hasTrailingUncompletedRoman(in: changed),
              !reading.contains(where: { $0.isASCII && $0.isLetter }),
              seenReadings.insert(reading).inserted else { continue }
        guard let lexical = lookup(changed, lookupOptions).first(where: {
            $0.rubyCount == reading.count && !$0.data.isEmpty && $0.inputable
        }), let first = lexical.data.first, let last = lexical.data.last,
              (lexical.data.count == 1 || (lexical.text != reading && lexical.text != hiraganaToKatakana(reading))) else { continue }
        // The channel penalty bounds the geometric preference. Dictionary
        // scores still choose among word candidates, with original input counts.
        let value = lexical.value - 3 + 2 * alternative.weight
        output.append(Candidate(
            text: lexical.text,
            value: value,
            composingCount: .inputCount(composing.input.count),
            lastMid: lexical.lastMid,
            data: [DicdataElement(word: lexical.text, ruby: hiraganaToKatakana(reading),
                lcid: first.lcid, rcid: last.rcid, mid: lexical.lastMid,
                value: value, metadata: .isKeyboardTypoCorrection)],
            isLearningTarget: false
        ))
    }
    guard !output.isEmpty else { return .init() }
    // Normal results also contain generated whole-input kana candidates. The
    // exact dictionary API identifies real kana words as well as kanji words.
    let originalIsLexical = composing.convertTarget.count < 2 || !lookup(composing, lookupOptions).isEmpty
    return .init(candidates: output.sorted { $0.value > $1.value }, originalIsLexical: originalIsLexical)
}

func mergeTouchWeightedKeyboardCandidates(
    normal: [Candidate], touch: KeyboardTouchCandidates, dictionary: [Candidate]
) -> [Candidate] {
    guard !touch.candidates.isEmpty else { return normal }
    // Exact lexical input is protected from automatic reading changes. Touch
    // alternatives remain selectable after the normal first candidate.
    let baselineScore = dictionary.first?.data.reduce(Float.zero) { $0 + $1.value() } ?? -.infinity
    let alreadyCorrected = normal.first.flatMap { keyboardCorrectionReading($0) } != nil
    let promote = !touch.originalIsLexical && !alreadyCorrected && touch.candidates[0].value > baselineScore
    var result = normal
    var insertion = promote ? 0 : min(1, result.count)
    for candidate in touch.candidates {
        if let existing = result.firstIndex(where: { $0.text == candidate.text }) {
            if existing < insertion { continue }
            result.remove(at: existing)
        }
        result.insert(candidate, at: insertion)
        insertion += 1
    }
    return result
}

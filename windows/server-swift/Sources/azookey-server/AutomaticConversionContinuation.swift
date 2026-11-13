import Foundation

let maxAutomaticConversionContinuationSurfaceCount = 12
let maxAutomaticConversionContinuationReadingCount = 24
let maxAutomaticConversionContinuationTailCount = 24
let automaticConversionContinuationMinimumScoreGap: Float = 0.5

private typealias AutomaticConversionContinuationPath = (
    pieces: [ReconversionReadingPiece],
    score: Float,
    secondBestScore: Float?
)

private let automaticConversionContinuationStopCharacters: Set<Character> = [
    "\n", "。", "、", "！", "？", "!", "?", ".", ",", "，", "．", "…", "‥",
    "」", "』", "】", "）", ")", "]", "}", "｝", "〉", "》",
]

/// Katakana readings become the hiragana readings Zenzai expects, while the
/// long vowel mark and uncommon katakana stay untouched.
func katakanaToHiragana(_ text: String) -> String {
    var scalars = String.UnicodeScalarView()
    scalars.reserveCapacity(text.unicodeScalars.count)
    for scalar in text.unicodeScalars {
        let value = scalar.value
        switch value {
        case 0x30A1...0x30F6:
            scalars.append(UnicodeScalar(value - 0x60) ?? scalar)
        case 0x30FD:
            scalars.append(UnicodeScalar(UInt32(0x309D)) ?? scalar)
        case 0x30FE:
            scalars.append(UnicodeScalar(UInt32(0x309E)) ?? scalar)
        default:
            scalars.append(scalar)
        }
    }
    return String(scalars)
}

// Serialized by the single-threaded FFI executor in Rust and @MainActor in tests.
nonisolated(unsafe) private var automaticConversionContinuationPathCache: (
    context: String,
    path: AutomaticConversionContinuationPath?
)?

nonisolated(unsafe) private var automaticContinuationDisabledContext: String?

/// Stop an automatic continuation for one exact context so repeated keystrokes
/// do not keep paying a configured conversion that produced no usable result.
func disableAutomaticConversionContinuation(context: String) {
    automaticContinuationDisabledContext = context
}

func invalidateAutomaticConversionContinuationCache() {
    automaticConversionContinuationPathCache = nil
    automaticContinuationDisabledContext = nil
}

private func automaticConversionContinuationPath(
    for context: String
) -> AutomaticConversionContinuationPath? {
    if let cached = automaticConversionContinuationPathCache, cached.context == context {
        return cached.path
    }

    var lengths: [Int] = []
    var seenLengths = Set<Int>()
    for length in [
        min(context.count, maxAutomaticConversionContinuationTailCount),
        12,
        6,
        3,
    ] {
        guard length > 0, length <= context.count, seenLengths.insert(length).inserted else {
            continue
        }
        lengths.append(length)
    }

    var resolved: AutomaticConversionContinuationPath?
    for length in lengths {
        if let path = reconversionDictionary.inferReadingPieces(
            for: String(context.suffix(length))
        ) {
            resolved = path
            break
        }
    }
    automaticConversionContinuationPathCache = (context: context, path: resolved)
    return resolved
}

private func isAutomaticConversionContinuationStopCharacter(_ character: Character) -> Bool {
    if character.isNewline {
        return true
    }
    return automaticConversionContinuationStopCharacters.contains(character)
}

/// Rebuild the committed prefix of the sentence from the surrounding text so a
/// continued composition can reconvert the same boundary-aware reading that an
/// explicit auto-commit/partial commit would use.
func makeAutomaticConversionContinuation(
    context: String,
    currentReading: String,
    isCursorAtEnd: Bool
) -> ConversionContinuation? {
    guard !context.isEmpty, isCursorAtEnd else {
        return nil
    }
    guard !currentReading.isEmpty,
          currentReading.count <= maxConversionContinuationCurrentReadingCount,
          isKanaLikeConversionContinuationReading(currentReading) else {
        return nil
    }
    guard automaticContinuationDisabledContext != context else {
        return nil
    }
    guard let path = automaticConversionContinuationPath(for: context) else {
        return nil
    }
    if let secondBestScore = path.secondBestScore,
       path.score - secondBestScore < automaticConversionContinuationMinimumScoreGap {
        return nil
    }

    var surface = ""
    var reading = ""
    for piece in path.pieces.reversed() {
        if piece.surface.contains(where: isAutomaticConversionContinuationStopCharacter) {
            break
        }
        let pieceReading = katakanaToHiragana(piece.reading)
        guard isKanaLikeConversionContinuationReading(pieceReading) else {
            break
        }
        let nextSurfaceCount = surface.count + piece.surface.count
        let nextReadingCount = reading.count + pieceReading.count
        guard nextSurfaceCount <= maxAutomaticConversionContinuationSurfaceCount,
              nextReadingCount <= maxAutomaticConversionContinuationReadingCount,
              nextReadingCount + currentReading.count
                <= maxCombinedConversionContinuationReadingCount else {
            break
        }
        surface = piece.surface + surface
        reading = pieceReading + reading
    }

    guard !surface.isEmpty,
          !reading.isEmpty,
          context.hasSuffix(surface) else {
        return nil
    }
    // A pure-hiragana prefix only rebuilds itself or converts to kanji the surface slice cannot strip.
    guard surface.contains(where: { !isKanaLikeConversionContinuationReading(String($0)) }) else {
        return nil
    }
    return ConversionContinuation(reading: reading, surface: surface)
}

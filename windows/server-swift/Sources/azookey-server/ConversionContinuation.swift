import Foundation
import KanaKanjiConverterModule

/// Live-conversion prefix retained across an auto-commit so the next
/// conversion can rebuild the Zenzai input from the committed reading and
/// surface instead of converting only the remaining reading.
struct ConversionContinuation: Equatable, Sendable {
    let reading: String
    let surface: String
}

let maxConversionContinuationReadingCount = 64
let maxConversionContinuationSurfaceCount = 128
let maxCombinedConversionContinuationReadingCount = 48
let maxConversionContinuationCurrentReadingCount = 16

@MainActor var conversionContinuation: ConversionContinuation?

@MainActor
func clearConversionContinuation() {
    conversionContinuation = nil
}

/// Hiragana, the long vowel mark, and the hiragana iteration marks are the
/// only readings that can be prepended as direct (surface) input.
func isKanaLikeConversionContinuationReading(_ text: String) -> Bool {
    guard !text.isEmpty else {
        return false
    }
    return text.unicodeScalars.allSatisfy { scalar in
        switch scalar.value {
        case 0x3041...0x3096, 0x309D, 0x309E, 0x30FC:
            return true
        default:
            return false
        }
    }
}

enum ConversionContinuationContextResolution: Equatable {
    case use(context: String)
    case skip
}

func resolveConversionContinuationContext(
    context: String,
    continuationSurface: String
) -> ConversionContinuationContextResolution {
    if context.isEmpty {
        return .use(context: "")
    }
    guard !continuationSurface.isEmpty,
          context.hasSuffix(continuationSurface) else {
        return .skip
    }
    return .use(context: String(context.dropLast(continuationSurface.count)))
}

struct ConversionContinuationRequest {
    let continuation: ConversionContinuation
    let context: String
    let combinedComposingText: ComposingText
}

func makeConversionContinuationRequest(
    continuation: ConversionContinuation?,
    allowZenzai: Bool,
    isCursorAtEnd: Bool,
    previewHiragana: String,
    hasTrailingUncompletedRoman: Bool,
    context: String
) -> ConversionContinuationRequest? {
    guard allowZenzai,
          let continuation,
          isCursorAtEnd,
          !hasTrailingUncompletedRoman else {
        return nil
    }
    guard !continuation.reading.isEmpty,
          !continuation.surface.isEmpty,
          continuation.reading.count <= maxConversionContinuationReadingCount,
          continuation.surface.count <= maxConversionContinuationSurfaceCount,
          isKanaLikeConversionContinuationReading(continuation.reading) else {
        return nil
    }
    guard !previewHiragana.isEmpty,
          previewHiragana.count <= maxConversionContinuationCurrentReadingCount,
          isKanaLikeConversionContinuationReading(previewHiragana) else {
        return nil
    }
    let combinedReading = continuation.reading + previewHiragana
    guard combinedReading.count <= maxCombinedConversionContinuationReadingCount else {
        return nil
    }
    let effectiveContext: String
    switch resolveConversionContinuationContext(
        context: context,
        continuationSurface: continuation.surface
    ) {
    case .skip:
        return nil
    case .use(let resolvedContext):
        effectiveContext = resolvedContext
    }
    var combinedComposingText = ComposingText()
    combinedComposingText.insertAtCursorPosition(combinedReading, inputStyle: .direct)
    return ConversionContinuationRequest(
        continuation: continuation,
        context: effectiveContext,
        combinedComposingText: combinedComposingText
    )
}

private func normalizedConversionContinuationRuby(_ text: String) -> String {
    var scalars = String.UnicodeScalarView()
    scalars.reserveCapacity(text.unicodeScalars.count)
    for scalar in text.unicodeScalars {
        let value = scalar.value
        switch value {
        case 0x3041...0x3096:
            scalars.append(UnicodeScalar(value + 0x60)!)
        case 0x309D:
            scalars.append(UnicodeScalar(0x30FD)!)
        case 0x309E:
            scalars.append(UnicodeScalar(0x30FE)!)
        default:
            scalars.append(scalar)
        }
    }
    return String(scalars)
}

/// Slices a candidate produced for `continuation.reading + currentReading`
/// down to the part that belongs to the current composition.
func sliceConversionContinuationCandidate(
    _ candidate: Candidate,
    continuation: ConversionContinuation,
    currentReading: String
) -> Candidate? {
    guard !continuation.reading.isEmpty,
          !continuation.surface.isEmpty,
          !currentReading.isEmpty else {
        return nil
    }

    let committedRubyCount = continuation.reading.count
    var consumedRubyCount = 0
    var consumedSurface = ""
    var suffixData: [DicdataElement]?
    var splitAtBoundary = false

    for (index, element) in candidate.data.enumerated() {
        let elementRubyCount = element.ruby.count
        let nextRubyCount = consumedRubyCount + elementRubyCount
        if nextRubyCount < committedRubyCount {
            consumedSurface += element.word
            consumedRubyCount = nextRubyCount
            continue
        }
        if nextRubyCount == committedRubyCount {
            guard consumedSurface + element.word == continuation.surface else {
                return nil
            }
            suffixData = Array(candidate.data.dropFirst(index + 1))
            break
        }

        let consumedInsideNode = committedRubyCount - consumedRubyCount
        guard consumedInsideNode > 0,
              consumedInsideNode < elementRubyCount,
              continuation.surface.hasPrefix(consumedSurface) else {
            return nil
        }
        let remainingCommittedSurface = continuation.surface.dropFirst(consumedSurface.count)
        guard !remainingCommittedSurface.isEmpty,
              element.word.hasPrefix(remainingCommittedSurface) else {
            return nil
        }
        let remainingWord = element.word.dropFirst(remainingCommittedSurface.count)
        let remainingRuby = element.ruby.dropFirst(consumedInsideNode)
        guard !remainingWord.isEmpty, !remainingRuby.isEmpty else {
            return nil
        }
        let splitElement = DicdataElement(
            word: String(remainingWord),
            ruby: String(remainingRuby),
            lcid: element.lcid,
            rcid: element.rcid,
            mid: element.mid,
            value: element.value(),
            metadata: element.metadata
        )
        suffixData = [splitElement] + candidate.data.dropFirst(index + 1)
        splitAtBoundary = true
        break
    }

    guard let suffixData, !suffixData.isEmpty else {
        return nil
    }

    let suffixRuby = suffixData.reduce(into: "") { $0 += $1.ruby }
    let normalizedSuffixRuby = normalizedConversionContinuationRuby(suffixRuby)
    let normalizedCurrentReading = normalizedConversionContinuationRuby(currentReading)
    guard !normalizedSuffixRuby.isEmpty,
          normalizedSuffixRuby.hasPrefix(normalizedCurrentReading)
            || normalizedCurrentReading.hasPrefix(normalizedSuffixRuby) else {
        return nil
    }

    let shiftedCorrections = candidate.keyboardTypoCorrections.compactMap {
        correction -> KeyboardTypoCorrectionProvenance? in
        let range = correction.originalSurfaceRange
        guard range.lowerBound >= committedRubyCount else {
            return nil
        }
        return KeyboardTypoCorrectionProvenance(
            kind: correction.kind,
            originalSurfaceRange: (range.lowerBound - committedRubyCount)
                ..< (range.upperBound - committedRubyCount)
        )
    }

    return Candidate(
        text: suffixData.reduce(into: "") { $0 += $1.word },
        value: candidate.value,
        composingCount: .surfaceCount(suffixRuby.count),
        lastMid: suffixData.last?.mid ?? candidate.lastMid,
        data: suffixData,
        actions: candidate.actions,
        inputable: candidate.inputable,
        isLearningTarget: splitAtBoundary ? false : candidate.isLearningTarget,
        keyboardTypoCorrections: shiftedCorrections
    )
}

@_silgen_name("SetConversionContinuation")
@MainActor public func set_conversion_continuation(
    reading: UnsafePointer<CChar>,
    surface: UnsafePointer<CChar>
) -> Bool {
    setConversionContinuation(
        reading: String(cString: reading),
        surface: String(cString: surface)
    )
}

@MainActor
@discardableResult
func setConversionContinuation(reading: String, surface: String) -> Bool {
    if reading.isEmpty && surface.isEmpty {
        conversionContinuation = nil
        serverLog("DEBUG", "SetConversionContinuation: cleared")
        return true
    }
    guard !reading.isEmpty, !surface.isEmpty else {
        conversionContinuation = nil
        serverLog(
            "WARN",
            "SetConversionContinuation: rejected reason=incomplete reading_len=\(reading.count) surface_len=\(surface.count)"
        )
        return false
    }
    guard isKanaLikeConversionContinuationReading(reading) else {
        conversionContinuation = nil
        serverLog(
            "WARN",
            "SetConversionContinuation: rejected reason=non_kana_reading reading_len=\(reading.count)"
        )
        return false
    }
    guard reading.count <= maxConversionContinuationReadingCount,
          surface.count <= maxConversionContinuationSurfaceCount else {
        conversionContinuation = nil
        serverLog(
            "WARN",
            "SetConversionContinuation: rejected reason=over_cap reading_len=\(reading.count) surface_len=\(surface.count) max_reading_len=\(maxConversionContinuationReadingCount) max_surface_len=\(maxConversionContinuationSurfaceCount)"
        )
        return false
    }
    conversionContinuation = ConversionContinuation(reading: reading, surface: surface)
    serverLog(
        "DEBUG",
        "SetConversionContinuation: set reading_len=\(reading.count) surface_len=\(surface.count)"
    )
    return true
}

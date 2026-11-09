import Foundation
import KanaKanjiConverterModule
import Testing
@testable import azookey_server

private func automaticContinuationElement(word: String, ruby: String) -> DicdataElement {
    DicdataElement(
        word: word,
        ruby: ruby,
        cid: CIDData.一般名詞.cid,
        mid: MIDData.一般.mid,
        value: 0
    )
}

/// User entries carry the same score, so `得` with both readings is a real
/// near-tie. The primary fixture keeps the unambiguous `エ` reading usable for
/// the continuation cases, and `includeTokudokuEntry` restores the tie for the
/// confidence-gate case.
private func automaticContinuationDictionary(
    includeTokudokuEntry: Bool = true
) -> ReconversionDictionary {
    var entries: [DicdataElement] = [
        automaticContinuationElement(word: "追加", ruby: "ツイカ"),
        automaticContinuationElement(word: "情報", ruby: "ジョウホウ"),
        automaticContinuationElement(word: "追加情報", ruby: "ツイカジョウホウ"),
        automaticContinuationElement(word: "得", ruby: "エ"),
    ]
    if includeTokudokuEntry {
        entries.append(automaticContinuationElement(word: "得", ruby: "トク"))
    }
    entries.append(contentsOf: [
        automaticContinuationElement(word: "食べ", ruby: "タベ"),
        automaticContinuationElement(word: "読ん", ruby: "ヨン"),
        automaticContinuationElement(word: "本", ruby: "ホン"),
        automaticContinuationElement(word: "を", ruby: "ヲ"),
        automaticContinuationElement(word: "東京", ruby: "トウキョウ"),
        automaticContinuationElement(word: "今日", ruby: "キョウ"),
        automaticContinuationElement(word: "新聞", ruby: "シンブン"),
    ])
    var dictionary = ReconversionDictionary()
    dictionary.replaceUserEntries(entries)
    return dictionary
}

@MainActor
private func withAutomaticContinuationDictionary<T>(
    _ dictionary: ReconversionDictionary,
    _ body: () -> T
) -> T {
    let previousDictionary = reconversionDictionary
    reconversionDictionary = dictionary
    invalidateAutomaticConversionContinuationCache()
    defer {
        reconversionDictionary = previousDictionary
        invalidateAutomaticConversionContinuationCache()
    }
    return body()
}

@Test func automaticConversionContinuationReconstructsCommittedPrefix() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            let continuation = makeAutomaticConversionContinuation(
                context: "追加情報を得",
                currentReading: "たり",
                isCursorAtEnd: true
            )
            #expect(
                continuation
                    == ConversionContinuation(
                        reading: "ついかじょうほうをえ",
                        surface: "追加情報を得"
                    )
            )
        }
    }
}

@Test func automaticConversionContinuationStopsAtClausePunctuation() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            let continuation = makeAutomaticConversionContinuation(
                context: "今日は。追加情報を得",
                currentReading: "たり",
                isCursorAtEnd: true
            )
            #expect(
                continuation
                    == ConversionContinuation(
                        reading: "ついかじょうほうをえ",
                        surface: "追加情報を得"
                    )
            )
        }
    }
}

@Test func automaticConversionContinuationRejectsTrailingPunctuation() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得。",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
        }
    }
}

@Test func automaticConversionContinuationGatesOnInputs() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(automaticContinuationDictionary()) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "タリ",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "tari",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: String(repeating: "あ", count: 17),
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "たり",
                    isCursorAtEnd: false
                ) == nil
            )
        }
    }
}

@Test func automaticConversionContinuationIgnoresLatinAndNumberTokens() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得ABC123",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
            let continuation = makeAutomaticConversionContinuation(
                context: "abc123追加情報を得",
                currentReading: "たり",
                isCursorAtEnd: true
            )
            if let continuation {
                #expect(isKanaLikeConversionContinuationReading(continuation.reading))
                #expect(!continuation.surface.contains(where: { $0.isASCII }))
            }
        }
    }
}

@Test func automaticConversionContinuationCapsSurfaceLength() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            let context = String(repeating: "追加情報", count: 10)
            let continuation = makeAutomaticConversionContinuation(
                context: context,
                currentReading: "たり",
                isCursorAtEnd: true
            )
            #expect(continuation != nil)
            if let continuation {
                #expect(continuation.surface.count <= maxAutomaticConversionContinuationSurfaceCount)
                #expect(continuation.reading.count <= maxAutomaticConversionContinuationReadingCount)
                #expect(context.hasSuffix(continuation.surface))
            }
        }
    }
}

@Test func automaticConversionContinuationSkipsHiraganaOnlyContext() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            // The DP can read a pure-hiragana prefix, but rebuilding it cannot improve conversion.
            #expect(reconversionDictionary.inferReadingPieces(for: "これはしんぶん") != nil)
            #expect(
                makeAutomaticConversionContinuation(
                    context: "これはしんぶん",
                    currentReading: "を",
                    isCursorAtEnd: true
                ) == nil
            )
        }
    }
}

@Test func automaticConversionContinuationKeepsKanjiContextTail() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            guard let continuation = makeAutomaticConversionContinuation(
                context: "これは新聞",
                currentReading: "を",
                isCursorAtEnd: true
            ) else {
                Issue.record("mixed continuation was not built")
                return
            }
            #expect(continuation.surface.hasSuffix("新聞"))
            #expect(
                continuation.surface.contains {
                    !isKanaLikeConversionContinuationReading(String($0))
                }
            )
            #expect(isKanaLikeConversionContinuationReading(continuation.reading))
            #expect(continuation.reading.hasSuffix("しんぶん"))
        }
    }
}

@Test func automaticConversionContinuationCapsCombinedReadingLength() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            let currentReading = String(repeating: "あ", count: 16)
            let continuation = makeAutomaticConversionContinuation(
                context: "追加情報を得",
                currentReading: currentReading,
                isCursorAtEnd: true
            )
            #expect(continuation != nil)
            if let continuation {
                #expect(
                    continuation.reading.count + currentReading.count
                        <= maxCombinedConversionContinuationReadingCount
                )
            }
        }
    }
}

@Test func automaticConversionContinuationRejectsAmbiguousReconstruction() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: true)
        ) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "得",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
        }
    }
}

@Test func automaticConversionContinuationDisableIsPerContext() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) != nil
            )
            disableAutomaticConversionContinuation(context: "追加情報を得")
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を得",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "追加情報を",
                    currentReading: "たり",
                    isCursorAtEnd: true
                ) != nil
            )
        }
    }
}

@Test func automaticConversionContinuationBuildsCombinedRequest() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            guard let continuation = makeAutomaticConversionContinuation(
                context: "追加情報を得",
                currentReading: "たり",
                isCursorAtEnd: true
            ) else {
                Issue.record("automatic continuation was not built")
                return
            }
            let request = makeConversionContinuationRequest(
                continuation: continuation,
                allowZenzai: true,
                isCursorAtEnd: true,
                previewHiragana: "たり",
                hasTrailingUncompletedRoman: false,
                context: "追加情報を得"
            )
            #expect(request?.continuation == continuation)
            #expect(request?.context == "")
            #expect(request?.combinedComposingText.convertTarget == "ついかじょうほうをえたり")
        }
    }
}

@Test func katakanaToHiraganaKeepsLongVowelAndRareKatakana() {
    #expect(katakanaToHiragana("ツイカジョウホウヲエ") == "ついかじょうほうをえ")
    #expect(katakanaToHiragana("タロー") == "たろー")
    #expect(katakanaToHiragana("ヷヺ") == "ヷヺ")
}

@Test func inferReadingPiecesCoversEveryCharacter() async {
    await MainActor.run {
        withAutomaticContinuationDictionary(
            automaticContinuationDictionary(includeTokudokuEntry: false)
        ) {
            guard let resolved = reconversionDictionary.inferReadingPieces(for: "追加情報を得") else {
                Issue.record("inferReadingPieces returned no full-coverage path")
                return
            }
            #expect(!resolved.pieces.isEmpty)
            #expect(resolved.pieces.map(\.surface).joined() == "追加情報を得")
            #expect(resolved.pieces.map(\.reading).joined() == "ツイカジョウホウヲエ")
            #expect(resolved.pieces.allSatisfy { !$0.surface.isEmpty && !$0.reading.isEmpty })
            #expect(resolved.score < 0)
            if let secondBestScore = resolved.secondBestScore {
                #expect(secondBestScore <= resolved.score)
            }
        }
    }
}

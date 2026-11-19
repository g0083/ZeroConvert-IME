import Foundation
import KanaKanjiConverterModule
import Testing
@testable import azookey_server

private func testPackageRootURL() -> URL {
    URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
}

private func makeStubCandidate(
    word: String,
    ruby: String,
    composingCount: ComposingCount
) -> Candidate {
    Candidate(
        text: word,
        value: -1,
        composingCount: composingCount,
        lastMid: MIDData.一般.mid,
        data: [
            DicdataElement(
                word: word,
                ruby: ruby,
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            )
        ]
    )
}

@MainActor
private func collectComposedCandidates() -> [(text: String, subtext: String, count: Int32)] {
    var length: CInt = 0
    let ptr = get_composed_text(lengthPtr: &length)
    defer { free_candidate_list(ptr, length) }
    var out: [(text: String, subtext: String, count: Int32)] = []
    out.reserveCapacity(Int(length))
    for i in 0..<Int(length) {
        guard let c = ptr[i]?.pointee else { continue }
        out.append((
            text: c.text.map { String(cString: $0) } ?? "",
            subtext: c.subtext.map { String(cString: $0) } ?? "",
            count: c.correspondingCount
        ))
    }
    return out
}

@MainActor
private func withPredictiveServerState<T>(
    dictionaryURL: URL,
    reconversion: ReconversionDictionary? = nil,
    memoryURL: URL? = nil,
    _ body: () throws -> T
) rethrows -> T {
    let previousConverter = converter
    let previousSupplement = normalNBestSupplementConverter
    let previousComposing = composingText
    let previousSnapshots = composingTextSnapshots
    let previousInputStyle = currentInputStyle
    let previousLearningType = currentLearningType
    let previousMemoryURL = currentLearningMemoryDirectoryURL
    let previousCache = learningCandidateCache
    let previousOverrides = learningSelectionOverrides
    let previousZenzaiEnable = config["enable"]
    let previousPredictive = config["predictiveConversion"]
    let previousPostComposition = config["postCompositionPrediction"]
    let previousContext = config["context"]
    let previousContinuation = conversionContinuation
    let previousCommitted = lastCommittedContinuation
    let previousReconversion = reconversionDictionary
    let previousBigrams = postCompositionBigramStore
    let previousLastPostComp = lastCommittedPostCompositionCandidate

    defer {
        converter = previousConverter
        normalNBestSupplementConverter = previousSupplement
        composingText = previousComposing
        composingTextSnapshots = previousSnapshots
        currentInputStyle = previousInputStyle
        currentLearningType = previousLearningType
        currentLearningMemoryDirectoryURL = previousMemoryURL
        learningCandidateCache = previousCache
        learningSelectionOverrides = previousOverrides
        config["enable"] = previousZenzaiEnable
        config["predictiveConversion"] = previousPredictive
        config["postCompositionPrediction"] = previousPostComposition
        config["context"] = previousContext
        conversionContinuation = previousContinuation
        lastCommittedContinuation = previousCommitted
        reconversionDictionary = previousReconversion
        postCompositionBigramStore = previousBigrams
        clearPostCompositionPredictionState()
        lastCommittedPostCompositionCandidate = previousLastPostComp
        invalidateAutomaticConversionContinuationCache()
    }

    converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
    normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: false)
    composingText = ComposingText()
    composingTextSnapshots = []
    currentInputStyle = .roman2kana
    currentLearningType = memoryURL == nil ? .nothing : .inputAndOutput
    currentLearningMemoryDirectoryURL = memoryURL
    learningCandidateCache.removeAll()
    learningSelectionOverrides.removeAll()
    postCompositionBigramStore.removeAll()
    clearPostCompositionPredictionState()
    config["enable"] = true
    config["predictiveConversion"] = true
    config["postCompositionPrediction"] = nil
    config["context"] = ""
    conversionContinuation = nil
    lastCommittedContinuation = nil
    if let reconversion {
        reconversionDictionary = reconversion
    }
    invalidateAutomaticConversionContinuationCache()
    return try body()
}

@Test func midRomajiPreservesCachedPredictionsUntilSyllableCompletes() async {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")

    await MainActor.run {
        withPredictiveServerState(dictionaryURL: dictionaryURL) {
            for ch in "yoro" {
                composingText.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
            }
            let atYoro = collectComposedCandidates().map(\.text)
            #expect(atYoro.contains("よろしく"), "yoro candidates: \(atYoro.prefix(15))")

            composingText.insertAtCursorPosition("s", inputStyle: .roman2kana)
            let atYoros = collectComposedCandidates().map(\.text)
            #expect(atYoros.contains("よろしく"), "yoros lost cached prediction: \(atYoros.prefix(15))")

            composingText.insertAtCursorPosition("h", inputStyle: .roman2kana)
            let atYorosh = collectComposedCandidates().map(\.text)
            #expect(atYorosh.contains("よろしく"), "yorosh lost cached prediction: \(atYorosh.prefix(15))")

            composingText.insertAtCursorPosition("i", inputStyle: .roman2kana)
            let atYoroshi = collectComposedCandidates().map(\.text)
            #expect(atYoroshi.contains("よろしく"), "yoroshi candidates: \(atYoroshi.prefix(15))")

            composingText = ComposingText()
            for ch in "yoro" {
                composingText.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
            }
            #expect(collectComposedCandidates().map(\.text).contains("よろしく"))
            composingText.insertAtCursorPosition("k", inputStyle: .roman2kana)
            let atYorok = collectComposedCandidates().map(\.text)
            #expect(!atYorok.contains("よろしく"), "yorok should filter out non-K-row cached entries: \(atYorok.prefix(15))")
        }
    }
}

@MainActor
@Test func pendingRomajiCanFindCompletionsWithoutAnEarlierCandidateRequest() {
    let dictionaryURL = testPackageRootURL().appending(path: "azooKey_dictionary_storage/Dictionary")
    withPredictiveServerState(dictionaryURL: dictionaryURL) {
        let previousModelPath = config["embeddedModelPath"]
        let testModelPath = ProcessInfo.processInfo.environment["ZEROCON_PENDING_PREDICTION_TEST_MODEL"]
        config["embeddedModelPath"] = testModelPath
        config["enable"] = testModelPath != nil
        defer { config["embeddedModelPath"] = previousModelPath }
        func append(_ keys: String) {
            for key in keys {
                composingText.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
            }
        }
        @MainActor func expectCandidate(_ text: String) {
            let candidates = collectComposedCandidates()
            let candidate = candidates.prefix(6).first { $0.text == text }
            #expect(candidate?.count == Int32(composingText.input.count),
                    "\(composingText.convertTarget) -> \(candidates.prefix(10))")
        }

        append("shin")
        #expect(composingText.convertTarget == "しn")
        _ = collectComposedCandidates()
        append("g")
        #expect(composingText.convertTarget == "しんg")
        expectCandidate("シンギュラリティ")

        // A rapid burst of keys can skip all preceding candidate requests.
        clear_text()
        append("sing")
        expectCandidate("シンギュラリティ")
        composingText.deleteBackwardFromCursorPosition(count: 1)
        append("k")
        #expect(composingText.convertTarget == "しんk")
        #expect(!collectComposedCandidates().contains { $0.text == "シンギュラリティ" })
        composingText.deleteBackwardFromCursorPosition(count: 1)
        append("gy")
        expectCandidate("シンギュラリティ")
        append("u")
        #expect(composingText.convertTarget == "しんぎゅ")
        #expect(collectComposedCandidates().contains { $0.text == "シンギュラリティ" })

        _ = composingText.moveCursorFromCursorPosition(count: -2)
        var length: CInt = 0
        let cursorCandidates = get_composed_text_for_cursor_range(lengthPtr: &length)
        defer { free_candidate_list(cursorCandidates, length) }
        #expect(!(0..<Int(length)).contains { index in
            cursorCandidates[index]?.pointee.text.map { String(cString: $0) } == "シンギュラリティ"
        })

        clear_text()
        append("yorosh")
        expectCandidate("よろしく")
        clear_text()
        config["predictiveConversion"] = false
        append("shing")
        #expect(!collectComposedCandidates().contains { $0.text == "シンギュラリティ" })
    }
}

@Test func learningSelectionOverridesPrioritizeRecentAndPromoteAboveSubClauses() async throws {
    let full1 = makeStubCandidate(word: "明日", ruby: "アシタ", composingCount: .inputCount(3))
    let full2 = makeStubCandidate(word: "朝", ruby: "アシタ", composingCount: .inputCount(3))
    let dictPred = makeStubCandidate(word: "明日の天気", ruby: "アシタノテンキ", composingCount: .inputCount(3))
    let partial = makeStubCandidate(word: "あ", ruby: "ア", composingCount: .inputCount(1))

    await MainActor.run {
        let previousLearningType = currentLearningType
        let previousOverrides = learningSelectionOverrides
        let previousPredictive = config["predictiveConversion"]
        defer {
            currentLearningType = previousLearningType
            learningSelectionOverrides = previousOverrides
            config["predictiveConversion"] = previousPredictive
        }

        currentLearningType = .inputAndOutput
        config["predictiveConversion"] = true
        learningSelectionOverrides = LearningSelectionStore()

        // Record older phrases first, then the most recent phrase last; recency must win deterministically.
        learningSelectionOverrides.record(output: "明日の打ち合わせ", for: "アシタノウチアワセ")
        learningSelectionOverrides.record(output: "明日の天気", for: "アシタノテンキ")
        learningSelectionOverrides.record(output: "明日お願いします", for: "アシタオネガイシマス")

        var prioritized = prioritizeLearningSelectionOverrides(
            [full1, full2, dictPred, partial],
            ruby: "あした",
            candidate: { $0 },
            synthesizeElement: { $0 }
        )

        #expect(prioritized.map(\.text) == ["明日", "朝", "明日お願いします", "明日の天気", "あ"])
        promoteKanaReadingCompletion(in: &prioritized, normalizedReading: "アシタ")
        #expect(prioritized.map(\.text) == ["明日", "明日お願いします", "朝", "明日の天気", "あ"])
    }
}

@Test func kanaPredictionPromotionPreservesNormalCandidatesAndRequiresAPrefixMatch() {
    let top = makeStubCandidate(word: "今日", ruby: "キョウ", composingCount: .surfaceCount(3))
    let alternative = makeStubCandidate(word: "京", ruby: "キョウ", composingCount: .surfaceCount(3))
    let differentReading = makeStubCandidate(word: "教室", ruby: "キョウシツ", composingCount: .surfaceCount(3))
    let completion = makeStubCandidate(word: "今日中", ruby: "キョウジュウ", composingCount: .surfaceCount(3))
    let nextCompletion = makeStubCandidate(word: "今日は", ruby: "キョウハ", composingCount: .surfaceCount(3))
    var corrected = differentReading
    corrected.data[0].metadata.insert(.isKeyboardTypoCorrection)
    let original = [top, alternative, corrected, completion, nextCompletion]
    var ranked = original

    promoteKanaReadingCompletion(in: &ranked, normalizedReading: "キョウ")
    #expect(ranked.map(\.text) == ["今日", "今日中", "京", "教室", "今日は"])
    #expect(!extendsKanaReading(completion, normalizedReading: "キョウシ"))

    for reading in ["キ", "キョウノ"] {
        ranked = original
        promoteKanaReadingCompletion(in: &ranked, normalizedReading: reading)
        #expect(ranked.map(\.text) == original.map(\.text))
    }
}

@Test func commitLearningSyncsSupplementConverterCacheInSameSession() async throws {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-sync-prediction-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    try await MainActor.run {
        try withPredictiveServerState(dictionaryURL: dictionaryURL, memoryURL: memoryURL) {
            let options = ConvertRequestOptions(
                requireJapanesePrediction: .manualMix,
                requireEnglishPrediction: .disabled,
                keyboardLanguage: .ja_JP,
                learningType: .inputAndOutput,
                memoryDirectoryURL: memoryURL,
                sharedContainerURL: memoryURL,
                textReplacer: .init {
                    packageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
                },
                specialCandidateProviders: nil,
                zenzaiMode: .off,
                metadata: nil
            )

            // Populate normalNBestSupplementConverter's memoryLOUDS cache BEFORE any commit (caches nil).
            var warmupPrefix = ComposingText()
            warmupPrefix.insertAtCursorPosition("おさ", inputStyle: .direct)
            let before = normalNBestSupplementConverter.requestCandidates(warmupPrefix, options: options)
            let beforePreds = before.predictionResults.map(\.text)
            #expect(!beforePreds.contains("おさとう"), "unexpected baseline: \(beforePreds)")

            // Commit a custom learned phrase via converter (`commit_learning_candidate`).
            let customElement = DicdataElement(
                word: "おさとう特製",
                ruby: "オサトウトクセイ",
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -2.0
            )
            let customCandidate = Candidate(
                text: "おさとう特製",
                value: -2.0,
                composingCount: .surfaceCount(8),
                lastMid: MIDData.一般.mid,
                data: [customElement],
                isLearningTarget: true
            )
            let batchFirstId = try #require(cacheLearningCandidates([customCandidate]))
            let candidateId = learningCandidateId(at: 0, batchFirstId: batchFirstId)
            #expect(commit_learning_candidate(candidateId: candidateId, commitKind: 1))

            // Query normalNBestSupplementConverter again without rebuilding it; cache invalidation must pick up commit.
            var prefix = ComposingText()
            prefix.insertAtCursorPosition("おさと", inputStyle: .direct)
            let after = normalNBestSupplementConverter.requestCandidates(prefix, options: options)
            let afterPreds = after.predictionResults.map(\.text)
            #expect(afterPreds.contains("おさとう特製"), "supplement converter stale cache: \(afterPreds)")
        }
    }
}

@Test func continuationConnectsPredictionAcrossPartialCommitAndPureKanaAndCommittedContext() async throws {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-continuation-pred-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    // 1. Explicit partial-commit continuation feeds normalNBestSupplementConverter + mid-romaji cache.
    await MainActor.run {
        withPredictiveServerState(dictionaryURL: dictionaryURL) {
            config["context"] = ""
            conversionContinuation = ConversionContinuation(reading: "とうきょうへ", surface: "東京へ")
            composingText.insertAtCursorPosition("i", inputStyle: .roman2kana)
            let withTokyoContext = collectComposedCandidates()
            let withTokyoTexts = withTokyoContext.map(\.text)
            #expect(withTokyoContext.allSatisfy { !$0.text.contains("東京へ") && $0.count == 1 })
            #expect(withTokyoTexts.contains { $0.hasPrefix("行") || $0.hasPrefix("い") }, "expected linked verb for 東京へ+い: \(withTokyoTexts)")

            // Typing trailing consonant 'k' preserves contextual cache via continuation-prediction session.
            composingText.insertAtCursorPosition("k", inputStyle: .roman2kana)
            let midConsonant = collectComposedCandidates()
            let midTexts = midConsonant.map(\.text)
            #expect(midTexts.contains { $0.hasPrefix("行") }, "expected cached K-row verb for 東京へ+いk: \(midTexts)")
        }
    }

    // 2. Full commit (`commit_learning_candidate` + `clear_text`) connects to subsequent input when context matches,
    //    and isolates cleanly when context moves away (empty or mismatched).
    try await MainActor.run {
        try withPredictiveServerState(dictionaryURL: dictionaryURL, memoryURL: memoryURL) {
            // Use real converter candidates when available so authentic particle rcid/mid drive predictions.
            var tokyoInput = ComposingText()
            tokyoInput.insertAtCursorPosition("とうきょうへ", inputStyle: .direct)
            let tokyoCandidates = normalNBestSupplementConverter.requestCandidates(
                tokyoInput,
                options: ConvertRequestOptions(
                    requireJapanesePrediction: .disabled,
                    requireEnglishPrediction: .disabled,
                    keyboardLanguage: .ja_JP,
                    learningType: .inputAndOutput,
                    memoryDirectoryURL: memoryURL,
                    sharedContainerURL: memoryURL,
                    textReplacer: .init {
                        packageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
                    },
                    specialCandidateProviders: nil,
                    zenzaiMode: .off,
                    metadata: nil
                )
            ).mainResults
            let candidate = tokyoCandidates.first(where: { $0.text == "東京へ" }) ?? Candidate(
                text: "東京へ",
                value: -2.0,
                composingCount: .surfaceCount(6),
                lastMid: MIDData.一般.mid,
                data: [
                    DicdataElement(word: "東京", ruby: "トウキョウ", cid: CIDData.地名一般.cid, mid: MIDData.一般.mid, value: -1.0),
                    DicdataElement(word: "へ", ruby: "ヘ", cid: CIDData.係助詞ハ.cid, mid: MIDData.一般.mid, value: -1.0),
                ],
                isLearningTarget: true
            )
            let firstId = try #require(cacheLearningCandidates([candidate]))
            let id = learningCandidateId(at: 0, batchFirstId: firstId)
            #expect(commit_learning_candidate(candidateId: id, commitKind: 0))
            clear_text()
            #expect(lastCommittedContinuation == ConversionContinuation(reading: "とうきょうへ", surface: "東京へ"))

            config["context"] = "東京へ"
            composingText.insertAtCursorPosition("い", inputStyle: .direct)
            let linkedAfterCommit = collectComposedCandidates()
            #expect(linkedAfterCommit.contains { $0.text.hasPrefix("行") && $0.count == 1 },
                    "expected linked prediction after full commit: \(linkedAfterCommit.map(\.text))")

            // Clearing/moving editor context to empty or another phrase must NOT apply the old committed prefix.
            composingText = ComposingText()
            composingText.insertAtCursorPosition("い", inputStyle: .direct)
            config["context"] = ""
            let withoutContext = collectComposedCandidates()
            #expect(withoutContext.allSatisfy { !$0.text.contains("東京へ") })
            #expect(
                makePredictiveConversionContinuationRequest(
                    continuation: lastCommittedContinuation,
                    allowPredictive: true,
                    isCursorAtEnd: true,
                    previewComposingText: composingText,
                    context: "",
                    requireContextSuffixMatch: true
                ) == nil
            )
        }
    }

    // 3. Pure-kana automatic continuation unlocks prediction linkage without changing Zenzai main continuation gating.
    await MainActor.run {
        var reconv = ReconversionDictionary()
        reconv.replaceUserEntries([
            DicdataElement(word: "これは", ruby: "コレハ", cid: CIDData.一般名詞.cid, mid: MIDData.一般.mid, value: 0),
        ])
        withPredictiveServerState(dictionaryURL: dictionaryURL, reconversion: reconv) {
            #expect(
                makeAutomaticConversionContinuation(
                    context: "これは",
                    currentReading: "き",
                    isCursorAtEnd: true
                ) == nil
            )
            #expect(
                makeAutomaticConversionContinuation(
                    context: "これは",
                    currentReading: "き",
                    isCursorAtEnd: true,
                    allowPureKana: true
                ) == ConversionContinuation(reading: "これは", surface: "これは")
            )
        }
    }
}

private func decodeJsonDict(_ ptr: UnsafeMutablePointer<CChar>?) throws -> [String: Any] {
    let raw = try #require(ptr)
    defer { free_c_string(raw) }
    let data = try #require(String(cString: raw).data(using: .utf8))
    return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
}

@MainActor
private func callPostCompositionPredictions(
    candidateId: UInt64 = 0,
    committedText: String,
    committedReading: String = "",
    context: String = ""
) throws -> [String: Any] {
    try committedText.withCString { textPtr in
        try committedReading.withCString { readingPtr in
            try context.withCString { contextPtr in
                try decodeJsonDict(get_post_composition_predictions(
                    candidateId: candidateId,
                    committedText: textPtr,
                    committedReading: readingPtr,
                    context: contextPtr
                ))
            }
        }
    }
}

@MainActor
private func callSelectPostComposition(
    predictionId: UInt64,
    context: String = "",
    allowLearning: Bool = true
) throws -> [String: Any] {
    try context.withCString { contextPtr in
        try decodeJsonDict(select_post_composition_prediction(
            predictionId: predictionId,
            context: contextPtr,
            allowLearning: allowLearning
        ))
    }
}

@Test func postCompositionPredictsNextWordsChainsMultiHopAndConnectsContinuation() async throws {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-postcomp-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    try await MainActor.run {
        try withPredictiveServerState(dictionaryURL: dictionaryURL, memoryURL: memoryURL) {
            for ch in "とうきょう" {
                composingText.insertAtCursorPosition(String(ch), inputStyle: .direct)
            }
            let tokyoCandidates = collectComposedCandidates()
            let tokyoItem = try #require(tokyoCandidates.first(where: { $0.text == "東京" }))
            // Find the exact cached candidateId matching "東京".
            var resolvedTokyoId: UInt64 = 0
            for probe in UInt64(1)...UInt64(max(1, tokyoCandidates.count)) {
                if learningCandidate(probe)?.text == "東京" {
                    resolvedTokyoId = probe
                    break
                }
            }
            #expect(resolvedTokyoId > 0 || tokyoItem.text == "東京")

            let firstPayload = try callPostCompositionPredictions(
                candidateId: resolvedTokyoId,
                committedText: "東京",
                committedReading: "とうきょう",
                context: "東京"
            )
            let firstPreds = try #require(firstPayload["predictions"] as? [[String: Any]])
            #expect(!firstPreds.isEmpty, "expected post-composition predictions for 東京")
            #expect(firstPreds.allSatisfy { !(($0["text"] as? String) ?? "").isEmpty })
            // Non-emoji collocations (e.g. particles/suffixes) must appear before any trailing emoji.
            let firstEmojiIdx = firstPreds.firstIndex { ($0["isEmoji"] as? Bool) == true }
            let firstTextIdx = firstPreds.firstIndex { ($0["isEmoji"] as? Bool) == false }
            #expect(firstTextIdx == 0)
            if let firstEmojiIdx {
                #expect(firstEmojiIdx > 0)
            }

            // Tap a non-terminal particle prediction (e.g. "に" / "へ" / "の" / "は") and verify chaining + continuation.
            let nextHopChoice = try #require(firstPreds.first { entry in
                let text = entry["text"] as? String ?? ""
                return (entry["isTerminal"] as? Bool) == false && ["に", "へ", "の", "は"].contains(text)
            }, "expected particle prediction in: \(firstPreds.map { $0["text"] ?? "" })")
            let nextId = try #require((nextHopChoice["id"] as? String).flatMap(UInt64.init))
            let nextText = try #require(nextHopChoice["text"] as? String)

            let chainedPayload = try callSelectPostComposition(
                predictionId: nextId,
                context: "東京",
                allowLearning: true
            )
            #expect((chainedPayload["accepted"] as? Bool) == true)
            #expect((chainedPayload["terminal"] as? Bool) == false)
            #expect((chainedPayload["leftSurface"] as? String) == "東京\(nextText)")
            let secondPreds = try #require(chainedPayload["predictions"] as? [[String: Any]])
            #expect(!secondPreds.isEmpty, "expected next-hop predictions after 東京\(nextText)")

            // Typing immediately after tapping a post-composition chip must link with lastCommittedContinuation!
            #expect(lastCommittedContinuation?.surface == "東京\(nextText)")

            // Terminal punctuation ends the chain with empty next predictions and clears continuation.
            let freshAfterNoun = try callPostCompositionPredictions(
                committedText: "東京",
                committedReading: "とうきょう",
                context: "東京"
            )
            let freshPreds = try #require(freshAfterNoun["predictions"] as? [[String: Any]])
            if let periodChoice = freshPreds.first(where: { ($0["text"] as? String) == "。" }) {
                let periodId = try #require((periodChoice["id"] as? String).flatMap(UInt64.init))
                let terminalPayload = try callSelectPostComposition(
                    predictionId: periodId,
                    context: "東京",
                    allowLearning: true
                )
                #expect((terminalPayload["accepted"] as? Bool) == true)
                #expect((terminalPayload["terminal"] as? Bool) == true)
                let afterTerminal = try #require(terminalPayload["predictions"] as? [[String: Any]])
                #expect(afterTerminal.isEmpty)
                #expect(lastCommittedContinuation == nil)
            }

            // Context mismatch must reject stale predictions, and disabled setting returns empty.
            let mismatched = try callPostCompositionPredictions(
                committedText: "東京",
                committedReading: "とうきょう",
                context: "大阪"
            )
            #expect((mismatched["predictions"] as? [[String: Any]])?.isEmpty == true)

            config["predictiveConversion"] = false
            let disabled = try callPostCompositionPredictions(
                committedText: "東京",
                committedReading: "とうきょう",
                context: "東京"
            )
            #expect((disabled["predictions"] as? [[String: Any]])?.isEmpty == true)

            // Explicit postCompositionPrediction toggle works independently of predictiveConversion.
            config["predictiveConversion"] = true
            config["postCompositionPrediction"] = false
            let explicitlyDisabled = try callPostCompositionPredictions(
                committedText: "東京",
                committedReading: "とうきょう",
                context: "東京"
            )
            #expect((explicitlyDisabled["predictions"] as? [[String: Any]])?.isEmpty == true)

            config["predictiveConversion"] = false
            config["postCompositionPrediction"] = true
            let explicitlyEnabled = try callPostCompositionPredictions(
                committedText: "東京",
                committedReading: "とうきょう",
                context: "東京"
            )
            let explicitlyEnabledPreds = try #require(explicitlyEnabled["predictions"] as? [[String: Any]])
            #expect(!explicitlyEnabledPreds.isEmpty)
        }
    }
}

@Test func postCompositionBigramLearningPrioritizesCommittedPairsReloadsAndResets() async throws {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-bigram-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    try await MainActor.run {
        try withPredictiveServerState(dictionaryURL: dictionaryURL, memoryURL: memoryURL) {
            let firstCand = Candidate(
                text: "お疲れ様",
                value: -2.0,
                composingCount: .surfaceCount(6),
                lastMid: MIDData.一般.mid,
                data: [DicdataElement(word: "お疲れ様", ruby: "オツカレサマ", cid: CIDData.一般名詞.cid, mid: MIDData.一般.mid, value: -2.0)],
                isLearningTarget: true
            )
            let secondCand = Candidate(
                text: "でした",
                value: -1.5,
                composingCount: .surfaceCount(3),
                lastMid: MIDData.一般.mid,
                data: [DicdataElement(word: "でした", ruby: "デシタ", cid: CIDData.助動詞デス基本形.cid, mid: MIDData.一般.mid, value: -1.5)],
                isLearningTarget: true
            )
            let firstId = try #require(cacheLearningCandidates([firstCand]))
            config["context"] = ""
            #expect(commit_learning_candidate(candidateId: firstId, commitKind: 0))

            let secondId = try #require(cacheLearningCandidates([secondCand]))
            config["context"] = "お疲れ様"
            #expect(commit_learning_candidate(candidateId: secondId, commitKind: 0))

            // Simulate reload from disk into a fresh in-memory store.
            postCompositionBigramStore.removeAll()
            clearPostCompositionPredictionState()
            loadPostCompositionBigrams()

            let payload = try callPostCompositionPredictions(
                committedText: "お疲れ様",
                committedReading: "おつかれさま",
                context: "お疲れ様"
            )
            let preds = try #require(payload["predictions"] as? [[String: Any]])
            #expect(preds.first?["text"] as? String == "でした", "expected learned bigram at slot 0: \(preds)")

            // Cache deletion candidate id before switching to read-only mode so read-only forget is verified too.
            let delId = try #require(cacheLearningCandidates([secondCand]))

            // Read-only mode can resolve candidateId == 0 and read bigrams without mutating disk.
            currentLearningType = .onlyOutput
            let readOnlyPayload = try callPostCompositionPredictions(
                candidateId: 0,
                committedText: "お疲れ様",
                committedReading: "おつかれさま",
                context: "お疲れ様"
            )
            let readOnlyPreds = try #require(readOnlyPayload["predictions"] as? [[String: Any]])
            #expect(readOnlyPreds.first?["text"] as? String == "でした")

            // Forget removes the learned pair cleanly, and reset clears everything.
            #expect(forget_learning_candidate(candidateId: delId))
            let afterForget = try callPostCompositionPredictions(
                committedText: "お疲れ様",
                committedReading: "おつかれさま",
                context: "お疲れ様"
            )
            let afterForgetTexts = (afterForget["predictions"] as? [[String: Any]])?.compactMap { $0["text"] as? String } ?? []
            #expect(!afterForgetTexts.contains("でした"))

            currentLearningType = .inputAndOutput
            let reId1 = try #require(cacheLearningCandidates([firstCand]))
            config["context"] = ""
            #expect(commit_learning_candidate(candidateId: reId1, commitKind: 0))
            let reId2 = try #require(cacheLearningCandidates([secondCand]))
            config["context"] = "お疲れ様"
            #expect(commit_learning_candidate(candidateId: reId2, commitKind: 0))
            #expect(postCompositionBigramStore.count > 0)
            #expect(reset_learning_memory())
            #expect(postCompositionBigramStore.count == 0)
        }
    }
}

@Test func diagPostCompositionProbe() async throws {
    let packageRoot = testPackageRootURL()
    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-diag-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    try await MainActor.run {
        try withPredictiveServerState(dictionaryURL: dictionaryURL, memoryURL: memoryURL) {
            config["postCompositionPrediction"] = true
            let samples = [
                ("あしたは", "明日は"),
                ("とうきょうに", "東京に"),
                ("がっこうにいく", "学校に行く"),
                ("おつかれさま", "お疲れ様"),
                ("たべた", "食べた"),
                ("あたらしい", "新しい"),
                ("とうきょう", "東京"),
                ("とうきょうえき", "東京駅"),
                ("おはようございます", "おはようございます"),
                ("わたしは", "私は"),
                ("てんき", "天気"),
                ("えきまえ", "駅前"),
                ("にほんご", "日本語"),
                ("こんにちは", "こんにちは"),
                ("きょうはあめ", "今日は雨"),
            ]
            for (reading, surface) in samples {
                let left = try #require(resolvePostCompositionLeftSideCandidate(
                    candidateId: 0,
                    committedText: surface,
                    committedReading: reading
                ))
                let dataDesc = left.data.map { "\($0.word)(\($0.ruby),rcid=\($0.rcid),val=\($0.value()))" }.joined(separator: " | ")
                let textOnlyOptions = ConvertRequestOptions(
                    N_best: 10,
                    requireJapanesePrediction: .manualMix,
                    requireEnglishPrediction: .disabled,
                    keyboardLanguage: .ja_JP,
                    learningType: currentLearningType,
                    memoryDirectoryURL: memoryURL,
                    sharedContainerURL: converterRuntimeDirectoryURL(),
                    textReplacer: .empty,
                    specialCandidateProviders: nil,
                    zenzaiMode: .off,
                    metadata: nil
                )
                let raw = normalNBestSupplementConverter.requestPostCompositionPredictionCandidates(
                    leftSideCandidate: left,
                    options: textOnlyOptions
                )
                let rawDesc = raw.map { pred in
                    let kind = switch pred.type {
                    case .additional: "add"
                    case .replacement(let t, let r): "rep(\(t.map(\.word).joined())→\(r.map(\.word).joined()))"
                    }
                    return "\(pred.text)[\(kind),\(String(format: "%.2f", Double(pred.value)))]"
                }.joined(separator: ", ")
                print("DIAG [\(reading) -> \(surface)] data=[\(dataDesc)] RAW=[\(rawDesc)]")
            }
        }
    }
}

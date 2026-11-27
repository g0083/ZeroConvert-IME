import Foundation
import ffi
import KanaKanjiConverterModule
import Testing
import Synchronization
@testable import azookey_server

@Test func gpuLayerCountKeepsLegacyDefaultAndClampsSettings() {
    #expect(resolvedGpuLayerCount(nil) == 99)
    #expect(resolvedGpuLayerCount(4) == 4)
    #expect(resolvedGpuLayerCount(0) == 0)
    #expect(resolvedGpuLayerCount(-1) == 0)
    #expect(resolvedGpuLayerCount(Int.max) == 99)
}

private func row(_ input: String, _ output: String, _ next: String = "") -> RomajiTableRow {
    RomajiTableRow(input: input, output: output, next_input: next)
}

// InputStyleManager uses a process-wide unsynchronized dictionary. Keep test
// registrations on the same actor as every ComposingText lookup.
@MainActor private func makeTemporaryCustomInputStyle(_ rows: [RomajiTableRow]) throws -> InputStyle {
    let fileURL = FileManager.default.temporaryDirectory
        .appendingPathComponent("azookey-romaji-test-\(UUID().uuidString).tsv")
    let content = try #require(buildCustomRomajiTableContent(rows: rows))
    try content.write(to: fileURL, atomically: true, encoding: .utf8)
    defer {
        try? FileManager.default.removeItem(at: fileURL)
    }
    let tableName = "azookey-windows-test-romaji-\(UUID().uuidString)"
    let table = try InputStyleManager.loadTable(from: fileURL)
    InputStyleManager.registerInputStyle(table: table, for: tableName)
    return .mapped(id: .tableName(tableName))
}

private func tableMap(_ rows: [RomajiTableRow]) -> [String: String] {
    Dictionary(
        uniqueKeysWithValues: buildCustomRomajiTableEntries(rows: rows).map { ($0.key, $0.value) }
    )
}

private func packageRootURL() -> URL {
    URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
}

private func defaultWindowsRomajiRows() throws -> [RomajiTableRow] {
    let tableURL = packageRootURL()
        .deletingLastPathComponent()
        .appending(path: "crates")
        .appending(path: "shared")
        .appending(path: "src")
        .appending(path: "default_romaji_table.txt")
    let content = try String(contentsOf: tableURL, encoding: .utf8)

    return content.split(whereSeparator: \.isNewline).compactMap { line in
        let trimmed = String(line).trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else {
            return nil
        }
        let columns = trimmed.split(separator: "\t", omittingEmptySubsequences: false)
        guard columns.count >= 2, !columns[0].isEmpty, !columns[1].isEmpty else {
            return nil
        }
        return row(
            String(columns[0]),
            String(columns[1]),
            columns.count >= 3 ? String(columns[2]) : ""
        )
    }
}

private struct ReverseDictionaryFixtureEntry {
    let surface: String
    let reading: String
    let score: Float
}

private func appendUInt16LE(_ value: UInt16, to data: inout Data) {
    var value = value.littleEndian
    withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
}

private func appendUInt32LE(_ value: UInt32, to data: inout Data) {
    var value = value.littleEndian
    withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
}

private func makeReverseDictionaryFixture(_ entries: [ReverseDictionaryFixtureEntry]) -> Data {
    let records = entries.sorted {
        Array($0.surface.utf8).lexicographicallyPrecedes(Array($1.surface.utf8))
    }.map { entry in
        let surface = Data(entry.surface.utf8)
        let reading = Data(entry.reading.utf8)
        var record = Data()
        appendUInt16LE(UInt16(surface.count), to: &record)
        appendUInt16LE(UInt16(reading.count), to: &record)
        appendUInt32LE(entry.score.bitPattern, to: &record)
        record.append(surface)
        record.append(reading)
        return record
    }

    let headerSize = 8 + (records.count + 1) * 4
    var data = Data("AZR2".utf8)
    appendUInt32LE(UInt32(records.count), to: &data)
    var offset = headerSize
    for record in records {
        appendUInt32LE(UInt32(offset), to: &data)
        offset += record.count
    }
    appendUInt32LE(UInt32(offset), to: &data)
    for record in records {
        data.append(record)
    }
    return data
}

private func writeReverseDictionaryFixture(
    _ data: Data,
    under temporaryDirectory: URL
) throws -> URL {
    let dictionaryURL = temporaryDirectory.appendingPathComponent("Dictionary", isDirectory: true)
    let reverseURL = dictionaryURL.appendingPathComponent("Reverse", isDirectory: true)
    try FileManager.default.createDirectory(at: reverseURL, withIntermediateDirectories: true)
    try data.write(to: reverseURL.appendingPathComponent("reverse-v2.bin"))
    return dictionaryURL
}

@Test func reconversionDictionaryPrefersExactReadingsOverSegmentedReadings() throws {
    let temporaryDirectory = FileManager.default.temporaryDirectory
        .appendingPathComponent("azookey-reconversion-test-\(UUID().uuidString)", isDirectory: true)
    defer { try? FileManager.default.removeItem(at: temporaryDirectory) }
    let dictionaryURL = try writeReverseDictionaryFixture(
        makeReverseDictionaryFixture([
            .init(surface: "今日", reading: "キョウ", score: -1),
            .init(surface: "加", reading: "カ", score: -1),
            .init(surface: "減", reading: "ヘ", score: -1),
            .init(surface: "減", reading: "ゲン", score: -2),
            .init(surface: "加減", reading: "カゲン", score: -3),
            .init(surface: "統", reading: "ミツル", score: -1),
            .init(surface: "一", reading: "ハジメ", score: -1),
            .init(surface: "統一", reading: "トウイツ", score: -3),
            .init(surface: "日本", reading: "ニッポン", score: -1),
            .init(surface: "日本", reading: "ニホン", score: -2),
            .init(surface: "日本語", reading: "ニホンゴ", score: -0.5),
            .init(surface: "語", reading: "ゴ", score: -1),
        ]),
        under: temporaryDirectory
    )
    var dictionary = ReconversionDictionary()
    try dictionary.load(from: dictionaryURL)

    #expect(dictionary.recordCount == 12)
    #expect(dictionary.inferReadings(for: "加減") == ["カゲン"])
    #expect(dictionary.inferReadings(for: "統一") == ["トウイツ"])
    #expect(dictionary.inferReadings(for: "日本") == ["ニッポン", "ニホン"])
    #expect(dictionary.inferReadings(for: "日本", limit: 1) == ["ニッポン"])
    #expect(dictionary.inferReadings(for: "日本語") == ["ニホンゴ"])
    #expect(dictionary.inferReadings(for: "今日は") == ["キョウハ"])
    #expect(
        dictionary.inferReadings(for: "今日\n日本")
            == ["キョウ\nニッポン", "キョウ\nニホン"]
    )
    #expect(dictionary.inferReadings(for: "😀") == ["😀"])
    #expect(dictionary.inferReadings(for: "龘").isEmpty)
}

@Test func reconversionDictionaryRejectsTruncatedIndex() throws {
    let temporaryDirectory = FileManager.default.temporaryDirectory
        .appendingPathComponent("azookey-reconversion-corrupt-test-\(UUID().uuidString)", isDirectory: true)
    defer { try? FileManager.default.removeItem(at: temporaryDirectory) }
    var data = Data("AZR2".utf8)
    appendUInt32LE(1, to: &data)
    appendUInt32LE(12, to: &data)
    let dictionaryURL = try writeReverseDictionaryFixture(data, under: temporaryDirectory)
    var dictionary = ReconversionDictionary()
    var rejected = false

    do {
        try dictionary.load(from: dictionaryURL)
    } catch {
        rejected = true
    }

    #expect(rejected)
    #expect(dictionary.recordCount == 0)
}

@Test func engineRuntimeDirectoryUsesAppData() {
    let directory = engineRuntimeDirectoryURL(
        appDataPath: #"C:\Users\test\AppData\Roaming"#,
        temporaryDirectoryURL: URL(filePath: #"C:\Users\test\AppData\Local\Temp"#)
    )

    #expect(directory.lastPathComponent == "EngineRuntime")
    #expect(directory.deletingLastPathComponent().lastPathComponent == "Azookey")
    #expect(directory.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "Roaming")
}

@Test func engineRuntimeDirectoryFallsBackOutsideInstallDirectory() {
    let installDirectory = URL(filePath: #"C:\Program Files\Azookey"#)
    let directory = engineRuntimeDirectoryURL(
        appDataPath: "  ",
        temporaryDirectoryURL: URL(filePath: #"C:\Users\test\AppData\Local\Temp"#)
    )

    #expect(directory.lastPathComponent == "EngineRuntime")
    #expect(directory.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "Temp")
    #expect(directory.path != installDirectory.path)
    #expect(!directory.path.hasPrefix(installDirectory.path + "/"))
}

@Test func engineRuntimeDirectoryDoesNotResolveUnusedTemporaryDirectory() {
    var temporaryLookups = 0
    func temporaryDirectory() -> URL {
        temporaryLookups += 1
        return URL(filePath: #"C:\Users\test\AppData\Local\Temp"#)
    }
    let appData = #"C:\Users\test\AppData\Roaming"#
    _ = applicationDataDirectoryURL(appDataPath: appData, temporaryDirectoryURL: temporaryDirectory())
    let runtime = engineRuntimeDirectoryURL(appDataPath: appData, temporaryDirectoryURL: temporaryDirectory())
    #expect(temporaryLookups == 0)
    #expect(runtime.lastPathComponent == "EngineRuntime")
}

@Test func engineRuntimeDirectoryResolvesTemporaryDirectoryOnlyForFallback() {
    var temporaryLookups = 0
    func temporaryDirectory() -> URL {
        temporaryLookups += 1
        return URL(filePath: #"C:\Users\test\AppData\Local\Temp"#)
    }
    let runtime = engineRuntimeDirectoryURL(appDataPath: "  ", temporaryDirectoryURL: temporaryDirectory())
    #expect(temporaryLookups == 1)
    #expect(runtime.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "Temp")
    _ = applicationDataDirectoryURL(appDataPath: nil, temporaryDirectoryURL: temporaryDirectory())
    #expect(temporaryLookups == 2)
}

@Test func emojiTextReplacerCacheReusesParsedResultsAndReloadsAfterReset() throws {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("emoji-cache-\(UUID().uuidString).txt")
    defer { try? FileManager.default.removeItem(at: file) }
    try "😀\tえがお\t\n".write(to: file, atomically: true, encoding: .utf8)
    var cache = EmojiTextReplacerCache()
    var reads = 0
    func load(_ url: URL) -> TextReplacer { reads += 1; return TextReplacer { url } }
    let first = cache.value(for: file, load: load)
    try "😃\tえがお\t\n".write(to: file, atomically: true, encoding: .utf8)
    let repeated = cache.value(for: file, load: load)
    #expect(reads == 1)
    #expect(first.getSearchResult(query: "えがお", target: [.emoji]).map(\.text) == ["😀"])
    #expect(repeated.getSearchResult(query: "えがお", target: [.emoji]) == first.getSearchResult(query: "えがお", target: [.emoji]))
    cache.reset()
    #expect(cache.value(for: file, load: load).getSearchResult(query: "えがお", target: [.emoji]).map(\.text) == ["😃"])
    #expect(reads == 2)
}

@Test func emojiTextReplacerCacheDoesNotReuseAnotherResource() {
    var cache = EmojiTextReplacerCache()
    var reads = 0
    func load(_ url: URL) -> TextReplacer { reads += 1; return .empty }
    let first = URL(filePath: "/emoji/resource-a.txt")
    let second = URL(filePath: "/emoji/resource-b.txt")
    _ = cache.value(for: first, load: load)
    _ = cache.value(for: first, load: load)
    _ = cache.value(for: second, load: load)
    #expect(reads == 2)
}

private func testConvertRequestOptions(
    memoryURL: URL,
    experimentalKeyboardTypoCorrection: Bool = false
) -> ConvertRequestOptions {
    let packageRoot = packageRootURL()
    return ConvertRequestOptions(
        requireJapanesePrediction: .disabled,
        requireEnglishPrediction: .disabled,
        keyboardLanguage: .ja_JP,
        learningType: .nothing,
        memoryDirectoryURL: memoryURL,
        sharedContainerURL: memoryURL,
        textReplacer: .init {
            packageRoot
                .appending(path: "azooKey_emoji_dictionary_storage")
                .appending(path: "EmojiDictionary")
                .appending(path: "emoji_all_E15.1.txt")
        },
        specialCandidateProviders: nil,
        zenzaiMode: .off,
        experimentalKeyboardTypoCorrection: experimentalKeyboardTypoCorrection,
        metadata: .init(versionString: "Azookey for Windows test")
    )
}

private func testLearningConvertRequestOptions(memoryURL: URL) -> ConvertRequestOptions {
    let packageRoot = packageRootURL()
    return ConvertRequestOptions(
        requireJapanesePrediction: .disabled,
        requireEnglishPrediction: .disabled,
        keyboardLanguage: .ja_JP,
        learningType: .inputAndOutput,
        memoryDirectoryURL: memoryURL,
        sharedContainerURL: memoryURL,
        textReplacer: .init {
            packageRoot
                .appending(path: "azooKey_emoji_dictionary_storage")
                .appending(path: "EmojiDictionary")
                .appending(path: "emoji_all_E15.1.txt")
        },
        specialCandidateProviders: nil,
        zenzaiMode: .off,
        metadata: .init(versionString: "Azookey for Windows learning test")
    )
}

@MainActor private func keyboardTypoCandidateTexts(
    keys: String,
    inputStyle: InputStyle = .roman2kana,
    enabled: Bool,
    memoryURL: URL,
    converter: KanaKanjiConverter? = nil
) -> [String] {
    var composingText = ComposingText()
    for key in keys {
        composingText.insertAtCursorPosition(String(key), inputStyle: inputStyle)
    }
    return keyboardTypoCandidateTexts(
        composingText: composingText,
        enabled: enabled,
        memoryURL: memoryURL,
        converter: converter
    )
}

@MainActor private func keyboardTypoCandidateTexts(
    composingText: ComposingText,
    enabled: Bool,
    memoryURL: URL,
    converter: KanaKanjiConverter? = nil
) -> [String] {
    keyboardTypoCandidates(
        composingText: composingText,
        enabled: enabled,
        memoryURL: memoryURL,
        converter: converter
    ).map(\.text)
}

@MainActor private func keyboardTypoCandidates(
    composingText: ComposingText,
    enabled: Bool,
    memoryURL: URL,
    converter: KanaKanjiConverter? = nil
) -> [Candidate] {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let converter = converter ?? KanaKanjiConverter(
        dictionaryURL: dictionaryURL,
        preloadDictionary: true
    )
    return converter.requestCandidates(
        composingText,
        options: testConvertRequestOptions(
            memoryURL: memoryURL,
            experimentalKeyboardTypoCorrection: enabled
        )
    ).mainResults
}

@MainActor @Test func decimalMinuteCounterKeepsItsReadingInDictionaryCandidates() {
    let dictionary = packageRootURL().appending(path: "azooKey_dictionary_storage/Dictionary")
    let converter = KanaKanjiConverter(dictionaryURL: dictionary, preloadDictionary: false)
    let memory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
    let options = testConvertRequestOptions(memoryURL: memory)

    func convert(_ reading: String) -> [Candidate] {
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        return converter.requestCandidates(input, options: options).mainResults
    }

    for (reading, expected) in [
        ("14じ30ぷんから", "14時30分から"),
        ("15ぷんいないにまとめる", "15分以内にまとめる"),
        ("３０ぷん", "３０分"),
    ] {
        let matching = convert(reading).filter { $0.text == expected }
        #expect(matching.contains { candidate in
            candidate.data.contains { $0.word == "分" && $0.ruby == "プン" }
        })
    }

    #expect(!convert("ぷん").contains { $0.text == "分" })
    #expect(convert("30ふん").contains { $0.text == "30分" })
}

private func testCandidate(
    word: String,
    ruby: String,
    composingCount: ComposingCount,
    keyboardTypoCorrections: [KeyboardTypoCorrectionProvenance] = []
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
        ],
        keyboardTypoCorrections: keyboardTypoCorrections
    )
}

@Test func learningCandidateCanOnlyBeConsumedOnce() async throws {
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    await MainActor.run {
        let previousLearningType = currentLearningType
        currentLearningType = .inputAndOutput
        learningCandidateCache.removeAll()
        let batchFirstId = cacheLearningCandidates([candidate])
        let candidateId = learningCandidateId(at: 0, batchFirstId: batchFirstId)

        #expect(candidateId != 0)
        #expect(consumeLearningCandidate(candidateId) != nil)
        #expect(consumeLearningCandidate(candidateId) == nil)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func refusedDeletionLeavesTheCandidateUsable() async throws {
    var notATarget = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    notATarget.isLearningTarget = false

    await MainActor.run {
        let previousLearningType = currentLearningType
        currentLearningType = .inputAndOutput
        learningCandidateCache.removeAll()
        let batchFirstId = cacheLearningCandidates([notATarget])
        let candidateId = learningCandidateId(at: 0, batchFirstId: batchFirstId)
        #expect(candidateId != 0)

        // A refused deletion must not consume the id, otherwise the candidate can
        // no longer be committed or pinned by the id the client already holds.
        #expect(!forget_learning_candidate(candidateId: candidateId))
        #expect(learningCandidate(candidateId) != nil)
        #expect(consumeLearningCandidate(candidateId) != nil)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func refusedDeletionWhileLearningIsDisabledKeepsTheCandidate() async throws {
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    await MainActor.run {
        let previousLearningType = currentLearningType
        learningCandidateCache.removeAll()

        // Ids are only issued while learning is enabled, so cache the batch first
        // and then turn learning off: this is the state a client can still be in
        // when the user changes the setting while a candidate bar is visible.
        currentLearningType = .inputAndOutput
        let batchFirstId = cacheLearningCandidates([candidate])
        let candidateId = learningCandidateId(at: 0, batchFirstId: batchFirstId)
        #expect(candidateId != 0)
        currentLearningType = .nothing

        // The guard runs before the consume, so the id stays usable.
        #expect(!forget_learning_candidate(candidateId: candidateId))
        #expect(learningCandidate(candidateId) != nil)
        #expect(!validate_learning_candidate_deletion(candidateId: candidateId))
        #expect(learningCandidate(candidateId) != nil)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func deletionValidationConsumesWithoutWritingStores() async throws {
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    await MainActor.run {
        let previousLearningType = currentLearningType
        currentLearningType = .inputAndOutput
        learningCandidateCache.removeAll()
        let batchFirstId = cacheLearningCandidates([candidate])
        let candidateId = learningCandidateId(at: 0, batchFirstId: batchFirstId)
        #expect(candidateId != 0)

        // The device check path: accepted once, refused the second time, and it
        // never reaches the stores, so a user's learning memory is untouched.
        #expect(validate_learning_candidate_deletion(candidateId: candidateId))
        #expect(!validate_learning_candidate_deletion(candidateId: candidateId))
        #expect(learningCandidate(candidateId) != nil)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func learningCandidateIdsUseDenseSequentialSlots() async throws {
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let second = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    await MainActor.run {
        let previousLearningType = currentLearningType
        currentLearningType = .inputAndOutput
        learningCandidateCache.removeAll()

        let batchFirstId = cacheLearningCandidates([first, second])
        let firstId = learningCandidateId(at: 0, batchFirstId: batchFirstId)
        let secondId = learningCandidateId(at: 1, batchFirstId: batchFirstId)

        #expect(firstId != 0)
        #expect(secondId == firstId + 1)
        #expect(learningCandidateCache.slotCount == 2)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func learningCandidateIdsRemainValidAcrossLaterBatches() {
    var cache = LearningCandidateCache()
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let second = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let firstBatchId = cache.appendBatch([first, second])!
    let firstId = cache.candidateId(at: 0, batchFirstId: firstBatchId)
    let firstBatchLastId = cache.candidateId(at: 1, batchFirstId: firstBatchId)
    let secondBatchId = cache.appendBatch([second])!
    let secondId = cache.candidateId(at: 0, batchFirstId: secondBatchId)

    #expect(firstId != secondId)
    #expect(firstBatchLastId + 1 == secondId)
    #expect(cache.batchCount == 2)
    #expect(cache.consume(firstId)?.text == "今日")
    #expect(cache.consume(firstBatchLastId)?.text == "教")
    #expect(cache.consume(secondId)?.text == "教")
}

@Test func clearingLearningCandidateCacheInvalidatesAllBatches() {
    var cache = LearningCandidateCache()
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let batchFirstId = cache.appendBatch([candidate])!
    let oldCandidateId = cache.candidateId(at: 0, batchFirstId: batchFirstId)

    cache.removeAll()
    let newBatchFirstId = cache.appendBatch([candidate])!
    let newCandidateId = cache.candidateId(at: 0, batchFirstId: newBatchFirstId)

    #expect(oldCandidateId != newCandidateId)
    #expect(cache.consume(oldCandidateId) == nil)
    #expect(cache.consume(newCandidateId)?.text == "今日")
    #expect(cache.batchCount == 1)
}

@Test func emptyLearningCandidateBatchDoesNotAllocateIds() {
    var cache = LearningCandidateCache()

    #expect(cache.appendBatch([]) == nil)
    #expect(cache.batchCount == 0)
    #expect(cache.slotCount == 0)
}

@Test func learningCandidateCacheEvictsOldestBatchesAtConfiguredLimit() {
    var cache = LearningCandidateCache(maxBatchCount: 2, maxSlotCount: 8)
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let firstBatchId = cache.appendBatch([candidate])!
    let firstId = cache.candidateId(at: 0, batchFirstId: firstBatchId)
    let secondBatchId = cache.appendBatch([candidate])!
    let secondId = cache.candidateId(at: 0, batchFirstId: secondBatchId)
    let thirdBatchId = cache.appendBatch([candidate])!
    let thirdId = cache.candidateId(at: 0, batchFirstId: thirdBatchId)

    #expect(firstId != secondId)
    #expect(secondId != thirdId)
    #expect(cache.batchCount == 2)
    #expect(cache.slotCount == 2)
    #expect(cache.consume(firstId) == nil)
    #expect(cache.consume(secondId)?.text == "今日")
    #expect(cache.consume(thirdId)?.text == "今日")
}

@Test func pinnedLearningCandidateSurvivesBatchEvictionUntilConsumed() {
    var cache = LearningCandidateCache(maxBatchCount: 2, maxSlotCount: 2)
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let later = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let firstBatchId = cache.appendBatch([first])!
    let firstId = cache.candidateId(at: 0, batchFirstId: firstBatchId)
    let pinnedFirst = cache.pin(firstId)
    #expect(pinnedFirst)

    _ = cache.appendBatch([later])
    _ = cache.appendBatch([later])

    #expect(cache.batchCount == 2)
    #expect(cache.slotCount == 2)
    #expect(cache.protectedSlotCount == 1)
    #expect(cache.consume(firstId)?.text == "今日")
    #expect(cache.consume(firstId) == nil)
    #expect(cache.protectedSlotCount == 1)
    cache.removeAllPins()
    #expect(cache.protectedSlotCount == 0)
}

@Test func protectedLearningCandidateBatchesShareTheExplicitSlotLimit() {
    var cache = LearningCandidateCache(maxBatchCount: 4, maxSlotCount: 3)
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let alternate = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let later = testCandidate(
        word: "京",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let firstBatchId = cache.appendBatch([first, alternate])!
    let firstId = cache.candidateId(at: 0, batchFirstId: firstBatchId)
    let alternateId = cache.candidateId(at: 1, batchFirstId: firstBatchId)
    let pinnedFirst = cache.pin(firstId)
    #expect(pinnedFirst)
    #expect(cache.protectedSlotCount == 2)

    let laterBatchId = cache.appendBatch([later])!
    let laterId = cache.candidateId(at: 0, batchFirstId: laterBatchId)
    let pinnedLater = cache.pin(laterId)
    #expect(pinnedLater)
    #expect(cache.slotCount == 3)
    #expect(cache.protectedSlotCount == 3)
    #expect(cache.appendBatch([later]) == nil)

    // Protecting one selection preserves alternate nonzero IDs from the same
    // client snapshot instead of copying only the selected candidate.
    #expect(cache.consume(alternateId)?.text == "教")
    #expect(cache.consume(laterId)?.text == "京")
    cache.removeAllPins()
    #expect(cache.protectedSlotCount == 0)
    #expect(cache.appendBatch([later]) != nil)
}

@Test func compositionEditDiscardsFutureOnlyPinsWithoutExtraRPC() async {
    await MainActor.run {
        let previousLearningCandidateCache = learningCandidateCache
        let previousComposingTextSnapshots = composingTextSnapshots
        defer {
            learningCandidateCache = previousLearningCandidateCache
            composingTextSnapshots = previousComposingTextSnapshots
        }

        learningCandidateCache = LearningCandidateCache(
            maxBatchCount: 1,
            maxSlotCount: 1
        )
        let candidate = testCandidate(
            word: "今日",
            ruby: "きょう",
            composingCount: .inputCount(3)
        )
        let batchFirstId = learningCandidateCache.appendBatch([candidate])!
        let candidateId = learningCandidateCache.candidateId(
            at: 0,
            batchFirstId: batchFirstId
        )
        let pinnedFuture = learningCandidateCache.pin(candidateId)
        #expect(pinnedFuture)
        _ = learningCandidateCache.appendBatch([candidate])

        composingTextSnapshots.removeAll()
        discardPinnedLearningCandidatesBeforeCompositionEdit()

        #expect(learningCandidateCache.protectedSlotCount == 0)
        _ = learningCandidateCache.appendBatch([candidate])
        #expect(learningCandidateCache.consume(candidateId) == nil)

        learningCandidateCache.removeAll()
        let activeBatchFirstId = learningCandidateCache.appendBatch([candidate])!
        let activeCandidateId = learningCandidateCache.candidateId(
            at: 0,
            batchFirstId: activeBatchFirstId
        )
        let pinnedActive = learningCandidateCache.pin(activeCandidateId)
        #expect(pinnedActive)
        composingTextSnapshots = [ComposingText()]
        discardPinnedLearningCandidatesBeforeCompositionEdit()
        #expect(learningCandidateCache.protectedSlotCount == 1)
    }
}

@Test func learningCandidateCacheEvictsWholeBatchesToStayWithinSlotLimit() {
    var cache = LearningCandidateCache(maxBatchCount: 4, maxSlotCount: 3)
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let second = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let oldestBatchId = cache.appendBatch([first, second])!
    let oldestId = cache.candidateId(at: 0, batchFirstId: oldestBatchId)
    let middleBatchId = cache.appendBatch([first])!
    let middleId = cache.candidateId(at: 0, batchFirstId: middleBatchId)
    let newestBatchId = cache.appendBatch([second])!
    let newestId = cache.candidateId(at: 0, batchFirstId: newestBatchId)

    #expect(cache.batchCount == 2)
    #expect(cache.slotCount == 2)
    #expect(cache.consume(oldestId) == nil)
    #expect(cache.consume(middleId)?.text == "今日")
    #expect(cache.consume(newestId)?.text == "教")
}

@Test func oversizedLearningCandidateBatchCachesOnlyBoundedPrefix() {
    var cache = LearningCandidateCache(maxBatchCount: 2, maxSlotCount: 2)
    let first = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let second = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let third = testCandidate(
        word: "京",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    let batchFirstId = cache.appendBatch([first, second, third])!
    let firstId = cache.candidateId(at: 0, batchFirstId: batchFirstId)
    let secondId = cache.candidateId(at: 1, batchFirstId: batchFirstId)

    #expect(firstId != 0)
    #expect(secondId == firstId + 1)
    #expect(cache.candidateId(at: 2, batchFirstId: batchFirstId) == 0)
    #expect(cache.batchCount == 1)
    #expect(cache.slotCount == 2)
    #expect(cache.consume(firstId)?.text == "今日")
    #expect(cache.consume(secondId)?.text == "教")
}

@Test func defaultLearningCandidateCacheRetainsPreparedFutureClauseWindow() {
    var cache = LearningCandidateCache()
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let observedHighWaterCandidateCount = 201
    let candidates = Array(
        repeating: candidate,
        count: observedHighWaterCandidateCount
    )
    var oldestId: UInt64 = 0
    var newestId: UInt64 = 0

    // The client retains the current clause and up to 16 prepared advances.
    for generation in 0..<17 {
        let batchFirstId = cache.appendBatch(candidates)!
        if generation == 0 {
            oldestId = cache.candidateId(at: 0, batchFirstId: batchFirstId)
        }
        newestId = cache.candidateId(
            at: observedHighWaterCandidateCount - 1,
            batchFirstId: batchFirstId
        )
    }

    #expect(cache.batchCount == 17)
    #expect(cache.slotCount == 17 * observedHighWaterCandidateCount)
    #expect(cache.consume(oldestId)?.text == "今日")
    #expect(cache.consume(newestId)?.text == "今日")
}

@Test func protectedClauseCandidateBatchSurvivesBulkCacheEviction() {
    var cache = LearningCandidateCache()
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let alternate = testCandidate(
        word: "教",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let candidates = Array(repeating: candidate, count: 201)
    let protectedBatchId = cache.appendBatch([candidate, alternate])!
    let selectedId = cache.candidateId(at: 0, batchFirstId: protectedBatchId)
    let alternateId = cache.candidateId(at: 1, batchFirstId: protectedBatchId)
    let pinnedSelected = cache.pin(selectedId)
    #expect(pinnedSelected)
    var newestId: UInt64 = 0

    // More than 40 high-water candidate generations exceed the 8,192-slot
    // ring. Unprotected batches are evicted around the protected source batch.
    for _ in 0..<48 {
        let batchFirstId = cache.appendBatch(candidates)!
        newestId = cache.candidateId(at: 0, batchFirstId: batchFirstId)
    }

    #expect(cache.slotCount <= maxLearningCandidateCacheSlotCount)
    #expect(cache.protectedSlotCount == 2)
    #expect(cache.consume(selectedId)?.text == "今日")
    #expect(cache.consume(alternateId)?.text == "教")
    #expect(cache.consume(newestId)?.text == "今日")
}

@Test func learningCandidateCacheIsSkippedWhenLearningDoesNotAcceptInput() async throws {
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )

    await MainActor.run {
        let previousLearningType = currentLearningType
        learningCandidateCache.removeAll()

        currentLearningType = .onlyOutput
        #expect(cacheLearningCandidates([candidate]) == nil)
        #expect(learningCandidateCache.slotCount == 0)

        currentLearningType = .nothing
        #expect(cacheLearningCandidates([candidate]) == nil)
        #expect(learningCandidateCache.slotCount == 0)

        learningCandidateCache.removeAll()
        currentLearningType = previousLearningType
    }
}

@Test func latestLearningSelectionPrioritizesWholeAndPartialMatchingReadings() async {
    let katouSugar = testCandidate(
        word: "果糖",
        ruby: "かとう",
        composingCount: .inputCount(3)
    )
    let today = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(3)
    )
    let katouSurname = testCandidate(
        word: "加藤",
        ruby: "かとう",
        composingCount: .inputCount(3)
    )
    let candidates = [katouSugar, today, katouSurname]

    await MainActor.run {
        let previousLearningType = currentLearningType
        let previousLearningSelectionOverrides = learningSelectionOverrides
        let previousPredictiveConversion = config["predictiveConversion"]
        defer {
            currentLearningType = previousLearningType
            learningSelectionOverrides = previousLearningSelectionOverrides
            config["predictiveConversion"] = previousPredictiveConversion
        }

        currentLearningType = .inputAndOutput
        config["predictiveConversion"] = false
        learningSelectionOverrides = ["カトウ": "加藤"]
        let prioritized = prioritizeLearningSelectionOverrides(candidates, ruby: "かとう") { $0 }
        #expect(prioritized.map(\.text) == ["加藤", "果糖", "今日"])

        var unrelatedCandidateAccessCount = 0
        let unrelated = prioritizeLearningSelectionOverrides(candidates, ruby: "しゅう") {
            unrelatedCandidateAccessCount += 1
            return $0
        }
        #expect(unrelated.map(\.text) == candidates.map(\.text))
        #expect(unrelatedCandidateAccessCount == 0)

        currentLearningType = .nothing
        let learningDisabled = prioritizeLearningSelectionOverrides(candidates, ruby: "かとう") { $0 }
        #expect(learningDisabled.map(\.text) == ["果糖", "今日", "加藤"])
    }
}

@Test func prioritizeLearningSelectionOverridesSynthesizesMissingLearnedCandidate() async throws {
    let katouSugar = testCandidate(
        word: "果糖",
        ruby: "カトウ",
        composingCount: .inputCount(3)
    )
    let candidates = [katouSugar]

    await MainActor.run {
        let previousLearningType = currentLearningType
        let previousLearningSelectionOverrides = learningSelectionOverrides
        let previousPredictiveConversion = config["predictiveConversion"]
        defer {
            currentLearningType = previousLearningType
            learningSelectionOverrides = previousLearningSelectionOverrides
            config["predictiveConversion"] = previousPredictiveConversion
        }

        currentLearningType = .inputAndOutput
        learningSelectionOverrides["カトウ"] = "加藤"
        let prioritized = prioritizeLearningSelectionOverrides(
            candidates,
            ruby: "かとう",
            leftContext: "前回と違う場面で",
            candidate: { $0 },
            synthesizeElement: { $0 }
        )
        #expect(prioritized.map(\.text) == ["果糖", "加藤"])
    }
}

@Test func prioritizeLearningSelectionOverridesPreservesMultipleLRUOrder() async throws {
    let c1 = testCandidate(word: "効果", ruby: "コウカ", composingCount: .inputCount(3))
    let c2 = testCandidate(word: "硬化", ruby: "コウカ", composingCount: .inputCount(3))
    let c3 = testCandidate(word: "降下", ruby: "コウカ", composingCount: .inputCount(3))
    let candidates = [c1, c2, c3]

    await MainActor.run {
        let previousLearningType = currentLearningType
        let previousLearningSelectionOverrides = learningSelectionOverrides
        let previousPredictiveConversion = config["predictiveConversion"]
        defer {
            currentLearningType = previousLearningType
            learningSelectionOverrides = previousLearningSelectionOverrides
            config["predictiveConversion"] = previousPredictiveConversion
        }

        currentLearningType = .inputAndOutput
        // 過去に「効果」を確定し、直近で「降下」を確定
        let now = learningEpochMilliseconds()
        learningSelectionOverrides.record(output: "効果", for: "コウカ", nowEpochMs: now - 1)
        learningSelectionOverrides.record(output: "降下", for: "コウカ", nowEpochMs: now)
        let prioritized = prioritizeLearningSelectionOverrides(
            candidates,
            ruby: "こうか",
            candidate: { $0 },
            synthesizeElement: { $0 }
        )
        #expect(prioritized.map(\.text) == ["降下", "効果", "硬化"])
    }
}

@Test func prioritizeLearningSelectionOverridesPrefixSuggestsRecentCompletions() async throws {
    let c1 = testCandidate(word: "プロ", ruby: "プロ", composingCount: .inputCount(2))
    let candidates = [c1]

    await MainActor.run {
        let previousLearningType = currentLearningType
        let previousLearningSelectionOverrides = learningSelectionOverrides
        let previousPredictiveConversion = config["predictiveConversion"]
        defer {
            currentLearningType = previousLearningType
            learningSelectionOverrides = previousLearningSelectionOverrides
            config["predictiveConversion"] = previousPredictiveConversion
        }

        currentLearningType = .inputAndOutput
        learningSelectionOverrides.record(output: "プログラミング", for: "プログラミング")
        config["predictiveConversion"] = true
        let prioritized = prioritizeLearningSelectionOverrides(
            candidates,
            ruby: "ぷろ",
            candidate: { $0 },
            synthesizeElement: { $0 }
        )
        #expect(prioritized.map(\.text) == ["プロ", "プログラミング"])
        #expect(learningCandidateRuby(prioritized[1]) == "プログラミング")
        #expect(prioritized[1].composingCount == c1.composingCount)

        config["predictiveConversion"] = false
        let disabledPrioritized = prioritizeLearningSelectionOverrides(
            candidates,
            ruby: "ぷろ",
            candidate: { $0 },
            synthesizeElement: { $0 }
        )
        #expect(disabledPrioritized.map(\.text) == ["プロ"])
    }
}

@Test func testPredictiveConversionCandidatesWithRealConverter() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    await MainActor.run {
        let previousConverter = converter
        let previousComposingText = composingText
        let previousPredictiveConversion = config["predictiveConversion"]
        defer {
            converter = previousConverter
            composingText = previousComposingText
            config["predictiveConversion"] = previousPredictiveConversion
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        config["predictiveConversion"] = true
        composingText = ComposingText()
        composingText.insertAtCursorPosition("あ", inputStyle: .direct)
        var length: CInt = 0
        let candidatesPtr = get_composed_text(lengthPtr: &length)
        defer { free_candidate_list(candidatesPtr, length) }
        var list: [(text: String, subtext: String, count: Int32)] = []
        for i in 0..<Int(length) {
            let c = candidatesPtr[i]!.pointee
            list.append((
                text: String(cString: c.text),
                subtext: String(cString: c.subtext),
                count: c.correspondingCount
            ))
        }
        #expect(length > 0)
        #expect(list[0].text == "あ")
        #expect(list.contains { $0.text == "あと" })
        #expect(list.contains { $0.text == "ある" })
    }
}

@Test func latestSelectionReplacesPreviouslyLearnedFirstCandidate() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-learning-test-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer {
        try? FileManager.default.removeItem(at: memoryURL)
    }

    try await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousComposingText = composingText
        let previousComposingTextSnapshots = composingTextSnapshots
        let previousLearningType = currentLearningType
        let previousLearningMemoryDirectoryURL = currentLearningMemoryDirectoryURL
        let previousLearningCandidateCache = learningCandidateCache
        let previousLearningSelectionOverrides = learningSelectionOverrides
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            composingText = previousComposingText
            composingTextSnapshots = previousComposingTextSnapshots
            currentLearningType = previousLearningType
            currentLearningMemoryDirectoryURL = previousLearningMemoryDirectoryURL
            learningCandidateCache = previousLearningCandidateCache
            learningSelectionOverrides = previousLearningSelectionOverrides
        }

        var source = ComposingText()
        source.insertAtCursorPosition("かとう", inputStyle: .direct)
        let options = testLearningConvertRequestOptions(memoryURL: memoryURL)
        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .inputAndOutput
        currentLearningMemoryDirectoryURL = memoryURL
        learningCandidateCache.removeAll()
        learningSelectionOverrides.removeAll()

        @MainActor func candidates() -> [Candidate] {
            prioritizeLearningSelectionOverrides(
                converter.requestCandidates(source, options: options).mainResults,
                ruby: source.convertTarget
            ) { $0 }
        }

        @MainActor func commit(_ candidate: Candidate, from candidates: [Candidate]) throws {
            let index = try #require(candidates.firstIndex { $0.text == candidate.text })
            let batchFirstId = try #require(cacheLearningCandidates(candidates))
            let candidateId = learningCandidateId(at: index, batchFirstId: batchFirstId)
            #expect(commit_learning_candidate_with_explicit(candidateId: candidateId, commitKind: 1, explicitSelection: true))
            clear_text()
        }

        let initial = candidates()
        let katouSugar = try #require(initial.first { $0.text == "果糖" })
        try commit(katouSugar, from: initial)

        let afterFirstCommit = candidates()
        #expect(afterFirstCommit.first?.text == "果糖")
        let katouSurname = try #require(afterFirstCommit.first { $0.text == "加藤" })
        try commit(katouSurname, from: afterFirstCommit)

        learningSelectionOverrides.removeAll()
        loadLearningSelectionOverrides()
        let afterSecondCommit = candidates()
        #expect(
            afterSecondCommit.first?.text == "加藤",
            "first candidates: \(afterSecondCommit.prefix(5).map(\.text))"
        )
    }
}

@Test func learningCandidateBatchCommitsAllSelectionsAndPersistsOverridesOnce() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-learning-batch-test-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer {
        try? FileManager.default.removeItem(at: memoryURL)
    }

    try await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousLearningType = currentLearningType
        let previousLearningMemoryDirectoryURL = currentLearningMemoryDirectoryURL
        let previousLearningCandidateCache = learningCandidateCache
        let previousLearningSelectionOverrides = learningSelectionOverrides
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            currentLearningType = previousLearningType
            currentLearningMemoryDirectoryURL = previousLearningMemoryDirectoryURL
            learningCandidateCache = previousLearningCandidateCache
            learningSelectionOverrides = previousLearningSelectionOverrides
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .inputAndOutput
        currentLearningMemoryDirectoryURL = memoryURL
        learningCandidateCache.removeAll()
        learningSelectionOverrides.removeAll()
        let options = testLearningConvertRequestOptions(memoryURL: memoryURL)

        @MainActor func candidateId(reading: String, output: String) throws -> UInt64 {
            var source = ComposingText()
            source.insertAtCursorPosition(reading, inputStyle: .direct)
            let candidates = converter.requestCandidates(source, options: options).mainResults
            let index = try #require(candidates.firstIndex { $0.text == output })
            let firstId = try #require(cacheLearningCandidates(candidates))
            return learningCandidateId(at: index, batchFirstId: firstId)
        }

        let candidateIds = try [
            candidateId(reading: "かとう", output: "加藤"),
            candidateId(reading: "はしる", output: "走る"),
        ]
        let commitKinds = [Int32(1), Int32(1)]
        let committedCount = candidateIds.withUnsafeBufferPointer { candidateIdsPointer in
            commitKinds.withUnsafeBufferPointer { commitKindsPointer in
                commit_learning_candidates(
                    candidateIds: candidateIdsPointer.baseAddress,
                    commitKinds: commitKindsPointer.baseAddress,
                    count: Int32(candidateIds.count)
                )
            }
        }

        #expect(committedCount == 2)
        learningSelectionOverrides.removeAll()
        loadLearningSelectionOverrides()
        #expect(learningSelectionOverrides["カトウ"] == "加藤")
        #expect(learningSelectionOverrides["ハシル"] == "走る")
        let saved = try JSONSerialization.jsonObject(with: Data(
            contentsOf: memoryURL.appending(path: "selection-overrides.json")
        )) as? [String: Any]
        #expect(saved?["version"] as? Int == 2)
    }
}

@Test func deletionRemovesTheTaughtWordAndPersistsTheStore() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-deletion-test-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: memoryURL, withIntermediateDirectories: true)
    defer {
        try? FileManager.default.removeItem(at: memoryURL)
    }

    try await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousLearningType = currentLearningType
        let previousLearningMemoryDirectoryURL = currentLearningMemoryDirectoryURL
        let previousLearningCandidateCache = learningCandidateCache
        let previousLearningSelectionOverrides = learningSelectionOverrides
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            currentLearningType = previousLearningType
            currentLearningMemoryDirectoryURL = previousLearningMemoryDirectoryURL
            learningCandidateCache = previousLearningCandidateCache
            learningSelectionOverrides = previousLearningSelectionOverrides
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .inputAndOutput
        currentLearningMemoryDirectoryURL = memoryURL
        learningCandidateCache.removeAll()
        learningSelectionOverrides.removeAll()
        let options = testLearningConvertRequestOptions(memoryURL: memoryURL)

        var source = ComposingText()
        source.insertAtCursorPosition("かとう", inputStyle: .direct)
        let candidates = converter.requestCandidates(source, options: options).mainResults
        let index = try #require(candidates.firstIndex { $0.text == "加藤" })
        let target = candidates[index]
        let firstId = try #require(cacheLearningCandidates(candidates))
        let candidateId = learningCandidateId(at: index, batchFirstId: firstId)
        #expect(candidateId != 0)

        // Teach the selection first so the deletion has something to remove and so
        // the persisted override makes the success path observable.
        #expect(commit_learning_candidate(candidateId: candidateId, commitKind: 1))
        learningSelectionOverrides.removeAll()
        loadLearningSelectionOverrides()
        #expect(learningSelectionOverrides["カトウ"] == "加藤")

        // Re-request the same reading to obtain a fresh id, then delete it. The
        // success path writes the memory trie and the selection override.
        let overridesURL = memoryURL.appendingPathComponent("selection-overrides.json", isDirectory: false)

        let refreshed = converter.requestCandidates(source, options: options).mainResults
        let refreshedIndex = try #require(refreshed.firstIndex { $0.text == "加藤" })
        let refreshedFirstId = try #require(cacheLearningCandidates(refreshed))
        let refreshedId = learningCandidateId(at: refreshedIndex, batchFirstId: refreshedFirstId)

        #expect(forget_learning_candidate(candidateId: refreshedId))

        // The id was consumed by the successful deletion, so a repeat is refused.
        #expect(!forget_learning_candidate(candidateId: refreshedId))

        // The override is gone from both memory and disk: the delete wrote the store.
        learningSelectionOverrides.removeAll()
        loadLearningSelectionOverrides()
        #expect(learningSelectionOverrides["カトウ"] == nil)
        let persisted = try Data(contentsOf: overridesURL)
        let removed = try JSONDecoder().decode(LearningSelectionStore.self, from: persisted)
        #expect(removed.records(for: "カトウ").isEmpty)

        // A failed trie update would leave a pause marker and disable learning.
        #expect(!FileManager.default.fileExists(
            atPath: memoryURL.appendingPathComponent(".pause").path
        ))
        _ = target
    }
}

@Test func ffiFreeCStringAcceptsNullAndAllocatedStrings() async throws {
    free_c_string(nil)

    let text = try #require(_strdup("azookey"))
    free_c_string(text)
}

@Test func cursorOffsetsUseFullInt32RangeWithoutSnapshotCollisions() async {
    await MainActor.run {
        let previousComposingText = composingText
        let previousComposingTextSnapshots = composingTextSnapshots
        defer {
            composingText = previousComposingText
            composingTextSnapshots = previousComposingTextSnapshots
        }

        let inputLength = 2048
        let input = String(repeating: "あ", count: inputLength)
        for offset in [125, 126, 127, 128, 129, 1024] {
            composingText = ComposingText()
            composingText.insertAtCursorPosition(input, inputStyle: .direct)
            composingTextSnapshots.removeAll()

            var cursor: CInt = 0
            free_c_string(move_cursor(offset: -CInt(inputLength), cursorPtr: &cursor))
            #expect(cursor == -CInt(inputLength))
            #expect(composingText.convertTargetCursorPosition == 0)
            free_c_string(move_cursor(offset: CInt(offset), cursorPtr: &cursor))
            #expect(cursor == CInt(offset))
            #expect(composingText.convertTargetCursorPosition == offset)
            #expect(composingTextSnapshots.isEmpty)
        }

        composingText = ComposingText()
        composingText.insertAtCursorPosition(input, inputStyle: .direct)
        var extremeCursor: CInt = 0
        free_c_string(move_cursor(offset: CInt.min, cursorPtr: &extremeCursor))
        #expect(extremeCursor == -CInt(inputLength))
        #expect(composingText.convertTargetCursorPosition == 0)
        free_c_string(move_cursor(offset: CInt.max, cursorPtr: &extremeCursor))
        #expect(extremeCursor == CInt(inputLength))
        #expect(composingText.convertTargetCursorPosition == inputLength)

        composingText = ComposingText()
        composingText.insertAtCursorPosition(input, inputStyle: .direct)
        composingTextSnapshots.removeAll()
        push_composing_text_snapshot(selectedCandidateId: 0)
        #expect(composingTextSnapshots.count == 1)

        var cursor: CInt = 0
        free_c_string(move_cursor(offset: -1024, cursorPtr: &cursor))
        free_c_string(move_cursor(offset: 125, cursorPtr: &cursor))
        #expect(cursor == 125)
        #expect(composingText.convertTargetCursorPosition == 1149)
        #expect(composingTextSnapshots.count == 1)

        pop_composing_text_snapshot(selectedCandidateId: 0)
        #expect(composingText.convertTargetCursorPosition == inputLength)
        #expect(composingTextSnapshots.isEmpty)

        push_composing_text_snapshot(selectedCandidateId: 0)
        clear_composing_text_snapshots()
        #expect(composingTextSnapshots.isEmpty)
    }
}

@Test func clauseBoundaryAdjustmentKeepsAlternateRomajiSegmentsAtomic() async {
    await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let cases = [
            (raw: "tya", kana: "ちゃ"),
            (raw: "cha", kana: "ちゃ"),
            (raw: "tyu", kana: "ちゅ"),
            (raw: "chu", kana: "ちゅ"),
        ]
        for testCase in cases {
            composingText = ComposingText()
            composingText.insertAtCursorPosition(
                "\(testCase.raw)ibu",
                inputStyle: .roman2kana
            )
            #expect(composingText.convertTarget == "\(testCase.kana)いぶ")

            var applied: CInt = 0
            var adjustedInputCount: CInt = -1
            var cursorOffset: CInt = 0
            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: 4,
                    direction: -1,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )

            #expect(applied == 1)
            #expect(adjustedInputCount == 3)
            #expect(composingText.convertTargetCursorPosition == testCase.kana.count)
            #expect(cursorOffset < 0)

            applied = 0
            adjustedInputCount = -1
            cursorOffset = 0
            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: 3,
                    direction: -1,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )

            #expect(applied == 0)
            #expect(adjustedInputCount == 3)
            #expect(cursorOffset == 0)
            #expect(composingText.convertTargetCursorPosition == testCase.kana.count)

            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: 3,
                    direction: 1,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )
            #expect(applied == 1)
            #expect(adjustedInputCount == 4)
            #expect(composingText.convertTargetCursorPosition == testCase.kana.count + 1)
        }
    }
}

@Test func clauseBoundaryAdjustmentIncludesDelayedSingleNBoundary() async {
    await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        composingText = ComposingText()
        composingText.insertAtCursorPosition(
            "iikagentouitusiro",
            inputStyle: .roman2kana
        )
        #expect(composingText.convertTarget == "いいかげんとういつしろ")

        _ = composingText.moveCursorFromCursorPosition(
            count: 6 - composingText.convertTargetCursorPosition
        )

        @MainActor func adjust(
            currentInputCount: CInt,
            direction: CInt,
            expectedInputCount: CInt,
            expectedCursorPosition: CInt
        ) {
            var applied: CInt = 0
            var adjustedInputCount: CInt = -1
            var cursorOffset: CInt = 0
            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: currentInputCount,
                    direction: direction,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )

            #expect(applied == 1)
            #expect(adjustedInputCount == expectedInputCount)
            #expect(composingText.convertTargetCursorPosition == expectedCursorPosition)
        }

        adjust(
            currentInputCount: 9,
            direction: -1,
            expectedInputCount: 7,
            expectedCursorPosition: 5
        )
        adjust(
            currentInputCount: 7,
            direction: 1,
            expectedInputCount: 9,
            expectedCursorPosition: 6
        )
        adjust(
            currentInputCount: 9,
            direction: -1,
            expectedInputCount: 7,
            expectedCursorPosition: 5
        )
        adjust(
            currentInputCount: 7,
            direction: -1,
            expectedInputCount: 6,
            expectedCursorPosition: 4
        )
    }
}

@Test func clauseBoundaryAdjustmentSupportsDelayedSingleNInCustomInputStyle() async throws {
    try await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let inputStyle = try makeTemporaryCustomInputStyle([
            row("n", "ん"),
            row("na", "な"),
            row("nn", "ん"),
            row("n'", "ん"),
            row("nya", "にゃ"),
            row("ta", "た"),
        ])
        composingText = ComposingText()
        composingText.insertAtCursorPosition("nta", inputStyle: inputStyle)
        #expect(composingText.convertTarget == "んた")

        var applied: CInt = 0
        var adjustedInputCount: CInt = -1
        var cursorOffset: CInt = 0
        free_c_string(
            adjust_clause_boundary(
                currentInputCount: 3,
                direction: -1,
                appliedPtr: &applied,
                adjustedInputCountPtr: &adjustedInputCount,
                cursorOffsetPtr: &cursorOffset
            )
        )

        #expect(applied == 1)
        #expect(adjustedInputCount == 1)
        #expect(composingText.convertTargetCursorPosition == 1)

        free_c_string(
            adjust_clause_boundary(
                currentInputCount: 1,
                direction: 1,
                appliedPtr: &applied,
                adjustedInputCountPtr: &adjustedInputCount,
                cursorOffsetPtr: &cursorOffset
            )
        )

        #expect(applied == 1)
        #expect(adjustedInputCount == 3)
        #expect(composingText.convertTargetCursorPosition == 2)
    }
}

@Test func clauseBoundaryAdjustmentKeepsAtomicCustomNSequence() async throws {
    try await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let inputStyle = try makeTemporaryCustomInputStyle([
            row("nq", "んや"),
        ])
        composingText = ComposingText()
        composingText.insertAtCursorPosition("nq", inputStyle: inputStyle)
        #expect(composingText.convertTarget == "んや")

        var applied: CInt = 0
        var adjustedInputCount: CInt = -1
        var cursorOffset: CInt = 0
        free_c_string(
            adjust_clause_boundary(
                currentInputCount: 2,
                direction: -1,
                appliedPtr: &applied,
                adjustedInputCountPtr: &adjustedInputCount,
                cursorOffsetPtr: &cursorOffset
            )
        )

        #expect(applied == 0)
        #expect(adjustedInputCount == 2)
        #expect(composingText.convertTargetCursorPosition == 2)
    }
}

@Test func rawInputIdentitySurvivesClauseShrinkWithoutNormalizingRomaji() async {
    await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let input = "aruteidonagaibunsyoudemofukusuuni"
        composingText = ComposingText()
        composingText.insertAtCursorPosition(input, inputStyle: .roman2kana)
        free_c_string(shrink_text(offset: 8))

        let rawInput = get_raw_input()
        defer {
            free_c_string(rawInput)
        }
        #expect(rawInput.map { String(cString: $0) } == String(input.dropFirst(8)))
    }
}

@Test func removeTextReturnsCanonicalInputAfterMappedRomajiDeletion() async throws {
    try await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let inputStyle = try makeTemporaryCustomInputStyle(defaultWindowsRomajiRows())
        composingText = ComposingText()
        for character in "buns" {
            composingText.insertAtCursorPosition(String(character), inputStyle: inputStyle)
        }

        var cursor: CInt = 0
        free_c_string(remove_text(cursorPtr: &cursor))
        var rawInput = get_raw_input()
        // The converter materializes the terminal `n` as kana while deleting
        // the pending `s`; this cannot be reproduced with a client-side pop.
        #expect(rawInput.map { String(cString: $0) } == "buん")
        free_c_string(rawInput)

        free_c_string(remove_text(cursorPtr: &cursor))
        rawInput = get_raw_input()
        #expect(rawInput.map { String(cString: $0) } == "bu")
        free_c_string(rawInput)
    }
}

@Test func removeTextCanConsumeMultipleRawElementsInLongComposition() async throws {
    try await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        // Exact key sequence recovered from the failing VM trace. The user
        // corrected the terminal `bnun` with three Backspaces; the mapped input
        // table removes two internal elements on the second deletion.
        let input = "aruteidonageibunsyoudemofukusuubnun"
        let inputStyle = try makeTemporaryCustomInputStyle(defaultWindowsRomajiRows())
        composingText = ComposingText()
        for character in input {
            composingText.insertAtCursorPosition(String(character), inputStyle: inputStyle)
        }
        #expect(composingText.input.count == 35)

        var cursor: CInt = 0
        free_c_string(remove_text(cursorPtr: &cursor))
        #expect(composingText.input.count == 34)
        free_c_string(remove_text(cursorPtr: &cursor))
        #expect(composingText.input.count == 32)
        free_c_string(remove_text(cursorPtr: &cursor))
        #expect(composingText.input.count == 31)

        let rawInput = get_raw_input()
        defer {
            free_c_string(rawInput)
        }
        #expect(rawInput.map { String(cString: $0).count } == 31)
        #expect(rawInput.map { String(cString: $0) } != String(input.dropLast(3)))
    }
}

@Test func clauseBoundaryAdjustmentSkipsEdgesAndUnknownInputBoundaries() async {
    await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        composingText = ComposingText()
        composingText.insertAtCursorPosition("tyaibu", inputStyle: .roman2kana)
        let endInputCount = CInt(composingText.input.count)
        let endSurfaceIndex = composingText.convertTargetCursorPosition

        for (currentInputCount, direction) in [
            (endInputCount, CInt(1)),
            (CInt(2), CInt(-1)),
            (CInt(-1), CInt(-1)),
        ] {
            var applied: CInt = 1
            var adjustedInputCount: CInt = -1
            var cursorOffset: CInt = 99
            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: currentInputCount,
                    direction: direction,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )

            #expect(applied == 0)
            #expect(adjustedInputCount == currentInputCount)
            #expect(cursorOffset == 0)
            #expect(composingText.convertTargetCursorPosition == endSurfaceIndex)
        }

        var applied: CInt = 1
        var adjustedInputCount: CInt = -1
        var cursorOffset: CInt = 99
        "different-input".withCString { expectedRawInputPointer in
            free_c_string(
                adjust_clause_boundary(
                    currentInputCount: 3,
                    direction: 1,
                    expectedRawInput: expectedRawInputPointer,
                    appliedPtr: &applied,
                    adjustedInputCountPtr: &adjustedInputCount,
                    cursorOffsetPtr: &cursorOffset
                )
            )
        }
        #expect(applied == 0)
        #expect(adjustedInputCount == 3)
        #expect(cursorOffset == 0)
        #expect(composingText.convertTargetCursorPosition == endSurfaceIndex)

        var length: CInt = -1
        let candidates = get_composed_text_for_cursor_prefix(
            requiredInputCount: endInputCount - 1,
            lengthPtr: &length
        )
        free_candidate_list(candidates, length)
        #expect(length == 0)
    }
}

@Test func clauseBoundaryCandidatesPreserveTheAdjustedInputBoundary() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousComposingText = composingText
        let previousLearningType = currentLearningType
        let previousExecURL = execURL
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            composingText = previousComposingText
            currentLearningType = previousLearningType
            execURL = previousExecURL
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .nothing
        execURL = packageRoot

        @MainActor func candidateCounts(requiredInputCount: CInt) -> [CInt] {
            var length: CInt = 0
            let list = get_composed_text_for_cursor_prefix(
                requiredInputCount: requiredInputCount,
                lengthPtr: &length
            )
            defer {
                free_candidate_list(list, length)
            }
            return (0..<Int(length)).compactMap { index in
                list.advanced(by: index).pointee?.pointee.correspondingCount
            }
        }

        @MainActor func adjust(
            rawInput: String,
            shrinkOffset: CInt = 0,
            currentInputCount: CInt,
            direction: CInt,
            expectedInputCount: CInt,
            expectedCursorPosition: CInt? = nil
        ) {
            composingText = ComposingText()
            composingText.insertAtCursorPosition(rawInput, inputStyle: .roman2kana)
            if shrinkOffset > 0 {
                free_c_string(shrink_text(offset: shrinkOffset))
            }

            var applied: CInt = 0
            var adjustedInputCount: CInt = -1
            var cursorOffset: CInt = 0
            let expectedRawInput = String(rawInput.dropFirst(Int(shrinkOffset)))
            expectedRawInput.withCString { expectedRawInputPointer in
                free_c_string(
                    adjust_clause_boundary(
                        currentInputCount: currentInputCount,
                        direction: direction,
                        expectedRawInput: expectedRawInputPointer,
                        appliedPtr: &applied,
                        adjustedInputCountPtr: &adjustedInputCount,
                        cursorOffsetPtr: &cursorOffset
                    )
                )
            }

            #expect(applied == 1)
            #expect(adjustedInputCount == expectedInputCount)
            #expect(get_cursor_position() == composingText.convertTargetCursorPosition)
            if let expectedCursorPosition {
                #expect(get_cursor_position() == expectedCursorPosition)
            }
            let counts = candidateCounts(requiredInputCount: expectedInputCount)
            #expect(
                counts.contains(expectedInputCount),
                "expected boundary \(expectedInputCount), candidates: \(counts)"
            )
        }

        let input = "aruteidonagaibunsyoudemofukusuuni"
        adjust(
            rawInput: input,
            currentInputCount: 8,
            direction: -1,
            expectedInputCount: 6,
            expectedCursorPosition: 4
        )
        adjust(
            rawInput: input,
            currentInputCount: 8,
            direction: 1,
            expectedInputCount: 10
        )
        adjust(
            rawInput: input,
            currentInputCount: 10,
            direction: 1,
            expectedInputCount: 12
        )
        adjust(
            rawInput: input,
            shrinkOffset: 8,
            currentInputCount: 16,
            direction: -1,
            expectedInputCount: 14
        )
        adjust(
            rawInput: input,
            shrinkOffset: 8,
            currentInputCount: 16,
            direction: 1,
            expectedInputCount: 18
        )
    }
}

@Test func cursorPrefixActualConverterSelectsTheDisplayedFirstClauseBoundary() async {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousComposingText = composingText
        let previousLearningType = currentLearningType
        let previousLearningCandidateCache = learningCandidateCache
        let previousConfig = config
        let previousExecURL = execURL
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            composingText = previousComposingText
            currentLearningType = previousLearningType
            learningCandidateCache = previousLearningCandidateCache
            config = previousConfig
            execURL = previousExecURL
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .nothing
        learningCandidateCache.removeAll()
        config["enable"] = false
        config["backend"] = "cpu"
        config["context"] = ""
        execURL = packageRoot
        composingText = ComposingText()
        composingText.insertAtCursorPosition(
            "aruteidonagaibunsyoudemofukusuubunsetunibunkatusareru",
            inputStyle: .roman2kana
        )

        var length: CInt = 0
        let list = get_composed_text_for_cursor_prefix(
            requiredInputCount: -1,
            lengthPtr: &length
        )
        defer {
            free_candidate_list(list, length)
        }
        let candidates = (0..<Int(length)).compactMap { index -> (String, CInt)? in
            guard let candidate = list.advanced(by: index).pointee?.pointee else {
                return nil
            }
            return (String(cString: candidate.text), candidate.correspondingCount)
        }

        #expect(!candidates.isEmpty)
        #expect(
            Set(candidates.map(\.1)) == [8],
            "first-clause candidates: \(candidates)"
        )
        #expect(candidates.contains { $0.0 == "ある程度" })
    }
}

@Test func shrinkTextSupportsLongOffsetsAndClampsDirectFfiInput() async {
    await MainActor.run {
        let previousComposingText = composingText
        defer {
            composingText = previousComposingText
        }

        let inputLength = 1100
        let input = String(repeating: "あ", count: inputLength)
        for offset in [127, 128, 129, 1024] {
            composingText = ComposingText()
            composingText.insertAtCursorPosition(input, inputStyle: .direct)

            let result = shrink_text(offset: CInt(offset))
            let remaining = String(cString: result)
            free_c_string(result)

            #expect(remaining.count == inputLength - offset)
            #expect(composingText.input.count == inputLength - offset)
        }

        composingText = ComposingText()
        composingText.insertAtCursorPosition(input, inputStyle: .direct)
        let negativeResult = shrink_text(offset: -1)
        let unchanged = String(cString: negativeResult)
        free_c_string(negativeResult)
        #expect(unchanged.count == inputLength)
        #expect(composingText.input.count == inputLength)
    }
}

@Test func ffiFreeCandidateListAcceptsNullEmptyAndPopulatedLists() async throws {
    free_candidate_list(nil, 0)

    let emptyList = to_list_pointer([])
    free_candidate_list(emptyList, 0)

    let text = try #require(_strdup("candidate"))
    let subtext = try #require(_strdup("remaining"))
    let hiragana = try #require(_strdup("かな"))
    let candidates = [
        FFICandidate(
            text: text,
            subtext: subtext,
            hiragana: hiragana,
            correspondingCount: 1,
            candidateId: 1, predictionReading: nil,
            tokenMargins: nil
        )
    ]

    free_candidate_list(to_list_pointer(candidates), Int32(candidates.count))

    let nilHiraganaText = try #require(_strdup("candidate"))
    let nilHiraganaSubtext = try #require(_strdup("remaining"))
    let nilHiraganaCandidates = [
        FFICandidate(
            text: nilHiraganaText,
            subtext: nilHiraganaSubtext,
            hiragana: nil,
            correspondingCount: 1,
            candidateId: 2, predictionReading: nil,
            tokenMargins: nil
        )
    ]

    free_candidate_list(to_list_pointer(nilHiraganaCandidates), Int32(nilHiraganaCandidates.count))

    let firstLegacyText = try #require(_strdup("legacy"))
    let firstLegacySubtext = try #require(_strdup("remaining"))
    let firstLegacyHiragana = try #require(_strdup("かな"))
    let firstLegacyCandidate = UnsafeMutablePointer<FFICandidate>.allocate(capacity: 1)
    firstLegacyCandidate.initialize(
        to: FFICandidate(
            text: firstLegacyText,
            subtext: firstLegacySubtext,
            hiragana: firstLegacyHiragana,
            correspondingCount: 1,
            candidateId: 3, predictionReading: nil,
            tokenMargins: nil
        )
    )

    let secondLegacyText = try #require(_strdup("legacy-second"))
    let secondLegacySubtext = try #require(_strdup("remaining-second"))
    let secondLegacyCandidate = UnsafeMutablePointer<FFICandidate>.allocate(capacity: 1)
    secondLegacyCandidate.initialize(
        to: FFICandidate(
            text: secondLegacyText,
            subtext: secondLegacySubtext,
            hiragana: nil,
            correspondingCount: 1,
            candidateId: 4, predictionReading: nil,
            tokenMargins: nil
        )
    )

    let legacyList = UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?>.allocate(capacity: 3)
    legacyList.advanced(by: 0).initialize(to: firstLegacyCandidate)
    legacyList.advanced(by: 1).initialize(to: nil)
    legacyList.advanced(by: 2).initialize(to: secondLegacyCandidate)
    free_candidate_list(legacyList, 3)
}

@Test func constructCandidateStringAdvancesByRubyWithoutMutatingRemainder() async throws {
    let candidate = Candidate(
        text: "今日は",
        value: -1,
        composingCount: .inputCount(5),
        lastMid: MIDData.一般.mid,
        data: [
            DicdataElement(
                word: "今日",
                ruby: "きょう",
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            ),
            DicdataElement(
                word: "は",
                ruby: "は",
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            ),
        ]
    )

    #expect(constructCandidateString(candidate: candidate, hiragana: "きょうは") == "今日は")
}

@Test func constructCandidateStringPreservesPredictiveCandidateTextWhenRubyOverruns() async throws {
    let candidate = testCandidate(
        word: "今日",
        ruby: "きょう",
        composingCount: .inputCount(2)
    )

    #expect(constructCandidateString(candidate: candidate, hiragana: "きょ") == "今日")
}

@Test func zenzaiNormalNBestSupplementKeepsZenzaiFirstAndDeduplicates() async throws {
    let hiragana = "ここではきものをぬいでください"
    let zenzaiTop = testCandidate(
        word: "ここでは着物を脱いでください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let zenzaiRichSecond = testCandidate(
        word: "ここで履物を脱いでください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let duplicatedTop = testCandidate(
        word: "ここでは着物を脱いでください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let normalSecond = testCandidate(
        word: "ここでは着物を脱いでくださ異",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let normalThird = testCandidate(
        word: "ここでは着物を脱いでくださ偉",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [zenzaiTop, zenzaiRichSecond],
        normalNBestResults: [duplicatedTop, normalSecond, normalThird, zenzaiRichSecond],
        hiragana: hiragana
    )

    #expect(
        merged.map { constructCandidateString(candidate: $0, hiragana: hiragana) } == [
            "ここでは着物を脱いでください",
            "ここで履物を脱いでください",
            "ここでは着物を脱いでくださ異",
            "ここでは着物を脱いでくださ偉",
        ]
    )
}

@Test func zenzaiMergePromotesSingleRewritePathWhenModelTopIsUncorrected() async throws {
    let hiragana = "ぱんをきっってくれる"
    let rewrite = KeyboardTypoCorrectionProvenance(
        kind: .smallTsu,
        originalSurfaceRange: 3 ..< 7
    )
    let uncorrected = testCandidate(
        word: hiragana,
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count)
    )
    let corrected = testCandidate(
        word: "パンを切ってくれる",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: [rewrite]
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [uncorrected, corrected],
        normalNBestResults: [],
        hiragana: hiragana
    )

    #expect(Array(merged.map(\.text).prefix(2)) == ["パンを切ってくれる", hiragana])
}

@Test func zenzaiMergePreservesModelSurfaceWhenTopAlreadyUsesRewritePath() async throws {
    let hiragana = "こんんいちはという"
    let rewrite = KeyboardTypoCorrectionProvenance(
        kind: .doubleNn,
        originalSurfaceRange: 0 ..< 6
    )
    let modelTop = testCandidate(
        word: "今日はという",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: [rewrite]
    )
    let literalSurface = testCandidate(
        word: "こんにちはという",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: [rewrite]
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [modelTop, literalSurface],
        normalNBestResults: [literalSurface],
        hiragana: hiragana
    )

    #expect(Array(merged.map(\.text).prefix(2)) == ["今日はという", "こんにちはという"])
}

@Test func zenzaiMergeDoesNotPromoteRewriteAcrossWholeSpanLexicalCollision() async throws {
    let hiragana = "ほんっっと"
    let rewrite = KeyboardTypoCorrectionProvenance(
        kind: .smallTsu,
        originalSurfaceRange: 0 ..< hiragana.count
    )
    let lexicalTop = testCandidate(
        word: "本っっと",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count)
    )
    let corrected = testCandidate(
        word: "ほんっと",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: [rewrite]
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [lexicalTop, corrected],
        normalNBestResults: [],
        hiragana: hiragana
    )

    #expect(merged.first?.text == "本っっと")
}

@Test func zenzaiMergeDoesNotPromoteRewriteAcrossSegmentedLexicalCollision() async throws {
    let hiragana = "えっっと"
    let rewrite = KeyboardTypoCorrectionProvenance(
        kind: .smallTsu,
        originalSurfaceRange: 0 ..< hiragana.count
    )
    let lexicalTop = Candidate(
        text: "絵っっと",
        value: -1,
        composingCount: .surfaceCount(hiragana.count),
        lastMid: MIDData.一般.mid,
        data: [
            DicdataElement(
                word: "絵っ",
                ruby: "エッ",
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            ),
            DicdataElement(
                word: "っと",
                ruby: "ット",
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            )
        ]
    )
    let corrected = testCandidate(
        word: "えっと",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: [rewrite]
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [lexicalTop, corrected],
        normalNBestResults: [],
        hiragana: hiragana
    )

    #expect(merged.first?.text == "絵っっと")
}

@Test func zenzaiMergeDoesNotPromoteCandidateWithMultipleRewrites() async throws {
    let hiragana = "きっってこんんいちは"
    let rewrites = [
        KeyboardTypoCorrectionProvenance(kind: .smallTsu, originalSurfaceRange: 0 ..< 4),
        KeyboardTypoCorrectionProvenance(kind: .doubleNn, originalSurfaceRange: 4 ..< 10),
    ]
    let uncorrected = testCandidate(
        word: hiragana,
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count)
    )
    let corrected = testCandidate(
        word: "切ってこんにちは",
        ruby: hiraganaToKatakana(hiragana),
        composingCount: .surfaceCount(hiragana.count),
        keyboardTypoCorrections: rewrites
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [uncorrected, corrected],
        normalNBestResults: [],
        hiragana: hiragana
    )

    #expect(merged.first?.text == hiragana)
}

@Test func zenzaiNormalNBestSupplementFiltersWeakRichCandidates() async throws {
    let hiragana = "ここではきものをぬいでください"
    let zenzaiTop = testCandidate(
        word: "ここでは着物を脱いでください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let partialRich = testCandidate(
        word: "ここでは",
        ruby: "ここでは",
        composingCount: .inputCount(4)
    )
    let katakanaEchoRich = testCandidate(
        word: "ココデハキモノヲヌイデクダサイ",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let hiraganaEchoRich = testCandidate(
        word: hiragana,
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let usefulRich = testCandidate(
        word: "ここで履物を脱いでください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )
    let normalSecond = testCandidate(
        word: "ここでは着物を抜いてください",
        ruby: hiragana,
        composingCount: .inputCount(16)
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [zenzaiTop, partialRich, katakanaEchoRich, hiraganaEchoRich, usefulRich],
        normalNBestResults: [normalSecond],
        hiragana: hiragana
    )

    #expect(
        merged.map { constructCandidateString(candidate: $0, hiragana: hiragana) } == [
            "ここでは着物を脱いでください",
            "ここで履物を脱いでください",
            "ここでは着物を抜いてください",
        ]
    )
}

@Test func zenzaiNormalNBestSupplementCanKeepFirstClauseRichCandidates() async throws {
    let hiragana = "ここではきものをぬいでください"
    let firstClause = testCandidate(
        word: "ここでは",
        ruby: "ここでは",
        composingCount: .inputCount(4)
    )
    let alternativeFirstClause = testCandidate(
        word: "ここで",
        ruby: "ここで",
        composingCount: .inputCount(3)
    )
    let normalFirstClause = testCandidate(
        word: "此処では",
        ruby: "ここでは",
        composingCount: .inputCount(4)
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [firstClause, alternativeFirstClause],
        normalNBestResults: [normalFirstClause],
        hiragana: hiragana,
        filterZenzaiAlternatives: false
    )

    #expect(
        merged.map { constructCandidateString(candidate: $0, hiragana: hiragana) } == [
            "ここでは",
            "ここで",
            "此処では",
        ]
    )
}

@Test func zenzaiNormalNBestSupplementUsesNormalCandidatesWhenZenzaiResultsAreEmpty() async throws {
    let hiragana = "あしたのてんきはあめです"
    let normal = testCandidate(
        word: "明日の天気は雨です",
        ruby: hiragana,
        composingCount: .inputCount(21)
    )

    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [],
        normalNBestResults: [normal],
        hiragana: hiragana
    )

    #expect(merged.map { constructCandidateString(candidate: $0, hiragana: hiragana) } == ["明日の天気は雨です"])
}

@Test func cursorPrefixBoundarySelectionUsesZenzaiFirstClauseBeforeNormalFallback() async throws {
    let boundaryCounts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("aruteidonagaibunsetsudemo", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let zenzaiFirstClause = testCandidate(
            word: "ある程度",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let normalLongerClause = testCandidate(
            word: "ある程度長い",
            ruby: "あるていどながい",
            composingCount: .inputCount(13)
        )
        let mergedFirstClauseResults = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: [zenzaiFirstClause],
            normalNBestResults: [normalLongerClause],
            hiragana: preview.convertTarget
        )
        let boundaryFirstClauseResults = cursorPrefixBoundaryFirstClauseResults(
            zenzaiFirstClauseResults: [zenzaiFirstClause],
            mergedFirstClauseResults: mergedFirstClauseResults
        )

        return (
            selected: cursorPrefixFirstClauseCorrespondingCount(
                firstClauseResults: boundaryFirstClauseResults,
                originalComposingText: source,
                previewComposingText: preview
            ),
            merged: cursorPrefixFirstClauseCorrespondingCount(
                firstClauseResults: mergedFirstClauseResults,
                originalComposingText: source,
                previewComposingText: preview
            )
        )
    }

    #expect(boundaryCounts.selected == 8)
    #expect(boundaryCounts.merged == 13)
}

@Test func supportsNextInputCarryForTsuRules() async throws {
    let map = tableMap([
        row("tt", "っ", "t"),
        row("ta", "た"),
    ])

    #expect(map["tt"] == "っt")
    #expect(map["tta"] == "った")
}

@Test func keepsWwOverlapRulesStable() async throws {
    let map = tableMap([
        row("ww", "っ", "w"),
        row("www", "w", "ww"),
        row("wa", "わ"),
    ])

    #expect(map["ww"] == "っw")
    #expect(map["www"] == "www")
    #expect(map["っww"] == "www")
    #expect(map["wwa"] == "っわ")
}

@Test func delaysPrefixCommitForNRow() async throws {
    let map = tableMap([
        row("n", "ん"),
        row("na", "な"),
        row("nn", "ん"),
        row("n'", "ん"),
        row("nya", "にゃ"),
        row("-", "ー"),
    ])

    #expect(map["n"] == nil)
    #expect(map["n{composition-separator}"] == "ん")
    #expect(map["n{any character}"] == "ん{any character}")
    #expect(map["ny"] == "ny")
    #expect(map["na"] == "な")
    #expect(map["nn"] == "ん")
    #expect(map["n'"] == "ん")
    #expect(map["n-"] == "んー")
}

@Test func explicitRowsOverrideGeneratedRules() async throws {
    let map = tableMap([
        row("ww", "っ", "w"),
        row("wa", "わ"),
        row("wwa", "ゔぁ"),
    ])

    #expect(map["wwa"] == "ゔぁ")
}

@Test func bracesAreEscapedForInputTableTokens() async throws {
    let map = tableMap([
        row("{a", "}", ""),
    ])

    #expect(map["{lbracket}a"] == "{rbracket}")
}

@Test func customRowsAreUsedWhenZenzaiIsEnabled() async throws {
    let selection = resolveRomajiInputStyleSelection(
        rows: [row("qa", "くぁ")]
    )

    #expect(selection == .custom)
}

@Test func customRowsAreUsedWhenZenzaiIsDisabled() async throws {
    let selection = resolveRomajiInputStyleSelection(
        rows: [row("qa", "くぁ")]
    )

    #expect(selection == .custom)
}

@Test func builtinRoman2KanaIsUsedWhenCustomRowsAreMissing() async throws {
    let selection = resolveRomajiInputStyleSelection(rows: nil)

    #expect(selection == .roman2kana)
}

@Test func zenzaiCandidateGateRejectsShortInput() async throws {
    let useZenzai = effectiveZenzaiEnabledForCandidates(
        isConfigured: true,
        inputCount: 2,
        hiraganaCount: 1
    )

    #expect(useZenzai == false)
}

@Test func zenzaiCandidateGateAcceptsLongEnoughInput() async throws {
    let useZenzai = effectiveZenzaiEnabledForCandidates(
        isConfigured: true,
        inputCount: 4,
        hiraganaCount: 2
    )

    #expect(useZenzai)
}

@Test func warmupUsesShortInputWhenZenzaiRuntimeIsDisabled() async throws {
    let metrics = await MainActor.run {
        let warmupComposingText = makeWarmupComposingText(
            zenzaiRuntimeEnabled: false,
            inputStyle: .roman2kana
        )
        return (
            inputCount: warmupComposingText.input.count,
            hiraganaCount: warmupComposingText.convertTarget.count,
            convertTarget: warmupComposingText.convertTarget
        )
    }

    #expect(metrics.inputCount == 1)
    #expect(metrics.hiraganaCount == 1)
    #expect(metrics.convertTarget == "あ")
}

@Test func warmupUsesZenzaiCandidatePathWhenRuntimeIsEnabled() async throws {
    let metrics = await MainActor.run {
        let warmupComposingText = makeWarmupComposingText(
            zenzaiRuntimeEnabled: true,
            inputStyle: .direct
        )
        return (
            inputCount: warmupComposingText.input.count,
            hiraganaCount: warmupComposingText.convertTarget.count,
            convertTarget: warmupComposingText.convertTarget
        )
    }

    #expect(metrics.inputCount == zenzaiWarmupRomanInput.count)
    #expect(metrics.inputCount >= minInputCountForZenzaiCandidates)
    #expect(metrics.hiraganaCount >= minHiraganaCountForZenzaiCandidates)
    #expect(metrics.convertTarget == "にほんご")
    #expect(
        effectiveZenzaiEnabledForCandidates(
            isConfigured: true,
            inputCount: metrics.inputCount,
            hiraganaCount: metrics.hiraganaCount
        )
    )
}

@Test func cpuBackendIsDisabledWhenAvxIsUnavailable() async throws {
    let enabled = effectiveZenzaiRuntimeEnabled(
        isConfigured: true,
        backend: "cpu",
        cpuBackendSupported: false
    )

    #expect(enabled == false)
}

@Test func nonCpuBackendRemainsAvailableWithoutCpuAvx() async throws {
    let enabled = effectiveZenzaiRuntimeEnabled(
        isConfigured: true,
        backend: "vulkan",
        cpuBackendSupported: false
    )

    #expect(enabled)
}

@Test func zenzaiBackendNormalizationIgnoresCaseAndWhitespace() async throws {
    #expect(normalizedZenzaiBackend(" Vulkan ") == "vulkan")
    #expect(normalizedZenzaiBackend(nil) == "cpu")
}

@Test func serverOptionsDisableJapanesePrediction() async throws {
    let predictionMode = await MainActor.run {
        getOptions(zenzaiEnabled: false).requireJapanesePrediction
    }

    #expect(predictionMode == .disabled)
}

@Test @MainActor func privateInputDisablesPersonalizationUntilLeavingTheField() {
    let previousConfig = config
    let previousLearning = currentLearningType
    defer {
        set_private_input(false)
        config = previousConfig
        currentLearningType = previousLearning
    }

    config["experimentalTypoCorrection"] = true
    config["predictiveConversion"] = true
    currentLearningType = .inputAndOutput
    set_private_input(true)
    let privateOptions = getOptions(context: "通常欄の文脈", zenzaiEnabled: true)
    #expect(privateOptions.learningType == .nothing)
    #expect(!privateOptions.experimentalKeyboardTypoCorrection)
    #expect(privateOptions.requireJapanesePrediction == .disabled)

    set_private_input(false)
    let regularOptions = getOptions(zenzaiEnabled: false)
    #expect(regularOptions.learningType == .inputAndOutput)
    #expect(regularOptions.experimentalKeyboardTypoCorrection)
    #expect(regularOptions.requireJapanesePrediction != .disabled)
}

@Test func keyboardTypoCorrectionDefaultsOffAndFollowsRuntimeConfig() async throws {
    let result = await MainActor.run {
        let previous = config["experimentalTypoCorrection"]
        defer {
            config["experimentalTypoCorrection"] = previous
        }

        config["experimentalTypoCorrection"] = false
        let disabled = getOptions(zenzaiEnabled: false).experimentalKeyboardTypoCorrection
        config["experimentalTypoCorrection"] = true
        let enabled = getOptions(zenzaiEnabled: false).experimentalKeyboardTypoCorrection
        return (disabled, enabled)
    }

    #expect(result.0 == false)
    #expect(result.1)
}

@Test func keyboardTypoCorrectionCandidatesAreNotLearnedWhileEnabled() {
    let typoEntry = DicdataElement(word: "私", ruby: "ワタシ", cid: 1285, mid: 0,
        value: -5, metadata: .isKeyboardTypoCorrection)
    let typoCandidate = Candidate(
        text: typoEntry.word,
        value: typoEntry.value(),
        composingCount: .surfaceCount(typoEntry.ruby.count),
        lastMid: typoEntry.mid,
        data: [typoEntry]
    )
    let ordinaryCandidate = testCandidate(
        word: "通常候補",
        ruby: "ツウジョウコウホ",
        composingCount: .surfaceCount(7)
    )
    let rewriteCandidate = testCandidate(
        word: "切って",
        ruby: "キッテ",
        composingCount: .surfaceCount(4),
        keyboardTypoCorrections: [
            .init(kind: .smallTsu, originalSurfaceRange: 0 ..< 4)
        ]
    )

    let disabled = disableLearningForKeyboardTypoCorrectionCandidates(
        [typoCandidate],
        experimentalTypoCorrectionEnabled: false
    )
    let enabled = disableLearningForKeyboardTypoCorrectionCandidates(
        [typoCandidate, rewriteCandidate, ordinaryCandidate],
        experimentalTypoCorrectionEnabled: true
    )

    #expect(disabled[0].isLearningTarget)
    #expect(!enabled[0].isLearningTarget)
    #expect(!enabled[1].isLearningTarget)
    #expect(enabled[2].isLearningTarget)
}

@Test func keyboardTypoCorrectionEntriesDoNotTriggerUserDictionaryProtection() throws {
    let entry = DicdataElement(word: "私", ruby: "ワタシ", cid: 1285, mid: 0, value: -5)
    var importedTypo = entry
    importedTypo.metadata = [.isFromUserDictionary, .isKeyboardTypoCorrection]
    var userEntry = entry
    userEntry.metadata = .isFromUserDictionary

    let typoIsProtected = ZenzUserDictionaryProtection.isProtectedUserDictionaryElement(importedTypo)
    let userEntryIsProtected = ZenzUserDictionaryProtection.isProtectedUserDictionaryElement(userEntry)
    #expect(!typoIsProtected)
    #expect(userEntryIsProtected)
    #expect(ZenzUserDictionaryProtection.endsWithPrintableAscii("ZeroConvert"))
    #expect(ZenzUserDictionaryProtection.endsWithPrintableAscii("(^o^)"))
    #expect(!ZenzUserDictionaryProtection.endsWithPrintableAscii("ZeroConvert "))
    #expect(!ZenzUserDictionaryProtection.endsWithPrintableAscii("碧海乃"))

    let particle = Array("を".utf8)
    #expect(!ZenzUserDictionaryProtection.shouldAllowMismatch(
        isUserDict: typoIsProtected, precedingUserDictEndedAscii: false, rawLogitGap: 0, tokenBytes: particle
    ))
    #expect(ZenzUserDictionaryProtection.shouldAllowMismatch(
        isUserDict: userEntryIsProtected, precedingUserDictEndedAscii: false, rawLogitGap: 20, tokenBytes: []
    ))
    #expect(ZenzUserDictionaryProtection.shouldAllowMismatch(
        isUserDict: false, precedingUserDictEndedAscii: true, rawLogitGap: 4.5, tokenBytes: particle
    ))
    #expect(!ZenzUserDictionaryProtection.shouldAllowMismatch(
        isUserDict: false, precedingUserDictEndedAscii: true, rawLogitGap: 4.51, tokenBytes: particle
    ))
    #expect(!ZenzUserDictionaryProtection.shouldAllowMismatch(
        isUserDict: false, precedingUserDictEndedAscii: true, rawLogitGap: 0, tokenBytes: Array("eを".utf8)
    ))
}

@Test func keyboardTypoRewriteCandidatesExposeRuleAndOriginalSurfaceRange() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-provenance-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    let candidates = await MainActor.run {
        @MainActor func candidates(_ keys: String) -> [Candidate] {
            var composingText = ComposingText()
            for key in keys {
                composingText.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
            }
            return keyboardTypoCandidates(
                composingText: composingText,
                enabled: true,
                memoryURL: memoryURL
            )
        }
        return (candidates("kixtuxtute"), candidates("konnnnitiha"))
    }

    let smallTsu = candidates.0.first { $0.text == "切って" }
    let doubleNn = candidates.1.first { $0.text == "こんにちは" }
    #expect(
        smallTsu?.keyboardTypoCorrections == [
            .init(kind: .smallTsu, originalSurfaceRange: 0 ..< 4)
        ]
    )
    #expect(
        doubleNn?.keyboardTypoCorrections == [
            .init(kind: .doubleNn, originalSurfaceRange: 0 ..< 6)
        ]
    )
}

@Test func keyboardTypoCorrectionProducesRewriteCandidates() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-correction-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    let results = await MainActor.run {
        (
            smallTsu: keyboardTypoCandidateTexts(
                keys: "kixtuxtute",
                enabled: true,
                memoryURL: memoryURL
            ),
            doubleNn: keyboardTypoCandidateTexts(
                keys: "konnnnitiha",
                enabled: true,
                memoryURL: memoryURL
            )
        )
    }

    #expect(results.smallTsu.contains("切って"), "candidates: \(results.smallTsu)")
    #expect(results.doubleNn.contains("こんにちは"), "candidates: \(results.doubleNn)")
}

@Test func keyboardTypoCorrectionCanBeDisabledWithoutReusingEnabledLattice() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-toggle-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    let results = await MainActor.run {
        let testConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: true
        )
        let enabled = keyboardTypoCandidateTexts(
            keys: "kixtuxtute",
            enabled: true,
            memoryURL: memoryURL,
            converter: testConverter
        )
        let disabled = keyboardTypoCandidateTexts(
            keys: "kixtuxtute",
            enabled: false,
            memoryURL: memoryURL,
            converter: testConverter
        )
        return (enabled, disabled)
    }

    #expect(results.0.contains("切って"), "enabled: \(results.0)")
    #expect(!results.1.contains("切って"), "disabled: \(results.1)")
}

@Test func keyboardTypoCorrectionDoesNotApplyToDirectKanaInput() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-direct-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }

    let candidates = await MainActor.run {
        let direct = keyboardTypoCandidateTexts(
            keys: "きっって",
            inputStyle: .direct,
            enabled: true,
            memoryURL: memoryURL
        )
        let directDictionary = keyboardTypoCandidateTexts(
            keys: "しますた",
            inputStyle: .direct,
            enabled: true,
            memoryURL: memoryURL
        )
        var romanThenDirect = ComposingText()
        for key in "kixtuxtute" {
            romanThenDirect.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
        }
        romanThenDirect.insertAtCursorPosition("。", inputStyle: .direct)

        var mixedTypo = ComposingText()
        for key in "ki" {
            mixedTypo.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
        }
        mixedTypo.insertAtCursorPosition("っって", inputStyle: .direct)
        return (
            direct,
            directDictionary,
            keyboardTypoCandidateTexts(
                composingText: romanThenDirect,
                enabled: true,
                memoryURL: memoryURL
            ),
            keyboardTypoCandidateTexts(
                composingText: mixedTypo,
                enabled: true,
                memoryURL: memoryURL
            )
        )
    }

    #expect(!candidates.0.contains("切って"), "direct candidates: \(candidates.0)")
    #expect(!candidates.1.contains("しました"), "direct dictionary candidates: \(candidates.1)")
    #expect(candidates.2.contains("切って。"), "mixed suffix candidates: \(candidates.2)")
    #expect(!candidates.3.contains("切って"), "mixed typo candidates: \(candidates.3)")
}

@Test func keyboardTypoCorrectionRewriteEditHistoryBackspace() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-backspace-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    let results = await MainActor.run {
        let testConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: true
        )
        let options = testConvertRequestOptions(
            memoryURL: memoryURL,
            experimentalKeyboardTypoCorrection: true
        )

        func candidateTexts(_ composingText: ComposingText) -> [String] {
            testConverter.requestCandidates(
                composingText,
                options: options
            ).mainResults.map(\.text)
        }

        var smallTsu = ComposingText()
        for key in "kixtuxtute" {
            smallTsu.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
        }
        let smallTsuBefore = candidateTexts(smallTsu)
        smallTsu.deleteBackwardFromCursorPosition(count: 1)
        let smallTsuAfter = candidateTexts(smallTsu)
        return (smallTsuBefore, smallTsuAfter, smallTsu)
    }

    #expect(results.0.contains("切って"), "before Backspace: \(results.0)")
    #expect(results.2.convertTarget == "きっっ")
    #expect(results.2.convertTargetCursorPosition == 3)
    #expect(results.2.input.count == 8)
    #expect(
        results.2.input == "kixtuxtu".map {
            ComposingText.InputElement(character: $0, inputStyle: .roman2kana)
        }
    )
    #expect(!results.1.contains("切って"), "after Backspace: \(results.1)")
}

@Test func keyboardTypoCorrectionRewriteEditHistoryCursorDelete() async throws {
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-typo-cursor-delete-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: memoryURL) }
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    let results = await MainActor.run {
        let testConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: true
        )
        let options = testConvertRequestOptions(
            memoryURL: memoryURL,
            experimentalKeyboardTypoCorrection: true
        )

        func candidateTexts(_ composingText: ComposingText) -> [String] {
            testConverter.requestCandidates(
                composingText,
                options: options
            ).mainResults.map(\.text)
        }

        var doubleNn = ComposingText()
        for key in "konnnnitiha" {
            doubleNn.insertAtCursorPosition(String(key), inputStyle: .roman2kana)
        }
        let before = candidateTexts(doubleNn)
        let cursorDelta = doubleNn.moveCursorFromCursorPosition(count: -4)
        doubleNn.deleteForwardFromCursorPosition(count: 1)
        // Cursor-prefix conversion is the production path while the caret is
        // inside the composition; the suffix remains visible but unconverted.
        let after = candidateTexts(doubleNn.prefixToCursorPosition())
        return (before, after, doubleNn, cursorDelta)
    }

    #expect(results.0.contains("こんにちは"), "before cursor Delete: \(results.0)")
    #expect(results.3 == -4)
    #expect(results.2.convertTarget == "こんいちは")
    #expect(results.2.convertTargetCursorPosition == 2)
    #expect(results.2.input.count == 10)
    #expect(
        results.2.input == [
            ComposingText.InputElement(character: "k", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "o", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "n", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "n", inputStyle: .roman2kana),
            ComposingText.InputElement(
                piece: .compositionSeparator,
                inputStyle: .mapped(id: .empty)
            ),
            ComposingText.InputElement(character: "i", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "t", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "i", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "h", inputStyle: .roman2kana),
            ComposingText.InputElement(character: "a", inputStyle: .roman2kana),
        ]
    )
    #expect(!results.1.contains("こんにちは"), "after cursor Delete: \(results.1)")
}

@Test func surfaceCountTracksUnderlyingRomanInputLength() async throws {
    let resolved = await MainActor.run {
        var composingText = ComposingText()
        composingText.insertAtCursorPosition("kato", inputStyle: .roman2kana)
        return resolveCandidateComposition(
            composingText: composingText,
            candidateComposingCount: .surfaceCount(1)
        )
    }

    #expect(resolved.correspondingCount == 2)
    #expect(resolved.remainingConvertTarget == "と")
}

@Test func compositeSurfaceCountPreservesClauseOffset() async throws {
    let resolved = await MainActor.run {
        var composingText = ComposingText()
        composingText.insertAtCursorPosition("kato", inputStyle: .roman2kana)
        return resolveCandidateComposition(
            composingText: composingText,
            candidateComposingCount: .composite(lhs: .inputCount(0), rhs: .surfaceCount(1))
        )
    }

    #expect(resolved.correspondingCount == 2)
    #expect(resolved.remainingConvertTarget == "と")
}

@Test func trailingNPreviewFinalizesRoman2KanaOnlyInPreview() async throws {
    let result = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("kagen", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source)
        return (source: source, preview: preview)
    }

    #expect(result.source.convertTarget == "かげn")
    #expect(result.source.input.count == 5)
    #expect(result.preview.syntheticEndOfText)
    #expect(result.preview.composingText.convertTarget == "かげん")
    #expect(result.preview.composingText.input.count == 6)
}

@Test func trailingNPreviewSkipsDirectInput() async throws {
    let preview = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("n", inputStyle: .direct)
        return makeCandidatePreviewComposingText(from: source)
    }

    #expect(preview.syntheticEndOfText == false)
    #expect(preview.composingText.convertTarget == "n")
    #expect(preview.composingText.input.count == 1)
}

@Test func trailingNPreviewKeepsCommittedRomanSequencesUntouched() async throws {
    let preview = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("kann", inputStyle: .roman2kana)
        return makeCandidatePreviewComposingText(from: source)
    }

    #expect(preview.syntheticEndOfText == false)
    #expect(preview.composingText.convertTarget == "かん")
}

@Test func trailingNPreviewSupportsCustomRomajiTable() async throws {
    let rows = [
        row("ka", "か"),
        row("ge", "げ"),
        row("n", "ん"),
        row("na", "な"),
        row("nn", "ん"),
        row("n'", "ん"),
        row("nya", "にゃ"),
        row("-", "ー"),
    ]
    let result = try await MainActor.run {
        let inputStyle = try makeTemporaryCustomInputStyle(rows)
        var source = ComposingText()
        source.insertAtCursorPosition("kagen", inputStyle: inputStyle)
        let preview = makeCandidatePreviewComposingText(from: source)
        return (source: source, preview: preview)
    }

    #expect(result.source.convertTarget == "かげn")
    #expect(result.preview.syntheticEndOfText)
    #expect(result.preview.composingText.convertTarget == "かげん")
}

@Test func customRomajiTableCommitsNBeforeConsonant() async throws {
    let rows = [
        row("n", "ん"),
        row("na", "な"),
        row("nn", "ん"),
        row("n'", "ん"),
        row("nya", "にゃ"),
        row("ta", "た"),
    ]
    let convertTarget = try await MainActor.run {
        let inputStyle = try makeTemporaryCustomInputStyle(rows)
        var source = ComposingText()
        source.insertAtCursorPosition("nta", inputStyle: inputStyle)
        return source.convertTarget
    }

    #expect(convertTarget == "んた")
}

@Test func dictionaryCandidatesIncludeKanjiAfterRomanTrailingNPreview() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-test-\(UUID().uuidString)")
    defer {
        try? FileManager.default.removeItem(at: memoryURL)
    }

    let candidates = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("iikagenn", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source)
        let previewHiragana = preview.composingText.convertTarget
        let testConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        return testConverter.requestCandidates(
            preview.composingText,
            options: testConvertRequestOptions(memoryURL: memoryURL)
        )
        .mainResults
        .map { constructCandidateString(candidate: $0, hiragana: previewHiragana) }
    }

    #expect(candidates.contains { $0.contains("加減") }, "candidates: \(candidates)")
}

@Test func singleWordKanjiCandidateBeatsHiraganaPrediction() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")
    let memoryURL = FileManager.default.temporaryDirectory
        .appending(path: "azookey-server-test-\(UUID().uuidString)")
    defer {
        try? FileManager.default.removeItem(at: memoryURL)
    }

    let candidates = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("kannji", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source)
        let previewHiragana = preview.composingText.convertTarget
        let testConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        return testConverter.requestCandidates(
            preview.composingText,
            options: testConvertRequestOptions(memoryURL: memoryURL)
        )
        .mainResults
        .prefix(5)
        .map { candidate in
            constructCandidateString(candidate: candidate, hiragana: previewHiragana)
        }
    }

    #expect(candidates.first == "感じ", "candidates: \(candidates)")
}

@Test func trailingNPreviewUsesPreviewSuffixForDisplaySubtext() async throws {
    let resolved = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("kagen", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source)
        return resolveCandidateCompositionForDisplay(
            originalComposingText: source,
            previewComposingText: preview.composingText,
            candidateComposingCount: .surfaceCount(2)
        )
    }

    #expect(resolved.correspondingCount == 4)
    #expect(resolved.remainingConvertTarget == "ん")
}

@Test func singleNBoundaryKeepsFollowingConsonantInRemainingText() async throws {
    let resolved = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("iikagentouitusiro", inputStyle: .roman2kana)
        return resolveCandidateComposition(
            composingText: source,
            candidateComposingCount: .inputCount(8)
        )
    }

    #expect(resolved.correspondingCount == 7)
    #expect(resolved.remainingConvertTarget == "とういつしろ")
}

@Test func trailingNPreviewForCursorPrefixOnlyAppliesAtCompositionEnd() async throws {
    let result = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("kagen", inputStyle: .roman2kana)
        let endPreview = makeCandidatePreviewComposingTextForCursorPrefix(
            prefixComposingText: source.prefixToCursorPosition(),
            suffixAfterCursor: ""
        )

        _ = source.moveCursorFromCursorPosition(count: -1)
        let midPrefix = source.prefixToCursorPosition()
        let midSuffix = String(source.convertTarget.dropFirst(source.convertTargetCursorPosition))
        let midPreview = makeCandidatePreviewComposingTextForCursorPrefix(
            prefixComposingText: midPrefix,
            suffixAfterCursor: midSuffix
        )

        return (endPreview: endPreview, midPreview: midPreview, midSuffix: midSuffix)
    }

    #expect(result.endPreview.syntheticEndOfText)
    #expect(result.endPreview.composingText.convertTarget == "かげん")
    #expect(result.midPreview.syntheticEndOfText == false)
    #expect(result.midPreview.composingText.convertTarget == "かげ")
    #expect(result.midSuffix == "n")
}

@Test func trailingNPreviewDoesNotGeneralizeToOtherDelayedPrefixes() async throws {
    let rows = [
        row("q", "く"),
        row("qa", "くぁ"),
    ]
    let preview = try await MainActor.run {
        let inputStyle = try makeTemporaryCustomInputStyle(rows)
        var source = ComposingText()
        source.insertAtCursorPosition("q", inputStyle: inputStyle)
        return makeCandidatePreviewComposingText(from: source)
    }

    #expect(preview.syntheticEndOfText == false)
    #expect(preview.composingText.convertTarget == "q")
}

@Test func backgroundWarmupAvoidsSharedInputStyleRegistry() async {
    let metrics = await MainActor.run {
        let disabled = makeBackgroundWarmupComposingText(zenzaiRuntimeEnabled: false)
        let enabled = makeBackgroundWarmupComposingText(zenzaiRuntimeEnabled: true)
        return (disabled: disabled, enabled: enabled)
    }

    #expect(metrics.disabled.convertTarget == "あ")
    #expect(metrics.enabled.convertTarget == "にほんご")
    #expect(metrics.disabled.input.count == 1)
    #expect(metrics.enabled.input.count == 4)
    #expect(metrics.disabled.input.allSatisfy { $0.inputStyle == .direct })
    #expect(metrics.enabled.input.allSatisfy { $0.inputStyle == .direct })
    #expect(
        effectiveZenzaiEnabledForCandidates(
            isConfigured: true,
            inputCount: metrics.disabled.input.count,
            hiraganaCount: metrics.disabled.convertTarget.count
        ) == false
    )
    #expect(
        effectiveZenzaiEnabledForCandidates(
            isConfigured: true,
            inputCount: metrics.enabled.input.count,
            hiraganaCount: metrics.enabled.convertTarget.count
        )
    )
    #expect(backgroundWarmupPreloadsDictionary == false)
}

@Test func cursorPrefixCandidatesSupplementFirstClauseWithMainResultsForSameBoundary() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("aruteidonagai", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let firstClause = testCandidate(
            word: "ある程度",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let hiragana = testCandidate(
            word: "あるていど",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let katakana = testCandidate(
            word: "アルテイド",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let fullSentence = Candidate(
            text: "ある程度長い",
            value: -1,
            composingCount: .inputCount(13),
            lastMid: MIDData.一般.mid,
            data: [
                DicdataElement(
                    word: "ある程度長い",
                    ruby: "あるていどながい",
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -1
                )
            ]
        )
        return cursorPrefixCandidateResults(
            mainResults: [fullSentence, hiragana, katakana],
            firstClauseResults: [firstClause],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: preview.convertTarget) }
    }

    #expect(resultTexts == ["ある程度", "あるていど", "アルテイド"])
}

@Test func cursorPrefixCandidatesDropFirstClauseResultsForDifferentBoundary() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("iikagentouitusiro", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let firstClause = testCandidate(
            word: "いい加減",
            ruby: "いいかげん",
            composingCount: .inputCount(7)
        )
        let tooShort = testCandidate(
            word: "いい",
            ruby: "いい",
            composingCount: .inputCount(2)
        )
        let hiragana = testCandidate(
            word: "いいかげん",
            ruby: "いいかげん",
            composingCount: .inputCount(7)
        )
        return cursorPrefixCandidateResults(
            mainResults: [],
            firstClauseResults: [firstClause, tooShort, hiragana],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: preview.convertTarget) }
    }

    #expect(resultTexts == ["いい加減", "いいかげん"])
}

@Test func cursorPrefixCandidatesUseLongestFirstClauseBoundaryWhenShorterCandidateRanksFirst() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("iikagentouitusiro", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let tooShort = testCandidate(
            word: "いい",
            ruby: "いい",
            composingCount: .inputCount(2)
        )
        let firstClause = testCandidate(
            word: "いい加減",
            ruby: "いいかげん",
            composingCount: .inputCount(7)
        )
        let hiragana = testCandidate(
            word: "いいかげん",
            ruby: "いいかげん",
            composingCount: .inputCount(7)
        )
        return cursorPrefixCandidateResults(
            mainResults: [],
            firstClauseResults: [tooShort, firstClause, hiragana],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: preview.convertTarget) }
    }

    #expect(resultTexts == ["いい加減", "いいかげん"])
}

@Test func cursorPrefixCandidatesRejectBoundaryThatConsumesPastDisplayedRuby() async throws {
    let boundary = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition(
            "aruteidonagaibunsyoudemofukusuubunsetunibunkatusareru",
            inputStyle: .roman2kana
        )
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let overconsuming = testCandidate(
            word: "ある程度",
            ruby: "あるていど",
            composingCount: .inputCount(10)
        )
        let displayAligned = testCandidate(
            word: "ある程度",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )

        return cursorPrefixFirstClauseCorrespondingCount(
            firstClauseResults: [overconsuming, displayAligned],
            originalComposingText: source,
            previewComposingText: preview
        )
    }

    #expect(boundary == 8)
}

@Test func cursorPrefixCandidatesPreferClauseTerminalBoundaryOverLongerNounPrefix() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("wagahaihanekodearunamaehamadanai", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let badLongBoundary = Candidate(
            text: "吾輩は猫である名",
            value: -1,
            composingCount: .inputCount(20),
            lastMid: MIDData.一般.mid,
            data: [
                DicdataElement(
                    word: "吾輩は猫である名",
                    ruby: "わがはいはねこであるな",
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -1
                )
            ]
        )
        let sentenceBoundary = Candidate(
            text: "吾輩は猫である",
            value: -1,
            composingCount: .inputCount(18),
            lastMid: MIDData.一般.mid,
            data: [
                DicdataElement(
                    word: "吾輩は猫である",
                    ruby: "わがはいはねこである",
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -1
                )
            ]
        )
        return cursorPrefixCandidateDisplayResults(
            mainResults: [],
            firstClauseResults: [badLongBoundary, sentenceBoundary],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map(\.displayText)
    }

    #expect(resultTexts == ["吾輩は猫である"])
}

@Test func cursorPrefixCandidatesPreferProperBoundaryOverFullPhraseCandidate() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("touitusiro", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let fullPhrase = testCandidate(
            word: "統一しろ",
            ruby: "とういつしろ",
            composingCount: .inputCount(10)
        )
        let firstClause = testCandidate(
            word: "統一",
            ruby: "とういつ",
            composingCount: .inputCount(6)
        )
        let hiragana = testCandidate(
            word: "とういつ",
            ruby: "とういつ",
            composingCount: .inputCount(6)
        )
        let katakana = testCandidate(
            word: "トウイツ",
            ruby: "とういつ",
            composingCount: .inputCount(6)
        )
        return cursorPrefixCandidateDisplayResults(
            mainResults: [fullPhrase],
            firstClauseResults: [fullPhrase, firstClause],
            exactClauseResults: [hiragana, katakana],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map(\.displayText)
    }

    #expect(resultTexts == ["統一", "とういつ", "トウイツ"])
}

@Test func cursorPrefixCandidatesSupplementWithExactClauseResultsWhenMainResultsLackSameBoundary() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("aruteidonagaibunsetsudemo", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let firstClause = testCandidate(
            word: "ある程度",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let fullSentence = Candidate(
            text: "ある程度長い文節でも",
            value: -1,
            composingCount: .inputCount(25),
            lastMid: MIDData.一般.mid,
            data: [
                DicdataElement(
                    word: "ある程度長い文節でも",
                    ruby: "あるていどながいぶんせつでも",
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -1
                )
            ]
        )
        let hiragana = testCandidate(
            word: "あるていど",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        let katakana = testCandidate(
            word: "アルテイド",
            ruby: "あるていど",
            composingCount: .inputCount(8)
        )
        return cursorPrefixCandidateResults(
            mainResults: [fullSentence],
            firstClauseResults: [firstClause],
            exactClauseResults: [hiragana, katakana],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: preview.convertTarget) }
    }

    #expect(resultTexts == ["ある程度", "あるていど", "アルテイド"])
}

@Test func cursorPrefixCandidatesSupplementParticleClauseWithExactClauseResults() async throws {
    let resultTexts = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("bunsetsudemofukusuunibunkatsusareru", inputStyle: .roman2kana)
        let preview = makeCandidatePreviewComposingText(from: source).composingText
        let firstClause = testCandidate(
            word: "文節でも",
            ruby: "ぶんせつでも",
            composingCount: .inputCount(12)
        )
        let alternative = testCandidate(
            word: "分節でも",
            ruby: "ぶんせつでも",
            composingCount: .inputCount(12)
        )
        let hiragana = testCandidate(
            word: "ぶんせつでも",
            ruby: "ぶんせつでも",
            composingCount: .inputCount(12)
        )
        let katakana = testCandidate(
            word: "ブンセツデモ",
            ruby: "ぶんせつでも",
            composingCount: .inputCount(12)
        )
        let fullSentence = Candidate(
            text: "文節でも複数に分割される",
            value: -1,
            composingCount: .inputCount(35),
            lastMid: MIDData.一般.mid,
            data: [
                DicdataElement(
                    word: "文節でも複数に分割される",
                    ruby: "ぶんせつでもふくすうにぶんかつされる",
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -1
                )
            ]
        )
        return cursorPrefixCandidateResults(
            mainResults: [fullSentence],
            firstClauseResults: [firstClause, alternative],
            exactClauseResults: [firstClause, alternative, hiragana, katakana],
            originalComposingText: source,
            previewComposingText: preview,
            previewHiragana: preview.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: preview.convertTarget) }
    }

    #expect(resultTexts == ["文節でも", "分節でも", "ぶんせつでも", "ブンセツデモ"])
}

@Test func cursorPrefixExactClauseComposingTextPreservesSelectedClauseInput() async throws {
    let clause = await MainActor.run {
        var source = ComposingText()
        source.insertAtCursorPosition("aruteidonagaibunsetsudemo", inputStyle: .roman2kana)
        return makeCursorPrefixExactClauseComposingText(
            prefixComposingText: source,
            correspondingCount: 8
        )
    }

    #expect(clause.convertTarget == "あるていど")
    #expect(clause.input.count == 8)
}

@Test func trailingUncompletedRomanSuppressesPrediction() async throws {
    var pureHira = ComposingText()
    pureHira.insertAtCursorPosition("ta", inputStyle: .roman2kana)
    #expect(!hasTrailingUncompletedRoman(in: pureHira))

    var incompleteRoman = ComposingText()
    incompleteRoman.insertAtCursorPosition("tak", inputStyle: .roman2kana)
    #expect(hasTrailingUncompletedRoman(in: incompleteRoman))

    var singleConsonant = ComposingText()
    singleConsonant.insertAtCursorPosition("k", inputStyle: .roman2kana)
    #expect(hasTrailingUncompletedRoman(in: singleConsonant))

    var finishedWord = ComposingText()
    finishedWord.insertAtCursorPosition("takano", inputStyle: .roman2kana)
    #expect(!hasTrailingUncompletedRoman(in: finishedWord))
}

@Test func optionsForNormalNBestSupplementDisablesPrediction() async throws {
    let original = ConvertRequestOptions(
        requireJapanesePrediction: .autoMix,
        requireEnglishPrediction: .autoMix,
        keyboardLanguage: .ja_JP,
        learningType: .nothing,
        memoryDirectoryURL: URL(fileURLWithPath: "/tmp"),
        sharedContainerURL: URL(fileURLWithPath: "/tmp"),
        textReplacer: .init { URL(fileURLWithPath: "/tmp") },
        specialCandidateProviders: nil,
        metadata: nil
    )
    let supplement = optionsForNormalNBestSupplement(from: original)
    #expect(supplement.requireJapanesePrediction == .disabled)
    #expect(supplement.requireEnglishPrediction == .disabled)
}

@Test func optionsForZenzaiConversionAndNormalNBestSupplementWithPredictionMatch11B() async throws {
    let predictiveEnabled = await MainActor.run {
        var opts = getOptions(zenzaiEnabled: true)
        opts.requireJapanesePrediction = .autoMix
        opts.requireEnglishPrediction = .autoMix
        return opts
    }
    let zenzaiOpts = optionsForZenzaiConversion(from: predictiveEnabled)
    #expect(zenzaiOpts.requireJapanesePrediction == .disabled)
    #expect(zenzaiOpts.requireEnglishPrediction == .disabled)

    let supplementPredictive = optionsForNormalNBestSupplementWithPrediction(from: predictiveEnabled)
    #expect(supplementPredictive.requireJapanesePrediction == .manualMix)
    #expect(supplementPredictive.requireEnglishPrediction == .manualMix)

    let predictiveDisabled = await MainActor.run {
        var opts = getOptions(zenzaiEnabled: true)
        opts.requireJapanesePrediction = .disabled
        opts.requireEnglishPrediction = .disabled
        return opts
    }
    let supplementDisabled = optionsForNormalNBestSupplementWithPrediction(from: predictiveDisabled)
    #expect(supplementDisabled.requireJapanesePrediction == .disabled)
    #expect(supplementDisabled.requireEnglishPrediction == .disabled)
}

@Test func mergePredictiveSupplementCandidatesPlacesPredictionsAfterFullSpanExactAndBeforeSubClauses() async throws {
    let hiragana = "よろし"
    let full1 = testCandidate(word: "宜し", ruby: "よろし", composingCount: .inputCount(3))
    let full2 = testCandidate(word: "芳し", ruby: "よろし", composingCount: .inputCount(3))
    let partial = testCandidate(word: "よろ", ruby: "よろ", composingCount: .inputCount(2))

    let dupFull = testCandidate(word: "宜し", ruby: "よろし", composingCount: .inputCount(3))
    let echoHira = testCandidate(word: "よろし", ruby: "よろし", composingCount: .inputCount(3))
    let echoKata = testCandidate(word: "ヨロシ", ruby: "よろし", composingCount: .inputCount(3))
    let emptyPred = testCandidate(word: "   ", ruby: "よろし", composingCount: .inputCount(3))
    let pred1 = testCandidate(word: "よろしくお願いします", ruby: "よろしくおねがいします", composingCount: .inputCount(3))
    let pred2 = testCandidate(word: "よろしくお願いいたします", ruby: "よろしくおねがいいたします", composingCount: .inputCount(3))
    let pred3 = testCandidate(word: "よろしいですか", ruby: "よろしいですか", composingCount: .inputCount(3))
    let pred4 = testCandidate(word: "よろしくね", ruby: "よろしくね", composingCount: .inputCount(3))

    let merged = mergePredictiveSupplementCandidates(
        exactMerged: [full1, full2, partial],
        predictionResults: [dupFull, echoHira, echoKata, emptyPred, pred1, pred2, pred3, pred4],
        hiragana: hiragana
    )

    #expect(
        merged.map(\.text) == [
            "宜し",
            "芳し",
            "よろしくお願いします",
            "よろしくお願いいたします",
            "よろしいですか",
            "よろ",
        ]
    )
}

@Test func mergePredictiveSupplementCandidatesKeepsPredictionsOnFirstPageWhenManyFullSpanExactExist() async throws {
    let hiragana = "しんぎゅら"
    let fullCandidates = (1...5).map { idx in
        testCandidate(word: "候補\(idx)", ruby: hiragana, composingCount: .inputCount(8))
    }
    let partial = testCandidate(word: "しんぎゅ", ruby: "しんぎゅ", composingCount: .inputCount(6))
    let pred1 = testCandidate(word: "シンギュラリティ", ruby: "しんぎゅらりてぃ", composingCount: .inputCount(8))
    let pred2 = testCandidate(word: "シンギュラリティー", ruby: "しんぎゅらりてぃー", composingCount: .inputCount(8))

    let merged = mergePredictiveSupplementCandidates(
        exactMerged: fullCandidates + [partial],
        predictionResults: [pred1, pred2],
        hiragana: hiragana
    )

    #expect(
        merged.map(\.text) == [
            "候補1",
            "候補2",
            "候補3",
            "シンギュラリティ",
            "シンギュラリティー",
            "候補4",
            "候補5",
            "しんぎゅ",
        ]
    )
}

@MainActor
@Test func wholeReadingCompletionsKeepPredictionVisibleAcrossCompletedAndPendingRomaji() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    let previousConverter = converter
    let previousSupplement = normalNBestSupplementConverter
    let previousComposing = composingText
    let previousSnapshots = composingTextSnapshots
    let previousInputStyle = currentInputStyle
    let previousLearningType = currentLearningType
    let previousZenzaiEnable = config["enable"]
    let previousPredictive = config["predictiveConversion"]
    let previousContext = config["context"]
    let previousContinuation = conversionContinuation
    let previousCommitted = lastCommittedContinuation
    let previousCache = learningCandidateCache
    let previousOverrides = learningSelectionOverrides
    defer {
        converter = previousConverter
        normalNBestSupplementConverter = previousSupplement
        composingText = previousComposing
        composingTextSnapshots = previousSnapshots
        currentInputStyle = previousInputStyle
        currentLearningType = previousLearningType
        config["enable"] = previousZenzaiEnable
        config["predictiveConversion"] = previousPredictive
        config["context"] = previousContext
        conversionContinuation = previousContinuation
        lastCommittedContinuation = previousCommitted
        learningCandidateCache = previousCache
        learningSelectionOverrides = previousOverrides
        invalidateAutomaticConversionContinuationCache()
    }

    converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
    normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: false)
    currentInputStyle = .roman2kana
    currentLearningType = .nothing
    learningCandidateCache.removeAll()
    learningSelectionOverrides.removeAll()
    config["enable"] = false
    config["predictiveConversion"] = true
    config["context"] = ""
    conversionContinuation = nil
    lastCommittedContinuation = nil

    func collectCandidates(allowZenzai: Bool) -> [String] {
        var length: CInt = 0
        let ptr = get_composed_text_impl(lengthPtr: &length, allowZenzai: allowZenzai)
        defer { free_candidate_list(ptr, length) }
        return (0..<Int(length)).compactMap { idx in
            ptr[idx]?.pointee.text.map { String(cString: $0) }
        }
    }

    // 1. singyur -> singyura -> singyurar -> singyurari must keep シンギュラリティ in top 6
    composingText = ComposingText()
    for ch in "singyur" {
        composingText.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
    }
    let atSingyur = collectCandidates(allowZenzai: false)
    #expect(atSingyur.prefix(6).contains("シンギュラリティ"), "singyur: \(Array(atSingyur.prefix(9)))")

    for (step, ch) in [("singyura", "a"), ("singyurar", "r"), ("singyurari", "i")] {
        composingText.insertAtCursorPosition(ch, inputStyle: .roman2kana)
        let list = collectCandidates(allowZenzai: false)
        #expect(list.prefix(6).contains("シンギュラリティ"), "\(step): \(Array(list.prefix(9)))")
    }

    // 2. komyuni -> komyunik -> komyunike must keep コミュニケーション in top 6
    composingText = ComposingText()
    for ch in "komyuni" {
        composingText.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
    }
    let atKomyuni = collectCandidates(allowZenzai: false)
    #expect(atKomyuni.prefix(6).contains("コミュニケーション"), "komyuni: \(Array(atKomyuni.prefix(9)))")

    for (step, ch) in [("komyunik", "k"), ("komyunike", "e")] {
        composingText.insertAtCursorPosition(ch, inputStyle: .roman2kana)
        let list = collectCandidates(allowZenzai: false)
        #expect(list.prefix(6).contains("コミュニケーション"), "\(step): \(Array(list.prefix(9)))")
    }
}

@Test func realConverterManualMixSeparatesPureExactMainResultsFromPredictionsForZenzai11B() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    try await MainActor.run {
        let converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)

        var input = ComposingText()
        input.insertAtCursorPosition("yorosi", inputStyle: .roman2kana)
        let hiragana = input.convertTarget
        #expect(hiragana == "よろし")

        var basePredictiveOptions = getOptions(zenzaiEnabled: true)
        basePredictiveOptions.requireJapanesePrediction = .autoMix
        basePredictiveOptions.requireEnglishPrediction = .autoMix
        let zenzaiOpts = optionsForZenzaiConversion(from: basePredictiveOptions)
        let supplementOpts = optionsForNormalNBestSupplementWithPrediction(from: basePredictiveOptions)

        let zenzaiStyleResult = converter.requestCandidates(input, options: zenzaiOpts)
        let supplementResult = converter.requestCandidates(input, options: supplementOpts)

        #expect(zenzaiStyleResult.predictionResults.isEmpty)
        #expect(!supplementResult.predictionResults.isEmpty)
        #expect(supplementResult.mainResults.map(\.text) == zenzaiStyleResult.mainResults.map(\.text))

        let combined = mergePredictiveSupplementCandidates(
            exactMerged: zenzaiStyleResult.mainResults,
            predictionResults: supplementResult.predictionResults,
            hiragana: hiragana
        )
        let topExact = try #require(zenzaiStyleResult.mainResults.first?.text)
        #expect(combined.first?.text == topExact)
        let firstPartialIndex = combined.firstIndex { $0.rubyCount < hiragana.count }
        let firstPredictionIndex = combined.firstIndex { candidate in
            supplementResult.predictionResults.contains { $0.text == candidate.text }
                && !zenzaiStyleResult.mainResults.contains { $0.text == candidate.text }
        }
        if let firstPredictionIndex {
            #expect(firstPredictionIndex >= 1)
            if let firstPartialIndex {
                #expect(firstPredictionIndex < firstPartialIndex)
            }
        }
    }
}

@Test func cursorMoveMidInsertAndCursorReturnAcrossSeparatorSucceeds() async throws {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    for keyboardTypoCorrection in [false, true] {
        let converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        var options = getOptions(zenzaiEnabled: false)
        options.experimentalKeyboardTypoCorrection = keyboardTypoCorrection

        // 1. Direct flick: "あいう" -> left 1 -> insert "か" -> right 1 back to end.
        var direct = ComposingText()
        for ch in ["あ", "い", "う"] {
            direct.insertAtCursorPosition(ch, inputStyle: .direct)
            _ = converter.requestCandidates(direct, options: options)
        }
        #expect(direct.moveCursorFromCursorPosition(count: -1) == -1)
        _ = converter.requestCandidates(direct.prefixToCursorPosition(), options: options)
        direct.insertAtCursorPosition("か", inputStyle: .direct)
        let directMid = converter.requestCandidates(direct.prefixToCursorPosition(), options: options)
        #expect(!directMid.mainResults.isEmpty)
        #expect(direct.moveCursorFromCursorPosition(count: 1) == 1)
        #expect(direct.convertTarget == "あいかう")
        let directReturned = converter.requestCandidates(direct, options: options)
        #expect(!directReturned.mainResults.isEmpty)

        // 2. Roman2Kana: pending consonant + completed kana in middle -> return right step by step.
        var roman = ComposingText()
        for ch in "watashihagakuseidesu" {
            roman.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
            let target = roman.isAtEndIndex ? roman : roman.prefixToCursorPosition()
            _ = converter.requestCandidates(target, options: options)
        }
        #expect(roman.moveCursorFromCursorPosition(count: -6) == -6) // わたしは|がくせいです
        _ = converter.requestCandidates(roman.prefixToCursorPosition(), options: options)
        for ch in "nihonno" {
            roman.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
            _ = converter.requestCandidates(roman.prefixToCursorPosition(), options: options)
        }
        for _ in 0..<6 {
            #expect(roman.moveCursorFromCursorPosition(count: 1) == 1)
            let target = roman.isAtEndIndex ? roman : roman.prefixToCursorPosition()
            let res = converter.requestCandidates(target, options: options)
            #expect(!res.mainResults.isEmpty)
        }

        // 3. Pending 'k' in middle -> return right.
        var pendingMid = ComposingText()
        for ch in "aiu" {
            pendingMid.insertAtCursorPosition(String(ch), inputStyle: .roman2kana)
            _ = converter.requestCandidates(pendingMid, options: options)
        }
        #expect(pendingMid.moveCursorFromCursorPosition(count: -1) == -1)
        _ = converter.requestCandidates(pendingMid.prefixToCursorPosition(), options: options)
        pendingMid.insertAtCursorPosition("k", inputStyle: .roman2kana)
        _ = converter.requestCandidates(pendingMid.prefixToCursorPosition(), options: options)
        #expect(pendingMid.moveCursorFromCursorPosition(count: 1) == 1)
        #expect(!converter.requestCandidates(pendingMid, options: options).mainResults.isEmpty)

        // 4. Mid-string backspace inserts frozen compositionSeparator -> return right.
        var midDelete = ComposingText()
        for ch in ["あ", "い", "う", "え"] {
            midDelete.insertAtCursorPosition(ch, inputStyle: .direct)
            _ = converter.requestCandidates(midDelete, options: options)
        }
        #expect(midDelete.moveCursorFromCursorPosition(count: -2) == -2)
        _ = converter.requestCandidates(midDelete.prefixToCursorPosition(), options: options)
        midDelete.deleteBackwardFromCursorPosition(count: 1)
        _ = converter.requestCandidates(midDelete.prefixToCursorPosition(), options: options)
        #expect(midDelete.moveCursorFromCursorPosition(count: 2) == 2)
        #expect(midDelete.convertTarget == "あうえ")
        #expect(!converter.requestCandidates(midDelete, options: options).mainResults.isEmpty)

        // 5. Prefix completion leaving a leading compositionSeparator.
        var leadingSep = ComposingText()
        leadingSep.insertAtCursorPosition([
            .init(character: "あ", inputStyle: .direct),
            .init(character: "い", inputStyle: .direct),
            .init(piece: .compositionSeparator, inputStyle: .mapped(id: .empty)),
            .init(character: "う", inputStyle: .direct),
            .init(character: "た", inputStyle: .direct),
        ])
        _ = converter.requestCandidates(leadingSep, options: options)
        leadingSep.prefixComplete(composingCount: .inputCount(2))
        let afterPrefixComplete = converter.requestCandidates(leadingSep, options: options)
        #expect(!afterPrefixComplete.mainResults.isEmpty)
    }
}

@Test func rawInputAndDeleteForwardHandleCompositionSeparatorsCleanly() async {
    await MainActor.run {
        let previousComposingText = composingText
        let previousInputStyle = currentInputStyle
        defer {
            composingText = previousComposingText
            currentInputStyle = previousInputStyle
        }

        composingText = ComposingText()
        currentInputStyle = .roman2kana
        var cursor: CInt = -1
        let initial = append_text(input: "kyoutenki", cursorPtr: &cursor)
        free_c_string(initial)
        #expect(composingText.convertTarget == "きょうてんき")
        #expect(cursor == 6)

        var moveDelta: CInt = 0
        // Move left 3 characters to place the cursor before "てんき".
        free_c_string(move_cursor(offset: -3, cursorPtr: &moveDelta))
        #expect(moveDelta == -3)
        #expect(get_cursor_position() == 3)

        // Insert "haii" before "てんき"; AzooKey inserts a compositionSeparator.
        free_c_string(append_text(input: "haii", cursorPtr: &cursor))
        #expect(composingText.convertTarget == "きょうはいいてんき")
        #expect(cursor == 6)
        #expect(get_cursor_position() == 6)

        let rawInputAfterInsert = get_raw_input()
        defer { free_c_string(rawInputAfterInsert) }
        #expect(rawInputAfterInsert.map { String(cString: $0) } == "kyouhaiitenki")

        // DeleteForwardText removes "て" right of cursor without moving cursor left.
        var cursorAfterDeleteForward: CInt = -1
        let hiraganaAfterDeleteForward = delete_forward_text(cursorPtr: &cursorAfterDeleteForward)
        defer { free_c_string(hiraganaAfterDeleteForward) }
        #expect(String(cString: hiraganaAfterDeleteForward) == "きょうはいいんき")
        #expect(cursorAfterDeleteForward == 6)

        // DeleteBackward at mid-sentence still yields canonical raw_input.
        var cursorAfterRemove: CInt = -1
        let hiraganaAfterRemove = remove_text(cursorPtr: &cursorAfterRemove)
        defer { free_c_string(hiraganaAfterRemove) }
        #expect(String(cString: hiraganaAfterRemove) == "きょうはいんき")
        #expect(cursorAfterRemove == 5)

        let rawInputAfterRemove = get_raw_input()
        defer { free_c_string(rawInputAfterRemove) }
        #expect(rawInputAfterRemove.map { String(cString: $0) } == "kyouhainki")
    }
}

@Test func cursorRangeConvertsFullPrefixPreservesSuffixAndHandlesStartCursor() async {
    let packageRoot = packageRootURL()
    let dictionaryURL = packageRoot
        .appending(path: "azooKey_dictionary_storage")
        .appending(path: "Dictionary")

    await MainActor.run {
        let previousConverter = converter
        let previousSupplementConverter = normalNBestSupplementConverter
        let previousComposingText = composingText
        let previousLearningType = currentLearningType
        let previousExecURL = execURL
        let previousZenzaiEnable = config["enable"]
        let previousPredictive = config["predictiveConversion"]
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplementConverter
            composingText = previousComposingText
            currentLearningType = previousLearningType
            execURL = previousExecURL
            config["enable"] = previousZenzaiEnable
            config["predictiveConversion"] = previousPredictive
        }

        converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        normalNBestSupplementConverter = KanaKanjiConverter(
            dictionaryURL: dictionaryURL,
            preloadDictionary: false
        )
        currentLearningType = .nothing
        execURL = packageRoot
        config["enable"] = false
        config["predictiveConversion"] = false

        composingText = ComposingText()
        for ch in "きょうはいいてんき" {
            composingText.insertAtCursorPosition(String(ch), inputStyle: .direct)
        }

        // Place cursor at "きょうはいい|てんき" (multi-clause prefix length 6).
        var moveDelta: CInt = 0
        free_c_string(move_cursor(offset: -3, cursorPtr: &moveDelta))
        #expect(get_cursor_position() == 6)

        var length: CInt = 0
        let list = get_composed_text_for_cursor_range(lengthPtr: &length)
        defer { free_candidate_list(list, length) }
        #expect(length > 0)
        let candidate0 = list[0]!.pointee
        // Full prefix [0..<6] is "きょうはいい", not truncated to first clause "きょうは".
        #expect(candidate0.correspondingCount == 6)
        #expect(String(cString: candidate0.subtext) == "てんき")
        #expect(String(cString: candidate0.hiragana) == "きょうはいいてんき")

        // Committing top candidate leaves only suffix "てんき" editable.
        free_c_string(shrink_text(offset: candidate0.correspondingCount))
        #expect(composingText.convertTarget == "てんき")

        // Moving cursor to start returns 0 candidates while preserving non-empty target.
        free_c_string(move_cursor(offset: -3, cursorPtr: &moveDelta))
        #expect(get_cursor_position() == 0)
        var startLength: CInt = -1
        let startList = get_composed_text_for_cursor_range(lengthPtr: &startLength)
        defer { free_candidate_list(startList, startLength) }
        #expect(startLength == 0)
    }
}

@Test func zenzDigitMaskingMasksAndRestoresWithoutCorruptingEnglishAndPartialBytes() {
    let candy = ZenzDigitMasking.mask("１００えんのあめ")
    #expect(candy.text == "namえんのあめ")
    #expect(candy.asciiDigits == ["100"])
    #expect(candy.rawDigits == ["１００"])
    #expect(ZenzDigitMasking.restore("nam円の飴", digits: candy.asciiDigits) == "100円の飴")

    let limited = ZenzDigitMasking.mask("Wi-Fi 6EとBluetooth 5.4", maxRuns: 2)
    #expect(limited.text == "Wi-Fi namEとBluetooth nam.4")
    #expect(limited.asciiDigits == ["6", "5"])
    #expect(limited.rawDigits == ["6", "5"])

    #expect(
        ZenzDigitMasking.restore("SELECT name FROM customers dynamic", digits: ["10"])
            == "SELECT name FROM customers dynamic"
    )
    #expect(ZenzDigitMasking.restore("namxnam", digits: ["3", "2"]) == "3x2")
    #expect(ZenzDigitMasking.restore("nambonまとめて持つ", digits: ["3"]) == "3bonまとめて持つ")

    #expect(ZenzDigitMasking.restoreBytes(Array("na".utf8), digits: ["3"], completePartialTrailingNam: true) == Array("3".utf8))
    #expect(ZenzDigitMasking.restoreBytes(Array("n".utf8), digits: ["3"], completePartialTrailingNam: true) == Array("3".utf8))
    #expect(ZenzDigitMasking.restoreBytes(Array("dyna".utf8), digits: ["3"], completePartialTrailingNam: true) == Array("dyna".utf8))

    let truncatedKanji = Array("nam円".utf8).dropLast()
    let restoredTruncated = ZenzDigitMasking.restoreBytes(Array(truncatedKanji), digits: ["100"])
    #expect(restoredTruncated == Array("100".utf8) + Array("円".utf8).dropLast())

    #expect(ZenzDigitMasking.restore("nam%の確率", digits: ["５０"], asciiDigits: ["50"]) == "50%の確率")
    #expect(ZenzDigitMasking.restore("nam円の飴", digits: ["１００"], asciiDigits: ["100"]) == "１００円の飴")
}

@MainActor @Test func userDictionaryEvaluatorProtectsCoinedAndAsciiWordsWithoutPromptInjection() throws {
    let packageRoot = packageRootURL()
    let modelURL = packageRoot
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-iq4nl-emb8.gguf")
    try #require(FileManager.default.fileExists(atPath: modelURL.path(percentEncoded: false)))

    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let emojiURL = packageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
    let testConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
    let cases = [
        (reading: "あおみの", ruby: "アオミノ", word: "碧海乃", expected: "碧海乃"),
        (reading: "ぜろこんをつかう", ruby: "ゼロコン", word: "ZeroConvert", expected: "ZeroConvertを使う"),
        (reading: "かおもじ", ruby: "カオモジ", word: "(^o^)", expected: "(^o^)"),
        (reading: "めあど", ruby: "メアド", word: "test@example.com", expected: "test@example.com"),
    ]
    testConverter.importDynamicUserDictionary(cases.map { entry in
        DicdataElement(word: entry.word, ruby: entry.ruby, cid: CIDData.固有名詞.cid, mid: MIDData.一般.mid, value: -5)
    })
    let options = optionsForZenzaiConversion(from: makeConvertRequestOptions(
        context: "",
        zenzaiEnabled: true,
        runtimeDirectoryURL: packageRoot,
        emojiDictionaryURL: emojiURL,
        zenzaiWeightURL: modelURL,
        profile: "",
        learningType: .nothing,
        experimentalKeyboardTypoCorrection: false
    ))
    for entry in cases {
        testConverter.stopComposition()
        var composing = ComposingText()
        composing.insertAtCursorPosition(entry.reading, inputStyle: .direct)
        let results = testConverter.requestCandidates(composing, options: options).mainResults
        #expect(results.contains { candidate in
            candidate.text == entry.expected && candidate.data.contains {
                $0.word == entry.word && ZenzUserDictionaryProtection.isProtectedUserDictionaryElement($0)
            }
        }, "reading=\(entry.reading) expected=\(entry.expected) candidates=\(results.map(\.text))")
    }
}

@Test func zenzDigitMaskingConvertsNumericInputsAndContextWithRealNanoV41() async throws {
    let packageRoot = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let repoRoot = packageRoot
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let envModel = ProcessInfo.processInfo.environment["ZEROCON_TYPO_EVALUATION_MODEL"]
        .flatMap { $0.isEmpty ? nil : URL(filePath: $0) }
    let defaultModel = repoRoot.appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-iq4nl-emb8.gguf")
    let modelURL = [envModel, defaultModel].compactMap { $0 }.first {
        FileManager.default.fileExists(atPath: $0.path(percentEncoded: false))
    }
    let resolvedModel = try #require(modelURL)

    let dictionaryURL = packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary")
    let emojiURL = packageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")

    let topResults: [(reading: String, context: String, zenzai: [String], merged: [String])] = await MainActor.run {
        let testConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: true)
        let cases: [(String, String)] = [
            ("2026ねん", ""),
            ("２０２６ねん", ""),
            ("3かいだて", ""),
            ("2027ねんどのよさん", ""),
            ("3ぼんまとめてもつ", ""),
            ("１００えんのあめ", ""),
            ("3にちのよてい", "2026ねん10がつ"),
            ("なまえをかく", "123ばんめの"),
        ]
        return cases.map { reading, context in
            testConverter.stopComposition()
            var composing = ComposingText()
            composing.insertAtCursorPosition(reading, inputStyle: .direct)
            let baseOptions = makeConvertRequestOptions(
                context: context,
                zenzaiEnabled: true,
                runtimeDirectoryURL: packageRoot,
                emojiDictionaryURL: emojiURL,
                zenzaiWeightURL: resolvedModel,
                profile: "",
                learningType: .nothing,
                experimentalKeyboardTypoCorrection: false
            )
            let results = testConverter.requestCandidates(
                composing,
                options: optionsForZenzaiConversion(from: baseOptions)
            ).mainResults
            let normalResults = testConverter.requestCandidates(
                composing,
                options: makeConvertRequestOptions(
                    context: context,
                    zenzaiEnabled: false,
                    runtimeDirectoryURL: packageRoot,
                    emojiDictionaryURL: emojiURL,
                    zenzaiWeightURL: resolvedModel,
                    profile: "",
                    learningType: .nothing,
                    experimentalKeyboardTypoCorrection: false
                )
            ).mainResults
            let merged = mergeZenzaiMainResultsWithNormalNBest(
                zenzaiResults: results,
                normalNBestResults: normalResults,
                hiragana: composing.convertTarget
            )
            return (reading, context, results.map(\.text), merged.map(\.text))
        }
    }

    let expectedTop: [(String, [String])] = [
        ("2026ねん", ["2026年"]),
        ("２０２６ねん", ["2026年", "２０２６年"]),
        ("3かいだて", ["3階建て", "3階建"]),
        ("2027ねんどのよさん", ["2027年度の予算"]),
        ("3ぼんまとめてもつ", ["3本まとめて持つ"]),
        ("１００えんのあめ", ["100円の飴", "１００円の飴", "100円の雨", "１００円の雨"]),
        ("3にちのよてい", ["3日の予定"]),
        ("なまえをかく", ["名前を書く"]),
    ]
    for (entry, expectation) in zip(topResults, expectedTop) {
        #expect(entry.reading == expectation.0)
        #expect(!entry.zenzai.isEmpty, "reading=\(entry.reading) produced empty zenzai results")
        for cand in entry.zenzai {
            #expect(!cand.contains("nam"), "unrestored nam in zenzai candidate=\(cand) for reading=\(entry.reading)")
        }
        let zenzaiTop = entry.zenzai.first ?? ""
        #expect(expectation.1.contains(zenzaiTop), "zenzai reading=\(entry.reading) context=\(entry.context) top=\(zenzaiTop) all=\(Array(entry.zenzai.prefix(5)))")
        let top = entry.merged.first ?? ""
        #expect(expectation.1.contains(top), "merged reading=\(entry.reading) context=\(entry.context) top=\(top) all=\(Array(entry.merged.prefix(5)))")
    }
}


@MainActor private func withDictionaryLearningState<T>(_ body: (URL, URL) throws -> T) throws -> T {
    let dictionaryURL = packageRootURL().appending(path: "azooKey_dictionary_storage/Dictionary")
    let directory = FileManager.default.temporaryDirectory.appending(path: "azookey-learning-\(UUID())")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let oldConverter = converter
    let oldSupplement = normalNBestSupplementConverter
    let oldType = currentLearningType
    let oldDirectory = currentLearningMemoryDirectoryURL
    let oldCache = learningCandidateCache
    let oldSelections = learningSelectionOverrides
    let oldBigramStore = postCompositionBigramStore
    let oldLastCommit = lastCommittedPostCompositionCandidate
    let oldConfig = config
    let oldContinuation = lastCommittedContinuation
    let oldPrivate = privateInputActive
    defer {
        set_private_input(oldPrivate)
        converter = oldConverter
        normalNBestSupplementConverter = oldSupplement
        currentLearningType = oldType
        currentLearningMemoryDirectoryURL = oldDirectory
        learningCandidateCache = oldCache
        learningSelectionOverrides = oldSelections
        postCompositionBigramStore = oldBigramStore
        clearPostCompositionPredictionState()
        lastCommittedPostCompositionCandidate = oldLastCommit
        lastCommittedContinuation = oldContinuation
        config = oldConfig
        try? FileManager.default.removeItem(at: directory)
    }
    set_private_input(false)
    converter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: false)
    normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: dictionaryURL, preloadDictionary: false)
    currentLearningType = .inputAndOutput
    currentLearningMemoryDirectoryURL = directory
    learningCandidateCache = LearningCandidateCache()
    learningSelectionOverrides = LearningSelectionStore()
    postCompositionBigramStore = PostCompositionBigramStore()
    clearPostCompositionPredictionState()
    config["enable"] = false
    config["context"] = ""
    config["predictiveConversion"] = true
    config["postCompositionPrediction"] = true
    return try body(directory, dictionaryURL)
}

@MainActor @Test func implicitCommitDoesNotHijackModelTopInOppositeContext() throws {
    try withDictionaryLearningState { _, _ in
        let bridge = testCandidate(word: "橋", ruby: "ハシ", composingCount: .inputCount(2))
        let chopsticks = testCandidate(word: "箸", ruby: "ハシ", composingCount: .inputCount(2))
        let id = try #require(cacheLearningCandidates([bridge]))
        #expect(commit_learning_candidate_with_explicit(candidateId: id, commitKind: 0, explicitSelection: false))
        let saved = try #require(learningSelectionOverrides.records(for: "はし").first)
        #expect(saved.explicitCount == 0)
        #expect(saved.implicitCount == 1)
        let results = prioritizeLearningSelectionOverrides(
            [chopsticks], ruby: "はし", leftContext: "ご飯を食べるのに",
            candidate: { $0 }, synthesizeElement: { $0 }
        )
        #expect(results.map(\.text) == ["箸"])
    }
}

@MainActor @Test func explicitSelectionWithMatchingLeftContextPromotesToTop() throws {
    try withDictionaryLearningState { _, _ in
        let bridge = testCandidate(word: "橋", ruby: "ハシ", composingCount: .inputCount(2))
        var alternative = testCandidate(word: "箸", ruby: "ハシ", composingCount: .inputCount(2))
        alternative.data[0].metadata.insert(.isFromUserDictionary)
        let learned = try #require(cacheLearningCandidates([bridge]))
        config["context"] = "川に架かる"
        #expect(commit_learning_candidate_with_explicit(candidateId: learned, commitKind: 0, explicitSelection: true))
        func ranked(_ context: String) -> [String] {
            prioritizeLearningSelectionOverrides([alternative, bridge], ruby: "はし", leftContext: context) { $0 }.map(\.text)
        }
        #expect(ranked("大きな川に架かる") == ["橋", "箸"])
        #expect(ranked("ご飯を食べるのに") == ["箸", "橋"])
        #expect(ranked("") == ["箸", "橋"])
    }
}

@MainActor @Test func partialReadingDoesNotSpliceClauseHeadIntoModelTop() throws {
    try withDictionaryLearningState { _, _ in
        let whole = testCandidate(word: "箸を取る", ruby: "ハシヲトル", composingCount: .inputCount(5))
        let modelHead = testCandidate(word: "箸", ruby: "ハシ", composingCount: .inputCount(2))
        let learned = testCandidate(word: "橋", ruby: "ハシ", composingCount: .inputCount(2))
        learningSelectionOverrides.record(output: "橋", for: "はし", leftContext: "川に架かる")
        let ranked = prioritizeLearningSelectionOverrides(
            [whole, modelHead, learned], ruby: "はしをとる", leftContext: "川に架かる"
        ) { $0 }
        #expect(ranked.map(\.text) == ["箸を取る", "箸", "橋"])
    }
}

@MainActor @Test func multiClauseSingleCommitRecordsChainBigramsAndReproducesSentence() throws {
    try withDictionaryLearningState { _, _ in
        let text = "今日は天気がいいから散歩に行こう"
        let reading = "きょうはてんきがいいからさんぽにいこう"
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        let candidates = converter.requestCandidates(input, options: testLearningConvertRequestOptions(
            memoryURL: try #require(currentLearningMemoryDirectoryURL)
        )).mainResults
        let sentence = try #require(candidates.first { $0.text == text }, "candidates=\(candidates.map(\.text))")
        let chunks = extractClauseChunks(from: sentence).map(\.surface)
        #expect(chunks == ["今日は", "天気が", "いいから", "散歩に", "行こう"],
                "elements=\(sentence.data)")
        let id = try #require(cacheLearningCandidates([sentence]))
        #expect(commit_learning_candidate(candidateId: id, commitKind: 0))
        #expect(postCompositionBigramStore.count == 4)
        let start = testCandidate(word: "今日は", ruby: "キョウハ", composingCount: .inputCount(4))
        let startID = try #require(cacheLearningCandidates([start]))
        #expect(commit_learning_candidate(candidateId: startID, commitKind: 0))
        let predictions = generatePostCompositionPredictions(leftSideCandidate: start, context: "今日は")
        #expect(predictions.first?.text == "天気が")
        #expect(predictions.contains { $0.text == "天気がいいから散歩に行こう" && $0.joinedCandidate.text == text })
        print("Sentence-chain regression:", chunks, predictions.prefix(2).map(\.text))
    }
}

@Test func postCompositionChainAvoidsCyclesAndStopsAtMaxDepth() throws {
    var store = PostCompositionBigramStore()
    func edge(_ previous: String, _ next: String, _ time: Int64 = 100) {
        store.record(previousSurface: previous, entry: .init(surface: next, ruby: next, rcid: 1285, mid: 1285), nowEpochMs: time)
    }
    for (left, right) in zip(["a", "b", "c", "d", "e"], ["b", "c", "d", "e", "f"]) { edge(left, right) }
    edge("b", "a", 101)
    let chain = try #require(store.chains(from: "a", nowEpochMs: 102).first)
    #expect(chain.map(\.surface) == ["b", "c", "d", "e"])
    let long = String(repeating: "あ", count: 22)
    edge("start", long)
    edge(long, String(repeating: "い", count: 19))
    #expect(store.chains(from: "start", nowEpochMs: 102).isEmpty)
    let legacy = Data(#"{"entriesByPrevious":{"前":[{"surface":"次","ruby":"つぎ","rcid":1285,"mid":1285}]},"recentPreviousSurfaces":["前"]}"#.utf8)
    var restored = try JSONDecoder().decode(PostCompositionBigramStore.self, from: legacy)
    #expect(restored.entries(for: "前").first?.count == 1)
    #expect(restored.entries(for: "前").first?.lastUsedEpochMs == 0)
    let forgot = restored.forget(surface: "次")
    #expect(forgot)
    #expect(restored.count == 0)
}

@Test func learningSelectionStoreMigratesV1JsonToV2Safely() throws {
    func reload(_ store: LearningSelectionStore) throws -> LearningSelectionStore {
        try JSONDecoder().decode(LearningSelectionStore.self, from: JSONEncoder().encode(store))
    }
    let v1 = Data(#"{"はし":["橋","箸"]}"#.utf8)
    var store = try JSONDecoder().decode(LearningSelectionStore.self, from: v1)
    #expect(store.outputs(for: "ハシ") == ["橋", "箸"])
    #expect(store.records(for: "ハシ").first?.explicitCount == 1)
    #expect(store.records(for: "ハシ").first?.implicitCount == 0)
    let v0 = Data(#"{"はし":"橋"}"#.utf8)
    #expect(try JSONDecoder().decode(LearningSelectionStore.self, from: v0).outputs(for: "ハシ") == ["橋"])
    for context in ["B", "A", "C", "D"] {
        store.record(output: "橋", for: "ハシ", leftContext: context)
    }
    store = try reload(store)
    store.record(output: "橋", for: "ハシ", leftContext: "E")
    let newest = try #require(store.records(for: "ハシ").first)
    #expect(newest.recentContexts == ["E", "D", "C", "A"])
    #expect(newest.contextCounts["B"] == nil)
    for index in 0..<7 {
        store.record(output: "表記\(index)", for: "よみ", nowEpochMs: Int64(index))
    }
    #expect(store.outputs(for: "ヨミ") == (1..<7).reversed().map { "表記\($0)" })
}

@MainActor @Test func learningModeReadOnlyAndDisabledRespectPersistenceAndReadContracts() throws {
    try withDictionaryLearningState { directory, _ in
        let model = testCandidate(word: "果糖", ruby: "カトウ", composingCount: .inputCount(3))
        let chosen = testCandidate(word: "加藤", ruby: "カトウ", composingCount: .inputCount(3))
        let id = try #require(cacheLearningCandidates([chosen]))
        #expect(commit_learning_candidate_with_explicit(candidateId: id, commitKind: 0, explicitSelection: true))
        let overrides = directory.appending(path: "selection-overrides.json")
        let saved = try Data(contentsOf: overrides)

        currentLearningType = .onlyOutput
        let readOnlyID = try #require(cacheLearningCandidates([chosen], allowReadOnlyDeletion: true))
        #expect(commit_learning_candidate_with_explicit(candidateId: readOnlyID, commitKind: 0, explicitSelection: true))
        #expect(try Data(contentsOf: overrides) == saved)
        #expect(prioritizeLearningSelectionOverrides([model, chosen], ruby: "かとう") { $0 }.first?.text == "加藤")

        currentLearningType = .nothing
        #expect(!forget_learning_candidate(candidateId: readOnlyID))
        #expect(prioritizeLearningSelectionOverrides([model, chosen], ruby: "かとう") { $0 }.first?.text == "果糖")
        #expect(try Data(contentsOf: overrides) == saved)
        currentLearningType = .onlyOutput
        #expect(forget_learning_candidate(candidateId: readOnlyID))
        loadLearningSelectionOverrides()
        #expect(learningSelectionOverrides.isEmpty)
        #expect(reset_learning_memory())

        currentLearningType = .nothing
        #expect(reset_learning_memory())
        #expect(!FileManager.default.fileExists(atPath: overrides.path))
    }
}

private func largeDictionaryFixture() -> [UserDictionaryEntry] {
    let positions: [UserDictionaryPos] = [.properNoun, .commonNoun, .sahenNoun, .symbol, .shortcut]
    let kana = Array("あいうえおかきくけこ")
    return (0...10_000).map { index in
        let reading = "じしょ" + String(String(index).map { kana[$0.wholeNumberValue!] })
        return UserDictionaryEntry(reading: reading, word: "登録語\(index)", pos: positions[index % positions.count])
    }
}

@Test func userDictionaryPosPresetsMapToExpectedCidsAndStableScoresAt10000Entries() {
    let snapshot = UserDictionarySnapshot(entries: largeDictionaryFixture())
    #expect(snapshot.entries.count == 10_000)
    #expect(snapshot.shortcuts.count == 2000)
    #expect(Array(snapshot.entries.prefix(5)).map(\.lcid) == [1288, 1285, 1283, 5, 1288])
    #expect(snapshot.entries.allSatisfy {
        $0.lcid == $0.rcid && $0.mid == MIDData.一般.mid &&
        $0.value() >= -6.5 && $0.metadata.contains(.isFromUserDictionary) &&
        !$0.metadata.contains(.isKeyboardTypoCorrection)
    })
    #expect(abs(snapshot.entries[9998].value() - (-5.9998)) < 0.0001)
    #expect(abs(snapshot.entries[9999].value() - (-1.09999)) < 0.0001)
}

@Test func userDictionaryNamePresetsUseSeparateConnectionAndMeaningIds() {
    let positions: [UserDictionaryPos] = [.personalName, .familyName, .givenName, .organization, .placeName]
    let snapshot = UserDictionarySnapshot(entries: positions.enumerated().map { index, pos in
        UserDictionaryEntry(reading: "よみ", word: "登録語\(index)", pos: pos)
    })
    #expect(snapshot.entries.map(\.lcid) == [1289, 1290, 1291, 1292, 1293])
    #expect(snapshot.entries.map(\.mid) == [501, 344, 370, 378, 501])
}

@MainActor @Test func userDictionary10000EntryLoadCompletesWithinBudget() throws {
    let start = ContinuousClock.now
    let snapshot = UserDictionarySnapshot(entries: largeDictionaryFixture())
    let elapsed = start.duration(to: .now)
    #expect(snapshot.entries.count == 10_000)
    #expect(elapsed < .seconds(5), "LOUDS build took \(elapsed)")
    let packageRoot = packageRootURL()
    let testConverter = KanaKanjiConverter(dictionaryURL: packageRoot.appending(path: "azooKey_dictionary_storage/Dictionary"), preloadDictionary: false)
    testConverter.installPreparedDynamicUserDictionary(snapshot.conversion)
    let options = testLearningConvertRequestOptions(memoryURL: packageRoot)
    for index in [0, 1, 2, 3, 9998, 9999] {
        let word = snapshot.entries[index]
        var input = ComposingText()
        input.insertAtCursorPosition(word.ruby, inputStyle: .direct)
        let candidates = testConverter.requestCandidates(input, options: options).mainResults
        #expect(candidates.contains { $0.text == word.word && (index != 9999 || !$0.isLearningTarget) },
                "\(index) \(word.ruby): \(candidates.map { ($0.text, $0.isLearningTarget) })")
        testConverter.stopComposition()
    }
    print("10,000-entry LOUDS build:", elapsed, "6 public conversion lookups")
}

@Test func userDictionaryDebouncedReloadCoalescesRapidConfigUpdates() async throws {
    let reloader = UserDictionaryReloader(delay: .milliseconds(30))
    let builds = Mutex<[Int]>([])
    for index in 0..<5 {
        reloader.schedule {
            builds.withLock { $0.append(index) }
            return UserDictionarySnapshot(entries: [.init(reading: "よみ", word: "更新\(index)")])
        }
    }
    var published: UserDictionarySnapshot?
    for _ in 0..<100 where published == nil {
        try await Task.sleep(for: .milliseconds(10))
        published = reloader.takePending()
    }
    #expect(published?.entries.map(\.word) == ["更新4"])
    #expect(builds.withLock { $0 } == [4])
    #expect(reloader.takePending() == nil)
}

@Test func loadUserDictionaryEntriesFallsBackToLegacyWhenDedicatedFileIsAbsent() throws {
    let dir = FileManager.default.temporaryDirectory
        .appending(path: "azookey-user-dict-load-test-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir) }

    let dictionaryURL = dir.appending(path: "user-dictionary.json")
    let legacy = [UserDictionaryEntry(reading: "ぜろ", word: "Zero", pos: .properNoun)]
    #expect(loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacy) == legacy)

    let v1Json = #"{"version":1,"entries":[{"reading":"めあど","word":"a@example.com","pos":"shortcut"}]}"#
    try v1Json.write(to: dictionaryURL, atomically: true, encoding: .utf8)
    #expect(loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacy) == [
        UserDictionaryEntry(reading: "めあど", word: "a@example.com", pos: .shortcut)
    ])

    try #"{"version":2,"entries":[]}"#.write(to: dictionaryURL, atomically: true, encoding: .utf8)
    #expect(loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacy) == nil)

    try "{not json".write(to: dictionaryURL, atomically: true, encoding: .utf8)
    #expect(loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacy) == nil)
}

@Test func userDictionaryReloaderInvalidatePendingDiscardsInFlightBuild() async throws {
    let reloader = UserDictionaryReloader(delay: .milliseconds(25))
    let built = Mutex(false)
    reloader.schedule {
        built.withLock { $0 = true }
        return UserDictionarySnapshot(
            entries: [.init(reading: "ふるい", word: "旧辞書")]
        )
    }
    reloader.invalidatePending()
    try await Task.sleep(for: .milliseconds(70))
    #expect(!built.withLock { $0 })
    #expect(reloader.takePending() == nil)
}

@MainActor @Test func flushLearningMemoryPersistsDirtyStoresOnly() throws {
    try withDictionaryLearningState { directory, _ in
        let selection = directory.appending(path: "selection-overrides.json")
        let bigrams = directory.appending(path: postCompositionBigramsFilename)
        #expect(flush_learning_memory())
        #expect(!FileManager.default.fileExists(atPath: selection.path))
        #expect(!FileManager.default.fileExists(atPath: bigrams.path))
        learningSelectionOverrides.record(output: "橋", for: "はし")
        learningSelectionOverridesDirty = true
        set_private_input(true)
        #expect(!flush_learning_memory())
        #expect(!FileManager.default.fileExists(atPath: selection.path))
        set_private_input(false)
        #expect(flush_learning_memory())
        let saved = try JSONDecoder().decode(LearningSelectionStore.self, from: Data(contentsOf: selection))
        #expect(saved.outputs(for: "ハシ") == ["橋"])
        #expect(!FileManager.default.fileExists(atPath: bigrams.path))
        try Data("sentinel".utf8).write(to: selection)
        #expect(flush_learning_memory())
        #expect(try Data(contentsOf: selection) == Data("sentinel".utf8))
    }
}

@MainActor @Test func resetConversionScopeClearsLearningMemorySelectionsAndBigrams() throws {
    try withDictionaryLearningState { directory, _ in
        let first = testCandidate(word: "今日は", ruby: "キョウハ", composingCount: .inputCount(4))
        let second = testCandidate(word: "天気が", ruby: "テンキガ", composingCount: .inputCount(4))
        let one = try #require(cacheLearningCandidates([first]))
        #expect(commit_learning_candidate(candidateId: one, commitKind: 0))
        config["context"] = "今日は"
        let two = try #require(cacheLearningCandidates([second]))
        #expect(commit_learning_candidate(candidateId: two, commitKind: 0))
        #expect(postCompositionBigramStore.count == 1)
        #expect(learningSelectionOverrides.outputCount == 2)
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path).count > 2)
        currentLearningType = .nothing
        #expect(reset_learning_memory())
        #expect(learningSelectionOverrides.isEmpty)
        #expect(postCompositionBigramStore.count == 0)
        #expect(!FileManager.default.fileExists(atPath: directory.path))
    }
}

@MainActor @Test func zenzaiRanksDictionarySuffixWhenPrefixReviewExhaustsInference() throws {
    let package = packageRootURL()
    let model = package.deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-iq4nl-emb8.gguf")
    try #require(FileManager.default.fileExists(atPath: model.path))
    let dictionary = package.appending(path: "azooKey_dictionary_storage/Dictionary")
    let emoji = package.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
    let store = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
    let main = KanaKanjiConverter(dicdataStore: store)
    let supplement = KanaKanjiConverter(dicdataStore: store)

    for (reading, context, acceptable) in [
        ("ぬるぽんえっくすをきどうする", "架空の名称を含む。", ["ぬるぽんえっくすを起動する"]),
        ("ぬるぽんえっくすをきどうする", "", ["ぬるぽんえっくすを起動する"]),
        ("あぷりをきどうする", "", ["アプリを起動する"]),
        ("きょうせいしっこうのもうしたて", "", ["強制執行の申立て"]),
        ("しんらばんしょうのことわり", "宇宙空間に存在するあらゆる物質や自然現象の法則を解き明かそうと研究に没頭する。", ["森羅万象の理"]),
        ("わいふぁいにつなぐ", "", ["Wi-Fiにつなぐ"]),
        ("みるふぁんぐをさいきどうする", "架空の名称を含む。", ["みるふぁんぐを再起動する", "ミルファングを再起動する"]),
    ] {
        main.stopComposition()
        supplement.stopComposition()
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        let options = optionsForZenzaiConversion(from: makeConvertRequestOptions(
            context: context, zenzaiEnabled: true, runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji, zenzaiWeightURL: model, profile: "",
            learningType: .nothing, experimentalKeyboardTypoCorrection: false
        ))
        var normalOptions = optionsForNormalNBestSupplement(from: options)
        normalOptions.zenzaiMode = .off
        _ = supplement.requestCandidates(input, options: normalOptions)
        main.adoptReusableFullInputLattice(supplement.exportReusableFullInputLattice(for: input))
        let actual = main.requestCandidates(input, options: options).mainResults.first?.text ?? ""
        #expect(acceptable.contains(actual), "reading=\(reading), actual=\(actual), acceptable=\(acceptable)")
    }

    var suru = ComposingText()
    suru.insertAtCursorPosition("する", inputStyle: .direct)
    let normal = testConvertRequestOptions(memoryURL: FileManager.default.temporaryDirectory)
    #expect(supplement.requestCandidates(suru, options: normal).mainResults.contains { $0.text == "摺る" })
}

@MainActor @Test func modelFirstAndPreparedUserDictionaryRegressionsWithRealNanoV41() throws {
    let packageRoot = packageRootURL()
    let model = packageRoot.deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-iq4nl-emb8.gguf")
    try #require(FileManager.default.fileExists(atPath: model.path))
    let emoji = packageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
    try withDictionaryLearningState { directory, _ in
        func convert(_ reading: String, context: String = "") -> [Candidate] {
            var input = ComposingText()
            input.insertAtCursorPosition(reading, inputStyle: .direct)
            let options = optionsForZenzaiConversion(from: makeConvertRequestOptions(
                context: context, zenzaiEnabled: true, runtimeDirectoryURL: directory, emojiDictionaryURL: emoji,
                zenzaiWeightURL: model, profile: "", learningType: .nothing, experimentalKeyboardTypoCorrection: false
            ))
            return converter.requestCandidates(input, options: options).mainResults
        }
        let cases = [
            (UserDictionaryEntry(reading: "あおみの", word: "碧海乃"), "あおみの", "碧海乃"),
            (UserDictionaryEntry(reading: "ぜろこん", word: "ZeroConvert"), "ぜろこんをつかう", "ZeroConvertを使う"),
            (UserDictionaryEntry(reading: "らすと", word: "Rust"), "らすとでかく", "Rustで書く"),
            (UserDictionaryEntry(reading: "めあど", word: "test@example.com", pos: .shortcut), "めあど", "test@example.com"),
            (UserDictionaryEntry(reading: "かおもじ", word: "(^o^)", pos: .symbol), "かおもじ", "(^o^)"),
        ]
        let dictionary = UserDictionarySnapshot(entries: cases.map { $0.0 })
        converter.installPreparedDynamicUserDictionary(dictionary.conversion)
        for (entry, reading, expected) in cases {
            print("Prepared dictionary model probe: \(expected)")
            let options = convert(reading)
            #expect(options.contains { $0.text == expected && $0.data.contains { $0.word == entry.word } },
                    "\(expected): \(options.map(\.text))")
            #expect(convert(reading).contains { $0.text == expected })
        }
        let context = "ご飯を食べるのに"
        let options = convert("はし", context: context)
        let baseline = try #require(options.first?.text)
        #expect(baseline != "橋")
        #expect(options.contains { $0.text == "橋" })
        learningSelectionOverrides.record(output: "橋", for: "はし", explicit: true)
        let explicit = prioritizeLearningSelectionOverrides(options, ruby: "はし", leftContext: context) { $0 }
        #expect(explicit.first?.text == baseline)
        learningSelectionOverrides = LearningSelectionStore()
        learningSelectionOverrides.record(output: "橋", for: "はし", explicit: false)
        let implicit = prioritizeLearningSelectionOverrides(
            options, ruby: "はし", leftContext: context, candidate: { $0 }, synthesizeElement: { $0 }
        )
        #expect(implicit.first?.text == baseline)
        print("Model-first context probe:", baseline, explicit.first?.text ?? "", implicit.first?.text ?? "")
    }
}

@MainActor @Test func parallelModelFirstExecutionProtectsUserDictAndLearningAndFixesPercentHalfwidth() throws {
    let package = packageRootURL()
    let model = package.deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-q8_0.gguf")
    try #require(FileManager.default.fileExists(atPath: model.path))
    let dictionary = package.appending(path: "azooKey_dictionary_storage/Dictionary")
    let emoji = package.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
    let store = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
    let main = KanaKanjiConverter(dicdataStore: store)
    let supplement = KanaKanjiConverter(dicdataStore: store)

    do {
        var input = ComposingText()
        input.insertAtCursorPosition("５０％のかくりつ", inputStyle: .direct)
        let baseOptions = makeConvertRequestOptions(
            context: "コインを投げたときに表が出る数学的な期待値。",
            zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji,
            zenzaiWeightURL: model,
            profile: "",
            learningType: .nothing,
            experimentalKeyboardTypoCorrection: false
        )
        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: main,
            supplementConverter: supplement,
            inputData: input,
            zenzaiOptions: optionsForZenzaiConversion(from: baseOptions),
            supplementOptions: optionsForNormalNBestSupplement(from: baseOptions),
            emitLogs: false,
            operation: "test_model_first_percent",
            diagnosticDetails: ""
        )
        let merged = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: execution.zenzai.mainResults,
            normalNBestResults: execution.supplement.mainResults,
            hiragana: input.convertTarget
        )
        #expect(execution.usedDirectModelFirst)
        #expect(merged.first?.text == "50%の確率", "merged=\(merged.prefix(3).map(\.text))")
        #expect(!execution.supplement.mainResults.isEmpty)
        #expect(!execution.zenzai.firstClauseResults.isEmpty)
    }

    do {
        let userDict = UserDictionarySnapshot(entries: [
            UserDictionaryEntry(reading: "ぜろこん", word: "ZeroConvert", pos: .properNoun)
        ])
        main.installPreparedDynamicUserDictionary(userDict.conversion)
        supplement.installPreparedDynamicUserDictionary(userDict.conversion)
        var input = ComposingText()
        input.insertAtCursorPosition("ぜろこんをつかう", inputStyle: .direct)
        let baseOptions = makeConvertRequestOptions(
            context: "",
            zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji,
            zenzaiWeightURL: model,
            profile: "",
            learningType: .nothing,
            experimentalKeyboardTypoCorrection: false
        )
        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: main,
            supplementConverter: supplement,
            inputData: input,
            zenzaiOptions: optionsForZenzaiConversion(from: baseOptions),
            supplementOptions: optionsForNormalNBestSupplement(from: baseOptions),
            emitLogs: false,
            operation: "test_model_first_user_dict",
            diagnosticDetails: ""
        )
        let merged = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: execution.zenzai.mainResults,
            normalNBestResults: execution.supplement.mainResults,
            hiragana: input.convertTarget
        )
        #expect(!execution.usedDirectModelFirst)
        #expect(merged.contains { $0.text == "ZeroConvertを使う" && $0.data.contains { $0.word == "ZeroConvert" } },
                "merged=\(merged.prefix(5).map(\.text))")
        let emptyDict = UserDictionarySnapshot(entries: [])
        main.installPreparedDynamicUserDictionary(emptyDict.conversion)
        supplement.installPreparedDynamicUserDictionary(emptyDict.conversion)
    }

    try withDictionaryLearningState { directory, _ in
        let learningStore = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
        let learnMain = KanaKanjiConverter(dicdataStore: learningStore)
        let learnSupplement = KanaKanjiConverter(dicdataStore: learningStore)
        let learnedElement = DicdataElement(
            word: "BridgeX",
            ruby: "ハシ",
            cid: CIDData.一般名詞.cid,
            mid: MIDData.一般.mid,
            value: -2.0,
            adjust: 0,
            metadata: [.isLearned]
        )
        let learnedCandidate = Candidate(
            text: "BridgeX",
            value: -2.0,
            composingCount: .surfaceCount(2),
            lastMid: MIDData.一般.mid,
            data: [learnedElement]
        )
        learnMain.syncLearningConfig(learningType: .inputAndOutput, memoryDirectoryURL: directory)
        learnSupplement.syncLearningConfig(learningType: .inputAndOutput, memoryDirectoryURL: directory)
        learnMain.updateLearningData(learnedCandidate)
        var input = ComposingText()
        input.insertAtCursorPosition("はし", inputStyle: .direct)
        let baseOptions = makeConvertRequestOptions(
            context: "",
            zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji,
            zenzaiWeightURL: model,
            profile: "",
            learningType: .inputAndOutput,
            learningMemoryDirectoryURL: directory,
            experimentalKeyboardTypoCorrection: false
        )
        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: learnMain,
            supplementConverter: learnSupplement,
            inputData: input,
            zenzaiOptions: optionsForZenzaiConversion(from: baseOptions),
            supplementOptions: optionsForNormalNBestSupplement(from: baseOptions),
            emitLogs: false,
            operation: "test_model_first_learning",
            diagnosticDetails: ""
        )
        let merged = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: execution.zenzai.mainResults,
            normalNBestResults: execution.supplement.mainResults,
            hiragana: input.convertTarget
        )
        #expect(!execution.usedDirectModelFirst)
        #expect(merged.contains { $0.text == "BridgeX" }, "merged=\(merged.prefix(5).map(\.text))")
    }
}

@Test func normalizedLearningRubyPreservesKanaFastPathAndDecomposedMarks() {
    #expect(normalizedLearningRuby("きょうはいいてんき") == "キョウハイイテンキ")
    #expect(normalizedLearningRuby("カタカナ") == "カタカナ")
    #expect(normalizedLearningRuby("abc123") == "abc123")
    #expect(normalizedLearningRuby("か\u{3099}き\u{3099}は\u{309A}") == "ガギパ")
    #expect(normalizedLearningRuby("カ\u{3099}キ\u{3099}ハ\u{309A}") == "ガギパ")
}

@MainActor @Test func zenzaiRuntimeTuningConfigureResetAndCancelledProbeWork() {
    ZenzaiRuntimeTuning.configure(decodeThreads: 2, batchThreads: 2, directModelFirstOverride: true)
    defer { ZenzaiRuntimeTuning.reset() }
    let emptyURL = FileManager.default.temporaryDirectory
    let options = makeConvertRequestOptions(
        context: "",
        zenzaiEnabled: false,
        runtimeDirectoryURL: emptyURL,
        emojiDictionaryURL: emptyURL.appendingPathComponent("missing.txt"),
        zenzaiWeightURL: emptyURL.appendingPathComponent("missing.gguf"),
        profile: "",
        learningType: .nothing,
        learningMemoryDirectoryURL: emptyURL,
        experimentalKeyboardTypoCorrection: false
    )
    let cancelled = probeRuntimeTuningConfigs(
        baseOptions: options,
        configs: [RuntimeTuningProbeConfig(decodeThreads: 2, batchThreads: 2, directModelFirst: true)],
        shouldCancel: { true }
    )
    #expect(cancelled == nil)
    ZenzaiRuntimeTuning.reset()
}

@Test func mergeZenzaiMainResultsWithNormalNBestDeduplicatesAndFiltersAlternatives() {
    let reading = "はし"
    let top = testCandidate(word: "橋", ruby: "ハシ", composingCount: .inputCount(2))
    let duplicateTop = testCandidate(word: "橋", ruby: "ハシ", composingCount: .inputCount(2))
    let rawHiragana = testCandidate(word: "はし", ruby: "ハシ", composingCount: .inputCount(2))
    let rawKatakana = testCandidate(word: "ハシ", ruby: "ハシ", composingCount: .inputCount(2))
    let validAlt = testCandidate(word: "箸", ruby: "ハシ", composingCount: .inputCount(2))
    let normalDup = testCandidate(word: "箸", ruby: "ハシ", composingCount: .inputCount(2))
    let normalNew = testCandidate(word: "端", ruby: "ハシ", composingCount: .inputCount(2))
    let merged = mergeZenzaiMainResultsWithNormalNBest(
        zenzaiResults: [top, duplicateTop, rawHiragana, rawKatakana, validAlt],
        normalNBestResults: [normalDup, normalNew],
        hiragana: reading
    )
    #expect(merged.map(\.text) == ["橋", "箸", "端"])
}

@MainActor @Test func cpuRuntimeTuningSettingsDecodeAndWindowsEngineRuntimeResolution() throws {
    let fullJSON = Data("""
    {
        "decode_threads": 4,
        "batch_threads": 6,
        "direct_model_first": false,
        "model_id": "nano_v4_2_iq4_emb8",
        "tuning_version": 2
    }
    """.utf8)
    let fullDecoded = try JSONDecoder().decode(CpuRuntimeTuningSettings.self, from: fullJSON)
    #expect(fullDecoded.decode_threads == 4)
    #expect(fullDecoded.batch_threads == 6)
    #expect(!fullDecoded.direct_model_first)
    #expect(fullDecoded.model_id == "nano_v4_2_iq4_emb8")
    #expect(fullDecoded.tuning_version == 2)

    let omittedBatchJSON = Data("""
    {
        "decode_threads": 2,
        "direct_model_first": true,
        "model_id": "standard_v4_2"
    }
    """.utf8)
    let omittedBatchDecoded = try JSONDecoder().decode(CpuRuntimeTuningSettings.self, from: omittedBatchJSON)
    #expect(omittedBatchDecoded.decode_threads == 2)
    #expect(omittedBatchDecoded.batch_threads == nil)
    #expect(omittedBatchDecoded.direct_model_first)
    #expect(omittedBatchDecoded.tuning_version == nil)
    #expect(canonicalZenzaiModelId(omittedBatchDecoded.model_id) == "standard_v4_2_q8")
    #expect(canonicalZenzaiModelId(nil) == "standard_v4_2_iq4_emb8")
    #expect(canonicalZenzaiModelId("nano_v4_2_iq4_emb8") == "nano_v4_2_iq4_emb8")

    #if os(Windows)
    let savedConfig = config
    defer {
        config = savedConfig
        ZenzaiRuntimeTuning.reset()
        configureEngineRuntime(zenzaiEnabled: false)
    }

    config["backend"] = "cpu"
    config["model"] = "standard_v4_2_q8"
    config["cpuRuntimeTuning"] = CpuRuntimeTuningSettings(
        decode_threads: 4,
        batch_threads: 6,
        direct_model_first: true,
        model_id: "standard_v4_2",
        tuning_version: expectedWindowsCpuTuningVersion
    )
    configureEngineRuntime(zenzaiEnabled: true)
    #expect(lastAppliedWindowsCpuRuntimeTuning?.decodeThreads == 4)
    #expect(lastAppliedWindowsCpuRuntimeTuning?.batchThreads == 6)
    #expect(lastAppliedWindowsCpuRuntimeTuning?.directModelFirst == true)

    config["cpuRuntimeTuning"] = CpuRuntimeTuningSettings(
        decode_threads: 4,
        batch_threads: 6,
        direct_model_first: true,
        model_id: "standard_v4_2",
        tuning_version: nil
    )
    configureEngineRuntime(zenzaiEnabled: true)
    #expect(lastAppliedWindowsCpuRuntimeTuning == nil)

    config["cpuRuntimeTuning"] = CpuRuntimeTuningSettings(
        decode_threads: 4,
        batch_threads: 6,
        direct_model_first: true,
        model_id: "standard_v4_2",
        tuning_version: 1
    )
    configureEngineRuntime(zenzaiEnabled: true)
    #expect(lastAppliedWindowsCpuRuntimeTuning == nil)

    config["cpuRuntimeTuning"] = CpuRuntimeTuningSettings(
        decode_threads: 4,
        batch_threads: 6,
        direct_model_first: true,
        model_id: "standard_v4_2",
        tuning_version: expectedWindowsCpuTuningVersion
    )
    config["backend"] = "vulkan"
    configureEngineRuntime(zenzaiEnabled: true)
    #expect(lastAppliedWindowsCpuRuntimeTuning == nil)

    config["backend"] = "cpu"
    config["model"] = "nano_v4_2_iq4_emb8"
    configureEngineRuntime(zenzaiEnabled: true)
    #expect(lastAppliedWindowsCpuRuntimeTuning == nil)

    config["model"] = "standard_v4_2_q8"
    configureEngineRuntime(zenzaiEnabled: false)
    #expect(lastAppliedWindowsCpuRuntimeTuning == nil)
    #endif
}

@MainActor @Test func parallelModelFirstPiggybacksAndSkipsLongPredictionContinuation() throws {
    let package = packageRootURL()
    let model = package.deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "artifacts/models/zerocon/zerocon-nano-v4.2-q8_0.gguf")
    try #require(FileManager.default.fileExists(atPath: model.path))
    let dictionary = package.appending(path: "azooKey_dictionary_storage/Dictionary")
    let emoji = package.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt")
    let store = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
    let main = KanaKanjiConverter(dicdataStore: store)
    let supplement = KanaKanjiConverter(dicdataStore: store)

    ZenzaiRuntimeTuning.configure(decodeThreads: 2, batchThreads: 2, directModelFirstOverride: true)
    defer { ZenzaiRuntimeTuning.reset() }

    let continuation = ConversionContinuation(reading: "とうきょう", surface: "東京")

    do {
        var input = ComposingText()
        input.insertAtCursorPosition("きょう", inputStyle: .direct)
        var combined = ComposingText()
        combined.insertAtCursorPosition("とうきょう" + "きょう", inputStyle: .direct)

        let baseOptions = makeConvertRequestOptions(
            context: "",
            zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji,
            zenzaiWeightURL: model,
            profile: "",
            learningType: .nothing,
            experimentalKeyboardTypoCorrection: false
        )
        let zenzaiOptions = optionsForZenzaiConversion(from: baseOptions)
        let supplementOptions = optionsForNormalNBestSupplementWithPrediction(from: baseOptions)
        var predOptions = supplementOptions
        predOptions.zenzaiMode = .off
        predOptions.N_best = min(predOptions.N_best, 3)

        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: main,
            supplementConverter: supplement,
            inputData: input,
            zenzaiOptions: zenzaiOptions,
            supplementOptions: supplementOptions,
            predictionContinuation: ZenzaiPredictionContinuationRequest(
                inputData: combined,
                options: predOptions,
                prefixConstraintSurface: continuation.surface,
                cacheOnlyJapanesePrediction: false,
                skipWhenSufficientPredictions: false
            ),
            emitLogs: false,
            operation: "test_parallel_pred_cont",
            diagnosticDetails: ""
        )
        if execution.ranInParallel {
            #expect(execution.predictionContinuationResult != nil)
            #expect(!execution.skippedPredictionContinuation)
        }
    }

    do {
        var input = ComposingText()
        input.insertAtCursorPosition("あしたのよていをかくにんする", inputStyle: .direct)
        var combined = ComposingText()
        combined.insertAtCursorPosition("とうきょう" + "あしたのよていをかくにんする", inputStyle: .direct)

        let baseOptions = makeConvertRequestOptions(
            context: "",
            zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: emoji,
            zenzaiWeightURL: model,
            profile: "",
            learningType: .nothing,
            experimentalKeyboardTypoCorrection: false
        )
        let zenzaiOptions = optionsForZenzaiConversion(from: baseOptions)
        let supplementOptions = optionsForNormalNBestSupplementWithPrediction(from: baseOptions)
        var predOptions = supplementOptions
        predOptions.zenzaiMode = .off
        predOptions.N_best = min(predOptions.N_best, 3)

        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: main,
            supplementConverter: supplement,
            inputData: input,
            zenzaiOptions: zenzaiOptions,
            supplementOptions: supplementOptions,
            predictionContinuation: ZenzaiPredictionContinuationRequest(
                inputData: combined,
                options: predOptions,
                prefixConstraintSurface: continuation.surface,
                cacheOnlyJapanesePrediction: false,
                skipWhenSufficientPredictions: true
            ),
            emitLogs: false,
            operation: "test_skip_long_auto_pred_cont",
            diagnosticDetails: ""
        )
        if execution.ranInParallel && execution.supplement.predictionResults.count >= 3 {
            #expect(execution.skippedPredictionContinuation)
            #expect(execution.predictionContinuationResult == nil)
        }
    }
}

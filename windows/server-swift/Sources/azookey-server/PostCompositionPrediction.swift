import Foundation
import KanaKanjiConverterModule

private let maxPostCompositionBigramPreviousCount = 256
private let maxPostCompositionBigramNextCount = 6
private let maxRecentPostCompositionSourceCount = 64
private let maxPostCompositionTextPredictions = 10
private let maxPostCompositionEmojiPredictions = 2
let postCompositionBigramsFilename = "post-composition-bigrams.json"

struct PostCompositionBigramEntry: Codable, Equatable {
    var surface: String
    var ruby: String
    var rcid: Int
    var mid: Int
    var count: UInt32
    var lastUsedEpochMs: Int64

    init(surface: String, ruby: String, rcid: Int, mid: Int, count: UInt32 = 1, lastUsedEpochMs: Int64 = 0) {
        self.surface = surface
        self.ruby = normalizedLearningRuby(ruby)
        self.rcid = rcid
        self.mid = mid
        self.count = count
        self.lastUsedEpochMs = lastUsedEpochMs
    }

    private enum CodingKeys: String, CodingKey {
        case surface, ruby, rcid, mid, count, lastUsedEpochMs
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            surface: try values.decode(String.self, forKey: .surface),
            ruby: try values.decode(String.self, forKey: .ruby),
            rcid: try values.decode(Int.self, forKey: .rcid),
            mid: try values.decode(Int.self, forKey: .mid),
            count: min(max(try values.decodeIfPresent(UInt32.self, forKey: .count) ?? 1, 1), 1000),
            lastUsedEpochMs: try values.decodeIfPresent(Int64.self, forKey: .lastUsedEpochMs) ?? 0
        )
    }

    func score(nowEpochMs: Int64) -> Double {
        let ageDays = lastUsedEpochMs == 0
            ? 7 : max(0, (Double(nowEpochMs) - Double(lastUsedEpochMs)) / 86_400_000)
        return Double(count) / (1 + ageDays / 14)
    }

    var element: DicdataElement {
        DicdataElement(
            word: surface, ruby: ruby.isEmpty ? normalizedLearningRuby(surface) : ruby,
            cid: rcid > 0 ? rcid : CIDData.一般名詞.cid, mid: mid, value: -1
        )
    }
}

struct PostCompositionBigramStore: Codable, Equatable {
    private var entriesByPrevious: [String: [PostCompositionBigramEntry]] = [:]

    var count: Int { entriesByPrevious.values.reduce(0) { $0 + $1.count } }

    mutating func removeAll() { entriesByPrevious.removeAll() }

    func entries(for previousSurface: String, nowEpochMs: Int64 = learningEpochMilliseconds()) -> [PostCompositionBigramEntry] {
        (entriesByPrevious[previousSurface] ?? []).sorted {
            let lhs = $0.score(nowEpochMs: nowEpochMs)
            let rhs = $1.score(nowEpochMs: nowEpochMs)
            if lhs != rhs { return lhs > rhs }
            if $0.lastUsedEpochMs != $1.lastUsedEpochMs { return $0.lastUsedEpochMs > $1.lastUsedEpochMs }
            return $0.surface < $1.surface
        }
    }

    func chains(from previous: String, nowEpochMs: Int64 = learningEpochMilliseconds()) -> [[PostCompositionBigramEntry]] {
        entries(for: previous, nowEpochMs: nowEpochMs).prefix(2).compactMap { start in
            guard start.surface != previous, start.surface.count <= 40 else { return nil }
            var chain = [start]
            var visited: Set<String> = [previous, start.surface]
            var length = start.surface.count
            while chain.count < 4, let tail = chain.last,
                  let next = entries(for: tail.surface, nowEpochMs: nowEpochMs).first(where: {
                      !visited.contains($0.surface) && length + $0.surface.count <= 40
                  }) {
                chain.append(next)
                visited.insert(next.surface)
                length += next.surface.count
            }
            return chain.count > 1 ? chain : nil
        }
    }

    private func ageOrder(_ key: String) -> (Int64, String) {
        (entriesByPrevious[key]?.map(\.lastUsedEpochMs).max() ?? 0, key)
    }

    private static func edgeAgeOrder(_ entry: PostCompositionBigramEntry) -> (Int64, UInt32, String) {
        (entry.lastUsedEpochMs, entry.count, entry.surface)
    }

    @discardableResult
    mutating func record(
        previousSurface: String, entry: PostCompositionBigramEntry,
        nowEpochMs: Int64 = learningEpochMilliseconds()
    ) -> Bool {
        guard isValidLearningKey(previousSurface), isValidLearningKey(entry.surface),
              previousSurface.count <= 32, entry.surface.count <= 40 else { return false }
        var current = entriesByPrevious[previousSurface] ?? []
        var updated = entry
        if let index = current.firstIndex(where: { $0.surface == entry.surface }) {
            updated.count = min(current.remove(at: index).count + 1, 1000)
        }
        updated.lastUsedEpochMs = nowEpochMs
        current.append(updated)
        if current.count > maxPostCompositionBigramNextCount,
           let oldest = current.indices.min(by: { Self.edgeAgeOrder(current[$0]) < Self.edgeAgeOrder(current[$1]) }) {
            current.remove(at: oldest)
        }
        entriesByPrevious[previousSurface] = current
        if entriesByPrevious.count > maxPostCompositionBigramPreviousCount,
           let oldest = entriesByPrevious.keys.min(by: { ageOrder($0) < ageOrder($1) }) {
            entriesByPrevious.removeValue(forKey: oldest)
        }
        return true
    }

    mutating func forget(surface: String) -> Bool {
        guard !surface.isEmpty else { return false }
        var changed = entriesByPrevious.removeValue(forKey: surface) != nil
        for (previous, list) in entriesByPrevious {
            let filtered = list.filter { $0.surface != surface }
            if filtered.count != list.count {
                changed = true
                entriesByPrevious[previous] = filtered.isEmpty ? nil : filtered
            }
        }
        return changed
    }

    func previousSurfaces(preceding surface: String) -> [String] {
        entriesByPrevious.keys.filter {
            entriesByPrevious[$0]?.contains { $0.surface == surface } == true
        }.sorted { ageOrder($0) < ageOrder($1) }
    }

    private struct Snapshot: Codable {
        var version: Int?
        var entriesByPrevious: [String: [PostCompositionBigramEntry]]
    }

    init() {}

    init(from decoder: Decoder) throws {
        let snapshot = try Snapshot(from: decoder)
        guard snapshot.version == nil || snapshot.version == 1 || snapshot.version == 2 else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unsupported bigram version"))
        }
        for (key, entries) in snapshot.entriesByPrevious where isValidLearningKey(key) && key.count <= 32 {
            var seen: Set<String> = []
            let valid = entries.filter { isValidLearningKey($0.surface) && $0.surface.count <= 40 }
                .sorted { Self.edgeAgeOrder($0) > Self.edgeAgeOrder($1) }
                .filter { seen.insert($0.surface).inserted }
            if !valid.isEmpty {
                entriesByPrevious[key] = Array(valid.prefix(maxPostCompositionBigramNextCount))
            }
        }
        let newestKeys = entriesByPrevious.keys.sorted { ageOrder($0) > ageOrder($1) }
        for key in newestKeys.dropFirst(maxPostCompositionBigramPreviousCount) {
            entriesByPrevious.removeValue(forKey: key)
        }
    }

    func encode(to encoder: Encoder) throws {
        try Snapshot(version: 2, entriesByPrevious: entriesByPrevious).encode(to: encoder)
    }
}

private struct RecentPostCompositionSource {
    let text: String
    let ruby: String
    let candidate: Candidate
}

struct PublishedPostCompositionPrediction {
    let id: UInt64
    let text: String
    let reading: String
    let leftSideCandidate: Candidate
    let joinedCandidate: Candidate
    let enginePrediction: PostCompositionPredictionCandidate?
    let isTerminal: Bool
    let isEmoji: Bool
    let shouldLearn: Bool
}

// Serialized by the single-threaded FFI executor in Rust, EmbeddedConversionActor on Android, and @MainActor in tests.
nonisolated(unsafe) var postCompositionBigramStore = PostCompositionBigramStore()
nonisolated(unsafe) private var recentPostCompositionSources: [RecentPostCompositionSource] = []
nonisolated(unsafe) var lastCommittedPostCompositionCandidate: Candidate?
nonisolated(unsafe) private var publishedPostCompositionPredictions: [UInt64: PublishedPostCompositionPrediction] = [:]
nonisolated(unsafe) private var nextPostCompositionPredictionId: UInt64 = 1
nonisolated(unsafe) var postCompositionCancellationCheck: () -> Bool = { false }

private let maxPostCompositionContextClauseLength = 32
private let postCompositionTrailingAuxiliaries: Set<String> = [
    "た", "だ", "です", "ます", "ました", "ません", "ない", "なかった",
    "たい", "たいです", "て", "で", "られる", "れる", "させる", "せる",
    "そう", "よう", "らしい", "みたい",
]
private let postCompositionElongationStarters: Set<Character> = [
    "ー", "〜", "～", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ", "っ", "ゃ", "ゅ", "ょ", "ゎ",
    "ァ", "ィ", "ゥ", "ェ", "ォ", "ッ", "ャ", "ュ", "ョ", "ヮ",
]
private let postCompositionZeroHintStopWords: Set<String> = [
    "なかれ", "なき", "なし", "れろ", "いろ", "しろ", "しまえ", "行え", "行い",
    "ござい", "ませ", "まし", "あり", "なり", "でき", "き", "おり", "ねえ", "あれ",
    "さ", "多", "含み", "立ち", "切り", "名", "数", "目", "見", "出来", "遊び", "強", "州", "村",
]
private let postCompositionInflectionSuffixes: Set<String> = [
    "て", "た", "てる", "ている", "ていた", "てきた", "ていく", "てみる",
    "たら", "たり", "ない", "なく", "なかった", "ます", "ました", "ません",
    "です", "でした", "だ", "ね", "よ", "る", "れる", "られる", "し", "きて", "きた",
]

private func isHiraganaCharacter(_ character: Character) -> Bool {
    character.unicodeScalars.allSatisfy { (0x3041 ... 0x3096).contains($0.value) || $0.value == 0x30FC }
}

private func isKanaCharacter(_ character: Character) -> Bool {
    character.unicodeScalars.allSatisfy {
        (0x3041 ... 0x3096).contains($0.value)
            || (0x30A1 ... 0x30F6).contains($0.value)
            || $0.value == 0x30FC
    }
}

private func effectivePostCompositionContextClause(committedText: String, context: String) -> String {
    guard !context.isEmpty,
          context.count > committedText.count,
          context.hasSuffix(committedText) else {
        return committedText
    }
    let normalized = context
        .replacingOccurrences(of: "\r\n", with: "\n")
        .replacingOccurrences(of: "\r", with: "\n")
    var clauseStart = normalized.startIndex
    for idx in normalized.indices {
        if isPostCompositionTerminalCharacter(normalized[idx]) {
            clauseStart = normalized.index(after: idx)
        }
    }
    let rawClause = String(normalized[clauseStart...]).trimmingCharacters(in: .whitespaces)
    guard !rawClause.isEmpty,
          rawClause.count > committedText.count,
          rawClause.hasSuffix(committedText) else {
        return committedText
    }
    if rawClause.count <= maxPostCompositionContextClauseLength {
        return rawClause
    }
    return committedText
}

private func isFunctionWordTailElement(_ element: DicdataElement) -> Bool {
    if CIDData.isJoshi(cid: element.rcid) || CIDData.isJoshi(cid: element.lcid) {
        return true
    }
    return postCompositionTrailingAuxiliaries.contains(element.word)
}

private func shouldAcceptEnginePostCompositionPrediction(
    _ pred: PostCompositionPredictionCandidate,
    effectiveLeftCandidate: Candidate
) -> Bool {
    guard isUsablePostCompositionPredictionText(pred.text),
          !pred.text.contains("エモジ"),
          !pred.text.contains("nam") else {
        return false
    }
    switch pred.type {
    case .replacement(let targetData, _):
        guard let lastTarget = targetData.last else {
            return false
        }
        // Reject LOUDS prefix replacements rooted only in a particle/auxiliary or trailing 1-char kana,
        // because they ignore the preceding phrase (e.g. 東京に -> お/ご/自分の, 食べた -> い/く).
        if isFunctionWordTailElement(lastTarget) {
            return false
        }
        if effectiveLeftCandidate.data.count > targetData.count,
           lastTarget.word.count == 1,
           lastTarget.word.allSatisfy(isKanaCharacter) {
            return false
        }
        if let firstChar = pred.text.first,
           postCompositionElongationStarters.contains(firstChar) {
            return false
        }
        if pred.text.count == 1,
           let only = pred.text.first,
           isHiraganaCharacter(only) {
            return false
        }
        if pred.text.count > 6 {
            return false
        }
        return true

    case .additional(let addData):
        if addData.count == 1 {
            let only = addData[0]
            if postCompositionZeroHintStopWords.contains(only.word) {
                return false
            }
            if only.rcid == CIDData.一般名詞.cid && only.value() <= -4.2 {
                return false
            }
        }
        return true
    }
}

private func resolveContextualLeftSideCandidate(
    leftSideCandidate: Candidate,
    contextualLeftText: String
) -> Candidate {
    guard contextualLeftText != leftSideCandidate.text,
          contextualLeftText.hasSuffix(leftSideCandidate.text),
          contextualLeftText.count <= maxConversionContinuationSurfaceCount else {
        return leftSideCandidate
    }
    let prefixSurface = String(contextualLeftText.dropLast(leftSideCandidate.text.count))
    guard !prefixSurface.isEmpty else {
        return leftSideCandidate
    }

    let prefixHiragana: String
    if let recent = recentPostCompositionSources.last(where: { $0.text == prefixSurface }) {
        prefixHiragana = katakanaToHiragana(recent.ruby)
    } else if let inferred = reconversionDictionary.inferReadings(for: prefixSurface).first {
        prefixHiragana = katakanaToHiragana(inferred)
    } else if isKanaLikeConversionContinuationReading(prefixSurface) {
        prefixHiragana = katakanaToHiragana(prefixSurface)
    } else {
        prefixHiragana = ""
    }

    let leftHiragana = katakanaToHiragana(learningCandidateRuby(leftSideCandidate))
    let combinedHiragana = prefixHiragana + leftHiragana
    if !prefixHiragana.isEmpty,
       !leftHiragana.isEmpty,
       combinedHiragana.count <= maxConversionContinuationReadingCount,
       isKanaLikeConversionContinuationReading(combinedHiragana) {
        var input = ComposingText()
        input.insertAtCursorPosition(combinedHiragana, inputStyle: .direct)
        let resolveOptions = makePostCompositionConverterOptions(predictiveEnabled: false, textReplacer: .empty)
        let resolved = normalNBestSupplementConverter.requestCandidates(
            input,
            options: resolveOptions,
            sessionID: "post-composition-context-resolve",
            cacheOnlyJapanesePrediction: false,
            prefixConstraintSurface: contextualLeftText
        )
        if let matched = resolved.mainResults.first(where: { $0.text == contextualLeftText })
            ?? resolved.firstClauseResults.first(where: { $0.text == contextualLeftText }) {
            return matched
        }
    }

    let prefixRuby = !prefixHiragana.isEmpty
        ? normalizedLearningRuby(prefixHiragana)
        : normalizedLearningRuby(prefixSurface)
    let prefixElement = DicdataElement(
        word: prefixSurface,
        ruby: prefixRuby,
        cid: CIDData.一般名詞.cid,
        mid: MIDData.一般.mid,
        value: -1
    )
    let mergedData = [prefixElement] + leftSideCandidate.data
    let mergedRubyCount = mergedData.reduce(0) { $0 + $1.ruby.count }
    return Candidate(
        text: contextualLeftText,
        value: leftSideCandidate.value,
        composingCount: .surfaceCount(max(1, mergedRubyCount)),
        lastMid: leftSideCandidate.lastMid,
        data: mergedData,
        isLearningTarget: leftSideCandidate.isLearningTarget
    )
}

private func isHiraganaOnlyText(_ text: String) -> Bool {
    !text.isEmpty && text.allSatisfy(isHiraganaCharacter)
}

private func isKatakanaOnlyText(_ text: String) -> Bool {
    !text.isEmpty && text.unicodeScalars.allSatisfy {
        (0x30A1 ... 0x30F6).contains($0.value) || $0.value == 0x30FC
    }
}

private let postCompositionParticleOrAdverbRubies: Set<String> = [
    "ハ", "ガ", "ヲ", "ニ", "デ", "ト", "モ", "ヘ", "ノ", "カ", "ヨ", "ネ",
    "カラ", "マデ", "ケド", "ダヨ", "キテ", "デス", "キョウ", "キュウ", "トキ", "コト",
]

private func shouldAttachContextualMorpheme(
    accumulated: [DicdataElement],
    next: DicdataElement
) -> Bool {
    guard let prev = accumulated.last else {
        return false
    }
    let currentLength = accumulated.reduce(0) { $0 + $1.word.count }
    guard currentLength + next.word.count <= 6 else {
        return false
    }
    if accumulated.count >= 2 && CIDData.isJoshi(cid: prev.rcid) {
        // Attach only tight auxiliary chains after `て/で` (e.g. 入っ+て+いる, 帰っ+て+きた), never after `に/から`.
        return (prev.word == "て" || prev.word == "で")
            && ["いる", "てる", "きた", "きて", "いく", "みる", "しまう"].contains(next.word)
    }
    if accumulated.count == 1 && (prev.word == "です" || prev.word == "ます") {
        return ["ね", "よ", "が", "か"].contains(next.word)
    }
    if accumulated.count == 1 && ["一緒", "本当", "すぐ", "静か", "朝", "夜"].contains(prev.word) {
        return ["に", "から"].contains(next.word)
    }
    if prev.word.hasSuffix("っ") {
        return postCompositionInflectionSuffixes.contains(next.word)
    }
    if postCompositionInflectionSuffixes.contains(next.word) {
        return !CIDData.isJoshi(cid: prev.rcid)
    }
    return false
}

private func sliceContextualNextWordElements(
    from elements: [DicdataElement],
    leftText: String
) -> [DicdataElement]? {
    guard let first = elements.first,
          isUsablePostCompositionPredictionText(first.word),
          !first.word.contains("エモジ"),
          !first.word.contains("nam") else {
        return nil
    }
    var sliced: [DicdataElement] = [first]
    for idx in 1 ..< min(3, elements.count) {
        let next = elements[idx]
        guard isUsablePostCompositionPredictionText(next.word),
              !next.word.contains("エモジ"),
              !next.word.contains("nam"),
              shouldAttachContextualMorpheme(accumulated: sliced, next: next) else {
            break
        }
        sliced.append(next)
    }
    if sliced.count >= 2 && ["し", "お"].contains(sliced.last?.word) {
        sliced.removeLast()
    }
    guard let last = sliced.last,
          !last.word.hasSuffix("っ") else {
        return nil
    }
    var surface = sliced.map(\.word).joined()
    if surface.hasSuffix("ってい") || surface.hasSuffix("んでい") || surface.hasSuffix("ってき") || surface.hasSuffix("んでき") {
        let trimmed = String(surface.dropLast())
        if let lastElem = sliced.last {
            if lastElem.word.count <= 1 && sliced.count >= 2 {
                sliced.removeLast()
                surface = trimmed
            } else if lastElem.word.count > 1 {
                sliced[sliced.count - 1] = DicdataElement(
                    word: String(lastElem.word.dropLast()),
                    ruby: lastElem.ruby.count > 1 ? String(lastElem.ruby.dropLast()) : lastElem.ruby,
                    cid: lastElem.rcid,
                    mid: lastElem.mid,
                    value: lastElem.value()
                )
                surface = trimmed
            }
        }
    }
    guard !surface.isEmpty,
          surface.count <= 6,
          surface != leftText,
          !(surface.count >= 2 && leftText.hasSuffix(surface)),
          !surface.hasPrefix("は言"),
          !surface.hasSuffix("はお") else {
        return nil
    }
    // Reject spurious ASCII/digit substitutions and identity katakana fallbacks on kana continuations.
    if surface.unicodeScalars.contains(where: { $0.isASCII && (CharacterSet.alphanumerics.contains($0)) }) {
        return nil
    }
    if sliced.count == 1 {
        let only = sliced[0]
        if isKatakanaOnlyText(only.word) && (only.word == only.ruby || only.word.count <= 4) && only.value() <= -13 {
            return nil
        }
        if postCompositionParticleOrAdverbRubies.contains(only.ruby)
            && !isHiraganaOnlyText(only.word)
            && only.word != "今日"
            && only.word != "時"
            && only.word != "来て"
            && only.word.count <= 2 {
            return nil
        }
    }
    if surface.hasPrefix("事") && sliced[0].ruby.hasPrefix("コト") {
        return nil
    }
    if surface.count == 1,
       let ch = surface.first,
       isKanaCharacter(ch),
       !["は", "が", "を", "に", "で", "と", "も", "の", "へ", "か", "や", "よ", "ね"].contains(surface) {
        return nil
    }
    return sliced
}

private func generateContextualZeroConPredictions(
    contextualLeftText: String,
    effectiveLeftCandidate: Candidate
) -> [PostCompositionPredictionCandidate] {
    guard currentRuntimeZenzaiEnabled(),
          !postCompositionCancellationCheck() else {
        return []
    }
    let modelURL = converterModelURL()
    guard FileManager.default.fileExists(atPath: modelURL.path) else {
        return []
    }
    let zenzOptions = getOptions(context: contextualLeftText, zenzaiEnabled: true)
    let rawReadings = converter.requestContextualNextWordReadings(
        leftSideContext: contextualLeftText,
        options: zenzOptions,
        maxBranches: 4,
        maxTokensPerBranch: 4,
        maxDurationSeconds: 0.06,
        shouldCancel: postCompositionCancellationCheck
    )
    guard !rawReadings.isEmpty,
          !postCompositionCancellationCheck() else {
        return []
    }

    let dictOptions = ConvertRequestOptions(
        N_best: 3,
        requireJapanesePrediction: .disabled,
        requireEnglishPrediction: .disabled,
        keyboardLanguage: .ja_JP,
        learningType: .nothing,
        memoryDirectoryURL: currentLearningMemoryDirectoryURL ?? converterRuntimeDirectoryURL(),
        sharedContainerURL: converterRuntimeDirectoryURL(),
        textReplacer: .empty,
        specialCandidateProviders: nil,
        zenzaiMode: .off,
        experimentalKeyboardTypoCorrection: false,
        metadata: nil
    )
    let formerRcid = effectiveLeftCandidate.data.last?.rcid ?? CIDData.一般名詞.cid
    var candidates: [PostCompositionPredictionCandidate] = []
    var seenTexts: Set<String> = [contextualLeftText, effectiveLeftCandidate.text]

    for readingCandidate in rawReadings {
        if postCompositionCancellationCheck() {
            break
        }
        if ["、", "。", "！", "？"].contains(readingCandidate.reading) {
            guard seenTexts.insert(readingCandidate.reading).inserted else {
                continue
            }
            let element = DicdataElement(
                word: readingCandidate.reading,
                ruby: readingCandidate.reading,
                cid: CIDData.記号.cid,
                mid: MIDData.一般.mid,
                value: PValue(readingCandidate.score)
            )
            candidates.append(
                PostCompositionPredictionCandidate(
                    text: readingCandidate.reading,
                    value: PValue(readingCandidate.score),
                    type: .additional(data: [element])
                )
            )
            continue
        }

        let hiragana = katakanaToHiragana(readingCandidate.reading)
        guard !hiragana.isEmpty,
              isKanaLikeConversionContinuationReading(hiragana) else {
            continue
        }
        var input = ComposingText()
        input.insertAtCursorPosition(hiragana, inputStyle: .direct)
        let converted = normalNBestSupplementConverter.requestCandidates(
            input,
            options: dictOptions,
            sessionID: "post-composition-zencon"
        )
        let pool = Array((converted.mainResults.isEmpty ? converted.firstClauseResults : converted.mainResults).prefix(3))
        var bestSlice: [DicdataElement]?
        var bestScore: Double = -.infinity

        for cand in pool {
            guard let sliced = sliceContextualNextWordElements(from: cand.data, leftText: contextualLeftText),
                  let first = sliced.first else {
                continue
            }
            if CIDData.isJoshi(cid: formerRcid) && sliced.map(\.word).joined() == "はい" {
                continue
            }
            let ccScore = normalNBestSupplementConverter.postCompositionConnectionScore(
                formerRcid: formerRcid,
                latterLcid: first.lcid
            )
            guard ccScore > -18 else {
                continue
            }
            let combined = Double(cand.value) + Double(ccScore) * 0.4
            if combined > bestScore {
                bestScore = combined
                bestSlice = sliced
            }
        }

        guard let sliced = bestSlice else {
            continue
        }
        let text = sliced.map(\.word).joined()
        guard seenTexts.insert(text).inserted else {
            continue
        }
        candidates.append(
            PostCompositionPredictionCandidate(
                text: text,
                value: PValue(readingCandidate.score),
                type: .additional(data: sliced)
            )
        )
    }

    return candidates
}

private func isPostCompositionTerminalCharacter(_ character: Character) -> Bool {
    switch character {
    case "。", ".", "．", "！", "!", "？", "?", "\n", "\r":
        return true
    default:
        return false
    }
}

func isPostCompositionTerminal(_ text: String) -> Bool {
    guard let last = text.last else {
        return true
    }
    return isPostCompositionTerminalCharacter(last)
}

private func isPostCompositionStopForBigram(_ text: String) -> Bool {
    guard !text.isEmpty,
          let first = text.first else {
        return true
    }
    if text.count == 1 && isAutomaticConversionContinuationStopCharacter(first) {
        return true
    }
    return isPostCompositionTerminal(text) || text.allSatisfy { $0.isWhitespace }
}

private func isUsablePostCompositionPredictionText(_ text: String) -> Bool {
    guard !text.isEmpty,
          !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
        return false
    }
    return !text.unicodeScalars.contains { scalar in
        CharacterSet.controlCharacters.contains(scalar)
            || (0xEE00 ... 0xEE0F).contains(scalar.value)
    }
}

private func postCompositionBigramsURL() -> URL? {
    currentLearningMemoryDirectoryURL?.appendingPathComponent(postCompositionBigramsFilename, isDirectory: false)
}

func loadPostCompositionBigrams() {
    guard !privateInputActive, !postCompositionBigramsDirty else { return }
    guard currentLearningType != .nothing,
          let fileURL = postCompositionBigramsURL() else {
        postCompositionBigramStore.removeAll()
        return
    }
    guard FileManager.default.fileExists(atPath: fileURL.path) else {
        postCompositionBigramStore.removeAll()
        return
    }
    do {
        let data = try Data(contentsOf: fileURL)
        postCompositionBigramStore = try JSONDecoder().decode(PostCompositionBigramStore.self, from: data)
    } catch {
        postCompositionBigramStore.removeAll()
        serverLog("WARN", "LoadPostCompositionBigrams: failed path=\(fileURL.path) error=\(error)")
    }
}

nonisolated(unsafe) private var postCompositionBigramsDirty = false

@discardableResult
func savePostCompositionBigrams() -> Bool {
    guard postCompositionBigramsDirty else { return true }
    guard !privateInputActive, currentLearningType != .nothing,
          let fileURL = postCompositionBigramsURL() else { return false }
    do {
        ensureLearningMemoryDirectoryIfNeeded()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(postCompositionBigramStore)
        try data.write(to: fileURL, options: .atomic)
        postCompositionBigramsDirty = false
        return true
    } catch {
        serverLog("WARN", "SavePostCompositionBigrams: failed path=\(fileURL.path) error=\(error)")
        return false
    }
}

func clearPublishedPostCompositionPredictions() {
    publishedPostCompositionPredictions.removeAll(keepingCapacity: false)
}

func clearActivePostCompositionState() {
    clearPublishedPostCompositionPredictions()
    lastCommittedPostCompositionCandidate = nil
}

func clearPostCompositionPredictionState() {
    clearActivePostCompositionState()
    recentPostCompositionSources.removeAll(keepingCapacity: false)
}

func resetPostCompositionLearningMemory() {
    postCompositionBigramsDirty = false
    postCompositionBigramStore.removeAll()
    clearPostCompositionPredictionState()
}

func postCompositionJoinedForgetElements(for candidate: Candidate, output: String) -> [DicdataElement] {
    guard !privateInputActive, currentLearningType != .nothing, !output.isEmpty else {
        return []
    }
    let baseRuby = learningCandidateRuby(candidate)
    let rcid = candidate.data.last?.rcid ?? CIDData.一般名詞.cid
    let mid = candidate.lastMid
    var seenJoinedWords: Set<String> = [output]
    var elements: [DicdataElement] = []

    func appendJoined(previousSurface: String) {
        guard !previousSurface.isEmpty else {
            return
        }
        let joinedWord = previousSurface + output
        guard seenJoinedWords.insert(joinedWord).inserted else {
            return
        }
        let previousRuby: String
        if let cached = recentPostCompositionSources.last(where: { $0.text == previousSurface }) {
            previousRuby = cached.ruby
        } else if let inferred = reconversionDictionary.inferReadings(for: previousSurface).first {
            previousRuby = normalizedLearningRuby(inferred)
        } else {
            previousRuby = normalizedLearningRuby(previousSurface)
        }
        elements.append(
            DicdataElement(
                word: joinedWord,
                ruby: previousRuby + baseRuby,
                lcid: CIDData.一般名詞.cid,
                rcid: rcid,
                mid: mid,
                value: candidate.value
            )
        )
    }

    for previousSurface in postCompositionBigramStore.previousSurfaces(preceding: output) {
        appendJoined(previousSurface: previousSurface)
    }
    if let continuation = lastCommittedContinuation,
       continuation.surface.count > output.count,
       continuation.surface.hasSuffix(output) {
        let prefixSurface = String(continuation.surface.dropLast(output.count))
        appendJoined(previousSurface: prefixSurface)
    }
    return elements
}

func forgetPostCompositionLearning(surface: String) {
    guard !privateInputActive, currentLearningType != .nothing else { return }
    clearActivePostCompositionState()
    recentPostCompositionSources.removeAll { $0.text == surface }
    if postCompositionBigramStore.forget(surface: surface) {
        postCompositionBigramsDirty = true
        _ = savePostCompositionBigrams()
    }
}

func recordRecentPublishedPostCompositionCandidates(_ candidates: [Candidate]) {
    guard !privateInputActive, currentLearningType != .nothing else { return }
    for candidate in candidates.prefix(24) {
        guard !candidate.text.isEmpty else {
            continue
        }
        let ruby = learningCandidateRuby(candidate)
        recentPostCompositionSources.removeAll { $0.text == candidate.text && $0.ruby == ruby }
        recentPostCompositionSources.append(
            RecentPostCompositionSource(text: candidate.text, ruby: ruby, candidate: candidate)
        )
    }
    if recentPostCompositionSources.count > maxRecentPostCompositionSourceCount {
        recentPostCompositionSources.removeFirst(
            recentPostCompositionSources.count - maxRecentPostCompositionSourceCount
        )
    }
}

func postCompositionContextLookupKeys(_ context: String) -> [String] {
    guard !context.isEmpty else { return [] }
    let clause = String(context.split(omittingEmptySubsequences: false, whereSeparator: isPostCompositionTerminalCharacter).last ?? "")
        .trimmingCharacters(in: .whitespaces)
    if clause != context && !clause.isEmpty && clause.count <= maxPostCompositionContextClauseLength {
        return [clause, context]
    }
    return [context]
}

private func recordContextualPostCompositionBigram(
    previousSurface: String,
    contextBeforeCommit: String,
    entry: PostCompositionBigramEntry
) -> Bool {
    let contextualPrevious = effectivePostCompositionContextClause(
        committedText: previousSurface,
        context: contextBeforeCommit
    )
    var recorded = false
    if contextualPrevious != previousSurface {
        recorded = postCompositionBigramStore.record(previousSurface: contextualPrevious, entry: entry)
    }
    if previousSurface.count >= 2 || contextualPrevious == previousSurface {
        recorded = postCompositionBigramStore.record(previousSurface: previousSurface, entry: entry) || recorded
    }
    return recorded
}

func extractClauseChunks(from candidate: Candidate) -> [(surface: String, ruby: String, rcid: Int, mid: Int)] {
    typealias Chunk = (surface: String, ruby: String, rcid: Int, mid: Int)
    let punctuation: Set<Character> = Set("、。！？,.!?")
    let endMarkers: Set<Character> = Set("はがをにへとでのもねよか、。！？")
    let particles = ["から", "まで", "より", "だけ", "ので", "のに", "では", "には", "とは", "でも",
                     "は", "が", "を", "に", "へ", "と", "で", "の", "も", "ね", "よ", "か"]
    var chunks: [Chunk] = []
    var current: Chunk?

    func append(_ chunk: Chunk) {
        let isPunctuation = chunk.surface.allSatisfy(punctuation.contains)
        let shortTail = chunk.surface.count <= 3 &&
            (chunk.surface.allSatisfy(isHiraganaCharacter) || isPunctuation)
        if let prior = current, shortTail,
           (prior.surface.last.map { !endMarkers.contains($0) } == true || isPunctuation) {
            current = (prior.surface + chunk.surface, prior.ruby + chunk.ruby, chunk.rcid, chunk.mid)
        } else {
            if let current { chunks.append(current) }
            current = chunk
        }
    }

    for element in candidate.data where isValidLearningKey(element.word) {
        let ruby = normalizedLearningRuby(element.ruby)
        // AzooKey also stores fused dictionary elements such as 「は天気」.
        // Split a recognized leading particle only when its left CID and reading agree.
        if let prior = current, prior.surface.last.map({ !endMarkers.contains($0) }) == true,
           CIDData.isJoshi(cid: element.lcid),
           let particle = particles.first(where: {
               element.word.count > $0.count && element.word.hasPrefix($0) && ruby.hasPrefix(normalizedLearningRuby($0))
           }) {
            let particleRuby = normalizedLearningRuby(particle)
            let completed = (prior.surface + particle, prior.ruby + particleRuby, element.lcid, prior.mid)
            chunks.append(completed)
            current = (String(element.word.dropFirst(particle.count)), String(ruby.dropFirst(particleRuby.count)), element.rcid, element.mid)
        } else {
            append((element.word, ruby, element.rcid, element.mid))
        }
    }
    if let current { chunks.append(current) }
    return chunks
}

func recordPostCompositionLearningCommit(
    _ candidate: Candidate,
    contextBeforeCommit: String,
    persist: Bool = true
) {
    guard !privateInputActive, currentLearningType == .inputAndOutput else { return }
    let previous = lastCommittedPostCompositionCandidate
    lastCommittedPostCompositionCandidate = candidate
    guard candidate.isLearningTarget else { return }
    let chunks = extractClauseChunks(from: candidate)
    guard let first = chunks.first else { return }
    func entry(_ chunk: (surface: String, ruby: String, rcid: Int, mid: Int)) -> PostCompositionBigramEntry {
        PostCompositionBigramEntry(surface: chunk.surface, ruby: chunk.ruby, rcid: chunk.rcid, mid: chunk.mid)
    }
    var recorded = false
    if let previous, previous.isLearningTarget, !contextBeforeCommit.isEmpty,
       contextBeforeCommit.hasSuffix(previous.text),
       !isPostCompositionStopForBigram(previous.text), !isPostCompositionStopForBigram(first.surface) {
        recorded = recordContextualPostCompositionBigram(
            previousSurface: previous.text, contextBeforeCommit: contextBeforeCommit, entry: entry(first)
        )
        if let last = extractClauseChunks(from: previous).last, last.surface != previous.text,
           !isPostCompositionStopForBigram(last.surface) {
            recorded = postCompositionBigramStore.record(previousSurface: last.surface, entry: entry(first)) || recorded
        }
    }
    for (left, right) in zip(chunks, chunks.dropFirst())
    where !isPostCompositionStopForBigram(left.surface) && !isPostCompositionStopForBigram(right.surface) {
        recorded = postCompositionBigramStore.record(previousSurface: left.surface, entry: entry(right)) || recorded
    }
    if recorded {
        postCompositionBigramsDirty = true
        if persist { _ = savePostCompositionBigrams() }
    }
}

private func makePostCompositionConverterOptions(
    predictiveEnabled: Bool,
    textReplacer: TextReplacer
) -> ConvertRequestOptions {
    return ConvertRequestOptions(
        N_best: 10,
        requireJapanesePrediction: predictiveEnabled ? .manualMix : .disabled,
        requireEnglishPrediction: .disabled,
        keyboardLanguage: .ja_JP,
        learningType: currentLearningType,
        memoryDirectoryURL: currentLearningMemoryDirectoryURL ?? converterRuntimeDirectoryURL(),
        sharedContainerURL: converterRuntimeDirectoryURL(),
        textReplacer: textReplacer,
        specialCandidateProviders: nil,
        zenzaiMode: .off,
        metadata: nil
    )
}

func resolvePostCompositionLeftSideCandidate(
    candidateId: UInt64,
    committedText: String,
    committedReading: String
) -> Candidate? {
    guard !committedText.isEmpty,
          !isPostCompositionTerminal(committedText),
          committedText.count <= maxConversionContinuationSurfaceCount else {
        return nil
    }
    if candidateId != 0,
       let cached = learningCandidate(candidateId),
       cached.text == committedText {
        return cached
    }
    let normalizedRuby = normalizedLearningRuby(committedReading)
    if let last = lastCommittedPostCompositionCandidate,
       last.text == committedText,
       normalizedRuby.isEmpty || learningCandidateRuby(last) == normalizedRuby {
        return last
    }
    if !normalizedRuby.isEmpty,
       let exact = recentPostCompositionSources.last(where: { $0.text == committedText && $0.ruby == normalizedRuby }) {
        return exact.candidate
    }
    if let recent = recentPostCompositionSources.last(where: { $0.text == committedText }) {
        return recent.candidate
    }

    let candidateHiragana = !committedReading.isEmpty
        ? katakanaToHiragana(committedReading)
        : (reconversionDictionary.inferReadings(for: committedText).first.map(katakanaToHiragana)
            ?? (isKanaLikeConversionContinuationReading(committedText) ? committedText : ""))
    if !candidateHiragana.isEmpty,
       candidateHiragana.count <= maxConversionContinuationReadingCount,
       isKanaLikeConversionContinuationReading(candidateHiragana) {
        var input = ComposingText()
        input.insertAtCursorPosition(candidateHiragana, inputStyle: .direct)
        let emojiURL = execURL
            .appendingPathComponent("EmojiDictionary")
            .appendingPathComponent("emoji_all_E15.1.txt")
        let resolveOptions = ConvertRequestOptions(
            N_best: 10,
            requireJapanesePrediction: .disabled,
            requireEnglishPrediction: .disabled,
            keyboardLanguage: .ja_JP,
            learningType: currentLearningType == .nothing ? .nothing : .onlyOutput,
            memoryDirectoryURL: currentLearningMemoryDirectoryURL ?? converterRuntimeDirectoryURL(),
            sharedContainerURL: converterRuntimeDirectoryURL(),
            textReplacer: .init { emojiURL },
            specialCandidateProviders: nil,
            zenzaiMode: .off,
            metadata: nil
        )
        let resolved = normalNBestSupplementConverter.requestCandidates(
            input,
            options: resolveOptions,
            sessionID: "post-composition-resolve",
            cacheOnlyJapanesePrediction: false,
            prefixConstraintSurface: committedText
        )
        if let matched = resolved.mainResults.first(where: { $0.text == committedText })
            ?? resolved.firstClauseResults.first(where: { $0.text == committedText }) {
            return matched
        }
    }

    guard committedText.contains(where: { $0.isLetter || $0.isNumber }) else {
        return nil
    }
    let ruby = !normalizedRuby.isEmpty
        ? normalizedRuby
        : normalizedLearningRuby(candidateHiragana.isEmpty ? committedText : candidateHiragana)
    return Candidate(
        text: committedText,
        value: -1,
        composingCount: .surfaceCount(ruby.isEmpty ? committedText.count : ruby.count),
        lastMid: MIDData.一般.mid,
        data: [
            DicdataElement(
                word: committedText,
                ruby: ruby,
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: -1
            )
        ],
        isLearningTarget: !ruby.isEmpty
    )
}

private func allocatePostCompositionPredictionId() -> UInt64 {
    let allocated = nextPostCompositionPredictionId == 0 ? 1 : nextPostCompositionPredictionId
    nextPostCompositionPredictionId = allocated &+ 1
    if nextPostCompositionPredictionId == 0 {
        nextPostCompositionPredictionId = 1
    }
    return allocated
}

func generatePostCompositionPredictions(
    leftSideCandidate: Candidate,
    context: String
) -> [PublishedPostCompositionPrediction] {
    clearPublishedPostCompositionPredictions()
    guard isPostCompositionPredictionEnabled(),
          !leftSideCandidate.text.isEmpty,
          !isPostCompositionTerminal(leftSideCandidate.text) else {
        return []
    }
    if !context.isEmpty,
       !context.hasSuffix(leftSideCandidate.text),
       !leftSideCandidate.text.hasSuffix(context) {
        return []
    }

    normalNBestSupplementConverter.syncLearningConfig(
        learningType: currentLearningType,
        memoryDirectoryURL: currentLearningMemoryDirectoryURL
    )
    if !privateInputActive && currentLearningType != .nothing {
        lastCommittedPostCompositionCandidate = leftSideCandidate
    }

    var results: [PublishedPostCompositionPrediction] = []
    var seenTexts: Set<String> = [leftSideCandidate.text]
    let contextualLeftText = effectivePostCompositionContextClause(
        committedText: leftSideCandidate.text,
        context: context
    )
    let effectiveLeftCandidate = resolveContextualLeftSideCandidate(
        leftSideCandidate: leftSideCandidate,
        contextualLeftText: contextualLeftText
    )

    func appendEnginePrediction(_ pred: PostCompositionPredictionCandidate) {
        guard results.count < maxPostCompositionTextPredictions,
              isUsablePostCompositionPredictionText(pred.text),
              seenTexts.insert(pred.text).inserted else {
            return
        }
        let joined = pred.join(to: leftSideCandidate)
        let id = allocatePostCompositionPredictionId()
        let item = PublishedPostCompositionPrediction(
            id: id,
            text: pred.text,
            reading: katakanaToHiragana(learningCandidateRuby(joined)),
            leftSideCandidate: leftSideCandidate,
            joinedCandidate: joined,
            enginePrediction: pred,
            isTerminal: pred.isTerminal || isPostCompositionTerminal(pred.text),
            isEmoji: false,
            shouldLearn: leftSideCandidate.isLearningTarget
        )
        publishedPostCompositionPredictions[id] = item
        results.append(item)
    }

    // Learned continuations precede engine predictions.
    if !privateInputActive && currentLearningType != .nothing {
        let keys = contextualLeftText == leftSideCandidate.text
            ? [contextualLeftText] : [contextualLeftText, leftSideCandidate.text]
        let now = learningEpochMilliseconds()
        var directCount = 0
        for key in keys {
            for entry in postCompositionBigramStore.entries(for: key, nowEpochMs: now) where directCount < 4 {
                let before = results.count
                appendEnginePrediction(.init(text: entry.surface, value: -0.5, type: .additional(data: [entry.element])))
                directCount += results.count - before
            }
        }
        var chainCount = 0
        for key in keys {
            for chain in postCompositionBigramStore.chains(from: key, nowEpochMs: now) where chainCount < 2 {
                let text = chain.map(\.surface).joined()
                let before = results.count
                appendEnginePrediction(.init(text: text, value: -0.6, type: .additional(data: chain.map(\.element))))
                chainCount += results.count - before
            }
        }
    }

    // 2. Query AzooKey collocations (`p/pc_<rcid>.csv`) + dictionary/learning LOUDS suffixes on full context,
    // filtering out broken single-char particle/auxiliary replacement fragments and Wikipedia stem noise.
    let textOnlyOptions = makePostCompositionConverterOptions(
        predictiveEnabled: true,
        textReplacer: .empty
    )
    let rawPredictions = normalNBestSupplementConverter.requestPostCompositionPredictionCandidates(
        leftSideCandidate: effectiveLeftCandidate,
        options: textOnlyOptions
    )
    var filteredReplacements: [PostCompositionPredictionCandidate] = []
    var filteredAdditionals: [PostCompositionPredictionCandidate] = []
    for pred in rawPredictions where shouldAcceptEnginePostCompositionPrediction(pred, effectiveLeftCandidate: effectiveLeftCandidate) {
        switch pred.type {
        case .replacement:
            filteredReplacements.append(pred)
        case .additional:
            filteredAdditionals.append(pred)
        }
    }

    // 3. Generate contextual next words conditioned on the full left context using ZeroCon when active.
    let contextualPredictions = generateContextualZeroConPredictions(
        contextualLeftText: contextualLeftText,
        effectiveLeftCandidate: effectiveLeftCandidate
    )

    let lastEffectiveElement = effectiveLeftCandidate.data.last
    let prefersContextualContentFirst = lastEffectiveElement.map { elem in
        isFunctionWordTailElement(elem)
            || (elem.word.count == 1 && isHiraganaCharacter(elem.word.first!))
            || elem.word.hasSuffix("た")
            || elem.word.hasSuffix("だ")
            || elem.word.hasSuffix("て")
            || elem.word.hasSuffix("ます")
            || elem.word.hasSuffix("です")
            || elem.word.hasSuffix("ない")
    } ?? false

    if prefersContextualContentFirst {
        for pred in contextualPredictions.prefix(5) {
            appendEnginePrediction(pred)
        }
        for pred in filteredReplacements.prefix(2) {
            appendEnginePrediction(pred)
        }
        for pred in filteredAdditionals {
            appendEnginePrediction(pred)
        }
    } else {
        // Content nouns (e.g. 東京, 東京駅, 駅前, 天気) combine bounded compound extensions,
        // high-priority grammatical particles, and contextual continuations.
        for pred in filteredReplacements.prefix(3) {
            appendEnginePrediction(pred)
        }
        for pred in filteredAdditionals.prefix(3) {
            appendEnginePrediction(pred)
        }
        for pred in contextualPredictions.prefix(4) {
            appendEnginePrediction(pred)
        }
        for pred in filteredAdditionals.dropFirst(3) {
            appendEnginePrediction(pred)
        }
        for pred in filteredReplacements.dropFirst(3) {
            appendEnginePrediction(pred)
        }
    }

    // 4. Append at most a couple of associated emojis behind text predictions.
    let emojiURL = execURL
        .appendingPathComponent("EmojiDictionary")
        .appendingPathComponent("emoji_all_E15.1.txt")
    let emojiReplacer = emojiTextReplacerCache.value(for: emojiURL)
    var emojiCount = 0
    for element in effectiveLeftCandidate.data.reversed() where emojiCount < maxPostCompositionEmojiPredictions {
        guard !(effectiveLeftCandidate.data.count > 1 && CIDData.isJoshi(cid: element.lcid)),
              !element.word.isEmpty else {
            continue
        }
        let matches = emojiReplacer.getSearchResult(
            query: element.word,
            target: [.emoji],
            ignoreNonBaseEmoji: true
        )
        for emoji in matches.reversed() {
            guard emojiCount < maxPostCompositionEmojiPredictions,
                  isUsablePostCompositionPredictionText(emoji.text),
                  seenTexts.insert(emoji.text).inserted else {
                continue
            }
            let emojiElement = DicdataElement(
                word: emoji.text,
                ruby: "エモジ",
                cid: CIDData.記号.cid,
                mid: MIDData.一般.mid,
                value: -3
            )
            let enginePred = PostCompositionPredictionCandidate(
                text: emoji.text,
                value: -3,
                type: .additional(data: [emojiElement])
            )
            let joined = enginePred.join(to: leftSideCandidate)
            let id = allocatePostCompositionPredictionId()
            let item = PublishedPostCompositionPrediction(
                id: id,
                text: emoji.text,
                reading: katakanaToHiragana(learningCandidateRuby(joined)),
                leftSideCandidate: leftSideCandidate,
                joinedCandidate: joined,
                enginePrediction: enginePred,
                isTerminal: true,
                isEmoji: true,
                shouldLearn: false
            )
            publishedPostCompositionPredictions[id] = item
            results.append(item)
            emojiCount += 1
        }
    }

    return results
}

private func updateLastCommittedContinuationAfterPostComposition(
    item: PublishedPostCompositionPrediction,
    contextBeforeSelection: String
) {
    if item.isTerminal {
        clearLastCommittedContinuation()
        return
    }
    let joinedSurface = item.joinedCandidate.text
    let joinedHiragana = katakanaToHiragana(learningCandidateRuby(item.joinedCandidate))
    guard !joinedSurface.isEmpty,
          !joinedSurface.contains(where: isAutomaticConversionContinuationStopCharacter),
          !joinedHiragana.isEmpty,
          isKanaLikeConversionContinuationReading(joinedHiragana),
          joinedHiragana.count <= maxAutomaticConversionContinuationReadingCount,
          joinedSurface.count <= maxAutomaticConversionContinuationSurfaceCount else {
        clearLastCommittedContinuation()
        return
    }
    if let existing = lastCommittedContinuation,
       !contextBeforeSelection.isEmpty,
       contextBeforeSelection.hasSuffix(existing.surface),
       existing.surface.hasSuffix(item.leftSideCandidate.text),
       existing.surface.count > item.leftSideCandidate.text.count {
        let prefixSurface = String(existing.surface.dropLast(item.leftSideCandidate.text.count))
        let leftHiragana = katakanaToHiragana(learningCandidateRuby(item.leftSideCandidate))
        if !leftHiragana.isEmpty,
           existing.reading.hasSuffix(leftHiragana) {
            let prefixReading = String(existing.reading.dropLast(leftHiragana.count))
            let combinedReading = prefixReading + joinedHiragana
            let combinedSurface = prefixSurface + joinedSurface
            if combinedReading.count <= maxAutomaticConversionContinuationReadingCount,
               combinedSurface.count <= maxAutomaticConversionContinuationSurfaceCount {
                lastCommittedContinuation = ConversionContinuation(
                    reading: combinedReading,
                    surface: combinedSurface
                )
                return
            }
        }
    }
    lastCommittedContinuation = ConversionContinuation(
        reading: joinedHiragana,
        surface: joinedSurface
    )
}

private func serializePostCompositionPayload(
    predictions: [PublishedPostCompositionPrediction],
    leftSurface: String,
    extra: [String: Any] = [:]
) -> [String: Any] {
    var payload = extra
    payload["leftSurface"] = leftSurface
    payload["predictions"] = predictions.map { item in
        [
            "id": String(item.id),
            "text": item.text,
            "reading": item.reading,
            "isTerminal": item.isTerminal,
            "isEmoji": item.isEmoji,
        ] as [String: Any]
    }
    return payload
}

private func jsonCString(from object: [String: Any]) -> UnsafeMutablePointer<CChar>? {
    guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
          let json = String(data: data, encoding: .utf8) else {
        return nil
    }
    return _strdup(json)
}

@_silgen_name("GetPostCompositionPredictions")
public func get_post_composition_predictions(
    candidateId: UInt64,
    committedText: UnsafePointer<CChar>?,
    committedReading: UnsafePointer<CChar>?,
    context: UnsafePointer<CChar>?
) -> UnsafeMutablePointer<CChar>? {
    let surface = committedText.map { String(cString: $0) } ?? ""
    let reading = committedReading.map { String(cString: $0) } ?? ""
    let contextString = context.map { String(cString: $0) } ?? ""

    guard isPostCompositionPredictionEnabled(),
          let leftSideCandidate = resolvePostCompositionLeftSideCandidate(
              candidateId: candidateId,
              committedText: surface,
              committedReading: reading
          ) else {
        clearPublishedPostCompositionPredictions()
        return jsonCString(from: serializePostCompositionPayload(predictions: [], leftSurface: surface))
    }

    let predictions = generatePostCompositionPredictions(
        leftSideCandidate: leftSideCandidate,
        context: contextString
    )
    serverLog(
        "DEBUG",
        "GetPostCompositionPredictions: leftSurface=\(leftSideCandidate.text) count=\(predictions.count)"
    )
    return jsonCString(from: serializePostCompositionPayload(
        predictions: predictions,
        leftSurface: leftSideCandidate.text
    ))
}

@_silgen_name("SelectPostCompositionPrediction")
public func select_post_composition_prediction(
    predictionId: UInt64,
    context: UnsafePointer<CChar>?,
    allowLearning: Bool
) -> UnsafeMutablePointer<CChar>? {
    let contextBeforeSelection = context.map { String(cString: $0) } ?? ""
    guard isPostCompositionPredictionEnabled(),
          let item = publishedPostCompositionPredictions.removeValue(forKey: predictionId) else {
        clearPublishedPostCompositionPredictions()
        return jsonCString(from: serializePostCompositionPayload(
            predictions: [],
            leftSurface: "",
            extra: ["accepted": false, "terminal": true]
        ))
    }
    clearPublishedPostCompositionPredictions()

    if !contextBeforeSelection.isEmpty,
       !contextBeforeSelection.hasSuffix(item.leftSideCandidate.text) {
        return jsonCString(from: serializePostCompositionPayload(
            predictions: [],
            leftSurface: "",
            extra: ["accepted": false, "terminal": true]
        ))
    }

    if !privateInputActive && allowLearning && item.shouldLearn && currentLearningType == .inputAndOutput {
        ensureConverterLearningConfigSynced()
        if let enginePred = item.enginePrediction {
            converter.updateLearningData(item.leftSideCandidate, with: enginePred)
            converterLearningDirty = true
            normalNBestSupplementConverter.setCompletedData(item.joinedCandidate)
            _ = flush_learning_memory()
        }
        if !item.isEmoji,
           !isPostCompositionStopForBigram(item.text),
           !isPostCompositionStopForBigram(item.leftSideCandidate.text) {
            let suffixRuby: String = switch item.enginePrediction?.type {
            case .additional(let data):
                normalizedLearningRuby(data.reduce(into: "") { $0 += $1.ruby })
            case .replacement(let targetData, let replacementData):
                {
                    let targetRubyCount = targetData.reduce(0) { $0 + $1.ruby.count }
                    let fullRuby = replacementData.reduce(into: "") { $0 += $1.ruby }
                    return normalizedLearningRuby(String(fullRuby.dropFirst(min(targetRubyCount, fullRuby.count))))
                }()
            case nil:
                normalizedLearningRuby(item.text)
            }
            let entry = PostCompositionBigramEntry(
                surface: item.text,
                ruby: suffixRuby,
                rcid: item.joinedCandidate.data.last?.rcid ?? CIDData.一般名詞.cid,
                mid: item.joinedCandidate.lastMid
            )
            if recordContextualPostCompositionBigram(
                previousSurface: item.leftSideCandidate.text,
                contextBeforeCommit: contextBeforeSelection,
                entry: entry
            ) {
                postCompositionBigramsDirty = true
                _ = savePostCompositionBigrams()
            }
        }
    }

    if !privateInputActive && currentLearningType != .nothing {
        lastCommittedPostCompositionCandidate = item.joinedCandidate
    }
    updateLastCommittedContinuationAfterPostComposition(
        item: item,
        contextBeforeSelection: contextBeforeSelection
    )

    let updatedContext = contextBeforeSelection.isEmpty ? "" : (contextBeforeSelection + item.text)
    let nextPredictions: [PublishedPostCompositionPrediction] = item.isTerminal
        ? []
        : generatePostCompositionPredictions(leftSideCandidate: item.joinedCandidate, context: updatedContext)

    serverLog(
        "DEBUG",
        "SelectPostCompositionPrediction: text=\(item.text) joined=\(item.joinedCandidate.text) terminal=\(item.isTerminal) nextCount=\(nextPredictions.count)"
    )
    return jsonCString(from: serializePostCompositionPayload(
        predictions: nextPredictions,
        leftSurface: item.joinedCandidate.text,
        extra: [
            "accepted": true,
            "terminal": item.isTerminal,
        ]
    ))
}

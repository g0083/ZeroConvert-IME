import Foundation
import KanaKanjiConverterModule
import Testing
@testable import azookey_server

private let correctionPackageRoot = URL(filePath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

private func correctionOptions(enabled: Bool, model: String? = nil, context: String = "") -> ConvertRequestOptions {
    makeConvertRequestOptions(context: context, zenzaiEnabled: model != nil,
        runtimeDirectoryURL: correctionPackageRoot,
        emojiDictionaryURL: correctionPackageRoot.appending(path: "azooKey_emoji_dictionary_storage/EmojiDictionary/emoji_all_E15.1.txt"),
        zenzaiWeightURL: URL(filePath: model ?? "unused.gguf"), profile: "", learningType: .nothing,
        experimentalKeyboardTypoCorrection: enabled)
}

private func typed(_ keys: String) -> ComposingText {
    var input = ComposingText()
    for key in keys { input.insertAtCursorPosition(String(key), inputStyle: .roman2kana) }
    return input
}

@MainActor private func dictionaryConverter() -> KanaKanjiConverter {
    let converter = KanaKanjiConverter(dictionaryURL: correctionPackageRoot.appending(path: "azooKey_dictionary_storage/Dictionary"), preloadDictionary: true)
    converter.importDynamicUserDictionary(makeKeyboardTypoDictionaryEntries())
    return converter
}

@Test func sharedTouchCorrectionPreservesObservedInputAndPublishesCorrectedReading() async {
    await MainActor.run {
        let converter = dictionaryConverter()
        let input = typed("watadi")
        let normal = converter.requestCandidates(input, options: correctionOptions(enabled: true)).mainResults
        let touch = touchWeightedKeyboardCandidates(composing: input,
            evidence: [.init(inputIndex: 4, typed: "d", alternatives: [("s", 0.9)])],
            options: correctionOptions(enabled: true), lookup: { input, options in
                #expect(!options.experimentalKeyboardTypoCorrection)
                return converter.requestExactKeyboardCorrectionCandidates(input, options: options)
            })
        #expect(touch.candidates.contains { $0.text == "私" })
        let merged = mergeTouchWeightedKeyboardCandidates(normal: normal, touch: touch,
            dictionary: normal)
        #expect(merged.first?.text == "私", "normal=\(normal.map { ($0.text, $0.value, $0.data.map { ($0.word, $0.ruby, $0.value()) }) })")
        if let candidate = touch.candidates.first(where: { $0.text == "私" }) {
            #expect(keyboardCorrectionReading(candidate) == "わたし")
            #expect(candidate.composingCount == .inputCount(6))
            #expect(constructCandidateString(candidate: candidate, hiragana: input.convertTarget) == "私")
            #expect(!candidate.isLearningTarget)
        }
        #expect(input.convertTarget == "わたぢ")
        #expect(input.input.count == 6)
        var edited = input
        edited.deleteBackwardFromCursorPosition(count: 1)
        edited.insertAtCursorPosition("di", inputStyle: .roman2kana)
        #expect(edited.convertTarget == input.convertTarget)
    }
}

@Test func sharedTouchCorrectionRejectsPendingDirectStaleAndDisabledInput() async {
    await MainActor.run {
        var calls = 0
        let lookup: (ComposingText, ConvertRequestOptions) -> [Candidate] = { _, _ in calls += 1; return [] }
        let evidence = [KeyboardTouchEvidence(inputIndex: 2, typed: "n", alternatives: [("m", 0.95)])]
        #expect(touchWeightedKeyboardCandidates(composing: typed("nan"), evidence: evidence,
            options: correctionOptions(enabled: true), lookup: lookup).candidates.isEmpty)
        #expect(touchWeightedKeyboardCandidates(composing: typed("watadi"), evidence: evidence,
            options: correctionOptions(enabled: true), lookup: lookup).candidates.isEmpty)
        var direct = ComposingText()
        direct.insertAtCursorPosition("watadi", inputStyle: .direct)
        #expect(touchWeightedKeyboardCandidates(composing: direct, evidence: evidence,
            options: correctionOptions(enabled: true), lookup: lookup).candidates.isEmpty)
        #expect(touchWeightedKeyboardCandidates(composing: typed("nan"), evidence: evidence,
            options: correctionOptions(enabled: false), lookup: lookup).candidates.isEmpty)
        #expect(calls == 0)
    }
}

@Test func touchWeightCannotOverrideAnExactLexicalReading() {
    let original = Candidate(text: "傘", value: -4, composingCount: .inputCount(4), lastMid: 0,
        data: [DicdataElement(word: "傘", ruby: "カサ", cid: 1285, mid: 0, value: -4)])
    let alternative = Candidate(text: "課題", value: -1, composingCount: .inputCount(4), lastMid: 0,
        data: [DicdataElement(word: "課題", ruby: "カダイ", cid: 1285, mid: 0, value: -1,
            metadata: .isKeyboardTypoCorrection)], isLearningTarget: false)
    let merged = mergeTouchWeightedKeyboardCandidates(normal: [original], touch: .init(candidates: [alternative], originalIsLexical: true),
        dictionary: [original])
    #expect(merged.map(\.text) == ["傘", "課題"])
}

@Test func fixedDictionaryCorrectionCarriesItsIntendedReadingAndUntouchedSuffix() {
    let entry = makeKeyboardTypoDictionaryEntries()[0]
    let candidate = Candidate(text: entry.word, value: -1, composingCount: .inputCount(8),
        lastMid: entry.mid, data: [entry])
    #expect(keyboardCorrectionReading(candidate, remainingReading: "ので") == "しましたので")
}

@Test func sharedTouchCorrectionFFIPreservesLongerReadingAndOriginalConsumption() async {
    await MainActor.run {
        let previousConverter = converter
        let previousSupplement = normalNBestSupplementConverter
        let previousInput = composingText
        let previousConfig = config
        let previousExec = execURL
        let previousLearning = currentLearningType
        let previousCache = learningCandidateCache
        let previousTouch = currentKeyboardTouchEvidence
        defer {
            converter = previousConverter
            normalNBestSupplementConverter = previousSupplement
            composingText = previousInput
            config = previousConfig
            execURL = previousExec
            currentLearningType = previousLearning
            learningCandidateCache = previousCache
            currentKeyboardTouchEvidence = previousTouch
        }
        converter = dictionaryConverter()
        normalNBestSupplementConverter = dictionaryConverter()
        // A deterministic word fixture makes the longer-reading path win without
        // relying on the installed user's vocabulary or learning history.
        let lexical = DicdataElement(word: "補正語", ruby: "ワタヂアヤ", cid: 1285, mid: 0, value: -1)
        normalNBestSupplementConverter.importDynamicUserDictionary([lexical])
        composingText = typed("watadixya")
        currentKeyboardTouchEvidence = [.init(inputIndex: 6, typed: "x", alternatives: [("a", 0.95)])]
        config = ["enable": false, "experimentalTypoCorrection": true, "predictiveConversion": false]
        execURL = correctionPackageRoot
        currentLearningType = .nothing
        let options = getOptions()
        let touch = touchWeightedKeyboardCandidates(composing: composingText,
            evidence: currentKeyboardTouchEvidence, options: options, lookup: { input, options in
                normalNBestSupplementConverter.requestExactKeyboardCorrectionCandidates(input, options: options)
            })
        #expect(touch.candidates.first?.text == "補正語", "touch=\(touch.candidates.map(\.text))")
        let normal = converter.requestCandidates(composingText, options: options).mainResults
        let merged = mergeTouchWeightedKeyboardCandidates(normal: normal, touch: touch,
            dictionary: normal)
        #expect(merged.first?.text == "補正語", "normal=\(normal.map { ($0.text, $0.value, $0.data.map { ($0.word, $0.ruby, $0.value(), $0.metadata) }) })")
        var count: CInt = 0
        let list = get_composed_text(lengthPtr: &count)
        defer { free_candidate_list(list, count) }
        #expect(count > 0)
        if count > 0, let candidate = list[0]?.pointee {
            #expect(String(cString: candidate.text) == "補正語")
            #expect(candidate.predictionReading.map { String(cString: $0) } == "わたぢあや")
            #expect(candidate.correspondingCount == 9)
            #expect(String(cString: candidate.subtext).isEmpty)
        }
        #expect(composingText.convertTarget == "わたぢゃ")
        #expect(composingText.input.count == 9)
        composingText.prefixComplete(composingCount: .inputCount(9))
        #expect(composingText.convertTarget.isEmpty)
        #expect(composingText.input.isEmpty)
    }
}

/// Run this alone with an output path to compare the actual converter on the
/// registered typo set, correct counterparts, and held-out normal controls.
@Test func keyboardTypoReadinessEvaluation() async throws {
    guard let output = ProcessInfo.processInfo.environment["ZEROCON_TYPO_EVALUATION_OUTPUT"] else { return }
    let model = ProcessInfo.processInfo.environment["ZEROCON_TYPO_EVALUATION_MODEL"]
    let windows = correctionPackageRoot.deletingLastPathComponent()
    let root = windows.deletingLastPathComponent()
    let table = try String(contentsOf: windows.appending(path: "data/keyboard-typo-dictionary.tsv"), encoding: .utf8)
        .split(whereSeparator: \.isNewline).map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
    let header = table[0]
    func column(_ name: String) -> Int { header.firstIndex(of: name)! }
    let rows = table.dropFirst()
    var cases: [(keys: String, expected: String?, clean: Bool, context: String, group: String)] = rows.map {
        ($0[column("typed_keys")], $0[column("expected_candidate")], false, "", "registered")
    }
    cases += ["gakkou", "konnnitiha", "onnna", "kitte", "massugu", "honntou", "etto", "honnto",
        "kyouhatenkigayoi", "watashihagakuseidesu", "toukyouniikimasu", "korehatesutodesu",
        "arigatougozaimasu", "shinjuku", "shinnbashi", "nyuuyooku", "syujutu", "iPhone", "https://example.com"].map { ($0, nil, true, "", "normal-control") }
    let normalLines = try String(contentsOf: root.appending(path: "data/typo_benchmark_300.jsonl"), encoding: .utf8)
        .split(whereSeparator: \.isNewline)
    var heldout: [[String: Any]] = []
    for line in normalLines {
        let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
        heldout.append(row)
    }
    let romanRows = try String(contentsOf: windows.appending(path: "crates/shared/src/default_romaji_table.txt"), encoding: .utf8)
        .split(whereSeparator: \.isNewline).map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
    var roman: [String: String] = ["ん": "nn", "っ": "xtu"]
    for row in romanRows where row.count >= 2 && !row[0].hasPrefix("#") && !row[1].isEmpty && (row.count < 3 || row[2].isEmpty) {
        let previous = roman[row[1]]
        if previous == nil || (row[1] != "ん" && row[1] != "っ" && row[0].count < previous!.count) { roman[row[1]] = row[0] }
    }
    func romanKeys(_ source: String) -> String {
        var reading = source.applyingTransform(.hiraganaToKatakana, reverse: true) ?? source
        var keys = ""
        while !reading.isEmpty {
            let match = roman.keys.filter { reading.hasPrefix($0) }.max { $0.count < $1.count }
            if let match { keys += roman[match]!; reading.removeFirst(match.count) }
            else { keys.append(reading.removeFirst()) }
        }
        return keys
    }
    for row in rows {
        let reading = row[column("corrected_reading")]
        let keys = romanKeys(reading)
        cases.append((keys, nil, true, "", "normal-counterpart"))
        if typed(row[column("corrected_keys")]).convertTarget != reading {
            cases.append((row[column("corrected_keys")], row[column("expected_candidate")], false, "", "romaji-compatibility"))
        }
    }
    for row in heldout {
        let clean = row["is_clean"] as! Bool
        cases.append((romanKeys(row["input_reading"] as! String), clean ? nil : row["expected"] as? String,
            clean, row["context"] as? String ?? "", clean ? "heldout-normal" : "heldout-typo"))
    }
    let data = try await MainActor.run { () throws -> Data in
        let ordinary = dictionaryConverter()
        let corrected = dictionaryConverter()
        let ordinaryModel = model == nil ? nil : dictionaryConverter()
        let correctedModel = model == nil ? nil : dictionaryConverter()
        var records: [[String: Any]] = []
        for item in cases {
            let input = typed(item.keys)
            var results: [[Candidate]] = []
            var times: [Double] = []
            var dictionaryOn: [Candidate] = []
            for enabled in [false, true] {
                let converter = enabled ? corrected : ordinary
                let start = Date()
                let options = correctionOptions(enabled: enabled, model: model, context: item.context)
                var dictionaryOptions = options
                dictionaryOptions.zenzaiMode = .off
                let dictionary = converter.requestCandidates(input, options: dictionaryOptions).mainResults
                if enabled { dictionaryOn = dictionary }
                if let engine = enabled ? correctedModel : ordinaryModel {
                    let predicted = engine.requestCandidates(input, options: optionsForZenzaiConversion(from: options)).mainResults
                    results.append(mergeZenzaiMainResultsWithNormalNBest(zenzaiResults: predicted,
                        normalNBestResults: dictionary, hiragana: input.convertTarget))
                } else { results.append(dictionary) }
                times.append(Date().timeIntervalSince(start) * 1000)
            }
            let before = results[0].map { constructCandidateString(candidate: $0, hiragana: input.convertTarget) }
            let after = results[1].map { constructCandidateString(candidate: $0, hiragana: input.convertTarget) }
            let changedReading = results[1].first.flatMap { keyboardCorrectionReading($0) }
            var touchTop = ""
            var touchReading = ""
            var touchMs = 0.0
            if item.clean {
                let neighbors: [Character: Character] = ["q":"w", "w":"e", "e":"r", "r":"t", "t":"y", "y":"u", "u":"i", "i":"o", "o":"p", "p":"o", "a":"s", "s":"d", "d":"f", "f":"g", "g":"h", "h":"j", "j":"k", "k":"l", "l":"k", "z":"x", "x":"c", "c":"v", "v":"b", "b":"n", "n":"m", "m":"n"]
                let evidence = input.input.enumerated().compactMap { index, element -> KeyboardTouchEvidence? in
                    guard case let .character(key) = element.piece,
                          let neighbor = neighbors[key] else { return nil }
                    return .init(inputIndex: index, typed: key, alternatives: [(neighbor, Float(0.9))])
                }
                let start = Date()
                let touched = touchWeightedKeyboardCandidates(composing: input, evidence: evidence,
                    options: correctionOptions(enabled: true), lookup: { text, options in
                        corrected.requestExactKeyboardCorrectionCandidates(text, options: options)
                    })
                let merged = mergeTouchWeightedKeyboardCandidates(normal: results[1], touch: touched,
                    dictionary: dictionaryOn)
                touchMs = Date().timeIntervalSince(start) * 1000
                touchTop = merged.first?.text ?? ""
                touchReading = merged.first.flatMap { keyboardCorrectionReading($0) } ?? ""
            }
            records.append(["keys": item.keys, "observedReading": input.convertTarget,
                "clean": item.clean, "group": item.group, "context": item.context,
                "expected": item.expected ?? "", "before": Array(before.prefix(5)),
                "after": Array(after.prefix(5)), "correctedReading": changedReading ?? "",
                "beforeMs": times[0], "afterMs": times[1], "touchTop": touchTop,
                "touchReading": touchReading, "touchMs": touchMs])
        }
        return try JSONSerialization.data(withJSONObject: ["model": model ?? "dictionary-only", "cases": records], options: [.prettyPrinted, .sortedKeys])
    }
    try data.write(to: URL(filePath: output))
    let evaluation = (try JSONSerialization.jsonObject(with: data) as! [String: Any])["cases"] as! [[String: Any]]
    let falseCorrections = evaluation.filter { $0["clean"] as? Bool == true && !($0["correctedReading"] as! String).isEmpty }
    #expect(falseCorrections.isEmpty, "Normal input acquired a reading correction: \(falseCorrections)")
    let touchFalseCorrections = evaluation.filter { $0["clean"] as? Bool == true && !($0["touchReading"] as! String).isEmpty }
    #expect(touchFalseCorrections.isEmpty, "Correct input was changed by boundary-touch evidence: \(touchFalseCorrections)")
}

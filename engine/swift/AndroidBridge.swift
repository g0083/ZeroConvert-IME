// Compiled in the same module as the unmodified Windows server sources.
import Foundation
import Dispatch
import KanaKanjiConverterModule
import ffi
#if os(Android)
import Android

// The Windows wrapper's annotations name this host actor when built for Android.
// Its ordinary serial executor is independent of Android's UI/main queue.
@globalActor public actor EmbeddedConversionActor {
    public static let shared = EmbeddedConversionActor()
}
public typealias MainActor = EmbeddedConversionActor

// Windows calls the CRT spelling; Android exports the POSIX spelling.
func _strdup(_ value: String) -> UnsafeMutablePointer<CChar>? { value.withCString { strdup($0) } }
#endif

@MainActor private var embeddedInitialized = false

@MainActor private func embeddedDecodeJsonObject(_ pointer: UnsafeMutablePointer<CChar>?) -> [String: Any] {
    guard let pointer else { return [:] }
    defer { free_c_string(pointer) }
    guard let data = String(cString: pointer).data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        return [:]
    }
    return object
}

/// Reads the server's learning counters through its C entry point, so the bridge
/// never grows a second copy of what counts as learned.
@MainActor private func embeddedLearningStats() -> [String: Any] {
    embeddedDecodeJsonObject(get_learning_stats())
}

// Use the existing Windows performance callback; retain durations only, never
// callback details (which can contain editor text). Disabled outside diagnostics.
private final class EmbeddedPerformance: @unchecked Sendable {
    private let lock = NSLock()
    private var enabled = false
    private var stages: [String: UInt64] = [:]
    func begin(_ enabled: Bool) { lock.lock(); self.enabled = enabled; stages = [:]; lock.unlock() }
    func isEnabled() -> Bool { lock.lock(); defer { lock.unlock() }; return enabled }
    func record(_ stage: UnsafePointer<CChar>?, _ milliseconds: UInt64) {
        guard let stage else { return }
        lock.lock(); defer { lock.unlock() }
        stages[String(cString: stage), default: 0] += milliseconds
    }
    func snapshot() -> [String: UInt64] { lock.lock(); defer { lock.unlock() }; return stages }
}
private let embeddedPerformance = EmbeddedPerformance()

private func appendJsonString(_ value: String, to out: inout String) {
    out.append("\"")
    if value.utf8.allSatisfy({ $0 >= 0x20 && $0 != 0x22 && $0 != 0x5C }) {
        out.append(value)
        out.append("\"")
        return
    }
    for scalar in value.unicodeScalars {
        switch scalar.value {
        case 0x22: out.append("\\\"")
        case 0x5C: out.append("\\\\")
        case 0x08: out.append("\\b")
        case 0x0C: out.append("\\f")
        case 0x0A: out.append("\\n")
        case 0x0D: out.append("\\r")
        case 0x09: out.append("\\t")
        case 0x00..<0x20:
            let hex = String(scalar.value, radix: 16, uppercase: false)
            out.append("\\u00")
            if hex.count < 2 { out.append("0") }
            out.append(hex)
        default:
            out.unicodeScalars.append(scalar)
        }
    }
    out.append("\"")
}

@MainActor private func embeddedDispatchJson(_ data: Data) throws -> String {
    guard let request = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let op = request["op"] as? String else {
        return "{\"error\":\"invalid request\"}"
    }
    if op == "convert" {
        return embeddedConvertJson(request)
    }
    let result = try embeddedDispatchRequest(request, op: op)
    let output = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    return String(decoding: output, as: UTF8.self)
}

@MainActor private func embeddedDispatchRequest(_ request: [String: Any], op: String) throws -> [String: Any] {
    if op == "configure" {
        guard let root = request["root"] as? String, let model = request["modelPath"] as? String else {
            return ["error": "missing runtime paths"]
        }
        config["embeddedModelPath"] = model
        set_server_log_callbacks(nil, nil, { embeddedPerformance.isEnabled() }, nil,
            { _, _, stage, elapsed, _ in embeddedPerformance.record(stage, elapsed) }, nil, nil, nil)
        postCompositionCancellationCheck = { zerocon_conversion_cancelled() }
        if !embeddedInitialized {
            root.withCString { initialize(path: $0, use_zenzai: true) }
            embeddedInitialized = true
        } else { load_config() }
        config["embeddedModelPath"] = model
        return ["ready": true]
    }
    if op == "applyRuntimeTuning" {
        let decodeThreads = (request["decodeThreads"] as? NSNumber)?.int32Value
        let batchThreads = (request["batchThreads"] as? NSNumber)?.int32Value
        let directModelFirst = request["directModelFirst"] as? Bool
        ZenzaiRuntimeTuning.configure(
            decodeThreads: decodeThreads.map { max(1, $0) },
            batchThreads: batchThreads.map { max(1, $0) },
            directModelFirstOverride: directModelFirst
        )
        return ["applied": true]
    }
    guard embeddedInitialized else { return ["error": "engine not initialized"] }
    if op == "probeRuntimeTuning" {
        let epoch = (request["requestEpoch"] as? NSNumber)?.uint64Value ?? zerocon_conversion_epoch()
        zerocon_conversion_begin(epoch)
        defer { zerocon_conversion_end() }
        guard !zerocon_conversion_cancelled() else { return ["cancelled": true] }
        guard let rawConfigs = request["configs"] else { return ["error": "missing configs"] }
        let configsData = try JSONSerialization.data(withJSONObject: rawConfigs)
        let configs = try JSONDecoder().decode([RuntimeTuningProbeConfig].self, from: configsData)
        let savedContext = config["context"] as? String ?? ""
        config["context"] = ""
        defer { config["context"] = savedContext }
        configureEngineRuntime(zenzaiEnabled: true)
        let emojiURL = execURL
            .appendingPathComponent("EmojiDictionary")
            .appendingPathComponent("emoji_all_E15.1.txt")
        let baseOptions = makeConvertRequestOptions(
            context: "",
            zenzaiEnabled: true,
            runtimeDirectoryURL: converterRuntimeDirectoryURL(),
            emojiDictionaryURL: emojiURL,
            textReplacer: .empty,
            zenzaiWeightURL: converterModelURL(),
            profile: "",
            learningType: .nothing,
            learningMemoryDirectoryURL: nil,
            experimentalKeyboardTypoCorrection: false,
            predictiveConversion: false
        )
        guard let measurements = withIsolatedProbeUserDictionary(
            mainConverter: converter,
            supplementConverter: normalNBestSupplementConverter,
            body: {
                probeRuntimeTuningConfigs(
                    baseOptions: baseOptions,
                    configs: configs,
                    shouldCancel: { zerocon_conversion_cancelled() }
                )
            }
        ) else {
            return ["cancelled": true]
        }
        let encoded = try JSONEncoder().encode(measurements)
        let serialized = try JSONSerialization.jsonObject(with: encoded)
        return ["measurements": serialized]
    }
    if op == "readings" {
        let surface = request["surface"] as? String ?? ""
        guard surface.count <= 128,
              let encoded = surface.withCString({ infer_reconversion_readings(surface: $0) }) else {
            return ["readings": [String]()]
        }
        defer { free_c_string(encoded) }
        let readings = try JSONSerialization.jsonObject(with: Data(String(cString: encoded).utf8))
        return ["readings": readings]
    }
    if op == "commit" {
        guard let id = request["candidateId"] as? String, let candidateId = UInt64(id) else {
            return ["error": "invalid candidate identity"]
        }
        return ["learned": commit_learning_candidate_with_explicit(
            candidateId: candidateId, commitKind: 0,
            explicitSelection: (request["explicitSelection"] as? Bool) ?? false
        )]
    }
    if op == "forget" {
        guard let id = request["candidateId"] as? String, let candidateId = UInt64(id) else {
            return ["error": "invalid candidate identity"]
        }
        return ["forgot": forget_learning_candidate(candidateId: candidateId)]
    }
    // Applies the same guards and single-shot consume as "forget" but writes no
    // store. Device checks use it so they never rewrite the user's learning memory.
    if op == "validateForget" {
        guard let id = request["candidateId"] as? String, let candidateId = UInt64(id) else {
            return ["error": "invalid candidate identity"]
        }
        return ["valid": validate_learning_candidate_deletion(candidateId: candidateId)]
    }
    if op == "clear" { clear_text(); return [:] }
    if op == "close" {
        _ = flush_learning_memory()
        clear_text()
        converter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        return [:]
    }
    // The Japanese dictionary is downloaded after first install. Windows restarts
    // its server to pick it up; Android rebuilds the same state in place.
    if op == "setDictionary" {
        guard let path = request["path"] as? String, !path.isEmpty else {
            return ["error": "missing dictionary path"]
        }
        let dictionaryURL = URL(filePath: path, directoryHint: .isDirectory)
        guard FileManager.default.fileExists(atPath: dictionaryURL.appendingPathComponent("louds/charID.chid").path) else {
            return ["error": "dictionary is incomplete"]
        }
        _ = flush_learning_memory()
        clear_text()
        converterDictionaryURL = dictionaryURL
        do {
            try reconversionDictionary.load(from: dictionaryURL)
        } catch {
            reconversionDictionary = ReconversionDictionary()
        }
        converter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        return ["ready": true]
    }
    if op == "resetLearning" {
        let reset = reset_learning_memory()
        return ["reset": reset, "stats": embeddedLearningStats()]
    }
    if op == "learningStats" { return embeddedLearningStats() }
    if op == "postComposition" {
        let epoch = (request["requestEpoch"] as? NSNumber)?.uint64Value ?? zerocon_conversion_epoch()
        zerocon_conversion_begin(epoch)
        defer { zerocon_conversion_end() }
        guard !zerocon_conversion_cancelled() else { return ["cancelled": true] }
        let candidateId = (request["candidateId"] as? String).flatMap(UInt64.init) ?? 0
        let committedText = request["committedText"] as? String ?? ""
        let committedReading = request["committedReading"] as? String ?? ""
        let context = request["context"] as? String ?? ""
        if let enabled = (request["postCompositionPrediction"] as? Bool) ?? (request["predictiveConversion"] as? Bool) {
            config["postCompositionPrediction"] = enabled
        }
        let payload = committedText.withCString { textPtr in
            committedReading.withCString { readingPtr in
                context.withCString { contextPtr in
                    embeddedDecodeJsonObject(get_post_composition_predictions(
                        candidateId: candidateId,
                        committedText: textPtr,
                        committedReading: readingPtr,
                        context: contextPtr
                    ))
                }
            }
        }
        if zerocon_conversion_cancelled() {
            clearPublishedPostCompositionPredictions()
            return ["cancelled": true]
        }
        return payload
    }
    if op == "selectPostComposition" {
        let epoch = (request["requestEpoch"] as? NSNumber)?.uint64Value ?? zerocon_conversion_epoch()
        zerocon_conversion_begin(epoch)
        defer { zerocon_conversion_end() }
        guard !zerocon_conversion_cancelled() else { return ["cancelled": true] }
        guard let id = request["predictionId"] as? String, let predictionId = UInt64(id) else {
            return ["error": "invalid post-composition prediction identity"]
        }
        let context = request["context"] as? String ?? ""
        let allowLearning = request["allowLearning"] as? Bool ?? true
        if let enabled = (request["postCompositionPrediction"] as? Bool) ?? (request["predictiveConversion"] as? Bool) {
            config["postCompositionPrediction"] = enabled
        }
        let payload = context.withCString { contextPtr in
            embeddedDecodeJsonObject(select_post_composition_prediction(
                predictionId: predictionId,
                context: contextPtr,
                allowLearning: allowLearning
            ))
        }
        if zerocon_conversion_cancelled() {
            clearPublishedPostCompositionPredictions()
            return ["cancelled": true]
        }
        return payload
    }
    return ["error": "invalid request"]
}

@MainActor private func embeddedConvertJson(_ request: [String: Any]) -> String {
    guard embeddedInitialized else { return "{\"error\":\"engine not initialized\"}" }
    guard let elements = request["elements"] as? [[String: Any]],
          elements.count <= 4096 else { return "{\"error\":\"invalid conversion request\"}" }
    var input: [ComposingText.InputElement] = []
    for element in elements {
        let style: InputStyle
        switch element["style"] as? String {
        case "ROMAN2KANA": style = .roman2kana
        case "FROZEN": style = .mapped(id: .empty)
        default: style = .direct
        }
        if element["separator"] as? Bool == true {
            input.append(.init(piece: .compositionSeparator, inputStyle: style))
        } else {
            for character in (element["text"] as? String ?? "") {
                input.append(.init(character: character, inputStyle: style))
            }
        }
    }
    var snapshot = ComposingText()
    snapshot.insertAtCursorPosition(input)
    let reading = snapshot.convertTarget
    let cursorUtf16 = request["cursorUtf16"] as? Int ?? reading.utf16.count
    let cursorIndex = String.Index(utf16Offset: max(0, min(cursorUtf16, reading.utf16.count)), in: reading)
    let cursor = reading.distance(from: reading.startIndex, to: cursorIndex)
    _ = snapshot.moveCursorFromCursorPosition(count: cursor - reading.count)
    // A local input implementation must never silently disagree with the engine.
    guard request["expectedReading"] as? String == reading else {
        var errorJson = "{\"error\":\"composition mismatch\",\"reading\":"
        appendJsonString(reading, to: &errorJson)
        errorJson.append("}")
        return errorJson
    }
    composingText = snapshot
    currentKeyboardTouchEvidence = (request["touchEvidence"] as? [[String: Any]] ?? []).prefix(128).compactMap { entry in
        guard let index = entry["inputIndex"] as? Int,
              let typed = entry["typed"] as? String, typed.count == 1,
              let alternatives = entry["alternatives"] as? [[String: Any]] else { return nil }
        return KeyboardTouchEvidence(inputIndex: index, typed: typed.first!, alternatives: alternatives.prefix(4).compactMap {
            guard let key = $0["key"] as? String, key.count == 1,
                  let weight = $0["weight"] as? Double, weight.isFinite else { return nil }
            return (key.first!, Float(weight))
        })
    }
    defer { currentKeyboardTouchEvidence = [] }
    config["context"] = request["context"] as? String ?? ""
    let continuationReading = request["continuationReading"] as? String ?? ""
    let continuationSurface = request["continuationSurface"] as? String ?? ""
    var continuationAccepted = false
    if request["allowAI"] as? Bool != false,
       !continuationReading.isEmpty,
       !continuationSurface.isEmpty {
        continuationAccepted = setConversionContinuation(reading: continuationReading, surface: continuationSurface)
    } else {
        _ = setConversionContinuation(reading: "", surface: "")
    }
    let epoch = (request["requestEpoch"] as? NSNumber)?.uint64Value ?? zerocon_conversion_epoch()
    zerocon_conversion_begin(epoch)
    embeddedPerformance.begin(request["diagnostics"] as? Bool ?? false)
    defer { zerocon_conversion_end(); embeddedPerformance.begin(false) }
    guard !zerocon_conversion_cancelled() else { return "{\"cancelled\":true}" }
    let started = DispatchTime.now().uptimeNanoseconds
    var length: CInt = 0
    var readingCompletions = Set<Int>()
    // No caller-supplied raw-input boundary: use the snapshot's cursor prefix.
    // Zero is an exact empty boundary in the shared API, not an unspecified one.
    let allowAI = request["allowAI"] as? Bool != false
    let candidates = allowAI && !snapshot.isAtEndIndex
        ? get_composed_text_for_cursor_range(lengthPtr: &length)
        : get_composed_text_impl(lengthPtr: &length, allowZenzai: allowAI, onReadingCompletion: {
            readingCompletions.insert($0)
        })
    defer { free_candidate_list(candidates, length) }
    if zerocon_conversion_cancelled() {
        // An aborted decode may leave a partially updated KV cache. Reset both
        // converter sessions before the next request, using their existing API.
        // Keep learning candidate IDs from earlier, successfully displayed batches.
        converter.stopComposition()
        normalNBestSupplementConverter.stopComposition()
        return "{\"cancelled\":true}"
    }
    let marshalStart = DispatchTime.now().uptimeNanoseconds
    var json = ""
    json.reserveCapacity(Int(length) * 96 + 512)
    json.append("{\"reading\":")
    appendJsonString(reading, to: &json)
    json.append(",\"cursorUtf16\":")
    json.append(String(cursorUtf16))
    json.append(",\"candidates\":[")
    let boundaries = snapshot.inputIndexToSurfaceIndexMap()
    var surfaceToUtf16 = [Int]()
    surfaceToUtf16.reserveCapacity(reading.count + 1)
    var utf16Offset = 0
    for character in reading {
        surfaceToUtf16.append(utf16Offset)
        utf16Offset += character.utf16.count
    }
    surfaceToUtf16.append(utf16Offset)
    var seenKeys = Set<String>(minimumCapacity: Int(length))
    var wroteCandidate = false
    for index in 0..<Int(length) {
        guard let candidate = candidates[index]?.pointee else { continue }
        guard let consumed = boundaries[Int(candidate.correspondingCount)],
              consumed > 0, consumed <= snapshot.convertTargetCursorPosition,
              consumed < surfaceToUtf16.count else { continue }
        let consumedUtf16 = surfaceToUtf16[consumed]
        let text = candidate.text.map { String(cString: $0) } ?? ""
        let key = "\(consumedUtf16):\(text)"
        guard seenKeys.insert(key).inserted else { continue }
        if wroteCandidate {
            json.append(",")
        } else {
            wroteCandidate = true
        }
        json.append("{\"text\":")
        appendJsonString(text, to: &json)
        json.append(",\"consumedReadingUtf16\":")
        json.append(String(consumedUtf16))
        if let suffixPtr = candidate.subtext, suffixPtr[0] != 0 {
            json.append(",\"suffix\":")
            appendJsonString(String(cString: suffixPtr), to: &json)
        }
        if candidate.candidateId != 0 {
            json.append(",\"candidateId\":\"")
            json.append(String(candidate.candidateId))
            json.append("\"")
        }
        if readingCompletions.contains(index) {
            json.append(",\"readingCompletion\":true")
        }
        if candidate.candidateId != 0 && learningCandidateCache.isLearningTarget(candidate.candidateId) {
            json.append(",\"learningAllowed\":true")
        }
        if let predPtr = candidate.predictionReading, predPtr[0] != 0 {
            json.append(",\"predictionReading\":")
            appendJsonString(String(cString: predPtr), to: &json)
        }
        json.append("}")
    }
    json.append("]")
    if embeddedPerformance.isEnabled() {
        let marshalMs = (DispatchTime.now().uptimeNanoseconds - marshalStart) / 1_000_000
        "bridge_marshal_candidates".withCString { embeddedPerformance.record($0, marshalMs) }
    }
    json.append(",\"timing\":{\"conversionNs\":")
    json.append(String(DispatchTime.now().uptimeNanoseconds - started))
    json.append(",\"decodeNs\":")
    json.append(String(zerocon_conversion_decode_nanoseconds()))
    json.append(",\"decodeCount\":")
    json.append(String(zerocon_conversion_decode_count()))
    json.append(",\"stagesMs\":{")
    let stages = embeddedPerformance.snapshot()
    var wroteStage = false
    for key in stages.keys.sorted() {
        if wroteStage { json.append(",") } else { wroteStage = true }
        appendJsonString(key, to: &json)
        json.append(":")
        json.append(String(stages[key]!))
    }
    json.append("}}")
    if !continuationReading.isEmpty, !continuationSurface.isEmpty {
        json.append(",\"continuation\":{\"readingLength\":")
        json.append(String(continuationReading.count))
        json.append(",\"surfaceLength\":")
        json.append(String(continuationSurface.count))
        json.append(",\"accepted\":")
        json.append(continuationAccepted ? "true" : "false")
        json.append("}")
    }
    json.append("}")
    return json
}

private final class EmbeddedReply: @unchecked Sendable {
    // The semaphore publishes the actor's response to the calling JNI worker.
    let completed = DispatchSemaphore(value: 0)
    var text = "{\"error\":\"invalid engine request\"}"
}

/// JNI calls this only on the Android conversion worker. Swift then executes the
/// unchanged Windows wrapper on its own actor, never on Android's main thread.
@_cdecl("zerocon_swift_request")
public func embeddedRequest(_ value: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    let data = Data(String(cString: value).utf8)
    let reply = EmbeddedReply()
    Task { @MainActor in
        defer { reply.completed.signal() }
        do {
            reply.text = try embeddedDispatchJson(data)
        } catch {
            let output = try? JSONSerialization.data(withJSONObject: ["error": String(describing: error)])
            if let output { reply.text = String(decoding: output, as: UTF8.self) }
        }
    }
    reply.completed.wait()
    return _strdup(reply.text)
}

@_cdecl("zerocon_swift_free")
public func embeddedFree(_ value: UnsafeMutablePointer<CChar>?) { free_c_string(value) }

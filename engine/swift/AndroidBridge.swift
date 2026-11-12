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

/// Reads the server's learning counters through its C entry point, so the bridge
/// never grows a second copy of what counts as learned.
@MainActor private func embeddedLearningStats() -> [String: Any] {
    guard let json = get_learning_stats() else { return [:] }
    defer { free_c_string(json) }
    guard let data = String(cString: json).data(using: .utf8),
          let stats = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        return [:]
    }
    return stats
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

@MainActor private func embeddedDispatch(_ data: Data) throws -> [String: Any] {
    guard let request = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let op = request["op"] as? String else { return ["error": "invalid request"] }
    if op == "configure" {
        guard let root = request["root"] as? String, let model = request["modelPath"] as? String else {
            return ["error": "missing runtime paths"]
        }
        config["embeddedModelPath"] = model
        set_server_log_callbacks(nil, nil, { embeddedPerformance.isEnabled() }, nil,
            { _, _, stage, elapsed, _ in embeddedPerformance.record(stage, elapsed) }, nil, nil, nil)
        if !embeddedInitialized {
            root.withCString { initialize(path: $0, use_zenzai: true) }
            embeddedInitialized = true
        } else { load_config() }
        config["embeddedModelPath"] = model
        return ["ready": true]
    }
    guard embeddedInitialized else { return ["error": "engine not initialized"] }
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
        return ["learned": commit_learning_candidate(candidateId: candidateId, commitKind: 0)]
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
        clear_text()
        converter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        normalNBestSupplementConverter = KanaKanjiConverter(dictionaryURL: converterDictionaryURL, preloadDictionary: false)
        return [:]
    }
    if op == "resetLearning" {
        let reset = reset_learning_memory()
        return ["reset": reset, "stats": embeddedLearningStats()]
    }
    if op == "learningStats" { return embeddedLearningStats() }
    guard op == "convert", let elements = request["elements"] as? [[String: Any]],
          elements.count <= 4096 else { return ["error": "invalid conversion request"] }
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
        return ["error": "composition mismatch", "reading": reading]
    }
    composingText = snapshot
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
    guard !zerocon_conversion_cancelled() else { return ["cancelled": true] }
    let started = DispatchTime.now().uptimeNanoseconds
    var length: CInt = 0
    // No caller-supplied raw-input boundary: use the snapshot's cursor prefix.
    // Zero is an exact empty boundary in the shared API, not an unspecified one.
    let candidates = request["allowAI"] as? Bool == false
        ? get_composed_text_for_reconversion(lengthPtr: &length)
        : (snapshot.isAtEndIndex ? get_composed_text(lengthPtr: &length) : get_composed_text_for_cursor_prefix(requiredInputCount: -1, lengthPtr: &length))
    defer { free_candidate_list(candidates, length) }
    if zerocon_conversion_cancelled() {
        // An aborted decode may leave a partially updated KV cache. Reset both
        // converter sessions before the next request, using their existing API.
        // Keep learning candidate IDs from earlier, successfully displayed batches.
        converter.stopComposition()
        normalNBestSupplementConverter.stopComposition()
        return ["cancelled": true]
    }
    var result: [[String: Any]] = []
    var seenKeys = Set<String>()
    let boundaries = snapshot.inputIndexToSurfaceIndexMap()
    for index in 0..<Int(length) {
        guard let candidate = candidates[index]?.pointee else { continue }
        guard let consumed = boundaries[Int(candidate.correspondingCount)],
              consumed > 0, consumed <= snapshot.convertTargetCursorPosition else { continue }
        let end = reading.index(reading.startIndex, offsetBy: consumed)
        let consumedUtf16 = reading[..<end].utf16.count
        let text = candidate.text.map { String(cString: $0) } ?? ""
        let suffix = candidate.subtext.map { String(cString: $0) } ?? ""
        let key = "\(consumedUtf16):\(text)"
        guard seenKeys.insert(key).inserted else { continue }
        result.append(["text": text, "suffix": suffix, "candidateId": String(candidate.candidateId),
                       "correspondingCount": candidate.correspondingCount,
                       "consumedReadingUtf16": consumedUtf16,
                       "learningAllowed": learningCandidateCache.isLearningTarget(candidate.candidateId)])
    }
    var response: [String: Any] = ["reading": reading, "cursorUtf16": cursorUtf16, "candidates": result,
            "timing": ["conversionNs": DispatchTime.now().uptimeNanoseconds - started,
                       "decodeNs": zerocon_conversion_decode_nanoseconds(),
                       "decodeCount": zerocon_conversion_decode_count(),
                       "stagesMs": embeddedPerformance.snapshot()]]
    if !continuationReading.isEmpty, !continuationSurface.isEmpty {
        response["continuation"] = ["readingLength": continuationReading.count,
                                    "surfaceLength": continuationSurface.count,
                                    "accepted": continuationAccepted]
    }
    return response
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
            let result = try embeddedDispatch(data)
            let output = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
            reply.text = String(decoding: output, as: UTF8.self)
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

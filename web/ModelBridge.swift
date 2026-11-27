import Foundation
import KanaKanjiConverterModule
import WASILibc

func _strdup(_ value: String) -> UnsafeMutablePointer<CChar>? {
    value.withCString { strdup($0) }
}

private struct Command: Decodable {
    let key: String
    let value: String?
    let offset: Int32?
    let candidateId: String?
    let inputCount: Int32?
    let skipCandidates: Bool?
}

private struct Row: Encodable {
    let text: String
    let suffix: String
    let reading: String
    let inputCount: Int32
    let id: String
    let predictionReading: String
    let learningAllowed: Bool
}

private struct State: Encodable {
    let reading: String
    let raw: String
    let cursor: Int32
    let inputCount: Int
    let readingCount: Int
    let candidates: [Row]
    let modelStatus: String
    let learningChanged: Bool?
    let adjustedInputCount: Int32?
}

// The host uses this instance from a single conversion Worker.
nonisolated(unsafe) private var correctionUndo: ComposingText?

private func field(_ pointer: UnsafeMutablePointer<CChar>?) -> String {
    pointer.map { String(cString: $0) } ?? ""
}

@_cdecl("zerocon_model_request")
public func modelRequest(_ address: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let address, let payload = String(validatingCString: address), payload.utf8.count <= 8192,
          let command = try? JSONDecoder().decode(Command.self, from: Data(payload.utf8)) else { return nil }
    var cursor: Int32 = 0
    var result: UnsafeMutablePointer<CChar>?
    var learningChanged: Bool?
    var adjustedInputCount: Int32?
    if ["append", "appendDirect", "replacePreviousDirect", "backspace", "delete", "move", "adjustClause", "shrink", "clear"].contains(command.key) {
        correctionUndo = nil
    }
    switch command.key {
    case "initialize":
        "/runtime".withCString { initialize(path: $0, use_zenzai: true) }
    case "append":
        guard let value = command.value, !value.contains("\0"), value.count <= 256 else { return nil }
        result = value.withCString { append_text(input: $0, cursorPtr: &cursor) }
    case "appendDirect", "replacePreviousDirect":
        guard let value = command.value, !value.isEmpty, !value.contains("\0"), value.count <= 256 else { return nil }
        if command.key == "replacePreviousDirect" {
            guard value.count == 1, get_cursor_position() > 0 else { return nil }
            free_c_string(remove_text(cursorPtr: &cursor))
        }
        result = value.withCString { append_text_direct(input: $0, cursorPtr: &cursor) }
    case "applyCorrection":
        guard correctionUndo == nil, let value = command.value, !value.isEmpty,
              !value.contains("\0"), value.count <= 256 else { return nil }
        let original = composingText
        clear_text()
        result = value.withCString { append_text_direct(input: $0, cursorPtr: &cursor) }
        correctionUndo = original
    case "undoCorrection":
        guard let original = correctionUndo else { return nil }
        clear_text()
        composingText = original
        correctionUndo = nil
    case "backspace": result = remove_text(cursorPtr: &cursor)
    case "delete": result = delete_forward_text(cursorPtr: &cursor)
    case "move": result = move_cursor(offset: command.offset ?? 0, cursorPtr: &cursor)
    case "adjustClause":
        guard let count = command.inputCount, let direction = command.offset, direction == -1 || direction == 1,
              let raw = command.value, !raw.contains("\0"), raw.count <= 256 else { return nil }
        var applied: Int32 = 0
        var boundary: Int32 = 0
        result = raw.withCString {
            adjust_clause_boundary(currentInputCount: count, direction: direction, expectedRawInput: $0,
                                   appliedPtr: &applied, adjustedInputCountPtr: &boundary, cursorOffsetPtr: &cursor)
        }
        if applied != 0 { adjustedInputCount = boundary }
    case "shrink": result = shrink_text(offset: command.offset ?? 0)
    case "context":
        guard let value = command.value, !value.contains("\0"), value.count <= 512 else { return nil }
        value.withCString { set_context(context: $0) }
    case "clear": clear_text()
    case "reloadSettings": load_config()
    case "recordLearning", "forgetLearning":
        guard let value = command.candidateId, let id = UInt64(value) else { return nil }
        learningChanged = command.key == "recordLearning"
            ? commit_learning_candidate(candidateId: id, commitKind: command.offset ?? 0)
            : forget_learning_candidate(candidateId: id)
    case "clearLearning": learningChanged = reset_learning_memory()
    default: return nil
    }
    if let result { free_c_string(result) }

    let rawAddress = get_raw_input()
    let raw = field(rawAddress)
    if let rawAddress { free_c_string(rawAddress) }
    let caret = get_cursor_position()
    let reading = composingText.convertTarget
    let rows: [Row]
    if command.skipCandidates == true || reading.isEmpty || ["clear", "recordLearning"].contains(command.key) {
        rows = []
    } else {
        var count: CInt = 0
        let candidates = caret == reading.count ? get_composed_text(lengthPtr: &count)
            : get_composed_text_for_cursor_range(lengthPtr: &count)
        defer { free_candidate_list(candidates, count) }
        rows = (0..<Int(count)).compactMap { index -> Row? in
            guard let row = candidates[index]?.pointee else { return nil }
            return Row(text: field(row.text), suffix: field(row.subtext), reading: field(row.hiragana),
                       inputCount: row.correspondingCount, id: String(row.candidateId),
                       predictionReading: field(row.predictionReading),
                       learningAllowed: learningCandidateCache.isLearningTarget(row.candidateId))
        }
    }
    let state = State(reading: reading, raw: raw, cursor: caret,
                      inputCount: composingText.input.count, readingCount: reading.count,
                      candidates: rows, modelStatus: converter.zenzStatus, learningChanged: learningChanged,
                      adjustedInputCount: adjustedInputCount)
    guard let data = try? JSONEncoder().encode(state) else { return nil }
    return _strdup(String(decoding: data, as: UTF8.self))
}

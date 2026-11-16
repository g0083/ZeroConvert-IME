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
}

private struct Row: Encodable {
    let text: String
    let suffix: String
    let inputCount: Int32
    let id: String
    let predictionReading: String
}

private struct State: Encodable {
    let reading: String
    let raw: String
    let cursor: Int32
    let candidates: [Row]
    let modelStatus: String
}

private func field(_ pointer: UnsafeMutablePointer<CChar>?) -> String {
    pointer.map { String(cString: $0) } ?? ""
}

@_cdecl("zerocon_model_request")
public func modelRequest(_ address: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let address, let payload = String(validatingCString: address), payload.utf8.count <= 8192,
          let command = try? JSONDecoder().decode(Command.self, from: Data(payload.utf8)) else { return nil }
    var cursor: Int32 = 0
    var result: UnsafeMutablePointer<CChar>?
    switch command.key {
    case "initialize":
        "/runtime".withCString { initialize(path: $0, use_zenzai: true) }
    case "append":
        guard let value = command.value, !value.contains("\0"), value.count <= 256 else { return nil }
        result = value.withCString { append_text(input: $0, cursorPtr: &cursor) }
    case "backspace": result = remove_text(cursorPtr: &cursor)
    case "delete": result = delete_forward_text(cursorPtr: &cursor)
    case "move": result = move_cursor(offset: command.offset ?? 0, cursorPtr: &cursor)
    case "shrink": result = shrink_text(offset: command.offset ?? 0)
    case "context":
        guard let value = command.value, !value.contains("\0"), value.count <= 512 else { return nil }
        value.withCString { set_context(context: $0) }
    case "clear": clear_text()
    default: return nil
    }
    if let result { free_c_string(result) }

    let rawAddress = get_raw_input()
    let raw = field(rawAddress)
    if let rawAddress { free_c_string(rawAddress) }
    var count: CInt = 0
    let caret = get_cursor_position()
    let reading = composingText.convertTarget
    let candidates = caret == reading.unicodeScalars.count ? get_composed_text(lengthPtr: &count)
        : get_composed_text_for_cursor_range(lengthPtr: &count)
    defer { free_candidate_list(candidates, count) }
    let rows = (0..<Int(count)).compactMap { index -> Row? in
        guard let row = candidates[index]?.pointee else { return nil }
        return Row(text: field(row.text), suffix: field(row.subtext),
                   inputCount: row.correspondingCount, id: String(row.candidateId),
                   predictionReading: field(row.predictionReading))
    }
    let state = State(reading: reading, raw: raw, cursor: caret,
                      candidates: rows, modelStatus: converter.zenzStatus)
    guard let data = try? JSONEncoder().encode(state) else { return nil }
    return _strdup(String(decoding: data, as: UTF8.self))
}

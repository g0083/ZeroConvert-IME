import Foundation
import KanaKanjiConverterModule
import WASILibc

private let root = URL(filePath: "/runtime", directoryHint: .isDirectory)

// The browser creates one Wasm instance per worker and calls it serially.
private nonisolated(unsafe) let converter = KanaKanjiConverter(
    dictionaryURL: root.appendingPathComponent("Dictionary"), preloadDictionary: false
)
private let options = ConvertRequestOptions(
    requireJapanesePrediction: .disabled, requireEnglishPrediction: .disabled,
    keyboardLanguage: .ja_JP, learningType: .nothing, memoryDirectoryURL: root,
    sharedContainerURL: root, textReplacer: .empty, specialCandidateProviders: nil,
    zenzaiMode: .off, metadata: nil
)

private struct Reply: Encodable {
    let reading: String
    let candidates: [String]
}

@_cdecl("zerocon_dictionary_convert")
public func dictionaryConvert(_ address: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let address, let raw = String(validatingCString: address), raw.utf8.count <= 1024 else {
        return nil
    }
    var input = ComposingText()
    input.insertAtCursorPosition(raw, inputStyle: .roman2kana)
    let results = input.convertTarget.isEmpty ? [] : converter.requestCandidates(input, options: options).mainResults
    var seen = Set<String>()
    var candidates: [String] = []
    for result in results where seen.insert(result.text).inserted {
        candidates.append(result.text)
        if candidates.count == 8 { break }
    }
    let reply = Reply(reading: input.convertTarget, candidates: candidates)
    guard let encoded = try? JSONEncoder().encode(reply) else { return nil }
    return String(decoding: encoded, as: UTF8.self).withCString { strdup($0) }
}

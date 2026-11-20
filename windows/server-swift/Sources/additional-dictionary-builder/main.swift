import Foundation
import KanaKanjiConverterModule

enum BuilderError: Error {
    case usage
    case invalidEntry(Int)
}

func buildDictionary() throws {
    let arguments = CommandLine.arguments
    guard arguments.count == 4 else { throw BuilderError.usage }
    let source = URL(fileURLWithPath: arguments[1])
    let output = URL(fileURLWithPath: arguments[2], isDirectory: true)
    let characterIDs = URL(fileURLWithPath: arguments[3])
    let entries = try String(contentsOf: source, encoding: .utf8)
        .split(separator: "\n").enumerated().map { index, line in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count == 6, !fields[0].isEmpty, !fields[1].isEmpty,
                  let lcid = UInt16(fields[2]), let rcid = UInt16(fields[3]),
                  let mid = UInt16(fields[4]), let score = Float(fields[5]),
                  score.isFinite, score <= 0
            else { throw BuilderError.invalidEntry(index + 1) }
            return DicdataElement(
                word: String(fields[1]), ruby: String(fields[0]),
                lcid: Int(lcid), rcid: Int(rcid), mid: Int(mid), value: score
            )
        }
    try DictionaryBuilder.exportDictionary(
        entries: entries, to: output, baseName: "",
        shardByFirstCharacter: true, charIDFileURL: characterIDs
    )
}

do {
    try buildDictionary()
} catch {
    FileHandle.standardError.write(Data("Dictionary build failed: \(error)\n".utf8))
    exit(1)
}

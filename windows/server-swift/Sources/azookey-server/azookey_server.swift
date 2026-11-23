import KanaKanjiConverterModule
import Foundation
import Dispatch
import ffi

private func executableDirectoryURL() -> URL? {
    guard let executablePath = CommandLine.arguments.first, !executablePath.isEmpty else {
        return nil
    }
    return URL(filePath: executablePath).deletingLastPathComponent()
}

private let fallbackDictionaryURL =
    executableDirectoryURL()?.appendingPathComponent("Dictionary", isDirectory: true)
    ?? URL(filePath: FileManager.default.currentDirectoryPath)

/// 日本語辞書は設定アプリ/設定画面が初回起動時に端末キャッシュへ取得する
/// （配布物には同梱しない）。解決順は次のとおり。
/// 1. `ZEROCON_DICTIONARY_DIR`（Android の JNI が設定する展開済み辞書）
/// 2. 実行ファイル横の `Dictionary\`（`Build-Trial -DictionaryOnly` の比較用）
/// 3. `%LOCALAPPDATA%\ZeroConIME\dictionary\Dictionary`（取得済みキャッシュ）
/// 3 は展開完了を示す `.complete` を伴うときだけ採用する。
func dictionaryDirectoryURL(executableDirectory: URL?) -> URL {
    let installed = executableDirectory?.appendingPathComponent("Dictionary", isDirectory: true)
        ?? URL(filePath: FileManager.default.currentDirectoryPath)
    let environment = ProcessInfo.processInfo.environment
    if let explicit = environment["ZEROCON_DICTIONARY_DIR"], !explicit.isEmpty {
        let directory = URL(filePath: explicit, directoryHint: .isDirectory)
        if FileManager.default.fileExists(atPath: directory.appendingPathComponent("louds/charID.chid").path) {
            return directory
        }
    }
    if FileManager.default.fileExists(atPath: installed.appendingPathComponent("louds/charID.chid").path) {
        return installed
    }
    guard let localAppData = environment["LOCALAPPDATA"], !localAppData.isEmpty else {
        return installed
    }
    let cacheRoot = URL(filePath: localAppData, directoryHint: .isDirectory)
        .appendingPathComponent("ZeroConIME", isDirectory: true)
        .appendingPathComponent("dictionary", isDirectory: true)
    let markFile = cacheRoot.appendingPathComponent(".complete", isDirectory: false)
    let cache = cacheRoot.appendingPathComponent("Dictionary", isDirectory: true)
    guard FileManager.default.fileExists(atPath: markFile.path),
          FileManager.default.fileExists(
            atPath: cache.appendingPathComponent("louds/charID.chid").path
          ) else {
        return installed
    }
    return cache
}

let resolvedDictionaryURL = dictionaryDirectoryURL(executableDirectory: executableDirectoryURL())

// Serialized by the single-threaded FFI executor in Rust and @MainActor in tests.
nonisolated(unsafe) var converterDictionaryURL = resolvedDictionaryURL
nonisolated(unsafe) var converterPreloadDictionary = false
nonisolated(unsafe) var converterDicdataStore = DicdataStore(
    dictionaryURL: resolvedDictionaryURL,
    preloadDictionary: false
)
nonisolated(unsafe) var converter = KanaKanjiConverter(dicdataStore: converterDicdataStore)
nonisolated(unsafe) var normalNBestSupplementConverter = KanaKanjiConverter(
    dicdataStore: converterDicdataStore
)
nonisolated(unsafe) var composingText = ComposingText()
nonisolated(unsafe) var composingTextSnapshots: [ComposingText] = []
nonisolated(unsafe) var currentInputStyle: InputStyle = .roman2kana
nonisolated(unsafe) var customRomajiTableEnabled = false
nonisolated(unsafe) var currentLearningType: LearningType = .inputAndOutput
nonisolated(unsafe) private(set) var privateInputActive = false
nonisolated(unsafe) var currentLearningMemoryDirectoryURL: URL?
nonisolated(unsafe) var learningCandidateCache = LearningCandidateCache()
nonisolated(unsafe) var learningSelectionOverrides = LearningSelectionStore()
nonisolated(unsafe) var reconversionDictionary = ReconversionDictionary()

nonisolated(unsafe) var execURL = URL(filePath: "")
nonisolated(unsafe) var config: [String : Any] = [
    "enable": false,
    "profile": "",
    "backend": "cpu",
    "experimentalTypoCorrection": defaultKeyboardTypoCorrection,
]
let defaultKeyboardTypoCorrection = true
let maxUserDictionaryEntryCount = 10_000
let minInputCountForZenzaiCandidates = 4
let minHiraganaCountForZenzaiCandidates = 2
let zenzaiWarmupRomanInput = "nihongo"
let warmupRequestCandidatesWarningMs = 5_000
// Request exact-clause supplements only when boundary-matched candidates are sparse.
let cursorPrefixExactClauseSupplementCandidateThreshold = 5
// Bound bulk candidate generations while retaining the current clause plus at
// most 16 prepared future clauses with more than 2x slot headroom at the
// observed high-water mark of 201 candidates per generation. Past clauses can
// outlive this ring, so their one selected candidate is pinned separately until
// the composition is cleared.
let maxLearningCandidateCacheBatchCount = 64
let maxLearningCandidateCacheSlotCount = 8_192
let maxLearningSelectionOverrideCount = 4_096
let maxOutputsPerRuby = 6
private let maxContextsPerRecord = 4
let learningSelectionOverridesFilename = "selection-overrides.json"
let backgroundWarmupPreloadsDictionary = false
let maxReconversionSurfaceCount = 128
let maxReconversionDictionarySpan = 40
let maxReconversionReadingCount = 4

nonisolated(unsafe) var currentRequestId: UInt64 = 0

struct ReconversionDictionaryEntry: Equatable, Sendable {
    let reading: String
    let score: Float
}

struct ReconversionReadingPiece: Equatable, Sendable {
    let surface: String
    let reading: String
    let score: Float
}

struct ReconversionDictionary {
    private static let magic = Array("AZR2".utf8)
    private var data = Data()
    private(set) var recordCount = 0
    private var userEntries: [String: [ReconversionDictionaryEntry]] = [:]

    mutating func load(from dictionaryURL: URL) throws {
        let url = dictionaryURL
            .appendingPathComponent("Reverse", isDirectory: true)
            .appendingPathComponent("reverse-v2.bin", isDirectory: false)
        let loaded = try Data(contentsOf: url, options: [.mappedIfSafe])
        guard loaded.count >= 12,
              Array(loaded.prefix(4)) == Self.magic else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let count = Int(Self.readUInt32LE(loaded, at: 4))
        let headerEnd = 8 + (count + 1) * 4
        guard count > 0, headerEnd <= loaded.count,
              Int(Self.readUInt32LE(loaded, at: 8)) == headerEnd,
              Int(Self.readUInt32LE(loaded, at: 8 + count * 4)) == loaded.count else {
            throw CocoaError(.fileReadCorruptFile)
        }
        data = loaded
        recordCount = count
    }

    mutating func replacePreparedUserEntries(_ entries: [String: [ReconversionDictionaryEntry]]) {
        userEntries = entries
    }

    mutating func replaceUserEntries(_ dictionaryEntries: [DicdataElement]) {
        replacePreparedUserEntries(Self.makeUserEntries(dictionaryEntries))
    }

    static func makeUserEntries(_ dictionaryEntries: [DicdataElement]) -> [String: [ReconversionDictionaryEntry]] {
        Dictionary(grouping: dictionaryEntries, by: \.word).mapValues { entries in
            Dictionary(
                entries.map {
                    ($0.ruby, ReconversionDictionaryEntry(reading: $0.ruby, score: -4))
                },
                uniquingKeysWith: { first, _ in first }
            ).values.sorted { $0.reading < $1.reading }
        }
    }

    func inferReadings(
        for surface: String,
        limit: Int = maxReconversionReadingCount
    ) -> [String] {
        let characters = Array(surface)
        guard !characters.isEmpty,
              characters.count <= maxReconversionSurfaceCount,
              limit > 0 else {
            return []
        }

        let exactEntries = entries(for: surface)
        if !exactEntries.isEmpty {
            return exactEntries
                .sorted {
                    if $0.score != $1.score {
                        return $0.score > $1.score
                    }
                    return $0.reading < $1.reading
                }
                .prefix(limit)
                .map(\.reading)
        }

        struct Path {
            var score: Float
            var reading: String
        }

        var paths: [[Path]] = Array(repeating: [], count: characters.count + 1)
        paths[0] = [Path(score: 0, reading: "")]
        func insert(_ candidate: Path, at index: Int) {
            if let existing = paths[index].firstIndex(where: { $0.reading == candidate.reading }) {
                if candidate.score > paths[index][existing].score {
                    paths[index][existing] = candidate
                }
            } else {
                paths[index].append(candidate)
            }
            paths[index].sort {
                if $0.score != $1.score {
                    return $0.score > $1.score
                }
                return $0.reading < $1.reading
            }
            if paths[index].count > limit {
                paths[index].removeLast(paths[index].count - limit)
            }
        }

        for start in characters.indices {
            guard !paths[start].isEmpty else {
                continue
            }

            let sourcePaths = paths[start]
            for path in sourcePaths {
                if Self.canPassThrough(characters[start]) {
                    insert(
                        Path(
                            score: path.score - 0.05,
                            reading: path.reading + Self.normalizedLiteral(characters[start])
                        ),
                        at: start + 1
                    )
                }

                let upperBound = min(characters.count, start + maxReconversionDictionarySpan)
                guard start < upperBound else {
                    continue
                }
                var word = ""
                for end in (start + 1)...upperBound {
                    word.append(characters[end - 1])
                    for entry in entries(for: word) {
                        insert(
                            Path(
                                score: path.score + entry.score - 0.2,
                                reading: path.reading + entry.reading
                            ),
                            at: end
                        )
                    }
                }
            }
        }
        return paths[characters.count].map(\.reading)
    }

    func inferReadingPieces(
        for surface: String,
        limit: Int = maxReconversionReadingCount
    ) -> (pieces: [ReconversionReadingPiece], score: Float, secondBestScore: Float?)? {
        let characters = Array(surface)
        guard !characters.isEmpty,
              characters.count <= maxReconversionSurfaceCount,
              limit > 0 else {
            return nil
        }

        struct Path {
            var score: Float
            var reading: String
            var pieces: [ReconversionReadingPiece]
        }

        var paths: [[Path]] = Array(repeating: [], count: characters.count + 1)
        paths[0] = [Path(score: 0, reading: "", pieces: [])]
        func insert(_ candidate: Path, at index: Int) {
            if let existing = paths[index].firstIndex(where: { $0.reading == candidate.reading }) {
                if candidate.score > paths[index][existing].score {
                    paths[index][existing] = candidate
                }
            } else {
                paths[index].append(candidate)
            }
            paths[index].sort {
                if $0.score != $1.score {
                    return $0.score > $1.score
                }
                return $0.reading < $1.reading
            }
            if paths[index].count > limit {
                paths[index].removeLast(paths[index].count - limit)
            }
        }

        for start in characters.indices {
            guard !paths[start].isEmpty else {
                continue
            }

            let sourcePaths = paths[start]
            for path in sourcePaths {
                if Self.canPassThrough(characters[start]) {
                    let literal = Self.normalizedLiteral(characters[start])
                    insert(
                        Path(
                            score: path.score - 0.05,
                            reading: path.reading + literal,
                            pieces: path.pieces + [
                                ReconversionReadingPiece(
                                    surface: String(characters[start]),
                                    reading: literal,
                                    score: -0.05
                                )
                            ]
                        ),
                        at: start + 1
                    )
                }

                let upperBound = min(characters.count, start + maxReconversionDictionarySpan)
                guard start < upperBound else {
                    continue
                }
                var word = ""
                for end in (start + 1)...upperBound {
                    word.append(characters[end - 1])
                    for entry in entries(for: word) {
                        insert(
                            Path(
                                score: path.score + entry.score - 0.2,
                                reading: path.reading + entry.reading,
                                pieces: path.pieces + [
                                    ReconversionReadingPiece(
                                        surface: word,
                                        reading: entry.reading,
                                        score: entry.score
                                    )
                                ]
                            ),
                            at: end
                        )
                    }
                }
            }
        }
        let results = paths[characters.count]
        guard let best = results.first else {
            return nil
        }
        return (
            pieces: best.pieces,
            score: best.score,
            secondBestScore: results.count > 1 ? results[1].score : nil
        )
    }

    private static func canPassThrough(_ character: Character) -> Bool {
        !character.unicodeScalars.contains { scalar in
            switch scalar.value {
            case 0x3005...0x3007, 0x303b,
                 0x3400...0x4dbf, 0x4e00...0x9fff, 0xf900...0xfaff,
                 0x20000...0x2fa1f:
                return true
            default:
                return false
            }
        }
    }

    private static func normalizedLiteral(_ character: Character) -> String {
        hiraganaToKatakana(String(character))
    }

    private func entries(for surface: String) -> [ReconversionDictionaryEntry] {
        var result = userEntries[surface] ?? []
        guard recordCount > 0 else {
            return result
        }
        let target = Array(surface.utf8)
        var lowerBound = 0
        var upperBound = recordCount
        while lowerBound < upperBound {
            let middle = lowerBound + (upperBound - lowerBound) / 2
            guard let record = record(at: middle) else {
                return result
            }
            let comparison = compareBytes(record.surface, target)
            if comparison < 0 {
                lowerBound = middle + 1
            } else {
                upperBound = middle
            }
        }
        var index = lowerBound
        while index < recordCount, let record = record(at: index), compareBytes(record.surface, target) == 0 {
            let reading = String(decoding: record.reading, as: UTF8.self)
            if !result.contains(where: { $0.reading == reading }) {
                result.append(ReconversionDictionaryEntry(reading: reading, score: record.score))
            }
            index += 1
        }
        return result
    }

    private func record(at index: Int) -> (surface: Data.SubSequence, reading: Data.SubSequence, score: Float)? {
        guard (0..<recordCount).contains(index) else {
            return nil
        }
        let start = Int(Self.readUInt32LE(data, at: 8 + index * 4))
        let end = Int(Self.readUInt32LE(data, at: 8 + (index + 1) * 4))
        guard start >= 8 + (recordCount + 1) * 4,
              end >= start + 8,
              end <= data.count else {
            return nil
        }
        let surfaceLength = Int(Self.readUInt16LE(data, at: start))
        let readingLength = Int(Self.readUInt16LE(data, at: start + 2))
        let surfaceStart = start + 8
        let readingStart = surfaceStart + surfaceLength
        guard readingStart + readingLength == end else {
            return nil
        }
        return (
            data[surfaceStart..<readingStart],
            data[readingStart..<end],
            Self.readFloat32LE(data, at: start + 4)
        )
    }

    private func compareBytes(_ left: Data.SubSequence, _ right: [UInt8]) -> Int {
        let commonCount = min(left.count, right.count)
        for index in 0..<commonCount {
            let leftByte = left[left.startIndex + index]
            let rightByte = right[index]
            if leftByte != rightByte {
                return leftByte < rightByte ? -1 : 1
            }
        }
        if left.count == right.count {
            return 0
        }
        return left.count < right.count ? -1 : 1
    }

    private static func readUInt16LE(_ data: Data, at offset: Int) -> UInt16 {
        UInt16(data[data.startIndex + offset])
            | (UInt16(data[data.startIndex + offset + 1]) << 8)
    }

    private static func readUInt32LE(_ data: Data, at offset: Int) -> UInt32 {
        UInt32(data[data.startIndex + offset])
            | (UInt32(data[data.startIndex + offset + 1]) << 8)
            | (UInt32(data[data.startIndex + offset + 2]) << 16)
            | (UInt32(data[data.startIndex + offset + 3]) << 24)
    }

    private static func readFloat32LE(_ data: Data, at offset: Int) -> Float {
        Float(bitPattern: readUInt32LE(data, at: offset))
    }
}

public typealias ServerLogEnabledCallback = @convention(c) () -> Bool
public typealias ServerLogLevelEnabledCallback = @convention(c) (
    UnsafePointer<CChar>?
) -> Bool
public typealias ServerLogWriteCallback = @convention(c) (
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?
) -> Void
public typealias ServerPerformanceLogWriteCallback = @convention(c) (
    UInt64,
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?,
    UInt64,
    UnsafePointer<CChar>?
) -> Void
public typealias ServerLogFlushCallback = @convention(c) () -> Void
public typealias ServerCrashTraceWriteCallback = @convention(c) (
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?
) -> Void

private final class ServerLogCallbacks: @unchecked Sendable {
    private let lock = NSLock()
    private var logEnabled: ServerLogEnabledCallback?
    private var logLevelEnabled: ServerLogLevelEnabledCallback?
    private var performanceLogEnabled: ServerLogEnabledCallback?
    private var writeLog: ServerLogWriteCallback?
    private var writePerformanceLog: ServerPerformanceLogWriteCallback?
    private var flushLog: ServerLogFlushCallback?
    private var crashTraceEnabled: ServerLogEnabledCallback?
    private var writeCrashTrace: ServerCrashTraceWriteCallback?

    func configure(
        logEnabled: ServerLogEnabledCallback?,
        logLevelEnabled: ServerLogLevelEnabledCallback?,
        performanceLogEnabled: ServerLogEnabledCallback?,
        writeLog: ServerLogWriteCallback?,
        writePerformanceLog: ServerPerformanceLogWriteCallback?,
        flushLog: ServerLogFlushCallback?,
        crashTraceEnabled: ServerLogEnabledCallback?,
        writeCrashTrace: ServerCrashTraceWriteCallback?
    ) {
        lock.lock()
        self.logEnabled = logEnabled
        self.logLevelEnabled = logLevelEnabled
        self.performanceLogEnabled = performanceLogEnabled
        self.writeLog = writeLog
        self.writePerformanceLog = writePerformanceLog
        self.flushLog = flushLog
        self.crashTraceEnabled = crashTraceEnabled
        self.writeCrashTrace = writeCrashTrace
        lock.unlock()
    }

    func isLogEnabled(level: String) -> Bool {
        lock.lock()
        let fallbackCallback = logEnabled
        let levelCallback = logLevelEnabled
        lock.unlock()
        if let levelCallback {
            return level.withCString { levelPointer in
                levelCallback(levelPointer)
            }
        }
        return fallbackCallback?() ?? false
    }

    func isPerformanceLogEnabled() -> Bool {
        lock.lock()
        let callback = performanceLogEnabled
        lock.unlock()
        return callback?() ?? false
    }

    func log(level: String, message: String) {
        lock.lock()
        let callback = writeLog
        lock.unlock()

        guard let callback else {
            return
        }

        level.withCString { levelPointer in
            message.withCString { messagePointer in
                callback(levelPointer, messagePointer)
            }
        }
    }

    func performanceLog(
        requestId: UInt64,
        operation: String,
        stage: String,
        elapsedMs: UInt64,
        details: String
    ) {
        lock.lock()
        let callback = writePerformanceLog
        lock.unlock()

        guard let callback else {
            return
        }

        operation.withCString { operationPointer in
            stage.withCString { stagePointer in
                details.withCString { detailsPointer in
                    callback(requestId, operationPointer, stagePointer, elapsedMs, detailsPointer)
                }
            }
        }
    }

    func flush() {
        lock.lock()
        let callback = flushLog
        lock.unlock()

        callback?()
    }

    func isCrashTraceEnabled() -> Bool {
        lock.lock()
        let callback = crashTraceEnabled
        lock.unlock()
        return callback?() ?? false
    }

    func crashTrace(operation: String, stage: String, state: String, details: String) {
        lock.lock()
        let callback = writeCrashTrace
        lock.unlock()

        guard let callback else {
            return
        }

        operation.withCString { operationPointer in
            stage.withCString { stagePointer in
                state.withCString { statePointer in
                    details.withCString { detailsPointer in
                        callback(operationPointer, stagePointer, statePointer, detailsPointer)
                    }
                }
            }
        }
    }
}

private let serverLogCallbacks = ServerLogCallbacks()

@_silgen_name("SetServerLogCallbacks")
public func set_server_log_callbacks(
    _ logEnabled: ServerLogEnabledCallback?,
    _ logLevelEnabled: ServerLogLevelEnabledCallback?,
    _ performanceLogEnabled: ServerLogEnabledCallback?,
    _ writeLog: ServerLogWriteCallback?,
    _ writePerformanceLog: ServerPerformanceLogWriteCallback?,
    _ flushLog: ServerLogFlushCallback?,
    _ crashTraceEnabled: ServerLogEnabledCallback?,
    _ writeCrashTrace: ServerCrashTraceWriteCallback?
) {
    serverLogCallbacks.configure(
        logEnabled: logEnabled,
        logLevelEnabled: logLevelEnabled,
        performanceLogEnabled: performanceLogEnabled,
        writeLog: writeLog,
        writePerformanceLog: writePerformanceLog,
        flushLog: flushLog,
        crashTraceEnabled: crashTraceEnabled,
        writeCrashTrace: writeCrashTrace
    )
}

func serverLog(
    requestId: UInt64,
    _ level: String = "INFO",
    _ message: @autoclosure () -> String,
    flush: Bool = false
) {
    guard !privateInputActive, serverLogCallbacks.isLogEnabled(level: level) else {
        return
    }

    serverLogCallbacks.log(level: level, message: "request_id=\(requestId) \(message())")
    if flush {
        serverLogCallbacks.flush()
    }
}

func serverLog(
    _ level: String = "INFO",
    _ message: @autoclosure () -> String,
    flush: Bool = false
) {
    serverLog(requestId: currentRequestId, level, message(), flush: flush)
}

private func crashTrace(
    requestId: UInt64,
    operation: String,
    stage: String,
    state: String,
    details: @autoclosure () -> String = ""
) {
    guard !privateInputActive, serverLogCallbacks.isCrashTraceEnabled() else {
        return
    }

    serverLogCallbacks.crashTrace(
        operation: operation,
        stage: stage,
        state: state,
        details: "request_id=\(requestId);\(details())"
    )
}

private func crashTrace(
    operation: String,
    stage: String,
    state: String,
    details: @autoclosure () -> String = ""
) {
    crashTrace(
        requestId: currentRequestId,
        operation: operation,
        stage: stage,
        state: state,
        details: details()
    )
}

private func candidateCrashTrace(
    useZenzai: Bool,
    operation: String,
    stage: String,
    state: String,
    details: @autoclosure () -> String = ""
) {
    guard useZenzai else {
        return
    }

    crashTrace(operation: operation, stage: stage, state: state, details: details())
}

private func performanceLog(
    requestId: UInt64,
    operation: String,
    stage: String,
    elapsedMs: Int,
    details: @autoclosure () -> String = ""
) {
    guard !privateInputActive, serverLogCallbacks.isPerformanceLogEnabled() else {
        return
    }

    serverLogCallbacks.performanceLog(
        requestId: requestId,
        operation: operation,
        stage: stage,
        elapsedMs: UInt64(max(0, elapsedMs)),
        details: details()
    )
}

private func performanceLog(
    operation: String,
    stage: String,
    elapsedMs: Int,
    details: @autoclosure () -> String = ""
) {
    performanceLog(
        requestId: currentRequestId,
        operation: operation,
        stage: stage,
        elapsedMs: elapsedMs,
        details: details()
    )
}

private func performanceNow() -> TimeInterval {
    ProcessInfo.processInfo.systemUptime
}

private func elapsedPerformanceMilliseconds(since start: TimeInterval) -> Int {
    Int((performanceNow() - start) * 1000)
}

func applicationDataDirectoryURL(
    appDataPath: String?,
    temporaryDirectoryURL: @autoclosure () -> URL
) -> URL {
    if let appDataPath,
       !appDataPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return URL(filePath: appDataPath)
            .appendingPathComponent("Azookey", isDirectory: true)
    }

    return temporaryDirectoryURL().appendingPathComponent("Azookey", isDirectory: true)
}

private func applicationDataDirectoryURL() -> URL {
    applicationDataDirectoryURL(
        appDataPath: ProcessInfo.processInfo.environment["APPDATA"],
        temporaryDirectoryURL: FileManager.default.temporaryDirectory
    )
}

func engineRuntimeDirectoryURL(
    appDataPath: String?,
    temporaryDirectoryURL: @autoclosure () -> URL
) -> URL {
    applicationDataDirectoryURL(
        appDataPath: appDataPath,
        temporaryDirectoryURL: temporaryDirectoryURL()
    ).appendingPathComponent("EngineRuntime", isDirectory: true)
}

private func settingsPath() -> URL {
    applicationDataDirectoryURL().appendingPathComponent("settings.json")
}

private func readAppSettings(at path: URL) throws -> AppSettings {
    let data = try Data(contentsOf: path)
    return try JSONDecoder().decode(AppSettings.self, from: data)
}

private func rebuildConverter() {
    let dicdataStore = DicdataStore(
        dictionaryURL: converterDictionaryURL,
        preloadDictionary: converterPreloadDictionary
    )
    converterDicdataStore = dicdataStore
    converter = KanaKanjiConverter(dicdataStore: dicdataStore)
    normalNBestSupplementConverter = KanaKanjiConverter(dicdataStore: dicdataStore)
    if let installedUserDictionary {
        converter.installPreparedDynamicUserDictionary(installedUserDictionary.conversion)
        normalNBestSupplementConverter.installPreparedDynamicUserDictionary(installedUserDictionary.conversion)
    }
    clearLastCommittedContinuation()
    clearPostCompositionPredictionState()
}

func converterRuntimeDirectoryURL() -> URL {
    engineRuntimeDirectoryURL(
        appDataPath: ProcessInfo.processInfo.environment["APPDATA"],
        temporaryDirectoryURL: FileManager.default.temporaryDirectory
    )
}

#if os(Android)
let defaultPredictiveConversionEnabled = true
#else
let defaultPredictiveConversionEnabled = false
#endif

func isPredictiveConversionEnabled() -> Bool {
    !privateInputActive && ((config["predictiveConversion"] as? Bool) ?? defaultPredictiveConversionEnabled)
}

func isPostCompositionPredictionEnabled() -> Bool {
    guard !privateInputActive else { return false }
    if let explicit = config["postCompositionPrediction"] as? Bool {
        return explicit
    }
    return isPredictiveConversionEnabled()
}

private func ensureConverterRuntimeDirectory() {
    let runtimeDirectoryURL = converterRuntimeDirectoryURL()
    do {
        try FileManager.default.createDirectory(
            at: runtimeDirectoryURL,
            withIntermediateDirectories: true
        )
    } catch {
        serverLog(
            "WARN",
            "Failed to create engine runtime directory \(runtimeDirectoryURL.path): \(error)"
        )
    }
}

func normalizedZenzaiBackend(_ backend: String?) -> String {
    let trimmed = (backend ?? "cpu")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
    return trimmed == "cuda" ? "vulkan" : trimmed
}

private func shouldOffloadZenzaiToGpu(zenzaiEnabled: Bool, backend: String?) -> Bool {
    let normalizedBackend = normalizedZenzaiBackend(backend)
    return zenzaiEnabled && !normalizedBackend.isEmpty && normalizedBackend != "cpu"
}

func canonicalZenzaiModelId(_ raw: String?) -> String {
    switch raw ?? "nano_v4_2_iq4_emb8" {
    case "nano_v4_2_q8", "nano_v4_1_q8", "nano_v4_q8", "standard", "standard_q4km":
        return "nano_v4_2_q8"
    case "standard_v4_2_iq4_emb8", "standard_v4_1_iq4_emb8", "standard_v4_iq4_emb8":
        return "standard_v4_2_iq4_emb8"
    case "standard_v4_2_q8", "standard_v4_1_q8", "standard_v4_q8", "standard_v4_2", "standard_v4_1":
        return "standard_v4_2_q8"
    default:
        return "nano_v4_2_iq4_emb8"
    }
}

func converterModelURL() -> URL {
    if let embeddedPath = config["embeddedModelPath"] as? String {
        return URL(filePath: embeddedPath)
    }
    let filename: String
    switch canonicalZenzaiModelId(config["model"] as? String) {
    case "nano_v4_2_q8":
        filename = "zerocon-nano-v4.2-q8_0.gguf"
    case "standard_v4_2_iq4_emb8":
        filename = "zerocon-standard-v4.2-iq4nl-emb8.gguf"
    case "standard_v4_2_q8":
        filename = "zerocon-standard-v4.2-q8_0.gguf"
    default:
        filename = "zerocon-nano-v4.2-iq4nl-emb8.gguf"
    }
    // 取得済みキャッシュ（%LOCALAPPDATA%\ZeroConIME\models\）を優先し、
    // 旧配布物に残ったモデルがあればそのまま使う。
    if let root = ProcessInfo.processInfo.environment["LOCALAPPDATA"], !root.isEmpty {
        let cached = URL(filePath: root)
            .appendingPathComponent("ZeroConIME/models/\(filename)")
        if FileManager.default.fileExists(atPath: cached.path) { return cached }
    }
    return execURL.appendingPathComponent("models/" + filename)
}

#if os(Windows)
internal let expectedWindowsCpuTuningVersion = 2
nonisolated(unsafe) internal private(set) var lastAppliedWindowsCpuRuntimeTuning: RuntimeTuningProbeConfig?

func resolvedWindowsCpuRuntimeTuning(
    zenzaiEnabled: Bool,
    shouldOffloadToGpu: Bool,
    model: String?,
    tuning: CpuRuntimeTuningSettings?
) -> RuntimeTuningProbeConfig? {
    guard zenzaiEnabled,
          !shouldOffloadToGpu,
          let tuning,
          tuning.tuning_version == expectedWindowsCpuTuningVersion,
          tuning.decode_threads > 0 else {
        return nil
    }
    if let tunedModelId = tuning.model_id, !tunedModelId.isEmpty {
        guard canonicalZenzaiModelId(tunedModelId) == canonicalZenzaiModelId(model) else {
            return nil
        }
    }
    return RuntimeTuningProbeConfig(
        decodeThreads: tuning.decode_threads,
        batchThreads: tuning.batch_threads.flatMap { $0 > 0 ? $0 : nil },
        directModelFirst: tuning.direct_model_first
    )
}
#endif

internal func configureEngineRuntime(zenzaiEnabled: Bool) {
    let shouldOffloadToGpu = shouldOffloadZenzaiToGpu(
        zenzaiEnabled: zenzaiEnabled,
        backend: config["backend"] as? String
    )
    #if os(Windows)
    if let applied = resolvedWindowsCpuRuntimeTuning(
        zenzaiEnabled: zenzaiEnabled,
        shouldOffloadToGpu: shouldOffloadToGpu,
        model: config["model"] as? String,
        tuning: config["cpuRuntimeTuning"] as? CpuRuntimeTuningSettings
    ) {
        ZenzaiRuntimeTuning.configure(
            decodeThreads: applied.decodeThreads,
            batchThreads: applied.batchThreads,
            directModelFirstOverride: applied.directModelFirst
        )
        lastAppliedWindowsCpuRuntimeTuning = applied
    } else {
        ZenzaiRuntimeTuning.reset()
        lastAppliedWindowsCpuRuntimeTuning = nil
    }
    #endif
    KanaKanjiConverterEngineRuntime.configure(
        gpuLayerCount: shouldOffloadToGpu ? resolvedGpuLayerCount(config["gpuLayers"] as? Int) : 0
    )
}

func resolvedGpuLayerCount(_ configured: Int?) -> Int32 {
    Int32(max(0, min(99, configured ?? 99)))
}

private func learningMemoryDirectoryURL(settingsPath: URL?) -> URL {
    if let settingsPath {
        return settingsPath
            .deletingLastPathComponent()
            .appendingPathComponent("LearningMemory", isDirectory: true)
    }

    return applicationDataDirectoryURL()
        .appendingPathComponent("LearningMemory", isDirectory: true)
}

private func normalizedLearningMode(_ mode: String?) -> String {
    (mode ?? "enabled")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .replacingOccurrences(of: "-", with: "_")
}

private func learningType(for mode: String?) -> LearningType {
    switch normalizedLearningMode(mode) {
    case "read_only", "readonly", "no_new_learning":
        return .onlyOutput
    case "disabled":
        return .nothing
    default:
        return .inputAndOutput
    }
}

func ensureLearningMemoryDirectoryIfNeeded() {
    guard currentLearningType != .nothing,
          let currentLearningMemoryDirectoryURL else {
        return
    }

    do {
        try FileManager.default.createDirectory(
            at: currentLearningMemoryDirectoryURL,
            withIntermediateDirectories: true
        )
    } catch {
        serverLog(
            "WARN",
            "Failed to create learning memory directory \(currentLearningMemoryDirectoryURL.path): \(error)"
        )
    }
}

func ensureConverterLearningConfigSynced() {
    ensureLearningMemoryDirectoryIfNeeded()
    converter.syncLearningConfig(
        learningType: currentLearningType,
        memoryDirectoryURL: currentLearningMemoryDirectoryURL
    )
    normalNBestSupplementConverter.syncLearningConfig(
        learningType: currentLearningType,
        memoryDirectoryURL: currentLearningMemoryDirectoryURL
    )
}

private func resetLearningMemoryDirectory() -> Bool {
    guard let currentLearningMemoryDirectoryURL else {
        serverLog("WARN", "ResetLearningMemory: learning memory directory is not configured")
        return false
    }

    do {
        if FileManager.default.fileExists(atPath: currentLearningMemoryDirectoryURL.path) {
            try FileManager.default.removeItem(at: currentLearningMemoryDirectoryURL)
        }
        // Disabled learning never writes, so recreating the directory would leave
        // an empty store behind and make the next run look as if history exists.
        if currentLearningType != .nothing {
            try FileManager.default.createDirectory(
                at: currentLearningMemoryDirectoryURL,
                withIntermediateDirectories: true
            )
        }
        return true
    } catch {
        serverLog(
            "WARN",
            "ResetLearningMemory: failed to reset directory \(currentLearningMemoryDirectoryURL.path): \(error)"
        )
        return false
    }
}

struct LearningCandidateCache {
    private struct Batch {
        let firstId: UInt64
        let candidates: [Candidate]
        let originalRanks: [Int]?
        var consumedOffsets: Set<Int> = []
        var isProtected = false
    }

    private let maxBatchCount: Int
    private let maxSlotCount: Int
    private var batches: [Batch?]
    private var firstBatchIndex = 0
    private var nextCandidateId: UInt64 = 1
    private var idSpaceExhausted = false
    private(set) var slotCount = 0
    private(set) var batchCount = 0
    private(set) var protectedSlotCount = 0

    init(
        maxBatchCount: Int = maxLearningCandidateCacheBatchCount,
        maxSlotCount: Int = maxLearningCandidateCacheSlotCount
    ) {
        precondition(maxBatchCount > 0)
        precondition(maxSlotCount > 0)
        self.maxBatchCount = maxBatchCount
        self.maxSlotCount = maxSlotCount
        self.batches = Array(repeating: nil, count: maxBatchCount)
    }

    mutating func appendBatch(_ candidates: [Candidate], originalRanks: [Int]? = nil) -> UInt64? {
        guard !candidates.isEmpty, !idSpaceExhausted else {
            return nil
        }

        let availableBatchSlotCount = maxSlotCount - protectedSlotCount
        guard availableBatchSlotCount > 0 else {
            return nil
        }
        let retainedCandidates = if candidates.count > availableBatchSlotCount {
            Array(candidates.prefix(availableBatchSlotCount))
        } else {
            candidates
        }
        let candidateCount = UInt64(retainedCandidates.count)
        let (nextId, overflow) = nextCandidateId.addingReportingOverflow(candidateCount)
        if overflow {
            idSpaceExhausted = true
            return nil
        }

        while batchCount == maxBatchCount
            || slotCount > maxSlotCount - retainedCandidates.count
        {
            guard evictOldestUnprotectedBatch() else {
                return nil
            }
        }

        let firstId = nextCandidateId
        let insertionIndex = physicalIndex(forLogicalIndex: batchCount)
        batches[insertionIndex] = Batch(
            firstId: firstId,
            candidates: retainedCandidates,
            originalRanks: originalRanks?.count == candidates.count ? originalRanks : nil
        )
        batchCount += 1
        slotCount += retainedCandidates.count
        nextCandidateId = nextId
        return firstId
    }

    func candidateId(at index: Int, batchFirstId: UInt64) -> UInt64 {
        guard index >= 0,
              batchCount > 0,
              let batch = batches[physicalIndex(forLogicalIndex: batchCount - 1)],
              batch.firstId == batchFirstId,
              index < batch.candidates.count else {
            return 0
        }

        return batchFirstId + UInt64(index)
    }

    mutating func pin(_ candidateId: UInt64) -> Bool {
        guard candidateId > 0 else {
            return false
        }
        guard let location = candidateLocation(for: candidateId),
              !batches[location.batchIndex]!.consumedOffsets.contains(location.candidateIndex)
        else {
            return false
        }
        if batches[location.batchIndex]!.isProtected {
            return true
        }

        // A client snapshot can later select any candidate from this batch.
        // Protect the complete batch so every issued nonzero ID stays valid.
        let candidateCount = batches[location.batchIndex]!.candidates.count
        batches[location.batchIndex]!.isProtected = true
        protectedSlotCount += candidateCount
        return true
    }

    func isLearningTarget(_ candidateId: UInt64) -> Bool {
        guard let location = candidateLocation(for: candidateId) else { return false }
        return batches[location.batchIndex]!.candidates[location.candidateIndex].isLearningTarget
    }

    /// Non-destructive lookup for callers that must validate before consuming.
    ///
    /// A refused operation has to leave the id usable: consuming first would
    /// silently break the later commit and pin of a candidate that was only
    /// rejected because of its flags or the current learning mode.
    func candidate(for candidateId: UInt64) -> Candidate? {
        guard candidateId > 0, let location = candidateLocation(for: candidateId) else {
            return nil
        }
        return batches[location.batchIndex]!.candidates[location.candidateIndex]
    }

    mutating func consume(_ candidateId: UInt64) -> Candidate? {
        consumeWithOriginalRank(candidateId)?.candidate
    }

    mutating func consumeWithOriginalRank(_ candidateId: UInt64) -> (candidate: Candidate, originalRank: Int)? {
        guard candidateId > 0 else {
            return nil
        }
        guard let location = candidateLocation(for: candidateId) else {
            return nil
        }
        guard batches[location.batchIndex]!.consumedOffsets
            .insert(location.candidateIndex).inserted else {
            return nil
        }

        let batch = batches[location.batchIndex]!
        return (batch.candidates[location.candidateIndex], batch.originalRanks?[location.candidateIndex] ?? location.candidateIndex)
    }

    mutating func removeAll() {
        for index in batches.indices {
            batches[index] = nil
        }
        firstBatchIndex = 0
        batchCount = 0
        slotCount = 0
        protectedSlotCount = 0
    }

    mutating func removeAllPins() {
        guard protectedSlotCount > 0 else {
            return
        }
        for logicalIndex in 0..<batchCount {
            batches[physicalIndex(forLogicalIndex: logicalIndex)]!.isProtected = false
        }
        protectedSlotCount = 0
    }

    private func physicalIndex(forLogicalIndex logicalIndex: Int) -> Int {
        (firstBatchIndex + logicalIndex) % maxBatchCount
    }

    private func candidateLocation(
        for candidateId: UInt64
    ) -> (batchIndex: Int, candidateIndex: Int)? {
        var lowerBound = 0
        var upperBound = batchCount
        while lowerBound < upperBound {
            let middle = lowerBound + (upperBound - lowerBound) / 2
            let batch = batches[physicalIndex(forLogicalIndex: middle)]!
            if batch.firstId <= candidateId {
                lowerBound = middle + 1
            } else {
                upperBound = middle
            }
        }

        guard lowerBound > 0 else {
            return nil
        }
        let batchIndex = physicalIndex(forLogicalIndex: lowerBound - 1)
        let batch = batches[batchIndex]!
        let offset = candidateId - batch.firstId
        guard offset < UInt64(batch.candidates.count) else {
            return nil
        }
        return (batchIndex, Int(offset))
    }

    private mutating func evictOldestUnprotectedBatch() -> Bool {
        guard batchCount > 0 else {
            return false
        }

        var victimLogicalIndex: Int?
        for logicalIndex in 0..<batchCount {
            let batchIndex = physicalIndex(forLogicalIndex: logicalIndex)
            if !batches[batchIndex]!.isProtected {
                victimLogicalIndex = logicalIndex
                break
            }
        }
        guard let victimLogicalIndex else {
            return false
        }

        let victimBatchIndex = physicalIndex(forLogicalIndex: victimLogicalIndex)
        slotCount -= batches[victimBatchIndex]!.candidates.count
        if victimLogicalIndex == 0 {
            batches[victimBatchIndex] = nil
            firstBatchIndex = (firstBatchIndex + 1) % maxBatchCount
        } else {
            for logicalIndex in victimLogicalIndex..<(batchCount - 1) {
                let destination = physicalIndex(forLogicalIndex: logicalIndex)
                let source = physicalIndex(forLogicalIndex: logicalIndex + 1)
                batches[destination] = batches[source]
            }
            batches[physicalIndex(forLogicalIndex: batchCount - 1)] = nil
        }
        batchCount -= 1
        return true
    }
}

private func clearLearningCandidateCache() {
    learningCandidateCache.removeAll()
}

func cacheLearningCandidates(_ candidates: [Candidate], allowReadOnlyDeletion: Bool = false) -> UInt64? {
    guard !privateInputActive else { return nil }
    recordRecentPublishedPostCompositionCandidates(candidates)
    guard currentLearningType == .inputAndOutput
          || (allowReadOnlyDeletion && currentLearningType == .onlyOutput) else {
        return nil
    }

    return learningCandidateCache.appendBatch(
        disableLearningForKeyboardTypoCorrectionCandidates(
            candidates,
            experimentalTypoCorrectionEnabled:
                (config["experimentalTypoCorrection"] as? Bool) ?? defaultKeyboardTypoCorrection
        )
    )
}

func learningCandidateId(at index: Int, batchFirstId: UInt64?) -> UInt64 {
    guard let batchFirstId else {
        return 0
    }

    return learningCandidateCache.candidateId(at: index, batchFirstId: batchFirstId)
}

func consumeLearningCandidate(_ candidateId: UInt64) -> Candidate? {
    learningCandidateCache.consume(candidateId)
}

func learningCandidate(_ candidateId: UInt64) -> Candidate? {
    learningCandidateCache.candidate(for: candidateId)
}

@_silgen_name("PinLearningCandidate")
public func pin_learning_candidate(candidateId: UInt64) -> Bool {
    learningCandidateCache.pin(candidateId)
}

// Client-side future snapshots do not have matching server composition
// snapshots. The first subsequent composition edit invalidates those futures,
// so release their selected candidates without adding another IPC round trip.
func discardPinnedLearningCandidatesBeforeCompositionEdit() {
    guard composingTextSnapshots.isEmpty,
          learningCandidateCache.protectedSlotCount > 0 else {
        return
    }
    learningCandidateCache.removeAllPins()
}

func normalizedLearningRuby(_ ruby: String) -> String {
    var needsPrecompose = false
    var needsKanaShift = false
    for scalar in ruby.unicodeScalars {
        let value = scalar.value
        if (0x3041...0x3096).contains(value) || (0x309D...0x309F).contains(value) {
            needsKanaShift = true
        } else if !((0x0020...0x007E).contains(value)
            || (0x3000...0x3029).contains(value)
            || (0x3030...0x303F).contains(value)
            || (0x309B...0x309C).contains(value)
            || (0x30A0...0x30FF).contains(value)
            || (0x4E00...0x9FFF).contains(value)
            || (0xFF01...0xFF9F).contains(value)
            || value == 0x0009 || value == 0x000A || value == 0x000D) {
            needsPrecompose = true
            break
        }
    }
    if !needsPrecompose {
        guard needsKanaShift else {
            return ruby
        }
        var shifted = String.UnicodeScalarView()
        shifted.reserveCapacity(ruby.unicodeScalars.count)
        for scalar in ruby.unicodeScalars {
            let value = scalar.value
            let normalizedValue = switch value {
            case 0x3041...0x3096, 0x309D...0x309F:
                value + 0x60
            default:
                value
            }
            shifted.append(UnicodeScalar(normalizedValue)!)
        }
        return String(shifted)
    }
    return ruby.precomposedStringWithCanonicalMapping.unicodeScalars.reduce(into: "") { normalized, scalar in
        let value = scalar.value
        let normalizedValue = switch value {
        case 0x3041...0x3096, 0x309D...0x309F:
            value + 0x60
        default:
            value
        }
        normalized.unicodeScalars.append(UnicodeScalar(normalizedValue)!)
    }
}

func learningCandidateRuby(_ candidate: Candidate) -> String {
    normalizedLearningRuby(candidate.data.reduce(into: "") { $0 += $1.ruby })
}

func learningCandidateOutput(_ candidate: Candidate) -> String {
    candidate.data.reduce(into: "") { $0 += $1.word }
}

private func learningSelectionOverridesURL() -> URL? {
    currentLearningMemoryDirectoryURL?.appendingPathComponent(
        learningSelectionOverridesFilename,
        isDirectory: false
    )
}

func learningEpochMilliseconds() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

func isValidLearningKey(_ value: String) -> Bool {
    value.unicodeScalars.contains {
        !CharacterSet.whitespacesAndNewlines.contains($0) && !CharacterSet.controlCharacters.contains($0)
    }
}

struct LearningSelectionStore: Codable, Sendable, ExpressibleByDictionaryLiteral {
    struct Record: Codable, Sendable {
        var output: String
        var explicitCount = 0
        var implicitCount = 0
        var lastUsedEpochMs: Int64
        var contextCounts: [String: Int] = [:]
        var recentContexts: [String] = []

        private enum CodingKeys: String, CodingKey {
            case output, explicitCount, implicitCount, lastUsedEpochMs, contextCounts, recentContexts
        }

        init(output: String, lastUsedEpochMs: Int64) {
            self.output = output
            self.lastUsedEpochMs = lastUsedEpochMs
        }

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            output = try values.decode(String.self, forKey: .output)
            explicitCount = min(max(try values.decode(Int.self, forKey: .explicitCount), 0), 1000)
            implicitCount = min(max(try values.decode(Int.self, forKey: .implicitCount), 0), 1000)
            lastUsedEpochMs = try values.decode(Int64.self, forKey: .lastUsedEpochMs)
            let counts = try values.decode([String: Int].self, forKey: .contextCounts)
            let ordered = try values.decodeIfPresent([String].self, forKey: .recentContexts) ?? []
            var seen: Set<String> = []
            recentContexts = Array((ordered + counts.keys.sorted()).filter {
                isValidLearningKey($0) && (counts[$0] ?? 0) > 0 && seen.insert($0).inserted
            }.prefix(maxContextsPerRecord))
            contextCounts = Dictionary(uniqueKeysWithValues: recentContexts.map {
                ($0, min(counts[$0] ?? 0, 1000))
            })
        }

        func matchingContextCount(_ context: String) -> Int {
            contextCounts.lazy.filter { context.hasSuffix($0.key) }.map(\.value).max() ?? 0
        }
    }

    private var entriesByRuby: [String: [Record]] = [:]
    // Maintained on mutation so prefix completion can stop after a few matches.
    private var recentRubies: [String] = []
    var count: Int { entriesByRuby.count }
    var outputCount: Int { entriesByRuby.values.reduce(0) { $0 + $1.count } }
    var keys: Dictionary<String, [Record]>.Keys { entriesByRuby.keys }
    var isEmpty: Bool { entriesByRuby.isEmpty }

    init() {}

    init(dictionaryLiteral elements: (String, String)...) {
        for (ruby, output) in elements {
            record(output: output, for: ruby)
        }
    }

    subscript(ruby: String) -> String? {
        get { records(for: ruby).first?.output }
        set {
            if let newValue {
                record(output: newValue, for: ruby)
            } else {
                removeValue(forKey: ruby)
            }
        }
    }

    mutating func record(
        output: String, for ruby: String, leftContext: String = "", explicit: Bool = true,
        nowEpochMs: Int64 = learningEpochMilliseconds()
    ) {
        let ruby = normalizedLearningRuby(ruby)
        guard isValidLearningKey(ruby), isValidLearningKey(output) else { return }
        var records = entriesByRuby[ruby] ?? []
        var record = Record(output: output, lastUsedEpochMs: nowEpochMs)
        if let index = records.firstIndex(where: { $0.output == output }) {
            record = records.remove(at: index)
        }
        record.lastUsedEpochMs = nowEpochMs
        if explicit {
            record.explicitCount = min(record.explicitCount + 1, 1000)
        } else {
            record.implicitCount = min(record.implicitCount + 1, 1000)
        }
        // Implicit commits must not turn an unrelated explicit choice into a contextual override.
        let context = String(leftContext.suffix(12))
        if explicit && isValidLearningKey(context) {
            record.contextCounts[context] = min((record.contextCounts[context] ?? 0) + 1, 1000)
            record.recentContexts.removeAll { $0 == context }
            record.recentContexts.insert(context, at: 0)
            if record.recentContexts.count > maxContextsPerRecord {
                record.contextCounts.removeValue(forKey: record.recentContexts.removeLast())
            }
        }
        records.insert(record, at: 0)
        entriesByRuby[ruby] = Array(records.prefix(maxOutputsPerRuby))
        recentRubies.removeAll { $0 == ruby }
        recentRubies.insert(ruby, at: 0)
        if entriesByRuby.count > maxLearningSelectionOverrideCount,
           let oldest = entriesByRuby.keys.min(by: { ageOrder($0) < ageOrder($1) }) {
            removeValue(forKey: oldest)
        }
    }

    private func ageOrder(_ ruby: String) -> (Int64, String) {
        (entriesByRuby[ruby]?.map(\.lastUsedEpochMs).max() ?? 0, ruby)
    }

    func records(for ruby: String) -> [Record] { entriesByRuby[normalizedLearningRuby(ruby)] ?? [] }
    func outputs(for ruby: String) -> [String] { records(for: ruby).map(\.output) }

    mutating func removeAll(keepingCapacity: Bool = false) {
        entriesByRuby.removeAll(keepingCapacity: keepingCapacity)
        recentRubies.removeAll(keepingCapacity: keepingCapacity)
    }

    @discardableResult
    mutating func removeValue(forKey key: String) -> [String]? {
        let ruby = normalizedLearningRuby(key)
        recentRubies.removeAll { $0 == ruby }
        return entriesByRuby.removeValue(forKey: ruby)?.map(\.output)
    }

    @discardableResult
    mutating func remove(output: String, for ruby: String) -> Bool {
        let ruby = normalizedLearningRuby(ruby)
        guard var records = entriesByRuby[ruby],
              let index = records.firstIndex(where: { $0.output == output }) else { return false }
        records.remove(at: index)
        if records.isEmpty {
            removeValue(forKey: ruby)
        } else {
            entriesByRuby[ruby] = records
        }
        return true
    }

    func prefixMatches(
        for prefixRuby: String, maxCount: Int = 3, expandedPrefixRubies: [String] = []
    ) -> [(ruby: String, output: String)] {
        let prefixes = expandedPrefixRubies.map(normalizedLearningRuby).filter { $0.count >= 2 }
        let activePrefixes = prefixes.isEmpty ? [prefixRuby].filter { $0.count >= 2 } : prefixes
        guard !activePrefixes.isEmpty, maxCount > 0 else { return [] }
        var results: [(ruby: String, output: String)] = []
        for ruby in recentRubies where ruby != prefixRuby && activePrefixes.contains(where: ruby.hasPrefix) {
            for record in entriesByRuby[ruby] ?? [] {
                results.append((ruby, record.output))
                if results.count == maxCount { return results }
            }
        }
        return results
    }

    private struct Snapshot: Codable {
        let version: Int
        let entriesByRuby: [String: [Record]]
    }

    private struct LegacySnapshot: Decodable {
        let version: Int
        let entriesByRuby: [String: [String]]
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let snapshot = try? container.decode(Snapshot.self), snapshot.version == 2 {
            for (ruby, records) in snapshot.entriesByRuby.sorted(by: { $0.key < $1.key }) {
                let key = normalizedLearningRuby(ruby)
                guard isValidLearningKey(key) else { continue }
                var seen: Set<String> = []
                entriesByRuby[key] = Array(((entriesByRuby[key] ?? []) + records)
                    .filter { isValidLearningKey($0.output) }
                    .sorted { ($0.lastUsedEpochMs, $0.explicitCount) > ($1.lastUsedEpochMs, $1.explicitCount) }
                    .filter { seen.insert($0.output).inserted }.prefix(maxOutputsPerRuby))
            }
            entriesByRuby = entriesByRuby.filter { !$0.value.isEmpty }
            recentRubies = entriesByRuby.keys.sorted { ageOrder($0) > ageOrder($1) }
            for ruby in recentRubies.dropFirst(maxLearningSelectionOverrideCount) {
                entriesByRuby.removeValue(forKey: ruby)
            }
            recentRubies = Array(recentRubies.prefix(maxLearningSelectionOverrideCount))
        } else {
            let legacy = if let flat = try? container.decode([String: [String]].self) {
                flat
            } else if let snapshot = try? container.decode(LegacySnapshot.self), snapshot.version == 1 {
                snapshot.entriesByRuby
            } else {
                try container.decode([String: String].self).mapValues { [$0] }
            }
            let now = learningEpochMilliseconds()
            for (ruby, outputs) in legacy.sorted(by: { $0.key < $1.key }).prefix(maxLearningSelectionOverrideCount) {
                for (index, output) in outputs.prefix(maxOutputsPerRuby).enumerated().reversed() {
                    record(output: output, for: ruby, nowEpochMs: now - Int64(index) * 1000)
                }
            }
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(Snapshot(version: 2, entriesByRuby: entriesByRuby))
    }
}

func loadLearningSelectionOverrides() {
    guard !privateInputActive, !learningSelectionOverridesDirty else { return }
    guard currentLearningType != .nothing,
          let overridesURL = learningSelectionOverridesURL() else {
        learningSelectionOverrides.removeAll(keepingCapacity: false)
        return
    }

    guard FileManager.default.fileExists(atPath: overridesURL.path) else {
        learningSelectionOverrides.removeAll(keepingCapacity: false)
        return
    }

    do {
        let data = try Data(contentsOf: overridesURL)
        learningSelectionOverrides = try JSONDecoder().decode(LearningSelectionStore.self, from: data)
    } catch {
        learningSelectionOverrides.removeAll(keepingCapacity: false)
        serverLog(
            "WARN",
            "Failed to load learning selection overrides at \(overridesURL.path): \(error)"
        )
    }
}

nonisolated(unsafe) var learningSelectionOverridesDirty = false

@discardableResult
private func saveLearningSelectionOverrides() -> Bool {
    guard learningSelectionOverridesDirty else { return true }
    guard !privateInputActive, currentLearningType != .nothing,
          let overridesURL = learningSelectionOverridesURL() else { return false }

    do {
        ensureLearningMemoryDirectoryIfNeeded()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(learningSelectionOverrides)
        try data.write(to: overridesURL, options: .atomic)
        learningSelectionOverridesDirty = false
        return true
    } catch {
        serverLog("WARN", "Failed to save learning selection overrides at \(overridesURL.path): \(error)")
        return false
    }
}

private func updateLearningSelectionOverride(
    _ candidate: Candidate, context: String, explicit: Bool
) -> Bool {
    guard !privateInputActive, currentLearningType == .inputAndOutput, candidate.isLearningTarget else {
        return false
    }
    let ruby = learningCandidateRuby(candidate)
    let output = learningCandidateOutput(candidate)
    guard isValidLearningKey(ruby), isValidLearningKey(output) else { return false }
    learningSelectionOverrides.record(output: output, for: ruby, leftContext: context, explicit: explicit)
    return true
}

func synthesizeLearnedCandidate(
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

nonisolated(unsafe) var currentUserDictionaryEntries: [DicdataElement] = []

func prioritizeLearningSelectionOverrides<Element>(
    _ elements: [Element],
    ruby: String,
    expandedPrefixRubies: [String] = [],
    leftContext: String = "",
    nowEpochMs: Int64 = learningEpochMilliseconds(),
    candidate: (Element) -> Candidate,
    synthesizeElement: ((Candidate) -> Element)? = nil
) -> [Element] {
    let mayReadLearning = !privateInputActive && currentLearningType != .nothing
    let normalizedRuby = normalizedLearningRuby(ruby)
    let exactRecords = mayReadLearning ? learningSelectionOverrides.records(for: normalizedRuby) : []
    var results = elements
    if !exactRecords.isEmpty {
        let refCount = elements.first.map { candidate($0).composingCount } ?? .inputCount(ruby.count)
        let modelTopIsUserDict = elements.first.map {
            candidate($0).data.contains {
                $0.metadata.contains(.isFromUserDictionary) && !$0.metadata.contains(.isKeyboardTypoCorrection)
            }
        } ?? false
        let ordered = exactRecords.map { ($0, $0.matchingContextCount(leftContext)) }.sorted {
            if $0.1 != $1.1 { return $0.1 > $1.1 }
            if $0.0.lastUsedEpochMs != $1.0.lastUsedEpochMs { return $0.0.lastUsedEpochMs > $1.0.lastUsedEpochMs }
            if $0.1 == 0 && $0.0.explicitCount != $1.0.explicitCount {
                return $0.0.explicitCount > $1.0.explicitCount
            }
            return $0.0.output < $1.0.output
        }
        var promoted = 0
        var secondary: [LearningSelectionStore.Record] = []
        func place(_ record: LearningSelectionStore.Record, at index: Int) -> Bool {
            if let match = results.firstIndex(where: {
                let item = candidate($0)
                return learningCandidateRuby(item) == normalizedRuby && learningCandidateOutput(item) == record.output
            }) {
                guard match >= index else { return false }
                let element = results.remove(at: match)
                results.insert(element, at: index)
                return true
            }
            guard record.explicitCount > 0, let synthesizeElement else { return false }
            results.insert(synthesizeElement(synthesizeLearnedCandidate(
                word: record.output, ruby: normalizedRuby, composingCount: refCount
            )), at: index)
            return true
        }

        for (record, contextCount) in ordered {
            let age = Double(nowEpochMs) - Double(record.lastUsedEpochMs)
            let recentOverride = leftContext.isEmpty
                ? (age <= 86_400_000 || (record.explicitCount >= 2 && age <= 14 * 86_400_000))
                : (record.explicitCount >= 3 && age <= 30 * 60_000)
            if record.explicitCount > 0 && (contextCount > 0 || (recentOverride && !modelTopIsUserDict)) {
                if place(record, at: promoted) { promoted += 1 }
            } else if !leftContext.isEmpty || recentOverride || record.explicitCount == 0 {
                secondary.append(record)
            }
        }
        var insertionIndex = min(max(promoted, 1), results.count)
        for record in secondary {
            if record.explicitCount == 0 && insertionIndex == 0 { continue }
            if place(record, at: insertionIndex) { insertionIndex += 1 }
        }
    }

    guard isPredictiveConversionEnabled(), let synthesizeElement,
          normalizedRuby.count >= 2 || !expandedPrefixRubies.isEmpty else { return results }
    let selectionPrefixMatches = mayReadLearning
        ? learningSelectionOverrides.prefixMatches(
            for: normalizedRuby, maxCount: 2, expandedPrefixRubies: expandedPrefixRubies
        )
        : []
    var bigramMatches: [PostCompositionBigramEntry] = []
    if mayReadLearning, normalizedRuby.count >= 2 {
        for key in postCompositionContextLookupKeys(leftContext) {
            for entry in postCompositionBigramStore.entries(for: key)
            where entry.ruby != normalizedRuby && entry.ruby.hasPrefix(normalizedRuby) {
                bigramMatches.append(entry)
            }
        }
    }
    let userDictMatches = (!currentUserDictionaryEntries.isEmpty && normalizedRuby.count >= 2)
        ? (installedUserDictionary?.prefixes[normalizedRuby] ?? []).filter {
            $0.ruby != normalizedRuby && $0.ruby.hasPrefix(normalizedRuby)
        }
        : []
    guard !selectionPrefixMatches.isEmpty || !bigramMatches.isEmpty || !userDictMatches.isEmpty else {
        return results
    }

    let normalizedRubyCount = normalizedRuby.count
    // All exact full-span candidates stay ahead of completions, even if there are interleaved subclauses.
    var insertionIndex = results.lastIndex {
        let item = candidate($0)
        return item.rubyCount == normalizedRubyCount && learningCandidateRuby(item) == normalizedRuby
    }.map { $0 + 1 } ?? min(1, results.count)
    let ref = results.first {
        let item = candidate($0)
        return item.rubyCount == normalizedRubyCount && learningCandidateRuby(item) == normalizedRuby
    } ?? results.first
    let refCount = ref.map { candidate($0).composingCount } ?? .inputCount(ruby.count)
    var seenOutputs = Set<String>(minimumCapacity: insertionIndex)
    for element in results[..<insertionIndex] {
        seenOutputs.insert(learningCandidateOutput(candidate(element)))
    }

    func insert(_ output: String, _ ruby: String, data: DicdataElement? = nil) -> Bool {
        guard seenOutputs.insert(output).inserted else { return false }
        if let match = results[insertionIndex...].firstIndex(where: {
            learningCandidateOutput(candidate($0)) == output
        }) {
            let element = results.remove(at: match)
            results.insert(element, at: insertionIndex)
        } else {
            var completion = synthesizeLearnedCandidate(word: output, ruby: ruby, composingCount: refCount)
            if let data { completion.data = [data] }
            results.insert(synthesizeElement(completion), at: insertionIndex)
        }
        insertionIndex += 1
        return true
    }

    for match in selectionPrefixMatches {
        _ = insert(match.output, match.ruby)
    }
    guard normalizedRuby.count >= 2 else { return results }
    for entry in bigramMatches {
        if insert(entry.surface, entry.ruby) { return results }
    }
    if let entry = userDictMatches.first(where: { !seenOutputs.contains($0.word) }) {
        _ = insert(entry.word, entry.ruby, data: entry)
    }
    return results
}

// Bundled emoji resources are immutable until Initialize/restart. Parsing the
// entire file for every key is unnecessary I/O and allocation on both hosts.
struct EmojiTextReplacerCache {
    private var resourceURL: URL?
    private var replacer: TextReplacer?

    mutating func value(
        for url: URL,
        load: (URL) -> TextReplacer = { url in TextReplacer { url } }
    ) -> TextReplacer {
        if resourceURL == url, let replacer { return replacer }
        let loaded = load(url)
        resourceURL = url
        replacer = loaded
        return loaded
    }

    mutating func reset() { resourceURL = nil; replacer = nil }
}

nonisolated(unsafe) var emojiTextReplacerCache = EmojiTextReplacerCache()

func makeConvertRequestOptions(
    context: String,
    zenzaiEnabled: Bool,
    runtimeDirectoryURL: URL,
    emojiDictionaryURL: URL,
    textReplacer: TextReplacer? = nil,
    zenzaiWeightURL: URL,
    profile: String,
    learningType: LearningType = .nothing,
    learningMemoryDirectoryURL: URL? = nil,
    experimentalKeyboardTypoCorrection: Bool = defaultKeyboardTypoCorrection,
    predictiveConversion: Bool = false
) -> ConvertRequestOptions {
    return ConvertRequestOptions(
        requireJapanesePrediction: predictiveConversion ? .autoMix : .disabled,
        requireEnglishPrediction: .autoMix,
        keyboardLanguage: .ja_JP,
        learningType: learningType,
        memoryDirectoryURL: learningMemoryDirectoryURL ?? runtimeDirectoryURL,
        sharedContainerURL: runtimeDirectoryURL,
        textReplacer: textReplacer ?? .init {
            return emojiDictionaryURL
        },
        specialCandidateProviders: nil,
        zenzaiMode: zenzaiEnabled ? .on(
            weight: zenzaiWeightURL,
            inferenceLimit: 5,
            requestRichCandidates: true,
            personalizationMode: nil,
            versionDependentMode: .v3(
                .init(
                    profile: profile,
                    leftSideContext: context.replacingOccurrences(of: " ", with: "\u{3000}")
                )
            )
        ) : .off,
        experimentalKeyboardTypoCorrection: experimentalKeyboardTypoCorrection,
        metadata: .init(versionString: "Azookey for Windows")
    )
}

private struct AppSettings: Decodable {
    let zenzai: ZenzaiSettings?
    let learning: LearningSettings?
    let user_dictionary: UserDictionarySettings?
    let romaji_table: RomajiTableSettings?
    let general: GeneralSettings?
}

private struct GeneralSettings: Decodable {
    let jev_conversion: Bool?
    let experimental_typo_correction: Bool?
    let predictive_conversion: Bool?
}

private struct LearningSettings: Decodable {
    let mode: String?
    let strength: String?
}

struct CpuRuntimeTuningSettings: Decodable {
    let decode_threads: Int32
    let batch_threads: Int32?
    let direct_model_first: Bool
    let model_id: String?
    let tuning_version: Int?
}

private struct ZenzaiSettings: Decodable {
    let enable: Bool?
    let profile: String?
    let backend: String?
    let gpu_layers: Int?
    let model: String?
    let cpu_runtime_tuning: CpuRuntimeTuningSettings?
}

private struct UserDictionarySettings: Decodable, Sendable {
    let entries: [UserDictionaryEntry]?
}

enum UserDictionaryPos: String, Decodable, Sendable {
    case properNoun = "proper_noun"
    case commonNoun = "common_noun"
    case sahenNoun = "sahen_noun"
    case shortcut
    case symbol

    var cid: Int {
        switch self {
        case .properNoun, .shortcut: 1288
        case .commonNoun: 1285
        case .sahenNoun: 1283
        case .symbol: 5
        }
    }

    func score(rank: Int) -> PValue {
        self == .shortcut ? -1 - min(Float(rank) * 0.00001, 0.2) : -5 - min(Float(rank) * 0.0001, 1.5)
    }
}

struct UserDictionaryEntry: Decodable, Equatable, Sendable {
    let reading: String
    let word: String
    var pos: UserDictionaryPos? = nil
}

private struct UserDictionaryFile: Decodable {
    let version: Int
    let entries: [UserDictionaryEntry]
}

func loadUserDictionaryEntries(
    dictionaryURL: URL,
    legacyEntries: [UserDictionaryEntry]
) -> [UserDictionaryEntry]? {
    guard FileManager.default.fileExists(atPath: dictionaryURL.path) else {
        return legacyEntries
    }
    do {
        let file = try JSONDecoder().decode(UserDictionaryFile.self, from: Data(contentsOf: dictionaryURL))
        guard file.version == 1 else {
            serverLog("WARN", "LoadConfig: unsupported user dictionary version")
            return nil
        }
        return file.entries
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
        return legacyEntries
    } catch {
        serverLog("WARN", "LoadConfig: failed to read user dictionary: \(error)")
        return nil
    }
}

struct UserDictionarySnapshot: Sendable {
    let entries: [DicdataElement]
    let shortcuts: [DicdataElement]
    let conversion: PreparedDynamicUserDictionary
    let reverseEntries: [String: [ReconversionDictionaryEntry]]
    let prefixes: [String: [DicdataElement]]

    init(entries source: [UserDictionaryEntry]) {
        var registered: [DicdataElement] = []
        var normal: [DicdataElement] = []
        var shortcuts: [DicdataElement] = []
        var seen: [String: Set<String>] = [:]
        var prefixes: [String: [DicdataElement]] = [:]

        for entry in source {
            if registered.count == maxUserDictionaryEntryCount { break }
            let ruby = normalizedLearningRuby(entry.reading.trimmingCharacters(in: .whitespacesAndNewlines))
                .precomposedStringWithCanonicalMapping
            let word = entry.word.trimmingCharacters(in: .whitespacesAndNewlines)
            guard isValidLearningKey(ruby), isValidLearningKey(word),
                  seen[ruby, default: []].insert(word).inserted else { continue }
            let pos = entry.pos ?? .properNoun
            let data = DicdataElement(word: word, ruby: ruby, cid: pos.cid, mid: pos.cid,
                                      value: pos.score(rank: registered.count), metadata: .isFromUserDictionary)
            registered.append(data)
            if pos == .shortcut {
                shortcuts.append(data)
            } else {
                normal.append(data)
                var prefix = ""
                // The conversion reading limit bounds useful prefix keys.
                for character in ruby.prefix(maxConversionContinuationReadingCount) {
                    prefix.append(character)
                    guard prefix.count >= 2, prefix != ruby else { continue }
                    // Full conversion and learned completions already occupy earlier slots.
                    if prefixes[prefix, default: []].count < 64 {
                        prefixes[prefix, default: []].append(data)
                    }
                }
            }
        }
        self.entries = registered
        self.shortcuts = shortcuts
        self.prefixes = prefixes
        self.reverseEntries = ReconversionDictionary.makeUserEntries(registered)
        self.conversion = PreparedDynamicUserDictionary(
            normal,
            shortcuts: shortcuts
        )
    }
}

// Building the immutable LOUDS snapshot never touches a live converter. The
// serialized host adopts only the newest completed generation.
final class UserDictionaryReloader: @unchecked Sendable {
    private let queue = DispatchQueue(label: "azookey.user-dictionary", qos: .utility)
    private let lock = NSLock()
    private var generation = 0
    private var pending: UserDictionarySnapshot?
    private var work: DispatchWorkItem?
    private let delay: DispatchTimeInterval

    init(delay: DispatchTimeInterval = .milliseconds(300)) {
        self.delay = delay
    }

    func schedule(_ build: @escaping @Sendable () -> UserDictionarySnapshot?) {
        lock.lock()
        generation += 1
        let ticket = generation
        pending = nil
        work?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, let snapshot = build() else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            if ticket == self.generation { self.pending = snapshot }
        }
        work = item
        lock.unlock()
        queue.asyncAfter(deadline: .now() + delay, execute: item)
    }

    func invalidatePending() {
        lock.lock()
        generation += 1
        pending = nil
        work?.cancel()
        work = nil
        lock.unlock()
    }

    func takePending() -> UserDictionarySnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        let snapshot = pending
        pending = nil
        return snapshot
    }
}

private let userDictionaryReloader = UserDictionaryReloader()
nonisolated(unsafe) private var installedUserDictionary: UserDictionarySnapshot?

private func installUserDictionarySnapshot(_ snapshot: UserDictionarySnapshot) {
    installedUserDictionary = snapshot
    currentUserDictionaryEntries = snapshot.entries
    reconversionDictionary.replacePreparedUserEntries(snapshot.reverseEntries)
    converter.installPreparedDynamicUserDictionary(snapshot.conversion)
    normalNBestSupplementConverter.installPreparedDynamicUserDictionary(snapshot.conversion)
    invalidateAutomaticConversionContinuationCache()
}

func adoptPendingUserDictionary() {
    guard let snapshot = userDictionaryReloader.takePending() else { return }
    installUserDictionarySnapshot(snapshot)
}

func withIsolatedProbeUserDictionary<T>(
    mainConverter: KanaKanjiConverter = converter,
    supplementConverter: KanaKanjiConverter = normalNBestSupplementConverter,
    body: () -> T
) -> T {
    let restoreSnapshot = installedUserDictionary?.conversion ?? PreparedDynamicUserDictionary([])
    let emptySnapshot = PreparedDynamicUserDictionary([])
    mainConverter.installPreparedDynamicUserDictionary(emptySnapshot)
    supplementConverter.installPreparedDynamicUserDictionary(emptySnapshot)
    defer {
        mainConverter.installPreparedDynamicUserDictionary(restoreSnapshot)
        supplementConverter.installPreparedDynamicUserDictionary(restoreSnapshot)
    }
    return body()
}


private struct RomajiTableSettings: Decodable {
    let rows: [RomajiTableRow]?
}

enum RomajiInputStyleSelection: Equatable {
    case roman2kana
    case custom
}

func resolveRomajiInputStyleSelection(
    rows: [RomajiTableRow]?
) -> RomajiInputStyleSelection {
    guard let rows, buildCustomRomajiTableContent(rows: rows) != nil else {
        return .roman2kana
    }

    return .custom
}

func effectiveZenzaiEnabledForCandidates(
    isConfigured: Bool,
    inputCount: Int,
    hiraganaCount: Int
) -> Bool {
    isConfigured
        && inputCount >= minInputCountForZenzaiCandidates
        && hiraganaCount >= minHiraganaCountForZenzaiCandidates
}

func effectiveZenzaiRuntimeEnabled(
    isConfigured: Bool,
    backend: String?,
    cpuBackendSupported: Bool
) -> Bool {
    guard isConfigured else {
        return false
    }

    let normalizedBackend = normalizedZenzaiBackend(backend)

    if normalizedBackend.isEmpty || normalizedBackend == "cpu" {
        return cpuBackendSupported
    }

    return true
}

private func cpuZenzaiBackendSupportedFromEnvironment() -> Bool {
    ProcessInfo.processInfo.environment["AZOOKEY_ZENZAI_CPU_SUPPORTED"] != "0"
}

private func setRoman2KanaInputStyle() {
    currentInputStyle = .roman2kana
    customRomajiTableEnabled = false
}

private func setCustomRomajiInputStyle(rows: [RomajiTableRow]?) {
    guard let rows, let content = buildCustomRomajiTableContent(rows: rows) else {
        setRoman2KanaInputStyle()
        return
    }

    let runtimeDirectoryURL = converterRuntimeDirectoryURL()
    let fileURL = runtimeDirectoryURL
        .appendingPathComponent("azookey-romaji-\(UUID().uuidString).tsv")

    do {
        try FileManager.default.createDirectory(
            at: runtimeDirectoryURL,
            withIntermediateDirectories: true
        )
        try content.write(to: fileURL, atomically: true, encoding: .utf8)
        defer {
            try? FileManager.default.removeItem(at: fileURL)
        }
        let tableName = "azookey-windows-custom-romaji"
        let table = try InputStyleManager.loadTable(from: fileURL)
        InputStyleManager.registerInputStyle(table: table, for: tableName)
        currentInputStyle = .mapped(id: .tableName(tableName))
        customRomajiTableEnabled = true
    } catch {
        serverLog("ERROR", "Failed to apply custom romaji table: \(error)")
        setRoman2KanaInputStyle()
    }
}

private func applyRomajiInputStyle(
    rows: [RomajiTableRow]?
) {
    switch resolveRomajiInputStyleSelection(
        rows: rows
    ) {
    case .roman2kana:
        setRoman2KanaInputStyle()
    case .custom:
        setCustomRomajiInputStyle(rows: rows)
    }
}

private func clampedCorrespondingCount(
    composingText: ComposingText,
    rawCount: Int
) -> Int {
    min(composingText.input.count, max(0, rawCount))
}

private func inputCharacter(_ element: ComposingText.InputElement) -> Character? {
    switch element.piece {
    case .character(let character):
        character
    case .key(_, let input, _):
        input
    case .compositionSeparator:
        nil
    }
}

private func asciiLowercase(_ character: Character) -> Character? {
    let scalars = String(character).unicodeScalars
    guard scalars.count == 1, let scalar = scalars.first else {
        return nil
    }

    let value = scalar.value
    if (65...90).contains(value), let lowered = UnicodeScalar(value + 32) {
        return Character(lowered)
    }
    if (97...122).contains(value) {
        return character
    }
    return nil
}

private func isAsciiRomajiVowel(_ character: Character) -> Bool {
    guard let lowered = asciiLowercase(character) else {
        return false
    }
    switch lowered {
    case "a", "i", "u", "e", "o":
        return true
    default:
        return false
    }
}

private func isAsciiRomajiConsonantExceptN(_ character: Character) -> Bool {
    guard let lowered = asciiLowercase(character) else {
        return false
    }
    return lowered != "n" && !isAsciiRomajiVowel(lowered)
}

private func adjustedCorrespondingCountForDelayedSingleN(
    composingText: ComposingText,
    rawCount: Int
) -> Int {
    let splitAt = clampedCorrespondingCount(composingText: composingText, rawCount: rawCount)
    guard splitAt >= 2, splitAt < composingText.input.count else {
        return splitAt
    }

    let previousElement = composingText.input[splitAt - 2]
    let consumedElement = composingText.input[splitAt - 1]
    let nextElement = composingText.input[splitAt]
    guard previousElement.inputStyle != .direct,
          consumedElement.inputStyle != .direct,
          nextElement.inputStyle != .direct,
          let previous = inputCharacter(previousElement),
          asciiLowercase(previous) == "n",
          let consumed = inputCharacter(consumedElement),
          isAsciiRomajiConsonantExceptN(consumed),
          let next = inputCharacter(nextElement),
          isAsciiRomajiVowel(next)
    else {
        return splitAt
    }

    return splitAt - 1
}

func resolveCandidateComposition(
    composingText: ComposingText,
    candidateComposingCount: ComposingCount
) -> (correspondingCount: Int, remainingConvertTarget: String) {
    var remainingComposingText = composingText
    remainingComposingText.prefixComplete(composingCount: candidateComposingCount)

    let rawCount = composingText.input.count - remainingComposingText.input.count
    let correspondingCount = adjustedCorrespondingCountForDelayedSingleN(
        composingText: composingText,
        rawCount: rawCount
    )
    if correspondingCount != rawCount {
        var adjustedRemainingComposingText = composingText
        adjustedRemainingComposingText.prefixComplete(
            composingCount: .inputCount(correspondingCount)
        )
        return (
            correspondingCount: correspondingCount,
            remainingConvertTarget: adjustedRemainingComposingText.convertTarget
        )
    }

    return (
        correspondingCount: correspondingCount,
        remainingConvertTarget: remainingComposingText.convertTarget
    )
}

func makeCandidatePreviewComposingText(
    from composingText: ComposingText
) -> (composingText: ComposingText, syntheticEndOfText: Bool) {
    guard composingText.convertTarget.last == "n" else {
        return (composingText: composingText, syntheticEndOfText: false)
    }

    guard let trailingElement = composingText.input.last else {
        return (composingText: composingText, syntheticEndOfText: false)
    }

    switch trailingElement.piece {
    case .character, .key:
        guard trailingElement.inputStyle != .direct else {
            return (composingText: composingText, syntheticEndOfText: false)
        }
    case .compositionSeparator:
        return (composingText: composingText, syntheticEndOfText: false)
    }

    var previewComposingText = composingText
    let originalConvertTarget = previewComposingText.convertTarget
    previewComposingText.insertAtCursorPosition([
        .init(piece: .compositionSeparator, inputStyle: trailingElement.inputStyle)
    ])

    guard previewComposingText.convertTarget != originalConvertTarget else {
        return (composingText: composingText, syntheticEndOfText: false)
    }

    return (composingText: previewComposingText, syntheticEndOfText: true)
}

func makeCandidatePreviewComposingTextForCursorPrefix(
    prefixComposingText: ComposingText,
    suffixAfterCursor: String
) -> (composingText: ComposingText, syntheticEndOfText: Bool) {
    guard suffixAfterCursor.isEmpty else {
        return (composingText: prefixComposingText, syntheticEndOfText: false)
    }

    return makeCandidatePreviewComposingText(from: prefixComposingText)
}

func resolveCandidateCompositionForDisplay(
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    candidateComposingCount: ComposingCount
) -> CandidateDisplayResolution {
    let originalResolution = resolveCandidateComposition(
        composingText: originalComposingText,
        candidateComposingCount: candidateComposingCount
    )
    let previewResolution = resolveCandidateComposition(
        composingText: previewComposingText,
        candidateComposingCount: candidateComposingCount
    )

    return (
        correspondingCount: originalResolution.correspondingCount,
        remainingConvertTarget: previewResolution.remainingConvertTarget,
        remainingConvertTargetCount: previewResolution.remainingConvertTarget.count
    )
}

typealias CandidateDisplayResolution = (
    correspondingCount: Int,
    remainingConvertTarget: String,
    remainingConvertTargetCount: Int
)

struct CursorPrefixCandidateResult {
    let candidate: Candidate
    let displayText: String
}

private struct CursorPrefixBoundaryCandidate {
    let index: Int
    let correspondingCount: Int
    let score: Int
}

private struct CursorPrefixBoundaryScoringContext {
    let previewHiragana: String
    let previewHiraganaBoundaries: [String.Index]

    init(previewHiragana: String) {
        self.previewHiragana = previewHiragana

        var boundaries = [String.Index]()
        boundaries.append(previewHiragana.startIndex)

        var index = previewHiragana.startIndex
        while index < previewHiragana.endIndex {
            index = previewHiragana.index(after: index)
            boundaries.append(index)
        }
        self.previewHiraganaBoundaries = boundaries
    }

    var previewHiraganaCount: Int {
        max(0, previewHiraganaBoundaries.count - 1)
    }

    func boundaryIndex(afterCharacters count: Int) -> String.Index? {
        guard count >= 0, count < previewHiraganaBoundaries.count else {
            return nil
        }
        return previewHiraganaBoundaries[count]
    }
}

private let cursorPrefixClauseTerminalSuffixes = [
    "ではない",
    "じゃない",
    "である",
    "でした",
    "だった",
    "ました",
    "ません",
    "です",
    "ます",
    "ない",
]

private func cursorPrefixHasCandidateRubyBoundary(
    candidate: Candidate,
    prefixSurfaceCount: Int
) -> Bool {
    var cursor = 0
    for element in candidate.data {
        cursor += element.ruby.count
        if cursor == prefixSurfaceCount {
            return true
        }
        if cursor > prefixSurfaceCount {
            return false
        }
    }
    return false
}

private func cursorPrefixTerminalPhraseBonus(
    context: CursorPrefixBoundaryScoringContext,
    prefixSurfaceCount: Int
) -> Int {
    guard let prefixEndIndex = context.boundaryIndex(afterCharacters: prefixSurfaceCount) else {
        return 0
    }

    for suffix in cursorPrefixClauseTerminalSuffixes {
        let suffixCount = suffix.count
        guard prefixSurfaceCount >= suffixCount else {
            continue
        }

        let suffixStartIndex = context.previewHiragana.index(
            prefixEndIndex,
            offsetBy: -suffixCount
        )
        if context.previewHiragana[suffixStartIndex..<prefixEndIndex].elementsEqual(suffix) {
            return 120
        }
    }
    return 0
}

private func cursorPrefixTokenBoundaryPenalty(
    candidate: Candidate,
    prefixSurfaceCount: Int
) -> Int {
    guard prefixSurfaceCount > 0,
          prefixSurfaceCount < candidate.rubyCount
    else {
        return 0
    }

    return cursorPrefixHasCandidateRubyBoundary(
        candidate: candidate,
        prefixSurfaceCount: prefixSurfaceCount
    ) ? 0 : 160
}

private func cursorPrefixBoundaryScore(
    candidate: Candidate,
    candidateIndex: Int,
    resolution: CandidateDisplayResolution,
    context: CursorPrefixBoundaryScoringContext
) -> Int {
    let remainingCount = resolution.remainingConvertTargetCount
    let prefixSurfaceCount = max(0, context.previewHiraganaCount - remainingCount)
    let terminalBonus = cursorPrefixTerminalPhraseBonus(
        context: context,
        prefixSurfaceCount: prefixSurfaceCount
    )
    let tokenBoundaryPenalty = cursorPrefixTokenBoundaryPenalty(
        candidate: candidate,
        prefixSurfaceCount: prefixSurfaceCount
    )
    // A first-clause result can occasionally consume the first character of
    // the following clause while its displayed ruby stops before it. Such a
    // boundary makes the first Shift+Left change invisible (for example,
    // "ある程度な" -> "ある程度"). Prefer a display-aligned boundary.
    let overconsumedSurfacePenalty = prefixSurfaceCount > candidate.rubyCount ? 160 : 0

    return resolution.correspondingCount * 4
        + terminalBonus
        - tokenBoundaryPenalty
        - overconsumedSurfacePenalty
        - candidateIndex
}

private func preferCursorPrefixBoundary(
    _ candidate: CursorPrefixBoundaryCandidate,
    over current: CursorPrefixBoundaryCandidate?
) -> Bool {
    guard let current else {
        return true
    }
    if candidate.score != current.score {
        return candidate.score > current.score
    }
    if candidate.correspondingCount != current.correspondingCount {
        return candidate.correspondingCount > current.correspondingCount
    }
    return candidate.index < current.index
}

func resolveCandidateCompositionForDisplay(
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    candidateComposingCount: ComposingCount,
    resolutionCache: inout [String: CandidateDisplayResolution]
) -> CandidateDisplayResolution {
    let cacheKey = String(describing: candidateComposingCount)
    if let cached = resolutionCache[cacheKey] {
        return cached
    }

    let resolved = resolveCandidateCompositionForDisplay(
        originalComposingText: originalComposingText,
        previewComposingText: previewComposingText,
        candidateComposingCount: candidateComposingCount
    )
    resolutionCache[cacheKey] = resolved
    return resolved
}

func cursorPrefixCandidateResults(
    mainResults: [Candidate],
    firstClauseResults: [Candidate],
    exactClauseResults: [Candidate] = [],
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    previewHiragana: String
) -> [Candidate] {
    cursorPrefixCandidateDisplayResults(
        mainResults: mainResults,
        firstClauseResults: firstClauseResults,
        exactClauseResults: exactClauseResults,
        originalComposingText: originalComposingText,
        previewComposingText: previewComposingText,
        previewHiragana: previewHiragana
    ).map(\.candidate)
}

func cursorPrefixCandidateDisplayResults(
    mainResults: [Candidate],
    firstClauseResults: [Candidate],
    exactClauseResults: [Candidate] = [],
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    previewHiragana: String
) -> [CursorPrefixCandidateResult] {
    var resolutionCache: [String: CandidateDisplayResolution] = [:]
    let firstClauseCorrespondingCount = cursorPrefixFirstClauseCorrespondingCount(
        firstClauseResults: firstClauseResults,
        originalComposingText: originalComposingText,
        previewComposingText: previewComposingText,
        resolutionCache: &resolutionCache
    )
    return cursorPrefixCandidateDisplayResults(
        mainResults: mainResults,
        firstClauseResults: firstClauseResults,
        exactClauseResults: exactClauseResults,
        firstClauseCorrespondingCount: firstClauseCorrespondingCount,
        originalComposingText: originalComposingText,
        previewComposingText: previewComposingText,
        previewHiragana: previewHiragana,
        resolutionCache: &resolutionCache
    )
}

func cursorPrefixCandidateDisplayResults(
    mainResults: [Candidate],
    firstClauseResults: [Candidate],
    exactClauseResults: [Candidate] = [],
    firstClauseCorrespondingCount: Int?,
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    previewHiragana: String,
    resolutionCache: inout [String: CandidateDisplayResolution]
) -> [CursorPrefixCandidateResult] {
    guard let firstClauseCorrespondingCount else {
        return mainResults.map {
            CursorPrefixCandidateResult(
                candidate: $0,
                displayText: constructCandidateString(candidate: $0, hiragana: previewHiragana)
            )
        }
    }

    var seenTexts = Set<String>()
    var results: [CursorPrefixCandidateResult] = []

    func appendIfNeeded(_ candidate: Candidate) {
        let text = constructCandidateString(candidate: candidate, hiragana: previewHiragana)
        guard seenTexts.insert(text).inserted else {
            return
        }
        results.append(CursorPrefixCandidateResult(candidate: candidate, displayText: text))
    }

    func matchesFirstClauseBoundary(_ candidate: Candidate) -> Bool {
        let correspondingCount = resolveCandidateCompositionForDisplay(
            originalComposingText: originalComposingText,
            previewComposingText: previewComposingText,
            candidateComposingCount: candidate.composingCount,
            resolutionCache: &resolutionCache
        ).correspondingCount
        return correspondingCount == firstClauseCorrespondingCount
    }

    for candidate in firstClauseResults {
        guard matchesFirstClauseBoundary(candidate) else {
            continue
        }
        appendIfNeeded(candidate)
    }

    for candidate in mainResults {
        guard matchesFirstClauseBoundary(candidate) else {
            continue
        }
        appendIfNeeded(candidate)
    }

    for candidate in exactClauseResults {
        guard matchesFirstClauseBoundary(candidate) else {
            continue
        }
        appendIfNeeded(candidate)
    }

    return results
}

func cursorPrefixFirstClauseCorrespondingCount(
    firstClauseResults: [Candidate],
    originalComposingText: ComposingText,
    previewComposingText: ComposingText
) -> Int? {
    var resolutionCache: [String: CandidateDisplayResolution] = [:]
    return cursorPrefixFirstClauseCorrespondingCount(
        firstClauseResults: firstClauseResults,
        originalComposingText: originalComposingText,
        previewComposingText: previewComposingText,
        resolutionCache: &resolutionCache
    )
}

func cursorPrefixFirstClauseCorrespondingCount(
    firstClauseResults: [Candidate],
    originalComposingText: ComposingText,
    previewComposingText: ComposingText,
    resolutionCache: inout [String: CandidateDisplayResolution]
) -> Int? {
    let inputCount = originalComposingText.input.count
    let scoringContext = CursorPrefixBoundaryScoringContext(
        previewHiragana: previewComposingText.convertTarget
    )
    var splitBoundary: CursorPrefixBoundaryCandidate?
    var fallbackBoundary: CursorPrefixBoundaryCandidate?

    for (index, candidate) in firstClauseResults.enumerated() {
        let resolution = resolveCandidateCompositionForDisplay(
            originalComposingText: originalComposingText,
            previewComposingText: previewComposingText,
            candidateComposingCount: candidate.composingCount,
            resolutionCache: &resolutionCache
        )
        guard resolution.correspondingCount > 0 else {
            continue
        }

        let boundary = CursorPrefixBoundaryCandidate(
            index: index,
            correspondingCount: resolution.correspondingCount,
            score: cursorPrefixBoundaryScore(
                candidate: candidate,
                candidateIndex: index,
                resolution: resolution,
                context: scoringContext
            )
        )

        if resolution.correspondingCount < inputCount,
           preferCursorPrefixBoundary(boundary, over: splitBoundary)
        {
            splitBoundary = boundary
        }
        if preferCursorPrefixBoundary(boundary, over: fallbackBoundary) {
            fallbackBoundary = boundary
        }
    }

    return splitBoundary?.correspondingCount ?? fallbackBoundary?.correspondingCount
}

func makeCursorPrefixExactClauseComposingText(
    prefixComposingText: ComposingText,
    correspondingCount: Int
) -> ComposingText {
    var clauseComposingText = ComposingText()
    let count = clampedCorrespondingCount(
        composingText: prefixComposingText,
        rawCount: correspondingCount
    )
    clauseComposingText.insertAtCursorPosition(
        Array(prefixComposingText.input.prefix(count))
    )
    return clauseComposingText
}

func getOptions(context: String = "") -> ConvertRequestOptions {
    getOptions(
        context: context,
        zenzaiEnabled: effectiveZenzaiRuntimeEnabled(
            isConfigured: (config["enable"] as? Bool) ?? false,
            backend: config["backend"] as? String,
            cpuBackendSupported: cpuZenzaiBackendSupportedFromEnvironment()
        )
    )
}

func getOptions(
    context: String = "",
    zenzaiEnabled: Bool
) -> ConvertRequestOptions {
    let zenzaiEnabled = zenzaiEnabled && !privateInputActive
    configureEngineRuntime(zenzaiEnabled: zenzaiEnabled)
    let emojiURL = execURL
        .appendingPathComponent("EmojiDictionary")
        .appendingPathComponent("emoji_all_E15.1.txt")
    return makeConvertRequestOptions(
        context: privateInputActive ? "" : context,
        zenzaiEnabled: zenzaiEnabled,
        runtimeDirectoryURL: converterRuntimeDirectoryURL(),
        emojiDictionaryURL: emojiURL,
        textReplacer: emojiTextReplacerCache.value(for: emojiURL),
        zenzaiWeightURL: converterModelURL(),
        profile: (config["profile"] as? String) ?? "",
        learningType: privateInputActive ? .nothing : currentLearningType,
        learningMemoryDirectoryURL: currentLearningMemoryDirectoryURL,
        experimentalKeyboardTypoCorrection: !privateInputActive
            && ((config["experimentalTypoCorrection"] as? Bool) ?? defaultKeyboardTypoCorrection),
        predictiveConversion: isPredictiveConversionEnabled()
    )
}

func currentRuntimeZenzaiEnabled() -> Bool {
    effectiveZenzaiRuntimeEnabled(
        isConfigured: (config["enable"] as? Bool) ?? false,
        backend: config["backend"] as? String,
        cpuBackendSupported: cpuZenzaiBackendSupportedFromEnvironment()
    )
}

/// 未確定のローマ字（子音アルファベット）が末尾に残っているかを判定する。
/// この状態では通常の文節ごとの予測探索を止め、末尾に合う既出候補と
/// 探索量を制限した読み全体の辞書候補を使う。
func hasTrailingUncompletedRoman(in composingText: ComposingText) -> Bool {
    guard let lastChar = composingText.convertTarget.last else {
        return false
    }
    return lastChar.isASCII && lastChar.isLetter
}

/// 通常N-best補完検索用オプションを生成する。
/// 補完検索はZenzaiがカバーしない文節候補のみをマージするためのものなので、予測変換は不要。
func optionsForNormalNBestSupplement(from options: ConvertRequestOptions) -> ConvertRequestOptions {
    var supplementOptions = options
    supplementOptions.requireJapanesePrediction = .disabled
    supplementOptions.requireEnglishPrediction = .disabled
    return supplementOptions
}

func optionsForZenzaiConversion(from options: ConvertRequestOptions) -> ConvertRequestOptions {
    var zenzaiOptions = options.disablingZenzaiRichCandidates()
    zenzaiOptions.requireJapanesePrediction = .disabled
    zenzaiOptions.requireEnglishPrediction = .disabled
    return zenzaiOptions
}

func optionsForNormalNBestSupplementWithPrediction(from options: ConvertRequestOptions) -> ConvertRequestOptions {
    var supplementOptions = options
    if supplementOptions.requireJapanesePrediction == .autoMix {
        supplementOptions.requireJapanesePrediction = .manualMix
    }
    if supplementOptions.requireEnglishPrediction == .autoMix {
        supplementOptions.requireEnglishPrediction = .manualMix
    }
    return supplementOptions
}

private struct ZenzaiDiagnosticSnapshot {
    let configuredEnabled: Bool
    let backend: String
    let normalizedBackend: String
    let profileLength: Int
    let cpuBackendSupported: Bool
    let runtimeEnabled: Bool
}

private func zenzaiDiagnosticSnapshot() -> ZenzaiDiagnosticSnapshot {
    let configuredEnabled = (config["enable"] as? Bool) ?? false
    let backend = (config["backend"] as? String) ?? "cpu"
    let profile = (config["profile"] as? String) ?? ""
    let cpuBackendSupported = cpuZenzaiBackendSupportedFromEnvironment()
    return ZenzaiDiagnosticSnapshot(
        configuredEnabled: configuredEnabled,
        backend: backend,
        normalizedBackend: normalizedZenzaiBackend(backend),
        profileLength: profile.count,
        cpuBackendSupported: cpuBackendSupported,
        runtimeEnabled: effectiveZenzaiRuntimeEnabled(
            isConfigured: configuredEnabled && !privateInputActive,
            backend: backend,
            cpuBackendSupported: cpuBackendSupported
        )
    )
}

private func sanitizeDiagnosticField(_ value: String, maxLength: Int = 80) -> String {
    let text = String(value.map { character -> Character in
        switch character {
        case "\t", "\r", "\n", ";":
            return " "
        default:
            return character
        }
    })
    if text.count <= maxLength {
        return text
    }
    return String(text.prefix(maxLength))
}

private func zenzaiDiagnosticDetails(
    snapshot: ZenzaiDiagnosticSnapshot,
    contextLength: Int,
    inputCount: Int,
    hiraganaLength: Int,
    previewHiraganaLength: Int? = nil,
    useZenzai: Bool,
    syntheticEndOfText: Bool? = nil,
    continuation: ConversionContinuation? = nil,
    combinedHiraganaLength: Int? = nil
) -> String {
    var fields = [
        "configured_zenzai=\(snapshot.configuredEnabled)",
        "runtime_zenzai=\(snapshot.runtimeEnabled)",
        "use_zenzai=\(useZenzai)",
        "backend=\(sanitizeDiagnosticField(snapshot.normalizedBackend))",
        "backend_raw=\(sanitizeDiagnosticField(snapshot.backend))",
        "cpu_backend_supported=\(snapshot.cpuBackendSupported)",
        "profile_len=\(snapshot.profileLength)",
        "context_len=\(contextLength)",
        "input_count=\(inputCount)",
        "hiragana_len=\(hiraganaLength)",
    ]
    if let previewHiraganaLength {
        fields.append("preview_hiragana_len=\(previewHiraganaLength)")
    }
    if let syntheticEndOfText {
        fields.append("synthetic_end_of_text=\(syntheticEndOfText)")
    }
    fields.append("continuation_active=\(continuation != nil)")
    fields.append("continuation_reading_len=\(continuation?.reading.count ?? 0)")
    fields.append("continuation_surface_len=\(continuation?.surface.count ?? 0)")
    if let combinedHiraganaLength {
        fields.append("combined_hiragana_len=\(combinedHiraganaLength)")
    }
    return fields.joined(separator: ";")
}

private func zenzaiDiagnosticsEnabled(useZenzai: Bool) -> Bool {
    serverLogCallbacks.isLogEnabled(level: "DEBUG")
        || serverLogCallbacks.isPerformanceLogEnabled()
        || (useZenzai && serverLogCallbacks.isCrashTraceEnabled())
}

private func makeWarmupComposingText(
    input: String,
    inputStyle: InputStyle
) -> ComposingText {
    var warmupComposingText = ComposingText()
    warmupComposingText.insertAtCursorPosition(input, inputStyle: inputStyle)
    return warmupComposingText
}

func makeWarmupComposingText(
    zenzaiRuntimeEnabled: Bool? = nil,
    inputStyle: InputStyle? = nil
) -> ComposingText {
    let selectedInputStyle = inputStyle ?? currentInputStyle
    let useZenzaiWarmup = zenzaiRuntimeEnabled ?? currentRuntimeZenzaiEnabled()
    guard useZenzaiWarmup else {
        return makeWarmupComposingText(input: "a", inputStyle: selectedInputStyle)
    }

    return makeWarmupComposingText(input: zenzaiWarmupRomanInput, inputStyle: .roman2kana)
}

// InputStyleManager stores custom tables in an unsynchronized process-wide
// dictionary. Warmup uses direct input to avoid touching the romaji table registry.
func makeBackgroundWarmupComposingText(
    zenzaiRuntimeEnabled: Bool
) -> ComposingText {
    makeWarmupComposingText(
        input: zenzaiRuntimeEnabled ? "にほんご" : "あ",
        inputStyle: .direct
    )
}

class SimpleComposingText {
    init(text: String, cursor: Int) {
        self.text = UnsafeMutablePointer<CChar>(mutating: text.utf8String)!
        self.cursor = cursor
    }

    var text: UnsafeMutablePointer<CChar>
    var cursor: Int
}

struct SComposingText {
    var text: UnsafeMutablePointer<CChar>
    var cursor: Int
}

func constructCandidateString(candidate: Candidate, hiragana: String) -> String {
    if candidate.data.count == 1 && candidate.data[0].metadata.contains(.isKeyboardTypoCorrection) {
        return candidate.text
    }
    if candidate.rubyCount > hiragana.count && !candidate.text.isEmpty {
        return candidate.text
    }

    var result = ""
    result.reserveCapacity(hiragana.count)

    var remainingStart = hiragana.startIndex
    var remainingCount = hiragana.count
    for data in candidate.data {
        let rubyCount = data.ruby.count
        if remainingCount < rubyCount {
            result += hiragana[remainingStart...]
            break
        }

        remainingStart = hiragana.index(remainingStart, offsetBy: rubyCount)
        remainingCount -= rubyCount
        result += data.word
    }

    return result
}

func hiraganaToKatakana(_ text: String) -> String {
    var scalars = String.UnicodeScalarView()
    scalars.reserveCapacity(text.unicodeScalars.count)

    for scalar in text.unicodeScalars {
        let value = scalar.value
        if (0x3041...0x3096).contains(value), let converted = UnicodeScalar(value + 0x60) {
            scalars.append(converted)
        } else {
            scalars.append(scalar)
        }
    }

    return String(scalars)
}

func shouldKeepZenzaiAlternativeCandidate(candidate: Candidate, hiragana: String) -> Bool {
    guard candidate.rubyCount >= hiragana.count else {
        return false
    }

    let text = constructCandidateString(candidate: candidate, hiragana: hiragana)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else {
        return false
    }

    return text != hiragana && text != hiraganaToKatakana(hiragana)
}

private func displayRubyForKeyboardTypoLookup(_ ruby: String) -> String {
    var displayRuby = ruby.replacingOccurrences(of: "-", with: "ー")
    if displayRuby.last == "n" {
        displayRuby.removeLast()
        displayRuby.append("ン")
    }
    return displayRuby
}

private func hasLexicalAlternativeAcrossWholeSpan(
    candidate: Candidate,
    rawTypoSpan: String,
    originalSurfaceRange: Range<Int>
) -> Bool {
    guard !rawTypoSpan.isEmpty else {
        return false
    }

    let elements = candidate.data.map { element in
        (
            ruby: displayRubyForKeyboardTypoLookup(element.ruby),
            word: element.word,
            isKeyboardTypoCorrection: element.metadata.contains(.isKeyboardTypoCorrection)
        )
    }
    var elementStartOffsets: [Int] = []
    elementStartOffsets.reserveCapacity(elements.count)
    var offset = 0
    for element in elements {
        elementStartOffsets.append(offset)
        offset += element.ruby.count
    }

    for startIndex in elements.indices {
        let startOffset = elementStartOffsets[startIndex]
        if startOffset != originalSurfaceRange.lowerBound {
            continue
        }

        var ruby = ""
        var word = ""
        for endIndex in startIndex ..< elements.endIndex {
            let element = elements[endIndex]
            if element.isKeyboardTypoCorrection {
                break
            }
            ruby += element.ruby
            word += element.word

            guard rawTypoSpan.hasPrefix(ruby) else {
                break
            }
            guard ruby == rawTypoSpan else {
                continue
            }

            let endOffset = elementStartOffsets[endIndex]
                + element.ruby.count
            if endOffset != originalSurfaceRange.upperBound {
                break
            }
            return hiraganaToKatakana(word) != rawTypoSpan
        }
    }
    return false
}

func confidentKeyboardTypoCorrectionForZenzaiMerge(
    zenzaiResults: [Candidate],
    normalNBestResults: [Candidate],
    hiragana: String
) -> Candidate? {
    guard let zenzaiTop = zenzaiResults.first else {
        return nil
    }

    // Zenzai already selected a path through the bounded rewrite. Preserve its
    // semantic/surface decision (for example, こんにちは vs 今日は).
    guard zenzaiTop.keyboardTypoCorrections.isEmpty else {
        return nil
    }

    let hiraganaCharacters = Array(hiragana)
    func canPromoteRewriteCandidate(_ candidate: Candidate) -> Bool {
        guard candidate.keyboardTypoCorrections.count == 1,
              let correction = candidate.keyboardTypoCorrections.first,
              correction.originalSurfaceRange.lowerBound >= 0,
              correction.originalSurfaceRange.upperBound <= hiraganaCharacters.count
        else {
            return false
        }

        let rawTypoSpan = hiraganaToKatakana(
            String(hiraganaCharacters[correction.originalSurfaceRange])
        )
        let hasWholeSpanLexicalAlternative = hasLexicalAlternativeAcrossWholeSpan(
            candidate: zenzaiTop,
            rawTypoSpan: rawTypoSpan,
            originalSurfaceRange: correction.originalSurfaceRange
        )
        return !rawTypoSpan.isEmpty && !hasWholeSpanLexicalAlternative
    }

    if let correctedZenzaiCandidate = zenzaiResults.dropFirst().first(where: canPromoteRewriteCandidate) {
        return correctedZenzaiCandidate
    }
    if let normalTop = normalNBestResults.first,
       canPromoteRewriteCandidate(normalTop)
    {
        return normalTop
    }

    return nil
}

func mergeZenzaiMainResultsWithNormalNBest(
    zenzaiResults: [Candidate],
    normalNBestResults: [Candidate],
    hiragana: String,
    filterZenzaiAlternatives: Bool = true
) -> [Candidate] {
    var seenTexts = Set<String>(minimumCapacity: zenzaiResults.count + normalNBestResults.count)
    var results: [Candidate] = []
    results.reserveCapacity(zenzaiResults.count + normalNBestResults.count)
    let katakanaHiragana = hiraganaToKatakana(hiragana)

    func appendIfNeeded(_ candidate: Candidate, precomputedText: String? = nil) {
        let text = precomputedText ?? constructCandidateString(candidate: candidate, hiragana: hiragana)
        guard seenTexts.insert(text).inserted else {
            return
        }
        results.append(candidate)
    }

    if let correction = confidentKeyboardTypoCorrectionForZenzaiMerge(
        zenzaiResults: zenzaiResults,
        normalNBestResults: normalNBestResults,
        hiragana: hiragana
    ) {
        appendIfNeeded(correction)
    }
    if let topCandidate = zenzaiResults.first {
        appendIfNeeded(topCandidate)
    }
    for candidate in zenzaiResults.dropFirst() {
        if filterZenzaiAlternatives {
            guard candidate.rubyCount >= hiragana.count else {
                continue
            }
            let rawText = constructCandidateString(candidate: candidate, hiragana: hiragana)
            let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, trimmed != hiragana, trimmed != katakanaHiragana else {
                continue
            }
            appendIfNeeded(candidate, precomputedText: rawText)
        } else {
            appendIfNeeded(candidate)
        }
    }
    for candidate in normalNBestResults {
        appendIfNeeded(candidate)
    }

    return results
}

func mergePredictiveSupplementCandidates(
    exactMerged: [Candidate],
    predictionResults: [Candidate],
    hiragana: String,
    maxPredictions: Int = 3,
    preferPredictionsAfterTopCandidate: Bool = false,
    maxExactBeforePredictions: Int? = 3
) -> [Candidate] {
    guard maxPredictions > 0, !predictionResults.isEmpty else {
        return exactMerged
    }
    let katakanaHiragana = hiraganaToKatakana(hiragana)
    var seenTexts = Set<String>(minimumCapacity: exactMerged.count + maxPredictions)
    for candidate in exactMerged {
        seenTexts.insert(constructCandidateString(candidate: candidate, hiragana: hiragana))
    }
    var filteredPredictions: [Candidate] = []
    filteredPredictions.reserveCapacity(min(maxPredictions, predictionResults.count))
    for candidate in predictionResults {
        let text = constructCandidateString(candidate: candidate, hiragana: hiragana)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text != hiragana, text != katakanaHiragana else {
            continue
        }
        guard seenTexts.insert(text).inserted else {
            continue
        }
        filteredPredictions.append(candidate)
        if filteredPredictions.count >= maxPredictions {
            break
        }
    }
    guard !filteredPredictions.isEmpty else {
        return exactMerged
    }
    // With a pending syllable the other full-span entries often differ only in
    // how they display the raw consonant. Leave room for completions near Top 1.
    let firstPartialIndex = exactMerged.indices.dropFirst().first { exactMerged[$0].rubyCount < hiragana.count }
        ?? exactMerged.endIndex
    let splitIndex: Int
    if preferPredictionsAfterTopCandidate {
        splitIndex = min(1, exactMerged.count)
    } else if let maxExactBeforePredictions {
        splitIndex = min(firstPartialIndex, min(maxExactBeforePredictions, exactMerged.count))
    } else {
        splitIndex = firstPartialIndex
    }
    var combined: [Candidate] = []
    combined.reserveCapacity(exactMerged.count + filteredPredictions.count)
    combined.append(contentsOf: exactMerged[..<splitIndex])
    combined.append(contentsOf: filteredPredictions)
    combined.append(contentsOf: exactMerged[splitIndex...])
    return combined
}

func extendsKanaReading(_ candidate: Candidate, normalizedReading: String) -> Bool {
    guard candidate.rubyCount > normalizedReading.count,
          candidate.inputable,
          candidate.keyboardTypoCorrections.isEmpty,
          !candidate.data.contains(where: { $0.metadata.contains(.isKeyboardTypoCorrection) }) else {
        return false
    }
    return learningCandidateRuby(candidate).hasPrefix(normalizedReading)
}

func promoteKanaReadingCompletion(
    in candidates: inout [Candidate], normalizedReading: String, afterFullSpan: Bool = false
) {
    guard normalizedReading.count >= 2,
          let first = candidates.first,
          !extendsKanaReading(first, normalizedReading: normalizedReading),
          let index = candidates.dropFirst().firstIndex(where: {
              extendsKanaReading($0, normalizedReading: normalizedReading)
          }) else { return }
    let normalizedCount = normalizedReading.count
    let insertionIndex = afterFullSpan
        ? (candidates.lastIndex {
            $0.rubyCount == normalizedCount && learningCandidateRuby($0) == normalizedReading
        }.map { $0 + 1 } ?? 1)
        : 1
    guard index > insertionIndex else { return }
    let completion = candidates.remove(at: index)
    candidates.insert(completion, at: insertionIndex)
}

func cursorPrefixBoundaryFirstClauseResults(
    zenzaiFirstClauseResults: [Candidate],
    mergedFirstClauseResults: [Candidate]
) -> [Candidate] {
    zenzaiFirstClauseResults.isEmpty ? mergedFirstClauseResults : zenzaiFirstClauseResults
}

func mergeDistinctPredictionCandidates(
    primary: [Candidate],
    fallback: [Candidate],
    hiragana: String
) -> [Candidate] {
    guard !primary.isEmpty else {
        return fallback
    }
    var seenTexts = Set<String>()
    var combined: [Candidate] = []
    combined.reserveCapacity(primary.count + fallback.count)
    for candidate in primary {
        let text = constructCandidateString(candidate: candidate, hiragana: hiragana)
        if seenTexts.insert(text).inserted {
            combined.append(candidate)
        }
    }
    for candidate in fallback {
        let text = constructCandidateString(candidate: candidate, hiragana: hiragana)
        if seenTexts.insert(text).inserted {
            combined.append(candidate)
        }
    }
    return combined
}

private func requestNormalNBestSupplementCandidates(
    converter targetConverter: KanaKanjiConverter = normalNBestSupplementConverter,
    inputData: ComposingText,
    options: ConvertRequestOptions,
    sessionID: String? = nil,
    cacheOnlyJapanesePrediction: Bool = false,
    prefixConstraintSurface: String? = nil,
    stage: String = "request_normal_nbest_supplement",
    operation: String,
    diagnosticDetails: String
) -> ConversionResult {
    var normalOptions = options
    normalOptions.zenzaiMode = .off

    let requestStart = performanceNow()
    let converted = targetConverter.requestCandidates(
        inputData,
        options: normalOptions,
        sessionID: sessionID,
        cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction,
        prefixConstraintSurface: prefixConstraintSurface
    )
    let requestMs = elapsedPerformanceMilliseconds(since: requestStart)
    performanceLog(
        operation: operation,
        stage: stage,
        elapsedMs: requestMs,
        details: "candidate_count=\(converted.mainResults.count);\(diagnosticDetails)"
    )
    serverLog(
        "DEBUG",
        "\(operation): normal N-best supplement returned candidateCount=\(converted.mainResults.count) \(diagnosticDetails)"
    )

    return converted
}

private final class UncheckedSendableBox<Value>: @unchecked Sendable {
    var value: Value
    init(_ value: Value) {
        self.value = value
    }
}

private let normalNBestSupplementQueue = DispatchQueue(
    label: "zerocon.nbest.supplement",
    qos: .userInitiated
)

struct ZenzaiPredictionContinuationRequest {
    let inputData: ComposingText
    let options: ConvertRequestOptions
    let prefixConstraintSurface: String
    let cacheOnlyJapanesePrediction: Bool
    let skipWhenSufficientPredictions: Bool
}

struct ZenzaiSupplementExecutionResult {
    let zenzai: ConversionResult
    let supplement: ConversionResult
    let usedDirectModelFirst: Bool
    let fellBackToAllZenzai: Bool
    let ranInParallel: Bool
    let nbestNs: UInt64
    let predictionContinuationResult: ConversionResult?
    let skippedPredictionContinuation: Bool

    init(
        zenzai: ConversionResult,
        supplement: ConversionResult,
        usedDirectModelFirst: Bool,
        fellBackToAllZenzai: Bool,
        ranInParallel: Bool,
        nbestNs: UInt64,
        predictionContinuationResult: ConversionResult? = nil,
        skippedPredictionContinuation: Bool = false
    ) {
        self.zenzai = zenzai
        self.supplement = supplement
        self.usedDirectModelFirst = usedDirectModelFirst
        self.fellBackToAllZenzai = fellBackToAllZenzai
        self.ranInParallel = ranInParallel
        self.nbestNs = nbestNs
        self.predictionContinuationResult = predictionContinuationResult
        self.skippedPredictionContinuation = skippedPredictionContinuation
    }
}

func executeZenzaiWithNormalNBestSupplement(
    mainConverter: KanaKanjiConverter = converter,
    supplementConverter: KanaKanjiConverter = normalNBestSupplementConverter,
    inputData: ComposingText,
    zenzaiOptions: ConvertRequestOptions,
    supplementOptions: ConvertRequestOptions,
    sessionID: String? = nil,
    cacheOnlyJapanesePrediction: Bool = false,
    prefixConstraintSurface: String? = nil,
    predictionContinuation: ZenzaiPredictionContinuationRequest? = nil,
    emitLogs: Bool = true,
    operation: String,
    diagnosticDetails: String
) -> ZenzaiSupplementExecutionResult {
    var normalOptions = supplementOptions
    normalOptions.zenzaiMode = .off

    func runSupplementSync() -> (result: ConversionResult, lattice: ReusableFullInputLattice?, elapsedNs: UInt64) {
        let nbStart = DispatchTime.now().uptimeNanoseconds
        let converted = supplementConverter.requestCandidates(
            inputData,
            options: normalOptions,
            sessionID: sessionID,
            cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction,
            prefixConstraintSurface: prefixConstraintSurface
        )
        let exported = supplementConverter.exportReusableFullInputLattice(for: inputData)
        return (converted, exported, DispatchTime.now().uptimeNanoseconds - nbStart)
    }

    func logSupplementIfNeeded(_ result: ConversionResult, elapsedNs: UInt64) {
        guard emitLogs else {
            return
        }
        performanceLog(
            operation: operation,
            stage: "request_normal_nbest_supplement",
            elapsedMs: Int(elapsedNs / 1_000_000),
            details: "candidate_count=\(result.mainResults.count);\(diagnosticDetails)"
        )
        serverLog(
            "DEBUG",
            "\(operation): normal N-best supplement returned candidateCount=\(result.mainResults.count) \(diagnosticDetails)"
        )
    }

    func logPredictionContinuationIfNeeded(_ result: ConversionResult, elapsedNs: UInt64) {
        guard emitLogs else {
            return
        }
        performanceLog(
            operation: "\(operation)_prediction_continuation",
            stage: "request_prediction_continuation",
            elapsedMs: Int(elapsedNs / 1_000_000),
            details: "candidate_count=\(result.mainResults.count);\(diagnosticDetails)"
        )
        serverLog(
            "DEBUG",
            "\(operation)_prediction_continuation: normal N-best supplement returned candidateCount=\(result.mainResults.count) \(diagnosticDetails)"
        )
    }

    let prep = mainConverter.prepareDirectZenzaiGeneration(for: inputData, options: zenzaiOptions)
    if prep.eligible {
        let canParallel = prep.isGpuOffloaded || ProcessInfo.processInfo.processorCount >= 4
        let supplementPayload: (result: ConversionResult, lattice: ReusableFullInputLattice?, elapsedNs: UInt64)
        let inlinePredResult: ConversionResult?
        let inlineSkippedPred: Bool
        let directOutcome: DirectZenzaiGenerationResult

        if canParallel {
            typealias ParallelOutput = (
                result: ConversionResult,
                lattice: ReusableFullInputLattice?,
                elapsedNs: UInt64,
                predictionContinuationResult: ConversionResult?,
                predictionContinuationNs: UInt64,
                skippedPredictionContinuation: Bool
            )
            let semaphore = DispatchSemaphore(value: 0)
            let inputBox = UncheckedSendableBox((supplementConverter, inputData, normalOptions, sessionID, cacheOnlyJapanesePrediction, prefixConstraintSurface, predictionContinuation))
            let outputBox = UncheckedSendableBox<ParallelOutput?>(nil)

            normalNBestSupplementQueue.async {
                let args = inputBox.value
                let nbStart = DispatchTime.now().uptimeNanoseconds
                let converted = args.0.requestCandidates(
                    args.1,
                    options: args.2,
                    sessionID: args.3,
                    cacheOnlyJapanesePrediction: args.4,
                    prefixConstraintSurface: args.5
                )
                let exported = args.0.exportReusableFullInputLattice(for: args.1)
                let elapsedNs = DispatchTime.now().uptimeNanoseconds - nbStart

                var predResult: ConversionResult?
                var predNs: UInt64 = 0
                var skippedPred = false
                if let predCont = args.6 {
                    if predCont.skipWhenSufficientPredictions && converted.predictionResults.count >= 3 {
                        skippedPred = true
                    } else {
                        var predOptions = predCont.options
                        predOptions.zenzaiMode = .off
                        predOptions.N_best = min(predOptions.N_best, 3)
                        let predStart = DispatchTime.now().uptimeNanoseconds
                        predResult = args.0.requestCandidates(
                            predCont.inputData,
                            options: predOptions,
                            sessionID: "continuation-prediction",
                            cacheOnlyJapanesePrediction: predCont.cacheOnlyJapanesePrediction,
                            prefixConstraintSurface: predCont.prefixConstraintSurface
                        )
                        predNs = DispatchTime.now().uptimeNanoseconds - predStart
                    }
                }

                outputBox.value = (converted, exported, elapsedNs, predResult, predNs, skippedPred)
                semaphore.signal()
            }

            directOutcome = mainConverter.runDirectZenzaiGenerationOnly(inputData, options: zenzaiOptions)
            semaphore.wait()

            if let parallelOutput = outputBox.value {
                supplementPayload = (parallelOutput.result, parallelOutput.lattice, parallelOutput.elapsedNs)
                inlinePredResult = parallelOutput.predictionContinuationResult
                inlineSkippedPred = parallelOutput.skippedPredictionContinuation
                if let predResult = parallelOutput.predictionContinuationResult {
                    logPredictionContinuationIfNeeded(predResult, elapsedNs: parallelOutput.predictionContinuationNs)
                }
            } else {
                supplementPayload = runSupplementSync()
                inlinePredResult = nil
                inlineSkippedPred = false
            }
        } else {
            supplementPayload = runSupplementSync()
            inlinePredResult = nil
            inlineSkippedPred = false
            directOutcome = mainConverter.runDirectZenzaiGenerationOnly(inputData, options: zenzaiOptions)
        }

        logSupplementIfNeeded(supplementPayload.result, elapsedNs: supplementPayload.elapsedNs)

        if let finalized = mainConverter.finalizeDirectZenzaiResult(
            directOutcome,
            inputData: inputData,
            options: zenzaiOptions,
            normalNBestResults: supplementPayload.result.mainResults,
            reusableLattice: supplementPayload.lattice
        ) {
            return ZenzaiSupplementExecutionResult(
                zenzai: finalized,
                supplement: supplementPayload.result,
                usedDirectModelFirst: true,
                fellBackToAllZenzai: false,
                ranInParallel: canParallel,
                nbestNs: supplementPayload.elapsedNs,
                predictionContinuationResult: inlinePredResult,
                skippedPredictionContinuation: inlineSkippedPred
            )
        }

        mainConverter.adoptReusableFullInputLattice(supplementPayload.lattice)
        let fallbackConverted = mainConverter.requestCandidates(
            inputData,
            options: zenzaiOptions,
            sessionID: sessionID,
            cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction
        )
        return ZenzaiSupplementExecutionResult(
            zenzai: fallbackConverted,
            supplement: supplementPayload.result,
            usedDirectModelFirst: false,
            fellBackToAllZenzai: true,
            ranInParallel: canParallel,
            nbestNs: supplementPayload.elapsedNs,
            predictionContinuationResult: inlinePredResult,
            skippedPredictionContinuation: inlineSkippedPred
        )
    }

    let supplementPayload = runSupplementSync()
    logSupplementIfNeeded(supplementPayload.result, elapsedNs: supplementPayload.elapsedNs)
    mainConverter.adoptReusableFullInputLattice(supplementPayload.lattice)
    let zenzaiResult = mainConverter.requestCandidates(
        inputData,
        options: zenzaiOptions,
        sessionID: sessionID,
        cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction
    )
    return ZenzaiSupplementExecutionResult(
        zenzai: zenzaiResult,
        supplement: supplementPayload.result,
        usedDirectModelFirst: false,
        fellBackToAllZenzai: false,
        ranInParallel: false,
        nbestNs: supplementPayload.elapsedNs
    )
}

struct RuntimeTuningProbeConfig: Codable, Sendable, Equatable {
    let decodeThreads: Int32
    let batchThreads: Int32?
    let directModelFirst: Bool
}

struct RuntimeTuningProbeMeasurement: Codable, Sendable {
    let decodeThreads: Int32
    let batchThreads: Int32?
    let directModelFirst: Bool
    let meanNs: UInt64
    let completedSamples: Int
}

func probeRuntimeTuningConfigs(
    mainConverter: KanaKanjiConverter = converter,
    supplementConverter: KanaKanjiConverter = normalNBestSupplementConverter,
    baseOptions: ConvertRequestOptions,
    configs: [RuntimeTuningProbeConfig],
    shouldCancel: () -> Bool = { false }
) -> [RuntimeTuningProbeMeasurement]? {
    guard !configs.isEmpty, !shouldCancel() else {
        return nil
    }
    let zenzaiOptions = optionsForZenzaiConversion(from: baseOptions)
    var supplementOptions = optionsForNormalNBestSupplement(from: zenzaiOptions)
    supplementOptions.zenzaiMode = .off

    let sampleReadings = [
        "きょうはいいてんき",
        "きしゃがしゅざいする",
        "あしたのよていをかくにんする",
        "あしたのかいぎのしりょうをじゅんびしてかられんらくします",
    ]
    var sampleInputs: [ComposingText] = []
    sampleInputs.reserveCapacity(sampleReadings.count)
    for reading in sampleReadings {
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        sampleInputs.append(input)
    }

    var warmupInput = ComposingText()
    warmupInput.insertAtCursorPosition("とうきょう", inputStyle: .direct)

    defer {
        ZenzaiRuntimeTuning.reset()
        mainConverter.stopComposition()
        supplementConverter.stopComposition()
    }

    // Warm up using the highest-thread config so the worker pool and DVFS clocks are active before timing starts.
    let initialConfig = configs.max(by: {
        ($0.batchThreads ?? $0.decodeThreads) < ($1.batchThreads ?? $1.decodeThreads)
    }) ?? configs[0]
    ZenzaiRuntimeTuning.configure(
        decodeThreads: initialConfig.decodeThreads,
        batchThreads: initialConfig.batchThreads,
        directModelFirstOverride: initialConfig.directModelFirst
    )
    mainConverter.stopComposition()
    supplementConverter.stopComposition()
    _ = executeZenzaiWithNormalNBestSupplement(
        mainConverter: mainConverter,
        supplementConverter: supplementConverter,
        inputData: warmupInput,
        zenzaiOptions: zenzaiOptions,
        supplementOptions: supplementOptions,
        cacheOnlyJapanesePrediction: false,
        emitLogs: false,
        operation: "runtime_tuning_warmup",
        diagnosticDetails: ""
    )
    guard !shouldCancel() else {
        return nil
    }

    func measureRound(for config: RuntimeTuningProbeConfig) -> (meanNs: UInt64, samples: Int)? {
        guard !shouldCancel() else {
            return nil
        }
        ZenzaiRuntimeTuning.configure(
            decodeThreads: config.decodeThreads,
            batchThreads: config.batchThreads,
            directModelFirstOverride: config.directModelFirst
        )
        var totalNs: UInt64 = 0
        var completed = 0
        for input in sampleInputs {
            guard !shouldCancel() else {
                return nil
            }
            mainConverter.stopComposition()
            supplementConverter.stopComposition()
            let started = DispatchTime.now().uptimeNanoseconds
            _ = executeZenzaiWithNormalNBestSupplement(
                mainConverter: mainConverter,
                supplementConverter: supplementConverter,
                inputData: input,
                zenzaiOptions: zenzaiOptions,
                supplementOptions: supplementOptions,
                cacheOnlyJapanesePrediction: false,
                emitLogs: false,
                operation: "runtime_tuning_probe",
                diagnosticDetails: ""
            )
            let elapsed = DispatchTime.now().uptimeNanoseconds - started
            totalNs += elapsed
            completed += 1
        }
        guard !shouldCancel(), completed > 0 else {
            return nil
        }
        return (totalNs / UInt64(completed), completed)
    }

    var roundMeansByConfig: [[UInt64]] = Array(repeating: [], count: configs.count)
    var totalSamplesByConfig: [Int] = Array(repeating: 0, count: configs.count)

    // Pass 1: screen every candidate configuration once.
    for (index, config) in configs.enumerated() {
        guard let result = measureRound(for: config) else {
            return nil
        }
        roundMeansByConfig[index].append(result.meanNs)
        totalSamplesByConfig[index] += result.samples
    }

    // Pass 2: confirm top 3 finalists (plus fastest max-decodeThreads config if absent) over 2 extra rounds.
    let rankedIndices = (0..<configs.count).sorted { roundMeansByConfig[$0][0] < roundMeansByConfig[$1][0] }
    var finalistIndices = Array(rankedIndices.prefix(3))
    let maxDecodeThreads = configs.map(\.decodeThreads).max() ?? 0
    if !finalistIndices.contains(where: { configs[$0].decodeThreads == maxDecodeThreads }),
       let fastestMaxIdx = rankedIndices.first(where: { configs[$0].decodeThreads == maxDecodeThreads }) {
        finalistIndices.append(fastestMaxIdx)
    }

    for confirmationRound in 0..<2 {
        let order = confirmationRound == 0 ? Array(finalistIndices.reversed()) : finalistIndices
        for index in order {
            guard let result = measureRound(for: configs[index]) else {
                return nil
            }
            roundMeansByConfig[index].append(result.meanNs)
            totalSamplesByConfig[index] += result.samples
        }
    }

    var measurements: [RuntimeTuningProbeMeasurement] = []
    measurements.reserveCapacity(configs.count)
    for index in 0..<configs.count {
        let rounds = roundMeansByConfig[index].sorted()
        let selectedMean = rounds.count >= 3 ? rounds[1] : rounds[0]
        measurements.append(
            RuntimeTuningProbeMeasurement(
                decodeThreads: configs[index].decodeThreads,
                batchThreads: configs[index].batchThreads,
                directModelFirst: configs[index].directModelFirst,
                meanNs: selectedMean,
                completedSamples: totalSamplesByConfig[index]
            )
        )
    }
    return measurements
}

private func _flushLearningForConfigReload() -> Bool {
    if flush_learning_memory() { return true }
    serverLog("WARN", "LoadConfig deferred because pending learning could not be saved")
    return false
}

@_silgen_name("LoadConfig")
public func load_config() {
    if !privateInputActive, !_flushLearningForConfigReload() { return }
    let loadedSettingsPath = settingsPath()
    var loadedSettings: AppSettings?
    var settingsLoadError: Error?
    do {
        let settings = try readAppSettings(at: loadedSettingsPath)
        loadedSettings = settings
    } catch {
        settingsLoadError = error
    }

    serverLog("INFO", "LoadConfig: start")
    let previousZenzaiEnabled = (config["enable"] as? Bool) ?? false
    let previousProfile = (config["profile"] as? String) ?? ""
    let previousBackend = (config["backend"] as? String) ?? "cpu"
    let previousGpuLayers = resolvedGpuLayerCount(config["gpuLayers"] as? Int)
    let previousExperimentalTypoCorrection =
        (config["experimentalTypoCorrection"] as? Bool) ?? defaultKeyboardTypoCorrection
    let previousEffectiveZenzaiEnabled = effectiveZenzaiRuntimeEnabled(
        isConfigured: previousZenzaiEnabled,
        backend: previousBackend,
        cpuBackendSupported: cpuZenzaiBackendSupportedFromEnvironment()
    )
    let previousUsedCustomRomajiTable = customRomajiTableEnabled
    let previousLearningType = currentLearningType
    let previousLearningMemoryDirectoryURL = currentLearningMemoryDirectoryURL
    config["enable"] = false
    config["profile"] = ""
    config["backend"] = "cpu"
    config["gpuLayers"] = 99
    config["model"] = "standard"
    config["cpuRuntimeTuning"] = nil
    config["experimentalTypoCorrection"] = defaultKeyboardTypoCorrection
    config["predictiveConversion"] = defaultPredictiveConversionEnabled
    config["postCompositionPrediction"] = nil
    setRoman2KanaInputStyle()
    currentLearningType = .inputAndOutput
    config["learningStrength"] = "model_first"
    currentLearningMemoryDirectoryURL = learningMemoryDirectoryURL(settingsPath: loadedSettingsPath)

    if let settings = loadedSettings {
        serverLog("INFO", "LoadConfig: reading settingsPath=\(loadedSettingsPath.path)")

        if let zenzai = settings.zenzai {
            config["gpuLayers"] = Int(resolvedGpuLayerCount(zenzai.gpu_layers))
            config["model"] = zenzai.model ?? "nano_v4_2_iq4_emb8"
            if let enableValue = zenzai.enable {
                config["enable"] = enableValue
            }

            if let profileValue = zenzai.profile {
                config["profile"] = profileValue
            }

            if let backendValue = zenzai.backend {
                config["backend"] = backendValue
            }

            if let tuningValue = zenzai.cpu_runtime_tuning, tuningValue.decode_threads > 0 {
                config["cpuRuntimeTuning"] = tuningValue
            }
        }

        // Jev uses only ordinary KKC, including warmup and clause navigation.
        if settings.general?.jev_conversion == true {
            config["enable"] = false
        }

        applyRomajiInputStyle(rows: settings.romaji_table?.rows)
        currentLearningType = learningType(for: settings.learning?.mode)
        config["learningStrength"] = settings.learning?.strength ?? "model_first"
        config["experimentalTypoCorrection"] =
            settings.general?.experimental_typo_correction ?? defaultKeyboardTypoCorrection
        config["predictiveConversion"] =
            settings.general?.predictive_conversion ?? defaultPredictiveConversionEnabled

    } else if let settingsLoadError {
        serverLog("ERROR", "Failed to read settings: \(settingsLoadError)")
    } else {
        serverLog("WARN", "LoadConfig: APPDATA is not set. Using defaults.")
    }

    let currentZenzaiEnabled = (config["enable"] as? Bool) ?? false
    let currentProfile = (config["profile"] as? String) ?? ""
    let currentBackend = (config["backend"] as? String) ?? "cpu"
    let currentExperimentalTypoCorrection =
        (config["experimentalTypoCorrection"] as? Bool) ?? defaultKeyboardTypoCorrection
    let currentEffectiveZenzaiEnabled = effectiveZenzaiRuntimeEnabled(
        isConfigured: currentZenzaiEnabled,
        backend: currentBackend,
        cpuBackendSupported: cpuZenzaiBackendSupportedFromEnvironment()
    )
    let currentUsedCustomRomajiTable = customRomajiTableEnabled
    let backendChanged = normalizedZenzaiBackend(previousBackend) != normalizedZenzaiBackend(currentBackend)
    let gpuLayersChanged = previousGpuLayers != resolvedGpuLayerCount(config["gpuLayers"] as? Int)
    if previousEffectiveZenzaiEnabled != currentEffectiveZenzaiEnabled
        || previousProfile != currentProfile
        || backendChanged
        || gpuLayersChanged
        || previousUsedCustomRomajiTable != currentUsedCustomRomajiTable
        || previousExperimentalTypoCorrection != currentExperimentalTypoCorrection
    {
        if backendChanged || gpuLayersChanged {
            rebuildConverter()
        } else {
            converter.stopComposition()
            normalNBestSupplementConverter.stopComposition()
        }
        composingText = ComposingText()
        composingTextSnapshots.removeAll()
        clearLearningCandidateCache()
    }
    if previousLearningType != currentLearningType
        || previousLearningMemoryDirectoryURL != currentLearningMemoryDirectoryURL
    {
        clearLearningCandidateCache()
        clearPostCompositionPredictionState()
    }
    let legacyEntries = loadedSettings?.user_dictionary?.entries ?? []
    let dictionaryURL = loadedSettingsPath.deletingLastPathComponent().appendingPathComponent("user-dictionary.json")
    if installedUserDictionary == nil {
        userDictionaryReloader.invalidatePending()
        if let entries = loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacyEntries) {
            installUserDictionarySnapshot(
                UserDictionarySnapshot(entries: entries)
            )
        }
    } else {
        userDictionaryReloader.schedule {
            guard let entries = loadUserDictionaryEntries(dictionaryURL: dictionaryURL, legacyEntries: legacyEntries) else {
                return nil
            }
            return UserDictionarySnapshot(entries: entries)
        }
    }
    invalidateAutomaticConversionContinuationCache()
    if privateInputActive {
        learningConfigNeedsLoad = true
    } else {
        ensureLearningMemoryDirectoryIfNeeded()
        loadLearningSelectionOverrides()
        loadPostCompositionBigrams()
    }

    serverLog(
        "INFO",
        "LoadConfig: completed enable=\(currentZenzaiEnabled) backend=\(currentBackend) effectiveEnable=\(currentEffectiveZenzaiEnabled) customRomaji=\(currentUsedCustomRomajiTable) learningType=\(currentLearningType) experimentalTypoCorrection=\(currentExperimentalTypoCorrection)"
    )
}

@_silgen_name("Initialize")
public func initialize(
    path: UnsafePointer<CChar>,
    use_zenzai: Bool
) {
    let path = String(cString: path)
    serverLog("INFO", "Initialize: start path=\(path) use_zenzai=\(use_zenzai)")
    execURL = URL(filePath: path)
    emojiTextReplacerCache.reset()
    // 設定アプリが取得した辞書キャッシュを優先する（無ければ実行ファイル横）。
    converterDictionaryURL = dictionaryDirectoryURL(executableDirectory: execURL)
    converterPreloadDictionary = true
    ensureConverterRuntimeDirectory()
    let reverseIndexStart = performanceNow()
    do {
        try reconversionDictionary.load(from: converterDictionaryURL)
        serverLog(
            "INFO",
            "Initialize: reconversion dictionary loaded entries=\(reconversionDictionary.recordCount) elapsed_ms=\(elapsedPerformanceMilliseconds(since: reverseIndexStart))"
        )
    } catch {
        reconversionDictionary = ReconversionDictionary()
        serverLog("ERROR", "Initialize: failed to load reconversion dictionary: \(error)")
    }
    invalidateAutomaticConversionContinuationCache()
    clearLastCommittedContinuation()
    clearPostCompositionPredictionState()
    rebuildConverter()
    clearLearningCandidateCache()

    load_config()

    // Warm dictionary tables quickly without blocking Initialize on cold Zenzai GGUF load;
    // startup warmup() warms the foreground converter once after the IPC pipe is open.
    composingText = makeWarmupComposingText(
        zenzaiRuntimeEnabled: false
    )
    let diagnosticDetails = (serverLogCallbacks.isLogEnabled(level: "DEBUG")
        || serverLogCallbacks.isLogEnabled(level: "INFO")
        || serverLogCallbacks.isCrashTraceEnabled())
        ? zenzaiDiagnosticDetails(
            snapshot: zenzaiDiagnosticSnapshot(),
            contextLength: 0,
            inputCount: composingText.input.count,
            hiraganaLength: composingText.convertTarget.count,
            useZenzai: false
        )
        : ""
    let options = getOptions(zenzaiEnabled: false)
    crashTrace(operation: "Initialize", stage: "requestCandidates", state: "begin", details: diagnosticDetails)
    serverLog("DEBUG", "Initialize: requestCandidates begin \(diagnosticDetails)", flush: true)
    let converted = converter.requestCandidates(
        composingText,
        options: options
    )
    crashTrace(
        operation: "Initialize",
        stage: "requestCandidates",
        state: "completed",
        details: "candidate_count=\(converted.mainResults.count);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "Initialize: requestCandidates returned candidateCount=\(converted.mainResults.count) \(diagnosticDetails)")
    composingText = ComposingText()
    composingTextSnapshots.removeAll()
    serverLog(
        "INFO",
        "Initialize: completed inputStyle=\(String(describing: currentInputStyle)) warmupUseZenzai=false \(diagnosticDetails)"
    )
}

@_silgen_name("SetRequestId")
public func set_request_id(_ requestID: UInt64) {
    currentRequestId = requestID
}

@_silgen_name("Warmup")
public func warmup() -> Bool {
    guard composingText.input.isEmpty else {
        serverLog("DEBUG", "Warmup: skipped reason=active_composition")
        return false
    }
    let diagnosticSnapshot = zenzaiDiagnosticSnapshot()
    let warmupComposing = makeBackgroundWarmupComposingText(
        zenzaiRuntimeEnabled: diagnosticSnapshot.runtimeEnabled
    )
    let useZenzai = effectiveZenzaiEnabledForCandidates(
        isConfigured: diagnosticSnapshot.runtimeEnabled,
        inputCount: warmupComposing.input.count,
        hiraganaCount: warmupComposing.convertTarget.count
    )
    configureEngineRuntime(zenzaiEnabled: useZenzai)
    let contextString = (config["context"] as? String) ?? ""
    let options = getOptions(context: contextString, zenzaiEnabled: useZenzai)
    var cachedDetails: String?
    let details: () -> String = {
        if let cachedDetails {
            return cachedDetails
        }
        let built = zenzaiDiagnosticDetails(
            snapshot: diagnosticSnapshot,
            contextLength: contextString.count,
            inputCount: warmupComposing.input.count,
            hiraganaLength: warmupComposing.convertTarget.count,
            useZenzai: useZenzai
        ) + ";warmup_input_style=direct"
        cachedDetails = built
        return built
    }
    serverLog("DEBUG", "Warmup: requestCandidates begin \(details())", flush: true)
    let requestStart = performanceNow()
    let converted = converter.requestCandidates(
        warmupComposing,
        options: options
    )
    let requestMs = elapsedPerformanceMilliseconds(since: requestStart)
    if requestMs >= warmupRequestCandidatesWarningMs {
        serverLog(
            "WARN",
            "Warmup: requestCandidates slow elapsed_ms=\(requestMs);threshold_ms=\(warmupRequestCandidatesWarningMs) \(details())",
            flush: true
        )
    }
    performanceLog(
        operation: "warmup",
        stage: "request_candidates",
        elapsedMs: requestMs,
        details: "candidate_count=\(converted.mainResults.count);\(details())"
    )
    serverLog(
        "DEBUG",
        "Warmup: completed candidateCount=\(converted.mainResults.count) \(details())"
    )
    return true
}

@_silgen_name("HasActiveComposition")
public func has_active_composition() -> Bool {
    !composingText.input.isEmpty
}

@_silgen_name("InferReconversionReadings")
public func infer_reconversion_readings(
    surface: UnsafePointer<CChar>
) -> UnsafeMutablePointer<CChar>? {
    adoptPendingUserDictionary()
    let surfaceString = String(cString: surface)
    let readings = reconversionDictionary.inferReadings(for: surfaceString)
    guard !readings.isEmpty else {
        serverLog("DEBUG", "InferReconversionReading: unsupported surfaceLength=\(surfaceString.count)")
        return nil
    }
    guard JSONSerialization.isValidJSONObject(readings),
          let data = try? JSONSerialization.data(withJSONObject: readings),
          let encodedReadings = String(data: data, encoding: .utf8) else {
        serverLog("ERROR", "InferReconversionReading: failed to encode readings")
        return nil
    }
    serverLog(
        "DEBUG",
        "InferReconversionReading: completed surfaceLength=\(surfaceString.count) readingCount=\(readings.count)"
    )
    return _strdup(encodedReadings)
}

@_silgen_name("AppendText")
public func append_text(
    input: UnsafePointer<CChar>,
    cursorPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<CChar> {
    discardPinnedLearningCandidatesBeforeCompositionEdit()
    let inputString = String(cString: input)
    serverLog("DEBUG", "AppendText: start inputLength=\(inputString.count) inputStyle=\(String(describing: currentInputStyle))")
    composingText.insertAtCursorPosition(inputString, inputStyle: currentInputStyle)

    cursorPtr.pointee = CInt(composingText.convertTargetCursorPosition)
    serverLog(
        "DEBUG",
        "AppendText: completed cursor=\(cursorPtr.pointee) hiraganaLength=\(composingText.convertTarget.count) inputCount=\(composingText.input.count)"
    )
    return _strdup(composingText.convertTarget)!
}

@_silgen_name("AppendTextDirect")
public func append_text_direct(
    input: UnsafePointer<CChar>,
    cursorPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<CChar> {
    discardPinnedLearningCandidatesBeforeCompositionEdit()
    let inputString = String(cString: input)
    serverLog("DEBUG", "AppendTextDirect: start inputLength=\(inputString.count)")
    composingText.insertAtCursorPosition(inputString, inputStyle: .direct)

    cursorPtr.pointee = CInt(composingText.convertTargetCursorPosition)
    serverLog(
        "DEBUG",
        "AppendTextDirect: completed cursor=\(cursorPtr.pointee) hiraganaLength=\(composingText.convertTarget.count)"
    )
    return _strdup(composingText.convertTarget)!
}

@_silgen_name("RemoveText")
public func remove_text(
    cursorPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<CChar> {
    discardPinnedLearningCandidatesBeforeCompositionEdit()
    serverLog("DEBUG", "RemoveText: start")
    composingText.deleteBackwardFromCursorPosition(count: 1)

    cursorPtr.pointee = CInt(composingText.convertTargetCursorPosition)
    serverLog(
        "DEBUG",
        "RemoveText: completed cursor=\(cursorPtr.pointee) hiraganaLength=\(composingText.convertTarget.count) inputCount=\(composingText.input.count)"
    )
    return _strdup(composingText.convertTarget)!
}

@_silgen_name("MoveCursor")
public func move_cursor(
    offset: Int32,
    cursorPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<CChar> {
    serverLog("DEBUG", "MoveCursor: start offset=\(offset)")
    let cursor = composingText.moveCursorFromCursorPosition(count: Int(offset))
    serverLog("DEBUG", "MoveCursor: offset=\(offset) cursor=\(cursor)")

    cursorPtr.pointee = CInt(cursor)
    serverLog("DEBUG", "MoveCursor: completed cursor=\(cursor)")
    return _strdup(composingText.convertTarget)!
}

@_silgen_name("GetCursorPosition")
public func get_cursor_position() -> CInt {
    CInt(clamping: composingText.convertTargetCursorPosition)
}

@_silgen_name("GetRawInput")
public func get_raw_input() -> UnsafeMutablePointer<CChar>? {
    var characters: [Character] = []
    characters.reserveCapacity(composingText.input.count)
    for element in composingText.input {
        if case .compositionSeparator = element.piece {
            continue
        }
        guard let character = inputCharacter(element) else {
            serverLog(
                "ERROR",
                "GetRawInput: failed inputCount=\(composingText.input.count) characterCount=\(characters.count)"
            )
            return nil
        }
        characters.append(character)
    }
    return _strdup(String(characters))
}

@_silgen_name("DeleteForwardText")
public func delete_forward_text(cursorPtr: UnsafeMutablePointer<CInt>) -> UnsafeMutablePointer<CChar> {
    serverLog(
        "DEBUG",
        "DeleteForwardText: start cursor=\(composingText.convertTargetCursorPosition) inputCount=\(composingText.input.count)"
    )
    composingText.deleteForwardFromCursorPosition(count: 1)
    cursorPtr.pointee = CInt(composingText.convertTargetCursorPosition)
    serverLog(
        "DEBUG",
        "DeleteForwardText: completed hiraganaLength=\(composingText.convertTarget.count) inputCount=\(composingText.input.count) cursor=\(composingText.convertTargetCursorPosition)"
    )
    return _strdup(composingText.convertTarget)!
}

private func clauseAdjustmentBoundaryMap(
    composingText: ComposingText
) -> [Int: Int] {
    var boundaries = composingText.inputIndexToSurfaceIndexMap()
    let independentBoundaries = boundaries.sorted { $0.key < $1.key }
    let surfaceCharacters = Array(composingText.convertTarget)

    for (start, end) in zip(independentBoundaries, independentBoundaries.dropFirst()) {
        let inputStart = start.key
        let surfaceStart = start.value
        guard end.key > inputStart + 1,
              end.value > surfaceStart + 1,
              composingText.input.indices.contains(inputStart),
              surfaceCharacters.indices.contains(surfaceStart),
              boundaries[inputStart + 1] == nil
        else {
            continue
        }

        let firstElement = composingText.input[inputStart]
        guard firstElement.inputStyle != .direct,
              let firstInput = inputCharacter(firstElement),
              asciiLowercase(firstInput) == "n",
              surfaceCharacters[surfaceStart] == "ん"
        else {
            continue
        }

        var suffixComposingText = ComposingText()
        suffixComposingText.insertAtCursorPosition(
            Array(composingText.input[(inputStart + 1) ..< end.key])
        )
        let expectedSurfaceSuffix = String(
            surfaceCharacters[(surfaceStart + 1) ..< end.value]
        )
        guard suffixComposingText.convertTarget == expectedSurfaceSuffix else {
            continue
        }

        // A single `n` is resolved only after the following romaji segment starts,
        // so the converter's independent-segment map omits the useful boundary
        // between `ん` and that following kana (for example, `nto` -> `んと`).
        boundaries[inputStart + 1] = surfaceStart + 1
    }

    return boundaries
}

@_silgen_name("AdjustClauseBoundary")
public func adjust_clause_boundary(
    currentInputCount: Int32,
    direction: Int32,
    expectedRawInput: UnsafePointer<CChar>? = nil,
    appliedPtr: UnsafeMutablePointer<CInt>,
    adjustedInputCountPtr: UnsafeMutablePointer<CInt>,
    cursorOffsetPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<CChar> {
    appliedPtr.pointee = 0
    adjustedInputCountPtr.pointee = currentInputCount
    cursorOffsetPtr.pointee = 0

    guard currentInputCount >= 0, direction != 0 else {
        serverLog(
            "DEBUG",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=invalid_request"
        )
        return _strdup(composingText.convertTarget)!
    }

    if let expectedRawInput {
        let expected = String(cString: expectedRawInput)
        let actualCharacters = composingText.input.compactMap(inputCharacter)
        let actual = String(actualCharacters)
        guard actualCharacters.count == composingText.input.count, actual == expected else {
            serverLog(
                "ERROR",
                "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=raw_input_mismatch expectedInputCount=\(expected.count) actualInputCount=\(composingText.input.count)"
            )
            return _strdup(composingText.convertTarget)!
        }
    }

    let surfaceIndexByInputIndex = clauseAdjustmentBoundaryMap(composingText: composingText)
    let inputBoundaries = surfaceIndexByInputIndex.keys.sorted()
    guard let currentBoundaryIndex = inputBoundaries.firstIndex(of: Int(currentInputCount)),
          let currentSurfaceIndex = surfaceIndexByInputIndex[Int(currentInputCount)]
    else {
        serverLog(
            "DEBUG",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=missing_input_boundary"
        )
        return _strdup(composingText.convertTarget)!
    }

    let targetBoundaryIndex = direction < 0
        ? currentBoundaryIndex - 1
        : currentBoundaryIndex + 1
    guard inputBoundaries.indices.contains(targetBoundaryIndex) else {
        serverLog(
            "DEBUG",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=edge"
        )
        return _strdup(composingText.convertTarget)!
    }

    let adjustedInputCount = inputBoundaries[targetBoundaryIndex]
    guard adjustedInputCount > 0 else {
        serverLog(
            "DEBUG",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=empty_current_clause"
        )
        return _strdup(composingText.convertTarget)!
    }
    guard let targetSurfaceIndex = surfaceIndexByInputIndex[adjustedInputCount] else {
        serverLog(
            "DEBUG",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=missing_surface_boundary"
        )
        return _strdup(composingText.convertTarget)!
    }

    let requestedCursorOffset = targetSurfaceIndex - composingText.convertTargetCursorPosition
    let cursorOffset = composingText.moveCursorFromCursorPosition(count: requestedCursorOffset)
    guard composingText.convertTargetCursorPosition == targetSurfaceIndex else {
        let rollbackOffset = currentSurfaceIndex - composingText.convertTargetCursorPosition
        _ = composingText.moveCursorFromCursorPosition(count: rollbackOffset)
        serverLog(
            "ERROR",
            "AdjustClauseBoundary: skipped currentInputCount=\(currentInputCount) direction=\(direction) reason=cursor_clamped"
        )
        return _strdup(composingText.convertTarget)!
    }

    appliedPtr.pointee = 1
    adjustedInputCountPtr.pointee = CInt(adjustedInputCount)
    cursorOffsetPtr.pointee = CInt(cursorOffset)
    serverLog(
        "DEBUG",
        "AdjustClauseBoundary: applied currentInputCount=\(currentInputCount) adjustedInputCount=\(adjustedInputCount) direction=\(direction) currentSurfaceIndex=\(currentSurfaceIndex) targetSurfaceIndex=\(targetSurfaceIndex) cursorOffset=\(cursorOffset)"
    )
    return _strdup(composingText.convertTarget)!
}

@_silgen_name("ClearComposingTextSnapshots")
public func clear_composing_text_snapshots() {
    composingTextSnapshots.removeAll()
    learningCandidateCache.removeAllPins()
    serverLog("DEBUG", "ClearComposingTextSnapshots: completed")
}

@_silgen_name("PushComposingTextSnapshot")
public func push_composing_text_snapshot(selectedCandidateId: UInt64) {
    if selectedCandidateId != 0 && !pin_learning_candidate(candidateId: selectedCandidateId) {
        serverLog(
            "DEBUG",
            "PushComposingTextSnapshot: learning candidate unavailable id=\(selectedCandidateId)"
        )
    }
    composingTextSnapshots.append(composingText)
    serverLog(
        "DEBUG",
        "PushComposingTextSnapshot: completed count=\(composingTextSnapshots.count) protectedLearningCandidateSlots=\(learningCandidateCache.protectedSlotCount)"
    )
}

@_silgen_name("PopComposingTextSnapshot")
public func pop_composing_text_snapshot(selectedCandidateId: UInt64) {
    if selectedCandidateId != 0 && !pin_learning_candidate(candidateId: selectedCandidateId) {
        serverLog(
            "DEBUG",
            "PopComposingTextSnapshot: learning candidate unavailable id=\(selectedCandidateId)"
        )
    }
    if let restored = composingTextSnapshots.popLast() {
        composingText = restored
    }
    serverLog(
        "DEBUG",
        "PopComposingTextSnapshot: completed remaining=\(composingTextSnapshots.count)"
    )
}

@_silgen_name("ClearText")
public func clear_text() {
    serverLog("DEBUG", "ClearText: start")
    converter.stopComposition()
    normalNBestSupplementConverter.stopComposition()
    composingText = ComposingText()
    composingTextSnapshots.removeAll()
    clearLearningCandidateCache()
    clearConversionContinuation()
    clearPublishedPostCompositionPredictions()
    serverLog("DEBUG", "ClearText: completed")
}

private func recordCommittedLearningContinuation(
    _ candidate: Candidate,
    contextBeforeCommit: String
) {
    let surface = candidate.text
    guard !surface.isEmpty,
          !surface.contains(where: isAutomaticConversionContinuationStopCharacter) else {
        clearLastCommittedContinuation()
        return
    }
    let reading = katakanaToHiragana(learningCandidateRuby(candidate))
    guard !reading.isEmpty,
          isKanaLikeConversionContinuationReading(reading),
          reading.count <= maxConversionContinuationReadingCount,
          surface.count <= maxConversionContinuationSurfaceCount else {
        clearLastCommittedContinuation()
        return
    }
    if let previous = lastCommittedContinuation,
       !contextBeforeCommit.isEmpty,
       contextBeforeCommit.hasSuffix(previous.surface),
       previous.reading.count + reading.count <= maxAutomaticConversionContinuationReadingCount,
       previous.surface.count + surface.count <= maxAutomaticConversionContinuationSurfaceCount {
        lastCommittedContinuation = ConversionContinuation(
            reading: previous.reading + reading,
            surface: previous.surface + surface
        )
    } else {
        lastCommittedContinuation = ConversionContinuation(reading: reading, surface: surface)
    }
}

@_silgen_name("CommitLearningCandidate")
public func commit_learning_candidate(candidateId: UInt64, commitKind: Int32) -> Bool {
    commit_learning_candidate_with_explicit(candidateId: candidateId, commitKind: commitKind, explicitSelection: false)
}

@_silgen_name("CommitLearningCandidateWithExplicit")
public func commit_learning_candidate_with_explicit(
    candidateId: UInt64, commitKind: Int32, explicitSelection: Bool
) -> Bool {
    withUnsafePointer(to: candidateId) { ids in
        withUnsafePointer(to: commitKind) { kinds in
            withUnsafePointer(to: explicitSelection) { flags in
                commit_learning_candidates_with_explicit(
                    candidateIds: ids, commitKinds: kinds, explicitSelections: flags, count: 1
                ) == 1
            }
        }
    }
}

@_silgen_name("CommitLearningCandidates")
public func commit_learning_candidates(
    candidateIds: UnsafePointer<UInt64>?, commitKinds: UnsafePointer<Int32>?, count: Int32
) -> Int32 {
    commit_learning_candidates_with_explicit(
        candidateIds: candidateIds, commitKinds: commitKinds, explicitSelections: nil, count: count
    )
}

@_silgen_name("CommitLearningCandidatesWithExplicit")
public func commit_learning_candidates_with_explicit(
    candidateIds: UnsafePointer<UInt64>?, commitKinds: UnsafePointer<Int32>?,
    explicitSelections: UnsafePointer<Bool>?, count: Int32
) -> Int32 {
    guard count > 0, let candidateIds, let commitKinds else { return 0 }
    guard !privateInputActive, currentLearningType == .inputAndOutput else { return count }

    var candidates: [(candidate: Candidate, explicit: Bool)] = []
    candidates.reserveCapacity(Int(count))
    for index in 0..<Int(count) {
        let id = candidateIds[index]
        guard let match = learningCandidateCache.consumeWithOriginalRank(id) else {
            serverLog("WARN", "CommitLearningCandidates: candidate not found candidateId=\(id) commitKind=\(commitKinds[index])")
            continue
        }
        candidates.append((match.candidate, (explicitSelections?[index] ?? false) || match.originalRank > 0))
    }
    guard !candidates.isEmpty else { return 0 }

    var context = (config["context"] as? String) ?? ""
    ensureConverterLearningConfigSynced()
    for entry in candidates {
        converter.setCompletedData(entry.candidate)
        normalNBestSupplementConverter.setCompletedData(entry.candidate)
        if entry.candidate.isLearningTarget {
            converter.updateLearningData(entry.candidate)
            converterLearningDirty = true
            if updateLearningSelectionOverride(entry.candidate, context: context, explicit: entry.explicit) {
                learningSelectionOverridesDirty = true
            }
        }
        recordCommittedLearningContinuation(entry.candidate, contextBeforeCommit: context)
        recordPostCompositionLearningCommit(entry.candidate, contextBeforeCommit: context, persist: false)
        context += entry.candidate.text
    }
    _ = flush_learning_memory()
    return Int32(candidates.count)
}

nonisolated(unsafe) var converterLearningDirty = false

@_silgen_name("FlushLearningMemory")
public func flush_learning_memory() -> Bool {
    guard !privateInputActive else { return false }
    if converterLearningDirty, currentLearningType == .inputAndOutput,
       converter.flushPendingLearningData() {
        normalNBestSupplementConverter.invalidateLearningMemoryCache()
        converterLearningDirty = false
    }
    let selectionsSaved = saveLearningSelectionOverrides()
    let bigramsSaved = savePostCompositionBigrams()
    return !converterLearningDirty && selectionsSaved && bigramsSaved
}

@_silgen_name("ResetLearningMemory")
public func reset_learning_memory() -> Bool {
    guard !privateInputActive, resetLearningMemoryDirectory() else { return false }
    converterLearningDirty = false
    learningSelectionOverridesDirty = false
    converter.syncLearningConfig(learningType: currentLearningType, memoryDirectoryURL: currentLearningMemoryDirectoryURL)
    normalNBestSupplementConverter.syncLearningConfig(learningType: currentLearningType, memoryDirectoryURL: currentLearningMemoryDirectoryURL)
    converter.resetMemory()
    normalNBestSupplementConverter.resetMemory()
    clearLearningCandidateCache()
    clearLastCommittedContinuation()
    learningSelectionOverrides.removeAll(keepingCapacity: false)
    resetPostCompositionLearningMemory()
    serverLog("INFO", "ResetLearningMemory: completed")
    return true
}

/// Deletes one candidate's conversion learning.
///
/// Both stores are addressed by the id the engine issued for that candidate, so
/// a host never has to reimplement how a reading maps to a dictionary entry.
/// Returns false when the id is unknown, was not a learning target, or the word
/// was absent from both stores.
@_silgen_name("ForgetLearningCandidate")
public func forget_learning_candidate(candidateId: UInt64) -> Bool {
    guard !privateInputActive else { return false }
    // Validate before consuming. Consuming marks the id used for both commit and
    // pin, so a refusal must not happen after it: a candidate that is skipped
    // because of its flags or the current learning mode has to stay selectable.
    guard let candidate = learningCandidate(candidateId) else {
        serverLog("WARN", "ForgetLearningCandidate: unknown candidateId=\(candidateId)")
        return false
    }
    guard candidate.isLearningTarget else {
        serverLog("WARN", "ForgetLearningCandidate: not a learning target candidateId=\(candidateId)")
        return false
    }
    guard currentLearningType != .nothing else {
        serverLog("WARN", "ForgetLearningCandidate: learning disabled candidateId=\(candidateId)")
        return false
    }
    // Consuming here, after the guards, still makes deletion single-shot: a stale
    // click or a retried request cannot delete a different candidate that reuses
    // the slot.
    guard consumeLearningCandidate(candidateId) != nil else {
        serverLog("WARN", "ForgetLearningCandidate: already consumed candidateId=\(candidateId)")
        return false
    }

    let ruby = learningCandidateRuby(candidate)
    let output = learningCandidateOutput(candidate)

    ensureConverterLearningConfigSynced()
    let extraForgetElements = postCompositionJoinedForgetElements(for: candidate, output: output)
    let forgetCandidate: Candidate
    if extraForgetElements.isEmpty {
        forgetCandidate = candidate
    } else {
        forgetCandidate = Candidate(
            text: candidate.text,
            value: candidate.value,
            composingCount: candidate.composingCount,
            lastMid: candidate.lastMid,
            data: candidate.data + extraForgetElements,
            isLearningTarget: candidate.isLearningTarget
        )
    }
    converter.forgetMemory(forgetCandidate)
    normalNBestSupplementConverter.forgetMemory(forgetCandidate)
    normalNBestSupplementConverter.invalidateLearningMemoryCache()
    clearLastCommittedContinuation()
    forgetPostCompositionLearning(surface: output)
    let removedOverride = !ruby.isEmpty &&
        learningSelectionOverrides.remove(output: output, for: ruby)
    if removedOverride {
        learningSelectionOverridesDirty = true
        _ = saveLearningSelectionOverrides()
    }
    serverLog(
        "INFO",
        "ForgetLearningCandidate: candidateId=\(candidateId) rubyCount=\(ruby.count) outputCount=\(output.count) removedOverride=\(removedOverride)"
    )
    return true
}

/// Checks that a deletion would be accepted, without touching persisted learning.
///
/// Device checks need the id and single-shot contract of
/// `forget_learning_candidate` but must not rewrite the user's learning memory.
/// This applies the same guards and consumes the id in the in-memory cache, then
/// returns before any store is written.
@_silgen_name("ValidateLearningCandidateDeletion")
public func validate_learning_candidate_deletion(candidateId: UInt64) -> Bool {
    guard !privateInputActive else { return false }
    guard let candidate = learningCandidate(candidateId) else {
        serverLog("DEBUG", "ValidateLearningCandidateDeletion: unknown candidateId=\(candidateId)")
        return false
    }
    guard candidate.isLearningTarget else {
        serverLog("DEBUG", "ValidateLearningCandidateDeletion: not a learning target candidateId=\(candidateId)")
        return false
    }
    guard currentLearningType != .nothing else {
        serverLog("DEBUG", "ValidateLearningCandidateDeletion: learning disabled candidateId=\(candidateId)")
        return false
    }
    // Consuming keeps the single-shot contract observable, and skipping the store
    // writes keeps the user's learning memory untouched.
    guard consumeLearningCandidate(candidateId) != nil else {
        serverLog("DEBUG", "ValidateLearningCandidateDeletion: already consumed candidateId=\(candidateId)")
        return false
    }
    return true
}

/// Per-store learning counts so a host can confirm a reset instead of trusting a bool.
@_silgen_name("GetLearningStats")
public func get_learning_stats() -> UnsafeMutablePointer<CChar>? {
    guard !privateInputActive else { return nil }
    let memoryURL = currentLearningMemoryDirectoryURL
    // A `.pause` marker means the long-term trie failed its atomic update and the
    // engine fell back to the `.2` copy; learning stays off until it is cleared.
    let pauseURL = memoryURL?.appendingPathComponent(".pause", isDirectory: false)
    let paused = pauseURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
    let payload: [String: Any] = [
        "selectionCount": learningSelectionOverrides.count,
        "historyPresent": memoryURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false,
        "historyPaused": paused,
        "learningMode": currentLearningType == .nothing
            ? "disabled"
            : (currentLearningType == .onlyOutput ? "read_only" : "enabled"),
        "candidateCacheSlots": learningCandidateCache.slotCount,
        "candidateCacheBatches": learningCandidateCache.batchCount,
        "postCompositionBigramCount": currentLearningType == .nothing ? 0 : postCompositionBigramStore.count,
        "selectionOverrideCount": currentLearningType == .nothing ? 0 : learningSelectionOverrides.outputCount,
    ]
    guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
          let json = String(data: data, encoding: .utf8) else {
        return nil
    }
    return _strdup(json)
}

func to_list_pointer(_ list: [FFICandidate]) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    let pointer = UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?>.allocate(capacity: list.count)
    guard !list.isEmpty else {
        return pointer
    }

    let candidateStorage = UnsafeMutablePointer<FFICandidate>.allocate(capacity: list.count)
    candidateStorage.initialize(from: list, count: list.count)
    for i in 0..<list.count {
        pointer.advanced(by: i).initialize(to: candidateStorage.advanced(by: i))
    }
    return pointer
}

@_silgen_name("FreeCString")
public func free_c_string(_ ptr: UnsafeMutablePointer<CChar>?) {
    guard let ptr else {
        return
    }
    free(ptr)
}

@_silgen_name("FreeCandidateList")
public func free_candidate_list(
    _ ptr: UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?>?,
    _ length: Int32
) {
    guard let ptr else {
        return
    }

    guard length > 0 else {
        ptr.deinitialize(count: 0)
        ptr.deallocate()
        return
    }

    let count = Int(length)
    guard count > 0 else {
        ptr.deallocate()
        return
    }

    let candidateStorage = ptr[0]
    let isContiguousCandidateStorage = candidateStorage.map { storage in
        (0..<count).allSatisfy { index in
            ptr[index] == storage.advanced(by: index)
        }
    } ?? false
    for index in 0..<count {
        guard let candidatePtr = ptr[index] else {
            continue
        }

        let candidate = candidatePtr.pointee
        free(candidate.text)
        free(candidate.subtext)
        free(candidate.hiragana)
        free(candidate.predictionReading)
    }

    if isContiguousCandidateStorage, let candidateStorage {
        candidateStorage.deinitialize(count: count)
        candidateStorage.deallocate()
    } else {
        for index in 0..<count {
            guard let candidatePtr = ptr[index] else {
                continue
            }
            candidatePtr.deinitialize(count: 1)
            candidatePtr.deallocate()
        }
    }

    ptr.deinitialize(count: count)
    ptr.deallocate()
}

@_silgen_name("GetComposedText")
public func get_composed_text(lengthPtr: UnsafeMutablePointer<CInt>) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    get_composed_text_impl(lengthPtr: lengthPtr, allowZenzai: true)
}

@_silgen_name("GetComposedTextForReconversion")
public func get_composed_text_for_reconversion(lengthPtr: UnsafeMutablePointer<CInt>) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    get_composed_text_impl(lengthPtr: lengthPtr, allowZenzai: false)
}

@_silgen_name("GetComposedTextForCursorRange")
public func get_composed_text_for_cursor_range(lengthPtr: UnsafeMutablePointer<CInt>) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    if composingText.isAtEndIndex {
        return get_composed_text_impl(lengthPtr: lengthPtr, allowZenzai: true)
    }
    let cursorPosition = composingText.convertTargetCursorPosition
    let suffixAfterCursor = String(composingText.convertTarget.dropFirst(cursorPosition))
    let prefixComposingText = composingText.prefixToCursorPosition()
    if prefixComposingText.convertTarget.isEmpty {
        lengthPtr.pointee = 0
        return to_list_pointer([])
    }
    return get_composed_text_impl(
        lengthPtr: lengthPtr,
        allowZenzai: true,
        sourceComposingText: prefixComposingText,
        suffixAfterCursor: suffixAfterCursor,
        isCursorAtEndOverride: false
    )
}

func get_composed_text_impl(
    lengthPtr: UnsafeMutablePointer<CInt>,
    allowZenzai: Bool,
    sourceComposingText: ComposingText? = nil,
    suffixAfterCursor: String = "",
    isCursorAtEndOverride: Bool? = nil,
    onReadingCompletion: ((Int) -> Void)? = nil
) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    adoptPendingUserDictionary()
    let targetComposingText = sourceComposingText ?? composingText
    let isCursorAtEnd = isCursorAtEndOverride ?? targetComposingText.isAtEndIndex
    let functionStart = performanceNow()
    let performanceEnabled = serverLogCallbacks.isPerformanceLogEnabled()
    let originalHiragana = targetComposingText.convertTarget
    let rawContextString = (config["context"] as? String) ?? ""
    let diagnosticSnapshot = zenzaiDiagnosticSnapshot()
    let runtimeZenzaiEnabled = diagnosticSnapshot.runtimeEnabled && allowZenzai
    let previewState = makeCandidatePreviewComposingText(from: targetComposingText)
    let previewComposingText = previewState.composingText
    let previewHiragana = previewComposingText.convertTarget
    let fullHiraganaForCandidate = suffixAfterCursor.isEmpty
        ? previewHiragana
        : previewHiragana + suffixAfterCursor
    let explicitContinuationRequest = makeConversionContinuationRequest(
        continuation: conversionContinuation,
        allowZenzai: allowZenzai,
        isCursorAtEnd: isCursorAtEnd,
        previewHiragana: previewHiragana,
        hasTrailingUncompletedRoman: hasTrailingUncompletedRoman(
            in: previewComposingText
        ),
        context: rawContextString
    )
    let automaticContinuation = (allowZenzai && explicitContinuationRequest == nil
        && isCursorAtEnd
        && !hasTrailingUncompletedRoman(in: previewComposingText))
        ? makeAutomaticConversionContinuation(
            context: rawContextString,
            currentReading: previewHiragana,
            isCursorAtEnd: true
        )
        : nil
    let continuationRequest = explicitContinuationRequest ?? makeConversionContinuationRequest(
        continuation: automaticContinuation,
        allowZenzai: allowZenzai,
        isCursorAtEnd: isCursorAtEnd,
        previewHiragana: previewHiragana,
        hasTrailingUncompletedRoman: hasTrailingUncompletedRoman(
            in: previewComposingText
        ),
        context: rawContextString
    )
    let requestComposingText = continuationRequest?.combinedComposingText
        ?? previewComposingText
    let contextString = continuationRequest?.context ?? rawContextString
    let useZenzai: Bool
    if let continuationRequest {
        useZenzai = effectiveZenzaiEnabledForCandidates(
            isConfigured: runtimeZenzaiEnabled,
            inputCount: continuationRequest.combinedComposingText.input.count,
            hiraganaCount: continuationRequest.combinedComposingText.convertTarget.count
        )
    } else {
        useZenzai = effectiveZenzaiEnabledForCandidates(
            isConfigured: runtimeZenzaiEnabled,
            inputCount: targetComposingText.input.count,
            hiraganaCount: originalHiragana.count
        )
    }
    let diagnosticsEnabled = zenzaiDiagnosticsEnabled(useZenzai: useZenzai)
    var diagnosticDetails = diagnosticsEnabled
        ? zenzaiDiagnosticDetails(
            snapshot: diagnosticSnapshot,
            contextLength: contextString.count,
            inputCount: targetComposingText.input.count,
            hiraganaLength: originalHiragana.count,
            previewHiraganaLength: previewHiragana.count,
            useZenzai: useZenzai,
            syntheticEndOfText: previewState.syntheticEndOfText,
            continuation: continuationRequest?.continuation,
            combinedHiraganaLength: continuationRequest?.combinedComposingText.convertTarget.count
        )
        : ""
    if diagnosticsEnabled && automaticContinuation != nil {
        diagnosticDetails += ";continuation_auto=true"
    }
    serverLog(
        "DEBUG",
        "GetComposedText: start \(diagnosticDetails)"
    )
    let hasTrailingRoman = hasTrailingUncompletedRoman(in: previewComposingText)
    let options = getOptions(context: contextString, zenzaiEnabled: useZenzai)
    let japanesePredictionEnabled = options.requireJapanesePrediction != .disabled
    let cacheOnlyPrediction = hasTrailingRoman
    let completePendingRoman = isCursorAtEnd && suffixAfterCursor.isEmpty && cacheOnlyPrediction && japanesePredictionEnabled
    let predictiveContinuationRequest: ConversionContinuationRequest?
    let isAutoOnlyPredictiveContinuation: Bool
    if suffixAfterCursor.isEmpty, japanesePredictionEnabled {
        if let continuationRequest {
            predictiveContinuationRequest = continuationRequest
            isAutoOnlyPredictiveContinuation = (explicitContinuationRequest == nil)
        } else {
            let explicitPredictive = makePredictiveConversionContinuationRequest(
                continuation: conversionContinuation,
                allowPredictive: allowZenzai,
                isCursorAtEnd: isCursorAtEnd,
                previewComposingText: previewComposingText,
                context: rawContextString
            )
            let committedPredictive = explicitPredictive ?? makePredictiveConversionContinuationRequest(
                continuation: lastCommittedContinuation,
                allowPredictive: allowZenzai,
                isCursorAtEnd: isCursorAtEnd,
                previewComposingText: previewComposingText,
                context: rawContextString,
                requireContextSuffixMatch: true
            )
            if let committedPredictive {
                predictiveContinuationRequest = committedPredictive
                isAutoOnlyPredictiveContinuation = false
            } else {
                let autoPredictive = (allowZenzai && isCursorAtEnd)
                    ? makeAutomaticConversionContinuation(
                        context: rawContextString,
                        currentReading: previewHiragana,
                        isCursorAtEnd: true,
                        allowPureKana: true,
                        allowUncompletedRoman: true
                    )
                    : nil
                predictiveContinuationRequest = makePredictiveConversionContinuationRequest(
                    continuation: autoPredictive,
                    allowPredictive: allowZenzai,
                    isCursorAtEnd: isCursorAtEnd,
                    previewComposingText: previewComposingText,
                    context: rawContextString
                )
                isAutoOnlyPredictiveContinuation = predictiveContinuationRequest != nil
            }
        }
    } else {
        predictiveContinuationRequest = nil
        isAutoOnlyPredictiveContinuation = false
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedText",
        stage: "requestCandidates",
        state: "begin",
        details: diagnosticDetails
    )
    serverLog("DEBUG", "GetComposedText: requestCandidates begin \(diagnosticDetails)")
    if performanceEnabled {
        performanceLog(
            operation: "get_composed_text",
            stage: "prepare_request",
            elapsedMs: elapsedPerformanceMilliseconds(since: functionStart),
            details: diagnosticDetails
        )
    }
    let zenzaiOptions = useZenzai ? optionsForZenzaiConversion(from: options) : options
    let normalNBestConverted: ConversionResult?
    let inlinePredictionContinuationResult: ConversionResult?
    let inlineSkippedPredictionContinuation: Bool
    let converted: ConversionResult
    let requestStart = performanceNow()
    if useZenzai, requestComposingText == previewComposingText {
        let supplementOptions = optionsForNormalNBestSupplementWithPrediction(from: options)
        let parallelPredictionContinuation = predictiveContinuationRequest.map { request in
            var predOptions = supplementOptions
            predOptions.zenzaiMode = .off
            predOptions.N_best = min(predOptions.N_best, 3)
            return ZenzaiPredictionContinuationRequest(
                inputData: request.combinedComposingText,
                options: predOptions,
                prefixConstraintSurface: request.continuation.surface,
                cacheOnlyJapanesePrediction: cacheOnlyPrediction,
                skipWhenSufficientPredictions: isAutoOnlyPredictiveContinuation && previewHiragana.count > 8
            )
        }
        let execution = executeZenzaiWithNormalNBestSupplement(
            inputData: requestComposingText,
            zenzaiOptions: zenzaiOptions,
            supplementOptions: supplementOptions,
            cacheOnlyJapanesePrediction: cacheOnlyPrediction,
            predictionContinuation: parallelPredictionContinuation,
            operation: "get_composed_text",
            diagnosticDetails: diagnosticDetails
        )
        normalNBestConverted = execution.supplement
        inlinePredictionContinuationResult = execution.predictionContinuationResult
        inlineSkippedPredictionContinuation = execution.skippedPredictionContinuation
        converted = execution.zenzai
    } else {
        inlinePredictionContinuationResult = nil
        inlineSkippedPredictionContinuation = false
        if useZenzai {
            let supplementOptions = optionsForNormalNBestSupplementWithPrediction(from: options)
            normalNBestConverted = requestNormalNBestSupplementCandidates(
                inputData: previewComposingText,
                options: supplementOptions,
                cacheOnlyJapanesePrediction: cacheOnlyPrediction,
                operation: "get_composed_text",
                diagnosticDetails: diagnosticDetails
            )
        } else {
            normalNBestConverted = nil
        }
        converted = converter.requestCandidates(
            requestComposingText,
            options: zenzaiOptions,
            sessionID: nil,
            cacheOnlyJapanesePrediction: cacheOnlyPrediction
        )
    }
    let requestMs = elapsedPerformanceMilliseconds(since: requestStart)
    performanceLog(
        operation: "get_composed_text",
        stage: "request_candidates",
        elapsedMs: requestMs,
        details: "candidate_count=\(converted.mainResults.count);\(diagnosticDetails)"
    )
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedText",
        stage: "requestCandidates",
        state: "completed",
        details: "candidate_count=\(converted.mainResults.count);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "GetComposedText: requestCandidates returned candidateCount=\(converted.mainResults.count) \(diagnosticDetails)")
    let rawZenzaiCandidateCount = converted.mainResults.count
    var zenzaiMainResults = converted.mainResults
    var continuationSlicedCount = 0
    var continuationFallback = false
    if let continuationRequest {
        let sliceStart = performanceNow()
        let slicedResults = converted.mainResults.compactMap { candidate in
            sliceConversionContinuationCandidate(
                candidate,
                continuation: continuationRequest.continuation,
                currentReading: previewHiragana
            )
        }
        let sliceMs = elapsedPerformanceMilliseconds(since: sliceStart)
        continuationSlicedCount = slicedResults.count
        if slicedResults.isEmpty {
            continuationFallback = true
            if automaticContinuation != nil {
                disableAutomaticConversionContinuation(context: rawContextString)
            }
            var fallbackOptions = getOptions(context: rawContextString, zenzaiEnabled: useZenzai)
            if useZenzai {
                fallbackOptions = optionsForZenzaiConversion(from: fallbackOptions)
            }
            let fallbackStart = performanceNow()
            let fallbackConverted = converter.requestCandidates(
                previewComposingText,
                options: fallbackOptions,
                sessionID: nil,
                cacheOnlyJapanesePrediction: cacheOnlyPrediction
            )
            let fallbackMs = elapsedPerformanceMilliseconds(since: fallbackStart)
            let fallbackDetails = diagnosticsEnabled ? "\(diagnosticDetails);continuation_fallback_context=raw" : ""
            performanceLog(
                operation: "get_composed_text",
                stage: "request_continuation_fallback",
                elapsedMs: fallbackMs,
                details: "candidate_count=\(fallbackConverted.mainResults.count);\(fallbackDetails)"
            )
            serverLog(
                "DEBUG",
                "GetComposedText: continuation fallback returned candidateCount=\(fallbackConverted.mainResults.count) \(fallbackDetails)"
            )
            zenzaiMainResults = fallbackConverted.mainResults
        } else {
            zenzaiMainResults = slicedResults
        }
        performanceLog(
            operation: "get_composed_text",
            stage: "slice_continuation_candidates",
            elapsedMs: sliceMs,
            details: "zenzai_raw_candidate_count=\(rawZenzaiCandidateCount);continuation_sliced_count=\(continuationSlicedCount);continuation_fallback=\(continuationFallback);\(diagnosticDetails)"
        )
        if diagnosticsEnabled {
            diagnosticDetails += ";zenzai_raw_candidate_count=\(rawZenzaiCandidateCount);continuation_sliced_count=\(continuationSlicedCount);continuation_fallback=\(continuationFallback)"
        }
    }
    let mergeExactStart = performanceEnabled ? performanceNow() : 0
    let mergedExactResults = normalNBestConverted.map {
        mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: zenzaiMainResults,
            normalNBestResults: $0.mainResults,
            hiragana: previewHiragana
        )
    } ?? zenzaiMainResults
    let mergeExactMs = performanceEnabled ? elapsedPerformanceMilliseconds(since: mergeExactStart) : 0
    let pendingRomanCompletions: [Candidate]
    if completePendingRoman {
        let start = performanceEnabled ? performanceNow() : 0
        pendingRomanCompletions = (useZenzai ? normalNBestSupplementConverter : converter)
            .requestPendingRomanCompletions(previewComposingText, options: options)
        if performanceEnabled {
            performanceLog(operation: "get_composed_text", stage: "pending_roman_predictions",
                           elapsedMs: elapsedPerformanceMilliseconds(since: start))
        }
    } else {
        pendingRomanCompletions = []
    }
    let completeWholeReading = isCursorAtEnd && suffixAfterCursor.isEmpty && !hasTrailingRoman && japanesePredictionEnabled
    let wholeReadingCompletions: [Candidate]
    if completeWholeReading {
        let start = performanceEnabled ? performanceNow() : 0
        wholeReadingCompletions = (useZenzai ? normalNBestSupplementConverter : converter)
            .requestWholeReadingCompletions(previewComposingText, options: options)
        if performanceEnabled {
            performanceLog(operation: "get_composed_text", stage: "whole_reading_predictions",
                           elapsedMs: elapsedPerformanceMilliseconds(since: start))
        }
    } else {
        wholeReadingCompletions = []
    }
    let effectivePredictionResults: [Candidate]?
    if suffixAfterCursor.isEmpty, japanesePredictionEnabled {
        let rawBasePredictions = normalNBestConverted?.predictionResults
            ?? (continuationRequest.map { request in
                converted.predictionResults.compactMap {
                    slicePredictiveContinuationCandidate(
                        $0,
                        continuation: request.continuation,
                        currentReading: previewHiragana
                    )
                }
            } ?? converted.predictionResults)
        let basePredictions = wholeReadingCompletions.isEmpty
            ? rawBasePredictions
            : mergeDistinctPredictionCandidates(
                primary: wholeReadingCompletions,
                fallback: rawBasePredictions,
                hiragana: previewHiragana
            )
        if let predictiveContinuationRequest, useZenzai || continuationRequest == nil {
            let shouldSkipSequentialContinuation = isAutoOnlyPredictiveContinuation
                && previewHiragana.count > 8
                && basePredictions.count >= 3
            let contPredConverted: ConversionResult?
            if let cachedContPred = inlinePredictionContinuationResult {
                contPredConverted = cachedContPred
            } else if inlineSkippedPredictionContinuation || shouldSkipSequentialContinuation {
                contPredConverted = nil
            } else {
                let predictionConverter = useZenzai ? normalNBestSupplementConverter : converter
                var contPredOptions = optionsForNormalNBestSupplementWithPrediction(from: options)
                contPredOptions.N_best = min(contPredOptions.N_best, 3)
                contPredConverted = requestNormalNBestSupplementCandidates(
                    converter: predictionConverter,
                    inputData: predictiveContinuationRequest.combinedComposingText,
                    options: contPredOptions,
                    sessionID: "continuation-prediction",
                    cacheOnlyJapanesePrediction: cacheOnlyPrediction,
                    prefixConstraintSurface: predictiveContinuationRequest.continuation.surface,
                    stage: "request_prediction_continuation",
                    operation: "get_composed_text_prediction_continuation",
                    diagnosticDetails: diagnosticDetails
                )
            }
            if let contPredConverted {
                let contextualPredictions = contPredConverted.predictionResults.compactMap {
                    slicePredictiveContinuationCandidate(
                        $0,
                        continuation: predictiveContinuationRequest.continuation,
                        currentReading: previewHiragana
                    )
                }
                effectivePredictionResults = mergeDistinctPredictionCandidates(
                    primary: contextualPredictions,
                    fallback: basePredictions,
                    hiragana: previewHiragana
                )
            } else {
                effectivePredictionResults = basePredictions
            }
        } else if useZenzai || continuationRequest != nil || completePendingRoman || !wholeReadingCompletions.isEmpty {
            effectivePredictionResults = basePredictions
        } else {
            effectivePredictionResults = nil
        }
    } else {
        effectivePredictionResults = nil
    }
    let mergePostStart = performanceEnabled ? performanceNow() : 0
    let mergedMainResults = effectivePredictionResults.map {
        let predictions = pendingRomanCompletions.isEmpty
            ? $0
            : Array($0.prefix(1)) + pendingRomanCompletions + $0.dropFirst()
        return mergePredictiveSupplementCandidates(
            exactMerged: mergedExactResults,
            predictionResults: predictions,
            hiragana: previewHiragana,
            maxPredictions: completePendingRoman ? 6 : 3,
            preferPredictionsAfterTopCandidate: completePendingRoman
        )
    } ?? mergedExactResults
    let touchCandidates = suffixAfterCursor.isEmpty
        ? touchWeightedKeyboardCandidates(
            composing: previewComposingText,
            evidence: currentKeyboardTouchEvidence,
            options: options,
            lookup: { text, lookupOptions in
                normalNBestSupplementConverter.requestExactKeyboardCorrectionCandidates(text, options: lookupOptions)
            }
        )
        : KeyboardTouchCandidates()
    let weightedMainResults = mergeTouchWeightedKeyboardCandidates(
        normal: mergedMainResults, touch: touchCandidates,
        dictionary: normalNBestConverted?.mainResults ?? converted.mainResults
    )
    let expandedPrefixRubies = (cacheOnlyPrediction && japanesePredictionEnabled)
        ? (useZenzai ? normalNBestSupplementConverter : converter).predictiveRubyPrefixes(for: previewComposingText)
        : []
    let rawMainResults = prioritizeLearningSelectionOverrides(
        weightedMainResults,
        ruby: previewHiragana,
        expandedPrefixRubies: expandedPrefixRubies,
        leftContext: contextString,
        candidate: { $0 },
        synthesizeElement: { $0 }
    )
    var mainResults = rawMainResults
    if !suffixAfterCursor.isEmpty,
       let first = mainResults.first,
       first.rubyCount != previewHiragana.count,
       let exactIdx = mainResults.firstIndex(where: { $0.rubyCount == previewHiragana.count }) {
        let exactCandidate = mainResults.remove(at: exactIdx)
        mainResults.insert(exactCandidate, at: 0)
    } else if let first = mainResults.first, first.rubyCount > previewHiragana.count,
              !isTouchWeightedKeyboardCandidate(first) {
        if let exactIdx = mainResults.firstIndex(where: { $0.rubyCount <= previewHiragana.count }) {
            let exactCandidate = mainResults.remove(at: exactIdx)
            mainResults.insert(exactCandidate, at: 0)
        }
    }
    // Keep the highest-ranked Android completion ahead of partial clauses.
    let kanaCompletionReading = (onReadingCompletion != nil && japanesePredictionEnabled &&
        isCursorAtEnd && suffixAfterCursor.isEmpty && !hasTrailingRoman)
        ? normalizedLearningRuby(previewHiragana) : nil
    if let kanaCompletionReading {
        promoteKanaReadingCompletion(in: &mainResults, normalizedReading: kanaCompletionReading, afterFullSpan: true)
    }
    if performanceEnabled {
        performanceLog(
            operation: "get_composed_text",
            stage: "merge_candidates",
            elapsedMs: mergeExactMs + elapsedPerformanceMilliseconds(since: mergePostStart),
            details: "candidate_count=\(mainResults.count);\(diagnosticDetails)"
        )
    }
    if let normalNBestConverted {
        serverLog(
            "DEBUG",
            "GetComposedText: merged Zenzai candidates candidateCount=\(mainResults.count) zenzaiCandidateCount=\(zenzaiMainResults.count) normalNBestCandidateCount=\(normalNBestConverted.mainResults.count) \(diagnosticDetails)"
        )
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedText",
        stage: "postprocessCandidates",
        state: "begin",
        details: "candidate_count=\(mainResults.count);zenzai_candidate_count=\(zenzaiMainResults.count);\(diagnosticDetails)"
    )
    let buildStart = performanceEnabled ? performanceNow() : 0
    var constructCandidateStringMs = 0
    var resolveCandidateCompositionMs = 0
    var strdupCandidatesMs = 0
    var resolutionCache: [String: CandidateDisplayResolution] = [:]
    var result: [FFICandidate] = []
    result.reserveCapacity(mainResults.count)
    let learningCandidateBatchFirstId = cacheLearningCandidates(mainResults, allowReadOnlyDeletion: true)
    let pendingCompletionTexts: Set<String> = onReadingCompletion == nil
        ? [] : Set(pendingRomanCompletions.map(\.text))
    let normalizedNasalReading = previewHiragana.last == "n"
        ? hiraganaToKatakana(String(previewHiragana.dropLast()) + "ん") : nil

    for i in 0..<mainResults.count {
        let candidate = mainResults[i]

        let constructStart = performanceEnabled ? performanceNow() : 0
        let candidateText = constructCandidateString(candidate: candidate, hiragana: previewHiragana)
        if let onReadingCompletion {
            if let kanaCompletionReading,
               extendsKanaReading(candidate, normalizedReading: kanaCompletionReading) {
                onReadingCompletion(i)
            } else if completePendingRoman,
                      candidate.rubyCount > previewHiragana.count ||
                        (i > 0 && pendingCompletionTexts.contains(candidateText) && learningCandidateRuby(candidate) != normalizedNasalReading) {
                onReadingCompletion(i)
            }
        }
        if performanceEnabled {
            constructCandidateStringMs += elapsedPerformanceMilliseconds(since: constructStart)
        }

        let resolveStart = performanceEnabled ? performanceNow() : 0
        let resolvedCandidate = resolveCandidateCompositionForDisplay(
            originalComposingText: targetComposingText,
            previewComposingText: previewComposingText,
            candidateComposingCount: candidate.composingCount,
            resolutionCache: &resolutionCache
        )
        if performanceEnabled {
            resolveCandidateCompositionMs += elapsedPerformanceMilliseconds(since: resolveStart)
        }
        let correspondingCount = resolvedCandidate.correspondingCount

        let strdupStart = performanceEnabled ? performanceNow() : 0
        let text = _strdup(candidateText)
        let combinedSubtext = suffixAfterCursor.isEmpty
            ? resolvedCandidate.remainingConvertTarget
            : resolvedCandidate.remainingConvertTarget + suffixAfterCursor
        let subtext = _strdup(combinedSubtext)
        let hiragana = i == 0 ? _strdup(fullHiraganaForCandidate) : nil
        if performanceEnabled {
            strdupCandidatesMs += elapsedPerformanceMilliseconds(since: strdupStart)
        }

        result.append(
            FFICandidate(
                text: text,
                subtext: subtext,
                hiragana: hiragana,
                correspondingCount: Int32(correspondingCount),
                candidateId: learningCandidateId(at: i, batchFirstId: learningCandidateBatchFirstId),
                predictionReading: suffixAfterCursor.isEmpty
                    ? keyboardCorrectionReading(candidate, remainingReading: resolvedCandidate.remainingConvertTarget).flatMap { _strdup($0) }
                    : nil
            )
        )
    }

    lengthPtr.pointee = CInt(result.count)
    let listPointer = to_list_pointer(result)
    if performanceEnabled {
        let stringAllocationCount = result.isEmpty ? 0 : result.count * 2 + 1
        performanceLog(
            operation: "get_composed_text",
            stage: "construct_candidate_string",
            elapsedMs: constructCandidateStringMs,
            details: "candidate_count=\(result.count);main_candidate_count=\(mainResults.count);zenzai_candidate_count=\(zenzaiMainResults.count);normal_nbest_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);\(diagnosticDetails)"
        )
        performanceLog(
            operation: "get_composed_text",
            stage: "resolve_candidate_composition",
            elapsedMs: resolveCandidateCompositionMs,
            details: "candidate_count=\(result.count);cache_entries=\(resolutionCache.count);\(diagnosticDetails)"
        )
        performanceLog(
            operation: "get_composed_text",
            stage: "strdup_candidates",
            elapsedMs: strdupCandidatesMs,
            details: "candidate_count=\(result.count);string_allocations=\(stringAllocationCount);\(diagnosticDetails)"
        )
        performanceLog(
            operation: "get_composed_text",
            stage: "build_ffi_candidates_total",
            elapsedMs: elapsedPerformanceMilliseconds(since: buildStart),
            details: "candidate_count=\(result.count);main_candidate_count=\(mainResults.count);zenzai_candidate_count=\(zenzaiMainResults.count);normal_nbest_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);cache_entries=\(resolutionCache.count);string_allocations=\(stringAllocationCount);learning_cache_batches=\(learningCandidateCache.batchCount);learning_cache_slots=\(learningCandidateCache.slotCount);learning_cache_protected_slots=\(learningCandidateCache.protectedSlotCount);\(diagnosticDetails)"
        )
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedText",
        stage: "postprocessCandidates",
        state: "completed",
        details: "candidate_count=\(result.count);main_candidate_count=\(mainResults.count);zenzai_candidate_count=\(zenzaiMainResults.count);normal_nbest_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "GetComposedText: postprocessCandidates completed candidateCount=\(result.count) mainCandidateCount=\(mainResults.count) zenzaiCandidateCount=\(zenzaiMainResults.count) normalNBestCandidateCount=\(normalNBestConverted?.mainResults.count ?? 0) \(diagnosticDetails)")
    serverLog("DEBUG", "GetComposedText: completed candidateCount=\(result.count) \(diagnosticDetails)")

    return listPointer
}

@_silgen_name("GetComposedTextForCursorPrefix")
public func get_composed_text_for_cursor_prefix(
    requiredInputCount: Int32,
    lengthPtr: UnsafeMutablePointer<CInt>
) -> UnsafeMutablePointer<UnsafeMutablePointer<FFICandidate>?> {
    adoptPendingUserDictionary()
    let functionStart = performanceNow()
    let performanceEnabled = serverLogCallbacks.isPerformanceLogEnabled()
    let hiragana = composingText.convertTarget
    let suffixAfterCursor = String(hiragana.dropFirst(composingText.convertTargetCursorPosition))
    let prefixComposingText = composingText.prefixToCursorPosition()
    let requiredBoundary = requiredInputCount >= 0 ? Int(requiredInputCount) : nil
    if let requiredBoundary, requiredBoundary != prefixComposingText.input.count {
        lengthPtr.pointee = 0
        serverLog(
            "ERROR",
            "GetComposedTextForCursorPrefix: skipped reason=required_boundary_mismatch requiredInputCount=\(requiredBoundary) actualInputCount=\(prefixComposingText.input.count)"
        )
        return to_list_pointer([])
    }
    let previewState = makeCandidatePreviewComposingTextForCursorPrefix(
        prefixComposingText: prefixComposingText,
        suffixAfterCursor: suffixAfterCursor
    )
    let previewPrefixComposingText = previewState.composingText
    let prefixHiragana = prefixComposingText.convertTarget
    let previewPrefixHiragana = previewPrefixComposingText.convertTarget
    let contextString = (config["context"] as? String) ?? ""
    let diagnosticSnapshot = zenzaiDiagnosticSnapshot()
    let runtimeZenzaiEnabled = diagnosticSnapshot.runtimeEnabled
    let useZenzai = effectiveZenzaiEnabledForCandidates(
        isConfigured: runtimeZenzaiEnabled,
        inputCount: prefixComposingText.input.count,
        hiraganaCount: prefixHiragana.count
    )
    let diagnosticsEnabled = zenzaiDiagnosticsEnabled(useZenzai: useZenzai)
    let diagnosticDetails = diagnosticsEnabled
        ? zenzaiDiagnosticDetails(
            snapshot: diagnosticSnapshot,
            contextLength: contextString.count,
            inputCount: prefixComposingText.input.count,
            hiraganaLength: prefixHiragana.count,
            previewHiraganaLength: previewPrefixHiragana.count,
            useZenzai: useZenzai,
            syntheticEndOfText: previewState.syntheticEndOfText
        )
        : ""
    serverLog(
        "DEBUG",
        "GetComposedTextForCursorPrefix: start suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    var options = getOptions(context: contextString, zenzaiEnabled: useZenzai)
    if hasTrailingUncompletedRoman(in: previewPrefixComposingText) {
        options.requireJapanesePrediction = .disabled
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedTextForCursorPrefix",
        stage: "requestCandidates",
        state: "begin",
        details: "suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "GetComposedTextForCursorPrefix: requestCandidates begin suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)")
    if performanceEnabled {
        performanceLog(
            operation: "get_composed_text_for_cursor_prefix",
            stage: "prepare_request",
            elapsedMs: elapsedPerformanceMilliseconds(since: functionStart),
            details: "suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
    }
    let totalStart = performanceNow()
    let cursorPrefixZenzaiOptions = useZenzai ? optionsForZenzaiConversion(from: options) : options
    let cursorPrefixSupplementOptions = optionsForNormalNBestSupplement(from: options)
    let normalNBestConverted: ConversionResult?
    let converted: ConversionResult
    let requestStart = performanceNow()
    if useZenzai {
        let execution = executeZenzaiWithNormalNBestSupplement(
            inputData: previewPrefixComposingText,
            zenzaiOptions: cursorPrefixZenzaiOptions,
            supplementOptions: cursorPrefixSupplementOptions,
            operation: "get_composed_text_for_cursor_prefix",
            diagnosticDetails: "suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
        normalNBestConverted = execution.supplement
        converted = execution.zenzai
    } else {
        normalNBestConverted = nil
        converted = converter.requestCandidates(previewPrefixComposingText, options: cursorPrefixZenzaiOptions)
    }
    let requestMs = elapsedPerformanceMilliseconds(since: requestStart)
    performanceLog(
        operation: "get_composed_text_for_cursor_prefix",
        stage: "request_candidates",
        elapsedMs: requestMs,
        details: "first_clause_candidate_count=\(converted.firstClauseResults.count);main_candidate_count=\(converted.mainResults.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedTextForCursorPrefix",
        stage: "requestCandidates",
        state: "completed",
        details: "first_clause_candidate_count=\(converted.firstClauseResults.count);main_candidate_count=\(converted.mainResults.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "GetComposedTextForCursorPrefix: requestCandidates returned firstClauseCandidateCount=\(converted.firstClauseResults.count) mainCandidateCount=\(converted.mainResults.count) suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)")
    let cursorPrefixMainResults = normalNBestConverted.map {
        mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: converted.mainResults,
            normalNBestResults: $0.mainResults,
            hiragana: previewPrefixHiragana
        )
    } ?? converted.mainResults
    let cursorPrefixFirstClauseResults = normalNBestConverted.map {
        mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: converted.firstClauseResults,
            normalNBestResults: $0.firstClauseResults,
            hiragana: previewPrefixHiragana,
            filterZenzaiAlternatives: false
        )
    } ?? converted.firstClauseResults
    if let normalNBestConverted {
        serverLog(
            "DEBUG",
            "GetComposedTextForCursorPrefix: merged Zenzai candidates firstClauseCandidateCount=\(cursorPrefixFirstClauseResults.count) mainCandidateCount=\(cursorPrefixMainResults.count) zenzaiFirstClauseCandidateCount=\(converted.firstClauseResults.count) zenzaiMainCandidateCount=\(converted.mainResults.count) normalNBestFirstClauseCandidateCount=\(normalNBestConverted.firstClauseResults.count) normalNBestMainCandidateCount=\(normalNBestConverted.mainResults.count) suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedTextForCursorPrefix",
        stage: "postprocessCandidates",
        state: "begin",
        details: "phase=first_clause;first_clause_candidate_count=\(cursorPrefixFirstClauseResults.count);main_candidate_count=\(cursorPrefixMainResults.count);zenzai_first_clause_candidate_count=\(converted.firstClauseResults.count);zenzai_main_candidate_count=\(converted.mainResults.count);normal_nbest_first_clause_candidate_count=\(normalNBestConverted?.firstClauseResults.count ?? 0);normal_nbest_main_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    var cursorPrefixResolutionCache: [String: CandidateDisplayResolution] = [:]
    let boundaryFirstClauseResults = cursorPrefixBoundaryFirstClauseResults(
        zenzaiFirstClauseResults: converted.firstClauseResults,
        mergedFirstClauseResults: cursorPrefixFirstClauseResults
    )
    let firstClauseCorrespondingCount = requiredBoundary
        ?? cursorPrefixFirstClauseCorrespondingCount(
            firstClauseResults: boundaryFirstClauseResults,
            originalComposingText: prefixComposingText,
            previewComposingText: previewPrefixComposingText,
            resolutionCache: &cursorPrefixResolutionCache
        )
    let preliminaryCursorPrefixResults = cursorPrefixCandidateDisplayResults(
        mainResults: cursorPrefixMainResults,
        firstClauseResults: cursorPrefixFirstClauseResults,
        firstClauseCorrespondingCount: firstClauseCorrespondingCount,
        originalComposingText: prefixComposingText,
        previewComposingText: previewPrefixComposingText,
        previewHiragana: previewPrefixHiragana,
        resolutionCache: &cursorPrefixResolutionCache
    )
    let shouldRequestExactClauseResults = preliminaryCursorPrefixResults.count < cursorPrefixExactClauseSupplementCandidateThreshold
    var exactClauseResults: [Candidate] = []
    if let firstClauseCorrespondingCount, shouldRequestExactClauseResults {
        let exactClauseComposingText = makeCursorPrefixExactClauseComposingText(
            prefixComposingText: prefixComposingText,
            correspondingCount: firstClauseCorrespondingCount
        )
        let exactClausePreviewState = makeCandidatePreviewComposingText(
            from: exactClauseComposingText
        )
        let exactClauseDiagnosticDetails = diagnosticsEnabled
            ? zenzaiDiagnosticDetails(
                snapshot: diagnosticSnapshot,
                contextLength: contextString.count,
                inputCount: exactClauseComposingText.input.count,
                hiraganaLength: exactClauseComposingText.convertTarget.count,
                previewHiraganaLength: exactClausePreviewState.composingText.convertTarget.count,
                useZenzai: useZenzai,
                syntheticEndOfText: exactClausePreviewState.syntheticEndOfText
            )
            : ""
        candidateCrashTrace(
            useZenzai: useZenzai,
            operation: "GetComposedTextForCursorPrefix",
            stage: "requestCandidatesExactClause",
            state: "begin",
            details: "corresponding_count=\(firstClauseCorrespondingCount);\(exactClauseDiagnosticDetails)"
        )
        serverLog(
            "DEBUG",
            "GetComposedTextForCursorPrefix: requestCandidates exactClause begin correspondingCount=\(firstClauseCorrespondingCount) \(exactClauseDiagnosticDetails)"
        )
        let exactClauseNormalNBestConverted: ConversionResult?
        let exactClauseConverted: ConversionResult
        let exactClauseRequestStart = performanceNow()
        if useZenzai {
            let execution = executeZenzaiWithNormalNBestSupplement(
                inputData: exactClausePreviewState.composingText,
                zenzaiOptions: cursorPrefixZenzaiOptions,
                supplementOptions: cursorPrefixSupplementOptions,
                operation: "get_composed_text_for_cursor_prefix_exact_clause",
                diagnosticDetails: "corresponding_count=\(firstClauseCorrespondingCount);\(exactClauseDiagnosticDetails)"
            )
            exactClauseNormalNBestConverted = execution.supplement
            exactClauseConverted = execution.zenzai
        } else {
            exactClauseNormalNBestConverted = nil
            exactClauseConverted = converter.requestCandidates(
                exactClausePreviewState.composingText,
                options: cursorPrefixZenzaiOptions
            )
        }
        exactClauseResults = exactClauseNormalNBestConverted.map {
            mergeZenzaiMainResultsWithNormalNBest(
                zenzaiResults: exactClauseConverted.mainResults,
                normalNBestResults: $0.mainResults,
                hiragana: exactClausePreviewState.composingText.convertTarget
            )
        } ?? exactClauseConverted.mainResults
        if let exactClauseNormalNBestConverted {
            serverLog(
                "DEBUG",
                "GetComposedTextForCursorPrefix: merged exactClause Zenzai candidates candidateCount=\(exactClauseResults.count) zenzaiCandidateCount=\(exactClauseConverted.mainResults.count) normalNBestCandidateCount=\(exactClauseNormalNBestConverted.mainResults.count) correspondingCount=\(firstClauseCorrespondingCount) \(exactClauseDiagnosticDetails)"
            )
        }
        let exactClauseRequestMs = elapsedPerformanceMilliseconds(since: exactClauseRequestStart)
        performanceLog(
            operation: "get_composed_text_for_cursor_prefix",
            stage: "request_candidates_exact_clause",
            elapsedMs: exactClauseRequestMs,
            details: "candidate_count=\(exactClauseResults.count);zenzai_candidate_count=\(exactClauseConverted.mainResults.count);normal_nbest_candidate_count=\(exactClauseNormalNBestConverted?.mainResults.count ?? 0);corresponding_count=\(firstClauseCorrespondingCount);\(exactClauseDiagnosticDetails)"
        )
        candidateCrashTrace(
            useZenzai: useZenzai,
            operation: "GetComposedTextForCursorPrefix",
            stage: "requestCandidatesExactClause",
            state: "completed",
            details: "candidate_count=\(exactClauseResults.count);zenzai_candidate_count=\(exactClauseConverted.mainResults.count);normal_nbest_candidate_count=\(exactClauseNormalNBestConverted?.mainResults.count ?? 0);corresponding_count=\(firstClauseCorrespondingCount);\(exactClauseDiagnosticDetails)"
        )
        serverLog(
            "DEBUG",
            "GetComposedTextForCursorPrefix: requestCandidates exactClause returned candidateCount=\(exactClauseResults.count) zenzaiCandidateCount=\(exactClauseConverted.mainResults.count) normalNBestCandidateCount=\(exactClauseNormalNBestConverted?.mainResults.count ?? 0) correspondingCount=\(firstClauseCorrespondingCount) \(exactClauseDiagnosticDetails)"
        )
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedTextForCursorPrefix",
        stage: "postprocessCandidates",
        state: "begin",
        details: "phase=merge;preliminary_candidate_count=\(preliminaryCursorPrefixResults.count);exact_clause_candidate_count=\(exactClauseResults.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    let rawCursorPrefixResults = exactClauseResults.isEmpty
        ? preliminaryCursorPrefixResults
        : cursorPrefixCandidateDisplayResults(
            mainResults: cursorPrefixMainResults,
            firstClauseResults: cursorPrefixFirstClauseResults,
            exactClauseResults: exactClauseResults,
            firstClauseCorrespondingCount: firstClauseCorrespondingCount,
            originalComposingText: prefixComposingText,
            previewComposingText: previewPrefixComposingText,
            previewHiragana: previewPrefixHiragana,
            resolutionCache: &cursorPrefixResolutionCache
        )
    let rawCursorPrefixPrioritized = prioritizeLearningSelectionOverrides(
        rawCursorPrefixResults,
        ruby: previewPrefixHiragana,
        leftContext: contextString,
        candidate: { $0.candidate },
        synthesizeElement: { synth in
            CursorPrefixCandidateResult(
                candidate: synth,
                displayText: constructCandidateString(candidate: synth, hiragana: previewPrefixHiragana)
            )
        }
    )
    var cursorPrefixResults = rawCursorPrefixPrioritized
    if let first = cursorPrefixResults.first, first.candidate.rubyCount > previewPrefixHiragana.count {
        if let exactIdx = cursorPrefixResults.firstIndex(where: { $0.candidate.rubyCount <= previewPrefixHiragana.count }) {
            let exactCandidate = cursorPrefixResults.remove(at: exactIdx)
            cursorPrefixResults.insert(exactCandidate, at: 0)
        }
    }
    let totalMs = elapsedPerformanceMilliseconds(since: totalStart)
    performanceLog(
        operation: "get_composed_text_for_cursor_prefix",
        stage: "total_before_ffi_candidates",
        elapsedMs: totalMs,
        details: "candidate_count=\(cursorPrefixResults.count);first_clause_candidate_count=\(cursorPrefixFirstClauseResults.count);main_candidate_count=\(cursorPrefixMainResults.count);zenzai_first_clause_candidate_count=\(converted.firstClauseResults.count);zenzai_main_candidate_count=\(converted.mainResults.count);normal_nbest_first_clause_candidate_count=\(normalNBestConverted?.firstClauseResults.count ?? 0);normal_nbest_main_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);exact_clause_candidate_count=\(exactClauseResults.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    let buildStart = performanceEnabled ? performanceNow() : 0
    var resolveCandidateCompositionMs = 0
    var strdupCandidatesMs = 0
    var result: [FFICandidate] = []
    result.reserveCapacity(cursorPrefixResults.count)
    let learningCandidateBatchFirstId = cacheLearningCandidates(
        cursorPrefixResults.map(\.candidate), allowReadOnlyDeletion: true
    )
    let ffiHiragana = previewPrefixHiragana + suffixAfterCursor

    for i in 0..<cursorPrefixResults.count {
        let cursorPrefixResult = cursorPrefixResults[i]
        let candidate = cursorPrefixResult.candidate

        let resolveStart = performanceEnabled ? performanceNow() : 0
        let resolvedCandidate = resolveCandidateCompositionForDisplay(
            originalComposingText: prefixComposingText,
            previewComposingText: previewPrefixComposingText,
            candidateComposingCount: candidate.composingCount,
            resolutionCache: &cursorPrefixResolutionCache
        )
        if performanceEnabled {
            resolveCandidateCompositionMs += elapsedPerformanceMilliseconds(since: resolveStart)
        }
        let correspondingCount = resolvedCandidate.correspondingCount

        let strdupStart = performanceEnabled ? performanceNow() : 0
        let text = _strdup(cursorPrefixResult.displayText)
        let subtext = _strdup(resolvedCandidate.remainingConvertTarget + suffixAfterCursor)
        let hiragana = i == 0 ? _strdup(ffiHiragana) : nil
        if performanceEnabled {
            strdupCandidatesMs += elapsedPerformanceMilliseconds(since: strdupStart)
        }

        result.append(
            FFICandidate(
                text: text,
                subtext: subtext,
                hiragana: hiragana,
                correspondingCount: Int32(correspondingCount),
                candidateId: learningCandidateId(at: i, batchFirstId: learningCandidateBatchFirstId),
                predictionReading: nil
            )
        )
    }

    lengthPtr.pointee = CInt(result.count)
    let listPointer = to_list_pointer(result)
    if performanceEnabled {
        let stringAllocationCount = result.isEmpty ? 0 : result.count * 2 + 1
        performanceLog(
            operation: "get_composed_text_for_cursor_prefix",
            stage: "resolve_candidate_composition",
            elapsedMs: resolveCandidateCompositionMs,
            details: "candidate_count=\(result.count);cache_entries=\(cursorPrefixResolutionCache.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
        performanceLog(
            operation: "get_composed_text_for_cursor_prefix",
            stage: "strdup_candidates",
            elapsedMs: strdupCandidatesMs,
            details: "candidate_count=\(result.count);string_allocations=\(stringAllocationCount);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
        performanceLog(
            operation: "get_composed_text_for_cursor_prefix",
            stage: "build_ffi_candidates_total",
            elapsedMs: elapsedPerformanceMilliseconds(since: buildStart),
            details: "candidate_count=\(result.count);first_clause_candidate_count=\(cursorPrefixFirstClauseResults.count);main_candidate_count=\(cursorPrefixMainResults.count);zenzai_first_clause_candidate_count=\(converted.firstClauseResults.count);zenzai_main_candidate_count=\(converted.mainResults.count);normal_nbest_first_clause_candidate_count=\(normalNBestConverted?.firstClauseResults.count ?? 0);normal_nbest_main_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);exact_clause_candidate_count=\(exactClauseResults.count);cache_entries=\(cursorPrefixResolutionCache.count);string_allocations=\(stringAllocationCount);learning_cache_batches=\(learningCandidateCache.batchCount);learning_cache_slots=\(learningCandidateCache.slotCount);learning_cache_protected_slots=\(learningCandidateCache.protectedSlotCount);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
        )
    }
    candidateCrashTrace(
        useZenzai: useZenzai,
        operation: "GetComposedTextForCursorPrefix",
        stage: "postprocessCandidates",
        state: "completed",
        details: "candidate_count=\(result.count);first_clause_candidate_count=\(cursorPrefixFirstClauseResults.count);main_candidate_count=\(cursorPrefixMainResults.count);zenzai_first_clause_candidate_count=\(converted.firstClauseResults.count);zenzai_main_candidate_count=\(converted.mainResults.count);normal_nbest_first_clause_candidate_count=\(normalNBestConverted?.firstClauseResults.count ?? 0);normal_nbest_main_candidate_count=\(normalNBestConverted?.mainResults.count ?? 0);exact_clause_candidate_count=\(exactClauseResults.count);suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)"
    )
    serverLog("DEBUG", "GetComposedTextForCursorPrefix: postprocessCandidates completed candidateCount=\(result.count) firstClauseCandidateCount=\(cursorPrefixFirstClauseResults.count) mainCandidateCount=\(cursorPrefixMainResults.count) zenzaiFirstClauseCandidateCount=\(converted.firstClauseResults.count) zenzaiMainCandidateCount=\(converted.mainResults.count) normalNBestFirstClauseCandidateCount=\(normalNBestConverted?.firstClauseResults.count ?? 0) normalNBestMainCandidateCount=\(normalNBestConverted?.mainResults.count ?? 0) exactClauseCandidateCount=\(exactClauseResults.count) suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)")
    serverLog("DEBUG", "GetComposedTextForCursorPrefix: completed candidateCount=\(result.count) suffix_len=\(suffixAfterCursor.count);\(diagnosticDetails)")

    return listPointer
}

@_silgen_name("ShrinkText")
public func shrink_text(
    offset: Int32
) -> UnsafeMutablePointer<CChar>  {
    discardPinnedLearningCandidatesBeforeCompositionEdit()
    serverLog("DEBUG", "ShrinkText: start offset=\(offset)")
    var afterComposingText = composingText
    let boundedOffset = min(max(Int(offset), 0), afterComposingText.input.count)
    afterComposingText.prefixComplete(composingCount: .inputCount(boundedOffset))
    composingText = afterComposingText

    serverLog("DEBUG", "ShrinkText: completed hiraganaLength=\(composingText.convertTarget.count) inputCount=\(composingText.input.count)")
    return _strdup(composingText.convertTarget)!
}

nonisolated(unsafe) private var learningConfigNeedsLoad = false

@_silgen_name("SetPrivateInput")
public func set_private_input(_ enabled: Bool) {
    let wasPrivate = privateInputActive
    privateInputActive = enabled
    if wasPrivate && !enabled && learningConfigNeedsLoad {
        learningConfigNeedsLoad = false
        ensureLearningMemoryDirectoryIfNeeded()
        loadLearningSelectionOverrides()
        loadPostCompositionBigrams()
    }
    if enabled {
        config["context"] = ""
        clearLearningCandidateCache()
        clearPostCompositionPredictionState()
    }
}

@_silgen_name("SetContext")
public func set_context(
    context: UnsafePointer<CChar>
) {
    let contextString = privateInputActive ? "" : String(cString: context)
    config["context"] = contextString
    serverLog("DEBUG", "SetContext: contextLength=\(contextString.count)")
}

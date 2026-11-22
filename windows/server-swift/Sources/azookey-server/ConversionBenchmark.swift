import Dispatch
import Foundation
import KanaKanjiConverterModule

// One isolated pair of converters per run. Each question starts a new composition.
private final class ConversionBenchmark {
    private let main: KanaKanjiConverter
    private let supplement: KanaKanjiConverter
    private let dictionary: URL
    private let model: URL

    init(dictionary: URL, model: URL) {
        self.dictionary = dictionary
        self.model = model
        let store = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
        main = KanaKanjiConverter(dicdataStore: store)
        supplement = KanaKanjiConverter(dicdataStore: store)
    }

    var modelLoaded: Bool { main.zenzStatus == "load \(model.absoluteString)" }

    struct Result: Encodable {
        let candidates: [String]
        let elapsed_ns: UInt64
        let nbest_ns: UInt64
    }

    func run(reading: String, context: String) -> Result {
        main.stopComposition()
        supplement.stopComposition()
        let start = DispatchTime.now().uptimeNanoseconds
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        let options = optionsForZenzaiConversion(from: makeConvertRequestOptions(
            context: context, zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: dictionary.appendingPathComponent("emoji_all_E15.1.txt"), textReplacer: .empty,
            zenzaiWeightURL: model, profile: "", experimentalKeyboardTypoCorrection: false
        ))
        var supplementOptions = optionsForNormalNBestSupplement(from: options)
        supplementOptions.zenzaiMode = .off
        let nbestStart = DispatchTime.now().uptimeNanoseconds
        let extra = supplement.requestCandidates(input, options: supplementOptions).mainResults
        let nbest = DispatchTime.now().uptimeNanoseconds - nbestStart
        main.adoptReusableFullInputLattice(supplement.exportReusableFullInputLattice(for: input))
        let primary = main.requestCandidates(input, options: options).mainResults
        let candidates = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: primary, normalNBestResults: extra, hiragana: input.convertTarget
        ).map { constructCandidateString(candidate: $0, hiragana: input.convertTarget) }
        return Result(candidates: candidates, elapsed_ns: DispatchTime.now().uptimeNanoseconds - start, nbest_ns: nbest)
    }
}

@_cdecl("ZeroConOpenConversionBenchmark")
public func openConversionBenchmark(_ model: UnsafePointer<CChar>, _ dictionary: UnsafePointer<CChar>, _ gpuLayers: Int32) -> UnsafeMutableRawPointer? {
    let dictionaryURL = URL(filePath: String(cString: dictionary), directoryHint: .isDirectory)
    guard FileManager.default.fileExists(atPath: dictionaryURL.appendingPathComponent("louds/charID.chid").path) else { return nil }
    #if os(Windows)
    // Windows runs this in a separate server process. Android shares the engine's CPU runtime.
    KanaKanjiConverterEngineRuntime.configure(gpuLayerCount: gpuLayers)
    #endif
    let session = ConversionBenchmark(dictionary: dictionaryURL, model: URL(filePath: String(cString: model)))
    _ = session.run(reading: "とうきょう", context: "")
    guard session.modelLoaded else { return nil }
    return Unmanaged.passRetained(session).toOpaque()
}

@_cdecl("ZeroConRunConversionBenchmark")
public func runConversionBenchmark(_ pointer: UnsafeMutableRawPointer, _ reading: UnsafePointer<CChar>, _ context: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    let session = Unmanaged<ConversionBenchmark>.fromOpaque(pointer).takeUnretainedValue()
    let result = session.run(reading: String(cString: reading), context: String(cString: context))
    guard session.modelLoaded, let encoded = try? JSONEncoder().encode(result) else { return nil }
    return _strdup(String(decoding: encoded, as: UTF8.self))
}

@_cdecl("ZeroConCloseConversionBenchmark")
public func closeConversionBenchmark(_ pointer: UnsafeMutableRawPointer) {
    Unmanaged<ConversionBenchmark>.fromOpaque(pointer).release()
}

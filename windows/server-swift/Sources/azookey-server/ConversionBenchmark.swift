import Dispatch
import Foundation
import KanaKanjiConverterModule
#if os(Windows)
import ffi
#endif

// One isolated pair of converters per run. Each question starts a new composition.
private final class ConversionBenchmark {
    let main: KanaKanjiConverter
    let supplement: KanaKanjiConverter
    let dictionary: URL
    let model: URL
    let keyboardTypo: Bool

    init(dictionary: URL, model: URL, keyboardTypo: Bool = false) {
        self.dictionary = dictionary
        self.model = model
        self.keyboardTypo = keyboardTypo
        let store = DicdataStore(dictionaryURL: dictionary, preloadDictionary: false)
        main = KanaKanjiConverter(dicdataStore: store)
        supplement = KanaKanjiConverter(dicdataStore: store)
    }

    var modelLoaded: Bool { main.zenzStatus == "load \(model.absoluteString)" }

    struct Result: Encodable {
        let candidates: [String]
        let elapsed_ns: UInt64
        let nbest_ns: UInt64
        let corrected_reading: String?
    }

    func baseOptions(context: String) -> ConvertRequestOptions {
        makeConvertRequestOptions(
            context: context, zenzaiEnabled: true,
            runtimeDirectoryURL: dictionary,
            emojiDictionaryURL: dictionary.appendingPathComponent("emoji_all_E15.1.txt"), textReplacer: .empty,
            zenzaiWeightURL: model, profile: "", experimentalKeyboardTypoCorrection: keyboardTypo
        )
    }

    func run(reading: String, context: String) -> Result {
        main.stopComposition()
        supplement.stopComposition()
        let start = DispatchTime.now().uptimeNanoseconds
        var input = ComposingText()
        input.insertAtCursorPosition(reading, inputStyle: .direct)
        let options = optionsForZenzaiConversion(from: baseOptions(context: context))
        var supplementOptions = optionsForNormalNBestSupplement(from: options)
        supplementOptions.zenzaiMode = .off
        let execution = executeZenzaiWithNormalNBestSupplement(
            mainConverter: main,
            supplementConverter: supplement,
            inputData: input,
            zenzaiOptions: options,
            supplementOptions: supplementOptions,
            emitLogs: false,
            operation: "conversion_benchmark",
            diagnosticDetails: ""
        )
        let candidates = mergeZenzaiMainResultsWithNormalNBest(
            zenzaiResults: execution.zenzai.mainResults,
            normalNBestResults: execution.supplement.mainResults,
            hiragana: input.convertTarget
        )
        return Result(
            candidates: candidates.map { constructCandidateString(candidate: $0, hiragana: input.convertTarget) },
            elapsed_ns: DispatchTime.now().uptimeNanoseconds - start,
            nbest_ns: execution.nbestNs,
            corrected_reading: keyboardTypo ? candidates.first.flatMap { keyboardCorrectionReading($0) } : nil
        )
    }
}

@_cdecl("ZeroConOpenConversionBenchmark")
public func openConversionBenchmark(_ model: UnsafePointer<CChar>, _ dictionary: UnsafePointer<CChar>, _ gpuLayers: Int32) -> UnsafeMutableRawPointer? {
    #if os(Windows)
    // Windows runs this in a separate server process. Android shares the engine's CPU runtime.
    KanaKanjiConverterEngineRuntime.configure(gpuLayerCount: gpuLayers)
    let keyboardTypo = ProcessInfo.processInfo.environment["AZOOKEY_BENCHMARK_KEYBOARD_TYPO"] == "1"
    #else
    let keyboardTypo = false
    #endif
    return openConversionBenchmark(model, dictionary, gpuLayers: gpuLayers, keyboardTypo: keyboardTypo)
}

#if os(Android)
@_cdecl("ZeroConOpenTypoConversionBenchmark")
public func openTypoConversionBenchmark(_ model: UnsafePointer<CChar>, _ dictionary: UnsafePointer<CChar>, _ keyboardTypo: Bool) -> UnsafeMutableRawPointer? {
    openConversionBenchmark(model, dictionary, gpuLayers: 0, keyboardTypo: keyboardTypo)
}
#endif

private func openConversionBenchmark(_ model: UnsafePointer<CChar>, _ dictionary: UnsafePointer<CChar>, gpuLayers: Int32, keyboardTypo: Bool) -> UnsafeMutableRawPointer? {
    let dictionaryURL = URL(filePath: String(cString: dictionary), directoryHint: .isDirectory)
    guard FileManager.default.fileExists(atPath: dictionaryURL.appendingPathComponent("louds/charID.chid").path) else { return nil }
    let session = ConversionBenchmark(dictionary: dictionaryURL, model: URL(filePath: String(cString: model)), keyboardTypo: keyboardTypo)
    _ = session.run(reading: "とうきょう", context: "")
    guard session.modelLoaded else { return nil }
    #if os(Windows)
    // モデルがCPUにフォールバックした環境では、GPUの計測として扱わない。
    guard gpuLayers <= 0 || llama_supports_gpu_offload() else { return nil }
    #endif
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

@_cdecl("ZeroConConfigureRuntimeTuning")
public func configureRuntimeTuning(_ decodeThreads: Int32, _ batchThreads: Int32, _ directModelFirstMode: Int32) {
    ZenzaiRuntimeTuning.configure(
        decodeThreads: decodeThreads > 0 ? decodeThreads : nil,
        batchThreads: batchThreads > 0 ? batchThreads : nil,
        directModelFirstOverride: directModelFirstMode < 0 ? nil : (directModelFirstMode != 0)
    )
}

@_cdecl("ZeroConProbeConversionTuning")
public func probeConversionTuning(_ pointer: UnsafeMutableRawPointer, _ configsJson: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    let session = Unmanaged<ConversionBenchmark>.fromOpaque(pointer).takeUnretainedValue()
    guard let configs = try? JSONDecoder().decode([RuntimeTuningProbeConfig].self, from: Data(String(cString: configsJson).utf8)),
          let measurements = probeRuntimeTuningConfigs(
              mainConverter: session.main,
              supplementConverter: session.supplement,
              baseOptions: session.baseOptions(context: ""),
              configs: configs
          ),
          session.modelLoaded,
          let encoded = try? JSONEncoder().encode(measurements) else { return nil }
    return _strdup(String(decoding: encoded, as: UTF8.self))
}

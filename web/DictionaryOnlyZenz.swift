import EfficientNGram
package import Foundation

// Model inference is unavailable in the dictionary-only WebAssembly build.
// The shared converter never constructs this backend with zenzaiMode set to .off.
package final class Zenz {
    package var resourceURL: URL

    init(resourceURL: URL) throws {
        throw ModelUnavailable()
    }

    private struct ModelUnavailable: Error {}

    package func endSession() {}

    func candidateEvaluate(
        convertTarget: String, candidates: [Candidate], requestRichCandidates: Bool,
        prefixConstraint: Kana2Kanji.PrefixConstraint,
        personalizationMode: (mode: ConvertRequestOptions.ZenzaiMode.PersonalizationMode, base: EfficientNGram, personal: EfficientNGram)?,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode
    ) -> CandidateEvaluationResult { .error }

    func predictNextInputText(
        leftSideContext: String, composingText: String, count: Int, minLength: Int = 1,
        maxEntropy: Float?, versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode,
        possibleNexts: [String] = []
    ) -> String { "" }

    package func pureGreedyDecoding(pureInput: String, maxCount: Int = .max) -> String { "" }

    func generateTypoCandidates(
        leftSideContext: String, composingText: ComposingText, inputStyle: InputStyle,
        experimentalConfig: ExperimentalTypoCorrectionConfig, cache: ZenzaiTypoGenerationCache
    ) -> [ZenzaiTypoCandidate] { [] }
}

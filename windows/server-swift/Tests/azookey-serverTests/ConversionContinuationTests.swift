import Foundation
import KanaKanjiConverterModule
import Testing
@testable import azookey_server

private func continuationElement(
    word: String,
    ruby: String,
    mid: Int = MIDData.一般.mid
) -> DicdataElement {
    DicdataElement(
        word: word,
        ruby: ruby,
        cid: CIDData.一般名詞.cid,
        mid: mid,
        value: 0
    )
}

private func continuationCandidate(
    _ data: [DicdataElement],
    isLearningTarget: Bool = true
) -> Candidate {
    Candidate(
        text: data.reduce(into: "") { $0 += $1.word },
        value: 0,
        composingCount: .surfaceCount(data.reduce(into: 0) { $0 += $1.ruby.count }),
        lastMid: data.last?.mid ?? MIDData.一般.mid,
        data: data,
        isLearningTarget: isLearningTarget
    )
}

@Test func conversionContinuationSlicesAtExactNodeBoundary() throws {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
        continuationElement(word: "を", ruby: "を"),
        continuationElement(word: "得", ruby: "え"),
        continuationElement(word: "たり", ruby: "たり"),
    ])
    let sliced = try #require(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            currentReading: "じょうほうをえたり"
        )
    )

    #expect(sliced.data.map(\.word) == ["情報", "を", "得", "たり"])
    #expect(sliced.text == "情報を得たり")
    #expect(sliced.composingCount == .surfaceCount(9))
    #expect(sliced.rubyCount == 9)
    #expect(sliced.isLearningTarget)
    #expect(sliced.lastMid == MIDData.一般.mid)
}

@Test func conversionContinuationSplitsStraddlingDictionaryNode() throws {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
    ])
    let sliced = try #require(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "つい", surface: "追"),
            currentReading: "かじょうほう"
        )
    )

    #expect(sliced.data.map(\.word) == ["加", "情報"])
    #expect(sliced.data.map(\.ruby) == ["か", "じょうほう"])
    #expect(sliced.data.first?.lcid == CIDData.一般名詞.cid)
    #expect(sliced.data.first?.rcid == CIDData.一般名詞.cid)
    #expect(sliced.data.first?.mid == MIDData.一般.mid)
    #expect(sliced.data.first?.value() == candidate.data.first?.value())
    #expect(sliced.text == "加情報")
    #expect(sliced.composingCount == .surfaceCount(6))
    #expect(sliced.isLearningTarget == false)
}

@Test func conversionContinuationRejectsPrefixSurfaceMismatch() {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
    ])

    #expect(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追記"),
            currentReading: "じょうほう"
        ) == nil
    )
}

@Test func conversionContinuationRejectsStraddlingSurfaceMismatch() {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
    ])

    #expect(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "つい", surface: "追記"),
            currentReading: "かじょうほう"
        ) == nil
    )
}

@Test func conversionContinuationRejectsSuffixIncompatibleWithCurrentReading() {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
    ])

    #expect(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            currentReading: "かんけい"
        ) == nil
    )
}

@Test func conversionContinuationRejectsEmptySuffix() {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
    ])

    #expect(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            currentReading: "じょうほう"
        ) == nil
    )
}

@Test func conversionContinuationRejectsUnrepresentableBoundary() {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
    ])

    #expect(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついかじょうほう", surface: "追加情報"),
            currentReading: "を"
        ) == nil
    )
}

@Test func conversionContinuationKeepsCandidateExtendingBeyondCurrentReading() throws {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ついか"),
        continuationElement(word: "情報", ruby: "じょうほう"),
        continuationElement(word: "の", ruby: "の"),
    ])
    let sliced = try #require(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            currentReading: "じょうほう"
        )
    )

    #expect(sliced.data.map(\.word) == ["情報", "の"])
    #expect(sliced.composingCount == .surfaceCount(6))
}

@Test func conversionContinuationMatchesKatakanaDictionaryRubyToHiraganaReading() throws {
    let candidate = continuationCandidate([
        continuationElement(word: "追加", ruby: "ツイカ"),
        continuationElement(word: "情報", ruby: "ジョウホウ"),
    ])
    let sliced = try #require(
        sliceConversionContinuationCandidate(
            candidate,
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            currentReading: "じょうほう"
        )
    )

    #expect(sliced.data.map(\.word) == ["情報"])
    #expect(sliced.data.map(\.ruby) == ["ジョウホウ"])
    #expect(sliced.composingCount == .surfaceCount(5))
}

@Test func conversionContinuationContextStripsOnlyMatchingSurfaceSuffix() {
    #expect(
        resolveConversionContinuationContext(context: "これは追加", continuationSurface: "追加")
            == .use(context: "これは")
    )
    #expect(
        resolveConversionContinuationContext(context: "追加", continuationSurface: "追加")
            == .use(context: "")
    )
    #expect(
        resolveConversionContinuationContext(context: "これは追記", continuationSurface: "追加")
            == .skip
    )
    #expect(
        resolveConversionContinuationContext(context: "", continuationSurface: "追加")
            == .use(context: "")
    )
    #expect(
        resolveConversionContinuationContext(context: "これは", continuationSurface: "")
            == .skip
    )
}

@Test func conversionContinuationRequestBuildsCombinedDirectInput() throws {
    let request = try #require(
        makeConversionContinuationRequest(
            continuation: ConversionContinuation(reading: "ついか", surface: "追加"),
            allowZenzai: true,
            isCursorAtEnd: true,
            previewHiragana: "じょうほう",
            hasTrailingUncompletedRoman: false,
            context: "これは追加"
        )
    )

    #expect(request.context == "これは")
    #expect(request.combinedComposingText.convertTarget == "ついかじょうほう")
    #expect(request.combinedComposingText.input.count == 8)
    #expect(request.combinedComposingText.isAtEndIndex)
}

@Test func conversionContinuationRequestRejectsIneligibleInputs() {
    let continuation = ConversionContinuation(reading: "ついか", surface: "追加")

    func request(
        _ continuation: ConversionContinuation?,
        allowZenzai: Bool = true,
        isCursorAtEnd: Bool = true,
        previewHiragana: String = "じょうほう",
        hasTrailingUncompletedRoman: Bool = false,
        context: String = ""
    ) -> ConversionContinuationRequest? {
        makeConversionContinuationRequest(
            continuation: continuation,
            allowZenzai: allowZenzai,
            isCursorAtEnd: isCursorAtEnd,
            previewHiragana: previewHiragana,
            hasTrailingUncompletedRoman: hasTrailingUncompletedRoman,
            context: context
        )
    }

    #expect(request(nil) == nil)
    #expect(request(continuation, allowZenzai: false) == nil)
    #expect(request(continuation, isCursorAtEnd: false) == nil)
    #expect(request(continuation, hasTrailingUncompletedRoman: true) == nil)
    #expect(request(continuation, previewHiragana: "情報") == nil)
    #expect(request(continuation, previewHiragana: String(repeating: "あ", count: 17)) == nil)
    #expect(
        request(
            ConversionContinuation(
                reading: String(repeating: "あ", count: 44),
                surface: "追加"
            ),
            previewHiragana: "じょうほう"
        ) == nil
    )
    #expect(request(continuation, context: "これは追記") == nil)
    #expect(request(continuation) != nil)
}

@Test func conversionContinuationSetterValidatesAndClearsState() async {
    await MainActor.run {
        clearConversionContinuation()
        defer { clearConversionContinuation() }

        @MainActor func set(_ reading: String, _ surface: String) -> Bool {
            setConversionContinuation(reading: reading, surface: surface)
        }

        #expect(set("", ""))
        #expect(conversionContinuation == nil)

        #expect(set("ついか", "追加"))
        #expect(
            conversionContinuation
                == ConversionContinuation(reading: "ついか", surface: "追加")
        )

        #expect(!set("ついか", ""))
        #expect(conversionContinuation == nil)

        #expect(!set("", "追加"))
        #expect(conversionContinuation == nil)

        #expect(!set("tuika", "追加"))
        #expect(conversionContinuation == nil)

        #expect(set("たー", "たー"))
        #expect(set(String(repeating: "あ", count: maxConversionContinuationReadingCount), "追加"))
        #expect(
            !set(
                String(repeating: "あ", count: maxConversionContinuationReadingCount + 1),
                "追加"
            )
        )
        #expect(conversionContinuation == nil)

        #expect(
            set(
                "ついか",
                String(repeating: "追", count: maxConversionContinuationSurfaceCount)
            )
        )
        #expect(
            !set(
                "ついか",
                String(repeating: "追", count: maxConversionContinuationSurfaceCount + 1)
            )
        )
        #expect(conversionContinuation == nil)
    }
}
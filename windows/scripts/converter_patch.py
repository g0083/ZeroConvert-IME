"""Edits applied to the vendored AzooKeyKanaKanjiConverter before it is built.

The converter is a checksum-pinned third-party archive. Windows and Android
compile the same patched copy, so the anchors below are the contract with that
revision: when the pinned revision changes, this module must change in the same
commit. `prepare-modern-llama.py --check-converter` verifies the anchors against
the pinned archive without building anything, which is what keeps a converter
bump from silently breaking only the Android build.
"""

MODULEMAP_PATH = 'Sources/llama.cpp/module.modulemap'
ZENZ_CONTEXT_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzContext.swift'
)
ZENZ_PROMPT_BUILDER_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzPromptBuilder.swift'
)
ZENZ_EVALUATOR_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzCandidateEvaluator.swift'
)
LEARNING_MEMORY_PATH = (
    'Sources/KanaKanjiConverterModule/DictionaryManagement/LearningMemory.swift'
)
CONVERTER_API_PATH = 'Sources/KanaKanjiConverterModule/ConverterAPI/KanaKanjiConverter.swift'
LATTICE_PATH = 'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Lattice.swift'
DICDATA_STORE_PATH = 'Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStore.swift'
ZENZ_INPUT_GENERATOR_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzInputTextGenerator.swift'
)
ZENZ_PATH = 'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/Zenz.swift'
ZENZAI_PATH = 'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/zenzai.swift'
FULL_INPUT_PROCESSING_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/FullInputProcessing.swift'
)
FULL_INPUT_PROCESSING_WITH_PREFIX_CONSTRAINT_PATH = (
    'Sources/KanaKanjiConverterModule/ConversionAlgorithms/Core/'
    'FullInputProcessingWithPrefixConstraint.swift'
)
REGISTERED_NODE_PATH = 'Sources/KanaKanjiConverterModule/ConversionAlgorithms/RegisteredNode.swift'
LATTICE_NODE_PATH = 'Sources/KanaKanjiConverterModule/ConversionAlgorithms/LatticeNode.swift'

EXACT_KEYBOARD_LOOKUP_ANCHOR = '    public func requestCandidates(_ inputData: ComposingText, options: ConvertRequestOptions) -> ConversionResult {'
EXACT_KEYBOARD_LOOKUP = '''    /// Returns syntax connection score between `formerRcid` and `latterLcid`.
    public func postCompositionConnectionScore(formerRcid: Int, latterLcid: Int) -> PValue {
        self.converter.dicdataStore.getCCValue(formerRcid, latterLcid)
    }

    /// Generates bounded contextual next-word readings from the active Zenzai model using left-side context.
    public func requestContextualNextWordReadings(
        leftSideContext: String,
        options: ConvertRequestOptions,
        maxBranches: Int = 5,
        maxTokensPerBranch: Int = 5,
        maxDurationSeconds: Double = 0.085,
        shouldCancel: (() -> Bool)? = nil
    ) -> [ContextualNextWordReading] {
        guard !leftSideContext.isEmpty,
              options.zenzaiMode.enabled,
              case .v3 = options.zenzaiMode.versionDependentMode,
              shouldCancel?() != true,
              let zenz = self.getModel(modelURL: options.zenzaiMode.weightURL) else {
            return []
        }
        return zenz.contextualNextWordReadings(
            leftSideContext: leftSideContext,
            versionDependentConfig: options.zenzaiMode.versionDependentMode,
            maxBranches: maxBranches,
            maxTokensPerBranch: maxTokensPerBranch,
            maxDurationSeconds: maxDurationSeconds,
            shouldCancel: shouldCancel
        )
    }

    /// Drops cached long-term learning LOUDS data without rewriting files on disk,
    /// so sibling converter instances observe recent commits in the same session.
    public func invalidateLearningMemoryCache() {
        let previous = self.dicdataStoreState.learningMemoryManager.config
        var invalidationConfig = previous
        invalidationConfig.maxMemoryCount = -1
        self.dicdataStoreState.updateLearningConfig(invalidationConfig)
        var restoredConfig = previous
        restoredConfig.maxMemoryCount = max(0, previous.maxMemoryCount)
        self.dicdataStoreState.updateLearningConfig(restoredConfig)
    }

    /// Synchronizes learning memory configuration on the converter before committing or forgetting candidates.
    public func syncLearningConfig(
        learningType: LearningType,
        memoryDirectoryURL: URL?,
        maxMemoryCount: Int = 65536
    ) {
        self.dicdataStoreState.updateLearningConfig(
            LearningConfig(
                learningType: learningType,
                maxMemoryCount: maxMemoryCount,
                memoryURL: memoryDirectoryURL
            )
        )
    }

    private func withSession<T>(_ sessionID: SessionID?, _ body: () -> T) -> T {
        guard let sessionID, sessionID != self.activeSessionID else {
            return body()
        }
        let previousSessionID = self.activeSessionID
        self.activeSessionID = sessionID
        defer {
            self.activeSessionID = previousSessionID
        }
        return body()
    }

    /// Expands an uncompleted roman suffix into compatible katakana reading prefixes
    /// using the active input style table so overrides match mid-romaji keystrokes.
    public func predictiveRubyPrefixes(for composingText: ComposingText) -> [String] {
        let inputStyle = composingText.input.last?.inputStyle ?? .direct
        let source = self.converter.resolvePredictiveInputSource(composingText: composingText, inputStyle: inputStyle)
        guard source.droppedSuffixCount > 0, !source.possibleNexts.isEmpty else {
            return []
        }
        let baseKatakana = source.baseConvertTarget.toKatakana()
        guard !baseKatakana.isEmpty else {
            return []
        }
        return source.possibleNexts.map { baseKatakana + $0 }
    }

    /// Looks up whole-reading completions while the last syllable is still being typed.
    /// Use the active romanization table and a small dictionary budget instead of
    /// running the regular clause prediction search for each possible syllable.
    public func requestPendingRomanCompletions(
        _ inputData: ComposingText,
        options: ConvertRequestOptions
    ) -> [Candidate] {
        guard options.requireJapanesePrediction.isEnabled,
              inputData.input.last?.inputStyle == .roman2kana else { return [] }
        let source = self.converter.resolvePredictiveInputSource(composingText: inputData, inputStyle: .roman2kana)
        guard source.droppedSuffixCount > 0, (1...24).contains(source.baseConvertTarget.count) else { return [] }

        // When both ギ and ギュ are possible, the first search already covers the second.
        var suffixes: [String] = []
        for suffix in Set(source.possibleNexts).sorted() where !suffix.isEmpty {
            if !suffixes.contains(where: { suffix.hasPrefix($0) }) {
                suffixes.append(suffix)
            }
        }
        guard !suffixes.isEmpty else { return [] }
        self.dicdataStoreState.updateIfRequired(options: options)
        let store = self.converter.dicdataStore
        let base = source.baseConvertTarget.toKatakana()
        let inputLength = inputData.convertTarget.count
        let cc = store.getCCLatter(CIDData.BOS.cid)
        let dynamicEntries = store.getPrefixMatchDynamicUserDict(base, state: self.dicdataStoreState)
        var candidates: [Candidate] = []
        let compoundKana = "ャュョ"
        let contractedSuffixes = Set(source.possibleNexts.filter { $0.last.map(compoundKana.contains) == true })
        // Limited searches of ギ alone may never reach ギュ. Give contracted
        // syllables their own budget even when the plain kana is also possible.
        let searchedSuffixes = Array(suffixes.prefix(8)) + contractedSuffixes.sorted()
            .filter { !suffixes.contains($0) }.prefix(4)
        for suffix in searchedSuffixes {
            let ruby = base + suffix
            let entries = store.getPredictionLOUDSDicdata(
                key: ruby, state: self.dicdataStoreState, includeExactMatch: true, pendingRomanLimit: 48
            )
            let userEntries = dynamicEntries.lazy.filter { $0.ruby.hasPrefix(ruby) }.prefix(48)
            for entry in (entries + userEntries) where entry.ruby.hasPrefix(ruby) {
                let usesMeaning = DicdataStore.includeMMValueCalculation(entry)
                let meaningScore = usesMeaning ? store.getMMValue(MIDData.EOS.mid, entry.mid) : .zero
                candidates.append(Candidate(
                    text: entry.word,
                    value: entry.value() + cc.get(entry.lcid) + meaningScore - PValue(max(0, entry.ruby.count - inputLength)),
                    composingCount: .surfaceCount(inputLength),
                    lastMid: usesMeaning ? entry.mid : MIDData.EOS.mid,
                    data: [entry]
                ))
            }
        }
        // Deduplicate after ranking so different entries for a surface keep the best reading.
        let ranked = candidates.sorted {
            if $0.value != $1.value { return $0.value > $1.value }
            if $0.text != $1.text { return $0.text < $1.text }
            return $0.data[0].ruby < $1.data[0].ruby
        }
        let unique = self.getUniqueCandidate(ranked)
        // Keep the leading matches, then give distinct contracted syllables one
        // slot each so common words from a single branch cannot fill the strip.
        var diversified = Array(unique.prefix(2))
        var representedSuffixes = Set<String>()
        func contractedSuffix(for candidate: Candidate) -> String? {
            let remainder = candidate.data[0].ruby.dropFirst(base.count)
            return contractedSuffixes.first(where: { remainder.hasPrefix($0) })
        }
        representedSuffixes.formUnion(diversified.compactMap(contractedSuffix))
        for candidate in unique.dropFirst(2) where diversified.count < 5 {
            if let suffix = contractedSuffix(for: candidate), representedSuffixes.insert(suffix).inserted {
                diversified.append(candidate)
            }
        }
        return Array(self.getUniqueCandidate(diversified + unique).prefix(8))
    }

    /// Looks up whole-reading prefix completions for completed kana input so
    /// multi-clause tail predictions cannot evict whole-word completions.
    public func requestWholeReadingCompletions(
        _ inputData: ComposingText,
        options: ConvertRequestOptions
    ) -> [Candidate] {
        guard options.requireJapanesePrediction.isEnabled else { return [] }
        let convertTarget = inputData.convertTarget
        guard (2...24).contains(convertTarget.count),
              let lastChar = convertTarget.last,
              !(lastChar.isASCII && lastChar.isLetter) else {
            return []
        }
        let ruby = convertTarget.toKatakana()
        guard !ruby.isEmpty else { return [] }
        self.dicdataStoreState.updateIfRequired(options: options)
        let store = self.converter.dicdataStore
        let inputLength = convertTarget.count
        let cc = store.getCCLatter(CIDData.BOS.cid)
        let entries = store.getPredictionLOUDSDicdata(
            key: ruby, state: self.dicdataStoreState, includeExactMatch: false
        )
        let dynamicEntries = store.getPrefixMatchDynamicUserDict(ruby, state: self.dicdataStoreState)
        var candidates: [Candidate] = []
        for entry in (entries + dynamicEntries) where entry.ruby.hasPrefix(ruby) && entry.ruby != ruby {
            let usesMeaning = DicdataStore.includeMMValueCalculation(entry)
            let meaningScore = usesMeaning ? store.getMMValue(MIDData.EOS.mid, entry.mid) : .zero
            candidates.append(Candidate(
                text: entry.word,
                value: entry.value() + cc.get(entry.lcid) + meaningScore - PValue(max(0, entry.ruby.count - inputLength)),
                composingCount: .surfaceCount(inputLength),
                lastMid: usesMeaning ? entry.mid : MIDData.EOS.mid,
                data: [entry]
            ))
        }
        let ranked = candidates.sorted {
            if $0.value != $1.value { return $0.value > $1.value }
            if $0.text != $1.text { return $0.text < $1.text }
            return $0.data[0].ruby < $1.data[0].ruby
        }
        return Array(self.getUniqueCandidate(ranked).prefix(6))
    }

    /// Requests conversion candidates on an isolated named session, optionally reusing cached
    /// predictions only or constraining the lattice prefix to a committed surface.
    public func requestCandidates(
        _ inputData: ComposingText,
        options: ConvertRequestOptions,
        sessionID: String?,
        cacheOnlyJapanesePrediction: Bool = false,
        prefixConstraintSurface: String? = nil
    ) -> ConversionResult {
        self.withSession(sessionID) {
            self.requestCandidatesImpl(
                inputData,
                options: options,
                cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction,
                prefixConstraintSurface: prefixConstraintSurface
            )
        }
    }

    /// Bounded exact-word lookup for measured keyboard alternatives. Reuses the
    /// active dictionary and user/learning state without another lattice conversion.
    public func requestExactKeyboardCorrectionCandidates(_ inputData: ComposingText, options: ConvertRequestOptions) -> [Candidate] {
        let reading = inputData.convertTarget
        guard (2 ... self.converter.dicdataStore.maxlength).contains(reading.count) else { return [] }
        var exactOptions = options
        exactOptions.experimentalKeyboardTypoCorrection = false
        self.dicdataStoreState.updateIfRequired(options: exactOptions)
        self.dicdataStoreState.prepareKeyboardTypoCorrection(composingText: inputData, enabled: false,
            maxSpanLength: self.converter.dicdataStore.maxlength)
        let ruby = String(reading.map { $0.toKatakana() })
        let nodes = self.converter.dicdataStore.lookupDicdata(composingText: inputData,
            surfaceRange: (0, (reading.count - 1) ..< reading.count),
            needTypoCorrection: false, state: self.dicdataStoreState)
        return nodes.lazy.map { $0.data }.filter {
            $0.ruby == ruby && !$0.metadata.contains(.isKeyboardTypoCorrection)
        }.sorted { $0.value() > $1.value() }.prefix(8).map { entry in
            Candidate(text: entry.word, value: entry.value(), composingCount: .inputCount(inputData.input.count),
                lastMid: entry.mid, data: [entry], isLearningTarget: false)
        }
    }

'''

CONVERTER_API_REPLACEMENTS = (
    (
        '    private func processResult(inputData: ComposingText, result: (result: LatticeNode, lattice: Lattice), options: ConvertRequestOptions) -> ConversionResult {',
        '    private func processResult(\n'
        '        inputData: ComposingText,\n'
        '        result: (result: LatticeNode, lattice: Lattice),\n'
        '        options: ConvertRequestOptions,\n'
        '        cacheOnlyJapanesePrediction: Bool = false,\n'
        '        prefixConstraintSurface: String? = nil\n'
        '    ) -> ConversionResult {',
    ),
    (
        '        if options.requireJapanesePrediction.isEnabled {\n'
        '            let clauseResultCandidates = clauseResult.map { self.converter.processClauseCandidate($0) }\n'
        '            bestCandidateDataForPrediction = zip(clauseResult, clauseResultCandidates).max {$0.1.value < $1.1.value}!.0\n'
        '            wholeSentenceUniqueCandidates = self.getUniqueCandidate(clauseResultCandidates)\n'
        '        } else {\n'
        '            wholeSentenceUniqueCandidates = self.getUniqueCandidate(clauseResult.lazy.map { self.converter.processClauseCandidate($0) })\n'
        '        }',
        '        if options.requireJapanesePrediction.isEnabled && !cacheOnlyJapanesePrediction {\n'
        '            let clauseResultCandidates = clauseResult.map { self.converter.processClauseCandidate($0) }\n'
        '            if let prefixConstraintSurface, !prefixConstraintSurface.isEmpty {\n'
        '                bestCandidateDataForPrediction = zip(clauseResult, clauseResultCandidates)\n'
        '                    .filter { $0.1.text.hasPrefix(prefixConstraintSurface) }\n'
        '                    .max { $0.1.value < $1.1.value }?.0\n'
        '            } else {\n'
        '                bestCandidateDataForPrediction = zip(clauseResult, clauseResultCandidates).max {$0.1.value < $1.1.value}!.0\n'
        '            }\n'
        '            wholeSentenceUniqueCandidates = self.getUniqueCandidate(clauseResultCandidates)\n'
        '        } else {\n'
        '            wholeSentenceUniqueCandidates = self.getUniqueCandidate(clauseResult.lazy.map { self.converter.processClauseCandidate($0) })\n'
        '        }',
    ),
    (
        '            if options.requireJapanesePrediction.isEnabled, let bestCandidateDataForPrediction {\n'
        '                let candidates = self.getUniqueCandidate(\n'
        '                    self.getPredictionCandidate(bestCandidateDataForPrediction, composingText: inputData, options: options)\n'
        '                ).min(count: 3, sortedBy: {$0.value > $1.value})\n'
        '                stablePredictionCandidates = self.stablePredictionCandidates(\n'
        '                    composingText: inputData,\n'
        '                    inputStyle: inputStyle\n'
        '                )\n'
        '                predictionResults = self.mergeStableCandidates(\n'
        '                    stableCandidates: stablePredictionCandidates,\n'
        '                    otherCandidates: candidates,\n'
        '                    limit: 3\n'
        '                )\n'
        '                if options.requireJapanesePrediction.shouldMix {\n'
        '                    bestThreePredictionCandidates = predictionResults\n'
        '                } else {\n'
        '                    bestThreePredictionCandidates = []\n'
        '                }\n'
        '            } else {\n'
        '                self.invalidateStablePredictionCandidateCache()\n'
        '                bestThreePredictionCandidates = []\n'
        '            }',
        '            if options.requireJapanesePrediction.isEnabled {\n'
        '                if cacheOnlyJapanesePrediction {\n'
        '                    predictionResults = Array(\n'
        '                        self.getUniqueCandidate(\n'
        '                            self.stablePredictionCandidates(\n'
        '                                composingText: inputData,\n'
        '                                inputStyle: inputStyle\n'
        '                            )\n'
        '                        ).prefix(3)\n'
        '                    )\n'
        '                } else if let bestCandidateDataForPrediction {\n'
        '                    let candidates = self.getUniqueCandidate(\n'
        '                        self.getPredictionCandidate(bestCandidateDataForPrediction, composingText: inputData, options: options)\n'
        '                    ).min(count: 3, sortedBy: {$0.value > $1.value})\n'
        '                    stablePredictionCandidates = self.stablePredictionCandidates(\n'
        '                        composingText: inputData,\n'
        '                        inputStyle: inputStyle\n'
        '                    )\n'
        '                    predictionResults = self.mergeStableCandidates(\n'
        '                        stableCandidates: stablePredictionCandidates,\n'
        '                        otherCandidates: candidates,\n'
        '                        limit: 3\n'
        '                    )\n'
        '                } else {\n'
        '                    self.invalidateStablePredictionCandidateCache()\n'
        '                    predictionResults = []\n'
        '                }\n'
        '                if options.requireJapanesePrediction.shouldMix {\n'
        '                    bestThreePredictionCandidates = predictionResults\n'
        '                } else {\n'
        '                    bestThreePredictionCandidates = []\n'
        '                }\n'
        '            } else {\n'
        '                self.invalidateStablePredictionCandidateCache()\n'
        '                bestThreePredictionCandidates = []\n'
        '            }',
    ),
    (
        '    private func convertToLattice(_ inputData: ComposingText, N_best: Int, zenzaiMode: ConvertRequestOptions.ZenzaiMode, needTypoCorrection: Bool) -> (result: LatticeNode, lattice: Lattice)? {\n'
        '        if inputData.convertTarget.isEmpty {\n'
        '            return nil\n'
        '        }',
        '    private func convertToLattice(\n'
        '        _ inputData: ComposingText,\n'
        '        N_best: Int,\n'
        '        zenzaiMode: ConvertRequestOptions.ZenzaiMode,\n'
        '        needTypoCorrection: Bool,\n'
        '        prefixConstraintSurface: String? = nil\n'
        '    ) -> (result: LatticeNode, lattice: Lattice)? {\n'
        '        if inputData.convertTarget.isEmpty {\n'
        '            return nil\n'
        '        }\n'
        '        if !zenzaiMode.enabled, let prefixConstraintSurface, !prefixConstraintSurface.isEmpty {\n'
        '            let constraint = Kana2Kanji.PrefixConstraint(Array(prefixConstraintSurface.utf8))\n'
        '            let preprocessedLattice: Lattice?\n'
        '            if let previousInputData = self.currentSessionState.previousInputData,\n'
        '               !self.currentSessionState.lattice.isEmpty {\n'
        '                if previousInputData == inputData {\n'
        '                    let existing = self.currentSessionState.lattice\n'
        '                    existing.resetNodeStates()\n'
        '                    preprocessedLattice = existing\n'
        '                } else {\n'
        '                    preprocessedLattice = self.converter.buildLatticeWithIncrementalCache(\n'
        '                        inputData: inputData,\n'
        '                        inputCount: inputData.input.count,\n'
        '                        surfaceCount: inputData.convertTarget.count,\n'
        '                        incrementalCacheInfo: (inputData: previousInputData, lattice: self.currentSessionState.lattice),\n'
        '                        dicdataStoreState: self.dicdataStoreState\n'
        '                    )\n'
        '                }\n'
        '            } else {\n'
        '                preprocessedLattice = nil\n'
        '            }\n'
        '            let result = self.converter.kana2lattice_all_with_prefix_constraint(\n'
        '                inputData,\n'
        '                N_best: N_best,\n'
        '                constraint: constraint,\n'
        '                preprocessedLattice: preprocessedLattice,\n'
        '                dicdataStoreState: self.dicdataStoreState\n'
        '            )\n'
        '            self.updateCurrentSessionState {\n'
        '                $0.previousInputData = inputData\n'
        '                $0.lattice = result.lattice\n'
        '                $0.lastUnconstrainedResult = nil\n'
        '            }\n'
        '            return result\n'
        '        }',
    ),
    (
        '    public func requestCandidates(_ inputData: ComposingText, options: ConvertRequestOptions) -> ConversionResult {\n'
        '        debug("requestCandidates 入力は", inputData)\n'
        '        // 変換対象が無の場合\n'
        '        if inputData.convertTarget.isEmpty {\n'
        '            return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])\n'
        '        }\n'
        '        if options.shouldResetMemory {\n'
        '            self.resetMemory()\n'
        '        }\n'
        '        if let previous = self.currentSessionState.experimentalKeyboardTypoCorrection,\n'
        '           previous != options.experimentalKeyboardTypoCorrection\n'
        '        {\n'
        '            self.stopComposition()\n'
        '        }\n'
        '        self.updateCurrentSessionState {\n'
        '            $0.experimentalKeyboardTypoCorrection = options.experimentalKeyboardTypoCorrection\n'
        '        }\n'
        '        self.dicdataStoreState.updateIfRequired(options: options)\n'
        '        self.dicdataStoreState.prepareKeyboardTypoCorrection(\n'
        '            composingText: inputData,\n'
        '            enabled: options.experimentalKeyboardTypoCorrection,\n'
        '            maxSpanLength: self.converter.dicdataStore.maxlength\n'
        '        )\n'
        '        let needTypoCorrection = self.isClassicTypoCorrectionEnabled(options)\n'
        '\n'
        '        guard let result = self.convertToLattice(inputData, N_best: options.N_best, zenzaiMode: options.zenzaiMode, needTypoCorrection: needTypoCorrection) else {\n'
        '            return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])\n'
        '        }\n'
        '\n'
        '        return self.processResult(inputData: inputData, result: result, options: options)\n'
        '    }',
        '    public func requestCandidates(_ inputData: ComposingText, options: ConvertRequestOptions) -> ConversionResult {\n'
        '        self.requestCandidatesImpl(inputData, options: options)\n'
        '    }\n'
        '\n'
        '    private func requestCandidatesImpl(\n'
        '        _ inputData: ComposingText,\n'
        '        options: ConvertRequestOptions,\n'
        '        cacheOnlyJapanesePrediction: Bool = false,\n'
        '        prefixConstraintSurface: String? = nil\n'
        '    ) -> ConversionResult {\n'
        '        debug("requestCandidates 入力は", inputData)\n'
        '        // 変換対象が無の場合\n'
        '        if inputData.convertTarget.isEmpty {\n'
        '            return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])\n'
        '        }\n'
        '        if options.shouldResetMemory {\n'
        '            self.resetMemory()\n'
        '        }\n'
        '        if let previous = self.currentSessionState.experimentalKeyboardTypoCorrection,\n'
        '           previous != options.experimentalKeyboardTypoCorrection\n'
        '        {\n'
        '            self.stopComposition()\n'
        '        }\n'
        '        self.updateCurrentSessionState {\n'
        '            $0.experimentalKeyboardTypoCorrection = options.experimentalKeyboardTypoCorrection\n'
        '        }\n'
        '        self.dicdataStoreState.updateIfRequired(options: options)\n'
        '        if cacheOnlyJapanesePrediction, let prefixConstraintSurface, !prefixConstraintSurface.isEmpty {\n'
        '            guard options.requireJapanesePrediction.isEnabled else {\n'
        '                self.invalidateStablePredictionCandidateCache()\n'
        '                return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])\n'
        '            }\n'
        '            let inputStyle = inputData.input.last?.inputStyle ?? .direct\n'
        '            let cached = Array(\n'
        '                self.getUniqueCandidate(\n'
        '                    self.stablePredictionCandidates(composingText: inputData, inputStyle: inputStyle)\n'
        '                ).prefix(3)\n'
        '            )\n'
        '            self.updateStablePredictionCandidateCache(inputData: inputData, inputStyle: inputStyle, candidates: cached)\n'
        '            return ConversionResult(mainResults: [], predictionResults: cached, englishPredictionResults: [], firstClauseResults: [])\n'
        '        }\n'
        '        self.dicdataStoreState.prepareKeyboardTypoCorrection(\n'
        '            composingText: inputData,\n'
        '            enabled: options.experimentalKeyboardTypoCorrection,\n'
        '            maxSpanLength: self.converter.dicdataStore.maxlength\n'
        '        )\n'
        '        let needTypoCorrection = self.isClassicTypoCorrectionEnabled(options)\n'
        '\n'
        '        guard let result = self.convertToLattice(\n'
        '            inputData,\n'
        '            N_best: options.N_best,\n'
        '            zenzaiMode: options.zenzaiMode,\n'
        '            needTypoCorrection: needTypoCorrection,\n'
        '            prefixConstraintSurface: prefixConstraintSurface\n'
        '        ) else {\n'
        '            return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])\n'
        '        }\n'
        '\n'
        '        return self.processResult(\n'
        '            inputData: inputData,\n'
        '            result: result,\n'
        '            options: options,\n'
        '            cacheOnlyJapanesePrediction: cacheOnlyJapanesePrediction,\n'
        '            prefixConstraintSurface: prefixConstraintSurface\n'
        '        )\n'
        '    }',
    ),
)

MODULEMAP_ANCHOR = 'link "llama"'
MODULEMAP_REPLACEMENT = 'link "llama"\n    link "ggml"\n    link "ggml-base"'

# Ordered: each anchor is matched against the text produced by the previous
# replacement, so a later entry must not depend on text an earlier one removed.
ZENZ_CONTEXT_REPLACEMENTS = (
    (
        'enum ZenzError: LocalizedError {\n',
        'public enum ZenzaiRuntimeTuning {\n'
        '    private final class State: @unchecked Sendable {\n'
        '        private let lock = NSLock()\n'
        '        private var decodeThreadsOverride: Int32?\n'
        '        private var batchThreadsOverride: Int32?\n'
        '        private var directModelFirstOverride: Bool?\n\n'
        '        func configure(decodeThreads: Int32?, batchThreads: Int32?, directModelFirstOverride: Bool?) {\n'
        '            self.lock.lock()\n'
        '            defer { self.lock.unlock() }\n'
        '            self.decodeThreadsOverride = decodeThreads.map { max(1, $0) }\n'
        '            self.batchThreadsOverride = batchThreads.map { max(1, $0) }\n'
        '            self.directModelFirstOverride = directModelFirstOverride\n'
        '        }\n\n'
        '        func reset() {\n'
        '            self.configure(decodeThreads: nil, batchThreads: nil, directModelFirstOverride: nil)\n'
        '        }\n\n'
        '        func snapshot() -> (decode: Int32?, batch: Int32?, directModelFirst: Bool?) {\n'
        '            self.lock.lock()\n'
        '            defer { self.lock.unlock() }\n'
        '            return (self.decodeThreadsOverride, self.batchThreadsOverride, self.directModelFirstOverride)\n'
        '        }\n'
        '    }\n\n'
        '    nonisolated(unsafe) private static let state = State()\n\n'
        '    public static func configure(\n'
        '        decodeThreads: Int32? = nil,\n'
        '        batchThreads: Int32? = nil,\n'
        '        directModelFirstOverride: Bool? = nil\n'
        '    ) {\n'
        '        self.state.configure(\n'
        '            decodeThreads: decodeThreads,\n'
        '            batchThreads: batchThreads,\n'
        '            directModelFirstOverride: directModelFirstOverride\n'
        '        )\n'
        '    }\n\n'
        '    public static func reset() {\n'
        '        self.state.reset()\n'
        '    }\n\n'
        '    package static var resolvedDecodeThreadsOverride: Int32? {\n'
        '        self.state.snapshot().decode\n'
        '    }\n\n'
        '    package static var resolvedBatchThreadsOverride: Int32? {\n'
        '        self.state.snapshot().batch\n'
        '    }\n\n'
        '    package static var resolvedDirectModelFirstOverride: Bool? {\n'
        '        self.state.snapshot().directModelFirst\n'
        '    }\n'
        '}\n\n'
        'enum ZenzError: LocalizedError {\n',
    ),
    (
        '    private var prevInputBySeq: [llama_seq_id: [llama_token]] = [:]\n'
        '    private var prevPromptBySeq: [llama_seq_id: [llama_token]] = [:]',
        '    private var prevInputBySeq: [llama_seq_id: [llama_token]] = [:]\n'
        '    private var prevPromptBySeq: [llama_seq_id: [llama_token]] = [:]\n'
        '    let isGpuOffloaded: Bool\n'
        '    let modelWeightBytes: UInt64\n'
        '    #if Zenzai || ZenzaiCPU\n'
        '    private var greedySampler: UnsafeMutablePointer<llama_sampler>?\n'
        '    #else\n'
        '    private var greedySampler: OpaquePointer?\n'
        '    #endif\n\n'
        '    var prefersDirectModelFirst: Bool {\n'
        '        if let override = ZenzaiRuntimeTuning.resolvedDirectModelFirstOverride {\n'
        '            return override\n'
        '        }\n'
        '        return self.isGpuOffloaded || (self.modelWeightBytes > 0 && self.modelWeightBytes < 42 * 1024 * 1024)\n'
        '    }\n\n'
        '    static func resolvedThreads(isGpuOffloaded: Bool) -> (decode: Int, batch: Int) {\n'
        '        let logicalCores = max(1, ProcessInfo.processInfo.processorCount)\n'
        '        let defaultDecode = isGpuOffloaded\n'
        '            ? (logicalCores <= 1 ? 1 : min(4, max(2, logicalCores / 2)))\n'
        '            : (logicalCores <= 1 ? 1 : min(6, max(2, logicalCores / 2)))\n'
        '        let defaultBatch = isGpuOffloaded\n'
        '            ? defaultDecode\n'
        '            : (logicalCores <= 1 ? 1 : min(12, max(defaultDecode, logicalCores - 2)))\n'
        '        let decode = ZenzaiRuntimeTuning.resolvedDecodeThreadsOverride.map {\n'
        '            min(logicalCores, max(1, Int($0)))\n'
        '        } ?? defaultDecode\n'
        '        let batch = ZenzaiRuntimeTuning.resolvedBatchThreadsOverride.map {\n'
        '            min(logicalCores, max(decode, Int($0)))\n'
        '        } ?? max(decode, defaultBatch)\n'
        '        return (decode, batch)\n'
        '    }',
    ),
    (
        '    init(model: OpaquePointer, context: OpaquePointer, vocab: OpaquePointer) {\n'
        '        self.model = model\n'
        '        self.context = context\n'
        '        self.vocab = vocab\n'
        '    }\n\n'
        '    deinit {\n'
        '        llama_free(context)\n'
        '        llama_model_free(model)\n'
        '        llama_backend_free()\n'
        '    }',
        '    init(\n'
        '        model: OpaquePointer,\n'
        '        context: OpaquePointer,\n'
        '        vocab: OpaquePointer,\n'
        '        isGpuOffloaded: Bool = false,\n'
        '        modelWeightBytes: UInt64 = 0\n'
        '    ) {\n'
        '        self.model = model\n'
        '        self.context = context\n'
        '        self.vocab = vocab\n'
        '        self.isGpuOffloaded = isGpuOffloaded\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        self.modelWeightBytes = modelWeightBytes > 0 ? modelWeightBytes : llama_model_size(model)\n'
        '        self.greedySampler = llama_sampler_init_greedy()\n'
        '        #else\n'
        '        self.modelWeightBytes = modelWeightBytes\n'
        '        self.greedySampler = nil\n'
        '        #endif\n'
        '    }\n\n'
        '    deinit {\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        if let greedySampler {\n'
        '            llama_sampler_free(greedySampler)\n'
        '        }\n'
        '        #endif\n'
        '        llama_free(context)\n'
        '        llama_model_free(model)\n'
        '        llama_backend_free()\n'
        '    }',
    ),
    (
        '        #if Zenzai\n'
        '        model_params.n_gpu_layers = KanaKanjiConverterEngineRuntime.resolvedGpuLayerCount\n'
        '        #endif\n'
        '        #if ZenzaiCPU\n'
        '        // CPU 専用: GPU へのオフロードを無効化\n'
        '        model_params.n_gpu_layers = 0\n'
        '        model_params.split_mode = LLAMA_SPLIT_MODE_NONE\n'
        '        #endif\n'
        '        let model = llama_model_load_from_file(path, model_params)',
        '        #if Zenzai\n'
        '        let resolvedGpuLayers = KanaKanjiConverterEngineRuntime.resolvedGpuLayerCount\n'
        '        model_params.n_gpu_layers = resolvedGpuLayers\n'
        '        model_params.split_mode = LLAMA_SPLIT_MODE_NONE\n'
        '        let gpuOffloaded = resolvedGpuLayers > 0\n'
        '        #elseif ZenzaiCPU\n'
        '        // CPU 専用: GPU へのオフロードを無効化\n'
        '        model_params.n_gpu_layers = 0\n'
        '        model_params.split_mode = LLAMA_SPLIT_MODE_NONE\n'
        '        let gpuOffloaded = false\n'
        '        #else\n'
        '        let gpuOffloaded = false\n'
        '        #endif\n'
        '        let loadedModel: OpaquePointer?\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        if !gpuOffloaded, let cpuDevice = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) {\n'
        '            var cpuDevices: [ggml_backend_dev_t?] = [cpuDevice, nil]\n'
        '            loadedModel = cpuDevices.withUnsafeMutableBufferPointer { buffer in\n'
        '                model_params.devices = buffer.baseAddress\n'
        '                return llama_model_load_from_file(path, model_params)\n'
        '            }\n'
        '        } else {\n'
        '            loadedModel = llama_model_load_from_file(path, model_params)\n'
        '        }\n'
        '        #else\n'
        '        loadedModel = llama_model_load_from_file(path, model_params)\n'
        '        #endif\n'
        '        let model = loadedModel',
    ),
    (
        '        return ZenzContext(model: model, context: context, vocab: vocab)',
        '        return ZenzContext(model: model, context: context, vocab: vocab, isGpuOffloaded: gpuOffloaded)',
    ),
    (
        'model_params.use_mmap = true',
        'model_params.load_mode = LLAMA_LOAD_MODE_MMAP',
    ),
    (
        'ctx_params.n_ctx = 512',
        'ctx_params.n_ctx = 1024\n        ctx_params.n_seq_max = 2\n        ctx_params.kv_unified = true',
    ),
    # n_batch caps how many tokens one llama_decode call accepts, so leaving it
    # at 512 while n_ctx is 1024 makes long prompts abort on GGML_ASSERT rather
    # than degrade. Keep the two paired.
    (
        'ctx_params.n_batch = 512',
        'let (decodeThreads, batchThreads) = Self.resolvedThreads(isGpuOffloaded: false)\n'
        '        ctx_params.n_threads = Int32(decodeThreads)\n'
        '        ctx_params.n_threads_batch = Int32(batchThreads)\n'
        '        ctx_params.n_batch = 1024\n'
        '        ctx_params.n_ubatch = 512\n'
        '        ctx_params.n_outputs_max = 256\n'
        '        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED\n'
        '        ctx_params.no_perf = true',
    ),
    (
        '        var params = ctx_params\n'
        '        #if ZenzaiCPU\n'
        '        // CPU 専用: KV / KQV 等の GPU オフロードを完全に無効化\n'
        '        params.offload_kqv = false\n'
        '        #endif',
        '        var params = ctx_params\n'
        '        #if Zenzai\n'
        '        let isGpuParams = KanaKanjiConverterEngineRuntime.resolvedGpuLayerCount > 0\n'
        '        params.offload_kqv = isGpuParams\n'
        '        #elseif ZenzaiCPU\n'
        '        // CPU 専用: KV / KQV 等の GPU オフロードを完全に無効化\n'
        '        let isGpuParams = false\n'
        '        params.offload_kqv = false\n'
        '        #else\n'
        '        let isGpuParams = false\n'
        '        #endif\n'
        '        let (paramDecodeThreads, paramBatchThreads) = Self.resolvedThreads(isGpuOffloaded: isGpuParams)\n'
        '        params.n_threads = Int32(paramDecodeThreads)\n'
        '        params.n_threads_batch = Int32(paramBatchThreads)',
    ),
    (
        '    func resetContext() throws {\n'
        '        llama_free(self.context)\n'
        '        var params = Self.ctx_params\n'
        '        #if ZenzaiCPU\n'
        '        params.offload_kqv = false\n'
        '        #endif\n'
        '        let context = llama_init_from_model(self.model, params)\n'
        '        guard let context else {\n'
        '            debug("Could not load context!")\n'
        '            throw ZenzError.couldNotLoadContext\n'
        '        }\n'
        '        self.context = context\n'
        '        self.prevInputBySeq = [:]\n'
        '        self.prevPromptBySeq = [:]\n'
        '    }',
        '    func resetContext() throws {\n'
        '        // Reuse the allocated context and graph reservation; only clear KV memory.\n'
        '        llama_memory_clear(llama_get_memory(self.context), true)\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        if let greedySampler {\n'
        '            llama_sampler_reset(greedySampler)\n'
        '        }\n'
        '        #endif\n'
        '        self.prevInputBySeq = [:]\n'
        '        self.prevPromptBySeq = [:]\n'
        '    }',
    ),
    (
        'llama_backend_init()',
        'if let path = ProcessInfo.processInfo.environment["AZOOKEY_LLAMA_BACKEND_DIR"] {\n'
        '            ggml_backend_load_all_from_path(path)\n'
        '        } else { ggml_backend_load_all() }\n'
        '        llama_backend_init()',
    ),
    (
        'llama_kv_cache_seq_rm(context,',
        'llama_memory_seq_rm(llama_get_memory(context),',
    ),
    (
        'llama_kv_cache_seq_cp(context,',
        'llama_memory_seq_cp(llama_get_memory(context),',
    ),
    (
        'llama_kv_cache_seq_pos_max(self.context,',
        'llama_memory_seq_pos_max(llama_get_memory(self.context),',
    ),
    # The vendored llama_batch_add writes through batch.token[n_tokens] with no
    # bounds check, so a prompt longer than n_batch corrupts the heap before
    # llama_decode aborts on GGML_ASSERT. Enforce the capacity the context was
    # actually created with and degrade to a failed evaluation instead.
    (
        'var batch = llama_batch_init(512, 0, 1)\n'
        '        defer { llama_batch_free(batch) }\n'
        '        let n_ctx = llama_n_ctx(context)\n'
        '        let n_kv_req = tokens.count + (Int(n_len) - tokens.count)\n'
        '        if n_kv_req > n_ctx {\n'
        '            debug("error: n_kv_req > n_ctx, the required KV cache size is not big enough")\n'
        '        }',

        'let n_ctx = Int(llama_n_ctx(context))\n'
        '        let n_batch = Int(llama_n_batch(context))\n'
        '        let requestedLogits = max(0, tokens.count - max(prefixCacheCount, logits_start_index))\n'
        '        // tokens.count is the full prompt, and llama_decode receives one\n'
        '        // batch, so the KV cache, batch, and reserved output rows must hold it.\n'
        '        guard tokens.count <= n_ctx, tokens.count <= n_batch, requestedLogits <= 256 else {\n'
        '            debug("error: prompt of", tokens.count, "tokens exceeds n_ctx", n_ctx, "or n_batch", n_batch)\n'
        '            return nil\n'
        '        }\n'
        '        let (resolvedDecode, resolvedBatch) = Self.resolvedThreads(isGpuOffloaded: self.isGpuOffloaded)\n'
        '        let decodeThreads = Int32(resolvedDecode)\n'
        '        let batchThreads = Int32(resolvedBatch)\n'
        '        let deltaTokens = max(0, tokens.count - prefixCacheCount)\n'
        '        llama_set_n_threads(context, decodeThreads, deltaTokens <= 4 ? decodeThreads : batchThreads)\n'
        '        var batch = llama_batch_init(Int32(tokens.count), 0, 1)\n'
        '        defer { llama_batch_free(batch) }',
    ),
    (
        '        if llama_decode(context, batch) != 0 {\n'
        '            debug("llama_decode() failed")\n'
        '            return nil\n'
        '        }',
        '        if llama_decode(context, batch) != 0 {\n'
        '            self.prevInputBySeq[seqId] = Array(effectivePrevInput.prefix(prefixCacheCount))\n'
        '            debug("llama_decode() failed")\n'
        '            return nil\n'
        '        }',
    ),
    (
        '        } else {\n'
        '            let bufferPointer: UnsafeBufferPointer<Int8> = UnsafeBufferPointer(start: result, count: Int(nTokens))\n'
        '            return Array(bufferPointer)\n'
        '        }\n'
        '    }\n'
        '}',
        '        } else {\n'
        '            let bufferPointer: UnsafeBufferPointer<Int8> = UnsafeBufferPointer(start: result, count: Int(nTokens))\n'
        '            return Array(bufferPointer)\n'
        '        }\n'
        '    }\n\n'
        '    #if Zenzai || ZenzaiCPU\n'
        '    private static func appendPieceBytes(vocab: OpaquePointer, token: llama_token, buf: inout [CChar], into out: inout [UInt8]) {\n'
        '        let cap = Int32(buf.count)\n'
        '        let n = buf.withUnsafeMutableBufferPointer { ptr in\n'
        '            llama_token_to_piece(vocab, token, ptr.baseAddress, cap, 0, false)\n'
        '        }\n'
        '        if n > 0 {\n'
        '            let count = Int(n)\n'
        '            out.reserveCapacity(out.count + count)\n'
        '            for i in 0 ..< count {\n'
        '                out.append(UInt8(bitPattern: buf[i]))\n'
        '            }\n'
        '        } else if n < 0 {\n'
        '            let need = Int(-n)\n'
        '            var large = [CChar](repeating: 0, count: need)\n'
        '            let n2 = large.withUnsafeMutableBufferPointer { ptr in\n'
        '                llama_token_to_piece(vocab, token, ptr.baseAddress, Int32(need), 0, false)\n'
        '            }\n'
        '            if n2 > 0 {\n'
        '                let count = Int(n2)\n'
        '                out.reserveCapacity(out.count + count)\n'
        '                for i in 0 ..< count {\n'
        '                    out.append(UInt8(bitPattern: large[i]))\n'
        '                }\n'
        '            }\n'
        '        }\n'
        '    }\n\n'
        '    private static func findStopMarkerIndex(in bytes: [UInt8], from start: Int) -> Int? {\n'
        '        let n = bytes.count\n'
        '        var i = max(0, start)\n'
        '        while i < n {\n'
        '            let b = bytes[i]\n'
        '            if b == 0x0A || b == 0x0D {\n'
        '                return i\n'
        '            }\n'
        '            if b == 0xEE, i + 2 < n, bytes[i + 1] == 0xB8, (0x80 ... 0x8F).contains(bytes[i + 2]) {\n'
        '                return i\n'
        '            }\n'
        '            if b == 0x3C {\n'
        '                if i + 2 < n, bytes[i + 1] == 0x73, bytes[i + 2] == 0x3E {\n'
        '                    return i\n'
        '                }\n'
        '                if i + 3 < n, bytes[i + 1] == 0x2F, bytes[i + 2] == 0x73, bytes[i + 3] == 0x3E {\n'
        '                    return i\n'
        '                }\n'
        '            }\n'
        '            i += 1\n'
        '        }\n'
        '        return nil\n'
        '    }\n\n'
        '    private static func pieceHasStopMarker(vocab: OpaquePointer, token: llama_token, buf: inout [CChar], rollingTail: inout [UInt8]) -> Bool {\n'
        '        let prevCount = rollingTail.count\n'
        '        Self.appendPieceBytes(vocab: vocab, token: token, buf: &buf, into: &rollingTail)\n'
        '        if Self.findStopMarkerIndex(in: rollingTail, from: max(0, prevCount - 3)) != nil {\n'
        '            return true\n'
        '        }\n'
        '        if rollingTail.count > 16 {\n'
        '            rollingTail.removeFirst(rollingTail.count - 8)\n'
        '        }\n'
        '        return false\n'
        '    }\n'
        '    #endif\n\n'
        '    func generateDirectGreedy(promptTokens: [llama_token], maxTokens: Int) -> DirectZenzaiGenerationResult {\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        guard let sampler = self.greedySampler,\n'
        '              !promptTokens.isEmpty,\n'
        '              maxTokens > 0 else {\n'
        '            return .needsFallback\n'
        '        }\n'
        '        let nCtx = Int(llama_n_ctx(self.context))\n'
        '        let nBatch = Int(llama_n_batch(self.context))\n'
        '        guard promptTokens.count + maxTokens <= nCtx, nBatch > 0 else {\n'
        '            return .needsFallback\n'
        '        }\n'
        '        let memory = llama_get_memory(self.context)\n'
        '        let effectivePrevInput = self.prevInputBySeq[evalSeqId] ?? []\n'
        '        var prefix = effectivePrevInput.commonPrefix(with: promptTokens).count\n'
        '        if let otherPrevInput = self.prevInputBySeq[inputPredictionSeqId] {\n'
        '            let otherPrefix = otherPrevInput.commonPrefix(with: promptTokens).count\n'
        '            if otherPrefix > prefix {\n'
        '                let copiedCount = min(otherPrefix, promptTokens.count - 1)\n'
        '                if copiedCount > 0 {\n'
        '                    llama_memory_seq_rm(memory, evalSeqId, 0, -1)\n'
        '                    llama_memory_seq_cp(memory, inputPredictionSeqId, evalSeqId, 0, llama_pos(copiedCount))\n'
        '                    prefix = copiedCount\n'
        '                }\n'
        '            }\n'
        '        }\n'
        '        prefix = min(prefix, promptTokens.count - 1)\n'
        '        if prefix == 0\n'
        '            || !llama_memory_seq_rm(memory, evalSeqId, llama_pos(prefix), -1)\n'
        '            || llama_memory_seq_pos_max(memory, evalSeqId) != llama_pos(prefix - 1) {\n'
        '            if !llama_memory_seq_rm(memory, evalSeqId, 0, -1)\n'
        '                || llama_memory_seq_pos_max(memory, evalSeqId) != -1 {\n'
        '                llama_memory_clear(memory, true)\n'
        '                self.prevInputBySeq = [:]\n'
        '            } else {\n'
        '                self.prevInputBySeq[evalSeqId] = []\n'
        '            }\n'
        '            prefix = 0\n'
        '        }\n\n'
        '        let (resolvedDecode, resolvedBatch) = Self.resolvedThreads(isGpuOffloaded: self.isGpuOffloaded)\n'
        '        let decodeThreads = Int32(resolvedDecode)\n'
        '        let batchThreads = Int32(resolvedBatch)\n'
        '        let promptDelta = promptTokens.count - prefix\n'
        '        llama_set_n_threads(self.context, decodeThreads, promptDelta <= 4 ? decodeThreads : batchThreads)\n'
        '        llama_sampler_reset(sampler)\n\n'
        '        var cachedTokens = Array(promptTokens.prefix(prefix))\n'
        '        var mutablePrompt = promptTokens\n'
        '        var start = prefix\n'
        '        var prefillFailed = false\n'
        '        mutablePrompt.withUnsafeMutableBufferPointer { buf in\n'
        '            guard let base = buf.baseAddress else {\n'
        '                prefillFailed = true\n'
        '                return\n'
        '            }\n'
        '            while start < promptTokens.count {\n'
        '                let count = min(promptTokens.count - start, nBatch)\n'
        '                if llama_decode(self.context, llama_batch_get_one(base.advanced(by: start), Int32(count))) != 0 {\n'
        '                    prefillFailed = true\n'
        '                    return\n'
        '                }\n'
        '                start += count\n'
        '            }\n'
        '        }\n'
        '        if prefillFailed {\n'
        '            llama_memory_clear(memory, true)\n'
        '            self.prevInputBySeq = [:]\n'
        '            self.prevPromptBySeq[evalSeqId] = nil\n'
        '            return .decodeAborted\n'
        '        }\n'
        '        cachedTokens.append(contentsOf: promptTokens[prefix...])\n'
        '        llama_set_n_threads(self.context, decodeThreads, decodeThreads)\n\n'
        '        var outputBytes: [UInt8] = []\n'
        '        outputBytes.reserveCapacity(128)\n'
        '        var pieceBuf = [CChar](repeating: 0, count: 64)\n'
        '        var finished = false\n'
        '        var tokenMargins: [TokenLogitMargin] = []\n'
        '        tokenMargins.reserveCapacity(32)\n'
        '        let nVocab = Int(llama_vocab_n_tokens(self.vocab))\n\n'
        '        for _ in 0 ..< maxTokens {\n'
        '            var token: llama_token\n'
        '            var marginMilli: Int32? = nil\n'
        '            if nVocab > 0, let logits = llama_get_logits_ith(self.context, -1) {\n'
        '                var max1: Float = -Float.infinity\n'
        '                var max2: Float = -Float.infinity\n'
        '                var bestTok: llama_token = 0\n'
        '                for idx in 0 ..< nVocab {\n'
        '                    let value = logits[idx]\n'
        '                    if value > max1 {\n'
        '                        max2 = max1\n'
        '                        max1 = value\n'
        '                        bestTok = llama_token(idx)\n'
        '                    } else if value > max2 {\n'
        '                        max2 = value\n'
        '                    }\n'
        '                }\n'
        '                token = bestTok\n'
        '                if max1.isFinite && max2.isFinite {\n'
        '                    let diff = max(Float(0), max1 - max2)\n'
        '                    marginMilli = Int32(min(Float(60_000), (diff * 1000.0).rounded()))\n'
        '                } else if max1.isFinite {\n'
        '                    marginMilli = 60_000\n'
        '                }\n'
        '            } else {\n'
        '                token = llama_sampler_sample(sampler, self.context, -1)\n'
        '            }\n'
        '            if llama_vocab_is_eog(self.vocab, token) || token == self.eosToken {\n'
        '                finished = true\n'
        '                break\n'
        '            }\n'
        '            llama_sampler_accept(sampler, token)\n'
        '            let scanStart = max(0, outputBytes.count - 4)\n'
        '            Self.appendPieceBytes(vocab: self.vocab, token: token, buf: &pieceBuf, into: &outputBytes)\n'
        '            if let stopIndex = Self.findStopMarkerIndex(in: outputBytes, from: scanStart) {\n'
        '                outputBytes.removeSubrange(stopIndex...)\n'
        '                while let last = tokenMargins.last, last.byteEnd > outputBytes.count {\n'
        '                    tokenMargins.removeLast()\n'
        '                }\n'
        '                finished = true\n'
        '                break\n'
        '            }\n'
        '            if let marginMilli {\n'
        '                tokenMargins.append(TokenLogitMargin(byteEnd: outputBytes.count, marginMilli: marginMilli))\n'
        '            }\n'
        '            if outputBytes.count > 8192 {\n'
        '                self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '                self.prevPromptBySeq[evalSeqId] = promptTokens\n'
        '                return .needsFallback\n'
        '            }\n'
        '            if llama_decode(self.context, llama_batch_get_one(&token, 1)) != 0 {\n'
        '                llama_memory_clear(memory, true)\n'
        '                self.prevInputBySeq = [:]\n'
        '                self.prevPromptBySeq[evalSeqId] = nil\n'
        '                return .decodeAborted\n'
        '            }\n'
        '            cachedTokens.append(token)\n'
        '        }\n\n'
        '        self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '        self.prevPromptBySeq[evalSeqId] = promptTokens\n'
        '        guard finished, !outputBytes.isEmpty,\n'
        '              let decoded = String(bytes: outputBytes, encoding: .utf8),\n'
        '              !decoded.isEmpty else {\n'
        '            return .needsFallback\n'
        '        }\n'
        '        let verifiedMargins = tokenMargins.last?.byteEnd == outputBytes.count ? tokenMargins : []\n'
        '        return .text(decoded, tokenMargins: verifiedMargins)\n'
        '        #else\n'
        '        return .needsFallback\n'
        '        #endif\n'
        '    }\n\n'
        '    func continueDirectGreedy(prefixTokens: [llama_token], firstToken: llama_token, maxSteps: Int) -> [llama_token]? {\n'
        '        #if Zenzai || ZenzaiCPU\n'
        '        guard let sampler = self.greedySampler,\n'
        '              !prefixTokens.isEmpty,\n'
        '              maxSteps >= 0 else {\n'
        '            return nil\n'
        '        }\n'
        '        let nCtx = Int(llama_n_ctx(self.context))\n'
        '        guard prefixTokens.count + 1 + maxSteps <= nCtx else {\n'
        '            return nil\n'
        '        }\n'
        '        let memory = llama_get_memory(self.context)\n'
        '        let prefixCount = prefixTokens.count\n'
        '        guard llama_memory_seq_rm(memory, evalSeqId, llama_pos(prefixCount), -1),\n'
        '              llama_memory_seq_pos_max(memory, evalSeqId) == llama_pos(prefixCount - 1) else {\n'
        '            return nil\n'
        '        }\n'
        '        let decodeThreads = Int32(Self.resolvedThreads(isGpuOffloaded: self.isGpuOffloaded).decode)\n'
        '        llama_set_n_threads(self.context, decodeThreads, decodeThreads)\n'
        '        llama_sampler_reset(sampler)\n\n'
        '        var cachedTokens = prefixTokens\n'
        '        var generated: [llama_token] = [firstToken]\n'
        '        var curToken = firstToken\n'
        '        var pieceBuf = [CChar](repeating: 0, count: 64)\n'
        '        var rollingTail: [UInt8] = []\n'
        '        rollingTail.reserveCapacity(16)\n\n'
        '        if Self.pieceHasStopMarker(vocab: self.vocab, token: curToken, buf: &pieceBuf, rollingTail: &rollingTail) {\n'
        '            self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '            return generated\n'
        '        }\n\n'
        '        if maxSteps == 0 {\n'
        '            self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '            return nil\n'
        '        }\n\n'
        '        for _ in 0 ..< maxSteps {\n'
        '            if cachedTokens.count + 1 >= nCtx {\n'
        '                self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '                return nil\n'
        '            }\n'
        '            var stepToken = curToken\n'
        '            if llama_decode(self.context, llama_batch_get_one(&stepToken, 1)) != 0 {\n'
        '                llama_memory_seq_rm(memory, evalSeqId, llama_pos(prefixCount), -1)\n'
        '                self.prevInputBySeq[evalSeqId] = prefixTokens\n'
        '                return nil\n'
        '            }\n'
        '            cachedTokens.append(curToken)\n'
        '            let nextToken = llama_sampler_sample(sampler, self.context, -1)\n'
        '            if llama_vocab_is_eog(self.vocab, nextToken) || nextToken == self.eosToken {\n'
        '                self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '                return generated\n'
        '            }\n'
        '            llama_sampler_accept(sampler, nextToken)\n'
        '            generated.append(nextToken)\n'
        '            if Self.pieceHasStopMarker(vocab: self.vocab, token: nextToken, buf: &pieceBuf, rollingTail: &rollingTail) {\n'
        '                self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '                return generated\n'
        '            }\n'
        '            curToken = nextToken\n'
        '        }\n'
        '        self.prevInputBySeq[evalSeqId] = cachedTokens\n'
        '        return nil\n'
        '        #else\n'
        '        return nil\n'
        '        #endif\n'
        '    }\n'
        '}',
    ),
)


def _require(text, anchor):
    if anchor not in text:
        raise ValueError('Converter patch no longer applies: ' + anchor)


# Forgetting a word is an explicit user action, not a learning write, so it must
# work in read-only mode too. The upstream guard ties deletion to the same
# setting that permits new learning, which would make per-word deletion a no-op
# for users who keep learning history but freeze new entries.
LEARNING_FORGET_ANCHOR = '''    func forgetMemory(data: [DicdataElement]) {
        guard self.config.learningType.needUpdateMemory else {
            return
        }'''

LEARNING_FORGET_REPLACEMENT = '''    func forgetMemory(data: [DicdataElement]) {
        // Deletion is explicit: allow it whenever history is read at all.
        guard self.config.learningType.needUsingMemory else {
            return
        }'''

# resetMemory deleted the files but left memoryCollapsed set, so a reset in a
# session that had hit an update failure kept learning disabled until restart.
LEARNING_RESET_ANCHOR = '''        do {
            try LongTermLearningMemory.reset(directoryURL: memoryURL)
        } catch {
            debug("LearningManager reset failed", error)
        }
    }'''

LEARNING_RESET_REPLACEMENT = '''        do {
            try LongTermLearningMemory.reset(directoryURL: memoryURL)
            // The files it was derived from are gone, so the paused state is too.
            self.memoryCollapsed = LongTermLearningMemory.memoryCollapsed(directoryURL: memoryURL)
        } catch {
            debug("LearningManager reset failed", error)
        }
    }'''

LEARNING_CACHE_INVALIDATION_ANCHOR = '''    /// - Returns: Whether cache should be reseted or not.
    func updateConfig(_ newConfig: LearningConfig) -> Bool {
        // 更新の必要がなければ何もしない
        if !newConfig.learningType.needUsingMemory {'''

LEARNING_CACHE_INVALIDATION_REPLACEMENT = '''    /// - Returns: Whether cache should be reseted or not.
    func updateConfig(_ newConfig: LearningConfig) -> Bool {
        // Negative maxMemoryCount signals DicdataStoreState to drop cached memory LOUDS without rewriting disk files.
        if newConfig.maxMemoryCount < 0 {
            return true
        }
        // 更新の必要がなければ何もしない
        if !newConfig.learningType.needUsingMemory {'''

LEARNING_MEMORY_REPLACEMENTS = (
    (LEARNING_FORGET_ANCHOR, LEARNING_FORGET_REPLACEMENT),
    (LEARNING_RESET_ANCHOR, LEARNING_RESET_REPLACEMENT),
    (LEARNING_CACHE_INVALIDATION_ANCHOR, LEARNING_CACHE_INVALIDATION_REPLACEMENT),
)

# Zero-length elements such as `.compositionSeparator` inserted by cursor-mid
# edits map adjacent input indices to the same surface index, which left
# `sIndexPointer > sIndex` and panicked on `lower ..< upper` when the cursor
# returned across the separator.
LATTICE_SURFACE_LOOKUP_ANCHOR = '''        case .surface(let sIndex):
            if let iIndex = self.inputIndexToSurfaceIndexMap.filter({ $0.value == sIndex}).first?.key {
                .bothIndex(inputIndex: iIndex, surfaceIndex: sIndex)
            } else {
                .surfaceIndex(sIndex)
            }'''

LATTICE_SURFACE_LOOKUP_REPLACEMENT = '''        case .surface(let sIndex):
            // Zero-length separators share `sIndex`; pick the earliest input index to match `indices(...)`.
            if let iIndex = self.inputIndexToSurfaceIndexMap.filter({ $0.value == sIndex }).keys.min() {
                .bothIndex(inputIndex: iIndex, surfaceIndex: sIndex)
            } else {
                .surfaceIndex(sIndex)
            }'''

LATTICE_INDICES_ANCHOR = '''            if let sIndex = self.inputIndexToSurfaceIndexMap[i] {
                for j in min(surfaceCount, sIndexPointer) ..< min(surfaceCount, sIndex) {
                    indices.append(.surfaceIndex(j))
                }
                if sIndexPointer <= sIndex && sIndex < surfaceCount {
                    indices.append(.bothIndex(inputIndex: i, surfaceIndex: sIndex))
                } else {
                    indices.append(.inputIndex(i))
                }
                sIndexPointer = sIndex + 1'''

LATTICE_INDICES_REPLACEMENT = '''            if let sIndex = self.inputIndexToSurfaceIndexMap[i] {
                let surfaceStart = min(surfaceCount, sIndexPointer)
                let surfaceEnd = min(surfaceCount, sIndex)
                if surfaceStart < surfaceEnd {
                    for j in surfaceStart ..< surfaceEnd {
                        indices.append(.surfaceIndex(j))
                    }
                }
                if sIndexPointer <= sIndex && sIndex < surfaceCount {
                    indices.append(.bothIndex(inputIndex: i, surfaceIndex: sIndex))
                } else {
                    indices.append(.inputIndex(i))
                }
                sIndexPointer = max(sIndexPointer, sIndex + 1)'''

LATTICE_REPLACEMENTS = (
    (LATTICE_SURFACE_LOOKUP_ANCHOR, LATTICE_SURFACE_LOOKUP_REPLACEMENT),
    (LATTICE_INDICES_ANCHOR, LATTICE_INDICES_REPLACEMENT),
)


def patch_modulemap(text):
    """Link the ggml libraries the converter's llama module needs."""
    _require(text, MODULEMAP_ANCHOR)
    return text.replace(MODULEMAP_ANCHOR, MODULEMAP_REPLACEMENT)


def patch_zenz_context(text):
    """Adapt the converter to the pinned llama.cpp memory API and load mode."""
    for anchor, replacement in ZENZ_CONTEXT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    return text


ZENZ_DIGIT_MASKING_SWIFT = r'''public enum ZenzDigitMasking {
    public struct MaskedText: Sendable, Equatable {
        public let text: String
        public let asciiDigits: [String]
        public let rawDigits: [String]
    }

    public static func isDigitScalar(_ scalar: UnicodeScalar) -> Bool {
        asciiDigitScalar(scalar) != nil
    }

    public static func asciiDigitScalar(_ scalar: UnicodeScalar) -> UnicodeScalar? {
        switch scalar.value {
        case 0x0030 ... 0x0039:
            return scalar
        case 0xFF10 ... 0xFF19:
            return UnicodeScalar(scalar.value - 0xFEE0)
        default:
            return nil
        }
    }

    public static func mask(_ text: String, maxRuns: Int = .max) -> MaskedText {
        guard maxRuns > 0, text.unicodeScalars.contains(where: isDigitScalar) else {
            return MaskedText(text: text, asciiDigits: [], rawDigits: [])
        }
        var output = String.UnicodeScalarView()
        var asciiDigits: [String] = []
        var rawDigits: [String] = []
        var currentAscii = String.UnicodeScalarView()
        var currentRaw = String.UnicodeScalarView()

        @inline(__always)
        func flushRun() {
            guard !currentRaw.isEmpty else {
                return
            }
            if rawDigits.count < maxRuns {
                output.append(contentsOf: "nam".unicodeScalars)
                asciiDigits.append(String(currentAscii))
                rawDigits.append(String(currentRaw))
            } else {
                output.append(contentsOf: currentRaw)
            }
            currentAscii.removeAll(keepingCapacity: true)
            currentRaw.removeAll(keepingCapacity: true)
        }

        for scalar in text.unicodeScalars {
            if let ascii = asciiDigitScalar(scalar) {
                if rawDigits.count >= maxRuns {
                    output.append(scalar)
                } else {
                    currentAscii.append(ascii)
                    currentRaw.append(scalar)
                }
            } else {
                flushRun()
                output.append(scalar)
            }
        }
        flushRun()
        guard !rawDigits.isEmpty else {
            return MaskedText(text: text, asciiDigits: [], rawDigits: [])
        }
        return MaskedText(text: String(output), asciiDigits: asciiDigits, rawDigits: rawDigits)
    }

    @inline(__always)
    private static func isAsciiLower(_ byte: UInt8) -> Bool {
        (0x61 ... 0x7A).contains(byte)
    }

    @inline(__always)
    private static func isAsciiLowerExcludingX(_ byte: UInt8) -> Bool {
        (0x61 ... 0x77).contains(byte) || (0x79 ... 0x7A).contains(byte)
    }

    private static func findNam(in bytes: [UInt8], from start: Int) -> Int? {
        guard bytes.count >= start + 3 else {
            return nil
        }
        let limit = bytes.count - 3
        var idx = start
        while idx <= limit {
            if bytes[idx] == 0x6E && bytes[idx + 1] == 0x61 && bytes[idx + 2] == 0x6D {
                return idx
            }
            idx += 1
        }
        return nil
    }

    public static func restoreBytes(
        _ bytes: [UInt8],
        digits: [String],
        asciiDigits: [String]? = nil,
        completePartialTrailingNam: Bool = false
    ) -> [UInt8] {
        guard !digits.isEmpty else {
            return bytes
        }

        func replacePass(
            _ input: [UInt8],
            startDigitIndex: Int,
            fallback: Bool
        ) -> (bytes: [UInt8], nextDigitIndex: Int) {
            var out: [UInt8] = []
            out.reserveCapacity(input.count)
            var cursor = 0
            var digitIndex = startDigitIndex
            while digitIndex < digits.count, let pos = findNam(in: input, from: cursor) {
                out.append(contentsOf: input[cursor ..< pos])
                let afterIndex = pos + 3
                let keepLiteral: Bool
                if !fallback {
                    keepLiteral = afterIndex < input.count && isAsciiLowerExcludingX(input[afterIndex])
                } else {
                    let prevIsLower = out.last.map(isAsciiLower) ?? false
                    let startsWithE = afterIndex < input.count && input[afterIndex] == 0x65
                    let startsWithStandaloneIng =
                        afterIndex + 2 < input.count &&
                        input[afterIndex] == 0x69 &&
                        input[afterIndex + 1] == 0x6E &&
                        input[afterIndex + 2] == 0x67 &&
                        (afterIndex + 3 >= input.count || !isAsciiLower(input[afterIndex + 3]))
                    keepLiteral = prevIsLower || startsWithE || startsWithStandaloneIng
                }
                if keepLiteral {
                    out.append(contentsOf: [0x6E, 0x61, 0x6D])
                } else {
                    let prevIsAscii = out.last.map { $0 >= 0x21 && $0 <= 0x7E } ?? false
                    let nextIsAscii = afterIndex < input.count && input[afterIndex] >= 0x21 && input[afterIndex] <= 0x7E
                    let replacement: String
                    if (prevIsAscii || nextIsAscii),
                       let asciiDigits,
                       digitIndex < asciiDigits.count {
                        replacement = asciiDigits[digitIndex]
                    } else {
                        replacement = digits[digitIndex]
                    }
                    out.append(contentsOf: replacement.utf8)
                    digitIndex += 1
                }
                cursor = afterIndex
            }
            if cursor < input.count {
                out.append(contentsOf: input[cursor...])
            }
            return (out, digitIndex)
        }

        let first = findNam(in: bytes, from: 0) != nil
            ? replacePass(bytes, startDigitIndex: 0, fallback: false)
            : (bytes: bytes, nextDigitIndex: 0)
        var current = first.bytes
        var consumed = first.nextDigitIndex
        if consumed < digits.count, findNam(in: current, from: 0) != nil {
            let second = replacePass(current, startDigitIndex: consumed, fallback: true)
            current = second.bytes
            consumed = second.nextDigitIndex
        }
        if completePartialTrailingNam, consumed < digits.count {
            let suffixLen: Int?
            if current.count >= 2 && current[current.count - 2] == 0x6E && current[current.count - 1] == 0x61 {
                let prevIndex = current.count - 3
                suffixLen = (prevIndex < 0 || !isAsciiLower(current[prevIndex])) ? 2 : nil
            } else if current.count >= 1 && current[current.count - 1] == 0x6E {
                let prevIndex = current.count - 2
                suffixLen = (prevIndex < 0 || !isAsciiLower(current[prevIndex])) ? 1 : nil
            } else {
                suffixLen = nil
            }
            if let suffixLen {
                current.removeLast(suffixLen)
                let prevIsAscii = current.last.map { $0 >= 0x21 && $0 <= 0x7E } ?? false
                let replacement: String
                if prevIsAscii, let asciiDigits, consumed < asciiDigits.count {
                    replacement = asciiDigits[consumed]
                } else {
                    replacement = digits[consumed]
                }
                current.append(contentsOf: replacement.utf8)
            }
        }
        return current
    }

    public static func restore(_ text: String, digits: [String], asciiDigits: [String]? = nil) -> String {
        guard !digits.isEmpty, text.contains("nam") else {
            return text
        }
        return String(decoding: restoreBytes(Array(text.utf8), digits: digits, asciiDigits: asciiDigits), as: UTF8.self)
    }
}

'''

ZENZ_PROMPT_BUILDER_REPLACEMENTS = (
    (
        'enum ZenzPromptBuilder {\n',
        ZENZ_DIGIT_MASKING_SWIFT + 'enum ZenzPromptBuilder {\n',
    ),
    (
        '        let trimmedLeftContext = self.trimmedContext(leftSideContext, maxLength: mode.maxLeftSideContextLength)\n'
        '        let input = composingText.toKatakana()\n',
        '        let trimmedLeftContext = ZenzDigitMasking.mask(self.trimmedContext(leftSideContext, maxLength: mode.maxLeftSideContextLength)).text\n'
        '        let input = ZenzDigitMasking.mask(composingText.toKatakana()).text\n',
    ),
    (
        r'''    static func typoCorrectionPromptPrefix(leftSideContext: String) -> String {
        if leftSideContext.isEmpty {
            return inputTag
        } else {
            return contextTag + leftSideContext + inputTag
        }
    }''',
        r'''    static func typoCorrectionPromptPrefix(leftSideContext: String) -> String {
        let maskedLeftContext = ZenzDigitMasking.mask(leftSideContext).text
        if maskedLeftContext.isEmpty {
            return inputTag
        } else {
            return contextTag + maskedLeftContext + inputTag
        }
    }''',
    ),
    (
        r'''        var conditions: [String] = []
        if !userDictionaryPrompt.isEmpty {
            conditions.append("辞書:\(userDictionaryPrompt)")
        }

        let leftSideContext: String
        switch versionDependentConfig {
        case .v2(let mode):
            if let profile = mode.profile, !profile.isEmpty {
                let pf = profile.suffix(25)
                conditions.append("プロフィール:\(pf)")
            }
            leftSideContext = self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)
        case .v3(let mode):
            conditions.append(contentsOf: self.v3Conditions(mode))
            leftSideContext = self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)
        }

        switch versionDependentConfig {
        case .v2:
            if !conditions.isEmpty {
                return inputTag + input + contextTag + conditions.joined(separator: "・") + "・発言:\(leftSideContext)" + outputTag
            } else if !leftSideContext.isEmpty {
                return inputTag + input + contextTag + leftSideContext + outputTag
            } else {
                return inputTag + input + outputTag
            }
        case .v3:
            if !leftSideContext.isEmpty {
                return conditions.joined(separator: "") + contextTag + leftSideContext + inputTag + input + outputTag
            } else {
                return conditions.joined(separator: "") + inputTag + input + outputTag
            }
        }''',
        r'''        let maskedUserDictionaryPrompt = ZenzDigitMasking.mask(userDictionaryPrompt).text
        let maskedInput = ZenzDigitMasking.mask(input).text
        var conditions: [String] = []
        if !maskedUserDictionaryPrompt.isEmpty {
            conditions.append("辞書:\(maskedUserDictionaryPrompt)")
        }

        let leftSideContext: String
        switch versionDependentConfig {
        case .v2(let mode):
            if let profile = mode.profile, !profile.isEmpty {
                let pf = profile.suffix(25)
                conditions.append("プロフィール:\(pf)")
            }
            leftSideContext = ZenzDigitMasking.mask(self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)).text
        case .v3(let mode):
            conditions.append(contentsOf: self.v3Conditions(mode))
            leftSideContext = ZenzDigitMasking.mask(self.trimmedModeContext(mode.leftSideContext, maxLength: mode.maxLeftSideContextLength)).text
        }

        switch versionDependentConfig {
        case .v2:
            if !conditions.isEmpty {
                return inputTag + maskedInput + contextTag + conditions.joined(separator: "・") + "・発言:\(leftSideContext)" + outputTag
            } else if !leftSideContext.isEmpty {
                return inputTag + maskedInput + contextTag + leftSideContext + outputTag
            } else {
                return inputTag + maskedInput + outputTag
            }
        case .v3:
            if !leftSideContext.isEmpty {
                return conditions.joined(separator: "") + contextTag + leftSideContext + inputTag + maskedInput + outputTag
            } else {
                return conditions.joined(separator: "") + inputTag + maskedInput + outputTag
            }
        }''',
    ),
)


def patch_zenz_prompt_builder(text):
    """Mask ASCII and fullwidth digits with `nam` when formatting Zenz prompts."""
    for anchor, replacement in ZENZ_PROMPT_BUILDER_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


def patch_learning_memory(text):
    """Allow explicit per-word deletion in read-only mode and refresh collapse state."""
    for anchor, replacement in LEARNING_MEMORY_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    _require(text, LEARNING_FLUSH_MANAGER_ANCHOR)
    _require(text, LEARNING_FLUSH_MANAGER_BODY)
    text = text.replace(
        LEARNING_FLUSH_MANAGER_ANCHOR,
        LEARNING_FLUSH_MANAGER_ANCHOR.replace(
            '    func save() {',
            '    var hasTemporaryEntries: Bool {\n'
            '        !self.temporaryMemory.dicdata.isEmpty\n'
            '    }\n\n'
            '    func save() -> Bool {',
        ),
        1,
    )
    text = text.replace(LEARNING_FLUSH_MANAGER_BODY, LEARNING_FLUSH_MANAGER_REPLACEMENT, 1)
    return text


def patch_lattice(text):
    """Keep LatticeDualIndexMap safe and deterministic across composition separators."""
    for anchor, replacement in LATTICE_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    return text


DYNAMIC_DICTIONARY_BUILDER_PATH = 'Sources/KanaKanjiConverterModule/DictionaryManagement/DataStructure/DictionaryBuilder.swift'
DYNAMIC_DICTIONARY_STATE_PATH = 'Sources/KanaKanjiConverterModule/DictionaryManagement/DicdataStoreState.swift'

DYNAMIC_DICTIONARY_BUILDER_ANCHOR = """        struct Node {
            var children: OrderedDictionary<UInt8, Node> = [:]
        }

        var root = Node()
        func keyToChars"""
DYNAMIC_DICTIONARY_BUILDER_LOOP = """        for e in entries {
            guard let chars = keyToChars(e.ruby) else {
                continue
            }"""

def patch_dynamic_dictionary_builder(text):
    _require(text, DYNAMIC_DICTIONARY_BUILDER_ANCHOR)
    _require(text, DYNAMIC_DICTIONARY_BUILDER_LOOP)
    text = text.replace(DYNAMIC_DICTIONARY_BUILDER_ANCHOR, '        func keyToChars', 1)
    return text.replace(DYNAMIC_DICTIONARY_BUILDER_LOOP, """        return buildLOUDS(keys: entries.compactMap { keyToChars($0.ruby) })
    }

    static func buildLOUDS(keys: [[UInt8]]) -> (bits: [Bool], nodes2Characters: [UInt8]) {
        struct Node {
            var children: OrderedDictionary<UInt8, Node> = [:]
        }

        var root = Node()
        for chars in keys {""", 1)


DYNAMIC_DICTIONARY_PREPARED = """/// Immutable snapshot prepared outside the conversion executor.
public struct PreparedDynamicUserDictionary: Sendable {
    let snapshotID = UUID()
    private let trie: LOUDS?
    private let entriesByNode: [Int: [DicdataElement]]
    private let sortedEntries: [DicdataElement]
    let shortcutsByReading: [String: [DicdataElement]]
    private let protectedReadings: Set<String>
    private let maxProtectedReadingLength: Int

    public init(_ entries: [DicdataElement], shortcuts: [DicdataElement] = []) {
        let converted = entries.map { source in
            var element = source
            element.ruby = source.ruby.precomposedStringWithCanonicalMapping
            element.metadata.insert(.isFromUserDictionary)
            return element
        }
        let grouped = Dictionary(grouping: converted, by: \\.ruby)
        if grouped.isEmpty {
            trie = nil
            entriesByNode = [:]
        } else {
            let bytes = grouped.keys.sorted().map { Array($0.utf8) }
            let built = DictionaryBuilder.buildLOUDS(keys: bytes)
            let louds = LOUDS(bytes: DictionaryBuilder.makeLOUDSWords(bits: built.bits), nodeIndex2ID: built.nodes2Characters)
            trie = louds
            entriesByNode = Dictionary(uniqueKeysWithValues: grouped.compactMap { reading, entries in
                louds.searchNodeIndex(chars: Array(reading.utf8)).map { ($0, entries) }
            })
        }
        let protectedConversion = converted.filter { !$0.metadata.contains(.isKeyboardTypoCorrection) }
        sortedEntries = protectedConversion
            .sorted { $0.ruby < $1.ruby }
        let normalizedShortcuts = shortcuts.map { source in
            var element = source
            element.ruby = source.ruby.precomposedStringWithCanonicalMapping
            element.metadata.insert(.isFromUserDictionary)
            return element
        }
        shortcutsByReading = Dictionary(grouping: normalizedShortcuts, by: \\.ruby)
        let allProtected = protectedConversion.map(\\.ruby) + normalizedShortcuts.map(\\.ruby)
        protectedReadings = Set(allProtected)
        maxProtectedReadingLength = allProtected.reduce(0) { max($0, $1.count) }
    }

    var hasEntries: Bool { trie != nil }

    func hasMatchInKatakana(_ katakana: String) -> Bool {
        guard !self.protectedReadings.isEmpty else {
            return false
        }
        let chars = Array(katakana.precomposedStringWithCanonicalMapping)
        let n = chars.count
        guard n > 0, self.maxProtectedReadingLength > 0 else {
            return false
        }
        let maxLen = min(n, self.maxProtectedReadingLength)
        for start in 0 ..< n {
            let endLimit = min(n, start + maxLen)
            for end in (start + 1) ... endLimit {
                if self.protectedReadings.contains(String(chars[start ..< end])) {
                    return true
                }
            }
        }
        return false
    }

    func exact(_ reading: String) -> [DicdataElement] {
        guard let trie, let index = trie.searchNodeIndex(chars: Array(reading.precomposedStringWithCanonicalMapping.utf8)) else {
            return []
        }
        return entriesByNode[index] ?? []
    }

    func prefix(_ reading: String) -> [DicdataElement] {
        let key = reading.precomposedStringWithCanonicalMapping
        var lower = 0
        var upper = sortedEntries.count
        while lower < upper {
            let mid = (lower + upper) / 2
            if sortedEntries[mid].ruby < key { lower = mid + 1 } else { upper = mid }
        }
        return Array(sortedEntries[lower...].prefix(64).prefix(while: { $0.ruby.hasPrefix(key) }))
    }
}
"""

LEARNING_FLUSH_MANAGER_ANCHOR = """    func save() {
        guard self.config.learningType.needUpdateMemory,
              let memoryURL = config.memoryURL else {"""
LEARNING_FLUSH_MANAGER_BODY = """            debug(#function, "config.learningType=\\(self.config.learningType as _?)", "skip memory update")
            return
        }
        do {
            try LongTermLearningMemory.merge(tempTrie: self.temporaryMemory, directoryURL: memoryURL, maxMemoryCount: self.config.maxMemoryCount, char2UInt8: char2UInt8)
            // マージが済んだので、temporaryMemoryを空にする
            self.temporaryMemory = TemporalLearningMemoryTrie()
        } catch {
            // アップデートに失敗した場合、そのまま諦める。
            debug("LearningManager save: Failed to save LongTermLearningMemory", error)
        }
        // 状態を更新する
        self.memoryCollapsed = LongTermLearningMemory.memoryCollapsed(directoryURL: memoryURL)
    }"""
LEARNING_FLUSH_MANAGER_REPLACEMENT = """            return false
        }
        defer {
            self.memoryCollapsed = LongTermLearningMemory.memoryCollapsed(directoryURL: memoryURL)
        }
        do {
            try LongTermLearningMemory.merge(tempTrie: self.temporaryMemory, directoryURL: memoryURL, maxMemoryCount: self.config.maxMemoryCount, char2UInt8: char2UInt8)
            self.temporaryMemory = TemporalLearningMemoryTrie()
            return true
        } catch {
            debug("LearningManager save: Failed to save LongTermLearningMemory", error)
            return false
        }
    }"""
LEARNING_FLUSH_STATE_ANCHOR = """    func saveMemory() {
        self.learningMemoryManager.save()
        self.resetMemoryLOUDSCache()
    }"""
LEARNING_FLUSH_STATE_REPLACEMENT = """    @discardableResult
    func saveMemory() -> Bool {
        let saved = self.learningMemoryManager.save()
        self.resetMemoryLOUDSCache()
        return saved
    }"""
LEARNING_FLUSH_API_ANCHOR = """    public func commitUpdateLearningData() {
        self.dicdataStoreState.saveMemory()
    }"""
LEARNING_FLUSH_API_REPLACEMENT = LEARNING_FLUSH_API_ANCHOR.replace(
    "self.dicdataStoreState.saveMemory()", "_ = self.dicdataStoreState.saveMemory()"
) + """

    public func flushPendingLearningData() -> Bool {
        self.dicdataStoreState.saveMemory()
    }"""

DYNAMIC_DICTIONARY_STATE_FIELDS = """    private(set) var dynamicUserDictionary: [DicdataElement] = []
    private(set) var dynamicUserShortcuts: [DicdataElement] = []"""

DYNAMIC_DICTIONARY_STATE_IMPORT = """    func importDynamicUserDictionary(_ dicdata: [DicdataElement], shortcuts: [DicdataElement] = []) {
        self.dynamicUserDictionary = dicdata
        self.dynamicUserDictionary.mutatingForEach {
            $0.metadata = $0.metadata.contains(.isKeyboardTypoCorrection)
                ? [.isFromUserDictionary, .isKeyboardTypoCorrection]
                : .isFromUserDictionary
        }
        self.dynamicUserShortcuts = shortcuts
        self.dynamicUserShortcuts.mutatingForEach {
            $0.metadata = .isFromUserDictionary
        }
    }"""

def patch_dynamic_dictionary_state(text):
    _require(text, DYNAMIC_DICTIONARY_STATE_FIELDS)
    _require(text, DYNAMIC_DICTIONARY_STATE_IMPORT)
    text = text.replace(DYNAMIC_DICTIONARY_STATE_FIELDS,
        '    var dynamicUserDictionary = PreparedDynamicUserDictionary([])', 1)
    text = text.replace(DYNAMIC_DICTIONARY_STATE_IMPORT, """    func importDynamicUserDictionary(_ dicdata: [DicdataElement], shortcuts: [DicdataElement] = []) {
        self.dynamicUserDictionary = PreparedDynamicUserDictionary(dicdata, shortcuts: shortcuts)
    }""", 1)
    _require(text, LEARNING_FLUSH_STATE_ANCHOR)
    text = text.replace(LEARNING_FLUSH_STATE_ANCHOR, LEARNING_FLUSH_STATE_REPLACEMENT, 1)
    return text + "\n" + DYNAMIC_DICTIONARY_PREPARED


DYNAMIC_DICTIONARY_API_ANCHOR = '    public func updateUserDictionaryURL('
DYNAMIC_DICTIONARY_API = """    public func installPreparedDynamicUserDictionary(_ dictionary: PreparedDynamicUserDictionary) {
        self.dicdataStoreState.dynamicUserDictionary = dictionary
        self.stopComposition()
    }

"""

DYNAMIC_USER_DICTIONARY_MID_LOOKUP = (
    "        return self.mmValue[former * self.midCount + latter]",
    """        // User presets can use a CID as their MID. Keep the stored ID,
        // but map IDs outside the meaning table to the generic class.
        let left = (0..<self.midCount).contains(former) ? former : MIDData.一般.mid
        let right = (0..<self.midCount).contains(latter) ? latter : MIDData.一般.mid
        let index = left * self.midCount + right
        return self.mmValue.indices.contains(index) ? self.mmValue[index] : 0""",
)

DYNAMIC_DICTIONARY_LOOKUPS = (
    DYNAMIC_USER_DICTIONARY_MID_LOOKUP,
    ('state.dynamicUserShortcuts.filter { $0.ruby == rubyString }',
     'state.dynamicUserDictionary.shortcutsByReading[rubyString] ?? []'),
    ('!state.dynamicUserDictionary.isEmpty', 'state.dynamicUserDictionary.hasEntries'),
    ('state.dynamicUserDictionary.filter {$0.ruby == ruby}', 'state.dynamicUserDictionary.exact(String(ruby))'),
    ("""state.dynamicUserDictionary.filter {
            $0.ruby.hasPrefix(ruby) && !$0.metadata.contains(.isKeyboardTypoCorrection)
        }""", 'state.dynamicUserDictionary.prefix(String(ruby))'),
)

DYNAMIC_DICTIONARY_RESULT_ANCHOR = '        if case .完全一致 = options.requestQuery {\n            self.invalidateStablePredictionCandidateCache()'
DYNAMIC_DICTIONARY_RESULT_CANDIDATES = """        // Keep exact registrations selectable even when lattice/semantic rank
        // cuts their paths before the normal N-best result list.
        let registeredExactCandidates = self.dicdataStoreState.dynamicUserDictionary
            .exact(inputData.convertTarget.toKatakana()).lazy
            .filter { !$0.metadata.contains(.isKeyboardTypoCorrection) }
            .prefix(max(1, min(options.N_best, 32)))
            .map { entry in
                Candidate(
                    text: entry.word, value: entry.value(),
                    composingCount: .surfaceCount(inputData.convertTarget.count),
                    lastMid: entry.mid, data: [entry]
                )
            }
        let exactRegistrations = userShortcutsCandidates + registeredExactCandidates

"""
DYNAMIC_DICTIONARY_RESULT_REPLACEMENTS = (
    (DYNAMIC_DICTIONARY_RESULT_ANCHOR, DYNAMIC_DICTIONARY_RESULT_CANDIDATES + DYNAMIC_DICTIONARY_RESULT_ANCHOR),
    ("self.getUniqueCandidate(wholeSentenceUniqueCandidates.chained(userShortcutsCandidates))",
     "self.getUniqueCandidate(wholeSentenceUniqueCandidates.chained(exactRegistrations))"),
    ("        var seenCandidate: Set<String> = fullCandidates.mapSet {$0.text}",
     """        var seenCandidate: Set<String> = fullCandidates.mapSet {$0.text}
        seenCandidate.formUnion(exactRegistrations.map(\\.text))"""),
    ("        result.append(contentsOf: consume firstClauseCandidates)",
     """        result.append(contentsOf: self.getUniqueCandidate(exactRegistrations, seenCandidates: result.mapSet { $0.text }))
        result.append(contentsOf: consume firstClauseCandidates)"""),
)

REUSABLE_LATTICE_CONVERTER_API_REPLACEMENTS = (
    (
        '''/// かな漢字変換の管理を受け持つクラス
public final class KanaKanjiConverter {''',
        '''public struct TokenLogitMargin: Sendable, Equatable, Hashable {
    public let byteEnd: Int
    public let marginMilli: Int32

    public init(byteEnd: Int, marginMilli: Int32) {
        self.byteEnd = byteEnd
        self.marginMilli = marginMilli
    }
}

public enum DirectZenzaiGenerationResult: Sendable {
    case text(String, tokenMargins: [TokenLogitMargin] = [])
    case needsFallback
    case decodeAborted
}

extension ConvertRequestOptions {
    public func disablingZenzaiRichCandidates() -> ConvertRequestOptions {
        var copy = self
        if copy.zenzaiMode.enabled {
            copy.zenzaiMode.requestRichCandidates = false
        }
        return copy
    }
}

public final class ReusableFullInputLattice {
    let inputData: ComposingText
    let experimentalKeyboardTypoCorrection: Bool?
    let keyboardLanguage: KeyboardLanguage
    let userDictionarySnapshotID: UUID
    let userDictionaryURL: URL?
    let learningEnabled: Bool
    let memoryURL: URL?
    let seed: FullInputLatticeSeed
    let unconstrainedResult: LatticeNode?

    init(
        inputData: ComposingText,
        experimentalKeyboardTypoCorrection: Bool?,
        keyboardLanguage: KeyboardLanguage,
        userDictionarySnapshotID: UUID,
        userDictionaryURL: URL?,
        learningEnabled: Bool,
        memoryURL: URL?,
        seed: FullInputLatticeSeed,
        unconstrainedResult: LatticeNode?
    ) {
        self.inputData = inputData
        self.experimentalKeyboardTypoCorrection = experimentalKeyboardTypoCorrection
        self.keyboardLanguage = keyboardLanguage
        self.userDictionarySnapshotID = userDictionarySnapshotID
        self.userDictionaryURL = userDictionaryURL
        self.learningEnabled = learningEnabled
        self.memoryURL = memoryURL
        self.seed = seed
        self.unconstrainedResult = unconstrainedResult
    }
}

/// かな漢字変換の管理を受け持つクラス
public final class KanaKanjiConverter {''',
    ),
    (
        '''        var stablePredictionCandidateCache: StablePredictionCandidateCacheEntry?
        var experimentalKeyboardTypoCorrection: Bool?
    }''',
        '''        var stablePredictionCandidateCache: StablePredictionCandidateCacheEntry?
        var experimentalKeyboardTypoCorrection: Bool?
        var latticeBuiltAfterComplete: Bool = false
        var pendingReusableLattice: ReusableFullInputLattice?
        var lastUnconstrainedResult: LatticeNode?
    }''',
    ),
    (
        '''        // FIXME: enable cache based zenzai
        if zenzaiMode.enabled, let model = self.getModel(modelURL: zenzaiMode.weightURL) {
            let (result, nodes, cache) = self.converter.all_zenzai(
                inputData,
                zenz: model,
                zenzaiCache: self.currentSessionState.zenzaiCache,
                inferenceLimit: zenzaiMode.inferenceLimit,
                requestRichCandidates: zenzaiMode.requestRichCandidates,
                personalizationMode: self.getZenzaiPersonalization(mode: zenzaiMode.personalizationMode),
                versionDependentConfig: zenzaiMode.versionDependentMode,
                dicdataStoreState: self.dicdataStoreState
            )
            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.zenzaiCache = cache
            }
            return (result, nodes)
        }''',
        '''        let pendingReusableLattice = self.currentSessionState.pendingReusableLattice
        if pendingReusableLattice != nil {
            self.updateCurrentSessionState {
                $0.pendingReusableLattice = nil
            }
        }

        // FIXME: enable cache based zenzai
        if zenzaiMode.enabled, let model = self.getModel(modelURL: zenzaiMode.weightURL) {
            let reusablePair: (seed: FullInputLatticeSeed, unconstrainedResult: LatticeNode?)? = if let pendingReusableLattice,
                !needTypoCorrection,
                pendingReusableLattice.inputData == inputData,
                pendingReusableLattice.experimentalKeyboardTypoCorrection == self.currentSessionState.experimentalKeyboardTypoCorrection,
                pendingReusableLattice.keyboardLanguage == self.dicdataStoreState.keyboardLanguage,
                pendingReusableLattice.userDictionarySnapshotID == self.dicdataStoreState.dynamicUserDictionary.snapshotID,
                pendingReusableLattice.userDictionaryURL == self.dicdataStoreState.userDictionaryURL,
                pendingReusableLattice.learningEnabled == self.dicdataStoreState.learningMemoryManager.enabled,
                pendingReusableLattice.memoryURL == self.dicdataStoreState.memoryURL,
                !self.dicdataStoreState.learningMemoryManager.hasTemporaryEntries {
                (pendingReusableLattice.seed, pendingReusableLattice.unconstrainedResult)
            } else {
                nil
            }
            let (result, nodes, cache) = self.converter.all_zenzai(
                inputData,
                zenz: model,
                zenzaiCache: self.currentSessionState.zenzaiCache,
                inferenceLimit: zenzaiMode.inferenceLimit,
                requestRichCandidates: zenzaiMode.requestRichCandidates,
                personalizationMode: self.getZenzaiPersonalization(mode: zenzaiMode.personalizationMode),
                versionDependentConfig: zenzaiMode.versionDependentMode,
                dicdataStoreState: self.dicdataStoreState,
                reusableLatticeSeed: reusablePair?.seed,
                reusableUnconstrainedResult: reusablePair?.unconstrainedResult
            )
            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.zenzaiCache = cache
                $0.latticeBuiltAfterComplete = false
                $0.lastUnconstrainedResult = nil
            }
            return (result, nodes)
        }''',
    ),
    (
        '''            self.updateCurrentSessionState {
                $0.previousInputData = inputData
            }
            return result
        }

        debug("\\(#function): before \\(previousInputData) after \\(inputData)")''',
        '''            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.latticeBuiltAfterComplete = false
                $0.lastUnconstrainedResult = needTypoCorrection ? nil : result.result
            }
            return result
        }

        debug("\\(#function): before \\(previousInputData) after \\(inputData)")''',
    ),
    (
        '''        // 完全一致の場合
        if previousInputData == inputData {
            let result = converter.kana2lattice_no_change(N_best: N_best, previousResult: (inputData: previousInputData, lattice: self.currentSessionState.lattice))
            self.updateCurrentSessionState {
                $0.previousInputData = inputData
            }
            return result
        }''',
        '''        // 完全一致の場合
        if previousInputData == inputData {
            let result = converter.kana2lattice_no_change(N_best: N_best, previousResult: (inputData: previousInputData, lattice: self.currentSessionState.lattice))
            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.latticeBuiltAfterComplete = false
                $0.lastUnconstrainedResult = needTypoCorrection ? nil : result.result
            }
            return result
        }''',
    ),
    (
        '''            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.completedData = nil
            }
            return result
        }''',
        '''            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.completedData = nil
                $0.latticeBuiltAfterComplete = true
                $0.lastUnconstrainedResult = nil
            }
            return result
        }''',
    ),
    (
        '''        let result = converter.kana2lattice_changed(
            inputData,
            N_best: N_best,
            counts: diff,
            previousResult: (inputData: previousInputData, lattice: self.currentSessionState.lattice),
            needTypoCorrection: needTypoCorrection,
            dicdataStoreState: self.dicdataStoreState
        )
        self.updateCurrentSessionState {
            $0.previousInputData = inputData
        }

        return result''',
        '''        let result = converter.kana2lattice_changed(
            inputData,
            N_best: N_best,
            counts: diff,
            previousResult: (inputData: previousInputData, lattice: self.currentSessionState.lattice),
            needTypoCorrection: needTypoCorrection,
            dicdataStoreState: self.dicdataStoreState
        )
        self.updateCurrentSessionState {
            $0.previousInputData = inputData
            $0.latticeBuiltAfterComplete = false
            $0.lastUnconstrainedResult = needTypoCorrection ? nil : result.result
        }

        return result''',
    ),
)

REUSABLE_LATTICE_PUBLIC_API = '''    public func exportReusableFullInputLattice(for inputData: ComposingText) -> ReusableFullInputLattice? {
        let state = self.currentSessionState
        guard !state.latticeBuiltAfterComplete,
              state.previousInputData == inputData,
              !state.lattice.isEmpty,
              !self.dicdataStoreState.learningMemoryManager.hasTemporaryEntries else {
            return nil
        }
        return ReusableFullInputLattice(
            inputData: inputData,
            experimentalKeyboardTypoCorrection: state.experimentalKeyboardTypoCorrection,
            keyboardLanguage: self.dicdataStoreState.keyboardLanguage,
            userDictionarySnapshotID: self.dicdataStoreState.dynamicUserDictionary.snapshotID,
            userDictionaryURL: self.dicdataStoreState.userDictionaryURL,
            learningEnabled: self.dicdataStoreState.learningMemoryManager.enabled,
            memoryURL: self.dicdataStoreState.memoryURL,
            seed: FullInputLatticeSeed(inputData: inputData, lattice: state.lattice),
            unconstrainedResult: state.lastUnconstrainedResult
        )
    }

    public func adoptReusableFullInputLattice(_ reusable: ReusableFullInputLattice?) {
        self.updateCurrentSessionState {
            $0.pendingReusableLattice = reusable
        }
    }

    public func prepareDirectZenzaiGeneration(
        for inputData: ComposingText,
        options: ConvertRequestOptions
    ) -> (eligible: Bool, isGpuOffloaded: Bool) {
        if case .完全一致 = options.requestQuery {
            return (false, false)
        }
        guard !options.shouldResetMemory,
              options.zenzaiMode.enabled,
              options.zenzaiMode.personalizationMode == nil,
              !self.isClassicTypoCorrectionEnabled(options),
              (1...128).contains(inputData.convertTarget.count),
              let zenz = self.getModel(modelURL: options.zenzaiMode.weightURL),
              zenz.prefersDirectModelFirst else {
            return (false, false)
        }
        if let previous = self.currentSessionState.experimentalKeyboardTypoCorrection,
           previous != options.experimentalKeyboardTypoCorrection {
            self.stopComposition()
        }
        self.updateCurrentSessionState {
            $0.experimentalKeyboardTypoCorrection = options.experimentalKeyboardTypoCorrection
        }
        self.dicdataStoreState.updateIfRequired(options: options)
        let katakana = inputData.convertTarget.toKatakana()
        guard self.dicdataStoreState.userDictionaryLOUDS == nil,
              self.dicdataStoreState.userShortcutsLOUDS == nil,
              !(options.learningType != .nothing && self.dicdataStoreState.learningMemoryManager.hasTemporaryEntries),
              !self.dicdataStoreState.dynamicUserDictionary.hasMatchInKatakana(katakana) else {
            return (false, false)
        }
        return (true, zenz.isGpuOffloaded)
    }

    public func runDirectZenzaiGenerationOnly(
        _ inputData: ComposingText,
        options: ConvertRequestOptions
    ) -> DirectZenzaiGenerationResult {
        guard options.zenzaiMode.enabled,
              let zenz = self.zenz,
              zenz.resourceURL == options.zenzaiMode.weightURL else {
            return .needsFallback
        }
        return zenz.generateDirectCandidate(
            convertTarget: inputData.convertTarget,
            versionDependentConfig: options.zenzaiMode.versionDependentMode
        )
    }

    public func finalizeDirectZenzaiResult(
        _ outcome: DirectZenzaiGenerationResult,
        inputData: ComposingText,
        options: ConvertRequestOptions,
        normalNBestResults: [Candidate],
        reusableLattice: ReusableFullInputLattice?
    ) -> ConversionResult? {
        switch outcome {
        case .decodeAborted:
            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.zenzaiCache = nil
                $0.latticeBuiltAfterComplete = false
                $0.lastUnconstrainedResult = nil
                $0.pendingReusableLattice = nil
            }
            return ConversionResult(mainResults: [], predictionResults: [], englishPredictionResults: [], firstClauseResults: [])
        case .needsFallback:
            return nil
        case .text(let generatedText, _):
            guard !generatedText.isEmpty,
                  self.converter.heuristicRetryValidation(generatedText) else {
                return nil
            }
            let hasProtectedUserDictConflict = normalNBestResults.contains { candidate in
                candidate.data.contains { element in
                    ZenzUserDictionaryProtection.isProtectedUserDictionaryElement(element)
                        && !element.word.isEmpty
                        && !generatedText.contains(element.word)
                }
            }
            if hasProtectedUserDictConflict {
                return nil
            }
            if options.learningType.needUsingMemory {
                let hasLearnedConflict = normalNBestResults.prefix(3).contains { candidate in
                    candidate.text != generatedText
                        && candidate.data.contains { $0.metadata.contains(.isLearned) }
                }
                if hasLearnedConflict {
                    return nil
                }
            }

            let katakanaCount = inputData.convertTarget.count
            let exactCandidate = normalNBestResults.first { candidate in
                candidate.text == generatedText && candidate.rubyCount == katakanaCount
            }

            let chosenCandidate: Candidate
            var firstClauseResults: [Candidate] = []
            if let exactCandidate {
                chosenCandidate = exactCandidate
                if !exactCandidate.data.isEmpty {
                    let prefixClause = Candidate.makePrefixClauseCandidate(data: exactCandidate.data)
                    if !prefixClause.text.isEmpty {
                        var clause = prefixClause
                        clause.withActions(self.getAppropriateActions(clause))
                        clause.parseTemplate()
                        firstClauseResults = [clause]
                    }
                }
                if firstClauseResults.isEmpty {
                    firstClauseResults = [exactCandidate]
                }
            } else {
                let materializedLattice = reusableLattice?.seed.makeLattice() ?? Lattice()
                let baseNode = reusableLattice?.unconstrainedResult?.prevs.first ?? RegisteredNode.BOSNode()
                let baseCandidate = normalNBestResults.first ?? Candidate(
                    text: generatedText,
                    value: -5,
                    composingCount: .surfaceCount(katakanaCount),
                    lastMid: MIDData.一般.mid,
                    data: []
                )
                let materialized = self.converter.materializeWholeResultCandidate(
                    wholeText: generatedText,
                    inputData: inputData,
                    baseCandidate: baseCandidate,
                    baseNode: baseNode,
                    lattice: materializedLattice,
                    additionalNodes: reusableLattice?.unconstrainedResult?.prevs ?? []
                )
                var built = materialized.1
                built.withActions(self.getAppropriateActions(built))
                built.parseTemplate()
                chosenCandidate = built

                let candidateData = materialized.0.getCandidateData()
                if let first = candidateData.clauses.first {
                    let count = max(0, first.clause.dataEndIndex)
                    let dataSlice = count < candidateData.data.count
                        ? Array(candidateData.data[0...count])
                        : candidateData.data
                    var firstClause = Candidate(
                        text: first.clause.text,
                        value: first.value,
                        composingCount: first.clause.ranges.reduce(into: .inputCount(0)) { $0 = .composite($0, $1.count) },
                        lastMid: first.clause.mid,
                        data: dataSlice
                    )
                    firstClause.withActions(self.getAppropriateActions(firstClause))
                    firstClause.parseTemplate()
                    firstClauseResults = [firstClause]
                }
                if firstClauseResults.isEmpty {
                    firstClauseResults = [chosenCandidate]
                }
            }

            self.updateCurrentSessionState {
                $0.previousInputData = inputData
                $0.zenzaiCache = nil
                $0.latticeBuiltAfterComplete = false
                $0.lastUnconstrainedResult = nil
                $0.pendingReusableLattice = nil
            }
            return ConversionResult(
                mainResults: [chosenCandidate],
                predictionResults: [],
                englishPredictionResults: [],
                firstClauseResults: firstClauseResults
            )
        }
    }

'''

def patch_converter_api(text):
    """Extend KanaKanjiConverter API with session-scoped prediction, cache sync, and exact lookup."""
    _require(text, EXACT_KEYBOARD_LOOKUP_ANCHOR)
    text = text.replace(EXACT_KEYBOARD_LOOKUP_ANCHOR, REUSABLE_LATTICE_PUBLIC_API + EXACT_KEYBOARD_LOOKUP + EXACT_KEYBOARD_LOOKUP_ANCHOR, 1)
    for anchor, replacement in CONVERTER_API_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    for anchor, replacement in REUSABLE_LATTICE_CONVERTER_API_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    _require(text, DYNAMIC_DICTIONARY_API_ANCHOR)
    text = text.replace(DYNAMIC_DICTIONARY_API_ANCHOR, DYNAMIC_DICTIONARY_API + DYNAMIC_DICTIONARY_API_ANCHOR, 1)
    _require(text, LEARNING_FLUSH_API_ANCHOR)
    text = text.replace(LEARNING_FLUSH_API_ANCHOR, LEARNING_FLUSH_API_REPLACEMENT, 1)
    for anchor, replacement in DYNAMIC_DICTIONARY_RESULT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


ZENZ_EVALUATOR_USER_DICT_PROMPT_ANCHOR = r'''        var userDictionaryPrompt = ""
        for item in candidate.data where item.metadata.contains(.isFromUserDictionary) {
            userDictionaryPrompt += "\(item.word)(\(item.ruby.toHiragana()))"
        }'''

ZENZ_EVALUATOR_DECLARATION_ANCHOR = 'struct ZenzCandidateEvaluator {'

ZENZ_USER_DICTIONARY_PROTECTION = '''public enum ZenzUserDictionaryProtection {
    public static func isProtectedUserDictionaryElement(_ element: DicdataElement) -> Bool {
        element.metadata.contains(.isFromUserDictionary) && !element.metadata.contains(.isKeyboardTypoCorrection)
    }

    public static func endsWithPrintableAscii(_ word: String) -> Bool {
        word.unicodeScalars.last.map { $0.value >= 0x21 && $0.value <= 0x7E } ?? false
    }

    public static func isHiraganaLeadingBytes(_ bytes: [UInt8]) -> Bool {
        String(decoding: bytes, as: UTF8.self).unicodeScalars.first.map { $0.value >= 0x3041 && $0.value <= 0x3096 } ?? false
    }

    public static let asciiTrailingParticleMaxLogitGap: Float = 4.5

    public static func shouldAllowMismatch(
        isUserDict: Bool,
        precedingUserDictEndedAscii: Bool,
        rawLogitGap: Float,
        tokenBytes: [UInt8]
    ) -> Bool {
        isUserDict || (precedingUserDictEndedAscii && rawLogitGap <= Self.asciiTrailingParticleMaxLogitGap
            && Self.isHiraganaLeadingBytes(tokenBytes))
    }
}

'''

ZENZ_EVALUATOR_SETUP_ANCHOR = r'''        let candidateTokens = context.encode(candidate.text, addBOS: false, addEOS: false)
        let addressedTokens: [llama_token]
        if prevPrompt == promptTokens, !requestRichCandidates {
            var prefix = ""
            for character in candidate.text {
                let newPrefix = prefix + String(character)
                if prefixConstraint.constraint.hasPrefix(newPrefix.utf8) {
                    prefix = newPrefix
                } else {
                    break
                }
            }
            addressedTokens = context.encode(prefix, addBOS: false, addEOS: false)
        } else {
            addressedTokens = []
        }

        let tokens = promptTokens + candidateTokens
        let startOffset = promptTokens.count - 1 + addressedTokens.count
        guard let logits = context.evaluationLogits(tokens: tokens, startOffset: startOffset) else {
            debug("logits unavailable")
            return .error
        }
        let n_vocab = Int(context.vocabSize)
        let isLearnedToken: [(isLearned: Bool, priority: Float)] = Array(repeating: (false, 0), count: promptTokens.count) + candidate.data.flatMap {
            Array(repeating: ($0.metadata.contains(.isLearned), logf(self.learningPriority(data: $0))), count: context.encode($0.word, addBOS: false).count)
        }'''

ZENZ_EVALUATOR_SETUP_REPLACEMENT = r'''        let inputMask = ZenzDigitMasking.mask(input)
        let hasInputDigits = !inputMask.rawDigits.isEmpty
        let candidateMask = hasInputDigits ? ZenzDigitMasking.mask(candidate.text, maxRuns: inputMask.rawDigits.count) : nil
        let evaluationCandidateText = candidateMask?.text ?? candidate.text
        let evaluationDigits: [String]
        let evaluationAsciiDigits: [String]
        if let candidateMask {
            if candidateMask.rawDigits.count < inputMask.rawDigits.count {
                evaluationDigits = candidateMask.rawDigits + Array(inputMask.rawDigits.dropFirst(candidateMask.rawDigits.count))
                evaluationAsciiDigits = candidateMask.asciiDigits + Array(inputMask.asciiDigits.dropFirst(candidateMask.asciiDigits.count))
            } else {
                evaluationDigits = candidateMask.rawDigits
                evaluationAsciiDigits = candidateMask.asciiDigits
            }
        } else {
            evaluationDigits = []
            evaluationAsciiDigits = []
        }

        let candidateTokens = context.encode(evaluationCandidateText, addBOS: false, addEOS: false)
        let addressedTokens: [llama_token]
        if prevPrompt == promptTokens, !prefixConstraint.constraint.isEmpty {
            var prefix = ""
            var nextIndex = candidate.text.startIndex
            for character in candidate.text {
                let newPrefix = prefix + String(character)
                if prefixConstraint.constraint.hasPrefix(newPrefix.utf8) {
                    prefix = newPrefix
                    nextIndex = candidate.text.index(after: nextIndex)
                } else {
                    break
                }
            }
            if hasInputDigits, nextIndex < candidate.text.endIndex,
               let nextScalar = candidate.text[nextIndex...].unicodeScalars.first,
               ZenzDigitMasking.isDigitScalar(nextScalar) {
                while let last = prefix.unicodeScalars.last, ZenzDigitMasking.isDigitScalar(last) {
                    prefix.unicodeScalars.removeLast()
                }
            }
            let maskedPrefix = hasInputDigits ? ZenzDigitMasking.mask(prefix, maxRuns: inputMask.rawDigits.count).text : prefix
            let maskedPrefixTokens = context.encode(maskedPrefix, addBOS: false, addEOS: false)
            var matchedPrefixCount = 0
            while matchedPrefixCount < candidateTokens.count,
                  matchedPrefixCount < maskedPrefixTokens.count,
                  candidateTokens[matchedPrefixCount] == maskedPrefixTokens[matchedPrefixCount] {
                matchedPrefixCount += 1
            }
            addressedTokens = Array(candidateTokens.prefix(matchedPrefixCount))
        } else {
            addressedTokens = []
        }

        let tokens = promptTokens + candidateTokens
        let startOffset = promptTokens.count - 1 + addressedTokens.count
        guard let logits = context.evaluationLogits(tokens: tokens, startOffset: startOffset) else {
            debug("logits unavailable")
            return .error
        }
        let n_vocab = Int(context.vocabSize)
        var remainingMaskRuns = inputMask.rawDigits.count
        var tokenProtection: [(isUserDict: Bool, precedingUserDictEndedAscii: Bool)] =
            Array(repeating: (false, false), count: promptTokens.count)
        tokenProtection.reserveCapacity(tokens.count)
        var precedingUserDictEndedAscii = false
        let isLearnedToken: [(isLearned: Bool, priority: Float)] = Array(repeating: (false, 0), count: promptTokens.count) + candidate.data.flatMap { element in
            let tokenWord: String
            if hasInputDigits, remainingMaskRuns > 0 {
                let maskedElement = ZenzDigitMasking.mask(element.word, maxRuns: remainingMaskRuns)
                remainingMaskRuns -= maskedElement.rawDigits.count
                tokenWord = maskedElement.text
            } else {
                tokenWord = element.word
            }
            let tokenCount = context.encode(tokenWord, addBOS: false).count
            if tokenCount > 0 {
                let isUserDict = ZenzUserDictionaryProtection.isProtectedUserDictionaryElement(element)
                tokenProtection.append((isUserDict, precedingUserDictEndedAscii))
                tokenProtection.append(contentsOf: repeatElement((isUserDict, false), count: tokenCount - 1))
                precedingUserDictEndedAscii = isUserDict && ZenzUserDictionaryProtection.endsWithPrintableAscii(tokenWord)
            }
            return Array(repeating: (element.metadata.contains(.isLearned), logf(self.learningPriority(data: element))), count: tokenCount)
        }'''

ZENZ_EVALUATOR_LOOP_ANCHOR = '''        var altTokens = FixedSizeHeap<AlternativeHighProbToken>(size: requestRichCandidates ? 5 : 0)
        for (i, tokenID) in tokens.indexed().dropFirst(startOffset + 1) {
            var sumexp: Float = 0
            let startIndex = (i - 1 - startOffset) * Int(n_vocab)
            let endIndex = (i - startOffset) * Int(n_vocab)
            var tokenHeap = FixedSizeHeap<TokenAndLogprob>(size: requestRichCandidates ? 3 : 1)
            for index in startIndex ..< endIndex {
                sumexp += expf(logits[index])
            }
            let logsumexp = logf(sumexp)

            if let (mode, baseLM, personalLM) = personalizationMode, mode.alpha > 0 {
                let prefix = tokens[..<i].dropFirst(promptTokens.count).map(Int.init)
                let baseProb: [Float]
                let personalProb: [Float]
                if !prefix.isEmpty {
                    baseProb = baseLM.bulkPredict(prefix).map { logf(Float($0) + 1e-7) }
                    personalProb = personalLM.bulkPredict(prefix).map { logf(Float($0) + 1e-7) }
                } else {
                    baseProb = Array(repeating: 0, count: Int(n_vocab))
                    personalProb = baseProb
                }
                for (vocabIndex, (lpb, lpp)) in zip(0 ..< Int(n_vocab), zip(baseProb, personalProb)) {
                    let logp = logits[startIndex + vocabIndex] - logsumexp
                    let personalizedLogp = logp + mode.alpha * (lpp - lpb)
                    tokenHeap.insertIfPossible(TokenAndLogprob(token: llama_token(vocabIndex), logprob: personalizedLogp))
                }
            } else {
                for index in startIndex ..< endIndex {
                    let logp = logits[index] - logsumexp
                    tokenHeap.insertIfPossible(TokenAndLogprob(token: llama_token(index - startIndex), logprob: logp))
                }
            }

            guard let maxItem = tokenHeap.max else {
                debug("Max Item could not be found for unknown reason")
                return .error
            }
            if maxItem.token != tokenID {
                if maxItem.token == context.eosToken {
                    let cchars: [CChar] = tokens[..<i].reduce(into: []) {
                        $0.append(contentsOf: context.tokenToPiece(token: $1))
                    }
                    let data = Data(cchars.map { UInt8(bitPattern: $0) })
                    let string = String(data: data, encoding: .utf8) ?? ""
                    let wholeResult = String(string.dropFirst(normalizedPrompt.count))
                    return .wholeResult(wholeResult)
                } else {
                    let actualLogp = logits[startIndex + Int(tokenID)] - logsumexp
                    let preferLearnedToken = isLearnedToken[i].isLearned && actualLogp + isLearnedToken[i].priority > maxItem.logprob
                    if !preferLearnedToken {
                        let cchars = tokens[..<i].reduce(into: []) {
                            $0.append(contentsOf: context.tokenToPiece(token: $1))
                        } + context.tokenToPiece(token: maxItem.token)
                        return .fixRequired(prefixConstraint: cchars.dropFirst(normalizedPrompt.utf8.count).map(UInt8.init))
                    }
                }
            } else if !tokenHeap.isEmpty {
                tokenHeap.removeMax()
                let prefix = tokens[..<i].reduce(into: []) {
                    $0.append(contentsOf: context.tokenToPiece(token: $1))
                }.dropFirst(normalizedPrompt.utf8.count)

                for item in tokenHeap.unordered {
                    altTokens.insertIfPossible(
                        AlternativeHighProbToken(
                            token: item.token,
                            constraint: prefix.map(UInt8.init) + context.tokenToPiece(token: item.token).map(UInt8.init),
                            probabilityRatioToMaxProb: expf(item.logprob - maxItem.logprob)
                        )
                    )
                }
            }
            score += maxItem.logprob
        }'''

ZENZ_EVALUATOR_LOOP_REPLACEMENT = '''        @inline(__always)
        func restoreConstraintBytes(_ bytes: [UInt8], completePartialTrailingNam: Bool) -> [UInt8] {
            ZenzDigitMasking.restoreBytes(
                bytes,
                digits: evaluationDigits,
                asciiDigits: evaluationAsciiDigits,
                completePartialTrailingNam: completePartialTrailingNam
            )
        }

        @inline(__always)
        func decodePrefixBytes(upTo index: Int) -> [UInt8] {
            let cchars: [CChar] = tokens[..<index].reduce(into: []) {
                $0.append(contentsOf: context.tokenToPiece(token: $1))
            }
            return cchars.dropFirst(normalizedPrompt.utf8.count).map(UInt8.init)
        }

        @inline(__always)
        func decodeWholeResult(upTo index: Int) -> String {
            let cchars: [CChar] = tokens[..<index].reduce(into: []) {
                $0.append(contentsOf: context.tokenToPiece(token: $1))
            }
            let data = Data(cchars.map { UInt8(bitPattern: $0) })
            let string = String(data: data, encoding: .utf8) ?? ""
            return ZenzDigitMasking.restore(
                String(string.dropFirst(normalizedPrompt.count)),
                digits: evaluationDigits,
                asciiDigits: evaluationAsciiDigits
            )
        }

        @inline(__always)
        func stabilizedLogSumExp(startIndex: Int, endIndex: Int, maxRawLogit: Float) -> Float {
            var sumexp: Float = 0
            for index in startIndex ..< endIndex {
                sumexp += expf(logits[index] - maxRawLogit)
            }
            return maxRawLogit + logf(sumexp)
        }

        @inline(__always)
        func allowsProtectedMismatch(at index: Int, tokenID: llama_token, rawLogitGap: Float) -> Bool {
            guard index < tokenProtection.count else { return false }
            let protection = tokenProtection[index]
            let tokenBytes = !protection.isUserDict && protection.precedingUserDictEndedAscii && tokenID != context.eosToken
                ? context.tokenToPiece(token: tokenID).map(UInt8.init) : []
            return ZenzUserDictionaryProtection.shouldAllowMismatch(
                isUserDict: protection.isUserDict,
                precedingUserDictEndedAscii: protection.precedingUserDictEndedAscii,
                rawLogitGap: rawLogitGap,
                tokenBytes: tokenBytes
            )
        }

        @inline(__always)
        func isStopScalar(_ scalar: Unicode.Scalar) -> Bool {
            (scalar.value >= 0xEE00 && scalar.value <= 0xEE0F) || scalar.value == 0x0A || scalar.value == 0x0D
        }

        @inline(__always)
        func isPureStopPiece(_ bytes: [UInt8]) -> Bool {
            String(decoding: bytes, as: UTF8.self).unicodeScalars.first.map(isStopScalar) ?? false
        }

        @inline(__always)
        func containsStopScalar(_ bytes: [UInt8]) -> Bool {
            String(decoding: bytes, as: UTF8.self).unicodeScalars.contains(where: isStopScalar)
        }

        @inline(__always)
        func canDirectContinue(from index: Int) -> Bool {
            guard personalizationMode == nil else {
                return false
            }
            if !prefixConstraint.ignoreMemoryAndUserDictionary, index < tokens.count {
                let guardStart = max(promptTokens.count, index - 1)
                if guardStart < tokenProtection.count, tokenProtection[guardStart...].contains(where: \\.isUserDict) {
                    return false
                }
                if guardStart < isLearnedToken.count, isLearnedToken[guardStart...].contains(where: \\.isLearned) {
                    return false
                }
            }
            return true
        }

        @inline(__always)
        func shouldDirectContinue(at index: Int, maxToken: llama_token) -> Bool {
            guard canDirectContinue(from: index) else {
                return false
            }
            if tokens.count - index <= 2 {
                return true
            }
            let pieceBytes = context.tokenToPiece(token: maxToken).map { UInt8(bitPattern: $0) }
            if pieceBytes.contains(where: { $0 >= 0x21 && $0 <= 0x7E }) {
                return true
            }
            if !prefixConstraint.constraint.isEmpty,
               index <= promptTokens.count + addressedTokens.count {
                return true
            }
            return false
        }

        func greedyContinueToWholeResult(from index: Int, firstToken: llama_token, maxSteps: Int? = nil) -> String? {
            let firstBytes = context.tokenToPiece(token: firstToken).map { UInt8(bitPattern: $0) }
            if isPureStopPiece(firstBytes) {
                let whole = decodeWholeResult(upTo: index)
                return !whole.isEmpty && whole != candidate.text ? whole : nil
            }
            let limitSteps = maxSteps ?? min(48, max(8, input.count + 8))
            guard let suffixTokens = context.continueDirectGreedy(
                prefixTokens: Array(tokens.prefix(index)),
                firstToken: firstToken,
                maxSteps: limitSteps
            ) else {
                return nil
            }
            var bytes: [UInt8] = []
            for token in tokens[promptTokens.count ..< index] {
                bytes.append(contentsOf: context.tokenToPiece(token: token).map { UInt8(bitPattern: $0) })
            }
            for token in suffixTokens {
                bytes.append(contentsOf: context.tokenToPiece(token: token).map { UInt8(bitPattern: $0) })
            }
            var decoded = String(decoding: bytes, as: UTF8.self)
            var stopCut = decoded.endIndex
            if let scalarIdx = decoded.unicodeScalars.firstIndex(where: isStopScalar), scalarIdx < stopCut {
                stopCut = scalarIdx
            }
            if let endTag = decoded.range(of: "</s>")?.lowerBound, endTag < stopCut {
                stopCut = endTag
            }
            if let startTag = decoded.range(of: "<s>")?.lowerBound, startTag < stopCut {
                stopCut = startTag
            }
            if stopCut < decoded.endIndex {
                decoded = String(decoded[..<stopCut])
            }
            let useInputDigits = candidateMask != nil && candidateMask!.rawDigits.count < inputMask.rawDigits.count
            let restoreDigits = useInputDigits ? inputMask.rawDigits : evaluationDigits
            let restoreAsciiDigits = useInputDigits ? inputMask.asciiDigits : evaluationAsciiDigits
            let whole = ZenzDigitMasking.restore(decoded, digits: restoreDigits, asciiDigits: restoreAsciiDigits)
            guard !whole.isEmpty, whole != candidate.text else {
                return nil
            }
            return whole
        }

        var altTokens = FixedSizeHeap<AlternativeHighProbToken>(size: requestRichCandidates ? 5 : 0)
        for (i, tokenID) in tokens.indexed().dropFirst(startOffset + 1) {
            let startIndex = (i - 1 - startOffset) * Int(n_vocab)
            let endIndex = (i - startOffset) * Int(n_vocab)
            guard startIndex < endIndex else {
                debug("Max Item could not be found for unknown reason")
                return .error
            }

            if let (mode, baseLM, personalLM) = personalizationMode, mode.alpha > 0 {
                var maxRawLogit: Float = logits[startIndex]
                for index in (startIndex + 1) ..< endIndex {
                    let value = logits[index]
                    if value > maxRawLogit {
                        maxRawLogit = value
                    }
                }
                let logsumexp = stabilizedLogSumExp(startIndex: startIndex, endIndex: endIndex, maxRawLogit: maxRawLogit)
                var tokenHeap = FixedSizeHeap<TokenAndLogprob>(size: requestRichCandidates ? 3 : 1)
                let prefix = tokens[..<i].dropFirst(promptTokens.count).map(Int.init)
                let baseProb: [Float]
                let personalProb: [Float]
                if !prefix.isEmpty {
                    baseProb = baseLM.bulkPredict(prefix).map { logf(Float($0) + 1e-7) }
                    personalProb = personalLM.bulkPredict(prefix).map { logf(Float($0) + 1e-7) }
                } else {
                    baseProb = Array(repeating: 0, count: Int(n_vocab))
                    personalProb = baseProb
                }
                for (vocabIndex, (lpb, lpp)) in zip(0 ..< Int(n_vocab), zip(baseProb, personalProb)) {
                    let logp = logits[startIndex + vocabIndex] - logsumexp
                    let personalizedLogp = logp + mode.alpha * (lpp - lpb)
                    tokenHeap.insertIfPossible(TokenAndLogprob(token: llama_token(vocabIndex), logprob: personalizedLogp))
                }
                guard let maxItem = tokenHeap.max else {
                    debug("Max Item could not be found for unknown reason")
                    return .error
                }
                if maxItem.token != tokenID,
                   !allowsProtectedMismatch(at: i, tokenID: tokenID, rawLogitGap: maxRawLogit - logits[startIndex + Int(tokenID)]) {
                    if maxItem.token == context.eosToken {
                        return .wholeResult(decodeWholeResult(upTo: i))
                    } else {
                        let actualLogp = logits[startIndex + Int(tokenID)] - logsumexp
                        let learned = i < isLearnedToken.count ? isLearnedToken[i] : (isLearned: false, priority: Float(0))
                        let preferLearnedToken = learned.isLearned && actualLogp + learned.priority > maxItem.logprob
                        if !preferLearnedToken {
                            let constraint = restoreConstraintBytes(
                                decodePrefixBytes(upTo: i) + context.tokenToPiece(token: maxItem.token).map(UInt8.init),
                                completePartialTrailingNam: true
                            )
                            return .fixRequired(prefixConstraint: constraint)
                        }
                    }
                } else if maxItem.token == tokenID, !tokenHeap.isEmpty {
                    tokenHeap.removeMax()
                    let prefix = decodePrefixBytes(upTo: i)
                    for item in tokenHeap.unordered {
                        altTokens.insertIfPossible(
                            AlternativeHighProbToken(
                                token: item.token,
                                constraint: restoreConstraintBytes(
                                    prefix + context.tokenToPiece(token: item.token).map(UInt8.init),
                                    completePartialTrailingNam: false
                                ),
                                probabilityRatioToMaxProb: expf(item.logprob - maxItem.logprob)
                            )
                        )
                    }
                }
                score += maxItem.logprob
            } else {
                // Single-pass argmax over raw logits; skip expf and heap work unless needed.
                var maxRawLogit: Float = logits[startIndex]
                var maxToken: llama_token = 0
                var richHeap: FixedSizeHeap<TokenAndLogprob>?
                if requestRichCandidates {
                    var heap = FixedSizeHeap<TokenAndLogprob>(size: 3)
                    for index in startIndex ..< endIndex {
                        heap.insertIfPossible(TokenAndLogprob(token: llama_token(index - startIndex), logprob: logits[index]))
                    }
                    guard let maxItem = heap.max else {
                        debug("Max Item could not be found for unknown reason")
                        return .error
                    }
                    maxToken = maxItem.token
                    maxRawLogit = maxItem.logprob
                    richHeap = heap
                } else {
                    for index in (startIndex + 1) ..< endIndex {
                        let value = logits[index]
                        if value > maxRawLogit {
                            maxRawLogit = value
                            maxToken = llama_token(index - startIndex)
                        }
                    }
                }

                if maxToken != tokenID,
                   !allowsProtectedMismatch(at: i, tokenID: tokenID, rawLogitGap: maxRawLogit - logits[startIndex + Int(tokenID)]) {
                    if maxToken == context.eosToken {
                        return .wholeResult(decodeWholeResult(upTo: i))
                    } else {
                        let learned = i < isLearnedToken.count ? isLearnedToken[i] : (isLearned: false, priority: Float(0))
                        let preferLearnedToken = learned.isLearned && logits[startIndex + Int(tokenID)] + learned.priority > maxRawLogit
                        if !preferLearnedToken {
                            if shouldDirectContinue(at: i, maxToken: maxToken),
                               let wholeText = greedyContinueToWholeResult(from: i, firstToken: maxToken) {
                                return .wholeResult(wholeText)
                            }
                            let constraint = restoreConstraintBytes(
                                decodePrefixBytes(upTo: i) + context.tokenToPiece(token: maxToken).map(UInt8.init),
                                completePartialTrailingNam: true
                            )
                            return .fixRequired(prefixConstraint: constraint)
                        }
                    }
                } else if maxToken == tokenID, var heap = richHeap, !heap.isEmpty {
                    heap.removeMax()
                    let prefix = decodePrefixBytes(upTo: i)
                    for item in heap.unordered {
                        altTokens.insertIfPossible(
                            AlternativeHighProbToken(
                                token: item.token,
                                constraint: restoreConstraintBytes(
                                    prefix + context.tokenToPiece(token: item.token).map(UInt8.init),
                                    completePartialTrailingNam: false
                                ),
                                probabilityRatioToMaxProb: expf(item.logprob - maxRawLogit)
                            )
                        )
                    }
                }
            }
        }
        if personalizationMode == nil,
           !candidateTokens.isEmpty,
           tokens.count >= startOffset + 1,
           canDirectContinue(from: tokens.count) {
            let trailingStart = (tokens.count - 1 - startOffset) * Int(n_vocab)
            let trailingEnd = (tokens.count - startOffset) * Int(n_vocab)
            var trailingMaxLogit: Float = logits[trailingStart]
            var trailingMaxToken: llama_token = 0
            for index in (trailingStart + 1) ..< trailingEnd {
                let value = logits[index]
                if value > trailingMaxLogit {
                    trailingMaxLogit = value
                    trailingMaxToken = llama_token(index - trailingStart)
                }
            }
            if trailingMaxToken != context.eosToken {
                let trailingBytes = context.tokenToPiece(token: trailingMaxToken).map { UInt8(bitPattern: $0) }
                if !isPureStopPiece(trailingBytes),
                   let wholeText = greedyContinueToWholeResult(from: tokens.count, firstToken: trailingMaxToken, maxSteps: 4) {
                    return .wholeResult(wholeText)
                }
            }
        }'''


def patch_zenz_evaluator(text):
    """Protect user dictionary tokens without adding dictionary text to the model prompt."""
    _require(text, ZENZ_EVALUATOR_USER_DICT_PROMPT_ANCHOR)
    text = text.replace(ZENZ_EVALUATOR_USER_DICT_PROMPT_ANCHOR, '        let userDictionaryPrompt = ""', 1)
    _require(text, ZENZ_EVALUATOR_DECLARATION_ANCHOR)
    text = text.replace(ZENZ_EVALUATOR_DECLARATION_ANCHOR,
                        ZENZ_USER_DICTIONARY_PROTECTION + ZENZ_EVALUATOR_DECLARATION_ANCHOR, 1)
    _require(text, ZENZ_EVALUATOR_SETUP_ANCHOR)
    text = text.replace(ZENZ_EVALUATOR_SETUP_ANCHOR, ZENZ_EVALUATOR_SETUP_REPLACEMENT, 1)
    _require(text, ZENZ_EVALUATOR_LOOP_ANCHOR)
    return text.replace(ZENZ_EVALUATOR_LOOP_ANCHOR, ZENZ_EVALUATOR_LOOP_REPLACEMENT, 1)


# Windows dictionary checkouts and Android staged assets carry CRLF (`\r\n`)
# line endings; Swift treats `"\r\n"` as one Character so `.split(separator: "\n")`
# returns the entire CSV file as one unsplit line and discards rows 2..30.
DICDATA_ZERO_HINT_CRLF_ANCHOR = '            let csvLines = csvString.split(separator: "\\n")'
DICDATA_ZERO_HINT_CRLF_REPLACEMENT = '            let csvLines = csvString.split(whereSeparator: \\.isNewline)'

DICDATA_PREDICTION_LOOKUP_ANCHOR = '''    func getPredictionLOUDSDicdata(key: some StringProtocol, state: DicdataStoreState, includeExactMatch: Bool = false) -> [DicdataElement] {'''
DICDATA_PREDICTION_LOOKUP_REPLACEMENT = '''    func getPredictionLOUDSDicdata(
        key: some StringProtocol,
        state: DicdataStoreState,
        includeExactMatch: Bool = false,
        pendingRomanLimit: Int? = nil
    ) -> [DicdataElement] {'''

DICDATA_PREDICTION_LIMIT_REPLACEMENTS = (
    (DICDATA_PREDICTION_LOOKUP_ANCHOR, DICDATA_PREDICTION_LOOKUP_REPLACEMENT),
    ('        let maxCount = 700', '        let maxCount = pendingRomanLimit ?? 700'),
    ('        let depth = if count == 1 {', '        let depth = if pendingRomanLimit != nil {\n            7\n        } else if count == 1 {'),
    (
        '        if includeExactMatch, prefixIndices.count < maxCount {',
        '''        if pendingRomanLimit != nil {
            prefixIndices = Array(prefixIndices.prefix(maxCount))
        }
        if includeExactMatch, pendingRomanLimit != nil || prefixIndices.count < maxCount {''',
    ),
    (
        '        var userDictIndices = self.startingFromPrefixSearch(query: "user", charIDs: charIDs, maxCount: maxCount, state: state)\n'
        '        if includeExactMatch, userDictIndices.count < maxCount {',
        '''        var userDictIndices = self.startingFromPrefixSearch(query: "user", charIDs: charIDs,
            depth: pendingRomanLimit == nil ? .max : depth, maxCount: maxCount, state: state)
        if pendingRomanLimit != nil {
            userDictIndices = Array(userDictIndices.prefix(maxCount))
        }
        if includeExactMatch, pendingRomanLimit != nil || userDictIndices.count < maxCount {''',
    ),
    (
        '            var memoryDictIndices = self.startingFromPrefixSearch(query: "memory", charIDs: charIDs, maxCount: maxCount, state: state)\n'
        '            if includeExactMatch, memoryDictIndices.count < maxCount {',
        '''            var memoryDictIndices = self.startingFromPrefixSearch(query: "memory", charIDs: charIDs,
                depth: pendingRomanLimit == nil ? .max : depth, maxCount: maxCount, state: state)
            if pendingRomanLimit != nil {
                memoryDictIndices = Array(memoryDictIndices.prefix(maxCount))
            }
            if includeExactMatch, pendingRomanLimit != nil || memoryDictIndices.count < maxCount {''',
    ),
)


DICDATA_STORE_PERFORMANCE_REPLACEMENTS = (
    (
        '    private var ccLines: [Int: [PValue]] = [:]',
        '    private var ccLines: [[PValue]?] = .init(repeating: nil, count: 1319)',
    ),
    (
        '    private var charsID: [Character: UInt8] = [:]',
        '    private var charsID: [Character: UInt8] = [:]\n'
        '    private var defaultIdentifiers: [String: String] = [:]\n'
        '    private var missingLoudstxts: Set<String> = []',
    ),
    (
        '''    private func defaultDictionaryIdentifier(for query: String) -> String {
        let escaped = DictionaryBuilder.escapedIdentifier(query)
        let escapedURL = self.dictionaryURL.appendingPathComponent("louds/\\(escaped).louds", isDirectory: false)
        if FileManager.default.fileExists(atPath: escapedURL.path) {
            return escaped
        }
        let legacyURL = self.dictionaryURL.appendingPathComponent("louds/\\(query).louds", isDirectory: false)
        if FileManager.default.fileExists(atPath: legacyURL.path) {
            return query
        }
        return escaped
    }''',
        '''    private func defaultDictionaryIdentifier(for query: String) -> String {
        if let cached = self.defaultIdentifiers[query] {
            return cached
        }
        let escaped = DictionaryBuilder.escapedIdentifier(query)
        let escapedURL = self.dictionaryURL.appendingPathComponent("louds/\\(escaped).louds", isDirectory: false)
        if FileManager.default.fileExists(atPath: escapedURL.path) {
            self.defaultIdentifiers[query] = escaped
            return escaped
        }
        let legacyURL = self.dictionaryURL.appendingPathComponent("louds/\\(query).louds", isDirectory: false)
        let resolved = FileManager.default.fileExists(atPath: legacyURL.path) ? query : escaped
        self.defaultIdentifiers[query] = resolved
        return resolved
    }''',
    ),
    (
        '''    package func getDicdataFromLoudstxt3(identifier: String, indices: some Sequence<Int>, state: DicdataStoreState) -> [DicdataElement] {
        // Group indices by shard
        let dict = [Int: [Int]].init(grouping: indices, by: { $0 >> DictionaryBuilder.shardShift })
        var data: [DicdataElement] = []
        if identifier == "user", let userDictionaryURL = state.userDictionaryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getUserDictionaryDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    userDictionaryURL: userDictionaryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isFromUserDictionary
            }
        }
        if identifier == "user_shortcuts", let userDictionaryURL = state.userDictionaryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getUserShortcutsDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    userDictionaryURL: userDictionaryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isFromUserDictionary
            }
        }
        if identifier == "memory", let memoryURL = state.memoryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getMemoryDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    memoryURL: memoryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isLearned
            }
        }
        let defaultIdentifier = self.defaultDictionaryIdentifier(for: identifier)
        for (key, value) in dict {
            let fileID = "\\(defaultIdentifier)\\(key)"
            data.append(contentsOf: LOUDS.getDataForLoudstxt3(
                fileID,
                indices: value.map { $0 & DictionaryBuilder.localMask },
                cache: self.loudstxts[fileID],
                dictionaryURL: self.dictionaryURL
            ))
        }
        return data
    }''',
        '''    package func getDicdataFromLoudstxt3(identifier: String, indices: some Sequence<Int>, state: DicdataStoreState) -> [DicdataElement] {
        // Group indices by shard
        let dict = [Int: [Int]].init(grouping: indices, by: { $0 >> DictionaryBuilder.shardShift })
        guard !dict.isEmpty else {
            return []
        }
        var data: [DicdataElement] = []
        if identifier == "user", let userDictionaryURL = state.userDictionaryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getUserDictionaryDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    userDictionaryURL: userDictionaryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isFromUserDictionary
            }
            return data
        }
        if identifier == "user_shortcuts", let userDictionaryURL = state.userDictionaryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getUserShortcutsDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    userDictionaryURL: userDictionaryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isFromUserDictionary
            }
            return data
        }
        if identifier == "memory", let memoryURL = state.memoryURL {
            for (key, value) in dict {
                let fileID = "\\(identifier)\\(key)"
                data.append(contentsOf: LOUDS.getMemoryDataForLoudstxt3(
                    fileID,
                    indices: value.map { $0 & DictionaryBuilder.localMask },
                    cache: self.loudstxts[fileID],
                    memoryURL: memoryURL
                ))
            }
            data.mutatingForEach {
                $0.metadata = .isLearned
            }
            return data
        }
        let defaultIdentifier = self.defaultDictionaryIdentifier(for: identifier)
        for (key, value) in dict {
            let fileID = "\\(defaultIdentifier)\\(key)"
            let cachedData: Data
            if let existing = self.loudstxts[fileID] {
                cachedData = existing
            } else if self.missingLoudstxts.contains(fileID) {
                continue
            } else {
                let url = self.dictionaryURL.appendingPathComponent("louds/\\(fileID).loudstxt3", isDirectory: false)
                if let loaded = try? Data(contentsOf: url, options: [.mappedIfSafe]) {
                    self.loudstxts[fileID] = loaded
                    cachedData = loaded
                } else {
                    self.missingLoudstxts.insert(fileID)
                    continue
                }
            }
            data.append(contentsOf: LOUDS.getDataForLoudstxt3(
                fileID,
                indices: value.map { $0 & DictionaryBuilder.localMask },
                cache: cachedData,
                dictionaryURL: self.dictionaryURL
            ))
        }
        return data
    }''',
    ),
    (
        '''        latticeNodes.reserveCapacity(latticeNodes.count + additionalDicdata.count)
        for element in consume additionalDicdata {
            let rubyArray = Array(element.ruby)
            guard let info = stringToInfo[rubyArray] else {
                continue
            }
            if let element = penaltizedElementIfFeasible(consume element, rubyCount: rubyArray.count, penalty: info.penalty) {
                appendNode(
                    element,
                    endIndex: info.endIndex,
                    keyboardTypoCorrection: info.keyboardTypoCorrection
                )
            }
        }

        // MARK: 検索によって得たindicesから辞書データを実際に取り出していく
        for (identifier, value) in consume indices {
            let items = self.getDicdataFromLoudstxt3(identifier: identifier, indices: value, state: state)
            latticeNodes.reserveCapacity(latticeNodes.count + items.count)
            for element in consume items {
                let rubyArray = Array(element.ruby)
                guard let info = stringToInfo[rubyArray] else {
                    continue
                }
                if let element = penaltizedElementIfFeasible(consume element, rubyCount: rubyArray.count, penalty: info.penalty) {
                    appendNode(
                        element,
                        endIndex: info.endIndex,
                        keyboardTypoCorrection: info.keyboardTypoCorrection
                    )
                }
            }
        }''',
        '''        var lastRuby: String?
        var lastRubyCount = 0
        var lastInfo: (endIndex: Lattice.LatticeIndex, penalty: PValue, keyboardTypoCorrection: KeyboardTypoCorrectionProvenance?)?

        latticeNodes.reserveCapacity(latticeNodes.count + additionalDicdata.count)
        for element in consume additionalDicdata {
            let info: (endIndex: Lattice.LatticeIndex, penalty: PValue, keyboardTypoCorrection: KeyboardTypoCorrectionProvenance?)
            let rubyCount: Int
            if element.ruby == lastRuby {
                guard let cachedInfo = lastInfo else {
                    continue
                }
                info = cachedInfo
                rubyCount = lastRubyCount
            } else {
                lastRuby = element.ruby
                let rubyArray = Array(element.ruby)
                lastRubyCount = rubyArray.count
                lastInfo = stringToInfo[rubyArray]
                guard let resolvedInfo = lastInfo else {
                    continue
                }
                info = resolvedInfo
                rubyCount = lastRubyCount
            }
            if let element = penaltizedElementIfFeasible(consume element, rubyCount: rubyCount, penalty: info.penalty) {
                appendNode(
                    element,
                    endIndex: info.endIndex,
                    keyboardTypoCorrection: info.keyboardTypoCorrection
                )
            }
        }

        // MARK: 検索によって得たindicesから辞書データを実際に取り出していく
        for (identifier, value) in consume indices {
            let items = self.getDicdataFromLoudstxt3(identifier: identifier, indices: value, state: state)
            latticeNodes.reserveCapacity(latticeNodes.count + items.count)
            for element in consume items {
                let info: (endIndex: Lattice.LatticeIndex, penalty: PValue, keyboardTypoCorrection: KeyboardTypoCorrectionProvenance?)
                let rubyCount: Int
                if element.ruby == lastRuby {
                    guard let cachedInfo = lastInfo else {
                        continue
                    }
                    info = cachedInfo
                    rubyCount = lastRubyCount
                } else {
                    lastRuby = element.ruby
                    let rubyArray = Array(element.ruby)
                    lastRubyCount = rubyArray.count
                    lastInfo = stringToInfo[rubyArray]
                    guard let resolvedInfo = lastInfo else {
                        continue
                    }
                    info = resolvedInfo
                    rubyCount = lastRubyCount
                }
                if let element = penaltizedElementIfFeasible(consume element, rubyCount: rubyCount, penalty: info.penalty) {
                    appendNode(
                        element,
                        endIndex: info.endIndex,
                        keyboardTypoCorrection: info.keyboardTypoCorrection
                    )
                }
            }
        }''',
    ),
)


DICDATA_MINUTE_COUNTER_ANCHOR = '''            let prevIsNumber = i >= 0 && fullText[i].isNumber
            let j = surfaceRange.upperBound'''
DICDATA_MINUTE_COUNTER_REPLACEMENT = '''            let prevIsNumber = i >= 0 && fullText[i].isNumber
            if convertTarget == "プン", prevIsNumber {
                let previous = fullText[i].unicodeScalars
                if previous.count == 1, let scalar = previous.first, ZenzDigitMasking.isDigitScalar(scalar) {
                    // Use the same dictionary classes and cost as the フン → 分 entry.
                    result.append(DicdataElement(word: "分", ruby: convertTarget, cid: 1300, mid: 163, value: -6.9856))
                }
            }
            let j = surfaceRange.upperBound'''


def patch_dicdata_store(text):
    """Patch dictionary lookup, prediction data, and number-counter candidates."""
    _require(text, DICDATA_ZERO_HINT_CRLF_ANCHOR)
    text = text.replace(DICDATA_ZERO_HINT_CRLF_ANCHOR, DICDATA_ZERO_HINT_CRLF_REPLACEMENT, 1)
    for anchor, replacement in DICDATA_PREDICTION_LIMIT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    for anchor, replacement in DYNAMIC_DICTIONARY_LOOKUPS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    for anchor, replacement in DICDATA_STORE_PERFORMANCE_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    _require(text, DICDATA_MINUTE_COUNTER_ANCHOR)
    text = text.replace(DICDATA_MINUTE_COUNTER_ANCHOR, DICDATA_MINUTE_COUNTER_REPLACEMENT, 1)
    return text


ZENZ_INPUT_GENERATOR_STRUCT_ANCHOR = 'struct ZenzInputTextGenerator {'
ZENZ_INPUT_GENERATOR_STRUCT_REPLACEMENT = '''public struct ContextualNextWordReading: Sendable, Hashable {
    public let reading: String
    public let score: Float

    public init(reading: String, score: Float) {
        self.reading = reading
        self.score = score
    }
}

struct ZenzInputTextGenerator {'''

ZENZ_INPUT_GENERATOR_TAIL_ANCHOR = '''            if appendedTokens.isEmpty {
                break
            }
            promptTokens.append(contentsOf: appendedTokens)
        }

        return String(predictedCharacters)
    }
}'''

ZENZ_INPUT_GENERATOR_TAIL_REPLACEMENT = '''            if appendedTokens.isEmpty {
                break
            }
            promptTokens.append(contentsOf: appendedTokens)
        }

        return String(predictedCharacters)
    }

    private static func isContextualKatakanaScalar(_ scalar: UnicodeScalar) -> Bool {
        (0x30A1 ... 0x30F6).contains(scalar.value) || scalar.value == 0x30FC
    }

    private static func isValidContextualPiece(_ piece: String) -> Bool {
        guard !piece.isEmpty,
              !piece.contains("\\u{FFFD}"),
              !piece.contains("エモジ"),
              !piece.contains("nam") else {
            return false
        }
        return !piece.unicodeScalars.contains { scalar in
            CharacterSet.controlCharacters.contains(scalar)
                || (0xEE00 ... 0xEE0F).contains(scalar.value)
        }
    }

    static func generateContextualCandidates(
        context: ZenzContext,
        leftSideContext: String,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode,
        maxBranches: Int = 5,
        maxTokensPerBranch: Int = 5,
        minStarterProb: Float = 0.012,
        maxDurationSeconds: Double = 0.085,
        shouldCancel: (() -> Bool)? = nil
    ) -> [ContextualNextWordReading] {
        guard maxBranches > 0,
              maxTokensPerBranch >= 0,
              shouldCancel?() != true else {
            return []
        }
        let normalizedLine = leftSideContext
            .replacingOccurrences(of: "\\r\\n", with: "\\n")
            .replacingOccurrences(of: "\\r", with: "\\n")
        let cleanContext = String(normalizedLine.split(separator: "\\n", omittingEmptySubsequences: false).last ?? "")
            .trimmingCharacters(in: .whitespaces)
        guard !cleanContext.isEmpty,
              let prompt = ZenzPromptBuilder.inputPredictionPrompt(
                  leftSideContext: cleanContext,
                  composingText: "",
                  versionDependentConfig: versionDependentConfig
              ) else {
            return []
        }

        let startedAt = Date().timeIntervalSinceReferenceDate
        let baseTokens = context.encode(prompt, addBOS: true, addEOS: false)
        guard !baseTokens.isEmpty,
              baseTokens.count <= 440,
              shouldCancel?() != true,
              let firstLogits = context.inputPredictionLogits(
                  tokens: baseTokens,
                  startOffset: baseTokens.count - 1
              ) else {
            return []
        }

        let vocabSize = Int(context.vocabSize)
        guard vocabSize > 0 else {
            return []
        }

        var maxLogit: Float = -.infinity
        var topItems: [(token: llama_token, logit: Float)] = []
        topItems.reserveCapacity(16)
        for idx in 0 ..< vocabSize {
            let logit = firstLogits[idx]
            if logit > maxLogit {
                maxLogit = logit
            }
            if topItems.count < 14 || logit > topItems[topItems.count - 1].logit {
                let insertAt = topItems.firstIndex(where: { logit > $0.logit }) ?? topItems.count
                topItems.insert((token: llama_token(idx), logit: logit), at: insertAt)
                if topItems.count > 14 {
                    topItems.removeLast()
                }
            }
        }
        guard maxLogit.isFinite else {
            return []
        }
        var sumExp: Float = 0
        for idx in 0 ..< vocabSize {
            sumExp += expf(firstLogits[idx] - maxLogit)
        }
        let logSumExp = maxLogit + logf(max(sumExp, 1e-20))

        let punctuationSet: Set<String> = ["、", "。", "！", "？"]
        let disallowedStarters: Set<Character> = ["ー", "ァ", "ィ", "ゥ", "ェ", "ォ", "ッ", "ャ", "ュ", "ョ", "ヮ", "ン", "ヵ", "ヶ"]
        var outputsByReading: [String: Float] = [:]
        var starterBranches: [(token: llama_token, piece: String, logProb: Float)] = []
        starterBranches.reserveCapacity(maxBranches)

        for item in topItems {
            guard item.token != context.eosToken else {
                continue
            }
            let logProb = item.logit - logSumExp
            let prob = expf(logProb)
            let bytes = context.tokenToPiece(token: item.token).map { UInt8(truncatingIfNeeded: $0) }
            guard let piece = String(data: Data(bytes), encoding: .utf8),
                  Self.isValidContextualPiece(piece) else {
                continue
            }
            if punctuationSet.contains(piece) {
                if prob >= 0.03 {
                    if let existing = outputsByReading[piece] {
                        outputsByReading[piece] = max(existing, logProb)
                    } else {
                        outputsByReading[piece] = logProb
                    }
                }
                continue
            }
            guard prob >= minStarterProb,
                  starterBranches.count < maxBranches,
                  let firstChar = piece.first,
                  !disallowedStarters.contains(firstChar),
                  piece.unicodeScalars.allSatisfy(Self.isContextualKatakanaScalar) else {
                continue
            }
            starterBranches.append((token: item.token, piece: piece, logProb: logProb))
        }

        for starter in starterBranches {
            if shouldCancel?() == true || (Date().timeIntervalSinceReferenceDate - startedAt) >= maxDurationSeconds {
                break
            }
            var branchTokens: [llama_token] = [starter.token]
            var reading = starter.piece
            for _ in 0 ..< maxTokensPerBranch {
                if reading.count >= 8
                    || shouldCancel?() == true
                    || (Date().timeIntervalSinceReferenceDate - startedAt) >= maxDurationSeconds {
                    break
                }
                let stepTokens = baseTokens + branchTokens
                guard stepTokens.count < 480,
                      let stepLogits = context.inputPredictionLogits(
                          tokens: stepTokens,
                          startOffset: stepTokens.count - 1
                      ) else {
                    break
                }
                var bestIdx = 0
                var bestLogit = stepLogits[0]
                for idx in 1 ..< vocabSize {
                    let v = stepLogits[idx]
                    if v > bestLogit {
                        bestLogit = v
                        bestIdx = idx
                    }
                }
                let nextToken = llama_token(bestIdx)
                guard nextToken != context.eosToken else {
                    break
                }
                let nextBytes = context.tokenToPiece(token: nextToken).map { UInt8(truncatingIfNeeded: $0) }
                guard let nextPiece = String(data: Data(nextBytes), encoding: .utf8),
                      Self.isValidContextualPiece(nextPiece),
                      nextPiece.unicodeScalars.allSatisfy(Self.isContextualKatakanaScalar),
                      reading.count + nextPiece.count <= 10 else {
                    break
                }
                let candidateReading = reading + nextPiece
                if candidateReading.contains("エモジ") {
                    break
                }
                reading = candidateReading
                branchTokens.append(nextToken)
            }
            guard !reading.isEmpty,
                  reading != "エ",
                  reading != "エモ",
                  !reading.contains("エモジ") else {
                continue
            }
            if let existing = outputsByReading[reading] {
                outputsByReading[reading] = max(existing, starter.logProb)
            } else {
                outputsByReading[reading] = starter.logProb
            }
        }

        return outputsByReading
            .map { ContextualNextWordReading(reading: $0.key, score: $0.value) }
            .sorted { lhs, rhs in
                if lhs.score != rhs.score {
                    return lhs.score > rhs.score
                }
                return lhs.reading < rhs.reading
            }
    }
}'''


def patch_zenz_input_generator(text):
    """Add bounded contextual next-word reading prediction sharing the prediction KV slot."""
    _require(text, ZENZ_INPUT_GENERATOR_STRUCT_ANCHOR)
    text = text.replace(ZENZ_INPUT_GENERATOR_STRUCT_ANCHOR, ZENZ_INPUT_GENERATOR_STRUCT_REPLACEMENT, 1)
    _require(text, ZENZ_INPUT_GENERATOR_TAIL_ANCHOR)
    return text.replace(ZENZ_INPUT_GENERATOR_TAIL_ANCHOR, ZENZ_INPUT_GENERATOR_TAIL_REPLACEMENT, 1)


ZENZ_CONTEXTUAL_ANCHOR = '''    package func pureGreedyDecoding(pureInput: String, maxCount: Int = .max) -> String {
        guard let zenzContext else {
            return ""
        }
        return ZenzPureGreedyDecoder.decode(context: zenzContext, leftSideContext: pureInput, maxCount: maxCount)
    }'''

ZENZ_CONTEXTUAL_REPLACEMENT = '''    package func pureGreedyDecoding(pureInput: String, maxCount: Int = .max) -> String {
        guard let zenzContext else {
            return ""
        }
        return ZenzPureGreedyDecoder.decode(context: zenzContext, leftSideContext: pureInput, maxCount: maxCount)
    }

    package var prefersDirectModelFirst: Bool {
        self.zenzContext?.prefersDirectModelFirst ?? false
    }

    package var isGpuOffloaded: Bool {
        self.zenzContext?.isGpuOffloaded ?? false
    }

    package func generateDirectCandidate(
        convertTarget: String,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode
    ) -> DirectZenzaiGenerationResult {
        guard let zenzContext else {
            return .needsFallback
        }
        let katakana = convertTarget.toKatakana()
        let inputMask = ZenzDigitMasking.mask(katakana)
        let prompt = ZenzPromptBuilder.candidateEvaluationPrompt(
            input: katakana,
            userDictionaryPrompt: "",
            versionDependentConfig: versionDependentConfig
        )
        let promptTokens = zenzContext.encode(prompt, addBOS: true, addEOS: false)
        let maxTokens = min(112, max(32, katakana.count * 3 + 12))
        let generated = zenzContext.generateDirectGreedy(promptTokens: promptTokens, maxTokens: maxTokens)
        switch generated {
        case .text(let raw, let tokenMargins):
            let restored = ZenzDigitMasking.restore(raw, digits: inputMask.rawDigits, asciiDigits: inputMask.asciiDigits)
            let safeMargins = (inputMask.rawDigits.isEmpty || restored.utf8.count == raw.utf8.count) ? tokenMargins : []
            return restored.isEmpty ? .needsFallback : .text(restored, tokenMargins: safeMargins)
        case .needsFallback, .decodeAborted:
            return generated
        }
    }

    func contextualNextWordReadings(
        leftSideContext: String,
        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode,
        maxBranches: Int = 5,
        maxTokensPerBranch: Int = 5,
        maxDurationSeconds: Double = 0.085,
        shouldCancel: (() -> Bool)? = nil
    ) -> [ContextualNextWordReading] {
        guard let zenzContext else {
            return []
        }
        return ZenzInputTextGenerator.generateContextualCandidates(
            context: zenzContext,
            leftSideContext: leftSideContext,
            versionDependentConfig: versionDependentConfig,
            maxBranches: maxBranches,
            maxTokensPerBranch: maxTokensPerBranch,
            maxDurationSeconds: maxDurationSeconds,
            shouldCancel: shouldCancel
        )
    }'''


def patch_zenz(text):
    """Forward contextual next-word reading generation through Zenz."""
    _require(text, ZENZ_CONTEXTUAL_ANCHOR)
    return text.replace(ZENZ_CONTEXTUAL_ANCHOR, ZENZ_CONTEXTUAL_REPLACEMENT, 1)


FULL_INPUT_PROCESSING_REPLACEMENTS = (
    (
        '''    func makeRawNodes() -> [[LatticeNode]] {''',
        '''    init(inputData: ComposingText, lattice: Lattice) {
        let inputCount = inputData.input.count
        let surfaceCount = inputData.convertTarget.count
        let indexMap = LatticeDualIndexMap(inputData)
        let latticeIndices = indexMap.indices(inputCount: inputCount, surfaceCount: surfaceCount)
        var rawNodes: [[LatticeNode]] = []
        rawNodes.reserveCapacity(latticeIndices.count)
        for index in latticeIndices {
            let cell = lattice[index: index]
            if !cell.inputIndexedNodes.isEmpty {
                rawNodes.append(cell.inputIndexedNodes)
            }
            if !cell.surfaceIndexedNodes.isEmpty {
                rawNodes.append(cell.surfaceIndexedNodes)
            }
        }
        self.init(
            inputCount: inputCount,
            surfaceCount: surfaceCount,
            indexMap: indexMap,
            latticeIndices: latticeIndices,
            rawNodes: rawNodes
        )
    }

    func makeRawNodes() -> [[LatticeNode]] {''',
    ),
    (
        '''        let result: LatticeNode = LatticeNode.EOSNode
        for (isHead, nodeArray) in lattice.indexedNodes(indices: latticeIndices) {
            // それぞれのnodeに対して
            for node in nodeArray {
                if node.prevs.isEmpty {
                    continue
                }
                if self.dicdataStore.shouldBeRemoved(data: node.data) {
                    continue
                }
                // 生起確率を取得する。
                let wValue: PValue = node.data.value()
                if isHead {
                    // valuesを更新する
                    node.values = node.prevs.map {$0.totalValue + wValue + self.dicdataStore.getCCValue($0.data.rcid, node.data.lcid)}
                } else {
                    // valuesを更新する
                    node.values = node.prevs.map {$0.totalValue + wValue}
                }
                // 後続ノードのindex（正規化する）
                let nextIndex = indexMap.dualIndex(for: node.range.endIndex)
                // 文字数がcountと等しい場合登録する
                if nextIndex.surfaceIndex == surfaceCount {
                    self.updateResultNode(with: node, resultNode: result)
                } else {
                    self.updateNextNodes(with: node, nextNodes: lattice[index: nextIndex], nBest: N_best)
                }
            }
        }
        return result''',
        '''        let result: LatticeNode = LatticeNode.EOSNode
        var activeNodesByIndex: [LatticeDualIndexMap.DualIndex: [LatticeNode]] = [:]
        activeNodesByIndex.reserveCapacity(latticeIndices.count)
        func activeNodes(at index: LatticeDualIndexMap.DualIndex) -> [LatticeNode] {
            if let cached = activeNodesByIndex[index] {
                return cached
            }
            let filtered = lattice[index: index].filter {
                !self.dicdataStore.shouldBeRemoved(data: $0.data)
            }
            activeNodesByIndex[index] = filtered
            return filtered
        }
        for index in latticeIndices {
            let isHead = index.inputIndex == 0 && index.surfaceIndex == 0
            let nodeArray = activeNodes(at: index)
            // それぞれのnodeに対して
            for node in nodeArray {
                if node.prevs.isEmpty {
                    continue
                }
                // 生起確率を取得する。
                let wValue: PValue = node.data.value()
                if isHead {
                    // valuesを更新する
                    node.values = node.prevs.map {$0.totalValue + wValue + self.dicdataStore.getCCValue($0.data.rcid, node.data.lcid)}
                } else {
                    // valuesを更新する
                    node.values = node.prevs.map {$0.totalValue + wValue}
                }
                // 後続ノードのindex（正規化する）
                let nextIndex = indexMap.dualIndex(for: node.range.endIndex)
                // 文字数がcountと等しい場合登録する
                if nextIndex.surfaceIndex == surfaceCount {
                    self.updateResultNode(with: node, resultNode: result)
                } else {
                    self.updateNextNodes(
                        with: node,
                        nextNodes: activeNodes(at: nextIndex),
                        nBest: N_best,
                        filterRemoved: false
                    )
                }
            }
        }
        if N_best <= 2, result.prevs.count > 24 {
            result.prevs = self.boundedZenzaiResultPrevs(result.prevs)
        }
        return result''',
    ),
    (
        '''    func updateNextNodes(with node: LatticeNode, nextNodes: some Sequence<LatticeNode>, nBest: Int) {
        let ccLatter = self.dicdataStore.getCCLatter(node.data.rcid)
        for nextnode in nextNodes {
            if self.dicdataStore.shouldBeRemoved(data: nextnode.data) {
                continue
            }
            // クラスの連続確率を計算する。
            let ccValue: PValue = ccLatter.get(nextnode.data.lcid)
            // nodeの持っている全てのprevnodeに対して
            for (index, value) in node.values.enumerated() {
                let newValue: PValue = ccValue + value
                // 追加すべきindexを取得する
                let lastindex: Int = (nextnode.prevs.lastIndex(where: {$0.totalValue >= newValue}) ?? -1) + 1
                if lastindex == nBest {
                    continue
                }''',
        '''    func updateNextNodes(
        with node: LatticeNode,
        nextNodes: some Sequence<LatticeNode>,
        nBest: Int,
        filterRemoved: Bool = true
    ) {
        guard nBest > 0 else {
            return
        }
        let ccLatter = self.dicdataStore.getCCLatter(node.data.rcid)
        for nextnode in nextNodes {
            if filterRemoved && self.dicdataStore.shouldBeRemoved(data: nextnode.data) {
                continue
            }
            // クラスの連続確率を計算する。
            let ccValue: PValue = ccLatter.get(nextnode.data.lcid)
            // nodeの持っている全てのprevnodeに対して
            for (index, value) in node.values.enumerated() {
                let newValue: PValue = ccValue + value
                if nextnode.prevs.count >= nBest, nextnode.prevs[nBest - 1].totalValue >= newValue {
                    break
                }
                // 追加すべきindexを取得する
                let lastindex: Int = (nextnode.prevs.lastIndex(where: {$0.totalValue >= newValue}) ?? -1) + 1
                if lastindex == nBest {
                    break
                }''',
    ),
)


def patch_full_input_processing(text):
    """Add reusable lattice seed init, pre-filtered traversal, and N-best early break."""
    for anchor, replacement in FULL_INPUT_PROCESSING_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


REGISTERED_NODE_REPLACEMENTS = (
    (
        '''    case node(data: DicdataElement, prev: RegisteredNode?, totalValue: PValue, range: Lattice.LatticeRange)

    /// このノードが保持する辞書データ
    var data: DicdataElement {
        _read {
            switch self {
            case .node(let data, _, _, _):
                yield data
            }
        }
    }

    /// 1つ前のノードのデータ
    var prev: RegisteredNode? {
        _read {
            switch self {
            case .node(_, let prev, _, _):
                yield prev
            }
        }
    }

    /// 始点からこのノードまでのコスト
    var totalValue: PValue {
        switch self {
        case .node(_, _, let totalValue, _):
            return totalValue
        }
    }

    /// `composingText`の`input`で対応する範囲
    var range: Lattice.LatticeRange {
        switch self {
        case .node(_, _, _, let range):
            return range
        }
    }

    init(data: DicdataElement, registered: RegisteredNode?, totalValue: PValue, range: Lattice.LatticeRange) {
        self = .node(data: data, prev: registered, totalValue: totalValue, range: range)
    }''',
        '''    case node(
        data: DicdataElement,
        prev: RegisteredNode?,
        totalValue: PValue,
        range: Lattice.LatticeRange,
        candidateUTF8Count: UInt16,
        constraintMatchedUTF8Count: UInt16
    )

    /// このノードが保持する辞書データ
    var data: DicdataElement {
        _read {
            switch self {
            case .node(let data, _, _, _, _, _):
                yield data
            }
        }
    }

    /// 1つ前のノードのデータ
    var prev: RegisteredNode? {
        _read {
            switch self {
            case .node(_, let prev, _, _, _, _):
                yield prev
            }
        }
    }

    /// 始点からこのノードまでのコスト
    var totalValue: PValue {
        switch self {
        case .node(_, _, let totalValue, _, _, _):
            return totalValue
        }
    }

    /// `composingText`の`input`で対応する範囲
    var range: Lattice.LatticeRange {
        switch self {
        case .node(_, _, _, let range, _, _):
            return range
        }
    }

    var candidateUTF8Count: UInt16 {
        switch self {
        case .node(_, _, _, _, let candidateUTF8Count, _):
            return candidateUTF8Count
        }
    }

    var constraintMatchedUTF8Count: UInt16 {
        switch self {
        case .node(_, _, _, _, _, let constraintMatchedUTF8Count):
            return constraintMatchedUTF8Count
        }
    }

    init(
        data: DicdataElement,
        registered: RegisteredNode?,
        totalValue: PValue,
        range: Lattice.LatticeRange,
        constraintState: (matched: Int, total: Int)? = nil
    ) {
        let candidateUTF8Count = constraintState?.total
            ?? ((registered.map { Int($0.candidateUTF8Count) } ?? 0) + data.word.utf8.count)
        self = .node(
            data: data,
            prev: registered,
            totalValue: totalValue,
            range: range,
            candidateUTF8Count: UInt16(clamping: candidateUTF8Count),
            constraintMatchedUTF8Count: UInt16(clamping: constraintState?.matched ?? 0)
        )
    }''',
    ),
)


def patch_registered_node(text):
    """Store cumulative UTF-8 length and constraint match length on RegisteredNode."""
    for anchor, replacement in REGISTERED_NODE_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


LATTICE_NODE_REPLACEMENT = (
    '''    func getRegisteredNode(_ index: Int, value: PValue) -> RegisteredNode {
        RegisteredNode(data: self.data, registered: self.prevs[index], totalValue: value, range: self.range)
    }''',
    '''    func getRegisteredNode(
        _ index: Int,
        value: PValue,
        constraintState: (matched: Int, total: Int)? = nil
    ) -> RegisteredNode {
        RegisteredNode(
            data: self.data,
            registered: self.prevs[index],
            totalValue: value,
            range: self.range,
            constraintState: constraintState
        )
    }''',
)


def patch_lattice_node(text):
    """Forward optional prefix-constraint match state through LatticeNode.getRegisteredNode."""
    _require(text, LATTICE_NODE_REPLACEMENT[0])
    return text.replace(LATTICE_NODE_REPLACEMENT[0], LATTICE_NODE_REPLACEMENT[1], 1)


FULL_INPUT_PROCESSING_WITH_PREFIX_CONSTRAINT_REPLACEMENTS = (
    (
        '''    private func computeMatchedAndTotalLength(prev: RegisteredNode?, currentWord: String, constraintBytes: [UInt8]) -> (matched: Int, total: Int) {
        // collect words from prev chain in forward order
        var words: [String] = []
        var p = prev
        while let node = p {
            if !node.data.word.isEmpty {
                words.append(node.data.word)
            }
            p = node.prev
        }
        words.reverse()
        words.append(currentWord)

        // total = sum of utf8 counts (cheap per word)
        let total = words.reduce(0) { $0 + $1.utf8.count }

        // matched = compare up to first mismatch or until constraint end
        var ci = 0
        if !constraintBytes.isEmpty {
            outer: for w in words {
                if ci >= constraintBytes.count {
                    break
                }
                for b in w.utf8 {
                    if ci >= constraintBytes.count {
                        break outer
                    }
                    if b != constraintBytes[ci] {
                        break outer
                    }
                    ci += 1
                }
            }
        }
        // このprevのutf8カウントはtotalで、そのうちconstraintBytesと一致する部分がci
        return (matched: ci, total: total)
    }''',
        '''    private func computeMatchedAndTotalLength(prev: RegisteredNode?, currentWord: String, constraintBytes: [UInt8]) -> (matched: Int, total: Int) {
        let previousTotal = prev.map { Int($0.candidateUTF8Count) } ?? 0
        var matched = prev.map { Int($0.constraintMatchedUTF8Count) } ?? 0
        let total = previousTotal + currentWord.utf8.count

        // 直前までに不一致がなければ、今回追加する単語だけを照合する。
        guard matched == min(previousTotal, constraintBytes.count) else {
            return (matched, total)
        }
        for byte in currentWord.utf8 {
            guard matched < constraintBytes.count, byte == constraintBytes[matched] else {
                break
            }
            matched += 1
        }
        return (matched, total)
    }''',
    ),
    (
        '''        let result: LatticeNode = LatticeNode.EOSNode
        for (isHead, nodeArray) in lattice.indexedNodes(indices: latticeIndices) {''',
        '''        let result: LatticeNode = LatticeNode.EOSNode
        guard N_best > 0 else {
            return result
        }
        for (isHead, nodeArray) in lattice.indexedNodes(indices: latticeIndices) {''',
    ),
    (
        '''                        let newnode: RegisteredNode = node.getRegisteredNode(index, value: node.values[index])
                        result.prevs.append(newnode)''',
        '''                        let newnode: RegisteredNode = node.getRegisteredNode(
                            index,
                            value: node.values[index],
                            constraintState: mtPerPrev[index]
                        )
                        result.prevs.append(newnode)''',
    ),
    (
        '''                        for (index, value) in node.values.enumerated() {
                            let newValue: PValue = ccValue + value
                            // 追加すべきindexを取得する
                            let lastindex: Int = (nextnode.prevs.lastIndex(where: {$0.totalValue >= newValue}) ?? -1) + 1
                            if lastindex == N_best {
                                continue
                            }''',
        '''                        for (index, value) in node.values.enumerated() {
                            let newValue: PValue = ccValue + value
                            if nextnode.prevs.count >= N_best, nextnode.prevs[N_best - 1].totalValue >= newValue {
                                break
                            }
                            // 追加すべきindexを取得する
                            let lastindex: Int = (nextnode.prevs.lastIndex(where: {$0.totalValue >= newValue}) ?? -1) + 1
                            if lastindex == N_best {
                                break
                            }''',
    ),
    (
        '''                            let newnode: RegisteredNode = node.getRegisteredNode(index, value: newValue)
                            // removeしてからinsertした方が速い (insertはO(N)なので)''',
        '''                            let newnode: RegisteredNode = node.getRegisteredNode(
                                index,
                                value: newValue,
                                constraintState: mtPerPrev[index]
                            )
                            // removeしてからinsertした方が速い (insertはO(N)なので)''',
    ),
)


def patch_full_input_processing_with_prefix_constraint(text):
    """Avoid O(path) constraint scans and add N-best early break in constrained lattice search."""
    for anchor, replacement in FULL_INPUT_PROCESSING_WITH_PREFIX_CONSTRAINT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


ZENZAI_REPLACEMENTS = (
    (
        '''        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode,
        dicdataStoreState: DicdataStoreState
    ) -> (result: LatticeNode, lattice: Lattice, cache: ZenzaiCache) {''',
        '''        versionDependentConfig: ConvertRequestOptions.ZenzaiVersionDependentMode,
        dicdataStoreState: DicdataStoreState,
        reusableLatticeSeed: FullInputLatticeSeed? = nil,
        reusableUnconstrainedResult: LatticeNode? = nil
    ) -> (result: LatticeNode, lattice: Lattice, cache: ZenzaiCache) {''',
    ),
    (
        '''                } else if preprocessedLattice == nil {
                    let (seed, seedLattice) = self.makeFullInputLatticeSeedAndLattice(inputData, needTypoCorrection: false, dicdataStoreState: dicdataStoreState)
                    latticeSeed = seed
                    draftResult = self.kana2lattice_all_from_seed(seed, lattice: seedLattice, N_best: 2)''',
        '''                } else if preprocessedLattice == nil {
                    let seed: FullInputLatticeSeed
                    let seedLattice: Lattice
                    if let reusableLatticeSeed {
                        seed = reusableLatticeSeed
                        seedLattice = reusableLatticeSeed.makeLattice()
                    } else {
                        (seed, seedLattice) = self.makeFullInputLatticeSeedAndLattice(inputData, needTypoCorrection: false, dicdataStoreState: dicdataStoreState)
                    }
                    latticeSeed = seed
                    if let reusableUnconstrainedResult, !reusableUnconstrainedResult.prevs.isEmpty {
                        let draftNode: LatticeNode
                        if reusableUnconstrainedResult.prevs.count > 24 {
                            let bounded = LatticeNode.EOSNode
                            bounded.prevs = self.boundedZenzaiResultPrevs(reusableUnconstrainedResult.prevs)
                            draftNode = bounded
                        } else {
                            draftNode = reusableUnconstrainedResult
                        }
                        draftResult = (result: draftNode, lattice: seedLattice)
                    } else {
                        draftResult = self.kana2lattice_all_from_seed(seed, lattice: seedLattice, N_best: 2)
                    }''',
    ),
    (
        '''                } else if preprocessedLattice == nil {
                    let (seed, seedLattice) = self.makeFullInputLatticeSeedAndLattice(inputData, needTypoCorrection: false, dicdataStoreState: dicdataStoreState)
                    latticeSeed = seed
                    draftResult = self.kana2lattice_all_with_prefix_constraint_from_seed(seed, lattice: seedLattice, N_best: 3, constraint: constraint)''',
        '''                } else if preprocessedLattice == nil {
                    let seed: FullInputLatticeSeed
                    let seedLattice: Lattice
                    if let reusableLatticeSeed {
                        seed = reusableLatticeSeed
                        seedLattice = reusableLatticeSeed.makeLattice()
                    } else {
                        (seed, seedLattice) = self.makeFullInputLatticeSeedAndLattice(inputData, needTypoCorrection: false, dicdataStoreState: dicdataStoreState)
                    }
                    latticeSeed = seed
                    draftResult = self.kana2lattice_all_with_prefix_constraint_from_seed(seed, lattice: seedLattice, N_best: 3, constraint: constraint)''',
    ),
    (
        '''            guard var (index, candidate) = best else {
                debug("best was not found!")
                // Emptyの場合
                // 制約が満たせない場合は無視する
                return (eosNode, lattice, ZenzaiCache(inputData, constraint: PrefixConstraint([]), satisfyingCandidate: nil, lattice: lattice))
            }''',
        '''            guard var (index, candidate) = best else {
                if !constraint.isEmpty, inferenceLimit > 0, let fallback = insertedCandidates.first {
                    let fallbackReview = zenz.candidateEvaluate(
                        convertTarget: inputData.convertTarget,
                        candidates: [fallback.1],
                        requestRichCandidates: requestRichCandidates,
                        prefixConstraint: constraint,
                        personalizationMode: personalizationMode,
                        versionDependentConfig: versionDependentConfig
                    )
                    inferenceLimit -= 1
                    if let resolved = self.acceptWholeResultCandidate(
                        fallbackReview,
                        inputData: inputData,
                        baseCandidate: fallback.1,
                        baseNode: fallback.0,
                        candidates: [fallback.1],
                        draftPrevs: [fallback.0],
                        constraint: constraint,
                        lattice: lattice
                    ) {
                        if resolved.chosen.1.text != fallback.1.text {
                            insertedCandidates.insert(resolved.chosen, at: 0)
                        }
                        return (eosNode, lattice, resolved.cache)
                    }
                }
                debug("best was not found!")
                // Emptyの場合
                // 制約が満たせない場合は無視する
                return (eosNode, lattice, ZenzaiCache(inputData, constraint: PrefixConstraint([]), satisfyingCandidate: nil, lattice: lattice))
            }''',
    ),
    (
        '''                inferenceLimit -= 1
                let nextAction = self.review(''',
        '''                inferenceLimit -= 1
                if let resolved = self.acceptWholeResultCandidate(
                    reviewResult,
                    inputData: inputData,
                    baseCandidate: candidate,
                    baseNode: draftResult.result.prevs[index],
                    candidates: candidates,
                    draftPrevs: draftResult.result.prevs,
                    constraint: constraint,
                    lattice: lattice
                ) {
                    if resolved.chosen.1.text != candidate.text {
                        insertedCandidates.insert(resolved.chosen, at: 0)
                    }
                    return (eosNode, lattice, resolved.cache)
                }
                let nextAction = self.review(''',
    ),
    (
        '''                case .retry(let candidateIndex):
                    index = candidateIndex
                    candidate = candidates[candidateIndex]''',
        '''                case .retry(let candidateIndex):
                    if inferenceLimit == 0, !constraint.hasEOS {
                        // Rank the free suffix against the new prefix before returning.
                        // This uses the existing lattice and runs no further inference.
                        break reviewLoop
                    }
                    index = candidateIndex
                    candidate = candidates[candidateIndex]''',
    ),
    (
        '''    /// リトライの候補に対して恣意的なバリデーションを実施する
    private func heuristicRetryValidation(_ text: String) -> Bool {''',
        '''    func boundedZenzaiResultPrevs(_ prevs: [RegisteredNode], baseLimit: Int = 24, specialLimit: Int = 16) -> [RegisteredNode] {
        guard prevs.count > baseLimit else {
            return prevs
        }
        let sorted = prevs.sorted { $0.totalValue > $1.totalValue }
        var kept = Array(sorted.prefix(baseLimit))
        for node in sorted.dropFirst(baseLimit) {
            var cursor: RegisteredNode? = node
            var hasSpecial = false
            while let current = cursor {
                if !current.data.metadata.isDisjoint(with: [.isFromUserDictionary, .isLearned]) {
                    hasSpecial = true
                    break
                }
                cursor = current.prev
            }
            if hasSpecial {
                kept.append(node)
                if kept.count >= baseLimit + specialLimit {
                    break
                }
            }
        }
        return kept
    }

    private func acceptWholeResultCandidate(
        _ reviewResult: CandidateEvaluationResult,
        inputData: ComposingText,
        baseCandidate: Candidate,
        baseNode: RegisteredNode,
        candidates: [Candidate],
        draftPrevs: [RegisteredNode],
        constraint: Kana2Kanji.PrefixConstraint,
        lattice: Lattice
    ) -> (chosen: (RegisteredNode, Candidate), cache: ZenzaiCache)? {
        guard case .wholeResult(let wholeText) = reviewResult,
              self.heuristicRetryValidation(wholeText),
              constraint.ignoreMemoryAndUserDictionary || !baseCandidate.data.contains(where: {
                  ZenzUserDictionaryProtection.isProtectedUserDictionaryElement($0) && !wholeText.contains($0.word)
              }) else {
            return nil
        }
        let chosen: (RegisteredNode, Candidate)
        if let exactIndex = candidates.firstIndex(where: { $0.text == wholeText && self.heuristicRetryValidation($0.text) }) {
            chosen = (draftPrevs[exactIndex], candidates[exactIndex])
        } else {
            chosen = self.materializeWholeResultCandidate(
                wholeText: wholeText,
                inputData: inputData,
                baseCandidate: baseCandidate,
                baseNode: baseNode,
                lattice: lattice,
                additionalNodes: draftPrevs
            )
        }
        let finalConstraint = PrefixConstraint(
            Array(wholeText.utf8),
            hasEOS: true,
            ignoreMemoryAndUserDictionary: constraint.ignoreMemoryAndUserDictionary
        )
        let cache = ZenzaiCache(inputData, constraint: finalConstraint, satisfyingCandidate: chosen.1, lattice: lattice)
        return (chosen, cache)
    }

    func materializeWholeResultCandidate(
        wholeText: String,
        inputData: ComposingText,
        baseCandidate: Candidate,
        baseNode: RegisteredNode,
        lattice: Lattice,
        additionalNodes: [RegisteredNode]
    ) -> (RegisteredNode, Candidate) {
        let R = Array(inputData.convertTarget.toKatakana())
        let S = Array(wholeText)
        let surfaceCount = inputData.convertTarget.count
        var rootNode = baseNode
        while let prev = rootNode.prev {
            rootNode = prev
        }
        let n = R.count
        let m = S.count
        if n == 0 || m == 0 || n > 128 || m > 128 {
            let fallbackElement = DicdataElement(
                word: wholeText,
                ruby: String(R),
                cid: CIDData.一般名詞.cid,
                mid: MIDData.一般.mid,
                value: baseCandidate.value
            )
            let reg = RegisteredNode(
                data: fallbackElement,
                registered: rootNode,
                totalValue: baseCandidate.value,
                range: .surface(from: 0, to: surfaceCount)
            )
            return (reg, self.processClauseCandidate(reg.getCandidateData()))
        }

        let inputToSurface = inputData.inputIndexToSurfaceIndexMap()
        func mapSurfaceRange(_ range: Lattice.LatticeRange) -> (Int, Int)? {
            let rawStart: Int
            let rawEnd: Int
            switch range {
            case .surface(let from, let to):
                rawStart = from
                rawEnd = to
            case .input(let from, let to):
                guard let mappedStart = inputToSurface[from], let mappedEnd = inputToSurface[to] else {
                    return nil
                }
                rawStart = mappedStart
                rawEnd = mappedEnd
            }
            let r1 = (n == surfaceCount) ? rawStart : min(n, max(0, rawStart))
            let r2 = (n == surfaceCount) ? rawEnd : min(n, max(0, rawEnd))
            guard r1 >= 0, r2 > r1, r2 <= n else {
                return nil
            }
            return (r1, r2)
        }

        var sStarts: [Character: [Int]] = [:]
        sStarts.reserveCapacity(m)
        for (idx, ch) in S.enumerated() {
            sStarts[ch, default: []].append(idx)
        }

        struct MatchKey: Hashable {
            let r1: Int
            let r2: Int
            let s1: Int
            let s2: Int
        }
        struct MatchEdge {
            let r1: Int
            let s1: Int
            let elem: DicdataElement
            let score: Float
        }

        let stride = m + 1
        var edgesByEnd = [[MatchEdge]](repeating: [], count: (n + 1) * stride)
        var bestEdgeIndex: [MatchKey: Int] = [:]

        func recordElement(_ elem: DicdataElement, range: Lattice.LatticeRange) {
            guard !elem.word.isEmpty, let (r1, r2) = mapSurfaceRange(range) else {
                return
            }
            let w = Array(elem.word)
            guard let first = w.first, let starts = sStarts[first] else {
                return
            }
            var score = Float(elem.value())
            if !elem.metadata.isDisjoint(with: [.isFromUserDictionary, .isLearned]) {
                score += 20.0
            }
            for s1 in starts {
                let s2 = s1 + w.count
                guard s2 <= m, S[s1 ..< s2].elementsEqual(w) else {
                    continue
                }
                let bucket = r2 * stride + s2
                let key = MatchKey(r1: r1, r2: r2, s1: s1, s2: s2)
                if let existingIdx = bestEdgeIndex[key] {
                    if score > edgesByEnd[bucket][existingIdx].score {
                        edgesByEnd[bucket][existingIdx] = MatchEdge(r1: r1, s1: s1, elem: elem, score: score)
                    }
                } else {
                    bestEdgeIndex[key] = edgesByEnd[bucket].count
                    edgesByEnd[bucket].append(MatchEdge(r1: r1, s1: s1, elem: elem, score: score))
                }
            }
        }

        for nodeArray in lattice {
            for node in nodeArray {
                if !self.dicdataStore.shouldBeRemoved(data: node.data) {
                    recordElement(node.data, range: node.range)
                }
            }
        }
        var walk: RegisteredNode? = baseNode
        while let cur = walk {
            recordElement(cur.data, range: cur.range)
            walk = cur.prev
        }
        for altNode in additionalNodes.prefix(8) {
            var cur: RegisteredNode? = altNode
            while let item = cur {
                recordElement(item.data, range: item.range)
                cur = item.prev
            }
        }
        for r in 0 ..< n {
            for s in 0 ..< m where R[r] == S[s].toKatakana() {
                let key = MatchKey(r1: r, r2: r + 1, s1: s, s2: s + 1)
                let bucket = (r + 1) * stride + (s + 1)
                let kanaElem = DicdataElement(
                    word: String(S[s]),
                    ruby: String(R[r]),
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: -12.0
                )
                let kanaScore: Float = -12.0
                if let existingIdx = bestEdgeIndex[key] {
                    if kanaScore > edgesByEnd[bucket][existingIdx].score {
                        edgesByEnd[bucket][existingIdx] = MatchEdge(r1: r, s1: s, elem: kanaElem, score: kanaScore)
                    }
                } else {
                    bestEdgeIndex[key] = edgesByEnd[bucket].count
                    edgesByEnd[bucket].append(MatchEdge(r1: r, s1: s, elem: kanaElem, score: kanaScore))
                }
            }
        }

        let negInf: Float = -1e30
        let totalCells = (n + 1) * stride
        var closedScore = [Float](repeating: negInf, count: totalCells)
        var closedPrevR = [Int](repeating: -1, count: totalCells)
        var closedPrevS = [Int](repeating: -1, count: totalCells)
        var closedElem = [DicdataElement?](repeating: nil, count: totalCells)

        var gapRScore = [Float](repeating: negInf, count: totalCells)
        var gapRStartR = [Int](repeating: -1, count: totalCells)
        var gapRStartS = [Int](repeating: -1, count: totalCells)

        var gapSScore = [Float](repeating: negInf, count: totalCells)
        var gapSStartR = [Int](repeating: -1, count: totalCells)
        var gapSStartS = [Int](repeating: -1, count: totalCells)

        var gapBothScore = [Float](repeating: negInf, count: totalCells)
        var gapBothStartR = [Int](repeating: -1, count: totalCells)
        var gapBothStartS = [Int](repeating: -1, count: totalCells)

        closedScore[0] = 0
        for r in 0 ... n {
            for s in 0 ... m {
                let idx = r * stride + s
                if r > 0 {
                    let up = (r - 1) * stride + s
                    if closedScore[up] > -1e29 {
                        let cand = closedScore[up] - 24.0
                        if cand > gapRScore[idx] {
                            gapRScore[idx] = cand
                            gapRStartR[idx] = r - 1
                            gapRStartS[idx] = s
                        }
                    }
                    if gapRScore[up] > -1e29 {
                        let cand = gapRScore[up] - 2.0
                        if cand > gapRScore[idx] {
                            gapRScore[idx] = cand
                            gapRStartR[idx] = gapRStartR[up]
                            gapRStartS[idx] = gapRStartS[up]
                        }
                    }
                    if gapSScore[up] > -1e29 {
                        let cand = gapSScore[up] - 2.0
                        if cand > gapBothScore[idx] {
                            gapBothScore[idx] = cand
                            gapBothStartR[idx] = gapSStartR[up]
                            gapBothStartS[idx] = gapSStartS[up]
                        }
                    }
                    if gapBothScore[up] > -1e29 {
                        let cand = gapBothScore[up] - 2.0
                        if cand > gapBothScore[idx] {
                            gapBothScore[idx] = cand
                            gapBothStartR[idx] = gapBothStartR[up]
                            gapBothStartS[idx] = gapBothStartS[up]
                        }
                    }
                }
                if s > 0 {
                    let left = r * stride + (s - 1)
                    if closedScore[left] > -1e29 {
                        let cand = closedScore[left] - 24.0
                        if cand > gapSScore[idx] {
                            gapSScore[idx] = cand
                            gapSStartR[idx] = r
                            gapSStartS[idx] = s - 1
                        }
                    }
                    if gapSScore[left] > -1e29 {
                        let cand = gapSScore[left] - 2.0
                        if cand > gapSScore[idx] {
                            gapSScore[idx] = cand
                            gapSStartR[idx] = gapSStartR[left]
                            gapSStartS[idx] = gapSStartS[left]
                        }
                    }
                    if gapRScore[left] > -1e29 {
                        let cand = gapRScore[left] - 2.0
                        if cand > gapBothScore[idx] {
                            gapBothScore[idx] = cand
                            gapBothStartR[idx] = gapRStartR[left]
                            gapBothStartS[idx] = gapRStartS[left]
                        }
                    }
                    if gapBothScore[left] > -1e29 {
                        let cand = gapBothScore[left] - 2.0
                        if cand > gapBothScore[idx] {
                            gapBothScore[idx] = cand
                            gapBothStartR[idx] = gapBothStartR[left]
                            gapBothStartS[idx] = gapBothStartS[left]
                        }
                    }
                }
                if gapBothScore[idx] > closedScore[idx] {
                    closedScore[idx] = gapBothScore[idx]
                    closedPrevR[idx] = gapBothStartR[idx]
                    closedPrevS[idx] = gapBothStartS[idx]
                    closedElem[idx] = nil
                }
                for edge in edgesByEnd[idx] {
                    let prevIdx = edge.r1 * stride + edge.s1
                    guard closedScore[prevIdx] > -1e29 else {
                        continue
                    }
                    let cand = closedScore[prevIdx] + edge.score
                    if cand > closedScore[idx] {
                        closedScore[idx] = cand
                        closedPrevR[idx] = edge.r1
                        closedPrevS[idx] = edge.s1
                        closedElem[idx] = edge.elem
                    }
                }
            }
        }

        struct Piece {
            var r1: Int
            var r2: Int
            var s1: Int
            var s2: Int
            var elem: DicdataElement?
        }
        var reversedPieces: [Piece] = []
        var curR = n
        var curS = m
        while curR > 0 || curS > 0 {
            let idx = curR * stride + curS
            let pr = closedPrevR[idx]
            let ps = closedPrevS[idx]
            guard pr >= 0, ps >= 0 else {
                reversedPieces = [Piece(r1: 0, r2: n, s1: 0, s2: m, elem: nil)]
                break
            }
            reversedPieces.append(Piece(r1: pr, r2: curR, s1: ps, s2: curS, elem: closedElem[idx]))
            curR = pr
            curS = ps
        }
        var merged: [Piece] = []
        for piece in reversedPieces.reversed() {
            if piece.elem == nil, var last = merged.last, last.elem == nil {
                last.r2 = piece.r2
                last.s2 = piece.s2
                merged[merged.count - 1] = last
            } else {
                merged.append(piece)
            }
        }
        var reg = rootNode
        for piece in merged {
            let elem: DicdataElement
            if let existing = piece.elem {
                elem = existing
            } else {
                let word = String(S[piece.s1 ..< piece.s2])
                let ruby = String(R[piece.r1 ..< piece.r2])
                let penalty: PValue = -22.0 - 2.0 * PValue((piece.r2 - piece.r1) + (piece.s2 - piece.s1))
                elem = DicdataElement(
                    word: word,
                    ruby: ruby,
                    cid: CIDData.一般名詞.cid,
                    mid: MIDData.一般.mid,
                    value: penalty
                )
            }
            let sStart = piece.r1 == 0 ? 0 : min(surfaceCount, piece.r1)
            let sEndRaw = piece.r2 == n ? surfaceCount : min(surfaceCount, piece.r2)
            let sEnd = max(sStart, sEndRaw)
            let cc = self.dicdataStore.getCCValue(reg.data.rcid, elem.lcid)
            let totalVal = reg.totalValue + elem.value() + cc
            reg = RegisteredNode(
                data: elem,
                registered: reg,
                totalValue: totalVal,
                range: .surface(from: sStart, to: sEnd)
            )
        }
        return (reg, self.processClauseCandidate(reg.getCandidateData()))
    }

    /// リトライの候補に対して恣意的なバリデーションを実施する
    func heuristicRetryValidation(_ text: String) -> Bool {''',
    ),
)


def patch_zenzai(text):
    """Reuse the dictionary lattice and rank the last partially constrained draft."""
    for anchor, replacement in ZENZAI_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text

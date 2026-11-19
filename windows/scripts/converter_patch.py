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
        'let logicalCores = max(1, ProcessInfo.processInfo.processorCount)\n'
        '        let decodeThreads = logicalCores <= 1 ? 1 : min(6, max(2, logicalCores / 2))\n'
        '        let batchThreads = logicalCores <= 1 ? 1 : min(12, max(decodeThreads, logicalCores - 2))\n'
        '        ctx_params.n_threads = Int32(decodeThreads)\n'
        '        ctx_params.n_threads_batch = Int32(batchThreads)\n'
        '        ctx_params.n_batch = 1024\n'
        '        ctx_params.n_ubatch = 512\n'
        '        ctx_params.n_outputs_max = 256\n'
        '        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED\n'
        '        ctx_params.no_perf = true',
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
        '        let logicalCores = max(1, ProcessInfo.processInfo.processorCount)\n'
        '        let decodeThreads = Int32(logicalCores <= 1 ? 1 : min(6, max(2, logicalCores / 2)))\n'
        '        let batchThreads = Int32(logicalCores <= 1 ? 1 : min(12, max(Int(decodeThreads), logicalCores - 2)))\n'
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
                    out.append(contentsOf: digits[digitIndex].utf8)
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
                current.append(contentsOf: digits[consumed].utf8)
            }
        }
        return current
    }

    public static func restore(_ text: String, digits: [String]) -> String {
        guard !digits.isEmpty, text.contains("nam") else {
            return text
        }
        return String(decoding: restoreBytes(Array(text.utf8), digits: digits), as: UTF8.self)
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
    return text


def patch_lattice(text):
    """Keep LatticeDualIndexMap safe and deterministic across composition separators."""
    for anchor, replacement in LATTICE_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement)
    return text


def patch_converter_api(text):
    """Extend KanaKanjiConverter API with session-scoped prediction, cache sync, and exact lookup."""
    _require(text, EXACT_KEYBOARD_LOOKUP_ANCHOR)
    text = text.replace(EXACT_KEYBOARD_LOOKUP_ANCHOR, EXACT_KEYBOARD_LOOKUP + EXACT_KEYBOARD_LOOKUP_ANCHOR, 1)
    for anchor, replacement in CONVERTER_API_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
    return text


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
        if let candidateMask {
            if candidateMask.rawDigits.count < inputMask.rawDigits.count {
                evaluationDigits = candidateMask.rawDigits + Array(inputMask.rawDigits.dropFirst(candidateMask.rawDigits.count))
            } else {
                evaluationDigits = candidateMask.rawDigits
            }
        } else {
            evaluationDigits = []
        }

        let candidateTokens = context.encode(evaluationCandidateText, addBOS: false, addEOS: false)
        let addressedTokens: [llama_token]
        if prevPrompt == promptTokens, !requestRichCandidates {
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
            addressedTokens = context.encode(maskedPrefix, addBOS: false, addEOS: false)
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
        let isLearnedToken: [(isLearned: Bool, priority: Float)] = Array(repeating: (false, 0), count: promptTokens.count) + candidate.data.flatMap { element in
            let tokenWord: String
            if hasInputDigits, remainingMaskRuns > 0 {
                let maskedElement = ZenzDigitMasking.mask(element.word, maxRuns: remainingMaskRuns)
                remainingMaskRuns -= maskedElement.rawDigits.count
                tokenWord = maskedElement.text
            } else {
                tokenWord = element.word
            }
            return Array(repeating: (element.metadata.contains(.isLearned), logf(self.learningPriority(data: element))), count: context.encode(tokenWord, addBOS: false).count)
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
            return ZenzDigitMasking.restore(String(string.dropFirst(normalizedPrompt.count)), digits: evaluationDigits)
        }

        @inline(__always)
        func stabilizedLogSumExp(startIndex: Int, endIndex: Int, maxRawLogit: Float) -> Float {
            var sumexp: Float = 0
            for index in startIndex ..< endIndex {
                sumexp += expf(logits[index] - maxRawLogit)
            }
            return maxRawLogit + logf(sumexp)
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
                if maxItem.token != tokenID {
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
                } else if !tokenHeap.isEmpty {
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

                if maxToken != tokenID {
                    if maxToken == context.eosToken {
                        return .wholeResult(decodeWholeResult(upTo: i))
                    } else {
                        let learned = i < isLearnedToken.count ? isLearnedToken[i] : (isLearned: false, priority: Float(0))
                        let preferLearnedToken = learned.isLearned && logits[startIndex + Int(tokenID)] + learned.priority > maxRawLogit
                        if !preferLearnedToken {
                            let constraint = restoreConstraintBytes(
                                decodePrefixBytes(upTo: i) + context.tokenToPiece(token: maxToken).map(UInt8.init),
                                completePartialTrailingNam: true
                            )
                            return .fixRequired(prefixConstraint: constraint)
                        }
                    }
                } else if var heap = richHeap, !heap.isEmpty {
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

                let logsumexp = stabilizedLogSumExp(startIndex: startIndex, endIndex: endIndex, maxRawLogit: maxRawLogit)
                score += maxRawLogit - logsumexp
            }
        }'''


def patch_zenz_evaluator(text):
    """Mask/restore digit runs with `nam`, optimize argmax, and delay softmax past early exits."""
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


def patch_dicdata_store(text):
    """Handle zero-hint CSV newlines and expose smaller prediction lookup budgets."""
    _require(text, DICDATA_ZERO_HINT_CRLF_ANCHOR)
    text = text.replace(DICDATA_ZERO_HINT_CRLF_ANCHOR, DICDATA_ZERO_HINT_CRLF_REPLACEMENT, 1)
    for anchor, replacement in DICDATA_PREDICTION_LIMIT_REPLACEMENTS:
        _require(text, anchor)
        text = text.replace(anchor, replacement, 1)
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

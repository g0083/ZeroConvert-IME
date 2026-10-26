#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <cstdint>
#include <atomic>
#include <functional>

namespace azookey {

struct LexicalSegment {
    std::string word;
    std::string ruby;
    float cost;
    int lcid;
    int rcid;
    int mid;
    int correctionKind = 0;
    int originalStart = -1;
    int originalEnd = -1;
    std::string lexicalRuby;
};

struct DicdataElement {
    std::string word;
    std::string ruby;
    float cost;
    int lcid;
    int rcid;
    int mid;
    std::vector<LexicalSegment> segments;
    int correctionKind = 0;
};

class LOUDS {
public:
    LOUDS(std::vector<uint64_t> bits, std::vector<uint8_t> node2id);
    ~LOUDS() = default;

    int searchNodeIndex(const std::vector<uint8_t>& charIds) const;
    int stepNodeIndex(int parentNodeIndex, uint8_t charId) const {
        return searchCharNodeIndex(parentNodeIndex, charId);
    }
    void prefixNodeIndices(int nodeIndex, int depth, int maxDepth, int maxCount, std::vector<int>& out) const;

private:
    std::vector<uint64_t> m_bits;
    std::vector<uint8_t> m_node2id;
    std::vector<uint32_t> m_flatChar2nodeIndices;
    std::vector<uint32_t> m_flatChar2nodeIndicesIndex;
    std::vector<uint32_t> m_rankLarge;

    void childNodeIndices(int parentNodeIndex, int& start, int& end) const;
    int searchCharNodeIndex(int parentNodeIndex, uint8_t charId) const;
};

struct SparseCBRow {
    float defaultVal = 1000.0f;
    std::vector<std::pair<uint16_t, float>> entries;
};

class AzooKeyDictionary {
public:
    AzooKeyDictionary();
    ~AzooKeyDictionary();

    bool init(const std::string& dictionaryDir);
    // abortFlag: when non-null, checked during the lattice build / Viterbi
    // search so that a superseded lookup can be interrupted early.
    std::vector<DicdataElement> lookup(const std::string& katakanaRuby, bool prefixSearch = true, int maxCount = 30,
                                        const std::atomic<bool>* abortFlag = nullptr,
                                        const std::function<bool()>& cancelled = {},
                                        const std::vector<int>& keyboardSurfaceEnds = {},
                                        const std::vector<DicdataElement>& userEntries = {});

private:
    std::string m_dictDir;
    std::unordered_map<char32_t, uint8_t> m_char2id;
    std::unordered_map<std::string, std::shared_ptr<LOUDS>> m_loudsCache;
    std::unordered_map<std::string, std::vector<uint8_t>> m_txt3Cache;
    std::vector<SparseCBRow> m_cbRows;
    std::vector<float> m_mmData;

    std::shared_ptr<LOUDS> loadLOUDS(const std::string& identifier);
    const std::vector<uint8_t>* loadTxt3Data(const std::string& identifier, int shard);
    std::vector<DicdataElement> parseLoudstxt3(const std::string& identifier, int shard, const std::vector<int>& localIndices);
    float getCCValue(int formerRcid, int latterLcid) const;
    float getMMValue(int formerMid, int latterMid) const;
};

} // namespace azookey

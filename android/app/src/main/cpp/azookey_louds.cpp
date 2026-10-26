#include "azookey_louds.h"
#include <fstream>
#include <algorithm>
#include <cstring>
#include <iterator>
#include <unordered_set>
#include <android/log.h>

#define LOG_TAG "AzooKeyLOUDS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace azookey {

#include "keyboard_typo_dictionary.inc"

// AzooKeyKanaKanjiConverter's CIDData/MIDData and DicdataElement.BOSData/EOSData.
static constexpr int BOS_CID = 0;
static constexpr int EOS_CID = 1316;
static constexpr int BOUNDARY_MID = 500;
// DicdataStore.maxlength limits each dictionary word, not the entire composition.
static constexpr int MAX_WORD_SPAN = 20;
static constexpr int MAX_COMPOSITION_LENGTH = 512;

static std::string char32ToUtf8(char32_t cp) {
    std::string out;
    if (cp < 0x80) {
        out.push_back((char)cp);
    } else if (cp < 0x800) {
        out.push_back((char)(0xC0 | (cp >> 6)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back((char)(0xE0 | (cp >> 12)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else {
        out.push_back((char)(0xF0 | (cp >> 18)));
        out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    }
    return out;
}

static inline int countLeadingZeros64(uint64_t val) {
    if (val == 0) return 64;
    return __builtin_clzll(val);
}

static inline int countPop64(uint64_t val) {
    return __builtin_popcountll(val);
}

// UTF-8 string to std::vector<char32_t>
static std::vector<char32_t> utf8ToUtf32(const std::string& str) {
    std::vector<char32_t> result;
    size_t i = 0;
    while (i < str.size()) {
        unsigned char c = (unsigned char)str[i];
        char32_t code = 0;
        int len = 0;
        if (c < 0x80) {
            code = c;
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            code = c & 0x1F;
            len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            code = c & 0x0F;
            len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            code = c & 0x07;
            len = 4;
        } else {
            i++;
            continue;
        }
        if (i + len > str.size()) break;
        for (int j = 1; j < len; j++) {
            code = (code << 6) | ((unsigned char)str[i + j] & 0x3F);
        }
        result.push_back(code);
        i += len;
    }
    return result;
}

// LOUDS implementation
LOUDS::LOUDS(std::vector<uint64_t> bits, std::vector<uint8_t> node2id)
    : m_bits(std::move(bits)), m_node2id(std::move(node2id)) {

    m_flatChar2nodeIndicesIndex.assign(256, 0);
    for (uint8_t val : m_node2id) {
        m_flatChar2nodeIndicesIndex[val]++;
    }
    for (int i = 1; i < 256; i++) {
        m_flatChar2nodeIndicesIndex[i] += m_flatChar2nodeIndicesIndex[i - 1];
    }

    std::vector<uint32_t> curCounts(256, 0);
    m_flatChar2nodeIndices.assign(m_node2id.size(), 0);
    for (size_t i = 0; i < m_node2id.size(); i++) {
        uint8_t val = m_node2id[i];
        uint32_t pos = (val == 0) ? curCounts[val] : (m_flatChar2nodeIndicesIndex[val - 1] + curCounts[val]);
        m_flatChar2nodeIndices[pos] = (uint32_t)i;
        curCounts[val]++;
    }

    m_rankLarge.assign(m_bits.size() + 1, 0);
    for (size_t i = 0; i < m_bits.size(); i++) {
        uint32_t zeros = 64 - countPop64(m_bits[i]);
        m_rankLarge[i + 1] = m_rankLarge[i] + zeros;
    }
}

void LOUDS::childNodeIndices(int parentNodeIndex, int& start, int& end) const {
    int left = parentNodeIndex >> 6;
    int right = (int)m_rankLarge.size() - 1;
    while (left <= right) {
        int mid = (left + right) / 2;
        if ((int)m_rankLarge[mid] >= parentNodeIndex) {
            right = mid - 1;
        } else {
            left = mid + 1;
        }
    }
    if (left >= (int)m_rankLarge.size()) {
        start = end = 0;
        return;
    }
    int i = left - 1;
    uint64_t byte = m_bits[i];
    int zeros_to_find = parentNodeIndex - (int)m_rankLarge[i];
    int k = 0;
    for (int z = 0; z < zeros_to_find; z++) {
        uint64_t inv = ~(byte << k);
        int lz = countLeadingZeros64(inv);
        k = lz + k + 1;
    }
    start = (i << 6) + k - parentNodeIndex + 1;

    if ((int)m_rankLarge[i + 1] == parentNodeIndex) {
        int j = i + 1;
        while (j < (int)m_bits.size() && m_bits[j] == 0xFFFFFFFFFFFFFFFFULL) {
            j++;
        }
        if (j < (int)m_bits.size()) {
            uint64_t byte2 = m_bits[j];
            int lz2 = countLeadingZeros64(~byte2);
            int a = lz2 % 64;
            end = (j << 6) + a - parentNodeIndex + 1;
        } else {
            end = start;
        }
    } else {
        uint64_t inv = ~(byte << k);
        int lz = countLeadingZeros64(inv);
        int a = (lz + k) % 64;
        end = (i << 6) + a - parentNodeIndex + 1;
    }
}

int LOUDS::searchCharNodeIndex(int parentNodeIndex, uint8_t charId) const {
    int childStart, childEnd;
    childNodeIndices(parentNodeIndex, childStart, childEnd);
    if (childStart >= childEnd) return -1;

    int sliceStart = (charId == 0) ? 0 : m_flatChar2nodeIndicesIndex[charId - 1];
    int sliceEnd = m_flatChar2nodeIndicesIndex[charId];

    int left = sliceStart;
    int right = sliceEnd;
    while (left < right) {
        int mid = (left + right) / 2;
        if ((int)m_flatChar2nodeIndices[mid] >= childStart) {
            right = mid;
        } else {
            left = mid + 1;
        }
    }

    if (left < sliceEnd && (int)m_flatChar2nodeIndices[left] >= childStart && (int)m_flatChar2nodeIndices[left] < childEnd) {
        return (int)m_flatChar2nodeIndices[left];
    }
    return -1;
}

int LOUDS::searchNodeIndex(const std::vector<uint8_t>& charIds) const {
    int node = 1;
    for (uint8_t cid : charIds) {
        int next = searchCharNodeIndex(node, cid);
        if (next < 0) return -1;
        node = next;
    }
    return node;
}

void LOUDS::prefixNodeIndices(int nodeIndex, int depth, int maxDepth, int maxCount, std::vector<int>& out) const {
    int childStart, childEnd;
    childNodeIndices(nodeIndex, childStart, childEnd);
    if (childStart >= childEnd) return;

    std::vector<int> currentChildren;
    currentChildren.reserve(childEnd - childStart);
    for (int idx = childStart; idx < childEnd; idx++) {
        if ((int)out.size() >= maxCount) return;
        out.push_back(idx);
        currentChildren.push_back(idx);
    }

    if (depth >= maxDepth) return;

    for (int child : currentChildren) {
        if ((int)out.size() >= maxCount) break;
        prefixNodeIndices(child, depth + 1, maxDepth, maxCount, out);
    }
}

// AzooKeyDictionary implementation
AzooKeyDictionary::AzooKeyDictionary() {}
AzooKeyDictionary::~AzooKeyDictionary() {}

bool AzooKeyDictionary::init(const std::string& dictionaryDir) {
    m_dictDir = dictionaryDir;
    std::string chidPath = dictionaryDir + "/louds/charID.chid";
    std::ifstream chidFile(chidPath, std::ios::binary);
    if (!chidFile.is_open()) {
        LOGE("Failed to open charID.chid at %s", chidPath.c_str());
        return false;
    }
    std::string content((std::istreambuf_iterator<char>(chidFile)), std::istreambuf_iterator<char>());
    std::vector<char32_t> chars = utf8ToUtf32(content);
    m_char2id.clear();
    for (size_t i = 0; i < chars.size(); i++) {
        m_char2id[chars[i]] = (uint8_t)i;
    }
    LOGI("Loaded %zu chars from charID.chid", m_char2id.size());

    // Load mm.binary (502 x 502 float matrix)
    m_mmData.clear();
    std::string mmPath = dictionaryDir + "/mm.binary";
    std::ifstream mmFile(mmPath, std::ios::binary);
    if (mmFile.is_open()) {
        mmFile.seekg(0, std::ios::end);
        size_t mmSize = mmFile.tellg();
        mmFile.seekg(0, std::ios::beg);
        m_mmData.resize(mmSize / sizeof(float));
        mmFile.read(reinterpret_cast<char*>(m_mmData.data()), m_mmData.size() * sizeof(float));
        LOGI("Loaded mm.binary: %zu floats", m_mmData.size());
    }

    // Load cb_packed.binary (sparse POS connection cost table)
    m_cbRows.clear();
    std::string cbPath = dictionaryDir + "/cb_packed.binary";
    std::ifstream cbFile(cbPath, std::ios::binary);
    if (cbFile.is_open()) {
        cbFile.seekg(0, std::ios::end);
        size_t cbSize = cbFile.tellg();
        cbFile.seekg(0, std::ios::beg);
        if (cbSize >= 6) {
            std::vector<uint8_t> buf(cbSize);
            cbFile.read(reinterpret_cast<char*>(buf.data()), cbSize);
            if (std::memcmp(buf.data(), "CBSP", 4) == 0) {
                uint16_t numRows = *reinterpret_cast<const uint16_t*>(&buf[4]);
                m_cbRows.resize(numRows);
                size_t off = 6;
                for (uint16_t r = 0; r < numRows && off + 6 <= cbSize; r++) {
                    float defVal;
                    uint16_t count;
                    std::memcpy(&defVal, &buf[off], sizeof(float));
                    std::memcpy(&count, &buf[off + 4], sizeof(uint16_t));
                    off += 6;
                    m_cbRows[r].defaultVal = defVal;
                    m_cbRows[r].entries.resize(count);
                    for (uint16_t j = 0; j < count && off + 6 <= cbSize; j++) {
                        uint16_t k;
                        float v;
                        std::memcpy(&k, &buf[off], sizeof(uint16_t));
                        std::memcpy(&v, &buf[off + 2], sizeof(float));
                        m_cbRows[r].entries[j] = {k, v};
                        off += 6;
                    }
                }
                LOGI("Loaded cb_packed.binary: %u POS rows", (unsigned)numRows);
            }
        }
    }

    return true;
}

float AzooKeyDictionary::getCCValue(int formerRcid, int latterLcid) const {
    if (formerRcid < 0 || formerRcid >= (int)m_cbRows.size()) return -25.0f;
    const auto& row = m_cbRows[formerRcid];
    const auto& entries = row.entries;
    int left = 0;
    int right = (int)entries.size() - 1;
    while (left <= right) {
        int mid = (left + right) >> 1;
        uint16_t k = entries[mid].first;
        if (k == (uint16_t)latterLcid) {
            return entries[mid].second;
        } else if (k < (uint16_t)latterLcid) {
            left = mid + 1;
        } else {
            right = mid - 1;
        }
    }
    return row.defaultVal;
}

float AzooKeyDictionary::getMMValue(int formerMid, int latterMid) const {
    if (formerMid == 500 || latterMid == 500) return 0.0f;
    int idx = formerMid * 502 + latterMid;
    if (idx >= 0 && idx < (int)m_mmData.size()) {
        return m_mmData[idx];
    }
    return 0.0f;
}

std::shared_ptr<LOUDS> AzooKeyDictionary::loadLOUDS(const std::string& identifier) {
    auto it = m_loudsCache.find(identifier);
    if (it != m_loudsCache.end()) return it->second;

    std::string loudsPath = m_dictDir + "/louds/" + identifier + ".louds";
    std::string charsPath = m_dictDir + "/louds/" + identifier + ".loudschars2";

    std::ifstream loudsFile(loudsPath, std::ios::binary);
    std::ifstream charsFile(charsPath, std::ios::binary);
    if (!loudsFile.is_open() || !charsFile.is_open()) {
        return nullptr;
    }

    loudsFile.seekg(0, std::ios::end);
    size_t loudsSize = loudsFile.tellg();
    loudsFile.seekg(0, std::ios::beg);
    size_t u64Count = loudsSize / 8;
    std::vector<uint64_t> bits(u64Count);
    loudsFile.read(reinterpret_cast<char*>(bits.data()), u64Count * 8);

    charsFile.seekg(0, std::ios::end);
    size_t charsSize = charsFile.tellg();
    charsFile.seekg(0, std::ios::beg);
    std::vector<uint8_t> node2id(charsSize);
    charsFile.read(reinterpret_cast<char*>(node2id.data()), charsSize);

    auto louds = std::make_shared<LOUDS>(std::move(bits), std::move(node2id));
    m_loudsCache[identifier] = louds;
    return louds;
}

const std::vector<uint8_t>* AzooKeyDictionary::loadTxt3Data(const std::string& identifier, int shard) {
    std::string key = identifier + "_" + std::to_string(shard);
    auto it = m_txt3Cache.find(key);
    if (it != m_txt3Cache.end()) return &it->second;

    std::string txt3Path = m_dictDir + "/louds/" + identifier + std::to_string(shard) + ".loudstxt3";
    std::ifstream file(txt3Path, std::ios::binary);
    if (!file.is_open()) return nullptr;

    file.seekg(0, std::ios::end);
    size_t fileSize = file.tellg();
    file.seekg(0, std::ios::beg);
    if (fileSize < 2) return nullptr;

    std::vector<uint8_t> data(fileSize);
    file.read(reinterpret_cast<char*>(data.data()), fileSize);
    auto inserted = m_txt3Cache.emplace(std::move(key), std::move(data));
    return &inserted.first->second;
}

std::vector<DicdataElement> AzooKeyDictionary::parseLoudstxt3(
    const std::string& identifier, int shard, const std::vector<int>& localIndices) {

    std::vector<DicdataElement> results;
    const auto* dataPtr = loadTxt3Data(identifier, shard);
    if (!dataPtr) return results;
    const auto& data = *dataPtr;
    size_t fileSize = data.size();

    uint16_t entryCount = *reinterpret_cast<const uint16_t*>(&data[0]);
    size_t headerSize = 2 + entryCount * 4;

    for (int idx : localIndices) {
        if (idx < 0 || idx >= entryCount) continue;
        size_t offPos = 2 + idx * 4;
        uint32_t start = *reinterpret_cast<const uint32_t*>(&data[offPos]);
        uint32_t end = (idx + 1 < entryCount) ? *reinterpret_cast<const uint32_t*>(&data[offPos + 4]) : (uint32_t)fileSize;
        if (start < headerSize || start >= end || end > fileSize) continue;

        const uint8_t* slice = &data[start];
        size_t sliceLen = end - start;
        if (sliceLen < 2) continue;

        uint16_t rowCount = *reinterpret_cast<const uint16_t*>(&slice[0]);
        if (rowCount == 0) continue;

        size_t textStart = 2 + rowCount * 10;
        if (textStart > sliceLen) continue;

        std::string textPart(reinterpret_cast<const char*>(&slice[textStart]), sliceLen - textStart);
        std::vector<std::string> fields;
        size_t last = 0;
        for (size_t p = 0; p <= textPart.size(); p++) {
            if (p == textPart.size() || textPart[p] == '\t') {
                fields.push_back(textPart.substr(last, p - last));
                last = p + 1;
            }
        }

        std::string ruby = fields.empty() ? "" : fields[0];

        for (uint16_t r = 0; r < rowCount; r++) {
            size_t rowOffset = 2 + r * 10;
            uint16_t lcid;
            uint16_t rcid;
            uint16_t mid;
            float cost;
            std::memcpy(&lcid, &slice[rowOffset], sizeof(uint16_t));
            std::memcpy(&rcid, &slice[rowOffset + 2], sizeof(uint16_t));
            std::memcpy(&mid, &slice[rowOffset + 4], sizeof(uint16_t));
            std::memcpy(&cost, &slice[rowOffset + 6], sizeof(float));

            std::string word = (r + 1 < fields.size() && !fields[r + 1].empty()) ? fields[r + 1] : ruby;
            results.push_back({word, ruby, cost, (int)lcid, (int)rcid, (int)mid});
        }
    }

    return results;
}

std::vector<DicdataElement> AzooKeyDictionary::lookup(const std::string& katakanaRuby, bool prefixSearch, int maxCount,
                                                    const std::atomic<bool>* abortFlag,
                                                    const std::function<bool()>& cancelled,
                                                    const std::vector<int>& keyboardSurfaceEnds,
                                                    const std::vector<DicdataElement>& userEntries) {
    std::vector<DicdataElement> candidates;
    if (katakanaRuby.empty()) return candidates;

    auto aborted = [&]() { return (abortFlag != nullptr && abortFlag->load()) || (cancelled && cancelled()); };
    if (aborted()) return candidates;

    std::vector<char32_t> chars = utf8ToUtf32(katakanaRuby);
    if (chars.empty()) return candidates;

    std::vector<uint8_t> charIds;
    charIds.reserve(chars.size());
    for (char32_t c : chars) {
        auto it = m_char2id.find(c);
        if (it != m_char2id.end()) {
            charIds.push_back(it->second);
        } else {
            charIds.push_back(255); // Swift character2charId's missing-character sentinel.
        }
    }

    const int N = (int)chars.size();
    std::unordered_set<std::string> seenFullWords;

    // 1. Multi-morpheme & Single-word N-Best Viterbi Beam Search across lattice [0..N]
    //    (Matches AzooKeyKanaKanjiConverter LOUDS + cb + mm scoring)
    if (!m_cbRows.empty() && N >= 1 && N <= MAX_COMPOSITION_LENGTH) {
        std::vector<std::vector<std::vector<DicdataElement>>> lattice(
            N, std::vector<std::vector<DicdataElement>>(MAX_WORD_SPAN + 1));

        for (int i = 0; i < N; i++) {
            if (aborted()) return candidates;
            // getWiseDicdata's single-character literal paths retain unknown text
            // while allowing dictionary conversion on either side of it.
            const auto literal = char32ToUtf8(chars[i]);
            if (chars[i] >= U'ァ' && chars[i] <= U'ヶ') {
                lattice[i][1].push_back({char32ToUtf8(chars[i] - 0x60), literal, -13.0f, 1288, 1288, 501});
            }
            lattice[i][1].push_back({literal, literal, -14.0f, 1288, 1288, 501});
            std::string ident = char32ToUtf8(chars[i]);
            auto louds = loadLOUDS(ident);
            if (!louds) continue;

            int node = 1;
            for (int j = i + 1; j <= std::min(N, i + MAX_WORD_SPAN); j++) {
                if (aborted()) return candidates;
                node = louds->stepNodeIndex(node, charIds[j - 1]);
                if (node < 0) break;
                int shard = node >> 11;
                int local = node & 2047;
                auto entries = parseLoudstxt3(ident, shard, {local});
                if (!entries.empty()) {
                    std::sort(entries.begin(), entries.end(), [](const DicdataElement& a, const DicdataElement& b) {
                        return a.cost > b.cost;
                    });
                    if (entries.size() > 24) entries.resize(24);
                    auto& target = lattice[i][j - i];
                    target.insert(target.end(), std::make_move_iterator(entries.begin()), std::make_move_iterator(entries.end()));
                }
            }
        }

        // Registered words participate at every matching internal span, using
        // the same noun IDs and insertion-order values as the Swift server.
        for (const auto& entry : userEntries) {
            if (aborted()) return candidates;
            const auto ruby = utf8ToUtf32(entry.ruby);
            const int length = static_cast<int>(ruby.size());
            if (length < 1 || length > MAX_WORD_SPAN) continue;
            for (int start = 0; start + length <= N; ++start) {
                if (std::equal(ruby.begin(), ruby.end(), chars.begin() + start)) {
                    lattice[start][length].push_back(entry);
                }
            }
        }

        if (keyboardSurfaceEnds.size() == chars.size()) {
            for (int start = 0; start < N; ++start) {
                if (aborted()) return candidates;
                const int end = std::clamp(keyboardSurfaceEnds[start], start, std::min(N, start + MAX_WORD_SPAN));
                if (end <= start) continue;

                // Fixed dictionary entries are exact spans inside a roman-input run.
                for (const auto& entry : keyboardTypoEntries) {
                    const auto ruby = utf8ToUtf32(entry.ruby);
                    const int next = start + static_cast<int>(ruby.size());
                    if (next <= end && std::equal(ruby.begin(), ruby.end(), chars.begin() + start)) {
                        lattice[start][next - start].push_back(entry);
                    }
                }

                // Swift KeyboardTypoCorrection.correctedPrefixes: one rewrite per prefix.
                std::vector<char32_t> output;
                int kind = 0;
                for (int index = start; index < end;) {
                    const auto hira = [](char32_t ch) { return ch >= U'ァ' && ch <= U'ヶ'; };
                    if (kind == 0 && index + 3 < end) {
                        const auto a = chars[index], b = chars[index + 1], c = chars[index + 2], d = chars[index + 3];
                        if (hira(a) && a != U'ッ' && b == U'ッ' && c == U'ッ' && hira(d) && d != U'ッ') {
                            output.insert(output.end(), {a, U'ッ', d}); kind = 1; index += 4;
                        } else if (hira(a) && a != U'ン' && b == U'ン' && c == U'ン' && d != U'ン') {
                            char32_t tail = d;
                            switch (d) {
                                case U'ア': tail = U'ナ'; break; case U'イ': tail = U'ニ'; break;
                                case U'ウ': tail = U'ヌ'; break; case U'エ': tail = U'ネ'; break;
                                case U'オ': tail = U'ノ'; break; default: break;
                            }
                            output.insert(output.end(), {a, U'ン', tail}); kind = 2; index += 4;
                        } else output.push_back(chars[index++]);
                    } else output.push_back(chars[index++]);
                    if (!kind) continue;
                    if (aborted()) return candidates;
                    auto correctedLouds = loadLOUDS(char32ToUtf8(output.front()));
                    if (!correctedLouds) continue;
                    std::vector<uint8_t> ids;
                    for (auto ch : output) {
                        auto found = m_char2id.find(ch);
                        if (found == m_char2id.end()) { ids.clear(); break; }
                        ids.push_back(found->second);
                    }
                    if (ids.empty()) continue;
                    const int node = correctedLouds->searchNodeIndex(ids);
                    if (node < 0) continue;
                    auto entries = parseLoudstxt3(char32ToUtf8(output.front()), node >> 11, {node & 2047});
                    std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) { return a.cost > b.cost; });
                    if (entries.size() > 24) entries.resize(24);
                    for (auto& entry : entries) { entry.correctionKind = kind; lattice[start][index - start].push_back(std::move(entry)); }
                }
            }
        }

        struct ViterbiState {
            float score;
            int rcid;
            int mid;
            std::string surface;
            std::vector<LexicalSegment> segments;
        };

        const int BEAM_WIDTH = 24;
        std::vector<std::vector<ViterbiState>> dp(N + 1);
        dp[0].push_back({0.0f, BOS_CID, BOUNDARY_MID, "", {}});

        for (int i = 0; i < N; i++) {
            if (aborted()) return candidates;
            if (dp[i].empty()) continue;
            for (int j = i + 1; j <= std::min(N, i + MAX_WORD_SPAN); j++) {
                if (aborted()) return candidates;
                const auto& edges = lattice[i][j - i];
                if (edges.empty()) continue;
                auto& nextStates = dp[j];
                for (const auto& st : dp[i]) {
                    for (const auto& e : edges) {
                        float cc = getCCValue(st.rcid, e.lcid);
                        float mm = getMMValue(st.mid, e.mid);
                        float nextScore = st.score + e.cost + cc + mm;
                        auto segments = st.segments;
                        std::string originalRuby;
                        for (int index = i; index < j; ++index) originalRuby += char32ToUtf8(chars[index]);
                        segments.push_back({e.word, originalRuby, e.cost, e.lcid, e.rcid, e.mid,
                                            e.correctionKind, i, j, e.ruby});
                        nextStates.push_back({nextScore, e.rcid, e.mid, st.surface + e.word, std::move(segments)});
                    }
                }
                if ((int)nextStates.size() > BEAM_WIDTH * 3) {
                    std::sort(nextStates.begin(), nextStates.end(), [](const ViterbiState& a, const ViterbiState& b) {
                        return a.score > b.score;
                    });
                    // Deduplicate identical (surface, rcid) keeping highest score
                    std::vector<ViterbiState> pruned;
                    pruned.reserve(BEAM_WIDTH);
                    for (const auto& s : nextStates) {
                        bool dup = false;
                        for (const auto& p : pruned) {
                            if (p.surface == s.surface && p.rcid == s.rcid) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            pruned.push_back(s);
                            if ((int)pruned.size() >= BEAM_WIDTH) break;
                        }
                    }
                    nextStates = std::move(pruned);
                }
            }
            if ((int)dp[i + 1].size() > BEAM_WIDTH) {
                std::sort(dp[i + 1].begin(), dp[i + 1].end(), [](const ViterbiState& a, const ViterbiState& b) {
                    return a.score > b.score;
                });
                std::vector<ViterbiState> pruned;
                pruned.reserve(BEAM_WIDTH);
                for (const auto& s : dp[i + 1]) {
                    bool dup = false;
                    for (const auto& p : pruned) {
                        if (p.surface == s.surface && p.rcid == s.rcid) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        pruned.push_back(s);
                        if ((int)pruned.size() >= BEAM_WIDTH) break;
                    }
                }
                dp[i + 1] = std::move(pruned);
            }
        }

        // Close the sentence with the same EOS entry as the Swift dictionary.
        if (!dp[N].empty()) {
            for (auto& st : dp[N]) {
                st.score += getCCValue(st.rcid, EOS_CID) + getMMValue(st.mid, BOUNDARY_MID);
            }
            std::sort(dp[N].begin(), dp[N].end(), [](const ViterbiState& a, const ViterbiState& b) {
                return a.score > b.score;
            });

            int rank = 0;
            for (const auto& st : dp[N]) {
                if (st.surface.empty()) continue;
                if (seenFullWords.insert(st.surface).second) {
                    // Normalized cost so full-span Viterbi candidates sort in exact Viterbi rank order
                    float normCost = 100.0f - (float)rank * 0.5f;
                    candidates.push_back({st.surface, katakanaRuby, normCost, 0, 0, st.mid, st.segments});
                    rank++;
                    if ((int)candidates.size() >= maxCount) break;
                }
            }
        }

        // Also include any remaining direct full-span single-word entries not already in top Viterbi beam
        if (N <= MAX_WORD_SPAN) for (const auto& e : lattice[0][N]) {
            if ((int)candidates.size() >= maxCount) break;
            if (seenFullWords.insert(e.word).second) {
                candidates.push_back({e.word, katakanaRuby, e.cost, e.lcid, e.rcid, e.mid,
                    {{e.word, katakanaRuby, e.cost, e.lcid, e.rcid, e.mid, e.correctionKind, 0, N, e.ruby}}});
            }
        }
    }

    // 2. Prefix completion search (single-word completions longer than katakanaRuby)
    if (aborted()) return candidates;
    std::string firstIdent = char32ToUtf8(chars[0]);
    auto louds = loadLOUDS(firstIdent);
    if (louds) {
        int exactNode = louds->searchNodeIndex(charIds);
        std::vector<int> prefixNodes;
        if (exactNode > 0) {
            prefixNodes.push_back(exactNode);
        }
        if (prefixSearch && exactNode > 0) {
            louds->prefixNodeIndices(exactNode, 0, 8, maxCount, prefixNodes);
        }
        if (!prefixNodes.empty()) {
            std::unordered_map<int, std::vector<int>> shardMap;
            for (int node : prefixNodes) {
                int shard = node >> 11;
                int local = node & 2047;
                shardMap[shard].push_back(local);
            }
            std::vector<DicdataElement> prefixEntries;
            for (const auto& pair : shardMap) {
                if (aborted()) return candidates;
                auto entries = parseLoudstxt3(firstIdent, pair.first, pair.second);
                prefixEntries.insert(prefixEntries.end(), entries.begin(), entries.end());
            }
            std::sort(prefixEntries.begin(), prefixEntries.end(), [](const DicdataElement& a, const DicdataElement& b) {
                return a.cost > b.cost;
            });
            for (const auto& e : prefixEntries) {
                if ((int)candidates.size() >= maxCount) break;
                if (e.ruby == katakanaRuby) {
                    if (seenFullWords.insert(e.word).second) {
                        candidates.push_back(e);
                    }
                } else {
                    candidates.push_back(e);
                }
            }
        }
    }

    if ((int)candidates.size() > maxCount) {
        candidates.resize(maxCount);
    }

    return candidates;
}

} // namespace azookey

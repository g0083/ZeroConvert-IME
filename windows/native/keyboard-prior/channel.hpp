#pragma once
#include "roman_table.hpp"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <codecvt>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <locale>
#include <map>
#include <string>
#include <vector>

inline std::u32string decode_utf8(const std::string &s) {
  static thread_local std::wstring_convert<std::codecvt_utf8<char32_t>,
                                           char32_t>
      converter;
  return converter.from_bytes(s);
}
inline std::string encode_utf8(const std::u32string &s) {
  static thread_local std::wstring_convert<std::codecvt_utf8<char32_t>,
                                           char32_t>
      converter;
  return converter.to_bytes(s);
}
inline std::u32string hiragana(std::u32string s) {
  for (auto &c : s)
    if (c >= U'ァ' && c <= U'ヶ')
      c -= 96;
  return s;
}
inline std::u32string katakana(std::u32string s) {
  for (auto &c : s)
    if (c >= U'ぁ' && c <= U'ゖ')
      c += 96;
  return s;
}
inline bool kana(const std::u32string &s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー';
  });
}
inline bool kana_or_punct(const std::u32string &s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー' || c == U'、' ||
           c == U'。' || c == U'！' || c == U'？' || c == U'!' || c == U'?';
  });
}
inline std::u32string roman_decode(const std::string &keys) {
  std::u32string result;
  for (size_t i = 0; i < keys.size();) {
    if (i + 1 < keys.size() && keys[i] == keys[i + 1] &&
        std::string("aeioun").find(keys[i]) == std::string::npos) {
      result += U'っ';
      ++i;
      continue;
    }
    bool found = false;
    for (size_t width = std::min(size_t(4), keys.size() - i); width > 0;
         --width) {
      auto at = roman_table.find(keys.substr(i, width));
      if (at != roman_table.end() && kana(at->second)) {
        result += at->second;
        i += width;
        found = true;
        break;
      }
    }
    if (found)
      continue;
    if (keys[i] == 'n' &&
        (i + 1 == keys.size() ||
         std::string("aiueoy").find(keys[i + 1]) == std::string::npos))
      result += U'ん';
    else if (keys[i] == '-')
      result += U'ー';
    else if (keys[i] == '?')
      result += U'？';
    else if (keys[i] == '!')
      result += U'！';
    else if (keys[i] == ',')
      result += U'、';
    else if (keys[i] == '.')
      result += U'。';
    else
      result += char32_t(keys[i]);
    ++i;
  }
  return result;
}
inline std::string kana_to_romaji(const std::u32string &text,
                                  bool kunrei = false) {
  static const std::map<std::u32string, std::string> table_hepburn = {
      {U"きゃ", "kya"}, {U"きゅ", "kyu"}, {U"きょ", "kyo"}, {U"しゃ", "sha"},
      {U"しゅ", "shu"}, {U"しょ", "sho"}, {U"ちゃ", "cha"}, {U"ちゅ", "chu"},
      {U"ちょ", "cho"}, {U"にゃ", "nya"}, {U"にゅ", "nyu"}, {U"にょ", "nyo"},
      {U"ひゃ", "hya"}, {U"ひゅ", "hyu"}, {U"ひょ", "hyo"}, {U"みゃ", "mya"},
      {U"みゅ", "myu"}, {U"みょ", "myo"}, {U"りゃ", "rya"}, {U"りゅ", "ryu"},
      {U"りょ", "ryo"}, {U"ぎゃ", "gya"}, {U"ぎゅ", "gyu"}, {U"ぎょ", "gyo"},
      {U"じゃ", "ja"},  {U"じゅ", "ju"},  {U"じょ", "jo"},  {U"びゃ", "bya"},
      {U"びゅ", "byu"}, {U"びょ", "byo"}, {U"ぴゃ", "pya"}, {U"ぴゅ", "pyu"},
      {U"ぴょ", "pyo"}, {U"あ", "a"},     {U"い", "i"},     {U"う", "u"},
      {U"え", "e"},     {U"お", "o"},     {U"か", "ka"},    {U"き", "ki"},
      {U"く", "ku"},    {U"け", "ke"},    {U"こ", "ko"},    {U"さ", "sa"},
      {U"し", "shi"},   {U"す", "su"},    {U"せ", "se"},    {U"そ", "so"},
      {U"た", "ta"},    {U"ち", "chi"},   {U"つ", "tsu"},   {U"て", "te"},
      {U"と", "to"},    {U"な", "na"},    {U"に", "ni"},    {U"ぬ", "nu"},
      {U"ね", "ne"},    {U"の", "no"},    {U"は", "ha"},    {U"ひ", "hi"},
      {U"ふ", "fu"},    {U"へ", "he"},    {U"ほ", "ho"},    {U"ま", "ma"},
      {U"み", "mi"},    {U"む", "mu"},    {U"め", "me"},    {U"も", "mo"},
      {U"や", "ya"},    {U"ゆ", "yu"},    {U"よ", "yo"},    {U"ら", "ra"},
      {U"り", "ri"},    {U"る", "ru"},    {U"れ", "re"},    {U"ろ", "ro"},
      {U"わ", "wa"},    {U"を", "wo"},    {U"ん", "nn"},    {U"が", "ga"},
      {U"ぎ", "gi"},    {U"ぐ", "gu"},    {U"げ", "ge"},    {U"ご", "go"},
      {U"ざ", "za"},    {U"じ", "ji"},    {U"ず", "zu"},    {U"ぜ", "ze"},
      {U"ぞ", "zo"},    {U"だ", "da"},    {U"ぢ", "di"},    {U"づ", "du"},
      {U"で", "de"},    {U"ど", "do"},    {U"ば", "ba"},    {U"び", "bi"},
      {U"ぶ", "bu"},    {U"べ", "be"},    {U"ぼ", "bo"},    {U"ぱ", "pa"},
      {U"ぴ", "pi"},    {U"ぷ", "pu"},    {U"ぺ", "pe"},    {U"ぽ", "po"},
      {U"ぁ", "la"},    {U"ぃ", "li"},    {U"ぅ", "lu"},    {U"ぇ", "le"},
      {U"ぉ", "lo"},    {U"ゃ", "lya"},   {U"ゅ", "lyu"},   {U"ょ", "lyo"},
      {U"っ", "ltu"},   {U"ー", "-"},     {U"？", "?"},     {U"！", "!"},
      {U"、", ","},     {U"。", "."}};
  static const std::map<std::u32string, std::string> table_kunrei = []() {
    auto m = table_hepburn;
    m[U"し"] = "si";
    m[U"ち"] = "ti";
    m[U"つ"] = "tu";
    m[U"ふ"] = "hu";
    m[U"じ"] = "zi";
    m[U"しゃ"] = "sya";
    m[U"しゅ"] = "syu";
    m[U"しょ"] = "syo";
    m[U"ちゃ"] = "tya";
    m[U"ちゅ"] = "tyu";
    m[U"ちょ"] = "tyo";
    m[U"じゃ"] = "zya";
    m[U"じゅ"] = "zyu";
    m[U"じょ"] = "zyo";
    return m;
  }();
  const auto &table = kunrei ? table_kunrei : table_hepburn;
  const auto h = hiragana(text);
  auto next_rom = [&](size_t idx) -> std::string {
    if (idx + 1 < h.size()) {
      auto at2 = table.find(h.substr(idx, 2));
      if (at2 != table.end())
        return at2->second;
    }
    if (idx < h.size()) {
      auto at1 = table.find(h.substr(idx, 1));
      if (at1 != table.end())
        return at1->second;
      if (h[idx] < 128)
        return std::string(1, char(h[idx]));
    }
    return "";
  };
  std::string out;
  for (size_t i = 0; i < h.size();) {
    if (i + 1 < h.size()) {
      auto at2 = table.find(h.substr(i, 2));
      if (at2 != table.end()) {
        out += at2->second;
        i += 2;
        continue;
      }
    }
    if (h[i] == U'っ' && i + 1 < h.size()) {
      const auto nxt = next_rom(i + 1);
      if (!nxt.empty() && nxt[0] >= 'a' && nxt[0] <= 'z' &&
          std::string("aiueon").find(nxt[0]) == std::string::npos) {
        out += nxt[0];
        ++i;
        continue;
      }
    }
    if (h[i] == U'ん' && i + 1 < h.size()) {
      const auto nxt = next_rom(i + 1);
      if (!nxt.empty() &&
          std::string("aiueony").find(nxt[0]) == std::string::npos) {
        out += 'n';
        ++i;
        continue;
      }
    }
    auto at1 = table.find(h.substr(i, 1));
    if (at1 != table.end())
      out += at1->second;
    else if (h[i] >= U'ａ' && h[i] <= U'ｚ')
      out += char(h[i] - 0xfee0);
    else if (h[i] >= U'Ａ' && h[i] <= U'Ｚ')
      out += char(h[i] - 0xfee0 + 32);
    else if (h[i] < 128)
      out += char(std::tolower(int(h[i])));
    ++i;
  }
  return out;
}
inline std::map<char, std::string> make_neighbors() {
  std::map<char, std::pair<double, double>> positions;
  const std::string rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
  const double offsets[] = {0, .25, .75};
  for (int y = 0; y < 3; ++y)
    for (size_t x = 0; x < rows[y].size(); ++x)
      positions[rows[y][x]] = {x + offsets[y], y};
  std::map<char, std::string> neighbors;
  for (const auto &a : positions)
    for (const auto &b : positions) {
      if (a.first != b.first &&
          std::hypot(a.second.first - b.second.first,
                     a.second.second - b.second.second) <= 1.26)
        neighbors[a.first] += b.first;
    }
  return neighbors;
}

struct DictionaryPath {
  double score;
  std::string surface;
};
class ChannelDictionary {
  std::map<std::u32string, DictionaryPath> entries;
  struct TrieNode {
    bool has_word = false;
    double best_score = -std::numeric_limits<double>::infinity();
    std::string best_surface;
    std::vector<std::pair<char32_t, uint32_t>> children;
  };
  std::vector<TrieNode> trie;

public:
  explicit ChannelDictionary(const std::string &path) {
    std::ifstream source(path, std::ios::binary | std::ios::ate);
    if (!source)
      throw std::runtime_error("dictionary unavailable");
    const auto size = source.tellg();
    if (size < 16 || size > 64 * 1024 * 1024)
      throw std::runtime_error("invalid dictionary size");
    std::vector<char> bytes(static_cast<size_t>(size));
    source.seekg(0);
    source.read(bytes.data(), size);
    auto u32 = [&](size_t at) {
      uint32_t n;
      if (at + 4 > bytes.size())
        throw std::runtime_error("dictionary offset");
      std::memcpy(&n, bytes.data() + at, 4);
      return n;
    };
    if (std::string(bytes.data(), 4) != "AZR2")
      throw std::runtime_error("invalid dictionary magic");
    const auto count = u32(4);
    if (count > (bytes.size() - 12) / 4 || u32(8) != 8 + 4 * (count + 1) ||
        u32(8 + 4 * count) != bytes.size())
      throw std::runtime_error("invalid dictionary header");
    for (uint32_t i = 0; i < count; ++i) {
      const size_t start = u32(8 + 4 * i), end = u32(12 + 4 * i);
      if (start < 8 + 4 * (count + 1) || end < start + 8 || end > bytes.size())
        throw std::runtime_error("invalid dictionary record");
      uint16_t sl, rl;
      float score;
      std::memcpy(&sl, bytes.data() + start, 2);
      std::memcpy(&rl, bytes.data() + start + 2, 2);
      std::memcpy(&score, bytes.data() + start + 4, 4);
      if (start + 8 + sl + rl != end || !std::isfinite(score))
        throw std::runtime_error("invalid dictionary fields");
      const auto reading =
          hiragana(decode_utf8(std::string(bytes.data() + start + 8 + sl, rl)));
      const std::string surface(bytes.data() + start + 8, sl);
      if (!kana(reading) || reading.size() > 40 || surface.empty() ||
          surface.find_first_of("\r\n\t") != std::string::npos)
        continue;
      auto at = entries.find(reading);
      if (at == entries.end() || score > at->second.score ||
          (score == at->second.score && surface > at->second.surface))
        entries[reading] = {score, surface};
    }
    trie.push_back(TrieNode{});
    for (const auto &entry : entries) {
      uint32_t node = 0;
      for (char32_t ch : entry.first) {
        uint32_t next = 0;
        for (const auto &edge : trie[node].children) {
          if (edge.first == ch) {
            next = edge.second;
            break;
          }
        }
        if (next == 0) {
          next = static_cast<uint32_t>(trie.size());
          trie[node].children.push_back({ch, next});
          trie.push_back(TrieNode{});
        }
        node = next;
      }
      if (!trie[node].has_word || entry.second.score > trie[node].best_score ||
          (entry.second.score == trie[node].best_score &&
           entry.second.surface > trie[node].best_surface)) {
        trie[node].has_word = true;
        trie[node].best_score = entry.second.score;
        trie[node].best_surface = entry.second.surface;
      }
    }
  }
  DictionaryPath convert_fast(const std::u32string &reading) const {
    static const std::u32string bound_moras = U"っんーぁぃぅぇぉゃゅょゎ";
    static const std::u32string particles = U"はがをにへでとのもやかねよわな、。！？!?";
    const double missing = -std::numeric_limits<double>::infinity();
    std::vector<double> scores(reading.size() + 1, missing);
    std::vector<size_t> prev_idx(reading.size() + 1, 0);
    std::vector<std::string> piece_surf(reading.size() + 1);
    scores[0] = 0.0;
    for (size_t start = 0; start < reading.size(); ++start) {
      if (!std::isfinite(scores[start]))
        continue;
      const char32_t first = reading[start];
      const bool single_ok =
          (first >= U'ぁ' && first <= U'ゖ') || first == U'ー' ||
          first == U'、' || first == U'。' || first == U'！' ||
          first == U'？' || first == U'!' || first == U'?';
      const double fb_score = scores[start] + (single_ok ? -22.0 : -40.0);
      if (fb_score > scores[start + 1]) {
        scores[start + 1] = fb_score;
        prev_idx[start + 1] = start;
        piece_surf[start + 1] = encode_utf8(reading.substr(start, 1));
      }
      uint32_t node = 0;
      for (size_t end = start; end < std::min(reading.size(), start + 40); ++end) {
        uint32_t next = 0;
        for (const auto &edge : trie[node].children) {
          if (edge.first == reading[end]) {
            next = edge.second;
            break;
          }
        }
        if (next == 0)
          break;
        node = next;
        if (trie[node].has_word) {
          const size_t len = end - start + 1;
          double w_score = trie[node].best_score;
          if (len == 1) {
            if (bound_moras.find(first) != std::u32string::npos) {
              w_score = -20.0;
            } else if (particles.find(first) != std::u32string::npos) {
              if (start == 0 && first >= U'ぁ' && first <= U'ゖ')
                w_score = std::min(w_score, -9.0);
            } else {
              w_score = std::min(w_score, -11.0);
            }
          } else {
            w_score += 2.5 * double(std::min<size_t>(len - 1, 4));
          }
          const double cand_score = scores[start] + w_score;
          if (cand_score > scores[end + 1]) {
            scores[end + 1] = cand_score;
            prev_idx[end + 1] = start;
            piece_surf[end + 1] = trie[node].best_surface;
          }
        }
      }
    }
    std::vector<std::string> parts;
    for (size_t cur = reading.size(); cur > 0; cur = prev_idx[cur])
      parts.push_back(piece_surf[cur]);
    std::string full_surface;
    for (auto it = parts.rbegin(); it != parts.rend(); ++it)
      full_surface += *it;
    return {scores.back() - 2.5 * double(reading.size()), full_surface};
  }
  double score_fast(const std::u32string &reading) const {
    return convert_fast(reading).score;
  }
  DictionaryPath convert(const std::u32string &reading) const {
    const double missing = -std::numeric_limits<double>::infinity();
    std::vector<DictionaryPath> paths(reading.size() + 1, {missing, ""});
    paths[0] = {0, ""};
    for (size_t start = 0; start < reading.size(); ++start) {
      if (!std::isfinite(paths[start].score))
        continue;
      for (size_t end = start + 1; end <= std::min(reading.size(), start + 40);
           ++end) {
        const auto piece = reading.substr(start, end - start);
        const auto at = entries.find(piece);
        if (at == entries.end() && end != start + 1)
          continue;
        const DictionaryPath word =
            at != entries.end()
                ? at->second
                : DictionaryPath{kana(piece) ? -24. : -40., encode_utf8(piece)};
        const DictionaryPath next{paths[start].score + word.score,
                                  paths[start].surface + word.surface};
        if (next.score > paths[end].score ||
            (next.score == paths[end].score &&
             next.surface > paths[end].surface))
          paths[end] = next;
      }
    }
    return paths.back();
  }
};

struct ReadingCandidate {
  std::u32string reading;
  DictionaryPath path;
  double penalty = 0.0;
};
inline std::vector<ReadingCandidate>
channel_candidates(const std::string &keys,
                   const ChannelDictionary &dictionary) {
  if (keys.size() < 4 || keys.size() > 64 ||
      !std::all_of(keys.begin(), keys.end(),
                   [](char c) { return c >= 'a' && c <= 'z'; }))
    return {};
  const auto original = roman_decode(keys);
  static const auto neighbors = make_neighbors();
  std::map<std::u32string, DictionaryPath> grouped;
  auto add = [&](const std::string &candidate) {
    const auto reading = roman_decode(candidate);
    if ((reading == original || kana(reading)) && !grouped.count(reading))
      grouped[reading] = dictionary.convert(reading);
  };
  add(keys);
  for (size_t i = 0; i < keys.size(); ++i)
    for (const char c : neighbors.at(keys[i])) {
      auto variant = keys;
      variant[i] = c;
      add(variant);
    }
  std::vector<ReadingCandidate> candidates;
  for (const auto &entry : grouped)
    candidates.push_back({entry.first, entry.second, 0.0});
  std::sort(candidates.begin(), candidates.end(),
            [](const auto &a, const auto &b) {
              return a.path.score != b.path.score ? a.path.score > b.path.score
                                                  : a.reading < b.reading;
            });
  if (candidates.size() > 8)
    candidates.resize(8);
  if (std::none_of(candidates.begin(), candidates.end(),
                   [&](const auto &c) { return c.reading == original; }))
    candidates.back() = {original, grouped.at(original), 0.0};
  return candidates;
}

inline std::vector<ReadingCandidate>
assist_candidates(const std::string &raw_keys, const std::u32string &input_hira,
                  const ChannelDictionary &dictionary, size_t limit = 24) {
  const std::string rom =
      !raw_keys.empty() ? raw_keys : kana_to_romaji(input_hira);
  if (rom.size() < 2 || rom.size() > 128)
    return {};
  const std::u32string orig =
      !input_hira.empty() ? hiragana(input_hira) : roman_decode(rom);
  if (orig.size() < 2 || orig.size() > 128)
    return {};
  if (!std::all_of(orig.begin(), orig.end(), [](char32_t c) {
        return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー' || c == U'、' ||
               c == U'。' || c == U'！' || c == U'？' || c == U'!' ||
               c == U'?' || (c >= U'a' && c <= U'z') ||
               (c >= U'A' && c <= U'Z') || (c >= U'ａ' && c <= U'ｚ') ||
               (c >= U'Ａ' && c <= U'Ｚ') || c == U'-';
      }))
    return {};
  static const auto neighbors = make_neighbors();
  std::map<std::u32string, double> penalties;
  penalties[orig] = 0.0;
  auto add = [&](const std::u32string &cand, double pen) {
    if (cand.empty() || cand == orig)
      return;
    const int diff = int(cand.size()) - int(orig.size());
    if (diff < -3 || diff > 3 || !kana_or_punct(cand))
      return;
    const double len_pen = diff < 0 ? (-diff * 1.0) : 0.0;
    const double total_pen = pen + len_pen;
    auto at = penalties.find(cand);
    if (at == penalties.end() || total_pen < at->second)
      penalties[cand] = total_pen;
  };
  const std::string vowels = "aiueo";
  const std::string ins_chars = "aiueokstnhmyrwgzdbp";
  const std::string cons_chars = "kstnhmyrwgzdbp";
  std::vector<std::string> rom_forms = {rom};
  if (raw_keys.empty()) {
    const std::string rom_k = kana_to_romaji(input_hira, true);
    if (rom_k != rom && rom_k.size() >= 2 && rom_k.size() <= 128)
      rom_forms.push_back(rom_k);
  }
  for (const auto &r : rom_forms) {
    for (size_t i = 0; i < r.size(); ++i) {
      const char ch = r[i];
      if ((ch < 'a' || ch > 'z') && ch != '-')
        continue;
      const bool adj_vowel_a =
          ch == 'a' &&
          ((i > 0 && vowels.find(r[i - 1]) != std::string::npos) ||
           (i + 1 < r.size() && vowels.find(r[i + 1]) != std::string::npos));
      add(roman_decode(r.substr(0, i) + r.substr(i + 1)),
          adj_vowel_a ? 1.0 : 2.5);
      auto nb_it = neighbors.find(ch);
      if (nb_it != neighbors.end()) {
        for (char nb : nb_it->second) {
          auto v = r;
          v[i] = nb;
          add(roman_decode(v), 1.5);
        }
      }
      if (vowels.find(ch) != std::string::npos) {
        for (char vch : vowels) {
          if (vch != ch) {
            auto v = r;
            v[i] = vch;
            add(roman_decode(v), 2.2);
          }
        }
      } else if (i + 1 < r.size() &&
                 vowels.find(r[i + 1]) != std::string::npos) {
        if (ch == 'p') {
          auto v = r;
          v[i] = 'h';
          add(roman_decode(v), 2.4);
        } else if ((ch == 't' || ch == 'd') &&
                   (r[i + 1] == 'i' || r[i + 1] == 'u')) {
          auto v = r;
          v[i] = 's';
          add(roman_decode(v), 2.4);
        }
      }
    }
    for (size_t i = 0; i <= r.size(); ++i) {
      for (char ch : ins_chars) {
        add(roman_decode(r.substr(0, i) + std::string(1, ch) + r.substr(i)),
            2.8);
      }
    }
    for (size_t i = 0; i + 1 < r.size(); ++i) {
      if (r[i] < 'a' || r[i] > 'z' || r[i + 1] < 'a' || r[i + 1] > 'z')
        continue;
      if (r[i] != r[i + 1]) {
        auto v = r;
        std::swap(v[i], v[i + 1]);
        add(roman_decode(v), 1.8);
      }
      for (char vch : vowels) {
        add(roman_decode(r.substr(0, i) + std::string(1, vch) +
                         r.substr(i + 2)),
            3.0);
      }
    }
    for (size_t i = 0; i + 2 < r.size(); ++i) {
      if (r[i + 2] == 'a') {
        add(roman_decode(r.substr(0, i) + std::string(1, r[i + 1]) +
                         std::string(1, r[i]) + r.substr(i + 3)),
            2.0);
      }
    }
    for (size_t i = 0; i + 3 < r.size(); ++i) {
      if (r[i + 1] == 'a' && vowels.find(r[i + 3]) != std::string::npos) {
        add(roman_decode(r.substr(0, i + 1) + std::string(1, r[i + 3]) +
                         std::string(1, r[i + 2]) + r.substr(i + 4)),
            2.0);
        for (char vch : vowels) {
          add(roman_decode(r.substr(0, i + 1) + std::string(1, vch) +
                           r.substr(i + 4)),
              2.2);
        }
      }
    }
  }
  const std::u32string specials = U"っんー";
  for (size_t i = 0; i < orig.size(); ++i) {
    if (specials.find(orig[i]) != std::u32string::npos)
      add(orig.substr(0, i) + orig.substr(i + 1), 1.0);
  }
  for (size_t i = 1; i <= orig.size(); ++i) {
    if (orig[i - 1] == U'？' || orig[i - 1] == U'！' || orig[i - 1] == U'。' ||
        orig[i - 1] == U'、')
      continue;
    for (char32_t sp : specials)
      add(orig.substr(0, i) + std::u32string(1, sp) + orig.substr(i), 2.5);
  }
  for (size_t i = 0; i + 1 < orig.size(); ++i) {
    auto v = orig;
    std::swap(v[i], v[i + 1]);
    add(v, 2.2);
    const auto sub2 = orig.substr(i, 2);
    if (sub2 == U"なっ" || sub2 == U"なな")
      add(orig.substr(0, i) + U"ん" + orig.substr(i + 2), 1.2);
    else if (sub2 == U"んな")
      add(orig.substr(0, i) + U"にん" + orig.substr(i + 2), 1.8);
    else if (sub2 == U"うじ")
      add(orig.substr(0, i) + U"じゅ" + orig.substr(i + 2), 1.8);
  }
  struct ScoredItem {
    double rank_score;
    DictionaryPath path;
    double penalty;
    std::u32string reading;
  };
  std::vector<ScoredItem> items;
  for (const auto &kv : penalties) {
    if (kv.first == orig)
      continue;
    const auto p = dictionary.convert_fast(kv.first);
    items.push_back({p.score - kv.second * 4.0, p, kv.second, kv.first});
  }
  std::sort(items.begin(), items.end(), [](const auto &a, const auto &b) {
    return a.rank_score != b.rank_score ? a.rank_score > b.rank_score
                                        : a.reading < b.reading;
  });
  std::vector<ReadingCandidate> out;
  out.push_back({orig, dictionary.convert_fast(orig), 0.0});
  for (size_t i = 0; i < items.size() && out.size() < limit; ++i)
    out.push_back({items[i].reading, items[i].path, items[i].penalty});
  return out;
}

inline std::string json_string(const std::string &text) {
  std::string result = "\"";
  for (const unsigned char c : text) {
    if (c < 32)
      throw std::runtime_error("invalid output text");
    if (c == '\\' || c == '"')
      result += '\\';
    result += char(c);
  }
  return result + '"';
}

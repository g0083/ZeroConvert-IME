#pragma once
#include "flick_channel.hpp"
#include "roman_table.hpp"
#include <algorithm>
#include <array>
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
#include <string_view>
#include <unordered_map>
#include <utility>
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
inline bool kana(std::u32string_view s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー';
  });
}
inline bool is_digit_char(char32_t c) {
  return (c >= U'0' && c <= U'9') || (c >= U'０' && c <= U'９');
}
inline char32_t normalize_digit_ascii(char32_t c) {
  if (c >= U'０' && c <= U'９')
    return U'0' + (c - U'０');
  return c;
}
inline std::u32string mask_digits_nam_u32(const std::u32string &text) {
  std::u32string out;
  out.reserve(text.size());
  bool in_digits = false;
  for (char32_t c : text) {
    if (is_digit_char(c)) {
      if (!in_digits) {
        out += U"nam";
        in_digits = true;
      }
    } else {
      in_digits = false;
      out.push_back(c);
    }
  }
  return out;
}
inline bool kana_or_punct(const std::u32string &s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー' || c == U'、' ||
           c == U'。' || c == U'！' || c == U'？' || c == U'!' || c == U'?' ||
           is_digit_char(c);
  });
}
inline const std::unordered_map<std::string_view, std::u32string_view> &
roman_table_fast() {
  static const auto *table = [] {
    auto *m = new std::unordered_map<std::string_view, std::u32string_view>();
    m->reserve(roman_table.size() * 2);
    for (const auto &kv : roman_table)
      m->emplace(std::string_view(kv.first), std::u32string_view(kv.second));
    return m;
  }();
  return *table;
}
inline bool is_aeioun_char(char c) {
  return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'n';
}
inline bool is_aiueoy_char(char c) {
  return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o' || c == 'y';
}
inline std::u32string
roman_decode_with_map(const std::string &keys, std::vector<size_t> &rom_to_kana,
                      std::vector<bool> &is_raw_ascii) {
  const auto &fast_rt = roman_table_fast();
  std::u32string result;
  result.reserve(keys.size());
  rom_to_kana.assign(keys.size(), 0);
  is_raw_ascii.assign(keys.size(), false);
  for (size_t i = 0; i < keys.size();) {
    if (i + 1 < keys.size() && keys[i] == keys[i + 1] &&
        !is_aeioun_char(keys[i])) {
      rom_to_kana[i] = result.size();
      result += U'っ';
      ++i;
      continue;
    }
    bool found = false;
    for (size_t width = std::min(size_t(4), keys.size() - i); width > 0;
         --width) {
      auto at = fast_rt.find(std::string_view(keys.data() + i, width));
      if (at != fast_rt.end() && kana(at->second)) {
        const size_t k_idx = result.size();
        for (size_t k = 0; k < width; ++k)
          rom_to_kana[i + k] = k_idx;
        result.append(at->second.data(), at->second.size());
        i += width;
        found = true;
        break;
      }
    }
    if (found)
      continue;
    rom_to_kana[i] = result.size();
    if (keys[i] == 'n' &&
        (i + 1 == keys.size() || !is_aiueoy_char(keys[i + 1])))
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
    else {
      result += char32_t(keys[i]);
      if ((keys[i] >= 'a' && keys[i] <= 'z') ||
          (keys[i] >= 'A' && keys[i] <= 'Z'))
        is_raw_ascii[i] = true;
    }
    ++i;
  }
  return result;
}
inline std::u32string roman_decode(const std::string &keys) {
  std::vector<size_t> rom_to_kana;
  std::vector<bool> is_raw_ascii;
  return roman_decode_with_map(keys, rom_to_kana, is_raw_ascii);
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
      {U"ゎ", "lwa"},   {U"ゕ", "lka"},   {U"ゖ", "lke"},   {U"っ", "ltu"},
      {U"ー", "-"},     {U"？", "?"},     {U"！", "!"},     {U"、", ","},
      {U"。", "."}};
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
    else if (h[i] >= U'０' && h[i] <= U'９')
      out += char(h[i] - U'０' + '0');
    else if (h[i] < 128)
      out += char(std::tolower(int(h[i])));
    ++i;
  }
  return out;
}
inline bool qwerty_key_pos(char c, double &x, double &y) {
  static const char *rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
  static const double offsets[] = {0.0, 0.25, 0.75};
  for (int r = 0; r < 3; ++r) {
    for (int col = 0; rows[r][col] != '\0'; ++col) {
      if (rows[r][col] == c) {
        x = double(col) + offsets[r];
        y = double(r);
        return true;
      }
    }
  }
  return false;
}
inline bool qwerty_left_hand(char c) {
  return std::string("qwertasdfgzxcvb").find(c) != std::string::npos;
}
inline double qwerty_sub_cost(char a, char b) {
  if (a == b)
    return -1.0;
  double ax = 0.0, ay = 0.0, bx = 0.0, by = 0.0;
  if (!qwerty_key_pos(a, ax, ay) || !qwerty_key_pos(b, bx, by))
    return -1.0;
  const double d = std::hypot(ax - bx, ay - by);
  const bool same_hand = qwerty_left_hand(a) == qwerty_left_hand(b);
  if (d <= 1.35) {
    if (ay == by && std::abs(ax - bx) == 1.0)
      return same_hand ? 1.1 : 1.5;
    if (d <= 1.26)
      return same_hand ? 1.35 : 1.7;
    return 1.8;
  }
  if (ay != by && std::abs(ay - by) == 1.0 && (bx - ax) * (by - ay) > 0.0) {
    if (d <= 1.65)
      return same_hand ? 1.75 : 1.95;
    if (d <= 1.85)
      return same_hand ? 1.85 : 2.05;
  }
  return -1.0;
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
inline std::array<std::vector<std::pair<char, double>>, 26>
make_qwerty_sub_table() {
  std::array<std::vector<std::pair<char, double>>, 26> table;
  for (char a = 'a'; a <= 'z'; ++a) {
    auto &row = table[size_t(a - 'a')];
    for (char b = 'a'; b <= 'z'; ++b) {
      const double cost = qwerty_sub_cost(a, b);
      if (cost > 0.0)
        row.push_back({b, cost});
    }
    std::sort(row.begin(), row.end(),
              [](const auto &x, const auto &y) {
                return x.second != y.second ? x.second < y.second
                                            : x.first < y.first;
              });
  }
  return table;
}
struct TouchHint {
  size_t index = 0;
  char key = 0;
  double weight = 0.0;
};
inline std::vector<TouchHint> parse_touch_hints(const std::string &spec) {
  std::vector<TouchHint> hints;
  if (spec.empty())
    return hints;
  size_t pos = 0;
  size_t seen_indices = 0;
  while (pos < spec.size() && seen_indices < 128 && hints.size() < 512) {
    size_t semi = spec.find(';', pos);
    if (semi == std::string::npos)
      semi = spec.size();
    const auto entry = spec.substr(pos, semi - pos);
    pos = semi + 1;
    const size_t colon = entry.find(':');
    if (colon == std::string::npos || colon == 0)
      continue;
    bool valid_idx = true;
    size_t idx = 0;
    for (size_t k = 0; k < colon; ++k) {
      if (entry[k] < '0' || entry[k] > '9') {
        valid_idx = false;
        break;
      }
      idx = idx * 10 + size_t(entry[k] - '0');
      if (idx >= 192) {
        valid_idx = false;
        break;
      }
    }
    if (!valid_idx)
      continue;
    ++seen_indices;
    size_t apos = colon + 1;
    size_t alts_for_idx = 0;
    while (apos < entry.size() && alts_for_idx < 4 && hints.size() < 512) {
      size_t comma = entry.find(',', apos);
      if (comma == std::string::npos)
        comma = entry.size();
      const auto item = entry.substr(apos, comma - apos);
      apos = comma + 1;
      const bool valid_key =
          (item[0] >= 'a' && item[0] <= 'z') || item[0] == '-';
      if (item.size() < 3 || item[1] != '=' || !valid_key)
        continue;
      char *endp = nullptr;
      const double w = std::strtod(item.c_str() + 2, &endp);
      if (endp != item.c_str() + item.size() || !std::isfinite(w) || w < 0.2 ||
          w > 1.0)
        continue;
      hints.push_back({idx, item[0], w});
      ++alts_for_idx;
    }
  }
  return hints;
}

struct DictionaryPath {
  double score;
  std::string surface;
};
class ChannelDictionary {
  struct TrieNode {
    bool has_word = false;
    double best_score = -std::numeric_limits<double>::infinity();
    std::string best_surface;
    std::vector<std::pair<char32_t, uint32_t>> children;
  };
  std::vector<TrieNode> trie;

  uint32_t find_child(uint32_t node, char32_t ch) const {
    const auto &children = trie[node].children;
    if (children.size() <= 8) {
      for (const auto &edge : children) {
        if (edge.first == ch)
          return edge.second;
      }
      return 0;
    }
    const auto it = std::lower_bound(
        children.begin(), children.end(), ch,
        [](const auto &edge, char32_t target) { return edge.first < target; });
    return (it != children.end() && it->first == ch) ? it->second : 0;
  }

public:
  explicit ChannelDictionary(const std::string &path) {
    std::ifstream source(path, std::ios::binary | std::ios::ate);
    if (!source)
      throw std::runtime_error("dictionary unavailable");
    const auto size = source.tellg();
    if (size < 16 || size > 256 * 1024 * 1024)
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
    trie.push_back(TrieNode{});
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
      uint32_t node = 0;
      for (char32_t ch : reading) {
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
      const double score_d = score;
      if (!trie[node].has_word || score_d > trie[node].best_score ||
          (score_d == trie[node].best_score &&
           surface > trie[node].best_surface)) {
        trie[node].has_word = true;
        trie[node].best_score = score_d;
        trie[node].best_surface = surface;
      }
    }
    for (auto &node : trie) {
      if (node.children.size() > 1) {
        std::sort(node.children.begin(), node.children.end(),
                  [](const auto &a, const auto &b) {
                    return a.first < b.first;
                  });
      }
    }
  }
  bool has_reading(std::u32string_view reading) const {
    uint32_t node = 0;
    for (char32_t ch : reading) {
      node = find_child(node, ch);
      if (node == 0)
        return false;
    }
    return trie[node].has_word;
  }
  struct DictionaryMarginals {
    std::vector<double> fwd;
    std::vector<double> bwd;
    std::vector<size_t> prev_idx;
    std::vector<std::string> piece_surf;
    std::vector<bool> is_boundary;
    std::vector<double> char_anomaly;
    double total_score = 0.0;
    std::string best_surface;

    std::string surface_prefix(size_t boundary_idx) const {
      const size_t n = prev_idx.size() > 0 ? prev_idx.size() - 1 : 0;
      if (boundary_idx == 0 || n == 0)
        return {};
      size_t pos = std::min(boundary_idx, n);
      while (pos > 0 && pos < is_boundary.size() && !is_boundary[pos])
        --pos;
      std::vector<std::string> parts;
      for (size_t cur = pos; cur > 0; cur = prev_idx[cur])
        parts.push_back(piece_surf[cur]);
      std::string prefix;
      for (auto it = parts.rbegin(); it != parts.rend(); ++it)
        prefix += *it;
      return prefix;
    }
  };
  DictionaryMarginals analyze(const std::u32string &reading) const {
    static const std::u32string bound_moras = U"っんーぁぃぅぇぉゃゅょゎ";
    static const std::u32string particles = U"はがをにへでとのもやかねよわな、。！？!?";
    const double missing = -std::numeric_limits<double>::infinity();
    const size_t n = reading.size();
    DictionaryMarginals out;
    out.fwd.assign(n + 1, missing);
    out.bwd.assign(n + 1, missing);
    out.prev_idx.assign(n + 1, 0);
    out.piece_surf.assign(n + 1, std::string());
    out.is_boundary.assign(n + 1, false);
    out.char_anomaly.assign(n, 0.0);
    std::vector<double> seg_raw_score(n + 1, -40.0);
    std::vector<bool> seg_is_fallback(n + 1, true);
    out.fwd[0] = 0.0;
    for (size_t start = 0; start < n; ++start) {
      if (!std::isfinite(out.fwd[start]))
        continue;
      const char32_t first = reading[start];
      const bool is_dig = is_digit_char(first);
      const bool single_ok =
          (first >= U'ぁ' && first <= U'ゖ') || first == U'ー' ||
          first == U'、' || first == U'。' || first == U'！' ||
          first == U'？' || first == U'!' || first == U'?' || is_dig;
      const double fb_w = is_dig ? -8.0 : (single_ok ? -22.0 : -40.0);
      const double fb_score = out.fwd[start] + fb_w;
      if (fb_score > out.fwd[start + 1]) {
        out.fwd[start + 1] = fb_score;
        out.prev_idx[start + 1] = start;
        out.piece_surf[start + 1] = encode_utf8(reading.substr(start, 1));
        seg_raw_score[start + 1] = fb_w;
        seg_is_fallback[start + 1] = true;
      }
      uint32_t node = 0;
      for (size_t end = start; end < std::min(n, start + 40); ++end) {
        const uint32_t next = find_child(node, reading[end]);
        if (next == 0)
          break;
        node = next;
        if (trie[node].has_word) {
          const size_t len = end - start + 1;
          const double base_w = trie[node].best_score;
          double w_score = base_w;
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
          const double cand_score = out.fwd[start] + w_score;
          if (cand_score > out.fwd[end + 1]) {
            out.fwd[end + 1] = cand_score;
            out.prev_idx[end + 1] = start;
            out.piece_surf[end + 1] = trie[node].best_surface;
            seg_raw_score[end + 1] = len == 1 ? w_score : base_w;
            seg_is_fallback[end + 1] = false;
          }
        }
      }
    }
    out.bwd[n] = 0.0;
    for (size_t start = n; start-- > 0;) {
      const char32_t first = reading[start];
      const bool is_dig = is_digit_char(first);
      const bool single_ok =
          (first >= U'ぁ' && first <= U'ゖ') || first == U'ー' ||
          first == U'、' || first == U'。' || first == U'！' ||
          first == U'？' || first == U'!' || first == U'?' || is_dig;
      if (std::isfinite(out.bwd[start + 1]))
        out.bwd[start] =
            (is_dig ? -8.0 : (single_ok ? -22.0 : -40.0)) + out.bwd[start + 1];
      uint32_t node = 0;
      for (size_t end = start; end < std::min(n, start + 40); ++end) {
        const uint32_t next = find_child(node, reading[end]);
        if (next == 0)
          break;
        node = next;
        if (trie[node].has_word && std::isfinite(out.bwd[end + 1])) {
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
          out.bwd[start] = std::max(out.bwd[start], w_score + out.bwd[end + 1]);
        }
      }
    }
    std::vector<std::string> parts;
    out.is_boundary[0] = true;
    out.is_boundary[n] = true;
    for (size_t cur = n; cur > 0;) {
      const size_t prev = out.prev_idx[cur];
      out.is_boundary[cur] = true;
      out.is_boundary[prev] = true;
      parts.push_back(out.piece_surf[cur]);
      const size_t len = cur - prev;
      const double raw = seg_raw_score[cur];
      double anomaly = 0.0;
      if (len == 1) {
        if (is_digit_char(reading[prev]))
          anomaly = 0.0;
        else if (raw <= -39.0)
          anomaly = 100.0;
        else if (raw <= -19.0)
          anomaly = 25.0;
        else if (raw <= -10.5)
          anomaly = 12.0;
      } else {
        const double per_ch = raw / double(len);
        if (per_ch < -6.5)
          anomaly = -per_ch - 5.0;
      }
      for (size_t k = prev; k < cur; ++k)
        out.char_anomaly[k] = anomaly;
      cur = prev;
    }
    for (auto it = parts.rbegin(); it != parts.rend(); ++it)
      out.best_surface += *it;
    out.total_score = out.fwd[n] - 2.5 * double(n);
    return out;
  }
  double score_only(std::u32string_view reading) const {
    static constexpr std::u32string_view bound_moras = U"っんーぁぃぅぇぉゃゅょゎ";
    static constexpr std::u32string_view particles =
        U"はがをにへでとのもやかねよわな、。！？!?";
    const double missing = -std::numeric_limits<double>::infinity();
    const size_t n = reading.size();
    double stack_fwd[132];
    std::vector<double> heap_fwd;
    double *fwd = stack_fwd;
    if (n + 1 > 132) {
      heap_fwd.assign(n + 1, missing);
      fwd = heap_fwd.data();
    } else {
      std::fill(fwd, fwd + n + 1, missing);
    }
    fwd[0] = 0.0;
    for (size_t start = 0; start < n; ++start) {
      if (!std::isfinite(fwd[start]))
        continue;
      const char32_t first = reading[start];
      const bool is_dig = is_digit_char(first);
      const bool single_ok =
          (first >= U'ぁ' && first <= U'ゖ') || first == U'ー' ||
          first == U'、' || first == U'。' || first == U'！' ||
          first == U'？' || first == U'!' || first == U'?' || is_dig;
      const double fb_w = is_dig ? -8.0 : (single_ok ? -22.0 : -40.0);
      const double fb_score = fwd[start] + fb_w;
      if (fb_score > fwd[start + 1])
        fwd[start + 1] = fb_score;
      uint32_t node = 0;
      for (size_t end = start; end < std::min(n, start + 40); ++end) {
        const uint32_t next = find_child(node, reading[end]);
        if (next == 0)
          break;
        node = next;
        if (trie[node].has_word) {
          const size_t len = end - start + 1;
          double w_score = trie[node].best_score;
          if (len == 1) {
            if (bound_moras.find(first) != std::u32string_view::npos) {
              w_score = -20.0;
            } else if (particles.find(first) != std::u32string_view::npos) {
              if (start == 0 && first >= U'ぁ' && first <= U'ゖ')
                w_score = std::min(w_score, -9.0);
            } else {
              w_score = std::min(w_score, -11.0);
            }
          } else {
            w_score += 2.5 * double(std::min<size_t>(len - 1, 4));
          }
          const double cand_score = fwd[start] + w_score;
          if (cand_score > fwd[end + 1])
            fwd[end + 1] = cand_score;
        }
      }
    }
    return fwd[n] - 2.5 * double(n);
  }
  DictionaryPath convert_fast(const std::u32string &reading) const {
    const auto analyzed = analyze(reading);
    return {analyzed.total_score, analyzed.best_surface};
  }
  double score_fast(const std::u32string &reading) const {
    return score_only(reading);
  }
  DictionaryPath convert(const std::u32string &reading) const {
    const double missing = -std::numeric_limits<double>::infinity();
    std::vector<DictionaryPath> paths(reading.size() + 1, {missing, ""});
    paths[0] = {0, ""};
    for (size_t start = 0; start < reading.size(); ++start) {
      if (!std::isfinite(paths[start].score))
        continue;
      const size_t max_end = std::min(reading.size(), start + 40);
      uint32_t node = 0;
      bool in_trie = true;
      for (size_t end = start + 1; end <= max_end; ++end) {
        if (in_trie) {
          node = find_child(node, reading[end - 1]);
          if (node == 0)
            in_trie = false;
        }
        const bool has_word = in_trie && trie[node].has_word;
        if (!has_word && end != start + 1) {
          if (!in_trie)
            break;
          continue;
        }
        DictionaryPath word;
        if (has_word) {
          word = {trie[node].best_score, trie[node].best_surface};
        } else {
          const auto piece = reading.substr(start, 1);
          const double fb = is_digit_char(piece[0]) ? -8. : (kana(piece) ? -24. : -40.);
          word = {fb, encode_utf8(piece)};
        }
        const DictionaryPath next{paths[start].score + word.score,
                                  paths[start].surface + word.surface};
        if (next.score > paths[end].score ||
            (next.score == paths[end].score &&
             next.surface > paths[end].surface))
          paths[end] = next;
        if (!in_trie)
          break;
      }
    }
    return paths.back();
  }
};

struct ReadingCandidate {
  std::u32string reading;
  DictionaryPath path;
  double penalty = 0.0;
  double local_delta = 0.0;
  size_t edit_l = 0;
  size_t edit_r = 0;
};

inline constexpr double assist_rerank_margin = 12.0;

template <typename Ranked>
inline bool has_clear_assist_lead(const std::vector<Ranked> &ranked) {
  if (ranked.size() < 2)
    return false;
  const auto &winner = ranked.front();
  // Compare only scores with the same original reading and left context.
  return ranked[1].win_idx == winner.win_idx &&
         std::none_of(ranked.begin() + 1, ranked.end(), [&](const auto &other) {
           return other.win_idx == winner.win_idx &&
                  winner.final_score - other.final_score < assist_rerank_margin;
         });
}
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
    candidates.push_back({entry.first, entry.second, 0.0, 0.0, 0, 0});
  std::sort(candidates.begin(), candidates.end(),
            [](const auto &a, const auto &b) {
              return a.path.score != b.path.score ? a.path.score > b.path.score
                                                  : a.reading < b.reading;
            });
  if (candidates.size() > 8)
    candidates.resize(8);
  if (std::none_of(candidates.begin(), candidates.end(),
                   [&](const auto &c) { return c.reading == original; }))
    candidates.back() = {original, grouped.at(original), 0.0, 0.0, 0, 0};
  return candidates;
}

inline std::vector<ReadingCandidate>
assist_candidates(const std::string &raw_keys, const std::u32string &input_hira,
                  const ChannelDictionary &dictionary, size_t limit = 12,
                  const std::string &touch_hints_spec = "",
                  InputKeyboardMode mode = InputKeyboardMode::Qwerty) {
  const std::u32string orig =
      !input_hira.empty() ? hiragana(input_hira) : roman_decode(raw_keys);
  const size_t H = orig.size();
  if (H < 2 || H > 128)
    return {};
  const bool is_flick_mode = is_flick_keyboard_mode(mode);
  if (!std::all_of(orig.begin(), orig.end(), [&](char32_t c) {
        if ((c >= U'ぁ' && c <= U'ゖ') || c == U'ー' || c == U'、' ||
            c == U'。' || c == U'！' || c == U'？' || c == U'!' ||
            c == U'?' || is_digit_char(c))
          return true;
        if (is_flick_mode &&
            (c == U'（' || c == U'）' || c == U'〜' || c == U'・'))
          return true;
        return !is_flick_mode &&
               ((c >= U'a' && c <= U'z') || (c >= U'A' && c <= U'Z') ||
                (c >= U'ａ' && c <= U'ｚ') || (c >= U'Ａ' && c <= U'Ｚ') ||
                c == U'-');
      }))
    return {};

  std::vector<char32_t> orig_digits;
  for (char32_t c : orig) {
    if (is_digit_char(c))
      orig_digits.push_back(c);
  }

  const auto orig_analysis = dictionary.analyze(orig);
  auto is_ascii_letter = [](char32_t c) {
    return (c >= U'a' && c <= U'z') || (c >= U'A' && c <= U'Z') ||
           (c >= U'ａ' && c <= U'ｚ') || (c >= U'Ａ' && c <= U'Ｚ');
  };
  double max_anomaly = 0.0;
  bool has_ascii_letter = false;
  for (size_t k = 0; k < H; ++k) {
    if (is_ascii_letter(orig[k]))
      has_ascii_letter = true;
    if (orig_analysis.char_anomaly[k] > max_anomaly)
      max_anomaly = orig_analysis.char_anomaly[k];
  }
  // Only trigger hotspot pruning for truly long inputs (H > 32) with a real
  // anomaly (raw ASCII/fullwidth or OOV/unjoined mora >= 25.0). Common 1-char
  // hiragana words score ~12.0 and must not suppress unrelated positions.
  const bool hard_anomaly = has_ascii_letter || max_anomaly >= 25.0;

  std::vector<bool> active_kana(H, false);
  auto activate_range = [&](size_t center, size_t radius) {
    const size_t lo = center > radius ? center - radius : 0;
    const size_t hi = std::min(H - 1, center + radius);
    for (size_t k = lo; k <= hi; ++k)
      active_kana[k] = true;
  };
  if (H <= 32 || !hard_anomaly) {
    std::fill(active_kana.begin(), active_kana.end(), true);
  } else {
    if (has_ascii_letter) {
      for (size_t k = 0; k < H; ++k) {
        if (is_ascii_letter(orig[k]))
          activate_range(k, 2);
      }
    }
    struct SpanPeak {
      double peak;
      size_t lo;
      size_t hi;
    };
    std::vector<SpanPeak> peaks;
    for (size_t k = 0; k < H;) {
      if (orig_analysis.char_anomaly[k] >= 25.0) {
        size_t j = k + 1;
        double mx = orig_analysis.char_anomaly[k];
        while (j < H && orig_analysis.char_anomaly[j] >= 25.0) {
          mx = std::max(mx, orig_analysis.char_anomaly[j]);
          ++j;
        }
        peaks.push_back({mx, k, j - 1});
        k = j;
      } else {
        ++k;
      }
    }
    std::sort(peaks.begin(), peaks.end(),
              [](const SpanPeak &a, const SpanPeak &b) {
                return a.peak > b.peak;
              });
    for (size_t p = 0; p < std::min<size_t>(peaks.size(), 3); ++p) {
      const size_t lo = peaks[p].lo > 2 ? peaks[p].lo - 2 : 0;
      const size_t hi = std::min(H - 1, peaks[p].hi + 2);
      for (size_t k = lo; k <= hi; ++k)
        active_kana[k] = true;
    }
    const size_t tail_lo = H > 6 ? H - 6 : 0;
    for (size_t k = tail_lo; k < H; ++k)
      active_kana[k] = true;
  }
  for (size_t k = 0; k < H; ++k) {
    if (is_digit_char(orig[k]))
      active_kana[k] = false;
  }

  std::unordered_map<std::u32string, double> penalties;
  penalties.reserve(2048);
  auto add = [&](const std::u32string &raw_cand, double pen,
                 bool no_len_pen = false) {
    if (raw_cand.empty())
      return;
    std::u32string cand = raw_cand;
    if (!orig_digits.empty()) {
      size_t d_idx = 0;
      for (size_t idx = 0; idx < cand.size(); ++idx) {
        if (is_digit_char(cand[idx])) {
          if (d_idx >= orig_digits.size() ||
              normalize_digit_ascii(cand[idx]) !=
                  normalize_digit_ascii(orig_digits[d_idx]))
            return;
          cand[idx] = orig_digits[d_idx++];
        }
      }
      if (d_idx != orig_digits.size())
        return;
    }
    if (cand == orig)
      return;
    const int diff = int(cand.size()) - int(orig.size());
    if (diff < -3 || diff > 3 || !kana_or_punct(cand))
      return;
    const double len_pen = (!no_len_pen && diff < 0) ? (-diff * 1.0) : 0.0;
    const double total_pen = pen + len_pen;
    auto at = penalties.find(cand);
    if (at == penalties.end() || total_pen < at->second)
      penalties[cand] = total_pen;
  };

  static const std::u32string sokuon_next =
      U"かきくけこさしすせそたちつてとぱぴぷぺぽ";
  static const std::u32string ou_row =
      U"おこそとのほもよろをごぞどぼぽうくすつぬふむゆるぐずづぶぷぉょゅぅ";
  static const std::u32string ae_row =
      U"あかさたなはまやらわがざだばぱえけせてねへめれげぜでべぺぁぇ";
  static const std::u32string specials = U"っんー";

  if (is_flick_mode) {
    generate_flick_candidates(orig, active_kana, touch_hints_spec,
                              mode == InputKeyboardMode::FlickToggle, add);
  } else {

  std::string rom;
  bool using_aligned_raw = false;
  if (!raw_keys.empty() && raw_keys.size() <= 192 &&
      roman_decode(raw_keys) == orig) {
    rom = raw_keys;
    using_aligned_raw = true;
  } else {
    rom = kana_to_romaji(orig);
  }
  if (rom.size() < 2 || rom.size() > 192)
    return {};

  const auto touch_hints =
      using_aligned_raw ? parse_touch_hints(touch_hints_spec)
                        : std::vector<TouchHint>{};
  auto lookup_touch_hint = [&](size_t idx, char key) -> double {
    for (const auto &h : touch_hints) {
      if (h.index == idx && h.key == key)
        return h.weight;
    }
    return 0.0;
  };

  static const auto qwerty_subs = make_qwerty_sub_table();
  static const std::string vowels = "aiueo";
  static const std::string cons_chars = "kstnhmyrwgzdbp";
  static const std::string ins_chars = "aiueokstnhmyrwgzdbp";
  const bool original_is_word = dictionary.has_reading(orig);

  std::vector<std::pair<std::string, bool>> rom_forms = {{rom, using_aligned_raw}};
  if (!using_aligned_raw) {
    const std::string rom_k = kana_to_romaji(orig, true);
    if (rom_k != rom && rom_k.size() >= 2 && rom_k.size() <= 192)
      rom_forms.push_back({rom_k, false});
  }

  for (const auto &rf : rom_forms) {
    const std::string &r = rf.first;
    const bool r_has_hints = rf.second && !touch_hints.empty();
    std::vector<size_t> rom_to_kana;
    std::vector<bool> is_raw_ascii;
    roman_decode_with_map(r, rom_to_kana, is_raw_ascii);
    std::vector<bool> active_rom(r.size(), false);
    for (size_t i = 0; i < r.size(); ++i) {
      const size_t k_idx = std::min(rom_to_kana[i], H - 1);
      if (active_kana[k_idx] || is_raw_ascii[i])
        active_rom[i] = true;
    }
    if (r_has_hints) {
      for (const auto &h : touch_hints) {
        if (h.weight >= 0.5 && h.index < r.size())
          active_rom[h.index] = true;
      }
    }

    for (size_t i = 0; i < r.size(); ++i) {
      const char ch = r[i];
      if (!active_rom[i] || ((ch < 'a' || ch > 'z') && ch != '-'))
        continue;

      // When the physical keys agree with the composing reading, a doubled
      // key missing at a yōon boundary is cheaper than an arbitrary insertion.
      if (rf.second && !original_is_word) {
        const auto k = rom_to_kana[i];
        const bool nasal = ch == 'n' && k + 1 < H && orig[k] == U'に' &&
                           i + 2 < r.size() && r[i + 1] == 'y' &&
                           (r[i + 2] == 'a' || r[i + 2] == 'u' || r[i + 2] == 'o');
        const bool long_vowel = ch == 'u' && i > 0 && r[i - 1] == 'y' &&
                                k + 2 < H && orig[k + 1] == U'ゅ' &&
                                orig[k + 2] != U'う';
        if (nasal || long_vowel) {
          auto variant = r;
          variant.insert(i, 1, ch);
          add(roman_decode(variant), 0.60);
        }
      }

      if (ch == '-') {
        const double hint_l = r_has_hints ? lookup_touch_hint(i, 'l') : 0.0;
        const double hint_p = r_has_hints ? lookup_touch_hint(i, 'p') : 0.0;
        {
          auto v = r;
          v[i] = 'l';
          const double cost =
              (hint_l > 0.0) ? std::max(0.60, 1.45 - 0.4 * hint_l) : 1.45;
          add(roman_decode(v), cost);
        }
        {
          auto v = r;
          v[i] = 'p';
          const double cost =
              (hint_p > 0.0) ? std::max(0.60, 1.50 - 0.4 * hint_p) : 1.50;
          add(roman_decode(v), cost);
        }
        if (r_has_hints) {
          for (const auto &h : touch_hints) {
            if (h.index == i && h.key != '-' && h.key != 'l' && h.key != 'p') {
              auto v = r;
              v[i] = h.key;
              add(roman_decode(v), std::max(0.70, 1.75 - 0.4 * h.weight));
            }
          }
        }
        continue;
      }

      // 1. Distance-proportional QWERTY adjacent substitution + TouchHint bonus
      for (const auto &sub : qwerty_subs[size_t(ch - 'a')]) {
        double cost = sub.second;
        if (r_has_hints) {
          const double hw = lookup_touch_hint(i, sub.first);
          if (hw > 0.0)
            cost = std::max(0.60, cost - 0.4 * hw);
        }
        auto v = r;
        v[i] = sub.first;
        add(roman_decode(v), cost);
      }
      if (ch == 'l' || ch == 'p') {
        const double base_dash =
            (ch == 'l') ? (is_raw_ascii[i] ? 1.15 : 1.50)
                        : (is_raw_ascii[i] ? 1.35 : 1.65);
        const double hint_dash = r_has_hints ? lookup_touch_hint(i, '-') : 0.0;
        const double cost =
            (hint_dash > 0.0) ? std::max(0.60, base_dash - 0.4 * hint_dash)
                              : base_dash;
        auto v = r;
        v[i] = '-';
        add(roman_decode(v), cost);
      }
      if (r_has_hints) {
        for (const auto &h : touch_hints) {
          if (h.index == i && h.key != ch && h.key != '-' &&
              qwerty_sub_cost(ch, h.key) <= 0.0) {
            auto v = r;
            v[i] = h.key;
            add(roman_decode(v), std::max(0.75, 1.8 - 0.4 * h.weight));
          } else if (h.index == i && h.key == '-' && ch != 'l' && ch != 'p') {
            auto v = r;
            v[i] = '-';
            add(roman_decode(v), std::max(0.70, 1.75 - 0.4 * h.weight));
          }
        }
      }
      // 1b. Non-adjacent vowel slips and p/h, t/d+i/u romaji confusions
      if (vowels.find(ch) != std::string::npos) {
        for (char vch : vowels) {
          if (vch != ch && qwerty_sub_cost(ch, vch) <= 0.0) {
            auto v = r;
            v[i] = vch;
            add(roman_decode(v), 2.30);
          }
        }
      } else if (i + 1 < r.size() &&
                 vowels.find(r[i + 1]) != std::string::npos) {
        if (ch == 'p') {
          auto v = r;
          v[i] = 'h';
          add(roman_decode(v), 2.35);
        } else if ((ch == 't' || ch == 'd') &&
                   (r[i + 1] == 'i' || r[i + 1] == 'u')) {
          auto v = r;
          v[i] = 'c';
          add(roman_decode(v), 2.35);
          v[i] = 's';
          add(roman_decode(v), 2.35);
        }
      }

      // 2. Transposition r[i] <-> r[i+1] & romaji-split transpositions
      if (i + 1 < r.size() && r[i + 1] >= 'a' && r[i + 1] <= 'z' &&
          r[i] != r[i + 1]) {
        auto v = r;
        std::swap(v[i], v[i + 1]);
        const double trans_cost =
            (qwerty_left_hand(r[i]) != qwerty_left_hand(r[i + 1])) ? 1.15
                                                                   : 1.45;
        add(roman_decode(v), trans_cost);
      }
      if (i + 2 < r.size() && r[i + 2] == 'a' && r[i + 1] >= 'a' &&
          r[i + 1] <= 'z') {
        add(roman_decode(r.substr(0, i) + std::string(1, r[i + 1]) +
                         std::string(1, r[i]) + r.substr(i + 3)),
            1.95);
      }
      if (i + 3 < r.size() && r[i + 1] == 'a' &&
          vowels.find(r[i + 3]) != std::string::npos) {
        add(roman_decode(r.substr(0, i + 1) + std::string(1, r[i + 3]) +
                         std::string(1, r[i + 2]) + r.substr(i + 4)),
            1.95);
        if (vowels.find(ch) == std::string::npos) {
          for (char vch : vowels) {
            if (i + 4 == r.size() && vch == r[i + 1])
              continue;
            add(roman_decode(r.substr(0, i + 1) + std::string(1, vch) +
                             r.substr(i + 4)),
                2.25);
          }
        }
      }

      // 3a. Structured omission: floating ASCII consonant + vowel insertion
      if (is_raw_ascii[i]) {
        for (char vch : vowels) {
          add(roman_decode(r.substr(0, i + 1) + std::string(1, vch) +
                           r.substr(i + 1)),
              1.25);
        }
      }

      // 3b. Missing vowel or consonant insertion at active romaji positions
      for (char ich : ins_chars) {
        const bool is_v = vowels.find(ich) != std::string::npos;
        add(roman_decode(r.substr(0, i) + std::string(1, ich) + r.substr(i)),
            is_v ? 2.35 : 2.65);
      }

      // 4. Targeted extra-key deletions
      const bool ch_is_vowel = vowels.find(ch) != std::string::npos;
      if (is_raw_ascii[i]) {
        add(roman_decode(r.substr(0, i) + r.substr(i + 1)), 1.05, true);
        if (i >= 2 && r[i - 1] == 'a') {
          for (const auto &sub : qwerty_subs[size_t(ch - 'a')]) {
            auto v = r.substr(0, i - 1) + std::string(1, sub.first) +
                     r.substr(i + 1);
            add(roman_decode(v), 1.7);
          }
        }
      } else {
        const bool adj_vowel_a =
            ch == 'a' &&
            ((i > 0 && vowels.find(r[i - 1]) != std::string::npos) ||
             (i + 1 < r.size() && vowels.find(r[i + 1]) != std::string::npos));
        const bool is_dup = (i > 0 && r[i] == r[i - 1]) ||
                            (i + 1 < r.size() && r[i] == r[i + 1]);
        const auto close_non_vowel_adj = [&](size_t j) {
          const char b = r[j];
          if (b < 'a' || b > 'z')
            return false;
          if (ch_is_vowel && vowels.find(b) != std::string::npos)
            return false;
          double ax = 0, ay = 0, bx = 0, by = 0;
          return qwerty_key_pos(ch, ax, ay) && qwerty_key_pos(b, bx, by) &&
                 std::hypot(ax - bx, ay - by) <= 1.26;
        };
        const bool adj_neighbor =
            (i > 0 && close_non_vowel_adj(i - 1)) ||
            (i + 1 < r.size() && close_non_vowel_adj(i + 1));
        if (adj_vowel_a) {
          add(roman_decode(r.substr(0, i) + r.substr(i + 1)), 1.0);
        } else if (is_dup) {
          const bool trailing_vowel_dup = (i + 1 == r.size() && ch_is_vowel);
          add(roman_decode(r.substr(0, i) + r.substr(i + 1)),
              trailing_vowel_dup ? 1.45 : 1.05, !trailing_vowel_dup);
        } else if (adj_neighbor) {
          add(roman_decode(r.substr(0, i) + r.substr(i + 1)), 1.45);
        } else {
          add(roman_decode(r.substr(0, i) + r.substr(i + 1)), 2.55);
        }
      }
    }
    if (!r.empty() && active_rom[r.size() - 1]) {
      for (char ich : ins_chars) {
        const bool is_v = vowels.find(ich) != std::string::npos;
        add(roman_decode(r + std::string(1, ich)), is_v ? 2.70 : 2.85);
      }
    }
  }

  // Kana-level structured omissions, deletions, transpositions, and n/particle fixes
  static constexpr std::u32string_view small_kana = U"ぁぃぅぇぉゃゅょゎ";
  static constexpr std::u32string_view contracted_y = U"ゃゅょ";
  static constexpr std::u32string_view full_y = U"やゆよ";
  for (size_t k = 0; k < H; ++k) {
    if (!active_kana[k])
      continue;
    // Repeating a romaji syllable repeats the whole mora, not a single key.
    if (k + 1 < H && orig[k] == orig[k + 1] &&
        orig[k] >= U'か' && orig[k] <= U'ゔ' &&
        orig[k] != U'っ' && orig[k] != U'ん' &&
        small_kana.find(orig[k]) == small_kana.npos &&
        (k + 2 == H || small_kana.find(orig[k + 2]) == small_kana.npos))
      add(orig.substr(0, k) + orig.substr(k + 1), 1.05, true);
    // A missed repeated key can drop the う after ゅ, or fuse ん + や/ゆ/よ
    // into にゃ/にゅ/にょ. Favor the repair only with whole-word evidence.
    if (!original_is_word && k + 1 < H) {
      const auto next = orig[k + 1];
      if (orig[k] == U'ゅ' && next != U'う') {
        auto candidate = orig;
        candidate.insert(k + 1, 1, U'う');
        if (dictionary.has_reading(candidate))
          add(candidate, 0.75);
      } else if (k > 0 && orig[k] == U'に') {
        const auto y = contracted_y.find(next);
        if (y != contracted_y.npos) {
          auto candidate = orig;
          candidate[k] = U'ん';
          candidate[k + 1] = full_y[y];
          if (dictionary.has_reading(candidate))
            add(candidate, 1.15);
        }
      }
    }
    if (specials.find(orig[k]) != std::u32string::npos)
      add(orig.substr(0, k) + orig.substr(k + 1), 1.05, true);
    if (sokuon_next.find(orig[k]) != std::u32string::npos)
      add(orig.substr(0, k) + U"っ" + orig.substr(k), 1.45);
    if (k >= 1 && orig[k - 1] != U'？' && orig[k - 1] != U'！' &&
        orig[k - 1] != U'。' && orig[k - 1] != U'、')
      add(orig.substr(0, k) + U"ん" + orig.substr(k), 1.65);
    if (k >= 1 && (orig[k - 1] >= U'ぁ' && orig[k - 1] <= U'ゖ')) {
      add(orig.substr(0, k) + U"ー" + orig.substr(k), 1.85);
      if (ou_row.find(orig[k - 1]) != std::u32string::npos)
        add(orig.substr(0, k) + U"う" + orig.substr(k), 1.75);
      if (ae_row.find(orig[k - 1]) != std::u32string::npos)
        add(orig.substr(0, k) + U"い" + orig.substr(k), 1.85);
    }
    if ((orig[H - 1] >= U'ぁ' && orig[H - 1] <= U'ゖ') && k == H - 1) {
      add(orig + U"ー", 1.85);
      if (ou_row.find(orig[k]) != std::u32string::npos)
        add(orig + U"う", 1.75);
      if (ae_row.find(orig[k]) != std::u32string::npos)
        add(orig + U"い", 1.85);
      if (H >= 1)
        add(orig + U"ん", 1.65);
    }
    if (orig[k] == U'わ')
      add(orig.substr(0, k) + U"を" + orig.substr(k + 1), 1.9);
    else if (orig[k] == U'を')
      add(orig.substr(0, k) + U"わ" + orig.substr(k + 1), 1.9);

    if (k + 1 < H) {
      auto v = orig;
      std::swap(v[k], v[k + 1]);
      add(v, 1.9);
      const auto sub2 = orig.substr(k, 2);
      if (sub2 == U"なっ" || sub2 == U"なな")
        add(orig.substr(0, k) + U"ん" + orig.substr(k + 2), 1.2, true);
      else if (sub2 == U"んな")
        add(orig.substr(0, k) + U"にん" + orig.substr(k + 2), 1.8);
      else if (sub2 == U"うじ")
        add(orig.substr(0, k) + U"じゅ" + orig.substr(k + 2), 1.8);
      else if (orig[k] == U'ん') {
        char32_t na_col = 0;
        switch (orig[k + 1]) {
        case U'あ': na_col = U'な'; break;
        case U'い': na_col = U'に'; break;
        case U'う': na_col = U'ぬ'; break;
        case U'え': na_col = U'ね'; break;
        case U'お': na_col = U'の'; break;
        default: break;
        }
        if (na_col != 0)
          add(orig.substr(0, k) + std::u32string(1, na_col) + orig.substr(k + 2),
              1.3, true);
      }
    }
  }
  } // end if (is_flick_mode) / else QWERTY

  // O(1) Local-Delta Dictionary Pre-filtering & Diversity Selection
  struct ScoredItem {
    double rank_score;
    double local_delta;
    double penalty;
    size_t edit_l;
    size_t edit_r;
    size_t anchor_l;
    size_t cand_sub_len;
    std::u32string reading;
  };
  std::unordered_map<uint32_t, double> orig_sub_cache;
  std::vector<ScoredItem> items;
  items.reserve(penalties.size());
  const std::u32string_view orig_sv(orig);
  for (const auto &kv : penalties) {
    const std::u32string &cand = kv.first;
    if (cand == orig)
      continue;
    size_t l = 0;
    while (l < H && l < cand.size() && orig[l] == cand[l])
      ++l;
    size_t suf = 0;
    while (suf < H - l && suf < cand.size() - l &&
           orig[H - 1 - suf] == cand[cand.size() - 1 - suf])
      ++suf;
    const size_t orig_r = H - suf;
    const size_t cand_r = cand.size() - suf;
    const size_t min_al = l > 10 ? l - 10 : 0;
    size_t anchor_l = l > 6 ? l - 6 : 0;
    while (anchor_l > min_al && !orig_analysis.is_boundary[anchor_l])
      --anchor_l;
    const size_t max_ar = std::min(H, orig_r + 10);
    size_t anchor_r = std::min(H, orig_r + 6);
    while (anchor_r < max_ar && !orig_analysis.is_boundary[anchor_r])
      ++anchor_r;
    const uint32_t cache_key =
        (uint32_t(anchor_l) << 16) | uint32_t(anchor_r);
    double orig_sub_score = 0.0;
    auto cache_it = orig_sub_cache.find(cache_key);
    if (cache_it != orig_sub_cache.end()) {
      orig_sub_score = cache_it->second;
    } else {
      orig_sub_score =
          dictionary.score_only(orig_sv.substr(anchor_l, anchor_r - anchor_l));
      orig_sub_cache.emplace(cache_key, orig_sub_score);
    }
    const int cand_sub_len =
        int(anchor_r - anchor_l) + int(cand_r) - int(orig_r);
    if (cand_sub_len <= 0)
      continue;
    const double cand_sub_score = dictionary.score_only(
        std::u32string_view(cand).substr(anchor_l, size_t(cand_sub_len)));
    const double pre_pen_weight = is_flick_mode ? 5.5 : 4.0;
    const double local_delta = cand_sub_score - orig_sub_score;
    const double rank_score = local_delta - kv.second * pre_pen_weight;
    items.push_back({rank_score, local_delta, kv.second, l, orig_r, anchor_l,
                     size_t(cand_sub_len), cand});
  }
  std::sort(items.begin(), items.end(), [](const auto &a, const auto &b) {
    return a.rank_score != b.rank_score ? a.rank_score > b.rank_score
                                        : a.reading < b.reading;
  });

  std::vector<ReadingCandidate> out;
  out.push_back({orig, {orig_analysis.total_score, orig_analysis.best_surface},
                 0.0, 0.0, 0, 0});
  std::vector<bool> picked(items.size(), false);
  std::unordered_map<size_t, size_t> bucket_counts;
  for (size_t i = 0; i < items.size() && out.size() < limit; ++i) {
    const size_t bucket = items[i].edit_l / 3;
    if (bucket_counts[bucket] >= 4)
      continue;
    bucket_counts[bucket]++;
    picked[i] = true;
    const auto cand_sub = dictionary.convert_fast(
        items[i].reading.substr(items[i].anchor_l, items[i].cand_sub_len));
    out.push_back({items[i].reading, cand_sub, items[i].penalty,
                   items[i].local_delta, items[i].edit_l, items[i].edit_r});
  }
  for (size_t i = 0; i < items.size() && out.size() < limit; ++i) {
    if (picked[i])
      continue;
    const auto cand_sub = dictionary.convert_fast(
        items[i].reading.substr(items[i].anchor_l, items[i].cand_sub_len));
    out.push_back({items[i].reading, cand_sub, items[i].penalty,
                   items[i].local_delta, items[i].edit_l, items[i].edit_r});
  }
  return out;
}

struct ScoringWindow {
  size_t win_l = 0;
  size_t win_r = 0;
  std::u32string left_context;
  std::u32string orig_slice;
  DictionaryPath orig_path;
  struct Member {
    size_t candidate_index = 0;
    std::u32string cand_slice;
    DictionaryPath cand_path;
  };
  std::vector<Member> members;
};

inline std::vector<ScoringWindow>
build_scoring_windows(const std::u32string &orig,
                      const std::u32string &ctx_u32,
                      const std::vector<ReadingCandidate> &candidates,
                      const ChannelDictionary &dictionary,
                      InputKeyboardMode mode = InputKeyboardMode::Qwerty) {
  std::vector<ScoringWindow> windows;
  const size_t H = orig.size();
  if (candidates.size() <= 1 || H == 0)
    return windows;

  auto tail_context = [&](const std::u32string &full_ctx) -> std::u32string {
    return full_ctx.size() > 48 ? full_ctx.substr(full_ctx.size() - 48)
                                : full_ctx;
  };

  if (H <= 24) {
    ScoringWindow win;
    win.win_l = 0;
    win.win_r = H;
    win.left_context = tail_context(ctx_u32);
    win.orig_slice = orig;
    win.orig_path = candidates[0].path;
    for (size_t i = 1; i < candidates.size(); ++i) {
      win.members.push_back(
          {i, candidates[i].reading, candidates[i].path});
    }
    windows.push_back(std::move(win));
    return windows;
  }

  const auto orig_analysis = dictionary.analyze(orig);
  std::vector<size_t> order(candidates.size() - 1);
  for (size_t i = 0; i < order.size(); ++i)
    order[i] = i + 1;
  std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
    return candidates[a].edit_l != candidates[b].edit_l
               ? candidates[a].edit_l < candidates[b].edit_l
               : a < b;
  });

  struct Cluster {
    size_t min_l = 0;
    size_t max_r = 0;
    double best_rank = -std::numeric_limits<double>::infinity();
    std::vector<size_t> indices;
  };
  const double pre_pen_weight = is_flick_keyboard_mode(mode) ? 5.5 : 4.0;
  std::vector<Cluster> clusters;
  for (size_t idx : order) {
    const auto &c = candidates[idx];
    const double rank = c.local_delta - c.penalty * pre_pen_weight;
    if (!clusters.empty() &&
        std::max(clusters.back().max_r, c.edit_r) - clusters.back().min_l <= 10) {
      clusters.back().max_r = std::max(clusters.back().max_r, c.edit_r);
      clusters.back().best_rank = std::max(clusters.back().best_rank, rank);
      clusters.back().indices.push_back(idx);
    } else {
      clusters.push_back({c.edit_l, c.edit_r, rank, {idx}});
    }
  }
  std::sort(clusters.begin(), clusters.end(),
            [](const Cluster &a, const Cluster &b) {
              return a.best_rank != b.best_rank ? a.best_rank > b.best_rank
                                                : a.min_l < b.min_l;
            });
  if (clusters.size() > 3)
    clusters.resize(3);

  auto snap_left = [&](size_t target, size_t limit_max) {
    size_t best = target;
    int best_dist = 99;
    for (int delta = -3; delta <= 3; ++delta) {
      const long long p = static_cast<long long>(target) + delta;
      if (p < 0 || static_cast<size_t>(p) > limit_max)
        continue;
      if (orig_analysis.is_boundary[size_t(p)] && std::abs(delta) < best_dist) {
        best_dist = std::abs(delta);
        best = size_t(p);
      }
    }
    return best;
  };
  auto snap_right = [&](size_t target, size_t limit_min) {
    size_t best = target;
    int best_dist = 99;
    for (int delta = 3; delta >= -3; --delta) {
      const long long p = static_cast<long long>(target) + delta;
      if (p < static_cast<long long>(limit_min) || size_t(p) > H)
        continue;
      if (orig_analysis.is_boundary[size_t(p)] && std::abs(delta) < best_dist) {
        best_dist = std::abs(delta);
        best = size_t(p);
      }
    }
    return best;
  };

  for (const auto &cl : clusters) {
    size_t win_l = snap_left(cl.min_l > 8 ? cl.min_l - 8 : 0, cl.min_l);
    size_t win_r = snap_right(std::min(H, cl.max_r + 10), cl.max_r);
    if (win_r - win_l > 28) {
      win_r = std::min(win_r, std::max(cl.max_r, win_l + 28));
      if (win_r - win_l > 28)
        win_l = std::max(win_l, std::min(cl.min_l, win_r - 28));
    }
    ScoringWindow win;
    win.win_l = win_l;
    win.win_r = win_r;
    const auto pfx_surface = decode_utf8(orig_analysis.surface_prefix(win_l));
    win.left_context = tail_context(ctx_u32 + pfx_surface);
    win.orig_slice = orig.substr(win_l, win_r - win_l);
    win.orig_path = dictionary.convert_fast(win.orig_slice);
    for (size_t idx : cl.indices) {
      const auto &c = candidates[idx];
      if (c.edit_l < win_l || c.edit_r > win_r)
        continue;
      const int slice_len =
          int(win_r - win_l) + int(c.reading.size()) - int(H);
      if (slice_len <= 0 || win_l + size_t(slice_len) > c.reading.size())
        continue;
      const auto c_slice = c.reading.substr(win_l, size_t(slice_len));
      win.members.push_back(
          {idx, c_slice, dictionary.convert_fast(c_slice)});
    }
    if (!win.members.empty())
      windows.push_back(std::move(win));
  }
  return windows;
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

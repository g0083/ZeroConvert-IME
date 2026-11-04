#pragma once
#include "roman_table.hpp"
#include <algorithm>
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
inline bool kana(const std::u32string &s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || c == U'ー';
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
    else
      result += char32_t(keys[i]);
    ++i;
  }
  return result;
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
    candidates.push_back({entry.first, entry.second});
  std::sort(candidates.begin(), candidates.end(),
            [](const auto &a, const auto &b) {
              return a.path.score != b.path.score ? a.path.score > b.path.score
                                                  : a.reading < b.reading;
            });
  if (candidates.size() > 8)
    candidates.resize(8);
  if (std::none_of(candidates.begin(), candidates.end(),
                   [&](const auto &c) { return c.reading == original; }))
    candidates.back() = {original, grouped.at(original)};
  return candidates;
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

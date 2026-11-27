#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <string>
#include <string_view>
#include <vector>

enum class InputKeyboardMode { Qwerty, Flick, FlickToggle };

inline bool is_flick_keyboard_mode(InputKeyboardMode mode) {
  return mode == InputKeyboardMode::Flick ||
         mode == InputKeyboardMode::FlickToggle;
}

inline InputKeyboardMode parse_input_keyboard_mode(const std::string &mode_str) {
  if (mode_str == "flick_toggle")
    return InputKeyboardMode::FlickToggle;
  if (mode_str == "flick")
    return InputKeyboardMode::Flick;
  return InputKeyboardMode::Qwerty;
}

struct FlickTouchHint {
  size_t index = 0;
  char32_t ch = 0;
  double weight = 0.0;
};

inline std::u32string flick_decode_utf8(const std::string &s) {
  std::u32string out;
  out.reserve(s.size());
  size_t i = 0;
  const size_t n = s.size();
  while (i < n) {
    const unsigned char c0 = static_cast<unsigned char>(s[i]);
    if (c0 < 0x80) {
      out.push_back(char32_t(c0));
      ++i;
    } else if ((c0 >> 5) == 0x6 && i + 1 < n) {
      const unsigned char c1 = static_cast<unsigned char>(s[i + 1]);
      if ((c1 & 0xC0) != 0x80)
        return {};
      out.push_back(char32_t(((c0 & 0x1F) << 6) | (c1 & 0x3F)));
      i += 2;
    } else if ((c0 >> 4) == 0xE && i + 2 < n) {
      const unsigned char c1 = static_cast<unsigned char>(s[i + 1]);
      const unsigned char c2 = static_cast<unsigned char>(s[i + 2]);
      if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80)
        return {};
      out.push_back(
          char32_t(((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F)));
      i += 3;
    } else if ((c0 >> 3) == 0x1E && i + 3 < n) {
      const unsigned char c1 = static_cast<unsigned char>(s[i + 1]);
      const unsigned char c2 = static_cast<unsigned char>(s[i + 2]);
      const unsigned char c3 = static_cast<unsigned char>(s[i + 3]);
      if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80)
        return {};
      out.push_back(char32_t(((c0 & 0x07) << 18) | ((c1 & 0x3F) << 12) |
                             ((c2 & 0x3F) << 6) | (c3 & 0x3F)));
      i += 4;
    } else {
      return {};
    }
  }
  return out;
}

inline std::vector<FlickTouchHint>
parse_flick_touch_hints(const std::string &spec) {
  std::vector<FlickTouchHint> hints;
  if (spec.empty() || spec.rfind("v2;", 0) == 0)
    return hints;
  const std::u32string u32 = flick_decode_utf8(spec);
  if (u32.empty())
    return hints;
  size_t pos = 0;
  size_t seen_indices = 0;
  while (pos < u32.size() && seen_indices < 128 && hints.size() < 512) {
    size_t semi = u32.find(U';', pos);
    if (semi == std::u32string::npos)
      semi = u32.size();
    const auto entry = u32.substr(pos, semi - pos);
    pos = semi + 1;
    const size_t colon = entry.find(U':');
    if (colon == std::u32string::npos || colon == 0)
      continue;
    bool valid_idx = true;
    size_t idx = 0;
    for (size_t k = 0; k < colon; ++k) {
      if (entry[k] < U'0' || entry[k] > U'9') {
        valid_idx = false;
        break;
      }
      idx = idx * 10 + size_t(entry[k] - U'0');
      if (idx >= 128) {
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
      size_t comma = entry.find(U',', apos);
      if (comma == std::u32string::npos)
        comma = entry.size();
      const auto item = entry.substr(apos, comma - apos);
      apos = comma + 1;
      if (item.size() < 3 || item[1] != U'=')
        continue;
      std::string w_ascii;
      w_ascii.reserve(item.size() - 2);
      bool ok_w = true;
      for (size_t k = 2; k < item.size(); ++k) {
        const char32_t wc = item[k];
        if ((wc >= U'0' && wc <= U'9') || wc == U'.') {
          w_ascii += char(wc);
        } else {
          ok_w = false;
          break;
        }
      }
      if (!ok_w || w_ascii.empty())
        continue;
      char *endp = nullptr;
      const double w = std::strtod(w_ascii.c_str(), &endp);
      if (endp != w_ascii.c_str() + w_ascii.size() || !std::isfinite(w) ||
          w < 0.2 || w > 1.0)
        continue;
      hints.push_back({idx, item[0], w});
      ++alts_for_idx;
    }
  }
  return hints;
}

enum class FlickMod { Plain = 0, Dakuten = 1, Handakuten = 2, Small = 3 };
struct FlickKeySpec {
  int row;
  int col;
  char32_t dirs[5]; // [CENTER, LEFT, UP, RIGHT, DOWN]
};
inline const std::array<FlickKeySpec, 10> &flick_kana_keys() {
  static const std::array<FlickKeySpec, 10> k = {{
      {0, 0, {U'あ', U'い', U'う', U'え', U'お'}},
      {0, 1, {U'か', U'き', U'く', U'け', U'こ'}},
      {0, 2, {U'さ', U'し', U'す', U'せ', U'そ'}},
      {1, 0, {U'た', U'ち', U'つ', U'て', U'と'}},
      {1, 1, {U'な', U'に', U'ぬ', U'ね', U'の'}},
      {1, 2, {U'は', U'ひ', U'ふ', U'へ', U'ほ'}},
      {2, 0, {U'ま', U'み', U'む', U'め', U'も'}},
      {2, 1, {U'や', U'（', U'ゆ', U'）', U'よ'}},
      {2, 2, {U'ら', U'り', U'る', U'れ', U'ろ'}},
      {3, 1, {U'わ', U'を', U'ん', U'ー', U'〜'}},
  }};
  return k;
}
inline char32_t flick_compose(int key, int dir, FlickMod mod) {
  if (key < 0 || key >= 10 || dir < 0 || dir >= 5)
    return 0;
  const char32_t base = flick_kana_keys()[size_t(key)].dirs[size_t(dir)];
  if (mod == FlickMod::Plain)
    return base;
  if (mod == FlickMod::Dakuten) {
    if (base == U'う')
      return U'ゔ';
    if (key == 1 || key == 2 || key == 5)
      return base + 1; // か->が, さ->ざ, は->ば
    if (key == 3) {
      if (dir == 0 || dir == 1 || dir == 2)
        return base + 1; // た->だ, ち->ぢ, つ->づ
      if (dir == 3 || dir == 4)
        return base + 1; // て->で, と->ど
    }
    return 0;
  }
  if (mod == FlickMod::Handakuten) {
    if (key == 5)
      return base + 2; // は->ぱ
    return 0;
  }
  if (mod == FlickMod::Small) {
    switch (base) {
    case U'あ': return U'ぁ';
    case U'い': return U'ぃ';
    case U'う': return U'ぅ';
    case U'え': return U'ぇ';
    case U'お': return U'ぉ';
    case U'つ': return U'っ';
    case U'や': return U'ゃ';
    case U'ゆ': return U'ゅ';
    case U'よ': return U'ょ';
    case U'わ': return U'ゎ';
    default: return 0;
    }
  }
  return 0;
}
inline bool flick_decompose(char32_t c, int &key, int &dir, FlickMod &mod) {
  for (int k = 0; k < 10; ++k) {
    for (int d = 0; d < 5; ++d) {
      for (FlickMod m :
           {FlickMod::Plain, FlickMod::Dakuten, FlickMod::Handakuten,
            FlickMod::Small}) {
        if (flick_compose(k, d, m) == c) {
          key = k;
          dir = d;
          mod = m;
          return true;
        }
      }
    }
  }
  return false;
}

inline int flick_punc_dir(char32_t c) {
  switch (c) {
  case U'、': return 0;
  case U'。': return 1;
  case U'？': return 2;
  case U'！': return 3;
  case U'・': return 4;
  default: return -1;
  }
}

inline const std::array<char32_t, 5> &flick_toggle_cycle(int key) {
  static const std::array<char32_t, 5> ya_cycle = {
      U'や', U'ゆ', U'よ', U'（', U'）'};
  static const std::array<std::array<char32_t, 5>, 10> cycles = {{
      {U'あ', U'い', U'う', U'え', U'お'},
      {U'か', U'き', U'く', U'け', U'こ'},
      {U'さ', U'し', U'す', U'せ', U'そ'},
      {U'た', U'ち', U'つ', U'て', U'と'},
      {U'な', U'に', U'ぬ', U'ね', U'の'},
      {U'は', U'ひ', U'ふ', U'へ', U'ほ'},
      {U'ま', U'み', U'む', U'め', U'も'},
      ya_cycle,
      {U'ら', U'り', U'る', U'れ', U'ろ'},
      {U'わ', U'を', U'ん', U'ー', U'〜'},
  }};
  return (key >= 0 && key < 10) ? cycles[size_t(key)] : ya_cycle;
}

enum class FlickObsKind {
  None,
  Flick,
  ToggleFirst,
  ToggleCycle,
  FlickMod,
  ToggleMod
};

struct FlickObservation {
  bool present = false;
  FlickObsKind kind = FlickObsKind::None;
  double nx = 0.5;
  double ny = 0.5;
  double dx = 0.0;
  double dy = 0.0;
};

inline bool is_v2_flick_obs_spec(const std::string &spec) {
  return spec.rfind("v2;", 0) == 0;
}

inline bool flick_parse_int_range(std::string_view token, int min_v, int max_v,
                                  int &out) {
  if (token.empty() || token.size() > 5)
    return false;
  size_t i = 0;
  bool neg = false;
  if (token[0] == '-') {
    neg = true;
    i = 1;
    if (i >= token.size())
      return false;
  }
  int val = 0;
  for (; i < token.size(); ++i) {
    const char c = token[i];
    if (c < '0' || c > '9')
      return false;
    val = val * 10 + (c - '0');
  }
  if (neg)
    val = -val;
  if (val < min_v || val > max_v)
    return false;
  out = val;
  return true;
}

inline std::vector<FlickObservation>
parse_flick_observations(const std::string &spec, size_t H) {
  std::vector<FlickObservation> obs(H);
  if (!is_v2_flick_obs_spec(spec) || H == 0)
    return obs;
  size_t pos = 3;
  size_t seen = 0;
  const std::string_view sv(spec);
  while (pos < sv.size() && seen < 128) {
    size_t semi = sv.find(';', pos);
    if (semi == std::string_view::npos)
      semi = sv.size();
    const auto entry = sv.substr(pos, semi - pos);
    pos = semi + 1;
    if (entry.empty())
      continue;
    const size_t colon = entry.find(':');
    if (colon == std::string_view::npos || colon == 0)
      continue;
    int idx = 0;
    if (!flick_parse_int_range(entry.substr(0, colon), 0, 127, idx))
      continue;
    if (size_t(idx) >= H)
      continue;
    const auto rest = entry.substr(colon + 1);
    std::array<std::string_view, 5> parts;
    size_t p_count = 0;
    size_t start = 0;
    while (start <= rest.size() && p_count < 5) {
      size_t comma = rest.find(',', start);
      if (comma == std::string_view::npos)
        comma = rest.size();
      parts[p_count++] = rest.substr(start, comma - start);
      if (comma == rest.size())
        break;
      start = comma + 1;
    }
    if (p_count != 5 || start < rest.size() && rest.find(',', start) != std::string_view::npos)
      continue;
    if (parts[0].size() != 1)
      continue;
    FlickObsKind kind = FlickObsKind::None;
    switch (parts[0][0]) {
    case 'f': kind = FlickObsKind::Flick; break;
    case 't': kind = FlickObsKind::ToggleFirst; break;
    case 'c': kind = FlickObsKind::ToggleCycle; break;
    case 'm': kind = FlickObsKind::FlickMod; break;
    case 'd': kind = FlickObsKind::ToggleMod; break;
    default: continue;
    }
    int nx_i = 0, ny_i = 0, dx_i = 0, dy_i = 0;
    if (!flick_parse_int_range(parts[1], 0, 100, nx_i) ||
        !flick_parse_int_range(parts[2], 0, 100, ny_i) ||
        !flick_parse_int_range(parts[3], -30, 30, dx_i) ||
        !flick_parse_int_range(parts[4], -30, 30, dy_i))
      continue;
    obs[size_t(idx)] = {true,
                        kind,
                        double(nx_i) / 100.0,
                        double(ny_i) / 100.0,
                        double(dx_i) / 10.0,
                        double(dy_i) / 10.0};
    ++seen;
  }
  return obs;
}

inline double flick_cell_switch_cost(int src_row, int src_col, int dst_row,
                                     int dst_col, const FlickObservation &obs) {
  if (src_row == dst_row && src_col == dst_col)
    return 0.0;
  const int dr = std::abs(dst_row - src_row);
  const int dc = std::abs(dst_col - src_col);
  if (!obs.present) {
    return (dr + dc == 1) ? 1.30 : 1.85;
  }
  const double ex = (dst_col < src_col)
                        ? obs.nx
                        : ((dst_col > src_col) ? (1.0 - obs.nx) : 0.0);
  const double ey = (dst_row < src_row)
                        ? obs.ny
                        : ((dst_row > src_row) ? (1.0 - obs.ny) : 0.0);
  const double norm_dist = std::hypot(ex, ey);
  return std::clamp(0.45 + 2.8 * norm_dist, 0.45, 2.45);
}

inline bool flick_is_adjacent_cardinal(int a, int b) {
  return (a == 1 && (b == 2 || b == 4)) || (a == 2 && (b == 1 || b == 3)) ||
         (a == 3 && (b == 2 || b == 4)) || (a == 4 && (b == 1 || b == 3));
}

inline double flick_dir_switch_cost(int committed_dir, int alt_dir,
                                    const FlickObservation &obs) {
  if (committed_dir == alt_dir)
    return 0.0;
  if (!obs.present) {
    if (committed_dir == 0 || alt_dir == 0)
      return 1.10;
    return flick_is_adjacent_cardinal(committed_dir, alt_dir) ? 1.20 : 1.85;
  }
  const double ax = std::abs(obs.dx);
  const double ay = std::abs(obs.dy);
  const double dist = std::max(ax, ay);
  auto proj = [&](int dir) -> double {
    switch (dir) {
    case 1: return -obs.dx;
    case 2: return -obs.dy;
    case 3: return obs.dx;
    case 4: return obs.dy;
    default: return 0.0;
    }
  };
  if (committed_dir == 0 && alt_dir != 0) {
    const double p = proj(alt_dir);
    if (p >= 0.35)
      return std::clamp(1.25 - 0.85 * p, 0.45, 1.15);
    if (p > 0.0)
      return 1.45 - 0.85 * p;
    if (dist < 0.15)
      return 1.55;
    if (p <= -0.35)
      return 2.15;
    return 1.70;
  }
  if (committed_dir != 0 && alt_dir == 0) {
    if (dist <= 1.50)
      return std::clamp(0.45 + 0.90 * std::max(0.0, dist - 0.85), 0.45, 1.10);
    return std::min(2.15, 1.15 + 0.55 * (dist - 1.50));
  }
  if (flick_is_adjacent_cardinal(committed_dir, alt_dir)) {
    const double ratio = proj(alt_dir) / std::max(dist, 0.01);
    if (ratio >= 0.35)
      return std::clamp(1.20 - 0.80 * ratio, 0.45, 1.00);
    if (ratio >= 0.0)
      return 1.45 - 0.70 * ratio;
    return std::min(2.20, 1.55 + 0.80 * (-ratio));
  }
  return std::clamp(1.75 + 0.40 * std::max(0.0, dist - 1.0), 1.75, 2.50);
}

inline bool flick_obs_is_ambiguous(const FlickObservation &obs) {
  if (!obs.present)
    return false;
  if (std::min(obs.nx, 1.0 - obs.nx) <= 0.25 ||
      std::min(obs.ny, 1.0 - obs.ny) <= 0.25)
    return true;
  const double ax = std::abs(obs.dx);
  const double ay = std::abs(obs.dy);
  const double major = std::max(ax, ay);
  const double minor = std::min(ax, ay);
  if (major >= 0.40 && major <= 1.45)
    return true;
  if (major >= 0.90 && minor / std::max(major, 1e-6) >= 0.45)
    return true;
  return false;
}

template <typename AddFn>
inline void generate_flick_candidates(const std::u32string &orig,
                                      std::vector<bool> &active_kana,
                                      const std::string &touch_hints_spec,
                                      bool toggle_mode, AddFn &&add) {
  const size_t H = orig.size();
  const bool v2_mode = is_v2_flick_obs_spec(touch_hints_spec);
  const std::vector<FlickObservation> observations =
      v2_mode ? parse_flick_observations(touch_hints_spec, H)
              : std::vector<FlickObservation>{};
  const std::vector<FlickTouchHint> flick_hints =
      v2_mode ? std::vector<FlickTouchHint>{}
              : parse_flick_touch_hints(touch_hints_spec);

  auto lookup_flick_hint = [&](size_t idx, char32_t ch) -> double {
    for (const auto &h : flick_hints) {
      if (h.index == idx && h.ch == ch)
        return h.weight;
    }
    return 0.0;
  };
  if (v2_mode) {
    for (size_t i = 0; i < std::min(H, observations.size()); ++i) {
      if (flick_obs_is_ambiguous(observations[i]))
        active_kana[i] = true;
    }
  } else {
    for (const auto &h : flick_hints) {
      if (h.weight >= 0.5 && h.index < H)
        active_kana[h.index] = true;
    }
  }

  auto apply_legacy_hint = [&](size_t pos, char32_t alt, double cost) -> double {
    if (!v2_mode) {
      const double hw = lookup_flick_hint(pos, alt);
      if (hw > 0.0)
        return std::max(0.55, cost - 0.4 * hw);
    }
    return cost;
  };
  auto add_flick_sub = [&](size_t pos, char32_t alt, double base_cost) {
    if (alt == 0 || alt == orig[pos])
      return;
    const double cost = apply_legacy_hint(pos, alt, base_cost);
    auto v = orig;
    v[pos] = alt;
    add(v, cost);
  };
  auto flick_mod_cost = [](FlickMod a, FlickMod b, FlickObsKind kind) -> double {
    if (a == b)
      return 0.0;
    const bool is_modded_obs =
        (kind == FlickObsKind::FlickMod || kind == FlickObsKind::ToggleMod);
    if (is_modded_obs &&
        ((a == FlickMod::Dakuten && b == FlickMod::Handakuten) ||
         (a == FlickMod::Handakuten && b == FlickMod::Dakuten) ||
         (a == FlickMod::Small && b == FlickMod::Dakuten) ||
         (a == FlickMod::Dakuten && b == FlickMod::Small) ||
         (a == FlickMod::Dakuten && b == FlickMod::Plain)))
      return 0.85;
    if ((a == FlickMod::Plain && b == FlickMod::Dakuten) ||
        (a == FlickMod::Dakuten && b == FlickMod::Plain) ||
        (a == FlickMod::Dakuten && b == FlickMod::Handakuten) ||
        (a == FlickMod::Handakuten && b == FlickMod::Dakuten) ||
        (a == FlickMod::Small && b == FlickMod::Dakuten) ||
        (a == FlickMod::Dakuten && b == FlickMod::Small))
      return 1.00;
    if ((a == FlickMod::Plain && b == FlickMod::Handakuten) ||
        (a == FlickMod::Handakuten && b == FlickMod::Plain))
      return 1.10;
    return 1.00;
  };

  static const std::u32string sokuon_next =
      U"かきくけこさしすせそたちつてとぱぴぷぺぽ";
  static const std::u32string ou_row =
      U"おこそとのほもよろをごぞどぼぽうくすつぬふむゆるぐずづぶぷぉょゅぅ";
  static const std::u32string ae_row =
      U"あかさたなはまやらわがざだばぱえけせてねへめれげぜでべぺぁぇ";
  static const std::u32string specials = U"っんー";
  static const std::u32string yoon_prev = U"きぎしじちぢにひびぴみり";

  const auto &keys = flick_kana_keys();
  for (size_t k = 0; k < H; ++k) {
    if (!active_kana[k])
      continue;
    const FlickObservation obs =
        (v2_mode && k < observations.size()) ? observations[k]
                                             : FlickObservation{};
    int key = -1, dir = -1;
    FlickMod mod = FlickMod::Plain;
    if (flick_decompose(orig[k], key, dir, mod)) {
      const bool skip_flick_dirs_for_toggle =
          toggle_mode && (obs.kind == FlickObsKind::ToggleCycle ||
                          obs.kind == FlickObsKind::ToggleMod);
      const bool fallback_toggle_dirs =
          !toggle_mode && (obs.kind == FlickObsKind::ToggleCycle ||
                           obs.kind == FlickObsKind::ToggleMod);

      // G1: Same-key direction errors (gesture-weighted) + Joint Dir+Mod
      if (!skip_flick_dirs_for_toggle) {
        for (int d2 = 0; d2 < 5; ++d2) {
          if (d2 == dir)
            continue;
          const double dc = fallback_toggle_dirs
                                ? 1.40
                                : flick_dir_switch_cost(dir, d2, obs);
          const char32_t alt_same_mod = flick_compose(key, d2, mod);
          if (alt_same_mod != 0)
            add_flick_sub(k, alt_same_mod, dc);
          if (mod != FlickMod::Plain) {
            const char32_t alt_plain = flick_compose(key, d2, FlickMod::Plain);
            if (alt_plain != 0)
              add_flick_sub(k, alt_plain, dc + 0.20);
          }
          if (obs.present && mod == FlickMod::Plain && dc <= 0.95) {
            for (FlickMod jm :
                 {FlickMod::Dakuten, FlickMod::Small, FlickMod::Handakuten}) {
              const char32_t alt_joint = flick_compose(key, d2, jm);
              if (alt_joint != 0)
                add_flick_sub(k, alt_joint, dc + 0.85);
            }
          }
        }
      }
      if (orig[k] == U'（' || orig[k] == U'）') {
        const int paren_dir = (orig[k] == U'（') ? 1 : 3;
        add_flick_sub(k, U'や', flick_dir_switch_cost(paren_dir, 0, obs));
        add_flick_sub(k, U'ゆ', flick_dir_switch_cost(paren_dir, 2, obs));
        add_flick_sub(k, U'よ', flick_dir_switch_cost(paren_dir, 4, obs));
        add_flick_sub(k, U'ゃ', flick_dir_switch_cost(paren_dir, 0, obs) + 0.35);
        add_flick_sub(k, U'ゅ', flick_dir_switch_cost(paren_dir, 2, obs) + 0.35);
        add_flick_sub(k, U'ょ', flick_dir_switch_cost(paren_dir, 4, obs) + 0.35);
      }

      // G2: 小゛゜ modifier omission/overshoot, Yōon/Sokuon small omission, &
      // position-aware 小゛゜ (3,0) misfire onto ま/わ/corner や
      for (FlickMod m2 : {FlickMod::Plain, FlickMod::Dakuten,
                          FlickMod::Handakuten, FlickMod::Small}) {
        if (m2 == mod)
          continue;
        const char32_t alt_mod = flick_compose(key, dir, m2);
        if (alt_mod == 0)
          continue;
        double mc = flick_mod_cost(mod, m2, obs.kind);
        if (m2 == FlickMod::Small || mod == FlickMod::Small) {
          const char32_t plain = flick_compose(key, dir, FlickMod::Plain);
          if (plain == U'う' &&
              (m2 == FlickMod::Dakuten || mod == FlickMod::Dakuten)) {
            mc = 1.35;
          } else if ((key == 0 || plain == U'わ') &&
                     m2 != FlickMod::Dakuten && mod != FlickMod::Dakuten) {
            mc = 1.35;
          }
        }
        if (mod == FlickMod::Plain && m2 == FlickMod::Small) {
          if ((orig[k] == U'や' || orig[k] == U'ゆ' || orig[k] == U'よ') &&
              k > 0 && yoon_prev.find(orig[k - 1]) != std::u32string::npos) {
            mc = std::min(mc, 0.80);
          } else if (orig[k] == U'つ' && k + 1 < H &&
                     sokuon_next.find(orig[k + 1]) != std::u32string::npos) {
            mc = std::min(mc, 0.85);
          }
        }
        add_flick_sub(k, alt_mod, mc);
      }
      const bool ya_corner_misfire =
          (orig[k] == U'や' && obs.present && obs.nx < 0.25 && obs.ny > 0.75);
      if (k > 0 &&
          (orig[k] == U'ま' || orig[k] == U'わ' || ya_corner_misfire)) {
        const double misfire_cost =
            obs.present
                ? std::clamp(0.30 + flick_cell_switch_cost(keys[size_t(key)].row,
                                                           keys[size_t(key)].col,
                                                           3, 0, obs),
                             0.75, 2.20)
                : 1.55;
        int pk = -1, pd = -1;
        FlickMod pm = FlickMod::Plain;
        if (flick_decompose(orig[k - 1], pk, pd, pm)) {
          for (FlickMod pm2 :
               {FlickMod::Dakuten, FlickMod::Handakuten, FlickMod::Small}) {
            const char32_t modded = flick_compose(pk, pd, pm2);
            if (modded != 0 && modded != orig[k - 1]) {
              add(orig.substr(0, k - 1) + std::u32string(1, modded) +
                      orig.substr(k + 1),
                  misfire_cost, true);
            }
          }
        }
      }

      // G3: 3x4 neighbor cell (orthogonal & diagonal) + Joint Cell×Direction
      const int r1 = keys[size_t(key)].row;
      const int c1 = keys[size_t(key)].col;
      for (int nk = 0; nk < 10; ++nk) {
        if (nk == key)
          continue;
        const int r2 = keys[size_t(nk)].row;
        const int c2 = keys[size_t(nk)].col;
        const int dr = std::abs(r2 - r1);
        const int dc = std::abs(c2 - c1);
        if (dr > 1 || dc > 1)
          continue;
        const double cc = flick_cell_switch_cost(r1, c1, r2, c2, obs);
        const char32_t c_mod = flick_compose(nk, dir, mod);
        if (c_mod != 0)
          add_flick_sub(k, c_mod, cc);
        if (mod != FlickMod::Plain) {
          const char32_t c_plain = flick_compose(nk, dir, FlickMod::Plain);
          if (c_plain != 0)
            add_flick_sub(k, c_plain, cc + 0.20);
        }
        if (obs.present && cc <= 1.10) {
          for (int d2 = 0; d2 < 5; ++d2) {
            if (d2 == dir)
              continue;
            const double dc2 = flick_dir_switch_cost(dir, d2, obs);
            if (dc2 > 1.05)
              continue;
            const double jcost = cc + dc2 * 0.85;
            const char32_t jc_mod = flick_compose(nk, d2, mod);
            if (jc_mod != 0)
              add_flick_sub(k, jc_mod, jcost);
            if (mod != FlickMod::Plain) {
              const char32_t jc_plain =
                  flick_compose(nk, d2, FlickMod::Plain);
              if (jc_plain != 0)
                add_flick_sub(k, jc_plain, jcost + 0.20);
            }
          }
        }
      }

      // G4: Toggle-mode specific cycle ±1/±2 and split-tap merge
      const bool run_toggle_rules =
          toggle_mode || obs.kind == FlickObsKind::ToggleFirst ||
          obs.kind == FlickObsKind::ToggleCycle ||
          obs.kind == FlickObsKind::ToggleMod;
      if (run_toggle_rules) {
        const auto &cycle = flick_toggle_cycle(key);
        const char32_t plain_cur = flick_compose(key, dir, FlickMod::Plain);
        int cur_ci = -1;
        for (int ci = 0; ci < 5; ++ci) {
          if (cycle[size_t(ci)] == plain_cur) {
            cur_ci = ci;
            break;
          }
        }
        if (cur_ci >= 0) {
          auto emit_cycle_step = [&](int step, double step_cost) {
            const char32_t target_base =
                cycle[size_t((cur_ci + 5 + step) % 5)];
            int alt_dir = -1;
            for (int d2 = 0; d2 < 5; ++d2) {
              if (keys[size_t(key)].dirs[size_t(d2)] == target_base) {
                alt_dir = d2;
                break;
              }
            }
            if (alt_dir < 0)
              return;
            const char32_t alt_mod = flick_compose(key, alt_dir, mod);
            if (alt_mod != 0)
              add_flick_sub(k, alt_mod, step_cost);
            if (mod != FlickMod::Plain) {
              const char32_t alt_plain =
                  flick_compose(key, alt_dir, FlickMod::Plain);
              if (alt_plain != 0)
                add_flick_sub(k, alt_plain, step_cost + 0.20);
            }
          };
          const double step1_cost =
              (obs.kind == FlickObsKind::Flick) ? 0.95 : 0.90;
          emit_cycle_step(-1, step1_cost);
          emit_cycle_step(+1, step1_cost);
          emit_cycle_step(-2, 1.35);
          emit_cycle_step(+2, 1.35);
        }
        if (toggle_mode && k + 1 < H && mod == FlickMod::Plain) {
          int key2 = -1, dir2 = -1;
          FlickMod mod2 = FlickMod::Plain;
          if (flick_decompose(orig[k + 1], key2, dir2, mod2) && key2 == key &&
              mod2 == FlickMod::Plain) {
            int ci1 = -1, ci2 = -1;
            for (int ci = 0; ci < 5; ++ci) {
              if (cycle[size_t(ci)] == orig[k])
                ci1 = ci;
              if (cycle[size_t(ci)] == orig[k + 1])
                ci2 = ci;
            }
            if (ci1 >= 0 && ci2 >= 0) {
              const char32_t merged_ch = cycle[size_t((ci1 + 1 + ci2) % 5)];
              if (merged_ch != 0) {
                add(orig.substr(0, k) + std::u32string(1, merged_ch) +
                        orig.substr(k + 2),
                    1.10, true);
              }
            }
          }
        }
      }
    } else {
      // G3b: keyPunc (3,2) edge recovery onto adjacent わ(3,1) / ら(2,2) / や(2,1)
      const int pdir = flick_punc_dir(orig[k]);
      if (pdir >= 0) {
        for (int nk : {9, 8, 7}) {
          const double cc = flick_cell_switch_cost(3, 2, keys[size_t(nk)].row,
                                                   keys[size_t(nk)].col, obs);
          const char32_t alt = flick_compose(nk, pdir, FlickMod::Plain);
          if (alt != 0)
            add_flick_sub(k, alt, cc);
          if (pdir != 0 && obs.present && cc <= 1.10) {
            const char32_t alt0 = flick_compose(nk, 0, FlickMod::Plain);
            if (alt0 != 0)
              add_flick_sub(k, alt0, cc + 0.40);
          }
        }
      }
    }

    if (!v2_mode) {
      for (const auto &h : flick_hints) {
        if (h.index == k && h.ch != orig[k]) {
          auto v = orig;
          v[k] = h.ch;
          add(v, std::max(0.75, 1.8 - 0.4 * h.weight));
        }
      }
    }

    // G5: Structured kana omissions (っ / ん / ー / う / い)
    if (sokuon_next.find(orig[k]) != std::u32string::npos)
      add(orig.substr(0, k) + U"っ" + orig.substr(k), 1.45);
    if (k >= 1 && orig[k - 1] != U'？' && orig[k - 1] != U'！' &&
        orig[k - 1] != U'。' && orig[k - 1] != U'、')
      add(orig.substr(0, k) + U"ん" + orig.substr(k), 1.65);
    if (k >= 1 && orig[k - 1] >= U'ぁ' && orig[k - 1] <= U'ゖ') {
      add(orig.substr(0, k) + U"ー" + orig.substr(k), 1.80);
      if (ou_row.find(orig[k - 1]) != std::u32string::npos)
        add(orig.substr(0, k) + U"う" + orig.substr(k), 1.75);
      if (ae_row.find(orig[k - 1]) != std::u32string::npos)
        add(orig.substr(0, k) + U"い" + orig.substr(k), 1.85);
    }
    if (k == H - 1 && orig[H - 1] >= U'ぁ' && orig[H - 1] <= U'ゖ') {
      add(orig + U"ー", 2.15);
      if (ou_row.find(orig[k]) != std::u32string::npos)
        add(orig + U"う", 2.10);
      if (ae_row.find(orig[k]) != std::u32string::npos)
        add(orig + U"い", 2.20);
      add(orig + U"ん", 2.00);
    }

    // G6: Extra / duplicate / stray symbol deletions
    const bool is_dup = (k > 0 && orig[k] == orig[k - 1]) ||
                        (k + 1 < H && orig[k] == orig[k + 1]);
    const bool is_vowel_kana =
        (orig[k] == U'あ' || orig[k] == U'い' || orig[k] == U'う' ||
         orig[k] == U'え' || orig[k] == U'お');
    if (is_dup) {
      const bool trailing_vowel = (k == H - 1 && is_vowel_kana);
      add(orig.substr(0, k) + orig.substr(k + 1),
          trailing_vowel ? 1.45 : 1.05, !trailing_vowel);
    } else if (specials.find(orig[k]) != std::u32string::npos) {
      add(orig.substr(0, k) + orig.substr(k + 1), 1.20, true);
    } else if (orig[k] == U'（' || orig[k] == U'）' || orig[k] == U'〜' ||
               orig[k] == U'・') {
      add(orig.substr(0, k) + orig.substr(k + 1), 1.10, true);
    } else {
      add(orig.substr(0, k) + orig.substr(k + 1), 2.75);
    }

    // G7: Adjacent kana swap & particle は↔わ
    if (k + 1 < H) {
      auto v = orig;
      std::swap(v[k], v[k + 1]);
      add(v, 1.55);
    }
    if (orig[k] == U'は')
      add(orig.substr(0, k) + U"わ" + orig.substr(k + 1), 1.85);
    else if (orig[k] == U'わ')
      add(orig.substr(0, k) + U"は" + orig.substr(k + 1), 1.85);
  }
}

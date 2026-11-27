#include "channel.hpp"
#include <cassert>
#include <cmath>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>

namespace {

std::string json_field(const std::string &line, const char *name) {
  const std::string key = std::string("\"") + name + "\":\"";
  const auto at = line.find(key);
  if (at == std::string::npos)
    return {};
  const auto start = at + key.size();
  const auto end = line.find('"', start);
  return end == std::string::npos ? std::string()
                                  : line.substr(start, end - start);
}

int find_candidate_rank(const std::vector<ReadingCandidate> &cands,
                        const std::u32string &target) {
  for (size_t i = 1; i < cands.size(); ++i) {
    if (cands[i].reading == target)
      return int(i);
  }
  return -1;
}

double find_candidate_penalty(const std::vector<ReadingCandidate> &cands,
                              const std::u32string &target) {
  for (size_t i = 1; i < cands.size(); ++i) {
    if (cands[i].reading == target)
      return cands[i].penalty;
  }
  return 1e9;
}

double min_mutation_penalty(const std::vector<ReadingCandidate> &cands) {
  double best = 1e9;
  for (size_t i = 1; i < cands.size(); ++i) {
    best = std::min(best, cands[i].penalty);
  }
  return best;
}

void run_unit_tests(const ChannelDictionary &dict) {
  // 1. v2 observation parsing and bounds checks
  const auto obs = parse_flick_observations(
      "v2;0:f,50,50,-8,0;1:t,95,5,0,0;2:c,10,90,30,-30;9:f,50,50,0,0;0:x,50,50,0,0;1:f,101,50,0,0;2:f,50,50,31,0",
      3);
  assert(obs.size() == 3);
  assert(obs[0].present && obs[0].kind == FlickObsKind::Flick &&
         std::abs(obs[0].nx - 0.50) < 1e-6 &&
         std::abs(obs[0].dx - (-0.8)) < 1e-6);
  assert(obs[1].present && obs[1].kind == FlickObsKind::ToggleFirst &&
         std::abs(obs[1].nx - 0.95) < 1e-6 &&
         std::abs(obs[1].ny - 0.05) < 1e-6);
  assert(obs[2].present && obs[2].kind == FlickObsKind::ToggleCycle &&
         std::abs(obs[2].dx - 3.0) < 1e-6 &&
         std::abs(obs[2].dy - (-3.0)) < 1e-6);

  // 2. Legacy hint parsing compatibility
  const auto legacy = parse_flick_touch_hints("1:り=0.82,か=0.40");
  assert(legacy.size() == 2);
  assert(legacy[0].index == 1 && legacy[0].ch == U'り' &&
         std::abs(legacy[0].weight - 0.82) < 1e-6);
  assert(parse_flick_touch_hints("v2;0:f,50,50,0,0").empty());

  // 3. Monotonicity of cell & direction switch costs
  const FlickObservation border_obs{true, FlickObsKind::Flick, 0.95, 0.50, 0.0, 0.0};
  const FlickObservation corner_obs{true, FlickObsKind::Flick, 0.92, 0.92, 0.0, 0.0};
  const FlickObservation center_obs{true, FlickObsKind::Flick, 0.50, 0.50, 0.0, 0.0};
  assert(flick_cell_switch_cost(0, 0, 0, 1, border_obs) <
         flick_cell_switch_cost(0, 0, 1, 1, corner_obs));
  assert(flick_cell_switch_cost(0, 0, 1, 1, corner_obs) <
         flick_cell_switch_cost(0, 0, 0, 1, center_obs));
  assert(flick_cell_switch_cost(0, 0, 0, 1, center_obs) <
         flick_cell_switch_cost(0, 0, 1, 1, center_obs));

  const FlickObservation short_left{true, FlickObsKind::Flick, 0.50, 0.50, -0.8, 0.0};
  const FlickObservation opposite_right{true, FlickObsKind::Flick, 0.50, 0.50, 1.5, 0.0};
  assert(flick_dir_switch_cost(0, 1, short_left) <
         flick_dir_switch_cost(0, 1, center_obs));
  assert(flick_dir_switch_cost(0, 1, center_obs) <
         flick_dir_switch_cost(0, 1, opposite_right));

  // 4. Joint cell x direction generation: あかた -> あした
  const auto joint_with_obs = assist_candidates(
      "", U"あかた", dict, 24, "v2;0:f,50,50,0,0;1:f,95,50,-8,0;2:f,50,50,0,0",
      InputKeyboardMode::Flick);
  const auto joint_without_obs = assist_candidates(
      "", U"あかた", dict, 24, "", InputKeyboardMode::Flick);
  assert(find_candidate_rank(joint_with_obs, U"あした") > 0);
  assert(find_candidate_penalty(joint_with_obs, U"あした") < 1.2);
  assert(find_candidate_rank(joint_without_obs, U"あした") == -1);

  // 5. Position-aware 小゛゜ misfire on わ (3,1)
  const auto misfire_edge = assist_candidates(
      "", U"ありかわとう", dict, 24,
      "v2;0:f,50,50,0,0;1:f,50,50,-20,0;2:f,50,50,0,0;3:f,6,50,0,0;4:f,50,50,0,20;5:f,50,50,0,-20",
      InputKeyboardMode::Flick);
  const auto misfire_center = assist_candidates(
      "", U"ありかわとう", dict, 24,
      "v2;0:f,50,50,0,0;1:f,50,50,-20,0;2:f,50,50,0,0;3:f,50,50,0,0;4:f,50,50,0,20;5:f,50,50,0,-20",
      InputKeyboardMode::Flick);
  assert(find_candidate_penalty(misfire_edge, U"ありがとう") <
         find_candidate_penalty(misfire_center, U"ありがとう"));

  // 6. keyPunc edge recovery
  const auto punc_watashi = assist_candidates(
      "", U"、たし", dict, 24, "v2;0:f,5,50,0,0;1:f,50,50,0,0;2:f,50,50,-20,0",
      InputKeyboardMode::Flick);
  assert(find_candidate_rank(punc_watashi, U"わたし") > 0);
  const auto punc_arigatou = assist_candidates(
      "", U"あ。がとう", dict, 24,
      "v2;0:f,50,50,0,0;1:f,50,5,-20,0;2:f,50,50,0,0;3:f,50,50,0,20;4:f,50,50,0,-20",
      InputKeyboardMode::Flick);
  assert(find_candidate_rank(punc_arigatou, U"ありがとう") > 0);

  // 7. Toggle mode cycle ±1 and split-tap merge
  const auto toggle_cycle_cands = assist_candidates(
      "", U"おはゆう", dict, 24,
      "v2;0:t,50,50,0,20;1:t,50,50,0,0;2:c,50,50,0,0;3:c,50,50,0,0",
      InputKeyboardMode::FlickToggle);
  const auto flick_same_cands = assist_candidates(
      "", U"おはゆう", dict, 24, "", InputKeyboardMode::Flick);
  assert(find_candidate_penalty(toggle_cycle_cands, U"おはよう") <
         find_candidate_penalty(flick_same_cands, U"おはよう"));

  const auto split_toggle = assist_candidates(
      "", U"ささごと", dict, 24,
      "v2;0:t,50,50,0,0;1:t,50,50,0,0;2:d,50,50,0,20;3:t,50,50,0,20",
      InputKeyboardMode::FlickToggle);
  const auto split_flick = assist_candidates(
      "", U"ささごと", dict, 24, "", InputKeyboardMode::Flick);
  assert(find_candidate_rank(split_toggle, U"しごと") > 0);
  assert(find_candidate_rank(split_flick, U"しごと") == -1);

  // 8. Clean-input protection (confident gesture observations raise direction/cell
  // mutation penalty and never lower min mutation penalty vs no-observation baseline)
  const auto clean_with_obs = assist_candidates(
      "", U"わたし", dict, 24,
      "v2;0:f,50,50,0,0;1:f,50,50,0,0;2:f,50,50,-22,0",
      InputKeyboardMode::Flick);
  const auto clean_no_obs = assist_candidates(
      "", U"わたし", dict, 24, "", InputKeyboardMode::Flick);
  assert(min_mutation_penalty(clean_with_obs) >=
         min_mutation_penalty(clean_no_obs));
  assert(find_candidate_penalty(clean_with_obs, U"わたす") >
         find_candidate_penalty(clean_no_obs, U"わたす"));

  // 9. QWERTY mode remains unaffected
  const auto qwerty_cands = assist_candidates(
      "watadi", U"わたぢ", dict, 24, "4:s=0.90", InputKeyboardMode::Qwerty);
  assert(find_candidate_rank(qwerty_cands, U"わたし") > 0);

  std::cout << "PASS all flick_channel C++17 unit tests\n";
}

struct CatStats {
  int total = 0;
  int obs_r1 = 0;
  int obs_r8 = 0;
  int obs_r24 = 0;
  int base_r1 = 0;
  int base_r8 = 0;
  int base_r24 = 0;
};

void run_benchmark(const ChannelDictionary &dict,
                   const std::string &jsonl_path) {
  std::ifstream in(jsonl_path);
  assert(in.good());
  std::map<std::string, CatStats> stats_by_cat;
  CatStats typo_total;
  int clean_total = 0;
  int clean_protected = 0;
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty())
      continue;
    const std::string id = json_field(line, "id");
    const std::string cat = json_field(line, "category");
    const std::string mode_s = json_field(line, "mode");
    const std::string input = json_field(line, "input");
    const std::string expected = json_field(line, "expected");
    const std::string touch = json_field(line, "touch");
    const InputKeyboardMode mode = parse_input_keyboard_mode(mode_s);
    const std::u32string input_u32 = decode_utf8(input);
    const std::u32string expected_u32 = decode_utf8(expected);

    const auto with_obs =
        assist_candidates("", input_u32, dict, 24, touch, mode);
    const auto without_obs =
        assist_candidates("", input_u32, dict, 24, "", mode);

    if (cat == "clean_guard") {
      ++clean_total;
      const double min_obs = min_mutation_penalty(with_obs);
      const double min_base = min_mutation_penalty(without_obs);
      if (min_obs >= min_base)
        ++clean_protected;
      continue;
    }

    const int rank_obs = find_candidate_rank(with_obs, expected_u32);
    const int rank_base = find_candidate_rank(without_obs, expected_u32);

    auto &cs = stats_by_cat[cat];
    ++cs.total;
    ++typo_total.total;
    if (rank_obs > 0) {
      ++cs.obs_r24;
      ++typo_total.obs_r24;
      if (rank_obs <= 8) {
        ++cs.obs_r8;
        ++typo_total.obs_r8;
      }
      if (rank_obs == 1) {
        ++cs.obs_r1;
        ++typo_total.obs_r1;
      }
    }
    if (rank_base > 0) {
      ++cs.base_r24;
      ++typo_total.base_r24;
      if (rank_base <= 8) {
        ++cs.base_r8;
        ++typo_total.base_r8;
      }
      if (rank_base == 1) {
        ++cs.base_r1;
        ++typo_total.base_r1;
      }
    }
    if (rank_obs <= 0) {
      std::cout << "MISS(obs): " << id << " (" << cat << ") " << input << " -> "
                << expected << "\n";
    }
  }

  std::cout << "\n12-Key Candidate Generation Benchmark (" << typo_total.total
            << " typo cases, " << clean_total << " clean_guard):\n";
  for (const auto &kv : stats_by_cat) {
    const auto &s = kv.second;
    std::cout << "  " << kv.first << " (n=" << s.total << "): "
              << "with_obs R@1=" << s.obs_r1 << "/" << s.total
              << " R@8=" << s.obs_r8 << "/" << s.total
              << " R@24=" << s.obs_r24 << "/" << s.total
              << " | no_obs R@1=" << s.base_r1 << "/" << s.total
              << " R@8=" << s.base_r8 << "/" << s.total
              << " R@24=" << s.base_r24 << "/" << s.total << "\n";
  }
  std::cout << "  OVERALL TYPO (n=" << typo_total.total << "): "
            << "with_obs R@1=" << typo_total.obs_r1 << "/" << typo_total.total
            << " R@8=" << typo_total.obs_r8 << "/" << typo_total.total
            << " R@24=" << typo_total.obs_r24 << "/" << typo_total.total
            << " | no_obs R@1=" << typo_total.base_r1 << "/" << typo_total.total
            << " R@8=" << typo_total.base_r8 << "/" << typo_total.total
            << " R@24=" << typo_total.base_r24 << "/" << typo_total.total
            << "\n";
  std::cout << "  CLEAN GUARD: " << clean_protected << "/" << clean_total
            << " preserved higher/equal min mutation penalty\n";

  assert(typo_total.obs_r24 * 100 >= typo_total.total * 95);
  assert(typo_total.obs_r1 > typo_total.base_r1);
  assert(typo_total.obs_r8 > typo_total.base_r8);
  assert(clean_protected == clean_total);
}

} // namespace

int main(int argc, char **argv) {
  if (argc < 3) {
    std::cerr << "Usage: flick_channel_test <reverse-v2.bin> <flick_12key_cases.jsonl>\n";
    return 2;
  }
  ChannelDictionary dict(argv[1]);
  run_unit_tests(dict);
  run_benchmark(dict, argv[2]);
  return 0;
}

// Port of windows/native/keyboard-prior/main.cpp `--assist` loop. Candidate
// generation and dictionary search are the shared channel.hpp; only the process
// protocol is replaced by a function call. Keep the scoring constants
// identical.
#include "keyboard_assist.h"
#include "channel.hpp"
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <vector>
#ifndef __wasi__
#include <mutex>
#include <thread>
#endif

long long assist_epoch();

namespace {
using Clock = std::chrono::steady_clock;

#ifndef __wasi__
std::mutex run_mutex; // guards model/context and serializes scoring
std::atomic<int> configured_decode_threads{-1};
std::atomic<int> configured_batch_threads{-1};
#else
int configured_decode_threads = -1;
int configured_batch_threads = -1;
#endif
double fast_logsumexp(const float *logits, int nv) {
  float max_value = logits[0];
  for (int j = 1; j < nv; ++j) {
    if (logits[j] > max_value)
      max_value = logits[j];
  }
  const float cutoff = max_value - 20.0f;
  double denom = 0.0;
  for (int j = 0; j < nv; ++j) {
    const float v = logits[j];
    if (v > cutoff)
      denom += double(std::expf(v - max_value));
  }
  return double(max_value) + std::log(denom);
}
struct KeyboardScorer {
  llama_model *model = nullptr;
  llama_context *context = nullptr;
  std::vector<llama_token> cached_prefix_tokens;
  int n_threads_decode = 2;
  int n_threads_batch = 4;
  long long request_epoch = 0;
  Clock::time_point deadline = Clock::time_point::max();
  bool follow_input_epoch;

  explicit KeyboardScorer(bool follow_input_epoch)
      : follow_input_epoch(follow_input_epoch) {}
  ~KeyboardScorer() { attach(nullptr); }

  void attach(llama_model *value);
  bool ensure_context();
  bool cancelled() const {
    return (follow_input_epoch && assist_epoch() != request_epoch) ||
           Clock::now() >= deadline;
  }
  std::vector<double> score_sequences(
      const llama_vocab *vocab,
      const std::vector<std::vector<llama_token>> &sequences,
      const std::vector<int> &prefix_lengths,
      double *out_seq0_min_tok_lp = nullptr);
  std::string run(const ChannelDictionary &dict, const std::string &raw_keys,
                  const std::string &input, const std::string &ctx,
                  const std::string &touch, const std::string &mode_str);
  std::string request(const ChannelDictionary &dict, const char *payload,
                      unsigned long long timeout_ms);
};

KeyboardScorer live_scorer(true);

std::shared_ptr<ChannelDictionary> dictionary;
std::string dictionary_path;
#ifndef __wasi__
std::mutex dictionary_mutex;
std::string failed_path;
bool loading = false;
#endif

std::string field(const std::string &payload, const char *name) {
  const std::string key = std::string("\"") + name + "\":\"";
  const auto at = payload.find(key);
  if (at == std::string::npos)
    return {};
  const auto start = at + key.size();
  const auto end = payload.find('"', start);
  return end == std::string::npos ? std::string()
                                  : payload.substr(start, end - start);
}

#ifdef __wasi__
std::shared_ptr<ChannelDictionary> ready_dictionary(const std::string &path) {
  if (path.empty()) return nullptr;
  if (!dictionary || dictionary_path != path) {
    dictionary = std::make_shared<ChannelDictionary>(path);
    dictionary_path = path;
  }
  return dictionary;
}
#else
void ensure_dictionary_loading(const std::string &path) {
  std::lock_guard<std::mutex> lock(dictionary_mutex);
  if (loading || path.empty() || path == failed_path)
    return;
  if (dictionary && dictionary_path == path)
    return;
  loading = true;
  std::thread([path] {
    std::shared_ptr<ChannelDictionary> loaded;
    try {
      loaded = std::make_shared<ChannelDictionary>(path);
    } catch (...) {
    }
    std::lock_guard<std::mutex> done(dictionary_mutex);
    if (loaded) {
      dictionary = loaded;
      dictionary_path = path;
      failed_path.clear();
    } else
      failed_path = path;
    loading = false;
  }).detach();
}

std::shared_ptr<ChannelDictionary> ready_dictionary(const std::string &path) {
  {
    std::lock_guard<std::mutex> lock(dictionary_mutex);
    if (dictionary && dictionary_path == path)
      return dictionary;
  }
  ensure_dictionary_loading(path);
  return nullptr;
}
#endif

bool KeyboardScorer::ensure_context() {
  if (context)
    return true;
  if (!model)
    return false;
#ifdef __wasi__
  n_threads_decode = n_threads_batch = 1;
#else
  const int logical_cores =
      int(std::max(1u, std::thread::hardware_concurrency()));
  const int auto_decode =
      logical_cores <= 1 ? 1 : std::clamp(logical_cores / 2, 2, 4);
  const int cfg_decode = configured_decode_threads.load();
  n_threads_decode =
      cfg_decode > 0 ? std::clamp(cfg_decode, 1, 32) : auto_decode;
  const int auto_batch =
      logical_cores <= 1
          ? 1
          : std::clamp(logical_cores - 2, n_threads_decode, 6);
  const int cfg_batch = configured_batch_threads.load();
  n_threads_batch =
      cfg_batch > 0 ? std::clamp(cfg_batch, 1, 64) : auto_batch;
#endif
  auto cp = llama_context_default_params();
  cp.n_ctx = 1024;
  cp.n_batch = 1024;
  cp.n_ubatch = 1024;
  cp.n_outputs_max = cp.n_batch;
  cp.n_seq_max = 32;
  cp.kv_unified = true;
  cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
  cp.no_perf = true;
  cp.n_threads = n_threads_decode;
  cp.n_threads_batch = n_threads_batch;
  cp.offload_kqv = false;
  cp.abort_callback = [](void *data) { return static_cast<KeyboardScorer *>(data)->cancelled(); };
  cp.abort_callback_data = this;
  cached_prefix_tokens.clear();
  context = llama_init_from_model(model, cp);
  return context != nullptr;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab,
                                  const std::string &text) {
  std::vector<llama_token> tokens(512);
  const int n = llama_tokenize(vocab, text.data(), int(text.size()),
                               tokens.data(), int(tokens.size()), false, true);
  if (n < 1 || n > 384)
    return {};
  tokens.resize(n);
  return tokens;
}

std::vector<double>
KeyboardScorer::score_sequences(const llama_vocab *vocab,
                const std::vector<std::vector<llama_token>> &sequences,
                const std::vector<int> &prefix_lengths,
                double *out_seq0_min_tok_lp) {
  if (out_seq0_min_tok_lp)
    *out_seq0_min_tok_lp = 0.0;
  const int nv = llama_vocab_n_tokens(vocab);
  int active_count = int(sequences.size());
  if (active_count == 0 ||
      std::any_of(sequences.begin(), sequences.end(),
                  [](const auto &sequence) { return sequence.empty(); }))
    return {};
  if (active_count > 32)
    active_count = 32;

  struct Node {
    llama_token token;
    int pos;
    bool need_logits;
    std::vector<int> owners;
  };
  struct Edge {
    int node;
    int owner;
    llama_token next;
  };
  const int max_ctx = int(llama_n_ctx(context));
  int shared_unscored_len = 0;
  std::vector<Node> nodes;
  std::vector<Edge> edges;
  while (true) {
    int max_shared = int(sequences[0].size()) - 1;
    for (int i = 0; i < active_count; ++i) {
      max_shared =
          std::min(max_shared, std::min(int(sequences[i].size()) - 1,
                                        std::max(0, prefix_lengths[i] - 1)));
    }
    shared_unscored_len = 0;
    while (shared_unscored_len < max_shared) {
      const llama_token tok = sequences[0][shared_unscored_len];
      bool all_match = true;
      for (int i = 1; i < active_count; ++i) {
        if (sequences[i][shared_unscored_len] != tok) {
          all_match = false;
          break;
        }
      }
      if (!all_match)
        break;
      ++shared_unscored_len;
    }

    std::map<std::vector<llama_token>, int> prefixes;
    nodes.clear();
    edges.clear();
    for (int i = 0; i < active_count; ++i) {
      const auto &tokens = sequences[i];
      std::vector<llama_token> prefix;
      for (int j = shared_unscored_len; j < int(tokens.size()) - 1; ++j) {
        prefix.push_back(tokens[j]);
        auto found = prefixes.find(prefix);
        int node;
        if (found == prefixes.end()) {
          node = int(nodes.size());
          prefixes.emplace(prefix, node);
          nodes.push_back({tokens[j], j, false, {}});
        } else {
          node = found->second;
        }
        nodes[node].owners.push_back(i);
        if (j + 1 >= prefix_lengths[i]) {
          nodes[node].need_logits = true;
          edges.push_back({node, i, tokens[j + 1]});
        }
      }
    }
    if (shared_unscored_len + int(nodes.size()) <= max_ctx ||
        active_count <= 2)
      break;
    --active_count;
  }
  if (shared_unscored_len + int(nodes.size()) > max_ctx)
    return std::vector<double>(sequences.size(), -1e9);
  if (nodes.empty())
    return std::vector<double>(sequences.size(), 0.0);

  auto *mem = llama_get_memory(context);
  int lcp = 0;
  const int cached_limit =
      std::min(shared_unscored_len, int(cached_prefix_tokens.size()));
  while (lcp < cached_limit && cached_prefix_tokens[lcp] == sequences[0][lcp]) {
    ++lcp;
  }
  if (lcp > 0) {
    llama_memory_seq_keep(mem, 0);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(lcp), -1)) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      lcp = 0;
    }
  } else {
    llama_memory_clear(mem, true);
    cached_prefix_tokens.clear();
  }

  const int delta = shared_unscored_len > 0 ? (shared_unscored_len - lcp) : 0;
  const int n_batch_limit = int(llama_n_batch(context));
  const bool merge_prefix_and_tree =
      shared_unscored_len > 0 && delta > 0 &&
      delta + int(nodes.size()) <= n_batch_limit;
  int node_logits_offset = 0;

  if (merge_prefix_and_tree) {
    if (lcp > 0) {
      for (int s = 1; s < active_count; ++s) {
        llama_memory_seq_cp(mem, 0, s, 0, static_cast<llama_pos>(lcp));
      }
    }
    auto batch =
        llama_batch_init(delta + int(nodes.size()), 0, active_count);
    for (int k = 0; k < delta; ++k) {
      const int pos = lcp + k;
      const int at = batch.n_tokens++;
      batch.token[at] = sequences[0][pos];
      batch.pos[at] = pos;
      batch.n_seq_id[at] = active_count;
      for (int s = 0; s < active_count; ++s)
        batch.seq_id[at][s] = s;
      batch.logits[at] = 0;
    }
    node_logits_offset = batch.n_tokens;
    for (const auto &node : nodes) {
      const int at = batch.n_tokens++;
      batch.token[at] = node.token;
      batch.pos[at] = node.pos;
      batch.n_seq_id[at] = int(node.owners.size());
      std::copy(node.owners.begin(), node.owners.end(), batch.seq_id[at]);
      batch.logits[at] = node.need_logits ? 1 : 0;
    }
    llama_set_n_threads(context, n_threads_decode,
                        batch.n_tokens <= 4 ? n_threads_decode : n_threads_batch);
    const int rc = llama_decode(context, batch);
    llama_batch_free(batch);
    if (rc != 0) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      return {};
    }
    cached_prefix_tokens.assign(sequences[0].begin(),
                                sequences[0].begin() + shared_unscored_len);
  } else {
    if (shared_unscored_len > 0) {
      if (delta > 0) {
        llama_set_n_threads(context, n_threads_decode,
                            delta <= 4 ? n_threads_decode : n_threads_batch);
        auto pfx_batch = llama_batch_init(delta, 0, 1);
        for (int k = 0; k < delta; ++k) {
          const int pos = lcp + k;
          pfx_batch.token[k] = sequences[0][pos];
          pfx_batch.pos[k] = pos;
          pfx_batch.n_seq_id[k] = 1;
          pfx_batch.seq_id[k][0] = 0;
          pfx_batch.logits[k] = 0;
        }
        pfx_batch.n_tokens = delta;
        const int pfx_rc = llama_decode(context, pfx_batch);
        llama_batch_free(pfx_batch);
        if (pfx_rc != 0) {
          llama_memory_clear(mem, true);
          cached_prefix_tokens.clear();
          return {};
        }
      }
      for (int s = 1; s < active_count; ++s) {
        llama_memory_seq_cp(mem, 0, s, 0,
                            static_cast<llama_pos>(shared_unscored_len));
      }
      cached_prefix_tokens.assign(sequences[0].begin(),
                                  sequences[0].begin() + shared_unscored_len);
    } else {
      cached_prefix_tokens.clear();
    }

    auto batch = llama_batch_init(int(nodes.size()), 0, active_count);
    for (const auto &node : nodes) {
      const int at = batch.n_tokens++;
      batch.token[at] = node.token;
      batch.pos[at] = node.pos;
      batch.n_seq_id[at] = int(node.owners.size());
      std::copy(node.owners.begin(), node.owners.end(), batch.seq_id[at]);
      batch.logits[at] = node.need_logits ? 1 : 0;
    }
    llama_set_n_threads(context, n_threads_decode,
                        batch.n_tokens <= 4 ? n_threads_decode : n_threads_batch);
    const int rc = llama_decode(context, batch);
    llama_batch_free(batch);
    if (rc != 0) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      return {};
    }
  }
  std::vector<double> scores(sequences.size(), -1e9);
  for (int i = 0; i < active_count; ++i)
    scores[i] = 0.0;
  struct LogitRow {
    const float *values;
    double normalizer;
  };
  std::vector<LogitRow> rows(nodes.size());
  for (int i = 0; i < int(nodes.size()); ++i) {
    if (!nodes[i].need_logits)
      continue;
    const float *logits =
        llama_get_logits_ith(context, node_logits_offset + i);
    if (!logits) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      return {};
    }
    rows[i] = {logits, fast_logsumexp(logits, nv)};
  }
  double seq0_min_lp = 0.0;
  bool seq0_has_tok = false;
  for (const auto &edge : edges) {
    const auto &row = rows[edge.node];
    const double tok_lp = double(row.values[edge.next]) - row.normalizer;
    scores[edge.owner] += tok_lp;
    if (edge.owner == 0) {
      seq0_min_lp = seq0_has_tok ? std::min(seq0_min_lp, tok_lp) : tok_lp;
      seq0_has_tok = true;
    }
  }
  if (out_seq0_min_tok_lp)
    *out_seq0_min_tok_lp = seq0_has_tok ? seq0_min_lp : 0.0;
  if (shared_unscored_len > 0) {
    llama_memory_seq_keep(mem, 0);
    if (!llama_memory_seq_rm(mem, 0,
                             static_cast<llama_pos>(shared_unscored_len), -1)) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
    }
  } else {
    llama_memory_clear(mem, true);
    cached_prefix_tokens.clear();
  }
  return scores;
}

std::string KeyboardScorer::run(const ChannelDictionary &dict, const std::string &raw_keys,
                const std::string &input_utf8,
                const std::string &context_utf8,
                const std::string &touch_hints,
                const std::string &mode_str) {
  const auto *vocab = llama_model_get_vocab(model);
  const auto input_mode = parse_input_keyboard_mode(mode_str);
  const bool is_flick_mode = is_flick_keyboard_mode(input_mode);
  const double rank_pen_weight = is_flick_mode ? 5.5 : 4.0;
  const double stage1_pen_weight = is_flick_mode ? 5.0 : 3.0;
  const auto input_hira = !input_utf8.empty()
                              ? hiragana(decode_utf8(input_utf8))
                              : roman_decode(raw_keys);
  const auto candidates = assist_candidates(
      raw_keys, input_hira, dict, 24, touch_hints, input_mode);
  std::ostringstream out;
  if (candidates.size() <= 1) {
    out << "{\"observed\":" << json_string(encode_utf8(input_hira)) << "}";
    return out.str();
  }
  std::u32string ctx_u32;
  if (!context_utf8.empty()) {
    ctx_u32 = decode_utf8(context_utf8);
    ctx_u32.erase(std::remove_if(ctx_u32.begin(), ctx_u32.end(),
                                 [](char32_t c) {
                                   return c < 32 ||
                                          (c >= U'\uee00' && c <= U'\uee02');
                                 }),
                  ctx_u32.end());
    if (ctx_u32.size() > 64)
      ctx_u32 = ctx_u32.substr(ctx_u32.size() - 64);
  }
  const auto &orig = candidates[0];
  const auto windows = build_scoring_windows(orig.reading, ctx_u32, candidates,
                                             dict, input_mode);
  if (windows.empty()) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  struct Ranked {
    double stage1_delta;
    double final_score;
    double surf_delta;
    bool stage2_evaluated;
    bool borderline_promoted;
    size_t index;
    size_t win_idx;
    size_t member_idx;
    std::u32string prompt_prefix;
    std::vector<llama_token> cand_tokens;
  };
  std::vector<Ranked> ranked;
  std::vector<int> win_orig_tokens(windows.size(), 0);
  for (size_t w = 0; w < windows.size(); ++w) {
    const auto &win = windows[w];
    if (win.members.empty())
      continue;
    std::vector<size_t> member_order(win.members.size());
    for (size_t j = 0; j < member_order.size(); ++j)
      member_order[j] = j;
    std::sort(member_order.begin(), member_order.end(),
              [&](size_t a, size_t b) {
                const auto &ca = candidates[win.members[a].candidate_index];
                const auto &cb = candidates[win.members[b].candidate_index];
                const double ra = ca.local_delta - ca.penalty * rank_pen_weight;
                const double rb = cb.local_delta - cb.penalty * rank_pen_weight;
                return ra != rb ? ra > rb : a < b;
              });
    const auto masked_ctx = mask_digits_nam_u32(win.left_context);
    const std::u32string prompt_prefix =
        masked_ctx.empty() ? U"\uee00"
                           : (U"\uee02" + masked_ctx + U"\uee00");
    const auto pfx_tokens = tokenize(vocab, encode_utf8(prompt_prefix));
    if (pfx_tokens.empty())
      return "null";
    const int pfx_len = int(pfx_tokens.size());
    const bool orig_has_ascii = std::any_of(
        win.orig_slice.begin(), win.orig_slice.end(), [](char32_t c) {
          return (c >= U'a' && c <= U'z') || (c >= U'A' && c <= U'Z') ||
                 (c >= U'ａ' && c <= U'ｚ') || (c >= U'Ａ' && c <= U'Ｚ');
        });

    constexpr size_t kTier1 = 8;
    const size_t tier1_n = std::min(member_order.size(), kTier1);
    std::vector<std::vector<llama_token>> tier1_seqs;
    tier1_seqs.reserve(1 + tier1_n);
    auto orig_seq = tokenize(
        vocab, encode_utf8(prompt_prefix +
                           mask_digits_nam_u32(katakana(win.orig_slice)) +
                           U"\uee01"));
    if (orig_seq.empty())
      return "null";
    tier1_seqs.push_back(std::move(orig_seq));
    for (size_t t = 0; t < tier1_n; ++t) {
      const auto &m = win.members[member_order[t]];
      auto c_seq = tokenize(
          vocab, encode_utf8(prompt_prefix +
                             mask_digits_nam_u32(katakana(m.cand_slice)) +
                             U"\uee01"));
      if (c_seq.empty())
        return "null";
      tier1_seqs.push_back(std::move(c_seq));
    }
    std::vector<int> tier1_lengths(tier1_seqs.size(), pfx_len);
    double orig_min_tok_lp = 0.0;
    const auto tier1_scores =
        score_sequences(vocab, tier1_seqs, tier1_lengths, &orig_min_tok_lp);
    if (tier1_scores.empty())
      return "null";
    if (tier1_scores[0] <= -1e8)
      continue;
    const double baseline = win.orig_path.score + 8.0 * tier1_scores[0];
    const int orig_tokens = int(tier1_seqs[0].size());
    win_orig_tokens[w] = orig_tokens;

    auto score_member_delta = [&](size_t j,
                                  std::vector<llama_token> &cand_tokens,
                                  double lm_score) {
      const auto &m = win.members[j];
      const auto &cand = candidates[m.candidate_index];
      const int token_diff = int(cand_tokens.size()) - orig_tokens;
      double len_comp = 0.0;
      if (token_diff > 0 && m.cand_slice.size() >= win.orig_slice.size() &&
          m.cand_path.score > win.orig_path.score + 2.0) {
        len_comp = 11.0 * std::min(token_diff, 2);
      } else if (token_diff < 0 &&
                 m.cand_slice.size() < win.orig_slice.size()) {
        const double short_unit = orig_has_ascii ? 5.0 : 10.0;
        len_comp = -short_unit * std::min(-token_diff, 2);
      }
      const double total = m.cand_path.score + 8.0 * lm_score + len_comp -
                           cand.penalty * stage1_pen_weight;
      const double delta = total - baseline;
      if (delta >= 28.0)
        ranked.push_back({delta, delta, 0.0, false, false, m.candidate_index,
                          w, j, prompt_prefix, std::move(cand_tokens)});
      return delta;
    };

    double tier1_best_delta = -1e9;
    bool tier1_best_non_shrinking = false;
    for (size_t t = 0; t < tier1_n; ++t) {
      const size_t j = member_order[t];
      const double delta =
          score_member_delta(j, tier1_seqs[t + 1], tier1_scores[t + 1]);
      if (delta > tier1_best_delta) {
        tier1_best_delta = delta;
        tier1_best_non_shrinking =
            win.members[j].cand_slice.size() >= win.orig_slice.size();
      }
    }

    if (tier1_n < member_order.size()) {
      const bool clear_win =
          (tier1_best_delta >= 75.0 && tier1_best_non_shrinking) ||
          tier1_best_delta >= 115.0;
      const double clean_score_floor = -4.0 * double(win.orig_slice.size());
      const bool clean_ok =
          tier1_best_delta < 20.0 && orig_min_tok_lp > -6.8 &&
          win.orig_path.score >= clean_score_floor;
      if (!clear_win && !clean_ok) {
        const size_t tier2_n = member_order.size() - tier1_n;
        std::vector<std::vector<llama_token>> tier2_seqs;
        tier2_seqs.reserve(tier2_n);
        for (size_t t = tier1_n; t < member_order.size(); ++t) {
          const auto &m = win.members[member_order[t]];
          auto c_seq = tokenize(
              vocab, encode_utf8(prompt_prefix +
                                 mask_digits_nam_u32(katakana(m.cand_slice)) +
                                 U"\uee01"));
          if (c_seq.empty())
            return "null";
          tier2_seqs.push_back(std::move(c_seq));
        }
        std::vector<int> tier2_lengths(tier2_seqs.size(), pfx_len);
        const auto tier2_scores =
            score_sequences(vocab, tier2_seqs, tier2_lengths);
        if (tier2_scores.empty())
          return "null";
        for (size_t t = 0; t < tier2_n; ++t) {
          score_member_delta(member_order[tier1_n + t], tier2_seqs[t],
                             tier2_scores[t]);
        }
      }
    }
  }
  std::sort(ranked.begin(), ranked.end(), [](const Ranked &a, const Ranked &b) {
    return a.stage1_delta > b.stage1_delta;
  });
  if (ranked.empty() || ranked[0].stage1_delta < 28.0) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  const size_t top_win = ranked[0].win_idx;
  const auto &win = windows[top_win];
  std::vector<size_t> win_ranked;
  for (size_t k = 0; k < ranked.size() && win_ranked.size() < 3; ++k) {
    if (ranked[k].win_idx == top_win)
      win_ranked.push_back(k);
  }
  const bool is_borderline = ranked[0].stage1_delta < 37.0;
  auto non_shrinking_ok = [&](size_t pos) {
    const auto &m = win.members[ranked[pos].member_idx];
    return m.cand_slice.size() >= win.orig_slice.size() &&
           m.cand_path.score >= win.orig_path.score - 2.0;
  };
  bool run_stage2 = false;
  if (!is_borderline) {
    run_stage2 = win_ranked.size() >= 2 &&
                 (ranked[win_ranked[0]].stage1_delta -
                      ranked[win_ranked[1]].stage1_delta <
                  20.0);
  } else {
    run_stage2 = win.orig_slice.size() >= 6 &&
                 std::any_of(win_ranked.begin(), win_ranked.end(),
                             non_shrinking_ok);
  }
  if (run_stage2) {
    const llama_token eos_tok = llama_vocab_eos(vocab);
    const int orig_pfx_len = win_orig_tokens[top_win];
    std::vector<std::vector<llama_token>> surf_sequences;
    std::vector<int> surf_prefixes;
    std::vector<size_t> valid_pos;
    if (!win.orig_path.surface.empty() && orig_pfx_len > 0) {
      auto orig_full = tokenize(
          vocab,
          encode_utf8(ranked[win_ranked[0]].prompt_prefix +
                      mask_digits_nam_u32(katakana(win.orig_slice)) +
                      U"\uee01" +
                      mask_digits_nam_u32(decode_utf8(win.orig_path.surface))));
      if (eos_tok >= 0 && !orig_full.empty())
        orig_full.push_back(eos_tok);
      if (int(orig_full.size()) > orig_pfx_len) {
        surf_sequences.push_back(std::move(orig_full));
        surf_prefixes.push_back(orig_pfx_len);
        for (size_t pos : win_ranked) {
          if (is_borderline && !non_shrinking_ok(pos))
            continue;
          const auto &rc = ranked[pos];
          const auto &m = win.members[rc.member_idx];
          if (m.cand_path.surface.empty())
            continue;
          auto full = tokenize(
              vocab,
              encode_utf8(rc.prompt_prefix +
                          mask_digits_nam_u32(katakana(m.cand_slice)) +
                          U"\uee01" +
                          mask_digits_nam_u32(decode_utf8(m.cand_path.surface))));
          if (eos_tok >= 0 && !full.empty())
            full.push_back(eos_tok);
          const int pfx_len = int(rc.cand_tokens.size());
          if (int(full.size()) > pfx_len) {
            surf_sequences.push_back(std::move(full));
            surf_prefixes.push_back(pfx_len);
            valid_pos.push_back(pos);
          }
        }
      }
    }
    if (surf_sequences.size() >= 2 && !valid_pos.empty()) {
      const auto surf_scores =
          score_sequences(vocab, surf_sequences, surf_prefixes);
      if (surf_scores.empty())
        return "null";
      const int n_orig_surf = int(surf_sequences[0].size()) - surf_prefixes[0];
      const double orig_surf_mean = surf_scores[0] / std::max(1, n_orig_surf);
      const double clamp_hi = is_borderline ? 2.0 : 1.5;
      const double weight = is_borderline ? 10.0 : 6.0;
      for (size_t j = 0; j < valid_pos.size(); ++j) {
        const size_t pos = valid_pos[j];
        const int n_cand_surf =
            int(surf_sequences[j + 1].size()) - surf_prefixes[j + 1];
        const double cand_surf_mean =
            surf_scores[j + 1] / std::max(1, n_cand_surf);
        const double surf_delta =
            std::clamp(cand_surf_mean - orig_surf_mean, -1.0, clamp_hi);
        ranked[pos].surf_delta = surf_delta;
        ranked[pos].stage2_evaluated = true;
        ranked[pos].final_score =
            ranked[pos].stage1_delta + weight * surf_delta;
        if (is_borderline && win.orig_slice.size() >= 6 &&
            non_shrinking_ok(pos) && cand_surf_mean >= -1.2 &&
            surf_delta >= 0.85 && ranked[pos].final_score >= 37.0) {
          ranked[pos].borderline_promoted = true;
        }
      }
      if (!is_borderline) {
        std::vector<Ranked> reordered;
        reordered.reserve(valid_pos.size());
        for (size_t pos : valid_pos)
          reordered.push_back(ranked[pos]);
        std::sort(reordered.begin(), reordered.end(),
                  [](const Ranked &a, const Ranked &b) {
                    return a.final_score > b.final_score;
                  });
        for (size_t j = 0; j < valid_pos.size(); ++j)
          ranked[valid_pos[j]] = std::move(reordered[j]);
      } else {
        size_t best_pos = ranked.size();
        for (size_t pos : valid_pos) {
          if (ranked[pos].borderline_promoted &&
              (best_pos == ranked.size() ||
               ranked[pos].final_score > ranked[best_pos].final_score)) {
            best_pos = pos;
          }
        }
        if (best_pos < ranked.size() && best_pos != 0)
          std::swap(ranked[0], ranked[best_pos]);
      }
    }
  }
  const auto &top = ranked[0];
  const bool accepted =
      !is_borderline || (top.stage2_evaluated && top.borderline_promoted);
  if (!accepted) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  const size_t best = top.index;
  const double best_delta = std::max(37.0, top.final_score);
  out << "{\"observed\":" << json_string(encode_utf8(orig.reading))
      << ",\"reading\":" << json_string(encode_utf8(candidates[best].reading));
  if (ranked.size() >= 2 && ranked[1].final_score >= 35.0) {
    out << ",\"alt_reading\":"
        << json_string(encode_utf8(candidates[ranked[1].index].reading));
    if (has_clear_assist_lead(ranked))
      out << ",\"clear_lead\":true";
  }
  out << ",\"gain\":" << std::setprecision(14) << best_delta << "}";
  return out.str();
}

void KeyboardScorer::attach(llama_model *value) {
  if (value != model && context) {
    llama_free(context);
    context = nullptr;
    cached_prefix_tokens.clear();
  }
  model = value;
}

std::string KeyboardScorer::request(const ChannelDictionary &dict,
                                    const char *payload_text,
                                    unsigned long long timeout_ms) {
  const std::string payload = payload_text;
  request_epoch = assist_epoch();
  deadline = Clock::now() + std::chrono::milliseconds(
                                std::min<unsigned long long>(timeout_ms, 30000));
  if (!ensure_context())
    return "null";
  return run(dict, field(payload, "raw"), field(payload, "input"),
             field(payload, "context"), field(payload, "touch"),
             field(payload, "mode"));
}
} // namespace

void keyboard_assist_preload_dictionary(const char *path) {
  if (!path || !*path)
    return;
#ifdef __wasi__
  (void)ready_dictionary(path);
#else
  ensure_dictionary_loading(path);
#endif
}

void keyboard_assist_attach(llama_model *value) {
#ifndef __wasi__
  std::lock_guard<std::mutex> lock(run_mutex);
#endif
  live_scorer.attach(value);
}

void keyboard_assist_detach() { keyboard_assist_attach(nullptr); }

void keyboard_assist_configure_threads(int decode_threads, int batch_threads) {
#ifdef __wasi__
  configured_decode_threads =
      decode_threads > 0 ? std::clamp(decode_threads, 1, 32) : -1;
  configured_batch_threads =
      batch_threads > 0 ? std::clamp(batch_threads, 1, 64) : -1;
  if (configured_decode_threads > 0)
    live_scorer.n_threads_decode = configured_decode_threads;
  if (configured_batch_threads > 0)
    live_scorer.n_threads_batch = configured_batch_threads;
  if (live_scorer.context) {
    llama_set_n_threads(live_scorer.context, live_scorer.n_threads_decode,
                        live_scorer.n_threads_batch);
  }
#else
  const int dec =
      decode_threads > 0 ? std::clamp(decode_threads, 1, 32) : -1;
  const int bat =
      batch_threads > 0 ? std::clamp(batch_threads, 1, 64) : -1;
  configured_decode_threads.store(dec);
  configured_batch_threads.store(bat);
  std::lock_guard<std::mutex> lock(run_mutex);
  if (dec > 0)
    live_scorer.n_threads_decode = dec;
  if (bat > 0)
    live_scorer.n_threads_batch = bat;
  if (live_scorer.context) {
    llama_set_n_threads(live_scorer.context, live_scorer.n_threads_decode,
                        live_scorer.n_threads_batch);
  }
#endif
}

std::string keyboard_assist_request(const char *payload_text,
                                    unsigned long long timeout_ms) {
  if (!payload_text)
    return "null";
  const std::string payload = payload_text;
  const auto path = field(payload, "dictionary");
  // Android can defer dictionary loading. The single-threaded WASI host calls
  // this on its separate assistance Worker.
  const auto dict = ready_dictionary(path);
  if (!dict)
    return "null";
#ifndef __wasi__
  std::unique_lock<std::mutex> lock(run_mutex, std::try_to_lock);
  if (!lock.owns_lock())
    return "null";
  try {
#endif
    return live_scorer.request(*dict, payload_text, timeout_ms);
#ifndef __wasi__
  } catch (...) {
    return "null";
  }
#endif
}

#ifdef __ANDROID__
struct BenchmarkKeyboardAssist {
  ChannelDictionary dictionary;
  KeyboardScorer scorer{false};

  BenchmarkKeyboardAssist(llama_model *model, const char *path) : dictionary(path) {
    scorer.attach(model);
  }
};

BenchmarkKeyboardAssist *keyboard_assist_open_benchmark(llama_model *model,
                                                       const char *path) {
  try {
    auto session = std::make_unique<BenchmarkKeyboardAssist>(model, path);
    if (session->scorer.ensure_context())
      return session.release();
  } catch (...) {
  }
  return nullptr;
}

void keyboard_assist_close_benchmark(BenchmarkKeyboardAssist *session) {
  delete session;
}

std::string keyboard_assist_request_benchmark(BenchmarkKeyboardAssist *session,
                                              const char *payload,
                                              unsigned long long timeout_ms) {
  if (!session || !payload)
    return "null";
  try {
    return session->scorer.request(session->dictionary, payload, timeout_ms);
  } catch (...) {
    return "null";
  }
}
#endif

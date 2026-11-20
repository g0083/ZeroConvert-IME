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
#endif
llama_model *model = nullptr;
llama_context *context = nullptr;
std::vector<llama_token> cached_prefix_tokens;
int n_threads_decode = 2;
int n_threads_batch = 4;
long long request_epoch = 0;
Clock::time_point deadline = Clock::time_point::max();

std::shared_ptr<ChannelDictionary> dictionary;
std::string dictionary_path;
#ifndef __wasi__
std::mutex dictionary_mutex;
std::string failed_path;
bool loading = false;
#endif

bool cancelled() {
  return assist_epoch() != request_epoch || Clock::now() >= deadline;
}

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

bool ensure_context() {
  if (context)
    return true;
  if (!model)
    return false;
#ifdef __wasi__
  n_threads_decode = n_threads_batch = 1;
#else
  const int logical_cores =
      int(std::max(1u, std::thread::hardware_concurrency()));
  n_threads_decode =
      logical_cores <= 1 ? 1 : std::clamp(logical_cores / 2, 2, 4);
  n_threads_batch = logical_cores <= 1
                        ? 1
                        : std::clamp(logical_cores - 2, n_threads_decode, 6);
#endif
  auto cp = llama_context_default_params();
  cp.n_ctx = 512;
  cp.n_batch = 512;
  cp.n_ubatch = 512;
  cp.n_outputs_max = 512;
  cp.n_seq_max = 32;
  cp.kv_unified = true;
  cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
  cp.no_perf = true;
  cp.n_threads = n_threads_decode;
  cp.n_threads_batch = n_threads_batch;
  cp.offload_kqv = false;
  cp.abort_callback = [](void *) { return cancelled(); };
  cp.abort_callback_data = nullptr;
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
score_sequences(const llama_vocab *vocab,
                const std::vector<std::vector<llama_token>> &sequences,
                const std::vector<int> &prefix_lengths) {
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

  if (shared_unscored_len > 0) {
    const int delta = shared_unscored_len - lcp;
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
  std::vector<double> scores(sequences.size(), -1e9),
      normalizers(nodes.size(), 0.0);
  for (int i = 0; i < active_count; ++i)
    scores[i] = 0.0;
  for (int i = 0; i < int(nodes.size()); ++i) {
    if (!nodes[i].need_logits)
      continue;
    const float *logits = llama_get_logits_ith(context, i);
    if (!logits) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      return {};
    }
    const float max_value = *std::max_element(logits, logits + nv);
    double denom = 0.0;
    for (int j = 0; j < nv; ++j)
      denom += double(std::expf(logits[j] - max_value));
    normalizers[i] = double(max_value) + std::log(denom);
  }
  for (const auto &edge : edges) {
    scores[edge.owner] +=
        double(llama_get_logits_ith(context, edge.node)[edge.next]) -
        normalizers[edge.node];
  }
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

std::string run(const ChannelDictionary &dict, const std::string &raw_keys,
                const std::string &input_utf8,
                const std::string &context_utf8,
                const std::string &touch_hints,
                const std::string &mode_str) {
  const auto *vocab = llama_model_get_vocab(model);
  const auto input_mode = (mode_str == "flick") ? InputKeyboardMode::Flick
                                                : InputKeyboardMode::Qwerty;
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
  const auto windows =
      build_scoring_windows(orig.reading, ctx_u32, candidates, dict);
  if (windows.empty()) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  struct Ranked {
    double stage1_delta;
    double final_score;
    size_t index;
    size_t win_idx;
    size_t member_idx;
    std::u32string prompt_prefix;
    std::vector<llama_token> cand_tokens;
  };
  std::vector<Ranked> ranked;
  for (size_t w = 0; w < windows.size(); ++w) {
    const auto &win = windows[w];
    const std::u32string prompt_prefix =
        win.left_context.empty() ? U"\uee00"
                                 : (U"\uee02" + win.left_context + U"\uee00");
    const auto pfx_tokens = tokenize(vocab, encode_utf8(prompt_prefix));
    if (pfx_tokens.empty())
      return "null";
    std::vector<std::vector<llama_token>> sequences;
    sequences.reserve(1 + win.members.size());
    auto orig_seq = tokenize(
        vocab, encode_utf8(prompt_prefix + katakana(win.orig_slice) + U"\uee01"));
    if (orig_seq.empty())
      return "null";
    sequences.push_back(std::move(orig_seq));
    for (const auto &m : win.members) {
      auto c_seq = tokenize(
          vocab, encode_utf8(prompt_prefix + katakana(m.cand_slice) + U"\uee01"));
      if (c_seq.empty())
        return "null";
      sequences.push_back(std::move(c_seq));
    }
    std::vector<int> lengths(sequences.size(), int(pfx_tokens.size()));
    const auto scores = score_sequences(vocab, sequences, lengths);
    if (scores.empty())
      return "null";
    if (scores[0] <= -1e8)
      continue;
    const double baseline = win.orig_path.score + 8.0 * scores[0];
    const int orig_tokens = int(sequences[0].size());
    for (size_t j = 0; j < win.members.size(); ++j) {
      const auto &m = win.members[j];
      const auto &cand = candidates[m.candidate_index];
      const int token_diff = int(sequences[j + 1].size()) - orig_tokens;
      const double len_comp =
          (m.cand_slice.size() >= win.orig_slice.size() && token_diff > 0 &&
           m.cand_path.score > win.orig_path.score + 3.5)
              ? 6.0 * std::min(token_diff, 2)
              : 0.0;
      const double total = m.cand_path.score + 8.0 * scores[j + 1] + len_comp -
                           cand.penalty * 3.0;
      const double delta = total - baseline;
      if (delta >= 34.0)
        ranked.push_back({delta, delta, m.candidate_index, w, j, prompt_prefix,
                          std::move(sequences[j + 1])});
    }
  }
  std::sort(ranked.begin(), ranked.end(), [](const Ranked &a, const Ranked &b) {
    return a.stage1_delta > b.stage1_delta;
  });
  if (ranked.empty() || ranked[0].stage1_delta < 37.0) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  const size_t top_win = ranked[0].win_idx;
  std::vector<size_t> win_ranked;
  for (size_t k = 0; k < ranked.size() && win_ranked.size() < 3; ++k) {
    if (ranked[k].win_idx == top_win)
      win_ranked.push_back(k);
  }
  if (win_ranked.size() >= 2 &&
      (ranked[win_ranked[0]].stage1_delta - ranked[win_ranked[1]].stage1_delta <
       assist_rerank_margin)) {
    std::vector<std::vector<llama_token>> surf_sequences;
    std::vector<int> surf_prefixes;
    std::vector<size_t> valid_pos;
    const auto &win = windows[top_win];
    for (size_t pos : win_ranked) {
      const auto &rc = ranked[pos];
      const auto &m = win.members[rc.member_idx];
      if (m.cand_path.surface.empty())
        continue;
      auto full = tokenize(vocab,
                           encode_utf8(rc.prompt_prefix + katakana(m.cand_slice) +
                                       U"\uee01" + decode_utf8(m.cand_path.surface)));
      const int pfx_len = int(rc.cand_tokens.size());
      if (int(full.size()) > pfx_len) {
        surf_sequences.push_back(std::move(full));
        surf_prefixes.push_back(pfx_len);
        valid_pos.push_back(pos);
      }
    }
    if (surf_sequences.size() >= 2) {
      const auto surf_scores =
          score_sequences(vocab, surf_sequences, surf_prefixes);
      if (surf_scores.empty())
        return "null";
      for (size_t j = 0; j < valid_pos.size(); ++j) {
        const size_t pos = valid_pos[j];
        const int n_surf = int(surf_sequences[j].size()) - surf_prefixes[j];
        ranked[pos].final_score =
            ranked[pos].stage1_delta +
            5.0 * (surf_scores[j] / std::max(1, n_surf));
      }
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
    }
  }
  const size_t best = ranked[0].index;
  const double best_delta = std::max(37.0, ranked[0].stage1_delta);
  out << "{\"observed\":" << json_string(encode_utf8(orig.reading))
      << ",\"reading\":" << json_string(encode_utf8(candidates[best].reading));
  if (ranked.size() >= 2 && ranked[1].stage1_delta >= 35.0) {
    out << ",\"alt_reading\":"
        << json_string(encode_utf8(candidates[ranked[1].index].reading));
    if (has_clear_assist_lead(ranked))
      out << ",\"clear_lead\":true";
  }
  out << ",\"gain\":" << std::setprecision(14) << best_delta << "}";
  return out.str();
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
  if (value != model && context) {
    llama_free(context);
    context = nullptr;
    cached_prefix_tokens.clear();
  }
  model = value;
}

void keyboard_assist_detach() {
#ifndef __wasi__
  std::lock_guard<std::mutex> lock(run_mutex);
#endif
  if (context) {
    llama_free(context);
    context = nullptr;
    cached_prefix_tokens.clear();
  }
  model = nullptr;
}

std::string keyboard_assist_request(const char *payload_text,
                                    unsigned long long timeout_ms) {
  if (!payload_text)
    return "null";
  const std::string payload = payload_text;
  const auto raw = field(payload, "raw");
  const auto input = field(payload, "input");
  const auto ctx = field(payload, "context");
  const auto path = field(payload, "dictionary");
  const auto touch = field(payload, "touch");
  const auto mode_str = field(payload, "mode");
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
    request_epoch = assist_epoch();
    deadline =
        Clock::now() + std::chrono::milliseconds(
                           std::min<unsigned long long>(timeout_ms, 30000));
    if (!ensure_context())
      return "null";
    return run(*dict, raw, input, ctx, touch, mode_str);
#ifndef __wasi__
  } catch (...) {
    return "null";
  }
#endif
}

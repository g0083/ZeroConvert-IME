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
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

long long assist_epoch();

namespace {
using Clock = std::chrono::steady_clock;

std::mutex run_mutex; // guards model/context and serializes scoring
llama_model *model = nullptr;
llama_context *context = nullptr;
std::vector<llama_token> cached_prefix_tokens;
int n_threads_decode = 2;
int n_threads_batch = 4;
long long request_epoch = 0;
Clock::time_point deadline = Clock::time_point::max();

std::mutex dictionary_mutex;
std::shared_ptr<ChannelDictionary> dictionary;
std::string dictionary_path;
std::string failed_path;
bool loading = false;

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

bool ensure_context() {
  if (context)
    return true;
  if (!model)
    return false;
  const int logical_cores =
      int(std::max(1u, std::thread::hardware_concurrency()));
  n_threads_decode =
      logical_cores <= 1 ? 1 : std::clamp(logical_cores / 2, 2, 4);
  n_threads_batch = logical_cores <= 1
                        ? 1
                        : std::clamp(logical_cores - 2, n_threads_decode, 6);
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
    throw std::runtime_error("invalid tokenized sequence");
  tokens.resize(n);
  return tokens;
}

std::vector<double>
score_sequences(const llama_vocab *vocab,
                const std::vector<std::vector<llama_token>> &sequences,
                const std::vector<int> &prefix_lengths) {
  const int nv = llama_vocab_n_tokens(vocab);
  const int count = int(sequences.size());
  if (count == 0)
    return {};
  if (count > 32)
    throw std::runtime_error("sequence count exceeds n_seq_max");

  int max_shared = int(sequences[0].size()) - 1;
  for (int i = 0; i < count; ++i) {
    max_shared =
        std::min(max_shared, std::min(int(sequences[i].size()) - 1,
                                      std::max(0, prefix_lengths[i] - 1)));
  }
  int shared_unscored_len = 0;
  while (shared_unscored_len < max_shared) {
    const llama_token tok = sequences[0][shared_unscored_len];
    bool all_match = true;
    for (int i = 1; i < count; ++i) {
      if (sequences[i][shared_unscored_len] != tok) {
        all_match = false;
        break;
      }
    }
    if (!all_match)
      break;
    ++shared_unscored_len;
  }

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
  std::map<std::vector<llama_token>, int> prefixes;
  std::vector<Node> nodes;
  std::vector<Edge> edges;
  for (int i = 0; i < count; ++i) {
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
      } else
        node = found->second;
      nodes[node].owners.push_back(i);
      if (j + 1 >= prefix_lengths[i]) {
        nodes[node].need_logits = true;
        edges.push_back({node, i, tokens[j + 1]});
      }
    }
  }
  if (nodes.empty())
    return std::vector<double>(count, 0.0);
  if (shared_unscored_len + int(nodes.size()) > 512)
    throw std::runtime_error("batch size");

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
        throw std::runtime_error("llama_decode prefix failed");
      }
    }
    for (int s = 1; s < count; ++s) {
      llama_memory_seq_cp(mem, 0, s, 0,
                          static_cast<llama_pos>(shared_unscored_len));
    }
    cached_prefix_tokens.assign(sequences[0].begin(),
                                sequences[0].begin() + shared_unscored_len);
  } else {
    cached_prefix_tokens.clear();
  }

  auto batch = llama_batch_init(int(nodes.size()), 0, count);
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
    throw std::runtime_error("llama_decode failed");
  }
  std::vector<double> scores(count, 0.0), normalizers(nodes.size(), 0.0);
  for (int i = 0; i < int(nodes.size()); ++i) {
    if (!nodes[i].need_logits)
      continue;
    const float *logits = llama_get_logits_ith(context, i);
    if (!logits) {
      llama_memory_clear(mem, true);
      cached_prefix_tokens.clear();
      throw std::runtime_error("missing logits");
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
                const std::string &context_utf8) {
  const auto *vocab = llama_model_get_vocab(model);
  const auto input_hira = !input_utf8.empty()
                              ? hiragana(decode_utf8(input_utf8))
                              : roman_decode(raw_keys);
  const auto candidates = assist_candidates(raw_keys, input_hira, dict, 24);
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
  const std::u32string prompt_prefix =
      ctx_u32.empty() ? U"\uee00" : (U"\uee02" + ctx_u32 + U"\uee00");
  const auto pfx_tokens = tokenize(vocab, encode_utf8(prompt_prefix));
  std::vector<std::vector<llama_token>> sequences;
  std::vector<int> lengths(candidates.size(), int(pfx_tokens.size()));
  for (const auto &c : candidates)
    sequences.push_back(tokenize(
        vocab, encode_utf8(prompt_prefix + katakana(c.reading) + U"\uee01")));
  const auto scores = score_sequences(vocab, sequences, lengths);
  const auto &orig = candidates[0];
  const double baseline = orig.path.score + 8.0 * scores[0];
  const int orig_tokens = int(sequences[0].size());
  struct Ranked {
    double stage1_delta;
    double final_score;
    size_t index;
  };
  std::vector<Ranked> ranked;
  for (size_t i = 1; i < candidates.size(); ++i) {
    const int token_diff = int(sequences[i].size()) - orig_tokens;
    const double len_comp =
        (token_diff > 0 && candidates[i].path.score > orig.path.score + 3.5)
            ? 6.0 * std::min(token_diff, 2)
            : 0.0;
    const double total = candidates[i].path.score + 8.0 * scores[i] + len_comp -
                         candidates[i].penalty * 3.0;
    const double delta = total - baseline;
    if (delta >= 34.0)
      ranked.push_back({delta, delta, i});
  }
  std::sort(ranked.begin(), ranked.end(), [](const Ranked &a, const Ranked &b) {
    return a.stage1_delta > b.stage1_delta;
  });
  if (ranked.empty() || ranked[0].stage1_delta < 37.0) {
    out << "{\"observed\":" << json_string(encode_utf8(orig.reading)) << "}";
    return out.str();
  }
  const size_t top_k = std::min<size_t>(ranked.size(), 3);
  if (top_k >= 2) {
    std::vector<std::vector<llama_token>> surf_sequences;
    std::vector<int> surf_prefixes;
    std::vector<size_t> valid_k;
    for (size_t k = 0; k < top_k; ++k) {
      const size_t idx = ranked[k].index;
      const auto &c = candidates[idx];
      if (c.path.surface.empty())
        continue;
      auto full =
          tokenize(vocab, encode_utf8(prompt_prefix + katakana(c.reading) +
                                      U"\uee01" + decode_utf8(c.path.surface)));
      const int pfx_len = int(sequences[idx].size());
      if (int(full.size()) > pfx_len) {
        surf_sequences.push_back(std::move(full));
        surf_prefixes.push_back(pfx_len);
        valid_k.push_back(k);
      }
    }
    if (surf_sequences.size() >= 2) {
      const auto surf_scores =
          score_sequences(vocab, surf_sequences, surf_prefixes);
      for (size_t j = 0; j < valid_k.size(); ++j) {
        const int n_surf = int(surf_sequences[j].size()) - surf_prefixes[j];
        ranked[valid_k[j]].final_score =
            ranked[valid_k[j]].stage1_delta +
            5.0 * (surf_scores[j] / std::max(1, n_surf));
      }
      std::sort(ranked.begin(), ranked.begin() + top_k,
                [](const Ranked &a, const Ranked &b) {
                  return a.final_score > b.final_score;
                });
    }
  }
  const size_t best = ranked[0].index;
  const double best_delta = std::max(37.0, ranked[0].stage1_delta);
  out << "{\"observed\":" << json_string(encode_utf8(orig.reading))
      << ",\"reading\":" << json_string(encode_utf8(candidates[best].reading));
  if (top_k >= 2 && ranked[1].stage1_delta >= 35.0) {
    out << ",\"alt_reading\":"
        << json_string(encode_utf8(candidates[ranked[1].index].reading));
  }
  out << ",\"gain\":" << std::setprecision(14) << best_delta << "}";
  return out.str();
}
} // namespace

void keyboard_assist_attach(llama_model *value) {
  std::lock_guard<std::mutex> lock(run_mutex);
  if (value != model && context) {
    llama_free(context);
    context = nullptr;
    cached_prefix_tokens.clear();
  }
  model = value;
}

void keyboard_assist_detach() {
  std::lock_guard<std::mutex> lock(run_mutex);
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
  // The dictionary builds on its own thread; this request just yields nothing.
  const auto dict = ready_dictionary(path);
  if (!dict)
    return "null";
  std::unique_lock<std::mutex> lock(run_mutex, std::try_to_lock);
  if (!lock.owns_lock())
    return "null";
  try {
    request_epoch = assist_epoch();
    deadline =
        Clock::now() + std::chrono::milliseconds(
                           std::min<unsigned long long>(timeout_ms, 30000));
    if (!ensure_context())
      return "null";
    return run(*dict, raw, input, ctx);
  } catch (...) {
    return "null";
  }
}

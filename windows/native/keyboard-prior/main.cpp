// Offline persistent likelihood probe for the pinned llama.cpp b12125 ABI.
// Input: count, then count lines of token_count, prefix_count, and token IDs.
// Each sequence contains the prompt marker, reading, and boundary token.
// Output: one sum log-likelihood per sequence, one line per request.
// No user settings, model files, or IME processes are modified.
#include "channel.hpp"
#include "benchmark_model.hpp"
#include "llama.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <deque>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

namespace {
int env_int_clamped(const char *name, int fallback, int min_val, int max_val) {
  const char *raw = std::getenv(name);
  if (!raw || !*raw)
    return fallback;
  try {
    const int parsed = std::stoi(raw);
    return std::clamp(parsed, min_val, max_val);
  } catch (...) {
    return fallback;
  }
}

int benchmark_worker() {
#ifdef _WIN32
  _setmode(_fileno(stdin), _O_BINARY);
#endif
  llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
  if (const char *directory = std::getenv("AZOOKEY_LLAMA_BACKEND_DIR"))
    ggml_backend_load_all_from_path(directory);
  llama_backend_init();
  std::string path;
  BenchmarkModel model;
  const int layers = env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS", 0, 0, 99);
  if (!std::getline(std::cin, path) || !model.load(path.c_str(), layers) || !model.warm_up())
    return 3;
  std::cout << "ready" << std::endl;
  std::string header;
  while (std::getline(std::cin, header)) {
    std::istringstream request(header);
    int tokens, timeout, bytes;
    if (!(request >> tokens >> timeout >> bytes) || bytes < 1 || bytes > 8192 ||
        tokens < 1 || tokens > 384 || timeout < 1 || timeout > 2000)
      return 2;
    std::string prompt(bytes, '\0');
    if (!std::cin.read(prompt.data(), bytes)) return 2;
    const auto start = BenchmarkModel::Clock::now();
    const auto result = model.complete(prompt, tokens, timeout);
    const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        BenchmarkModel::Clock::now() - start).count();
    std::cout << "{\"reason\":" << json_string(result.reason)
              << ",\"text\":" << json_string(result.text)
              << ",\"elapsed_ns\":" << elapsed << "}" << std::endl;
  }
  return 0;
}

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
} // namespace

int main(int argc, char **argv) {
  if (argc == 2 && std::string(argv[1]) == "--benchmark") return benchmark_worker();
  if (argc == 3 && std::string(argv[1]) == "--validate-channel") {
    try {
      const ChannelDictionary dictionary(argv[2]);
      std::string keys;
      while (std::getline(std::cin, keys)) {
        const auto candidates = channel_candidates(keys, dictionary);
        std::cout << "[";
        for (size_t i = 0; i < candidates.size(); ++i) {
          if (i)
            std::cout << ",";
          std::cout << "{\"reading\":"
                    << json_string(encode_utf8(candidates[i].reading))
                    << ",\"surface\":"
                    << json_string(candidates[i].path.surface)
                    << ",\"dictionary\":" << std::setprecision(14)
                    << candidates[i].path.score << "}";
        }
        std::cout << "]" << std::endl;
      }
      return 0;
    } catch (const std::exception &e) {
      std::cerr << e.what() << '\n';
      return 5;
    }
  }
  const bool hybrid = argc == 4 && std::string(argv[1]) == "--hybrid";
  const bool assist = argc == 4 && std::string(argv[1]) == "--assist";
  if (argc != 2 && !hybrid && !assist) {
    std::cerr << "usage: keyboard-prior [--hybrid|--assist] model.gguf "
                 "[dictionary.bin]\n";
    return 2;
  }
  if (!std::getenv("AZOOKEY_KEYBOARD_PRIOR_LOG")) {
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
  }
  if (const char *backend_dir = std::getenv("AZOOKEY_LLAMA_BACKEND_DIR");
      backend_dir && *backend_dir) {
    ggml_backend_load_all_from_path(backend_dir);
  } else {
    ggml_backend_load_all();
  }
  llama_backend_init();
  const int gpu_layers =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS", 0, 0, 99);
  auto mp = llama_model_default_params();
  mp.n_gpu_layers = gpu_layers;
  mp.load_mode = LLAMA_LOAD_MODE_MMAP;
  ggml_backend_dev_t cpu_devices[2] = {nullptr, nullptr};
  if (gpu_layers <= 0) {
    mp.split_mode = LLAMA_SPLIT_MODE_NONE;
    if (auto *cpu_dev =
            ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
      cpu_devices[0] = cpu_dev;
      mp.devices = cpu_devices;
    }
  }
  auto *model =
      llama_model_load_from_file(argv[(hybrid || assist) ? 2 : 1], mp);
  if (!model)
    return 3;
  const int logical_cores =
      int(std::max(1u, std::thread::hardware_concurrency()));
  const int auto_threads =
      gpu_layers > 0
          ? (logical_cores <= 1 ? 1 : std::clamp((logical_cores + 1) / 2, 2, 4))
          : (logical_cores <= 1 ? 1 : std::clamp(logical_cores / 2, 2, 6));
  const int auto_threads_batch =
      gpu_layers > 0
          ? (logical_cores <= 1 ? 1
                                : std::clamp(logical_cores / 2, auto_threads, 8))
          : (logical_cores <= 1
                 ? 1
                 : std::clamp(logical_cores - 2, auto_threads, 6));
  const int n_threads =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_THREADS", auto_threads, 1, 32);
  const int n_threads_batch = env_int_clamped(
      "AZOOKEY_KEYBOARD_PRIOR_THREADS_BATCH", auto_threads_batch, 1, 64);
  const int default_batch = (hybrid || assist) ? 1024 : 2048;
  const int n_batch =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_BATCH", default_batch, 256, 4096);
  const int n_ubatch =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_UBATCH", 512, 64, n_batch);

  auto cp = llama_context_default_params();
  cp.n_ctx = (hybrid || assist) ? 1024 : 4096;
  cp.n_batch = (hybrid || assist) ? std::min(n_batch, 1024) : 4096;
  cp.n_ubatch = std::min(n_ubatch, int(cp.n_batch));
  cp.n_outputs_max = cp.n_batch;
  cp.n_seq_max = 32;
  cp.kv_unified = true;
  cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
  cp.no_perf = true;
  cp.n_threads = n_threads;
  cp.n_threads_batch = n_threads_batch;
  cp.offload_kqv = gpu_layers > 0;
  auto *ctx = llama_init_from_model(model, cp);
  if (!ctx) {
    llama_model_free(model);
    return 4;
  }
  const auto *vocab = llama_model_get_vocab(model);
  const int nv = llama_vocab_n_tokens(vocab);
  auto *mem = llama_get_memory(ctx);
  std::vector<llama_token> cached_prefix_tokens;
  auto reset_prefix_cache = [&] {
    // Invalidating the KV cells is enough. Decode overwrites their data before
    // making them visible again.
    llama_memory_clear(mem, false);
    cached_prefix_tokens.clear();
  };

  auto score_sequences =
      [&](const std::vector<std::vector<llama_token>> &sequences,
          const std::vector<int> &prefix_lengths,
          double *out_seq0_min_tok_lp = nullptr) {
        if (out_seq0_min_tok_lp)
          *out_seq0_min_tok_lp = 0.0;
        int active_count = int(sequences.size());
        if (active_count == 0)
          return std::vector<double>();
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
        const int max_ctx = int(llama_n_ctx(ctx));
        int shared_unscored_len = 0;
        std::vector<Node> nodes;
        std::vector<Edge> edges;
        while (true) {
          int max_shared = int(sequences[0].size()) - 1;
          for (int i = 0; i < active_count; ++i) {
            max_shared = std::min(
                max_shared,
                std::min(int(sequences[i].size()) - 1,
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

        int lcp = 0;
        const int cached_limit =
            std::min(shared_unscored_len, int(cached_prefix_tokens.size()));
        while (lcp < cached_limit &&
               cached_prefix_tokens[lcp] == sequences[0][lcp]) {
          ++lcp;
        }
        if (lcp < int(cached_prefix_tokens.size())) {
          if (lcp == 0 ||
              !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(lcp), -1)) {
            reset_prefix_cache();
            lcp = 0;
          }
        }

        const int delta = shared_unscored_len - lcp;
        const int n_batch_limit = int(llama_n_batch(ctx));
        const bool merge_prefix_and_tree =
            delta > 0 && delta + int(nodes.size()) <= n_batch_limit;
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
          llama_set_n_threads(ctx, n_threads,
                              batch.n_tokens <= 4 ? n_threads : n_threads_batch);
          const int rc = llama_decode(ctx, batch);
          llama_batch_free(batch);
          if (rc != 0) {
            reset_prefix_cache();
            throw std::runtime_error("llama_decode failed");
          }
        } else {
          if (delta > 0) {
            llama_set_n_threads(ctx, n_threads,
                                delta <= 4 ? n_threads : n_threads_batch);
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
            const int pfx_rc = llama_decode(ctx, pfx_batch);
            llama_batch_free(pfx_batch);
            if (pfx_rc != 0) {
              reset_prefix_cache();
              throw std::runtime_error("llama_decode prefix failed");
            }
          }
          if (shared_unscored_len > 0) {
            for (int s = 1; s < active_count; ++s) {
              llama_memory_seq_cp(mem, 0, s, 0,
                                  static_cast<llama_pos>(shared_unscored_len));
            }
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
          llama_set_n_threads(ctx, n_threads,
                              batch.n_tokens <= 4 ? n_threads : n_threads_batch);
          const int rc = llama_decode(ctx, batch);
          llama_batch_free(batch);
          if (rc != 0) {
            reset_prefix_cache();
            throw std::runtime_error("llama_decode failed");
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
              llama_get_logits_ith(ctx, node_logits_offset + i);
          if (!logits) {
            reset_prefix_cache();
            throw std::runtime_error("missing logits");
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
            seq0_min_lp =
                seq0_has_tok ? std::min(seq0_min_lp, tok_lp) : tok_lp;
            seq0_has_tok = true;
          }
        }
        if (out_seq0_min_tok_lp)
          *out_seq0_min_tok_lp = seq0_has_tok ? seq0_min_lp : 0.0;
        if (shared_unscored_len > 0) {
          llama_memory_seq_keep(mem, 0);
          if (!llama_memory_seq_rm(
                  mem, 0, static_cast<llama_pos>(shared_unscored_len), -1)) {
            reset_prefix_cache();
          } else {
            cached_prefix_tokens.assign(
                sequences[0].begin(), sequences[0].begin() + shared_unscored_len);
          }
        } else {
          reset_prefix_cache();
        }
        return scores;
      };
  try {
    if (hybrid) {
      const ChannelDictionary dictionary(argv[3]);
      std::cout << "ready " << nv << std::endl;
      auto tokenize = [&](const std::string &text) {
        std::vector<llama_token> tokens(256);
        const int n =
            llama_tokenize(vocab, text.data(), int(text.size()), tokens.data(),
                           int(tokens.size()), false, true);
        if (n < 1 || n > 128)
          throw std::runtime_error("invalid tokenized reading");
        tokens.resize(n);
        return tokens;
      };
      const auto prefix = tokenize(encode_utf8(U"\uee00"));
      std::string keys;
      while (std::getline(std::cin, keys)) {
        const auto candidates = channel_candidates(keys, dictionary);
        if (candidates.empty()) {
          std::cout << "{}" << std::endl;
          continue;
        }
        const auto original = roman_decode(keys);
        std::vector<std::vector<llama_token>> sequences;
        std::vector<int> lengths(candidates.size(), int(prefix.size()));
        for (const auto &candidate : candidates) {
          auto reading = candidate.reading;
          for (auto &c : reading)
            if (c >= U'ぁ' && c <= U'ゖ')
              c += 96;
          sequences.push_back(
              tokenize(encode_utf8(U"\uee00" + reading + U"\uee01")));
        }
        const auto scores = score_sequences(sequences, lengths);
        double baseline = 0;
        for (size_t i = 0; i < candidates.size(); ++i)
          if (candidates[i].reading == original)
            baseline = candidates[i].path.score + 2 * scores[i];
        size_t best = candidates.size();
        double gain = 37.;
        for (size_t i = 0; i < candidates.size(); ++i) {
          const double delta =
              candidates[i].path.score + 2 * scores[i] - baseline;
          if (candidates[i].reading != original && delta >= gain &&
              (best == candidates.size() || delta > gain)) {
            best = i;
            gain = delta;
          }
        }
        if (best == candidates.size())
          std::cout << "{\"observed\":" << json_string(encode_utf8(original))
                    << "}" << std::endl;
        else
          std::cout << "{\"observed\":" << json_string(encode_utf8(original))
                    << ",\"reading\":"
                    << json_string(encode_utf8(candidates[best].reading))
                    << ",\"surface\":"
                    << json_string(candidates[best].path.surface)
                    << ",\"gain\":" << std::setprecision(14) << gain << "}"
                    << std::endl;
      }
    } else if (assist) {
      const ChannelDictionary dictionary(argv[3]);
      auto tokenize = [&](const std::string &text) {
        std::vector<llama_token> tokens(512);
        const int n =
            llama_tokenize(vocab, text.data(), int(text.size()), tokens.data(),
                           int(tokens.size()), false, true);
        if (n < 1 || n > 384)
          throw std::runtime_error("invalid tokenized sequence");
        tokens.resize(n);
        return tokens;
      };
      if (gpu_layers > 0) {
        // Vulkan lazily creates pipelines for different batch shapes. Exercise
        // prefix prefill and small/large candidate batches on synthetic tokens.
        const auto warm = [&](int count, int prefix_length, int branch_length) {
          std::vector<std::vector<llama_token>> sequences;
          for (int i = 0; i < count; ++i) {
            std::vector<llama_token> sequence;
            for (int j = 0; j < prefix_length; ++j)
              sequence.push_back((20 + j) % nv);
            for (int j = 0; j < branch_length; ++j)
              sequence.push_back((80 + i * 23 + j) % nv);
            sequences.push_back(std::move(sequence));
          }
          score_sequences(sequences, std::vector<int>(count, prefix_length));
        };
        warm(2, 1, 5);
        // Small prefix prefills use separate kernels. Start each with an empty
        // cache so the full prefix length is decoded, not just its last token.
        for (int prefix_tokens = 1; prefix_tokens <= 8; ++prefix_tokens) {
          reset_prefix_cache();
          warm(2, prefix_tokens + 1, 5);
        }
        warm(3, 5, 10);
        warm(8, 17, 12);
        warm(24, 49, 16);
        reset_prefix_cache();
      }
      std::cout << "ready " << nv << std::endl;
      auto evaluate_repair = [&](const std::string &line) -> std::string {
        std::string raw_keys, input_utf8, context_utf8, touch_hints, mode_str;
        const size_t tab1 = line.find('\t');
        if (tab1 == std::string::npos) {
          const bool all_lower =
              std::any_of(line.begin(), line.end(),
                          [](char c) { return c >= 'a' && c <= 'z'; }) &&
              std::all_of(line.begin(), line.end(), [](char c) {
                return (c >= 'a' && c <= 'z') || c == '-';
              });
          if (all_lower)
            raw_keys = line;
          else
            input_utf8 = line;
        } else {
          raw_keys = line.substr(0, tab1);
          const size_t tab2 = line.find('\t', tab1 + 1);
          if (tab2 == std::string::npos) {
            input_utf8 = line.substr(tab1 + 1);
          } else {
            input_utf8 = line.substr(tab1 + 1, tab2 - tab1 - 1);
            const size_t tab3 = line.find('\t', tab2 + 1);
            if (tab3 == std::string::npos) {
              context_utf8 = line.substr(tab2 + 1);
            } else {
              context_utf8 = line.substr(tab2 + 1, tab3 - tab2 - 1);
              const size_t tab4 = line.find('\t', tab3 + 1);
              if (tab4 == std::string::npos) {
                touch_hints = line.substr(tab3 + 1);
              } else {
                touch_hints = line.substr(tab3 + 1, tab4 - tab3 - 1);
                mode_str = line.substr(tab4 + 1);
              }
            }
          }
        }
        const auto input_mode = parse_input_keyboard_mode(mode_str);
        const bool is_flick_mode = is_flick_keyboard_mode(input_mode);
        const double rank_pen_weight = is_flick_mode ? 5.5 : 4.0;
        const double stage1_pen_weight = is_flick_mode ? 5.0 : 3.0;
        const auto input_hira = !input_utf8.empty()
                                    ? hiragana(decode_utf8(input_utf8))
                                    : roman_decode(raw_keys);
        const auto candidates = assist_candidates(
            raw_keys, input_hira, dictionary, 24, touch_hints, input_mode);
        std::ostringstream reply;
        if (candidates.size() <= 1) {
          reply << "{\"observed\":" << json_string(encode_utf8(input_hira))
                << "}";
          return reply.str();
        }
        std::u32string ctx_u32;
        if (!context_utf8.empty()) {
          ctx_u32 = decode_utf8(context_utf8);
          ctx_u32.erase(std::remove_if(ctx_u32.begin(), ctx_u32.end(),
                                       [](char32_t c) {
                                         return c < 32 || (c >= U'\uee00' &&
                                                           c <= U'\uee02');
                                       }),
                        ctx_u32.end());
          if (ctx_u32.size() > 64)
            ctx_u32 = ctx_u32.substr(ctx_u32.size() - 64);
        }
        const auto &orig = candidates[0];
        const auto windows = build_scoring_windows(
            orig.reading, ctx_u32, candidates, dictionary, input_mode);
        if (windows.empty()) {
          reply << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                << "}";
          return reply.str();
        }
        struct RankedCandidate {
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
        std::vector<RankedCandidate> ranked;
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
                      const auto &ca =
                          candidates[win.members[a].candidate_index];
                      const auto &cb =
                          candidates[win.members[b].candidate_index];
                      const double ra =
                          ca.local_delta - ca.penalty * rank_pen_weight;
                      const double rb =
                          cb.local_delta - cb.penalty * rank_pen_weight;
                      return ra != rb ? ra > rb : a < b;
                    });
          const auto masked_ctx = mask_digits_nam_u32(win.left_context);
          const std::u32string prompt_prefix =
              masked_ctx.empty() ? U"\uee00"
                                 : (U"\uee02" + masked_ctx + U"\uee00");
          const auto pfx_tokens = tokenize(encode_utf8(prompt_prefix));
          const int pfx_len = int(pfx_tokens.size());
          const bool orig_has_ascii = std::any_of(
              win.orig_slice.begin(), win.orig_slice.end(), [](char32_t c) {
                return (c >= U'a' && c <= U'z') ||
                       (c >= U'A' && c <= U'Z') ||
                       (c >= U'ａ' && c <= U'ｚ') ||
                       (c >= U'Ａ' && c <= U'Ｚ');
              });

          constexpr size_t kTier1 = 8;
          const size_t tier1_n = std::min(member_order.size(), kTier1);
          std::vector<std::vector<llama_token>> tier1_seqs;
          tier1_seqs.reserve(1 + tier1_n);
          tier1_seqs.push_back(tokenize(encode_utf8(
              prompt_prefix + mask_digits_nam_u32(katakana(win.orig_slice)) +
              U"\uee01")));
          for (size_t t = 0; t < tier1_n; ++t) {
            const auto &m = win.members[member_order[t]];
            tier1_seqs.push_back(tokenize(encode_utf8(
                prompt_prefix + mask_digits_nam_u32(katakana(m.cand_slice)) +
                U"\uee01")));
          }
          std::vector<int> tier1_lengths(tier1_seqs.size(), pfx_len);
          double orig_min_tok_lp = 0.0;
          const auto tier1_scores =
              score_sequences(tier1_seqs, tier1_lengths, &orig_min_tok_lp);
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
            if (token_diff > 0 &&
                m.cand_slice.size() >= win.orig_slice.size() &&
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
            if (delta >= 28.0) {
              ranked.push_back({delta, delta, 0.0, false, false,
                                m.candidate_index, w, j, prompt_prefix,
                                std::move(cand_tokens)});
            }
            return delta;
          };

          double tier1_best_delta = -1e9;
          bool tier1_best_non_shrinking = false;
          for (size_t t = 0; t < tier1_n; ++t) {
            const size_t j = member_order[t];
            const double delta = score_member_delta(
                j, tier1_seqs[t + 1], tier1_scores[t + 1]);
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
            const double clean_score_floor =
                -4.0 * double(win.orig_slice.size());
            const bool clean_ok =
                tier1_best_delta < 20.0 && orig_min_tok_lp > -6.8 &&
                win.orig_path.score >= clean_score_floor;
            if (!clear_win && !clean_ok) {
              const size_t tier2_n = member_order.size() - tier1_n;
              std::vector<std::vector<llama_token>> tier2_seqs;
              tier2_seqs.reserve(tier2_n);
              for (size_t t = tier1_n; t < member_order.size(); ++t) {
                const auto &m = win.members[member_order[t]];
                tier2_seqs.push_back(tokenize(encode_utf8(
                    prompt_prefix +
                    mask_digits_nam_u32(katakana(m.cand_slice)) + U"\uee01")));
              }
              std::vector<int> tier2_lengths(tier2_seqs.size(), pfx_len);
              const auto tier2_scores =
                  score_sequences(tier2_seqs, tier2_lengths);
              for (size_t t = 0; t < tier2_n; ++t) {
                score_member_delta(member_order[tier1_n + t], tier2_seqs[t],
                                   tier2_scores[t]);
              }
            }
          }
        }
        std::sort(ranked.begin(), ranked.end(),
                  [](const RankedCandidate &a, const RankedCandidate &b) {
                    return a.stage1_delta > b.stage1_delta;
                  });
        if (ranked.empty() || ranked[0].stage1_delta < 28.0) {
          reply << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                << "}";
        } else {
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
              auto orig_full = tokenize(encode_utf8(
                  ranked[win_ranked[0]].prompt_prefix +
                  mask_digits_nam_u32(katakana(win.orig_slice)) + U"\uee01" +
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
                  auto full_seq = tokenize(encode_utf8(
                      rc.prompt_prefix +
                      mask_digits_nam_u32(katakana(m.cand_slice)) + U"\uee01" +
                      mask_digits_nam_u32(decode_utf8(m.cand_path.surface))));
                  if (eos_tok >= 0 && !full_seq.empty())
                    full_seq.push_back(eos_tok);
                  const int pfx_len = int(rc.cand_tokens.size());
                  if (int(full_seq.size()) > pfx_len) {
                    surf_sequences.push_back(std::move(full_seq));
                    surf_prefixes.push_back(pfx_len);
                    valid_pos.push_back(pos);
                  }
                }
              }
            }
            if (surf_sequences.size() >= 2 && !valid_pos.empty()) {
              const auto surf_scores =
                  score_sequences(surf_sequences, surf_prefixes);
              const int n_orig_surf =
                  int(surf_sequences[0].size()) - surf_prefixes[0];
              const double orig_surf_mean =
                  surf_scores[0] / std::max(1, n_orig_surf);
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
                std::vector<RankedCandidate> reordered;
                reordered.reserve(valid_pos.size());
                for (size_t pos : valid_pos)
                  reordered.push_back(ranked[pos]);
                std::sort(reordered.begin(), reordered.end(),
                          [](const RankedCandidate &a,
                             const RankedCandidate &b) {
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
            reply << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                  << "}";
          } else {
            const size_t best = top.index;
            const double best_delta = std::max(37.0, top.final_score);
            reply << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                  << ",\"reading\":"
                  << json_string(encode_utf8(candidates[best].reading));
            if (ranked.size() >= 2 && ranked[1].final_score >= 35.0) {
              const size_t alt = ranked[1].index;
              reply << ",\"alt_reading\":"
                    << json_string(encode_utf8(candidates[alt].reading));
              if (has_clear_assist_lead(ranked))
                reply << ",\"clear_lead\":true";
            }
            reply << ",\"gain\":" << std::setprecision(14) << best_delta
                  << "}";
          }
        }
        return reply.str();
      };

      // The native result depends only on the complete request and the
      // worker's fixed model and dictionary. Backspace and retyping often
      // return to a recently scored request.
      constexpr size_t recent_limit = 8;
      constexpr size_t request_size_limit = 1024;
      std::deque<std::pair<std::string, std::string>> recent;
      std::string line;
      while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r')
          line.pop_back();
        if (line.empty()) {
          std::cout << "{}" << std::endl;
          continue;
        }
        const auto cached = std::find_if(
            recent.begin(), recent.end(),
            [&](const auto &entry) { return entry.first == line; });
        if (cached != recent.end()) {
          std::cout << cached->second << std::endl;
          continue;
        }
        auto reply = evaluate_repair(line);
        std::cout << reply << std::endl;
        if (line.size() <= request_size_limit) {
          if (recent.size() == recent_limit)
            recent.pop_front();
          recent.emplace_back(std::move(line), std::move(reply));
        }
      }
    } else {
      std::cout << "ready " << nv << std::endl;
      int count;
      while (std::cin >> count) {
        if (count < 1 || count > 32)
          throw std::runtime_error("invalid candidate count");
        std::vector<std::vector<llama_token>> sequences(count);
        std::vector<int> prefix_lengths(count);
        int total = 0;
        for (int i = 0; i < count; ++i) {
          auto &tokens = sequences[i];
          int n, prefix;
          if (!(std::cin >> n >> prefix) || n < 2 || n > 128 || prefix < 1 ||
              prefix >= n)
            throw std::runtime_error("invalid sequence/prefix length");
          prefix_lengths[i] = prefix;
          tokens.resize(n);
          for (auto &token : tokens) {
            if (!(std::cin >> token) || token < 0 || token >= nv)
              throw std::runtime_error("invalid token");
          }
          total += n - 1;
        }
        if (total > 4096)
          throw std::runtime_error("batch too large");
        auto scores = score_sequences(sequences, prefix_lengths);
        for (int i = 0; i < count; ++i)
          std::cout << (i ? " " : "") << std::setprecision(12) << scores[i];
        std::cout << std::endl;
      }
    }
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 5;
  }
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
}

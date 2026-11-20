// Offline persistent likelihood probe for the pinned llama.cpp b12125 ABI.
// Input: count, then count lines of token_count, prefix_count, and token IDs.
// Each sequence contains the prompt marker, reading, and boundary token.
// Output: one sum log-likelihood per sequence, one line per request.
// No user settings, model files, or IME processes are modified.
#include "channel.hpp"
#include "llama.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

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
} // namespace

int main(int argc, char **argv) {
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
  const int default_batch = (hybrid || assist) ? 512 : 2048;
  const int n_batch =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_BATCH", default_batch, 256, 4096);
  const int n_ubatch =
      env_int_clamped("AZOOKEY_KEYBOARD_PRIOR_UBATCH", 512, 64, n_batch);

  auto cp = llama_context_default_params();
  cp.n_ctx = (hybrid || assist) ? 512 : 4096;
  cp.n_batch = (hybrid || assist) ? std::min(n_batch, 512) : 4096;
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
  std::vector<llama_token> cached_prefix_tokens;

  auto score_sequences =
      [&](const std::vector<std::vector<llama_token>> &sequences,
          const std::vector<int> &prefix_lengths) {
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

        auto *mem = llama_get_memory(ctx);
        int lcp = 0;
        const int cached_limit =
            std::min(shared_unscored_len, int(cached_prefix_tokens.size()));
        while (lcp < cached_limit &&
               cached_prefix_tokens[lcp] == sequences[0][lcp]) {
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
              llama_memory_clear(mem, true);
              cached_prefix_tokens.clear();
              throw std::runtime_error("llama_decode prefix failed");
            }
          }
          for (int s = 1; s < active_count; ++s) {
            llama_memory_seq_cp(mem, 0, s, 0,
                                static_cast<llama_pos>(shared_unscored_len));
          }
          cached_prefix_tokens.assign(
              sequences[0].begin(), sequences[0].begin() + shared_unscored_len);
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
        llama_set_n_threads(ctx, n_threads,
                            batch.n_tokens <= 4 ? n_threads : n_threads_batch);
        const int rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
          llama_memory_clear(mem, true);
          cached_prefix_tokens.clear();
          throw std::runtime_error("llama_decode failed");
        }
        std::vector<double> scores(sequences.size(), -1e9);
        for (int i = 0; i < active_count; ++i)
          scores[i] = 0.0;
        std::vector<double> normalizers(nodes.size(), 0.0);
        for (int i = 0; i < int(nodes.size()); ++i) {
          if (!nodes[i].need_logits)
            continue;
          const float *logits = llama_get_logits_ith(ctx, i);
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
          const float *logits = llama_get_logits_ith(ctx, edge.node);
          scores[edge.owner] +=
              double(logits[edge.next]) - normalizers[edge.node];
        }
        if (shared_unscored_len > 0) {
          llama_memory_seq_keep(mem, 0);
          if (!llama_memory_seq_rm(
                  mem, 0, static_cast<llama_pos>(shared_unscored_len), -1)) {
            llama_memory_clear(mem, true);
            cached_prefix_tokens.clear();
          }
        } else {
          llama_memory_clear(mem, true);
          cached_prefix_tokens.clear();
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
          cached_prefix_tokens.clear();
          warm(2, prefix_tokens + 1, 5);
        }
        warm(3, 5, 10);
        warm(8, 17, 12);
        warm(24, 49, 16);
        llama_memory_clear(llama_get_memory(ctx), true);
        cached_prefix_tokens.clear();
      }
      std::cout << "ready " << nv << std::endl;
      std::string line;
      while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r')
          line.pop_back();
        if (line.empty()) {
          std::cout << "{}" << std::endl;
          continue;
        }
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
        const auto input_mode = (mode_str == "flick")
                                    ? InputKeyboardMode::Flick
                                    : InputKeyboardMode::Qwerty;
        const auto input_hira = !input_utf8.empty()
                                    ? hiragana(decode_utf8(input_utf8))
                                    : roman_decode(raw_keys);
        const auto candidates = assist_candidates(
            raw_keys, input_hira, dictionary, 24, touch_hints, input_mode);
        if (candidates.size() <= 1) {
          std::cout << "{\"observed\":" << json_string(encode_utf8(input_hira))
                    << "}" << std::endl;
          continue;
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
        const auto windows =
            build_scoring_windows(orig.reading, ctx_u32, candidates, dictionary);
        if (windows.empty()) {
          std::cout << "{\"observed\":"
                    << json_string(encode_utf8(orig.reading)) << "}"
                    << std::endl;
          continue;
        }
        struct RankedCandidate {
          double stage1_delta;
          double final_score;
          size_t index;
          size_t win_idx;
          size_t member_idx;
          std::u32string prompt_prefix;
          std::vector<llama_token> cand_tokens;
        };
        std::vector<RankedCandidate> ranked;
        for (size_t w = 0; w < windows.size(); ++w) {
          const auto &win = windows[w];
          const std::u32string prompt_prefix =
              win.left_context.empty()
                  ? U"\uee00"
                  : (U"\uee02" + win.left_context + U"\uee00");
          const auto pfx_tokens = tokenize(encode_utf8(prompt_prefix));
          std::vector<std::vector<llama_token>> sequences;
          sequences.reserve(1 + win.members.size());
          sequences.push_back(tokenize(encode_utf8(
              prompt_prefix + katakana(win.orig_slice) + U"\uee01")));
          for (const auto &m : win.members) {
            sequences.push_back(tokenize(encode_utf8(
                prompt_prefix + katakana(m.cand_slice) + U"\uee01")));
          }
          std::vector<int> lengths(sequences.size(), int(pfx_tokens.size()));
          const auto scores = score_sequences(sequences, lengths);
          const double baseline = win.orig_path.score + 8.0 * scores[0];
          const int orig_tokens = int(sequences[0].size());
          for (size_t j = 0; j < win.members.size(); ++j) {
            const auto &m = win.members[j];
            const auto &cand = candidates[m.candidate_index];
            const int token_diff = int(sequences[j + 1].size()) - orig_tokens;
            const double len_comp =
                (m.cand_slice.size() >= win.orig_slice.size() &&
                 token_diff > 0 && m.cand_path.score > win.orig_path.score + 3.5)
                    ? 6.0 * std::min(token_diff, 2)
                    : 0.0;
            const double total = m.cand_path.score + 8.0 * scores[j + 1] +
                                 len_comp - cand.penalty * 3.0;
            const double delta = total - baseline;
            if (delta >= 34.0) {
              ranked.push_back({delta, delta, m.candidate_index, w, j,
                                prompt_prefix, std::move(sequences[j + 1])});
            }
          }
        }
        std::sort(ranked.begin(), ranked.end(),
                  [](const RankedCandidate &a, const RankedCandidate &b) {
                    return a.stage1_delta > b.stage1_delta;
                  });
        if (ranked.empty() || ranked[0].stage1_delta < 37.0) {
          std::cout << "{\"observed\":"
                    << json_string(encode_utf8(orig.reading)) << "}"
                    << std::endl;
        } else {
          const size_t top_win = ranked[0].win_idx;
          std::vector<size_t> win_ranked;
          for (size_t k = 0; k < ranked.size() && win_ranked.size() < 3; ++k) {
            if (ranked[k].win_idx == top_win)
              win_ranked.push_back(k);
          }
          if (win_ranked.size() >= 2 &&
              (ranked[win_ranked[0]].stage1_delta -
                   ranked[win_ranked[1]].stage1_delta <
               12.0)) {
            std::vector<std::vector<llama_token>> surf_sequences;
            std::vector<int> surf_prefixes;
            std::vector<size_t> valid_pos;
            const auto &win = windows[top_win];
            for (size_t pos : win_ranked) {
              const auto &rc = ranked[pos];
              const auto &m = win.members[rc.member_idx];
              if (m.cand_path.surface.empty())
                continue;
              auto full_seq = tokenize(encode_utf8(
                  rc.prompt_prefix + katakana(m.cand_slice) + U"\uee01" +
                  decode_utf8(m.cand_path.surface)));
              const int pfx_len = int(rc.cand_tokens.size());
              if (int(full_seq.size()) > pfx_len) {
                surf_sequences.push_back(std::move(full_seq));
                surf_prefixes.push_back(pfx_len);
                valid_pos.push_back(pos);
              }
            }
            if (surf_sequences.size() >= 2) {
              const auto surf_scores =
                  score_sequences(surf_sequences, surf_prefixes);
              for (size_t j = 0; j < valid_pos.size(); ++j) {
                const size_t pos = valid_pos[j];
                const int n_surf =
                    int(surf_sequences[j].size()) - surf_prefixes[j];
                const double surf_mean = surf_scores[j] / std::max(1, n_surf);
                ranked[pos].final_score =
                    ranked[pos].stage1_delta + 5.0 * surf_mean;
              }
              std::vector<RankedCandidate> reordered;
              reordered.reserve(valid_pos.size());
              for (size_t pos : valid_pos)
                reordered.push_back(ranked[pos]);
              std::sort(reordered.begin(), reordered.end(),
                        [](const RankedCandidate &a, const RankedCandidate &b) {
                          return a.final_score > b.final_score;
                        });
              for (size_t j = 0; j < valid_pos.size(); ++j)
                ranked[valid_pos[j]] = std::move(reordered[j]);
            }
          }
          const size_t best = ranked[0].index;
          const double best_delta = std::max(37.0, ranked[0].stage1_delta);
          std::cout << "{\"observed\":"
                    << json_string(encode_utf8(orig.reading)) << ",\"reading\":"
                    << json_string(encode_utf8(candidates[best].reading));
          if (ranked.size() >= 2 && ranked[1].stage1_delta >= 35.0) {
            const size_t alt = ranked[1].index;
            std::cout << ",\"alt_reading\":"
                      << json_string(encode_utf8(candidates[alt].reading));
          }
          std::cout << ",\"gain\":" << std::setprecision(14) << best_delta
                    << "}" << std::endl;
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

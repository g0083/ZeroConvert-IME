// Offline persistent likelihood probe for the pinned llama.cpp b12125 ABI.
// Input: count, then count lines of token_count, prefix_count, and token IDs.
// Each sequence contains the prompt marker, reading, and boundary token.
// Output: one sum log-likelihood per sequence, one line per request.
// No user settings, model files, or IME processes are modified.
#include "channel.hpp"
#include "llama.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <vector>

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
  ggml_backend_load_all();
  llama_backend_init();
  auto mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  auto *model = llama_model_load_from_file(argv[(hybrid || assist) ? 2 : 1], mp);
  if (!model)
    return 3;
  auto cp = llama_context_default_params();
  cp.n_ctx = 4096;
  cp.n_batch = 4096;
  cp.n_ubatch = 512;
  cp.n_seq_max = 32;
  cp.kv_unified = true;
  cp.n_threads = 4;
  cp.n_threads_batch = 4;
  cp.offload_kqv = false;
  auto *ctx = llama_init_from_model(model, cp);
  if (!ctx) {
    llama_model_free(model);
    return 4;
  }
  const auto *vocab = llama_model_get_vocab(model);
  const int nv = llama_vocab_n_tokens(vocab);

  auto score_sequences =
      [&](const std::vector<std::vector<llama_token>> &sequences,
          const std::vector<int> &prefix_lengths) {
        const int count = int(sequences.size());
        // Share identical token prefixes across candidate sequence IDs.
        // This preserves the scoring formula while avoiding repeated prefix
        // work. Floating point/quantized kernels can vary across batch shapes.
        struct Node {
          llama_token token;
          int pos;
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
          for (int j = 0; j < int(tokens.size()) - 1; ++j) {
            prefix.push_back(tokens[j]);
            auto found = prefixes.find(prefix);
            int node;
            if (found == prefixes.end()) {
              node = int(nodes.size());
              prefixes.emplace(prefix, node);
              nodes.push_back({tokens[j], j, {}});
            } else
              node = found->second;
            nodes[node].owners.push_back(i);
            if (j + 1 >= prefix_lengths[i])
              edges.push_back({node, i, tokens[j + 1]});
          }
        }
        llama_memory_clear(llama_get_memory(ctx), true);
        auto batch = llama_batch_init(int(nodes.size()), 0, count);
        for (const auto &node : nodes) {
          const int at = batch.n_tokens++;
          batch.token[at] = node.token;
          batch.pos[at] = node.pos;
          batch.n_seq_id[at] = int(node.owners.size());
          std::copy(node.owners.begin(), node.owners.end(), batch.seq_id[at]);
          batch.logits[at] = 1;
        }
        const int rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0)
          throw std::runtime_error("llama_decode failed");
        std::vector<double> scores(count, 0);
        std::vector<double> normalizers;
        for (int i = 0; i < int(nodes.size()); ++i) {
          const float *logits = llama_get_logits_ith(ctx, i);
          if (!logits)
            throw std::runtime_error("missing logits");
          const double max_value = *std::max_element(logits, logits + nv);
          double denom = 0;
          for (int j = 0; j < nv; ++j)
            denom += std::exp(double(logits[j]) - max_value);
          normalizers.push_back(max_value + std::log(denom));
        }
        for (const auto &edge : edges) {
          const float *logits = llama_get_logits_ith(ctx, edge.node);
          scores[edge.owner] +=
              double(logits[edge.next]) - normalizers[edge.node];
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
      std::cout << "ready " << nv << std::endl;
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
      std::string line;
      while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r')
          line.pop_back();
        if (line.empty()) {
          std::cout << "{}" << std::endl;
          continue;
        }
        std::string raw_keys, input_utf8, context_utf8;
        const size_t tab1 = line.find('\t');
        if (tab1 == std::string::npos) {
          const bool all_lower = std::all_of(
              line.begin(), line.end(), [](char c) { return c >= 'a' && c <= 'z'; });
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
            context_utf8 = line.substr(tab2 + 1);
          }
        }
        const auto input_hira =
            !input_utf8.empty() ? hiragana(decode_utf8(input_utf8))
                                : roman_decode(raw_keys);
        const auto candidates =
            assist_candidates(raw_keys, input_hira, dictionary, 24);
        if (candidates.size() <= 1) {
          std::cout << "{\"observed\":" << json_string(encode_utf8(input_hira))
                    << "}" << std::endl;
          continue;
        }
        std::u32string ctx_u32;
        if (!context_utf8.empty()) {
          ctx_u32 = decode_utf8(context_utf8);
          ctx_u32.erase(
              std::remove_if(ctx_u32.begin(), ctx_u32.end(),
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
        const auto pfx_tokens = tokenize(encode_utf8(prompt_prefix));
        std::vector<std::vector<llama_token>> sequences;
        std::vector<int> lengths(candidates.size(), int(pfx_tokens.size()));
        for (const auto &c : candidates) {
          sequences.push_back(tokenize(
              encode_utf8(prompt_prefix + katakana(c.reading) + U"\uee01")));
        }
        const auto scores = score_sequences(sequences, lengths);
        const auto &orig = candidates[0];
        const double baseline = orig.path.score + 8.0 * scores[0];
        const int orig_tokens = int(sequences[0].size());
        struct RankedCandidate {
          double stage1_delta;
          double final_score;
          size_t index;
        };
        std::vector<RankedCandidate> ranked;
        for (size_t i = 1; i < candidates.size(); ++i) {
          const int token_diff = int(sequences[i].size()) - orig_tokens;
          const double len_comp =
              (token_diff > 0 &&
               candidates[i].path.score > orig.path.score + 3.5)
                  ? 6.0 * std::min(token_diff, 2)
                  : 0.0;
          const double total = candidates[i].path.score + 8.0 * scores[i] +
                               len_comp - candidates[i].penalty * 3.0;
          const double delta = total - baseline;
          if (delta >= 34.0) {
            ranked.push_back({delta, delta, i});
          }
        }
        std::sort(ranked.begin(), ranked.end(),
                  [](const RankedCandidate &a, const RankedCandidate &b) {
                    return a.stage1_delta > b.stage1_delta;
                  });
        if (ranked.empty() || ranked[0].stage1_delta < 37.0) {
          std::cout << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                    << "}" << std::endl;
        } else {
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
              auto full_seq = tokenize(
                  encode_utf8(prompt_prefix + katakana(c.reading) + U"\uee01" +
                              decode_utf8(c.path.surface)));
              const int pfx_len = int(sequences[idx].size());
              if (int(full_seq.size()) > pfx_len) {
                surf_sequences.push_back(std::move(full_seq));
                surf_prefixes.push_back(pfx_len);
                valid_k.push_back(k);
              }
            }
            if (surf_sequences.size() >= 2) {
              const auto surf_scores =
                  score_sequences(surf_sequences, surf_prefixes);
              for (size_t j = 0; j < valid_k.size(); ++j) {
                const size_t k = valid_k[j];
                const int n_surf =
                    int(surf_sequences[j].size()) - surf_prefixes[j];
                const double surf_mean =
                    surf_scores[j] / std::max(1, n_surf);
                ranked[k].final_score =
                    ranked[k].stage1_delta + 5.0 * surf_mean;
              }
              std::sort(ranked.begin(), ranked.begin() + top_k,
                        [](const RankedCandidate &a, const RankedCandidate &b) {
                          return a.final_score > b.final_score;
                        });
            }
          }
          const size_t best = ranked[0].index;
          const double best_delta = std::max(37.0, ranked[0].stage1_delta);
          std::cout << "{\"observed\":" << json_string(encode_utf8(orig.reading))
                    << ",\"reading\":"
                    << json_string(encode_utf8(candidates[best].reading));
          if (top_k >= 2 && ranked[1].stage1_delta >= 35.0) {
            const size_t alt = ranked[1].index;
            std::cout << ",\"alt_reading\":"
                      << json_string(encode_utf8(candidates[alt].reading));
          }
          std::cout << ",\"gain\":" << std::setprecision(14) << best_delta << "}"
                    << std::endl;
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

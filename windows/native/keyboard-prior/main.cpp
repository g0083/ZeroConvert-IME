// Offline persistent likelihood probe for llama.cpp b4846 (10131b23).
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
  if (argc != 2 && !hybrid) {
    std::cerr
        << "usage: keyboard-prior [--hybrid] model.gguf [dictionary.bin]\n";
    return 2;
  }
  llama_backend_init();
  auto mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  auto *model = llama_model_load_from_file(argv[hybrid ? 2 : 1], mp);
  if (!model)
    return 3;
  auto cp = llama_context_default_params();
  cp.n_ctx = 4096;
  cp.n_batch = 4096;
  cp.n_ubatch = 512;
  cp.n_seq_max = 32;
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
        llama_kv_cache_clear(ctx);
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

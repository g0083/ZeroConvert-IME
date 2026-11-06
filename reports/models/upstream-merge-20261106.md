
## 追記: llama.cpp API対応 (2026-11-06)

### 結論
llama.cppランタイム（7d516e2）は更新不要。問題はSwiftコードが2025-05-20にリネームされた旧API名を使っていたこと。

### 修正内容（コミット e6c09f7）
| 旧API | 新API |
|-------|-------|
| `llama_kv_cache_seq_rm(ctx, ...)` | `llama_memory_seq_rm(llama_get_memory(ctx), ...)` |
| `llama_kv_cache_seq_cp(ctx, ...)` | `llama_memory_seq_cp(llama_get_memory(ctx), ...)` |
| `llama_kv_cache_seq_pos_max(ctx, ...)` | `llama_memory_seq_pos_max(llama_get_memory(ctx), ...)` |
| `modelParams.use_mmap = true` | `modelParams.load_mode = LLAMA_LOAD_MODE_MMAP` |
| `ctx_params.flash_attn = true` | `ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED` |

### バンドルヘッダー更新
`Sources/llama.cpp/` 内のヘッダーを llama.cpp 7d516e2 のものに更新（llama.h, ggml.h, ggml-cpu.h, ggml-backend.h, ggml-alloc.h, ggml-opt.h, gguf.h追加）

### ビルド結果
**Build complete! (91.68 secs)** — コンパイル・リンクともに成功

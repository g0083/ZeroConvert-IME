// Host API adaptation only; keep the Windows conversion algorithm unchanged.
#if Zenzai || ZenzaiCPU
import llama
extension llama_model_params {
    var use_mmap: Bool {
        get { load_mode != LLAMA_LOAD_MODE_NONE }
        set { load_mode = newValue ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE }
    }
}
#endif

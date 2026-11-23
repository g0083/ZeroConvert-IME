use crate::ai_assist::{
    self, benchmark, keyboard_hybrid::BenchmarkKeyboard, runtime, AiAssistConfig,
};
use serde_json::{json, Value};
use std::{
    cell::RefCell,
    ffi::{c_char, c_void, CStr, CString},
    io::{self, BufRead, Write},
    path::PathBuf,
    rc::Rc,
};

unsafe extern "C" {
    fn ZeroConOpenConversionBenchmark(
        model: *const c_char,
        dictionary: *const c_char,
        gpu_layers: i32,
    ) -> *mut c_void;
    fn ZeroConRunConversionBenchmark(
        session: *mut c_void,
        reading: *const c_char,
        context: *const c_char,
    ) -> *mut c_char;
    fn ZeroConCloseConversionBenchmark(session: *mut c_void);
    fn ZeroConConfigureRuntimeTuning(
        decode_threads: i32,
        batch_threads: i32,
        direct_model_first_mode: i32,
    );
    fn ZeroConProbeConversionTuning(
        session: *mut c_void,
        configs_json: *const c_char,
    ) -> *mut c_char;
}

struct Session(*mut c_void);

impl Session {
    fn close_cleanly(&self) {
        unsafe { ZeroConConfigureRuntimeTuning(-1, -1, -1) }
    }

    fn probe_tuning(&self, configs: &Value) -> Option<Vec<u8>> {
        let encoded = serde_json::to_string(configs).ok()?;
        let configs = CString::new(encoded).ok()?;
        let result = unsafe { ZeroConProbeConversionTuning(self.0, configs.as_ptr()) };
        if result.is_null() {
            return None;
        }
        let bytes = unsafe { CStr::from_ptr(result) }.to_bytes().to_vec();
        unsafe { super::FreeCString(result) };
        Some(bytes)
    }

    fn with_result<T>(
        &self,
        reading: &str,
        context: &str,
        read: impl FnOnce(&[u8]) -> T,
    ) -> Option<T> {
        let reading = CString::new(reading).ok()?;
        let context = CString::new(context).ok()?;
        let result =
            unsafe { ZeroConRunConversionBenchmark(self.0, reading.as_ptr(), context.as_ptr()) };
        if result.is_null() {
            return None;
        }
        let output = read(unsafe { CStr::from_ptr(result) }.to_bytes());
        unsafe { super::FreeCString(result) };
        Some(output)
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        unsafe { ZeroConCloseConversionBenchmark(self.0) }
    }
}

#[derive(Clone, Default)]
struct BenchmarkAssist {
    keyboard: Option<Rc<RefCell<BenchmarkKeyboard>>>,
}

impl runtime::CompletionBackend for BenchmarkAssist {
    fn read_learning(&self) -> bool {
        false
    }
    fn cache_results(&self) -> bool {
        false
    }
    fn allow_native_worker(&self) -> bool {
        self.keyboard.is_some()
    }
    fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
        None
    }
    fn keyboard_repair(
        &self,
        raw: &str,
        input: &str,
        context: &str,
        timeout_ms: u64,
    ) -> Option<Option<Value>> {
        self.keyboard.as_ref().map(|keyboard| {
            keyboard
                .borrow_mut()
                .repair(raw, input, context, timeout_ms)
                .ok()
                .flatten()
        })
    }
}

pub fn serve(typo: bool) -> Result<(), Box<dyn std::error::Error>> {
    let mut lines = io::stdin().lock().lines();
    let mut next_line = || -> io::Result<String> {
        lines
            .next()
            .transpose()?
            .ok_or(io::ErrorKind::UnexpectedEof.into())
    };
    let model = CString::new(next_line()?)?;
    let dictionary = CString::new(next_line()?)?;
    let gpu_layers: i32 = next_line()?.parse()?;
    let session =
        unsafe { ZeroConOpenConversionBenchmark(model.as_ptr(), dictionary.as_ptr(), gpu_layers) };
    if session.is_null() {
        return Err("candidate benchmark initialization failed".into());
    }
    let session = Session(session);
    let typo_options = if typo {
        Some(serde_json::from_str::<benchmark::WindowsTypoOptions>(
            &next_line()?,
        )?)
    } else {
        None
    };
    let mut assist = BenchmarkAssist::default();
    let reverse = PathBuf::from(dictionary.to_str()?).join("Reverse/reverse-v2.bin");
    let config = typo_options.map(|options| AiAssistConfig {
        enabled: options.enabled,
        engine: options.engine,
        ..Default::default()
    });
    if config
        .as_ref()
        .is_some_and(|config| config.enabled && config.engine == "embedded")
    {
        let runtime_dir =
            std::env::var_os("AZOOKEY_LLAMA_BACKEND_DIR").ok_or("missing benchmark runtime")?;
        let keyboard = BenchmarkKeyboard::open(
            PathBuf::from(model.to_str()?),
            runtime_dir.into(),
            &reverse,
            gpu_layers as u32,
        )
        .ok_or("タイポ補正ワーカーを初期化できませんでした。")?;
        assist.keyboard = Some(Rc::new(RefCell::new(keyboard)));
    }
    // Lock stdout only while writing. The converter may use it for diagnostics.
    println!("benchmark:ready");
    for line in lines {
        let line = line?;
        let mut output = io::stdout().lock();
        if line.trim_start().starts_with('{') {
            let parsed: Value = serde_json::from_str(&line)?;
            if let Some(op) = parsed.get("op").and_then(Value::as_str) {
                drop(output);
                let reply = match op {
                    "probe_tuning" => session
                        .probe_tuning(&parsed["configs"])
                        .unwrap_or_else(|| b"null".to_vec()),
                    "configure_tuning" => {
                        let decode = parsed["decodeThreads"].as_i64().unwrap_or(-1) as i32;
                        let batch = parsed["batchThreads"].as_i64().unwrap_or(-1) as i32;
                        let mf = match parsed["directModelFirst"].as_bool() {
                            Some(true) => 1,
                            Some(false) => 0,
                            None => -1,
                        };
                        unsafe { ZeroConConfigureRuntimeTuning(decode, batch, mf) };
                        br#"{"applied":true}"#.to_vec()
                    }
                    _ => br#"{"error":"unknown control op"}"#.to_vec(),
                };
                output = io::stdout().lock();
                output.write_all(b"benchmark:")?;
                output.write_all(&reply)?;
            } else if let Some(config) = &config {
                drop(output);
                let case = serde_json::from_value(parsed)?;
                let result = runtime::with_backend(Box::new(assist.clone()), || {
                    benchmark::run_typo_case(
                        &case,
                        &reverse,
                        |reading, context| {
                            session
                                .with_result(reading, context, |bytes| {
                                    serde_json::from_slice(bytes).ok()
                                })
                                .flatten()
                        },
                        |context, reading, baseline, raw| {
                            if !config.enabled {
                                return None;
                            }
                            ai_assist::corrector::suggest_windows_composition(
                                context, reading, baseline, raw, config,
                            )
                            .0
                        },
                    )
                })
                .unwrap_or_else(|error| json!({ "error": error }));
                output = io::stdout().lock();
                output.write_all(b"benchmark:")?;
                serde_json::to_writer(&mut output, &result)?;
            } else {
                drop(output);
                output = io::stdout().lock();
                output.write_all(br#"benchmark:{"error":"unknown control payload"}"#)?;
            }
        } else {
            drop(output);
            let (reading, context): (String, String) = serde_json::from_str(&line)?;
            let result = session.with_result(&reading, &context, |bytes| bytes.to_vec());
            output = io::stdout().lock();
            output.write_all(b"benchmark:")?;
            output.write_all(result.as_deref().unwrap_or(b"null"))?;
        }
        writeln!(output)?;
        output.flush()?;
    }
    session.close_cleanly();
    Ok(())
}

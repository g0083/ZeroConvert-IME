//! Host boundaries only. Conversion policy stays in the original modules.
use serde_json::Value;
use std::{cell::RefCell, path::PathBuf, sync::OnceLock};

pub const PREDICTION_SERVER_PORT: u16 = 18091;

pub struct Paths {
    pub settings: PathBuf,
    pub reverse_dictionary: PathBuf,
}

static PATHS: OnceLock<Paths> = OnceLock::new();
static SETTINGS_RESOLVER: OnceLock<fn() -> Option<PathBuf>> = OnceLock::new();

/// Desktop host supplies its existing configuration resolver at startup.
pub fn configure_settings_resolver(resolver: fn() -> Option<PathBuf>) {
    let _ = SETTINGS_RESOLVER.set(resolver);
}

/// Configure once, before loading dictionaries/learning or starting requests.
pub fn configure_paths(paths: Paths) -> Result<(), Paths> {
    PATHS.set(paths)
}

pub fn settings_path() -> Option<PathBuf> {
    if let Some(paths) = PATHS.get() {
        return Some(paths.settings.clone());
    }
    SETTINGS_RESOLVER.get().and_then(|resolver| resolver())
}

pub fn reverse_dictionary_path() -> Option<PathBuf> {
    if let Some(paths) = PATHS.get() {
        return Some(paths.reverse_dictionary.clone());
    }
    Some(
        std::env::current_exe()
            .ok()?
            .parent()?
            .join("Dictionary/Reverse/reverse-v2.bin"),
    )
}

pub trait CompletionBackend {
    fn revision(&self) -> u64 {
        0
    }
    fn read_learning(&self) -> bool {
        true
    }
    fn allow_native_worker(&self) -> bool {
        false
    }
    fn wait_for_native_worker(&self) -> bool {
        false
    }
    fn complete(&self, port: u16, path: &str, body: &Value, timeout_ms: u64) -> Option<Value>;
}

pub(super) fn read_learning() -> bool {
    BACKEND.with(|slot| {
        slot.borrow()
            .as_ref()
            .is_none_or(|backend| backend.read_learning())
    })
}

pub(super) fn allow_native_worker() -> bool {
    BACKEND.with(|slot| {
        slot.borrow()
            .as_ref()
            .is_none_or(|backend| backend.allow_native_worker())
    })
}

pub(super) fn wait_for_native_worker() -> bool {
    BACKEND.with(|slot| {
        slot.borrow()
            .as_ref()
            .is_some_and(|backend| backend.wait_for_native_worker())
    })
}

pub(super) fn backend_revision() -> u64 {
    BACKEND.with(|slot| {
        slot.borrow()
            .as_ref()
            .map_or(0, |backend| backend.revision())
    })
}

thread_local! {
    static BACKEND: RefCell<Option<Box<dyn CompletionBackend>>> = RefCell::new(None);
}

/// Each worker owns its inference session; a panic also restores the previous one.
pub fn with_backend<T>(backend: Box<dyn CompletionBackend>, operation: impl FnOnce() -> T) -> T {
    struct Restore(Option<Box<dyn CompletionBackend>>);
    impl Drop for Restore {
        fn drop(&mut self) {
            BACKEND.with(|slot| {
                slot.replace(self.0.take());
            });
        }
    }
    let _restore = Restore(BACKEND.with(|slot| slot.replace(Some(backend))));
    operation()
}

pub(super) fn complete_override(
    port: u16,
    path: &str,
    body: &Value,
    timeout_ms: u64,
) -> Option<Option<Value>> {
    BACKEND.with(|slot| {
        slot.borrow()
            .as_ref()
            .map(|backend| backend.complete(port, path, body, timeout_ms))
    })
}

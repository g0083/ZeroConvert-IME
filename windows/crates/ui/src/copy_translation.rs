use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CopyTranslationPhase {
    Hidden,
    Toolbar,
    Loading,
    Translating,
    Result,
    Error,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CopySource {
    Editable,
    ReadOnlyDocument,
    Unknown,
}

impl CopySource {
    pub fn should_show(self) -> bool {
        matches!(self, CopySource::ReadOnlyDocument)
    }
}

pub fn count_text(text: &str) -> (usize, bool) {
    // Mirror selection_count: bounded UTF-16 units, CRLF counts as one newline.
    const MAX: usize = 65_536;
    let units: Vec<u16> = text.encode_utf16().collect();
    let truncated = units.len() > MAX;
    let mut end = units.len().min(MAX);
    if truncated && end > 0 && (0xd800..=0xdbff).contains(&units[end - 1]) {
        end -= 1;
    }
    let mut characters = 0usize;
    let mut previous_cr = false;
    for ch in char::decode_utf16(units[..end].iter().copied()) {
        let Ok(ch) = ch else { continue };
        if ch != '\n' || !previous_cr {
            characters += 1;
        }
        previous_cr = ch == '\r';
    }
    (characters, truncated)
}

pub const TRANSLATION_CHAR_LIMIT: usize = 512;

#[derive(Debug)]
pub struct CopyTranslationState {
    pub phase: CopyTranslationPhase,
    pub generation: usize,
    pub source: String,
    pub count: usize,
    pub truncated: bool,
    pub output: String,
    pub enabled: bool,
    gen_counter: AtomicUsize,
}

impl Default for CopyTranslationState {
    fn default() -> Self {
        Self::new()
    }
}

impl CopyTranslationState {
    pub fn new() -> Self {
        Self {
            phase: CopyTranslationPhase::Hidden,
            generation: 0,
            source: String::new(),
            count: 0,
            truncated: false,
            output: String::new(),
            enabled: true,
            gen_counter: AtomicUsize::new(0),
        }
    }

    fn next_generation(&mut self) -> usize {
        let g = self.gen_counter.fetch_add(1, Ordering::AcqRel) + 1;
        self.generation = g;
        g
    }

    pub fn on_copy(&mut self, text: String, source: CopySource) -> bool {
        if !self.enabled || !source.should_show() {
            // close any existing
            if self.phase != CopyTranslationPhase::Hidden {
                self.phase = CopyTranslationPhase::Hidden;
            }
            return false;
        }
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return false;
        }
        let (count, truncated) = count_text(&text);
        if count == 0 {
            return false;
        }
        // new generation, discard old result
        self.next_generation();
        self.source = text;
        self.count = count;
        self.truncated = truncated;
        self.output.clear();
        self.phase = CopyTranslationPhase::Toolbar;
        true
    }

    pub fn start_translate(&mut self) -> Option<(usize, String)> {
        if !self.enabled || self.phase != CopyTranslationPhase::Toolbar {
            return None;
        }
        if self.truncated || self.count > TRANSLATION_CHAR_LIMIT {
            self.phase = CopyTranslationPhase::Error;
            self.output = "翻訳は512文字までです。".into();
            return None;
        }
        if self.source.contains("<|") {
            self.phase = CopyTranslationPhase::Error;
            self.output = "翻訳できませんでした。".into();
            return None;
        }
        self.phase = CopyTranslationPhase::Loading;
        Some((self.generation, self.source.clone()))
    }

    pub fn on_translation_update(
        &mut self,
        generation: usize,
        phase: CopyTranslationPhase,
        text: String,
    ) {
        if generation != self.generation {
            return;
        }
        if matches!(self.phase, CopyTranslationPhase::Hidden) {
            return;
        }
        self.phase = phase;
        if !text.is_empty() {
            self.output = text;
        }
    }

    pub fn hide(&mut self) {
        if self.phase != CopyTranslationPhase::Hidden {
            self.next_generation();
            self.phase = CopyTranslationPhase::Hidden;
            self.source.clear();
            self.output.clear();
        }
    }

    pub fn is_visible(&self) -> bool {
        self.phase != CopyTranslationPhase::Hidden
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn count_crlf_and_emoji() {
        let (c, t) = count_text("a\r\nb😀");
        assert_eq!(c, 4);
        assert!(!t);
    }

    #[test]
    fn truncated_flag() {
        let big = "a".repeat(70_000);
        let (c, t) = count_text(&big);
        assert!(t);
        assert!(c <= 65_536);
    }

    #[test]
    fn state_on_copy_show_for_readonly() {
        let mut s = CopyTranslationState::new();
        assert!(s.on_copy("hello".into(), CopySource::ReadOnlyDocument));
        assert!(s.is_visible());
        assert_eq!(s.phase, CopyTranslationPhase::Toolbar);
        assert_eq!(s.count, 5);
    }

    #[test]
    fn editable_does_not_show() {
        let mut s = CopyTranslationState::new();
        assert!(!s.on_copy("hello".into(), CopySource::Editable));
        assert!(!s.is_visible());
    }

    #[test]
    fn unknown_does_not_show() {
        let mut s = CopyTranslationState::new();
        assert!(!s.on_copy("hello".into(), CopySource::Unknown));
    }

    #[test]
    fn new_copy_discards_old_result() {
        let mut s = CopyTranslationState::new();
        s.on_copy("a".into(), CopySource::ReadOnlyDocument);
        s.start_translate();
        let g1 = s.generation;
        s.on_copy("b".into(), CopySource::ReadOnlyDocument);
        assert!(s.generation > g1);
        assert_eq!(s.phase, CopyTranslationPhase::Toolbar);
        // old update ignored
        s.on_translation_update(g1, CopyTranslationPhase::Result, "old".into());
        assert_ne!(s.output, "old");
    }

    #[test]
    fn hide_ignores_late_update() {
        let mut s = CopyTranslationState::new();
        s.on_copy("a".into(), CopySource::ReadOnlyDocument);
        s.start_translate();
        let g = s.generation;
        s.hide();
        s.on_translation_update(g, CopyTranslationPhase::Result, "late".into());
        assert_eq!(s.phase, CopyTranslationPhase::Hidden);
    }

    #[test]
    fn translate_over_limit_sets_error() {
        let mut s = CopyTranslationState::new();
        let long = "a".repeat(513);
        s.on_copy(long, CopySource::ReadOnlyDocument);
        assert!(s.start_translate().is_none());
        assert_eq!(s.phase, CopyTranslationPhase::Error);
    }
}

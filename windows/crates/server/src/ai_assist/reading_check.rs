//! Read-only AZR2 dictionary validation. Checks an existing reading, never generates one.
use super::{katakana_to_hiragana, to_katakana};
use std::sync::OnceLock;

struct ReverseDictionary {
    bytes: Vec<u8>,
    count: usize,
    header_end: usize,
}
impl ReverseDictionary {
    fn u32(bytes: &[u8], offset: usize) -> Option<usize> {
        Some(
            u32::from_le_bytes(bytes.get(offset..offset.checked_add(4)?)?.try_into().ok()?)
                as usize,
        )
    }
    fn from_bytes(bytes: Vec<u8>) -> Option<Self> {
        if bytes.get(..4)? != b"AZR2" {
            return None;
        }
        let count = Self::u32(&bytes, 4)?;
        let header_end = 8usize.checked_add(count.checked_add(1)?.checked_mul(4)?)?;
        if count == 0
            || header_end > bytes.len()
            || Self::u32(&bytes, 8)? != header_end
            || Self::u32(&bytes, header_end - 4)? != bytes.len()
        {
            return None;
        }
        Some(Self {
            bytes,
            count,
            header_end,
        })
    }
    fn record(&self, index: usize) -> Option<(&str, &str, f32)> {
        if index >= self.count {
            return None;
        }
        let start = Self::u32(&self.bytes, 8 + index * 4)?;
        let end = Self::u32(&self.bytes, 12 + index * 4)?;
        if start < self.header_end || end < start.checked_add(8)? || end > self.bytes.len() {
            return None;
        }
        let surface_len =
            u16::from_le_bytes(self.bytes.get(start..start + 2)?.try_into().ok()?) as usize;
        let reading_len =
            u16::from_le_bytes(self.bytes.get(start + 2..start + 4)?.try_into().ok()?) as usize;
        let score = f32::from_le_bytes(self.bytes.get(start + 4..start + 8)?.try_into().ok()?);
        let middle = start.checked_add(8)?.checked_add(surface_len)?;
        if middle.checked_add(reading_len)? != end {
            return None;
        }
        Some((
            std::str::from_utf8(self.bytes.get(start + 8..middle)?).ok()?,
            std::str::from_utf8(self.bytes.get(middle..end)?).ok()?,
            score,
        ))
    }
    fn readings_with_scores<'a>(&'a self, surface: &str) -> Vec<(&'a str, f32)> {
        let (mut low, mut high) = (0, self.count);
        while low < high {
            let mid = low + (high - low) / 2;
            let Some((word, _, _)) = self.record(mid) else {
                return Vec::new();
            };
            if word.as_bytes() < surface.as_bytes() {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        (low..self.count)
            .take(128)
            .map_while(|i| self.record(i))
            .take_while(|(word, _, _)| *word == surface)
            .map(|(_, reading, score)| (reading, score))
            .collect()
    }
    fn matches(&self, surface: &str, reading: &str) -> bool {
        let normalized = to_katakana(reading);
        let mut offsets: Vec<_> = surface.char_indices().map(|(i, _)| i).collect();
        offsets.push(surface.len());
        if offsets.len() > 129 || normalized.chars().count() > 128 {
            return false;
        }
        // Single characters inside a known multi-character compound must not
        // fall back to -30.0 rare/name readings (e.g. 連->レ in 連絡).
        let mut in_compound = vec![false; offsets.len() - 1];
        for start in 0..offsets.len() - 1 {
            for end in start + 2..(start + 41).min(offsets.len()) {
                let word = &surface[offsets[start]..offsets[end]];
                if !self.readings_with_scores(word).is_empty() {
                    for pos in start..end {
                        in_compound[pos] = true;
                    }
                }
            }
        }
        let mut paths = vec![Vec::<usize>::new(); offsets.len()];
        paths[0].push(0);
        for start in 0..offsets.len() - 1 {
            if paths[start].is_empty() {
                continue;
            }
            for end in start + 1..(start + 41).min(offsets.len()) {
                let word = &surface[offsets[start]..offsets[end]];
                let scored = self.readings_with_scores(word);
                let has_standard = scored.iter().any(|&(_, s)| s > -25.0);
                let mut choices: Vec<&str> = scored
                    .into_iter()
                    .filter(|&(_, s)| {
                        if end == start + 1 && in_compound[start] {
                            s > -25.0
                        } else if has_standard {
                            s > -28.0
                        } else {
                            true
                        }
                    })
                    .map(|(r, _)| r)
                    .collect();
                let literal = to_katakana(word);
                // A kana/Latin literal can pass through, a kanji literal cannot.
                if end == start + 1
                    && katakana_to_hiragana(word).chars().all(|c| {
                        ('ぁ'..='ん').contains(&c) || c.is_ascii() || "ー、。！？!?・".contains(c)
                    })
                {
                    choices.push(&literal);
                }
                let previous = paths[start].clone();
                for consumed in previous {
                    for candidate in &choices {
                        if normalized[consumed..].starts_with(candidate) {
                            let next = consumed + candidate.len();
                            if !paths[end].contains(&next) {
                                paths[end].push(next);
                            }
                        }
                    }
                }
            }
        }
        paths.last().is_some_and(|p| p.contains(&normalized.len()))
    }
}

fn dictionary() -> Option<&'static ReverseDictionary> {
    static DICTIONARY: OnceLock<Option<ReverseDictionary>> = OnceLock::new();
    DICTIONARY
        .get_or_init(|| {
            let path = super::runtime::reverse_dictionary_path()?;
            ReverseDictionary::from_bytes(std::fs::read(path).ok()?)
        })
        .as_ref()
}

pub fn has_same_reading(surface: &str, reading: &str) -> bool {
    dictionary().is_some_and(|d| d.matches(surface, reading))
}

// Direct-input correction changes the canonical input to a surface. Reconstruct
// a bounded reading for prediction only; never use it to mutate composition.
pub fn reading_for_surface(surface: &str) -> Option<String> {
    dictionary()?.reading_for_surface(surface)
}

pub fn min_reading_distance(surface: &str, source_reading: &str) -> Option<usize> {
    if let Some(d) = dictionary() {
        return d.min_reading_distance(surface, source_reading);
    }
    // When running in an unconfigured unit test process without reverse-v2.bin,
    // reconstruct known vocabulary segments so supported_reading_change works.
    let mut reading = String::new();
    let mut rest = surface;
    while !rest.is_empty() {
        let mut matched = None;
        for &(v_reading, v_surface) in super::STANDARD_VOCAB {
            if rest.starts_with(v_surface)
                && matched.is_none_or(|(_, best_len)| v_surface.len() > best_len)
            {
                matched = Some((v_reading, v_surface.len()));
            }
        }
        if let Some((v_reading, len)) = matched {
            reading.push_str(v_reading);
            rest = &rest[len..];
        } else {
            let ch = rest.chars().next()?;
            let s = &rest[..ch.len_utf8()];
            if ('ぁ'..='ゖ').contains(&ch)
                || ('ァ'..='ヶ').contains(&ch)
                || "ー、。！？!?・".contains(ch)
            {
                reading.push_str(&katakana_to_hiragana(s));
                rest = &rest[ch.len_utf8()..];
            } else {
                return None;
            }
        }
    }
    Some(super::damerau_levenshtein_distance(
        &katakana_to_hiragana(source_reading),
        &reading,
    ))
}

impl ReverseDictionary {
    fn top_readings_for_surface(&self, surface: &str, beam: usize) -> Option<Vec<(f32, String)>> {
        let mut offsets: Vec<_> = surface.char_indices().map(|(i, _)| i).collect();
        offsets.push(surface.len());
        if offsets.len() > 129 {
            return None;
        }
        let mut paths: Vec<Vec<(f32, String)>> = vec![Vec::new(); offsets.len()];
        paths[0].push((0.0, String::new()));
        for start in 0..offsets.len() - 1 {
            if paths[start].is_empty() {
                continue;
            }
            paths[start].sort_by(|a, b| b.0.total_cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
            let mut dedup = Vec::with_capacity(beam);
            for (score, text) in paths[start].drain(..) {
                if !dedup.iter().any(|(_, existing)| existing == &text) {
                    dedup.push((score, text));
                    if dedup.len() == beam {
                        break;
                    }
                }
            }
            paths[start] = dedup;
            let current = paths[start].clone();
            for end in start + 1..(start + 41).min(offsets.len()) {
                let word = &surface[offsets[start]..offsets[end]];
                let scored = self.readings_with_scores(word);
                let mut word_choices: Vec<(f32, String)> = Vec::new();
                if !scored.is_empty() {
                    let best_score = scored[0].1;
                    let span_bonus = 6.0 * ((end - start).saturating_sub(1) as f32);
                    for (reading, score) in scored.into_iter().take(4) {
                        if score > -25.0 || score >= best_score {
                            word_choices.push((score + span_bonus, reading.to_owned()));
                        }
                    }
                } else if end == start + 1
                    && word.chars().all(|c| {
                        ('ぁ'..='ゖ').contains(&c)
                            || ('ァ'..='ヶ').contains(&c)
                            || "ー、。！？!?・".contains(c)
                    })
                {
                    word_choices.push((-4.0, to_katakana(word)));
                }
                for (w_score, w_reading) in &word_choices {
                    for (prev_score, prev_text) in &current {
                        paths[end].push((prev_score + w_score, format!("{prev_text}{w_reading}")));
                    }
                }
            }
        }
        let mut last = paths.pop()?;
        if last.is_empty() {
            return None;
        }
        last.sort_by(|a, b| b.0.total_cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        let mut dedup = Vec::with_capacity(beam);
        for (score, text) in last {
            if !dedup.iter().any(|(_, existing)| existing == &text) {
                dedup.push((score, text));
                if dedup.len() == beam {
                    break;
                }
            }
        }
        Some(dedup)
    }

    fn reading_for_surface(&self, surface: &str) -> Option<String> {
        self.top_readings_for_surface(surface, 4)?
            .into_iter()
            .next()
            .map(|(_, r)| r)
    }

    fn min_reading_distance(&self, surface: &str, source_reading: &str) -> Option<usize> {
        let candidates = self.top_readings_for_surface(surface, 6)?;
        let source_hira = katakana_to_hiragana(source_reading);
        candidates
            .into_iter()
            .map(|(_, reading)| {
                super::damerau_levenshtein_distance(&source_hira, &katakana_to_hiragana(&reading))
            })
            .min()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn dictionary() -> ReverseDictionary {
        let mut entries = vec![
            ("橋", "ハシ"),
            ("箸", "ハシ"),
            ("帰り", "カエリ"),
            ("世話", "セワ"),
        ];
        entries.sort_by(|a, b| a.0.as_bytes().cmp(b.0.as_bytes()));
        let header = 8 + (entries.len() + 1) * 4;
        let mut bytes = b"AZR2".to_vec();
        bytes.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        let mut records = Vec::new();
        for (surface, reading) in entries {
            bytes.extend_from_slice(&((header + records.len()) as u32).to_le_bytes());
            records.extend_from_slice(&(surface.len() as u16).to_le_bytes());
            records.extend_from_slice(&(reading.len() as u16).to_le_bytes());
            records.extend_from_slice(&0f32.to_le_bytes());
            records.extend_from_slice(surface.as_bytes());
            records.extend_from_slice(reading.as_bytes());
        }
        bytes.extend_from_slice(&((header + records.len()) as u32).to_le_bytes());
        bytes.extend(records);
        ReverseDictionary::from_bytes(bytes).unwrap()
    }
    #[test]
    fn script_or_homophone_changes_are_conversion_while_reading_changes_are_correction() {
        let d = dictionary();
        assert!(d.matches("お帰り", "おかえり"));
        assert_eq!(d.reading_for_surface("お帰り"), Some("オカエリ".into()));
        assert_eq!(
            d.reading_for_surface("お世話になっております"),
            Some("オセワニナッテオリマス".into())
        );
        assert!(d.reading_for_surface("未知の固有名詞").is_none());
        assert!(d.matches("橋", "はし"));
        assert!(d.matches("箸", "はし"));
        assert!(d.matches("お世話になっております", "おせわになっております"));
        assert!(!d.matches("お世話になっております", "おせわになております"));
        assert!(!d.matches("お帰り", "おkせり"));
        assert!(ReverseDictionary::from_bytes(b"AZR2\xff\xff\xff\xff".to_vec()).is_none());
    }

    #[test]
    fn compounds_are_not_overwritten_by_overlapping_single_kanji_or_rare_readings() {
        let mut entries = vec![
            ("担", "ユタカ", -10.0f32),
            ("担当", "タントウ", -9.0f32),
            ("当に", "マサニ", -8.0f32),
            ("代わります", "カワリマス", -8.0f32),
            ("連絡", "レンラク", -8.0f32),
            ("連", "レン", -10.0f32),
            ("連", "レ", -30.0f32),
            ("絡", "ラク", -12.0f32),
            ("腹", "フク", -9.0f32),
            ("腹", "ハラ", -10.0f32),
            ("立つ", "タツ", -8.0f32),
            ("お腹", "オナカ", -8.0f32),
        ];
        entries.sort_by(|a, b| a.0.as_bytes().cmp(b.0.as_bytes()));
        let header = 8 + (entries.len() + 1) * 4;
        let mut bytes = b"AZR2".to_vec();
        bytes.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        let mut records = Vec::new();
        for (surface, reading, score) in entries {
            bytes.extend_from_slice(&((header + records.len()) as u32).to_le_bytes());
            records.extend_from_slice(&(surface.len() as u16).to_le_bytes());
            records.extend_from_slice(&(reading.len() as u16).to_le_bytes());
            records.extend_from_slice(&score.to_le_bytes());
            records.extend_from_slice(surface.as_bytes());
            records.extend_from_slice(reading.as_bytes());
        }
        bytes.extend_from_slice(&((header + records.len()) as u32).to_le_bytes());
        bytes.extend(records);
        let d = ReverseDictionary::from_bytes(bytes).unwrap();
        assert_eq!(
            d.reading_for_surface("担当に代わります"),
            Some("タントウニカワリマス".into())
        );
        assert!(d.matches("ご連絡まで", "ごれんらくまで"));
        assert!(!d.matches("ご連絡まで", "ごれらくまで"));
        assert_eq!(d.min_reading_distance("腹が立つ", "はらたがつ"), Some(1));
        assert_eq!(d.min_reading_distance("お腹", "おなか"), Some(0));
    }
}

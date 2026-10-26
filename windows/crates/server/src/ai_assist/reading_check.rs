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
    fn record(&self, index: usize) -> Option<(&str, &str)> {
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
        let middle = start.checked_add(8)?.checked_add(surface_len)?;
        if middle.checked_add(reading_len)? != end {
            return None;
        }
        Some((
            std::str::from_utf8(self.bytes.get(start + 8..middle)?).ok()?,
            std::str::from_utf8(self.bytes.get(middle..end)?).ok()?,
        ))
    }
    fn readings<'a>(&'a self, surface: &str) -> Vec<&'a str> {
        let (mut low, mut high) = (0, self.count);
        while low < high {
            let mid = low + (high - low) / 2;
            let Some((word, _)) = self.record(mid) else {
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
            .take_while(|(word, _)| *word == surface)
            .map(|(_, reading)| reading)
            .collect()
    }
    fn matches(&self, surface: &str, reading: &str) -> bool {
        let normalized = to_katakana(reading);
        let mut offsets: Vec<_> = surface.char_indices().map(|(i, _)| i).collect();
        offsets.push(surface.len());
        if offsets.len() > 129 || normalized.chars().count() > 128 {
            return false;
        }
        let mut paths = vec![Vec::<usize>::new(); offsets.len()];
        paths[0].push(0);
        for start in 0..offsets.len() - 1 {
            if paths[start].is_empty() {
                continue;
            }
            for end in start + 1..(start + 41).min(offsets.len()) {
                let word = &surface[offsets[start]..offsets[end]];
                let mut choices = self.readings(word);
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

impl ReverseDictionary {
    fn reading_for_surface(&self, surface: &str) -> Option<String> {
        let mut offsets: Vec<_> = surface.char_indices().map(|(i, _)| i).collect();
        offsets.push(surface.len());
        if offsets.len() > 129 {
            return None;
        }
        let mut paths: Vec<Option<String>> = vec![None; offsets.len()];
        paths[0] = Some(String::new());
        for start in 0..offsets.len() - 1 {
            let Some(prefix) = paths[start].clone() else {
                continue;
            };
            for end in (start + 1..(start + 41).min(offsets.len())).rev() {
                if paths[end].is_some() {
                    continue;
                }
                let word = &surface[offsets[start]..offsets[end]];
                let reading = self
                    .readings(word)
                    .first()
                    .copied()
                    .map(str::to_owned)
                    .or_else(|| {
                        (end == start + 1
                            && word.chars().all(|c| {
                                ('ぁ'..='ゖ').contains(&c)
                                    || ('ァ'..='ヶ').contains(&c)
                                    || "ー、。！？!?・".contains(c)
                            }))
                        .then(|| to_katakana(word))
                    });
                if let Some(reading) = reading {
                    paths[end] = Some(format!("{prefix}{reading}"));
                }
            }
        }
        paths.pop().flatten()
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
}

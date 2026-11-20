//! English completion dictionary that is acquired at runtime instead of bundled.
//!
//! The source is wordfreq 3.1.1 `large_en.msgpack.gz` (frequency data: CC BY-SA
//! 4.0, code: Apache-2.0), fetched from the upstream repository and filtered on
//! the device. Nothing here downloads: the caller fetches the pinned file with
//! HTTPS, then hands the bytes to [`import`], which writes a small ranked cache.
//!
//! The cache is a newline-separated ranked list with a short `#` header, so rank
//! is the line order. Both Windows and Android read the same cache.

use std::path::{Path, PathBuf};

/// Pinned upstream revision that last changed the English data file.
pub const SOURCE_COMMIT: &str = "ec48c0a1237f271b343fa4e38fb896c8da587804";
pub const SOURCE_URL: &str = concat!(
    "https://raw.githubusercontent.com/rspeer/wordfreq/",
    "ec48c0a1237f271b343fa4e38fb896c8da587804",
    "/wordfreq/data/large_en.msgpack.gz"
);
/// SHA-256 of the pinned file, identical to the 3.1.1 release wheel.
pub const SOURCE_SHA256: &str = "dffae8066b78dce0a6667cf5f58e567054f902674667090a7ac8a8a44628b05c";
pub const SOURCE_BYTES: u64 = 1_494_836;
pub const SOURCE_LICENSE: &str =
    "CC BY-SA 4.0 (wordfreq data, https://creativecommons.org/licenses/by-sa/4.0/)";
pub const SOURCE_ATTRIBUTION: &str =
    "Frequency data from wordfreq 3.1.1 by Robyn Speer (Luminoso), filtered and re-ranked on device";

pub const CACHE_FILENAME: &str = "english-words.txt";
pub const CACHE_HEADER: &str = "# zerocon-english-dictionary v1";
/// Words kept from the upstream list. 30,000 keeps the useful tail without
/// letting rare web noise reach the 8-slot candidate window.
pub const DEFAULT_WORD_LIMIT: usize = 30_000;
pub const MIN_WORD_LEN: usize = 2;
pub const MAX_WORD_LEN: usize = 24;

/// Contraction stems produced by upstream tokenization (`don't` -> `don` + `t`).
/// They are not words a completer should offer. `cannot` and `won` are real words
/// and stay.
const CONTRACTION_STEMS: &[&str] = &[
    "ain", "aren", "couldn", "didn", "doesn", "don", "hadn", "hasn", "haven", "isn", "shouldn",
    "wasn", "weren", "wouldn",
];

/// Explicit terms dropped so the completion list stays swear-free, matching the
/// previous bundled list. Project-owned list, no third-party license.
const DENIED_WORDS: &[&str] = &[
    "anal",
    "analsex",
    "anus",
    "arse",
    "asshole",
    "assholes",
    "bastard",
    "bastards",
    "bitch",
    "bitches",
    "blowjob",
    "blowjobs",
    "bollocks",
    "boobs",
    "bukkake",
    "busty",
    "clit",
    "clitoris",
    "cock",
    "cocks",
    "cocksucker",
    "coon",
    "cum",
    "cums",
    "cumshot",
    "cunt",
    "cunts",
    "dick",
    "dickhead",
    "dicks",
    "dildo",
    "dildos",
    "douchebag",
    "dyke",
    "ejaculate",
    "ejaculation",
    "erotic",
    "fag",
    "faggot",
    "faggots",
    "fuck",
    "fucked",
    "fucker",
    "fuckers",
    "fucking",
    "fucks",
    "gaysex",
    "handjob",
    "hentai",
    "hooker",
    "incest",
    "jackass",
    "jerkoff",
    "jizz",
    "kike",
    "labia",
    "masturbate",
    "masturbation",
    "milf",
    "nigger",
    "niggers",
    "nipple",
    "nipples",
    "orgasm",
    "orgasms",
    "orgy",
    "paedophile",
    "pedophile",
    "penis",
    "piss",
    "porn",
    "porno",
    "pornography",
    "porns",
    "prick",
    "pussies",
    "pussy",
    "rape",
    "raped",
    "raping",
    "rapist",
    "retard",
    "retarded",
    "rimjob",
    "scrotum",
    "semen",
    "sex",
    "sexcam",
    "sexo",
    "sexual",
    "sexy",
    "shit",
    "shits",
    "shitted",
    "shitty",
    "slut",
    "sluts",
    "sperm",
    "spic",
    "squirt",
    "squirting",
    "testicle",
    "testicles",
    "tit",
    "tits",
    "titties",
    "tranny",
    "turd",
    "vagina",
    "vaginal",
    "vibrator",
    "wank",
    "wanker",
    "whore",
    "whores",
    "xxx",
];

/// Words the upstream corpus ranks too low (or predates) but that ZeroCon users
/// type often. Project-owned list, no third-party license. They are appended
/// after the ranked body, so they only win a prefix when they are specific
/// enough to be the only match (`kub`, `chatg`, `ll`).
const SUPPLEMENT_WORDS: &[&str] = &[
    "ai",
    "api",
    "apis",
    "app",
    "apps",
    "async",
    "await",
    "aws",
    "azure",
    "backend",
    "bitcoin",
    "blog",
    "boolean",
    "browser",
    "bug",
    "bugfix",
    "cache",
    "callback",
    "chatgpt",
    "cli",
    "clone",
    "cloud",
    "code",
    "coder",
    "commit",
    "commits",
    "compile",
    "compiler",
    "config",
    "container",
    "covid",
    "cpu",
    "crypto",
    "css",
    "dashboard",
    "data",
    "database",
    "dataset",
    "debug",
    "deploy",
    "deployment",
    "dev",
    "devops",
    "discord",
    "docker",
    "domain",
    "download",
    "emoji",
    "emojis",
    "endpoint",
    "enum",
    "env",
    "excel",
    "figma",
    "file",
    "files",
    "finetune",
    "firewall",
    "frontend",
    "gcp",
    "gif",
    "github",
    "gitlab",
    "gmail",
    "gpu",
    "gpus",
    "gradient",
    "hash",
    "hashmap",
    "host",
    "hostname",
    "html",
    "http",
    "https",
    "image",
    "images",
    "index",
    "inference",
    "instagram",
    "ios",
    "ipad",
    "iphone",
    "json",
    "jupyter",
    "kernel",
    "kotlin",
    "kubernetes",
    "lambda",
    "latency",
    "lint",
    "linux",
    "llm",
    "llms",
    "login",
    "logout",
    "laptop",
    "laptops",
    "macos",
    "markdown",
    "mastodon",
    "meme",
    "memes",
    "merge",
    "metadata",
    "microservice",
    "ml",
    "mobile",
    "modal",
    "model",
    "models",
    "mutex",
    "netflix",
    "node",
    "npm",
    "npu",
    "numpy",
    "oauth",
    "onboarding",
    "openai",
    "opensource",
    "pandas",
    "password",
    "path",
    "payload",
    "pdf",
    "pipeline",
    "plugin",
    "podcast",
    "postgres",
    "prompt",
    "prompts",
    "python",
    "pytorch",
    "query",
    "react",
    "readme",
    "refactor",
    "regex",
    "repo",
    "repository",
    "runtime",
    "rust",
    "sandbox",
    "screenshot",
    "sdk",
    "server",
    "serverless",
    "shell",
    "slack",
    "smartphone",
    "spotify",
    "sql",
    "ssh",
    "startup",
    "string",
    "struct",
    "sushi",
    "swift",
    "tablet",
    "tensor",
    "tensorflow",
    "terminal",
    "thread",
    "tiktok",
    "token",
    "tokenizer",
    "tsv",
    "tweet",
    "tweets",
    "typescript",
    "ubuntu",
    "ui",
    "unicode",
    "upload",
    "url",
    "usb",
    "ux",
    "variable",
    "vector",
    "vegan",
    "vpn",
    "vscode",
    "webhook",
    "webpack",
    "websocket",
    "wifi",
    "windows",
    "workflow",
    "workspace",
    "xml",
    "yaml",
    "youtube",
    "zoom",
];

fn is_denied(word: &str) -> bool {
    CONTRACTION_STEMS.binary_search(&word).is_ok() || DENIED_WORDS.binary_search(&word).is_ok()
}

/// A loaded dictionary. Words keep upstream rank order; `alphabetical` maps
/// byte order back to rank indices so prefix lookups stay O(log n + matches).
pub struct Catalog {
    words: Vec<String>,
    alphabetical: Vec<u32>,
}

impl Catalog {
    pub fn from_ranked_words(words: Vec<String>) -> Self {
        let mut alphabetical: Vec<u32> = (0..words.len() as u32).collect();
        alphabetical.sort_unstable_by(|left, right| {
            words[*left as usize]
                .as_str()
                .cmp(words[*right as usize].as_str())
        });
        Self {
            words,
            alphabetical,
        }
    }

    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    pub fn words(&self) -> &[String] {
        &self.words
    }

    /// Dictionary membership check used by the commit-time learning rule.
    pub fn contains(&self, lower: &str) -> bool {
        let start = self
            .alphabetical
            .partition_point(|index| self.words[*index as usize].as_str() < lower);
        self.alphabetical[start..]
            .first()
            .is_some_and(|index| self.words[*index as usize] == lower)
    }

    /// Best `limit` completions for `lower`, ordered by upstream frequency rank.
    pub fn prefix_best(&self, lower: &str, limit: usize) -> Vec<&str> {
        if limit == 0 || lower.is_empty() {
            return Vec::new();
        }
        let start = self
            .alphabetical
            .partition_point(|index| self.words[*index as usize].as_str() < lower);
        let mut best: Vec<(u32, &str)> = Vec::with_capacity(limit + 8);
        for index in &self.alphabetical[start..] {
            let word = self.words[*index as usize].as_str();
            if !word.starts_with(lower) {
                break;
            }
            if word.len() == lower.len() {
                continue;
            }
            let position = best.partition_point(|(rank, _)| *rank < *index);
            best.insert(position, (*index, word));
            best.truncate(limit + 8);
        }
        best.into_iter().take(limit).map(|(_, word)| word).collect()
    }
}

/// Reads a cached dictionary. Returns `None` when the file is missing or is not
/// one of our caches, so callers can fall back to "no static dictionary".
pub fn load(path: &Path) -> Option<Catalog> {
    let text = std::fs::read_to_string(path).ok()?;
    let mut words = Vec::new();
    let mut header_seen = false;
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if line.starts_with('#') {
            if line == CACHE_HEADER {
                header_seen = true;
            }
            continue;
        }
        if line.is_empty() {
            continue;
        }
        words.push(line.to_owned());
    }
    if !header_seen || words.is_empty() {
        return None;
    }
    Some(Catalog::from_ranked_words(words))
}

/// Parsed upstream buckets. Each element is one frequency bin; the bin index is
/// the negative frequency in centibels, so earlier bins are more frequent.
pub fn parse_buckets(bytes: &[u8]) -> Result<Vec<Vec<String>>, String> {
    let mut reader = Reader { bytes, position: 0 };
    // The file is one array whose first element is the header map, followed by
    // one entry per frequency bin.
    let Value::Array(mut items) = reader.value()? else {
        return Err("unexpected wordfreq body".to_owned());
    };
    if items.is_empty() {
        return Err("empty wordfreq data".to_owned());
    }
    let header = items.remove(0);
    let Value::Map(pairs) = header else {
        return Err("unexpected wordfreq header".to_owned());
    };
    let is_cb = pairs.iter().any(|(key, value)| {
        matches!((key, value), (Value::Str(k), Value::Str(v)) if k == "format" && v == "cB")
    });
    if !is_cb {
        return Err("unsupported wordfreq format".to_owned());
    }
    let buckets = items;
    let mut out = Vec::with_capacity(buckets.len());
    for bucket in buckets {
        let Value::Array(words) = bucket else {
            return Err("unexpected wordfreq bucket".to_owned());
        };
        let mut bin = Vec::with_capacity(words.len());
        for word in words {
            let Value::Str(word) = word else {
                return Err("unexpected wordfreq entry".to_owned());
            };
            bin.push(word);
        }
        out.push(bin);
    }
    Ok(out)
}

fn is_typeable(word: &str) -> bool {
    (MIN_WORD_LEN..=MAX_WORD_LEN).contains(&word.len())
        && word.bytes().all(|byte| byte.is_ascii_lowercase())
        && !is_denied(word)
}

/// Ranked word list from parsed buckets, filtered for typing, with the
/// project supplement appended for words the corpus ranks too low.
pub fn ranked_words(buckets: Vec<Vec<String>>, limit: usize) -> Vec<String> {
    let mut ranked = Vec::with_capacity(limit.min(50_000) + SUPPLEMENT_WORDS.len());
    'buckets: for bucket in buckets {
        for word in bucket {
            if ranked.len() >= limit {
                break 'buckets;
            }
            if !is_typeable(&word) {
                continue;
            }
            ranked.push(word);
        }
    }
    let additions: Vec<&str> = {
        let seen: std::collections::HashSet<&str> = ranked.iter().map(String::as_str).collect();
        SUPPLEMENT_WORDS
            .iter()
            .copied()
            .filter(|word| is_typeable(word) && !seen.contains(word))
            .collect()
    };
    for word in additions {
        if !ranked.iter().any(|existing| existing == word) {
            ranked.push(word.to_owned());
        }
    }
    ranked
}

/// Filters the upstream bytes and writes the cache atomically.
/// Returns the number of words written.
pub fn import(bytes: &[u8], destination: &Path, limit: usize) -> Result<usize, String> {
    let buckets = parse_buckets(bytes)?;
    let ranked = ranked_words(buckets, limit);
    if ranked.is_empty() {
        return Err("wordfreq data produced no words".to_owned());
    }
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let mut text =
        String::with_capacity(ranked.iter().map(|word| word.len() + 1).sum::<usize>() + 512);
    text.push_str(CACHE_HEADER);
    text.push('\n');
    text.push_str("# source: ");
    text.push_str(SOURCE_URL);
    text.push('\n');
    text.push_str("# license: ");
    text.push_str(SOURCE_LICENSE);
    text.push('\n');
    text.push_str("# attribution: ");
    text.push_str(SOURCE_ATTRIBUTION);
    text.push('\n');
    for word in &ranked {
        text.push_str(word);
        text.push('\n');
    }
    let temporary = destination.with_extension("txt.tmp");
    std::fs::write(&temporary, text.as_bytes()).map_err(|error| error.to_string())?;
    std::fs::rename(&temporary, destination).map_err(|error| error.to_string())?;
    Ok(ranked.len())
}

pub fn cache_path(root: &Path) -> PathBuf {
    root.join(CACHE_FILENAME)
}

/// Windows host location for the downloaded cache. Android passes its own
/// `filesDir` instead, so this is only called by Windows binaries.
pub fn windows_dictionary_root() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("ZeroConIME")
        .join("english")
}

enum Value {
    Array(Vec<Value>),
    Map(Vec<(Value, Value)>),
    Str(String),
    /// Integers only appear in the header; the value itself is never needed.
    Unsigned,
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl Reader<'_> {
    fn byte(&mut self) -> Result<u8, String> {
        let byte = *self
            .bytes
            .get(self.position)
            .ok_or_else(|| "truncated msgpack".to_owned())?;
        self.position += 1;
        Ok(byte)
    }

    fn take(&mut self, length: usize) -> Result<&[u8], String> {
        let end = self
            .position
            .checked_add(length)
            .ok_or_else(|| "msgpack length overflow".to_owned())?;
        let slice = self
            .bytes
            .get(self.position..end)
            .ok_or_else(|| "truncated msgpack".to_owned())?;
        self.position = end;
        Ok(slice)
    }

    fn big_endian(&mut self, width: usize) -> Result<u64, String> {
        let bytes = self.take(width)?;
        Ok(bytes
            .iter()
            .fold(0u64, |value, byte| (value << 8) | u64::from(*byte)))
    }

    fn string(&mut self, length: usize) -> Result<Value, String> {
        let bytes = self.take(length)?;
        String::from_utf8(bytes.to_vec())
            .map(Value::Str)
            .map_err(|_| "wordfreq data is not UTF-8".to_owned())
    }

    fn value(&mut self) -> Result<Value, String> {
        let tag = self.byte()?;
        match tag {
            0x00..=0x7f => Ok(Value::Unsigned),
            0x80..=0x8f => self.map(usize::from(tag & 0x0f)),
            0x90..=0x9f => self.array(usize::from(tag & 0x0f)),
            0xa0..=0xbf => self.string(usize::from(tag & 0x1f)),
            0xc0 => Ok(Value::Unsigned),
            0xc4 | 0xd9 => {
                let length = usize::from(self.byte()?);
                self.string(length)
            }
            0xc5 | 0xda => {
                let length = self.big_endian(2)? as usize;
                self.string(length)
            }
            0xdb => {
                let length = self.big_endian(4)? as usize;
                self.string(length)
            }
            0xcc => {
                self.byte()?;
                Ok(Value::Unsigned)
            }
            0xcd => {
                self.big_endian(2)?;
                Ok(Value::Unsigned)
            }
            0xce => {
                self.big_endian(4)?;
                Ok(Value::Unsigned)
            }
            0xdc => {
                let length = self.big_endian(2)? as usize;
                self.array(length)
            }
            0xdd => {
                let length = self.big_endian(4)? as usize;
                self.array(length)
            }
            0xde => {
                let length = self.big_endian(2)? as usize;
                self.map(length)
            }
            other => Err(format!("unsupported msgpack tag {other:#04x}")),
        }
    }

    fn array(&mut self, length: usize) -> Result<Value, String> {
        let mut items = Vec::with_capacity(length.min(4096));
        for _ in 0..length {
            items.push(self.value()?);
        }
        Ok(Value::Array(items))
    }

    fn map(&mut self, length: usize) -> Result<Value, String> {
        let mut pairs = Vec::with_capacity(length.min(16));
        for _ in 0..length {
            let key = self.value()?;
            let value = self.value()?;
            pairs.push((key, value));
        }
        Ok(Value::Map(pairs))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pack_string(out: &mut Vec<u8>, value: &str) {
        assert!(value.len() < 32);
        out.push(0xa0 | value.len() as u8);
        out.extend_from_slice(value.as_bytes());
    }

    fn pack_header(out: &mut Vec<u8>) {
        out.push(0x82);
        pack_string(out, "format");
        pack_string(out, "cB");
        pack_string(out, "version");
        out.push(1);
    }

    fn pack_bucket(out: &mut Vec<u8>, bucket: &[&str]) {
        assert!(bucket.len() < 16);
        out.push(0x90 | bucket.len() as u8);
        for word in bucket {
            pack_string(out, word);
        }
    }

    /// One array: header map first, then one array per frequency bin.
    fn pack_buckets(out: &mut Vec<u8>, buckets: &[&[&str]]) {
        assert!(buckets.len() < 15);
        out.push(0x90 | (buckets.len() + 1) as u8);
        pack_header(out);
        for bucket in buckets {
            pack_bucket(out, bucket);
        }
    }

    fn sample() -> Vec<u8> {
        let mut out = Vec::new();
        pack_buckets(
            &mut out,
            &[
                &["the", "to"],
                &["help", "hello", "held"],
                &["don", "fuck", "github"],
            ],
        );
        out
    }

    #[test]
    fn parses_and_filters_upstream_buckets() {
        let buckets = parse_buckets(&sample()).expect("parse");
        assert_eq!(buckets.len(), 3);
        let ranked = ranked_words(buckets, DEFAULT_WORD_LIMIT);
        assert_eq!(&ranked[..5], &["the", "to", "help", "hello", "held"]);
        // The supplement is appended and de-duplicated against the corpus body.
        assert_eq!(ranked.iter().filter(|word| *word == "github").count(), 1);
        assert!(ranked.iter().any(|word| word == "kubernetes"));
        assert!(!ranked.iter().any(|word| word == "don" || word == "fuck"));
    }

    #[test]
    fn rejects_unknown_format() {
        let mut out = Vec::new();
        pack_buckets(&mut out, &[&["the"]]);
        // Overwrite the header value "cB" so the format check rejects the file.
        let position = out
            .windows(2)
            .position(|pair| pair == [0xa2, b'c'])
            .expect("format value");
        out[position + 2] = b'z';
        assert!(parse_buckets(&out).is_err());
    }

    #[test]
    fn imports_and_reloads_cache() {
        let dir = std::env::temp_dir().join(format!("zerocon-english-{}", std::process::id()));
        let path = cache_path(&dir);
        let written = import(&sample(), &path, DEFAULT_WORD_LIMIT).expect("import");
        assert!(written > 6);
        let catalog = load(&path).expect("load");
        assert_eq!(catalog.len(), written);
        assert!(catalog.contains("help"));
        assert!(!catalog.contains("don"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn prefix_results_follow_rank_not_alphabet() {
        let catalog = Catalog::from_ranked_words(
            ["the", "to", "help", "hello", "held", "github"]
                .iter()
                .map(|word| (*word).to_owned())
                .collect(),
        );
        assert_eq!(catalog.prefix_best("hel", 8), vec!["help", "hello", "held"]);
        assert_eq!(catalog.prefix_best("he", 2), vec!["help", "hello"]);
        assert!(catalog.prefix_best("hel", 0).is_empty());
        assert!(catalog.prefix_best("zz", 8).is_empty());
    }

    #[test]
    fn missing_or_foreign_cache_is_rejected() {
        let dir = std::env::temp_dir().join(format!("zerocon-english-miss-{}", std::process::id()));
        assert!(load(&cache_path(&dir)).is_none());
        std::fs::create_dir_all(&dir).expect("mkdir");
        let path = cache_path(&dir);
        std::fs::write(&path, "the\nto\n").expect("write");
        assert!(load(&path).is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn deny_lists_are_sorted_for_binary_search() {
        let mut stems = CONTRACTION_STEMS.to_vec();
        stems.sort_unstable();
        assert_eq!(stems, CONTRACTION_STEMS);
        let mut denied = DENIED_WORDS.to_vec();
        denied.sort_unstable();
        assert_eq!(denied, DENIED_WORDS);
        assert!(CONTRACTION_STEMS.binary_search(&"won").is_err());
        assert!(CONTRACTION_STEMS.binary_search(&"cannot").is_err());
        let unique: std::collections::HashSet<&str> = SUPPLEMENT_WORDS.iter().copied().collect();
        assert_eq!(unique.len(), SUPPLEMENT_WORDS.len());
        assert!(SUPPLEMENT_WORDS.iter().all(|word| is_typeable(word)));
    }
}

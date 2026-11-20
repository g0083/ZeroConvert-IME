use super::*;
use shared::english_learning::{EnglishLearningSnapshot, EnglishLearningStore};
use std::sync::{LazyLock, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const LIMIT: usize = 8;

/// 英単語辞書は同梱せず、初回起動時に取得して端末内で抽出・並べ替えたキャッシュを読む。
/// 未取得の間は静的な補完候補がない（学習済み語と入力原文だけを返す）。
static CATALOG: OnceLock<Option<shared::english_dictionary::Catalog>> = OnceLock::new();

fn catalog() -> Option<&'static shared::english_dictionary::Catalog> {
    if let Some(catalog) = CATALOG.get() {
        return catalog.as_ref();
    }
    #[cfg(test)]
    {
        // テストは実ファイルの有無に依存させない。
        return CATALOG
            .get_or_init(|| {
                Some(shared::english_dictionary::Catalog::from_ranked_words(
                    ["hello", "help", "held", "here", "health", "heart"]
                        .iter()
                        .map(|word| (*word).to_owned())
                        .collect(),
                ))
            })
            .as_ref();
    }
    #[cfg(not(test))]
    {
        let path = shared::english_dictionary::cache_path(
            &shared::english_dictionary::windows_dictionary_root(),
        );
        // 未取得の間だけファイルを確認する。ダウンロード完了後にアプリを再起動しなくても
        // 次の補完から使えるようにするため、読めた時点で確定させる。
        if path.is_file() {
            if let Some(catalog) = shared::english_dictionary::load(&path) {
                return CATALOG.get_or_init(|| Some(catalog)).as_ref();
            }
        }
        None
    }
}

#[derive(Default)]
struct ClientEnglishLearningCache {
    server_session_id: u64,
    revision: u64,
    store: EnglishLearningStore,
}

static ENGLISH_LEARNING_CACHE: LazyLock<Mutex<ClientEnglishLearningCache>> =
    LazyLock::new(|| Mutex::new(ClientEnglishLearningCache::default()));

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

pub(super) fn is_dictionary_word(word: &str) -> bool {
    if word.starts_with('@') {
        return shared::english_learning::is_default_email_domain(word);
    }
    if !(2..=64).contains(&word.len()) || !word.bytes().all(|b| b.is_ascii_alphabetic()) {
        return false;
    }
    let lower = word.to_ascii_lowercase();
    catalog().is_some_and(|catalog| catalog.contains(&lower))
}

pub(super) fn cache_sync_cursor() -> (u64, u64) {
    let guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    (guard.server_session_id, guard.revision)
}

pub(super) fn apply_server_snapshot(server_session_id: u64, revision: u64, snapshot_json: &str) {
    let Ok(snapshot) = serde_json::from_str::<EnglishLearningSnapshot>(snapshot_json) else {
        return;
    };
    let mut guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if guard.server_session_id == server_session_id && revision != 0 && revision <= guard.revision {
        return;
    }
    guard.server_session_id = server_session_id;
    guard.revision = revision;
    guard.store = EnglishLearningStore::from_snapshot(snapshot);
}

impl ClientEnglishLearningCache {
    fn update_revision(&mut self, server_session_id: u64, revision: u64) {
        if self.server_session_id == server_session_id
            && self.revision > 0
            && revision == self.revision.saturating_add(1)
        {
            self.revision = revision;
        } else {
            self.server_session_id = server_session_id;
            self.revision = 0;
        }
    }
}

pub(super) fn update_revision(server_session_id: u64, revision: u64) {
    let mut guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.update_revision(server_session_id, revision);
}

pub(super) fn record_optimistic(prefix: &str, word: &str, explicit_selection: bool) {
    let in_dictionary = is_dictionary_word(word);
    let mut guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.store.record(
        prefix,
        word,
        explicit_selection,
        in_dictionary,
        now_millis(),
    );
}

pub(super) fn forget_local(word: &str, prefix: Option<&str>) -> bool {
    let mut guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.store.forget(word, prefix)
}

pub(super) fn committed_word(c: &Composition) -> String {
    if let Some(selected) = c.candidates.texts.get(c.selection_index.max(0) as usize) {
        if !selected.is_empty() {
            return selected.clone();
        }
    }
    if !c.preview.is_empty() {
        return c.preview.clone();
    }
    c.raw_input.clone()
}

/// Character index of the caret inside `raw_input` (0 = start, len = end).
/// `cursor_position` is `None` when the caret is at the end, matching the
/// Japanese `effective_cursor_and_total` convention.
pub(super) fn cursor_char_index(c: &Composition) -> usize {
    let total = c.raw_input.chars().count();
    c.cursor_position.unwrap_or(total).min(total)
}

pub(super) fn split_at_char(s: &str, char_index: usize) -> (String, String) {
    let total = s.chars().count();
    let index = char_index.min(total);
    let byte_index = s
        .char_indices()
        .nth(index)
        .map(|(byte, _)| byte)
        .unwrap_or(s.len());
    (
        s[..byte_index].to_owned(),
        s[byte_index..].to_owned(),
    )
}

pub(super) fn insert_at_char(s: &mut String, char_index: usize, text: &str) {
    let (before, after) = split_at_char(s, char_index);
    s.clear();
    s.push_str(&before);
    s.push_str(text);
    s.push_str(&after);
}

pub(super) fn remove_char_at(s: &mut String, char_index: usize) -> bool {
    let total = s.chars().count();
    if char_index >= total {
        return false;
    }
    let byte_start = s
        .char_indices()
        .nth(char_index)
        .map(|(byte, _)| byte)
        .unwrap_or(s.len());
    let byte_end = s
        .char_indices()
        .nth(char_index + 1)
        .map(|(byte, _)| byte)
        .unwrap_or(s.len());
    s.replace_range(byte_start..byte_end, "");
    true
}

pub(super) fn candidates_with_store(
    prefix: &str,
    store: &EnglishLearningStore,
    read_learning: bool,
) -> Candidates {
    if prefix.is_empty() {
        return Candidates::default();
    }
    let mut texts = vec![prefix.to_owned()];
    let mut candidate_ids = vec![if read_learning && store.is_promoted_word(prefix) {
        shared::ai_candidate::ENGLISH_LEARNING
    } else {
        0
    }];
    if shared::english_learning::is_email_domain_prefix(prefix) {
        for suggestion in store.suggest_email_domains(prefix, LIMIT, read_learning) {
            texts.push(suggestion.text);
            candidate_ids.push(if suggestion.learned {
                shared::ai_candidate::ENGLISH_LEARNING
            } else {
                0
            });
        }
    } else if (2..=64).contains(&prefix.len()) && prefix.bytes().all(|b| b.is_ascii_alphabetic()) {
        let lower = prefix.to_ascii_lowercase();
        let static_words: Vec<&str> = catalog()
            .map(|catalog| catalog.prefix_best(&lower, LIMIT + 8))
            .unwrap_or_default();
        for suggestion in store.merge_with_dictionary(prefix, &static_words, LIMIT, read_learning) {
            texts.push(suggestion.text);
            candidate_ids.push(if suggestion.learned {
                shared::ai_candidate::ENGLISH_LEARNING
            } else {
                0
            });
        }
    }
    let count = texts.len();
    Candidates {
        prediction_readings: Default::default(),
        texts,
        sub_texts: vec![String::new(); count],
        hiragana: prefix.into(),
        corresponding_count: vec![prefix.len() as i32; count],
        candidate_ids,
    }
}

pub(super) fn candidates_with_learning(prefix: &str, read_learning: bool) -> Candidates {
    if !read_learning {
        return candidates_with_store(prefix, &EnglishLearningStore::default(), false);
    }
    let guard = match ENGLISH_LEARNING_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    candidates_with_store(prefix, &guard.store, true)
}

#[cfg(test)]
pub(super) fn candidates(prefix: &str) -> Candidates {
    candidates_with_store(prefix, &EnglishLearningStore::default(), false)
}

impl TextServiceFactory {
    pub(super) fn plan_english_action(
        c: &Composition,
        action: &UserAction,
        shift: bool,
    ) -> Option<(CompositionState, Vec<ClientAction>)> {
        let active = c.state != CompositionState::None;
        let composing_email_domain = active && c.raw_input.starts_with('@');
        let finish_with_learning = |explicit: bool| {
            (
                CompositionState::None,
                vec![
                    ClientAction::CommitEnglishLearning {
                        prefix: c.raw_input.clone(),
                        word: committed_word(c),
                        explicit_selection: explicit,
                    },
                    ClientAction::EndComposition,
                ],
            )
        };
        match action {
            UserAction::Input('@') => {
                let mut actions = Vec::new();
                if active {
                    let word = committed_word(c);
                    actions.push(ClientAction::CommitWithText(word.clone()));
                    actions.push(ClientAction::CommitEnglishLearning {
                        prefix: c.raw_input.clone(),
                        word,
                        explicit_selection: c.selection_index > 0,
                    });
                    actions.push(ClientAction::EndComposition);
                }
                actions.push(ClientAction::StartComposition);
                actions.push(ClientAction::AppendTextDirect("@".to_owned()));
                Some((CompositionState::Composing, actions))
            }
            UserAction::Input(ch)
                if ch.is_ascii_alphabetic()
                    || (composing_email_domain
                        && shared::english_learning::is_email_domain_continuation_char(*ch)
                        && c.raw_input.len() < shared::english_learning::MAX_WORD_LEN) =>
            {
                let mut actions = Vec::new();
                if !active {
                    actions.push(ClientAction::StartComposition);
                }
                actions.push(ClientAction::AppendTextDirect(ch.to_string()));
                Some((CompositionState::Composing, actions))
            }
            UserAction::Number { value, .. }
                if composing_email_domain
                    && c.raw_input.len() < shared::english_learning::MAX_WORD_LEN =>
            {
                let digit = char::from_digit(*value as u32, 10).unwrap_or('0');
                Some((
                    CompositionState::Composing,
                    vec![ClientAction::AppendTextDirect(digit.to_string())],
                ))
            }
            UserAction::NumpadSymbol(ch)
                if composing_email_domain
                    && matches!(ch, '.' | '-')
                    && c.raw_input.len() < shared::english_learning::MAX_WORD_LEN =>
            {
                Some((
                    CompositionState::Composing,
                    vec![ClientAction::AppendTextDirect(ch.to_string())],
                ))
            }
            UserAction::Backspace if active => {
                let cursor = cursor_char_index(c);
                let total = c.raw_input.chars().count();
                if cursor == 0 {
                    Some((c.state.clone(), Vec::new()))
                } else if total <= 1 {
                    Some((
                        CompositionState::None,
                        vec![ClientAction::RemoveText, ClientAction::EndComposition],
                    ))
                } else {
                    Some((CompositionState::Composing, vec![ClientAction::RemoveText]))
                }
            }
            UserAction::Escape if active => Some((
                CompositionState::None,
                vec![
                    ClientAction::CommitWithText(c.raw_input.clone()),
                    ClientAction::EndComposition,
                ],
            )),
            UserAction::Enter if active => Some(finish_with_learning(c.selection_index > 0)),
            UserAction::Delete if active => {
                let cursor = cursor_char_index(c);
                let total = c.raw_input.chars().count();
                if cursor >= total {
                    Some((c.state.clone(), Vec::new()))
                } else {
                    Some((CompositionState::Composing, vec![ClientAction::DeleteForwardText]))
                }
            }
            UserAction::Navigation(Navigation::Left) if active => Some((
                CompositionState::Composing,
                vec![ClientAction::MoveCursor(-1)],
            )),
            UserAction::Navigation(Navigation::Right) if active => Some((
                CompositionState::Composing,
                vec![ClientAction::MoveCursor(1)],
            )),
            UserAction::Tab if active => {
                let count = c.candidates.texts.len();
                if count <= 1 {
                    return Some(finish_with_learning(false));
                }
                let index = if c.selection_index > 0 {
                    c.selection_index as usize
                } else if shift {
                    count - 1
                } else {
                    1
                };
                let word = c.candidates.texts[index.min(count - 1)].clone();
                Some((
                    CompositionState::None,
                    vec![
                        ClientAction::CommitWithText(word.clone()),
                        ClientAction::CommitEnglishLearning {
                            prefix: c.raw_input.clone(),
                            word,
                            explicit_selection: true,
                        },
                        ClientAction::EndComposition,
                    ],
                ))
            }
            UserAction::Navigation(Navigation::Up | Navigation::Down) if active => Some((
                CompositionState::Previewing,
                vec![
                    ClientAction::ShowCandidateWindow,
                    ClientAction::SetSelection(
                        if matches!(action, UserAction::Navigation(Navigation::Up)) {
                            SetSelectionType::Up
                        } else {
                            SetSelectionType::Down
                        },
                    ),
                ],
            )),
            UserAction::Space
            | UserAction::Input(_)
            | UserAction::Number { .. }
            | UserAction::NumpadSymbol(_)
                if active =>
            {
                let text = if matches!(action, UserAction::Space) {
                    " ".to_owned()
                } else {
                    Self::direct_text_for_action(action)?
                };
                Some((
                    CompositionState::None,
                    vec![
                        ClientAction::CommitEnglishLearning {
                            prefix: c.raw_input.clone(),
                            word: committed_word(c),
                            explicit_selection: c.selection_index > 0,
                        },
                        ClientAction::EndComposition,
                        ClientAction::CommitTextDirect(text),
                    ],
                ))
            }
            UserAction::ToggleInputMode | UserAction::InputModeOn => {
                let mut actions = Vec::new();
                if active {
                    actions.push(ClientAction::CommitEnglishLearning {
                        prefix: c.raw_input.clone(),
                        word: committed_word(c),
                        explicit_selection: c.selection_index > 0,
                    });
                    actions.push(ClientAction::EndComposition);
                }
                actions.push(ClientAction::SetIMEMode(InputMode::Kana));
                Some((CompositionState::None, actions))
            }
            UserAction::InputModeOff => {
                let mut actions = Vec::new();
                if active {
                    actions.push(ClientAction::CommitEnglishLearning {
                        prefix: c.raw_input.clone(),
                        word: committed_word(c),
                        explicit_selection: c.selection_index > 0,
                    });
                    actions.push(ClientAction::EndComposition);
                }
                actions.push(ClientAction::SetIMEMode(InputMode::Latin));
                Some((CompositionState::None, actions))
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn completions_preserve_input_and_case() {
        for prefix in ["hel", "Hel", "HEL", "hEl"] {
            let c = candidates(prefix);
            assert_eq!(c.texts[0], prefix);
            assert!(c.texts.iter().any(|w| w.eq_ignore_ascii_case("hello")));
            assert!(c.texts.iter().all(|w| w.starts_with(prefix)));
        }
        assert_eq!(candidates("x").texts, ["x"]);
        assert_eq!(candidates("123").texts, ["123"]);
        assert!(candidates("").is_empty_composition());
        let catalog = catalog().expect("test catalog");
        assert!(catalog.contains("hello"));
        assert_eq!(catalog.prefix_best("hel", 2), vec!["hello", "help"]);
    }

    #[test]
    fn candidates_with_store_prioritizes_learned_entries_and_tags_only_learned_ids() {
        let mut store = EnglishLearningStore::new(None);
        assert!(store.record("hel", "hello", true, true, 100));
        assert!(store.record("ZeroConvert", "ZeroConvert", true, false, 200));

        let c = candidates_with_store("hel", &store, true);
        assert_eq!(c.texts[0], "hel");
        assert_eq!(c.candidate_ids[0], 0);
        assert_eq!(c.texts[1], "hello");
        assert_eq!(c.candidate_ids[1], shared::ai_candidate::ENGLISH_LEARNING);
        assert!(c.candidate_ids[2..].iter().all(|&id| id == 0));

        let oov = candidates_with_store("Zero", &store, true);
        assert_eq!(oov.texts, ["Zero", "ZeroConvert"]);
        assert_eq!(
            oov.candidate_ids,
            [0, shared::ai_candidate::ENGLISH_LEARNING]
        );

        let exact_oov = candidates_with_store("ZeroConvert", &store, true);
        assert_eq!(exact_oov.texts, ["ZeroConvert"]);
        assert_eq!(
            exact_oov.candidate_ids,
            [shared::ai_candidate::ENGLISH_LEARNING]
        );

        let disabled = candidates_with_store("Zero", &store, false);
        assert_eq!(disabled.texts, ["Zero"]);
        assert_eq!(disabled.candidate_ids, [0]);
    }

    #[test]
    fn cache_revision_advances_only_on_contiguous_same_session_updates() {
        let mut cache = ClientEnglishLearningCache {
            server_session_id: 10,
            revision: 2,
            store: EnglishLearningStore::new(None),
        };
        cache.update_revision(10, 3);
        assert_eq!((cache.server_session_id, cache.revision), (10, 3));

        // Out-of-band server mutation leaves a revision gap -> invalidate to force resync.
        cache.update_revision(10, 5);
        assert_eq!((cache.server_session_id, cache.revision), (10, 0));

        // While invalidated (revision == 0), a subsequent update stays invalidated until snapshot sync.
        cache.update_revision(10, 1);
        assert_eq!((cache.server_session_id, cache.revision), (10, 0));

        // Server session change invalidates stale revision from the previous process.
        cache.revision = 4;
        cache.update_revision(11, 5);
        assert_eq!((cache.server_session_id, cache.revision), (11, 0));
    }

    #[test]
    fn typing_deleting_selecting_accepting_and_cancelling() {
        let mut c = Composition::default();
        for ch in "hell".chars() {
            let (state, actions) = TextServiceFactory::plan_actions_for_user_action(
                &c,
                &UserAction::Input(ch),
                &InputMode::Latin,
                false,
                &AppConfig::default(),
                false,
            )
            .unwrap();
            assert!(actions.contains(&ClientAction::AppendTextDirect(ch.to_string())));
            c.state = state;
            c.raw_input.push(ch);
        }
        let plan =
            |c: &Composition, a| TextServiceFactory::plan_english_action(c, &a, false).unwrap();
        assert_eq!(
            plan(&c, UserAction::Backspace).1,
            [ClientAction::RemoveText]
        );
        c.raw_input.pop();
        c.candidates = candidates(&c.raw_input);
        assert_eq!(
            plan(&c, UserAction::Tab).1,
            vec![
                ClientAction::CommitWithText(c.candidates.texts[1].clone()),
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: c.candidates.texts[1].clone(),
                    explicit_selection: true,
                },
                ClientAction::EndComposition,
            ]
        );
        c.selection_index = 2;
        assert_eq!(
            plan(&c, UserAction::Tab).1,
            vec![
                ClientAction::CommitWithText(c.candidates.texts[2].clone()),
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: c.candidates.texts[2].clone(),
                    explicit_selection: true,
                },
                ClientAction::EndComposition,
            ]
        );
        assert_eq!(
            plan(&c, UserAction::Enter).1,
            vec![
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: c.candidates.texts[2].clone(),
                    explicit_selection: true,
                },
                ClientAction::EndComposition,
            ]
        );
        c.selection_index = 0;
        assert_eq!(
            plan(&c, UserAction::Enter).1,
            vec![
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: "hel".into(),
                    explicit_selection: false,
                },
                ClientAction::EndComposition,
            ]
        );
        // Escape cancel must not emit CommitEnglishLearning.
        assert_eq!(
            plan(&c, UserAction::Escape).1,
            vec![
                ClientAction::CommitWithText("hel".into()),
                ClientAction::EndComposition,
            ]
        );
        assert_eq!(
            plan(&c, UserAction::Space).1,
            vec![
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: "hel".into(),
                    explicit_selection: false,
                },
                ClientAction::EndComposition,
                ClientAction::CommitTextDirect(" ".into()),
            ]
        );
        assert_eq!(
            plan(&c, UserAction::InputModeOn).1,
            vec![
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: "hel".into(),
                    explicit_selection: false,
                },
                ClientAction::EndComposition,
                ClientAction::SetIMEMode(InputMode::Kana),
            ]
        );
        assert_eq!(
            plan(&c, UserAction::Navigation(Navigation::Left)),
            (
                CompositionState::Composing,
                vec![ClientAction::MoveCursor(-1)]
            )
        );
        assert_eq!(
            plan(&c, UserAction::Navigation(Navigation::Right)),
            (
                CompositionState::Composing,
                vec![ClientAction::MoveCursor(1)]
            )
        );
        // At the end of the word, Delete is a no-op and keeps the composition.
        assert_eq!(
            plan(&c, UserAction::Delete),
            (CompositionState::Composing, Vec::new())
        );
        // Backspace/Delete respect a caret inside the word.
        c.cursor_position = Some(1);
        assert_eq!(
            plan(&c, UserAction::Backspace).1,
            [ClientAction::RemoveText]
        );
        assert_eq!(
            plan(&c, UserAction::Delete),
            (
                CompositionState::Composing,
                vec![ClientAction::DeleteForwardText]
            )
        );
        c.cursor_position = Some(0);
        assert_eq!(
            plan(&c, UserAction::Backspace),
            (CompositionState::Composing, Vec::new())
        );
        c.cursor_position = None;
        assert_eq!(
            plan(&c, UserAction::Input('.')).1,
            vec![
                ClientAction::CommitEnglishLearning {
                    prefix: "hel".into(),
                    word: "hel".into(),
                    explicit_selection: false,
                },
                ClientAction::EndComposition,
                ClientAction::CommitTextDirect(".".into()),
            ]
        );
        c.raw_input = "h".into();
        assert_eq!(
            plan(&c, UserAction::Backspace),
            (
                CompositionState::None,
                vec![ClientAction::RemoveText, ClientAction::EndComposition]
            )
        );
    }

    #[test]
    fn at_sign_triggers_email_domain_suggestions_and_commits_prior_word() {
        assert!(is_dictionary_word("@gmail.com"));
        assert!(!is_dictionary_word("@unknown.example"));

        let at_candidates = candidates("@");
        assert_eq!(at_candidates.texts[0], "@");
        assert_eq!(at_candidates.texts[1], "@gmail.com");
        assert_eq!(at_candidates.texts.len(), 1 + LIMIT);

        let yahoo_candidates = candidates("@yahoo.");
        assert_eq!(
            yahoo_candidates.texts,
            ["@yahoo.", "@yahoo.co.jp", "@yahoo.com"]
        );
        assert_eq!(candidates("@gmail.com").texts, ["@gmail.com"]);

        // Idle `@` starts an email-domain composition immediately.
        let idle = Composition::default();
        assert_eq!(
            TextServiceFactory::plan_english_action(&idle, &UserAction::Input('@'), false),
            Some((
                CompositionState::Composing,
                vec![
                    ClientAction::StartComposition,
                    ClientAction::AppendTextDirect("@".into()),
                ],
            ))
        );

        // Typing `@` after an active word commits/learns the word first, then starts `@`.
        let mut active_word = Composition {
            state: CompositionState::Previewing,
            raw_input: "taro".into(),
            candidates: Candidates {
                texts: vec!["taro".into(), "tarou".into()],
                ..Default::default()
            },
            selection_index: 1,
            ..Default::default()
        };
        assert_eq!(
            TextServiceFactory::plan_english_action(&active_word, &UserAction::Input('@'), false),
            Some((
                CompositionState::Composing,
                vec![
                    ClientAction::CommitWithText("tarou".into()),
                    ClientAction::CommitEnglishLearning {
                        prefix: "taro".into(),
                        word: "tarou".into(),
                        explicit_selection: true,
                    },
                    ClientAction::EndComposition,
                    ClientAction::StartComposition,
                    ClientAction::AppendTextDirect("@".into()),
                ],
            ))
        );

        // Deferred projection tracks the commit-then-restart sequence for fast follow-up keys.
        let mut projection =
            TextServiceFactory::deferred_projection(&active_word, InputMode::Latin);
        let (_, actions) =
            TextServiceFactory::plan_english_action(&active_word, &UserAction::Input('@'), false)
                .unwrap();
        let deferred: Vec<DeferredClientAction> = actions
            .into_iter()
            .map(|action| DeferredClientAction {
                action,
                transition: CompositionState::Composing,
            })
            .collect();
        TextServiceFactory::apply_actions_to_deferred_projection(&mut projection, &deferred);
        assert_eq!(projection.state, CompositionState::Composing);
        assert_eq!(projection.raw_input, "@");
        assert!(projection.reliable);

        // Dots, hyphens, underscores, and digits continue an active `@domain` composition,
        // whereas spaces or punctuation commit it.
        active_word.state = CompositionState::Composing;
        active_word.raw_input = "@my-co".into();
        active_word.selection_index = 0;
        active_word.candidates = candidates("@my-co");
        assert_eq!(
            TextServiceFactory::plan_english_action(&active_word, &UserAction::Input('.'), false),
            Some((
                CompositionState::Composing,
                vec![ClientAction::AppendTextDirect(".".into())],
            ))
        );
        assert_eq!(
            TextServiceFactory::plan_english_action(
                &active_word,
                &UserAction::Number {
                    value: 1,
                    is_numpad: false
                },
                false,
            ),
            Some((
                CompositionState::Composing,
                vec![ClientAction::AppendTextDirect("1".into())],
            ))
        );
        assert_eq!(
            TextServiceFactory::plan_english_action(&active_word, &UserAction::Space, false),
            Some((
                CompositionState::None,
                vec![
                    ClientAction::CommitEnglishLearning {
                        prefix: "@my-co".into(),
                        word: "@my-co".into(),
                        explicit_selection: false,
                    },
                    ClientAction::EndComposition,
                    ClientAction::CommitTextDirect(" ".into()),
                ],
            ))
        );

        // Tab adopts the top domain suggestion and learns it.
        let gm = Composition {
            state: CompositionState::Composing,
            raw_input: "@gm".into(),
            candidates: candidates("@gm"),
            ..Default::default()
        };
        assert_eq!(
            TextServiceFactory::plan_english_action(&gm, &UserAction::Tab, false),
            Some((
                CompositionState::None,
                vec![
                    ClientAction::CommitWithText("@gmail.com".into()),
                    ClientAction::CommitEnglishLearning {
                        prefix: "@gm".into(),
                        word: "@gmail.com".into(),
                        explicit_selection: true,
                    },
                    ClientAction::EndComposition,
                ],
            ))
        );
    }

    #[test]
    fn cursor_helpers_insert_and_delete_at_caret() {
        assert_eq!(split_at_char("hello", 2), ("he".to_owned(), "llo".to_owned()));
        assert_eq!(split_at_char("hello", 0), ("".to_owned(), "hello".to_owned()));
        assert_eq!(split_at_char("hello", 5), ("hello".to_owned(), "".to_owned()));
        assert_eq!(split_at_char("hello", 99), ("hello".to_owned(), "".to_owned()));

        let mut word = "helo".to_owned();
        insert_at_char(&mut word, 3, "l");
        assert_eq!(word, "hello");

        let mut start = "hello".to_owned();
        insert_at_char(&mut start, 0, "x");
        assert_eq!(start, "xhello");

        let mut middle = "hello".to_owned();
        assert!(remove_char_at(&mut middle, 1));
        assert_eq!(middle, "hllo");
        assert!(!remove_char_at(&mut middle, 99));
        assert_eq!(middle, "hllo");

        let mut comp = Composition {
            state: CompositionState::Composing,
            raw_input: "hello".into(),
            ..Default::default()
        };
        assert_eq!(cursor_char_index(&comp), 5);
        comp.cursor_position = Some(2);
        assert_eq!(cursor_char_index(&comp), 2);
        comp.cursor_position = Some(99);
        assert_eq!(cursor_char_index(&comp), 5);
        comp.cursor_position = Some(0);
        assert_eq!(cursor_char_index(&comp), 0);
    }
}

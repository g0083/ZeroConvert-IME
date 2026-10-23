use super::*;

const WORDS: &[(&str, usize)] = include!("english_words.rs");
const LIMIT: usize = 8;

pub(super) fn candidates(prefix: &str) -> Candidates {
    if prefix.is_empty() {
        return Candidates::default();
    }
    let mut texts = vec![prefix.to_owned()];
    if (2..=64).contains(&prefix.len()) && prefix.bytes().all(|b| b.is_ascii_alphabetic()) {
        let lower = prefix.to_ascii_lowercase();
        let start = WORDS.partition_point(|(w, _)| *w < lower.as_str());
        let mut best = Vec::with_capacity(LIMIT + 1);
        for &(word, rank) in WORDS[start..]
            .iter()
            .take_while(|(w, _)| w.starts_with(&lower))
        {
            if word == lower {
                continue;
            }
            let i = best.partition_point(|&(_, r)| r < rank);
            best.insert(i, (word, rank));
            best.truncate(LIMIT);
        }
        for (word, _) in best {
            let mut word = word.to_owned();
            if prefix.bytes().all(|b| b.is_ascii_uppercase()) {
                word.make_ascii_uppercase();
            }
            word.replace_range(..prefix.len(), prefix);
            texts.push(word);
        }
    }
    let count = texts.len();
    Candidates {
        texts,
        sub_texts: vec![String::new(); count],
        hiragana: prefix.into(),
        corresponding_count: vec![prefix.len() as i32; count],
        candidate_ids: vec![0; count],
    }
}

impl TextServiceFactory {
    pub(super) fn plan_english_action(
        c: &Composition,
        action: &UserAction,
        shift: bool,
    ) -> Option<(CompositionState, Vec<ClientAction>)> {
        let active = c.state != CompositionState::None;
        let finish = || (CompositionState::None, vec![ClientAction::EndComposition]);
        match action {
            UserAction::Input(ch) if ch.is_ascii_alphabetic() => {
                let mut actions = Vec::new();
                if !active {
                    actions.push(ClientAction::StartComposition);
                }
                actions.push(ClientAction::AppendTextDirect(ch.to_string()));
                Some((CompositionState::Composing, actions))
            }
            UserAction::Backspace if active => Some(if c.raw_input.len() <= 1 {
                (
                    CompositionState::None,
                    vec![ClientAction::RemoveText, ClientAction::EndComposition],
                )
            } else {
                (CompositionState::Composing, vec![ClientAction::RemoveText])
            }),
            UserAction::Escape if active => Some((
                CompositionState::None,
                vec![
                    ClientAction::CommitWithText(c.raw_input.clone()),
                    ClientAction::EndComposition,
                ],
            )),
            UserAction::Enter
            | UserAction::Delete
            | UserAction::Navigation(Navigation::Left | Navigation::Right)
                if active =>
            {
                Some(finish())
            }
            UserAction::Tab if active => {
                let count = c.candidates.texts.len();
                if count <= 1 {
                    return Some(finish());
                }
                let index = if c.selection_index > 0 {
                    c.selection_index as usize
                } else if shift {
                    count - 1
                } else {
                    1
                };
                Some((
                    CompositionState::None,
                    vec![
                        ClientAction::CommitWithText(
                            c.candidates.texts[index.min(count - 1)].clone(),
                        ),
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
                        ClientAction::EndComposition,
                        ClientAction::CommitTextDirect(text),
                    ],
                ))
            }
            UserAction::ToggleInputMode | UserAction::InputModeOn => {
                let mut actions = Vec::new();
                if active {
                    actions.push(ClientAction::EndComposition);
                }
                actions.push(ClientAction::SetIMEMode(InputMode::Kana));
                Some((CompositionState::None, actions))
            }
            UserAction::InputModeOff => {
                let mut actions = Vec::new();
                if active {
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
        assert!(WORDS.windows(2).all(|w| w[0].0 < w[1].0));
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
            plan(&c, UserAction::Tab).1[0],
            ClientAction::CommitWithText(c.candidates.texts[1].clone())
        );
        c.selection_index = 2;
        assert_eq!(
            plan(&c, UserAction::Tab).1[0],
            ClientAction::CommitWithText(c.candidates.texts[2].clone())
        );
        assert_eq!(
            plan(&c, UserAction::Escape).1[0],
            ClientAction::CommitWithText("hel".into())
        );
        assert_eq!(
            plan(&c, UserAction::Space).1,
            [
                ClientAction::EndComposition,
                ClientAction::CommitTextDirect(" ".into())
            ]
        );
        assert_eq!(
            plan(&c, UserAction::InputModeOn).1,
            [
                ClientAction::EndComposition,
                ClientAction::SetIMEMode(InputMode::Kana)
            ]
        );
        for direction in [Navigation::Left, Navigation::Right] {
            assert_eq!(
                plan(&c, UserAction::Navigation(direction)),
                (CompositionState::None, vec![ClientAction::EndComposition])
            );
        }
        assert_eq!(
            plan(&c, UserAction::Input('.')).1,
            vec![
                ClientAction::EndComposition,
                ClientAction::CommitTextDirect(".".into())
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
}

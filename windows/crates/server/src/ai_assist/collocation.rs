// ai_assist/collocation.rs
// 次世代アシスト Phase 2: 格助詞バイグラム (共起語) 先読み
//
// 「名詞 + 格助詞」の直後に続く動詞を 0ms で提示する。辞書・推論エンジン・
// ネットワークに一切依存せず、静的な共起テーブルだけで完結する。

/// 共起テーブル: (名詞+格助詞, 続く語彙)。
///
/// キーは読み (ひらがな) で、格助詞を必ず含む。助詞単体 (「を」「に」) は
/// キーにしないことで、格助詞として誤爆しないことを保証する。
const COLLOCATIONS: &[(&str, &[&str])] = &[
    (
        "資料を",
        &["作成する", "送付する", "確認する", "提出する", "共有する"],
    ),
    (
        "書類を",
        &["作成する", "送付する", "確認する", "提出する", "破棄する"],
    ),
    ("会議に", &["参加する", "出席する", "遅れる", "出る"]),
    (
        "ミーティングに",
        &["参加する", "出席する", "遅れる", "出る"],
    ),
    ("会議で", &["議論する", "決定する", "報告する"]),
    (
        "メールを",
        &["送信しました", "返信する", "確認しました", "作成する"],
    ),
    (
        "メッセージを",
        &["送信しました", "返信する", "確認しました", "送った"],
    ),
    ("ご飯を", &["食べる", "用意する", "買いに行く"]),
    ("昼食を", &["食べる", "用意する", "買いに行く"]),
    ("駅に", &["着きました", "向かっています", "到着しました"]),
    ("会社に", &["着きました", "向かっています", "到着しました"]),
    (
        "バグを",
        &["修正する", "再現する", "解消しました", "報告する"],
    ),
    (
        "エラーを",
        &["修正する", "再現する", "解消しました", "報告する"],
    ),
    (
        "コードを",
        &["レビューする", "リファクタリングする", "コミットする"],
    ),
    ("テストを", &["実行する", "追加する", "修正する"]),
];

/// 共起語から生成された先読み候補。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollocationCandidate {
    /// 確定される文字列 (前置部分 + 共起語)。
    pub text: String,
    /// 追加された共起語部分 (UI の読み行表示用)。
    pub continuation: String,
    /// 該当した共起テーブルのキー。
    pub key: &'static str,
}

/// 共起テーブルから先読み候補を生成する。
///
/// 入力末尾が共起テーブルのキーと一致する場合にのみ候補を返す。
/// 複数のキーが一致する場合は最長キーのものを採用する。
pub fn find_collocation(hiragana: &str) -> Option<CollocationCandidate> {
    let trimmed = hiragana.trim();
    if trimmed.is_empty() {
        return None;
    }

    let mut best: Option<(&'static str, &'static [&'static str])> = None;
    for (key, continuations) in COLLOCATIONS {
        if !trimmed.ends_with(key) {
            continue;
        }
        if best.is_none_or(|(best_key, _)| key.len() > best_key.len()) {
            best = Some((key, continuations));
        }
    }

    let (key, continuations) = best?;
    let continuation = continuations.first()?;
    let prefix_len = trimmed.len() - key.len();
    let prefix = &trimmed[..prefix_len];

    Some(CollocationCandidate {
        text: format!("{prefix}{key}{continuation}"),
        continuation: (*continuation).to_string(),
        key,
    })
}

/// 共起テーブルのキー一覧を返す (テスト・診断用)。
pub fn collocation_keys() -> Vec<&'static str> {
    COLLOCATIONS.iter().map(|(key, _)| *key).collect()
}

#[cfg(test)]
mod tests {
    use super::find_collocation;

    #[test]
    fn suggests_verb_after_noun_particle() {
        let candidate = find_collocation("資料を").expect("should match 資料を");
        assert_eq!(candidate.key, "資料を");
        assert_eq!(candidate.continuation, "作成する");
        assert_eq!(candidate.text, "資料を作成する");
    }

    #[test]
    fn keeps_the_leading_context() {
        let candidate = find_collocation("きのうの資料を").expect("should match 資料を");
        assert_eq!(candidate.text, "きのうの資料を作成する");
    }

    #[test]
    fn prefers_the_longest_matching_key() {
        // 「会議に」と「ミーティングに」が同時に末尾一致しうる場面
        let candidate = find_collocation("ミーティングに").expect("should match");
        assert_eq!(candidate.key, "ミーティングに");
    }

    #[test]
    fn does_not_fire_on_bare_particles() {
        assert!(find_collocation("を").is_none());
        assert!(find_collocation("に").is_none());
        assert!(find_collocation("で").is_none());
        assert!(find_collocation("へ").is_none());
        assert!(find_collocation("と").is_none());
        assert!(find_collocation("が").is_none());
    }

    #[test]
    fn does_not_fire_on_unrelated_words() {
        assert!(find_collocation("こんにちは").is_none());
        assert!(find_collocation("資料").is_none());
        assert!(find_collocation("").is_none());
        assert!(find_collocation("   ").is_none());
    }

    #[test]
    fn every_key_ends_with_a_case_particle() {
        for key in super::collocation_keys() {
            assert!(
                key.ends_with(['を', 'に', 'で', 'へ', 'と', 'が']),
                "key {key} must end with a case particle"
            );
            assert!(key.chars().count() >= 2, "key {key} must contain a noun");
        }
    }
}

//! Input mode implied by the focused field's InputScope.
//!
//! The mapping is a pure function so it can be tested without COM. `None` means
//! "no opinion": the current input mode is kept.
use windows::Win32::UI::TextServices::{
    InputScope, IS_ADDRESS_CITY, IS_ADDRESS_COUNTRYNAME, IS_ADDRESS_COUNTRYSHORTNAME,
    IS_ADDRESS_FULLPOSTALADDRESS, IS_ADDRESS_POSTALCODE, IS_ADDRESS_STATEORPROVINCE,
    IS_ADDRESS_STREET, IS_ALPHANUMERIC_FULLWIDTH, IS_ALPHANUMERIC_HALFWIDTH, IS_CURRENCY_AMOUNT,
    IS_CURRENCY_AMOUNTANDSYMBOL, IS_DATE_DAY, IS_DATE_DAYNAME, IS_DATE_FULLDATE, IS_DATE_MONTH,
    IS_DATE_MONTHNAME, IS_DATE_YEAR, IS_DIGITS, IS_EMAILNAME_OR_ADDRESS, IS_EMAIL_SMTPEMAILADDRESS,
    IS_EMAIL_USERNAME, IS_FILE_FILENAME, IS_FILE_FULLFILEPATH, IS_FORMULA, IS_FORMULA_NUMBER,
    IS_HIRAGANA, IS_KATAKANA_FULLWIDTH, IS_KATAKANA_HALFWIDTH, IS_LOGINNAME, IS_MAPS,
    IS_NAME_OR_PHONENUMBER, IS_NUMBER, IS_NUMBER_FULLWIDTH, IS_ONECHAR, IS_PERSONALNAME_FULLNAME,
    IS_PERSONALNAME_GIVENNAME, IS_PERSONALNAME_MIDDLENAME, IS_PERSONALNAME_PREFIX,
    IS_PERSONALNAME_SUFFIX, IS_PERSONALNAME_SURNAME, IS_TELEPHONE_AREACODE,
    IS_TELEPHONE_COUNTRYCODE, IS_TELEPHONE_FULLTELEPHONENUMBER, IS_TELEPHONE_LOCALNUMBER,
    IS_TIME_FULLTIME, IS_TIME_HOUR, IS_TIME_MINORSEC, IS_URL,
};

use anyhow::Result;

use super::{input_mode::InputMode, state::IMEState};
use crate::tsf::factory::TextServiceFactory;

/// Scopes that ask for Japanese input.
///
/// The Windows SDK defines no kanji-specific scope, so only the kana scopes can
/// select Japanese input.
#[inline]
fn is_kana_scope(scope: InputScope) -> bool {
    matches!(
        scope,
        IS_HIRAGANA | IS_KATAKANA_FULLWIDTH | IS_KATAKANA_HALFWIDTH
    )
}

/// Scopes that ask for alphanumeric, numeric, or otherwise Latin input.
///
/// `IS_SEARCH` is deliberately absent: search boxes are usually used with
/// Japanese input, and hosts that also declare `IS_URL` still resolve to Latin.
#[inline]
fn is_latin_scope(scope: InputScope) -> bool {
    matches!(
        scope,
        IS_ALPHANUMERIC_HALFWIDTH
            | IS_ALPHANUMERIC_FULLWIDTH
            | IS_URL
            | IS_EMAIL_SMTPEMAILADDRESS
            | IS_EMAIL_USERNAME
            | IS_EMAILNAME_OR_ADDRESS
            | IS_LOGINNAME
            | IS_FILE_FILENAME
            | IS_FILE_FULLFILEPATH
            | IS_NUMBER
            | IS_NUMBER_FULLWIDTH
            | IS_DIGITS
            | IS_TELEPHONE_FULLTELEPHONENUMBER
            | IS_TELEPHONE_COUNTRYCODE
            | IS_TELEPHONE_AREACODE
            | IS_TELEPHONE_LOCALNUMBER
            | IS_CURRENCY_AMOUNT
            | IS_CURRENCY_AMOUNTANDSYMBOL
            | IS_DATE_FULLDATE
            | IS_DATE_MONTH
            | IS_DATE_DAY
            | IS_DATE_YEAR
            | IS_DATE_MONTHNAME
            | IS_DATE_DAYNAME
            | IS_TIME_FULLTIME
            | IS_TIME_HOUR
            | IS_TIME_MINORSEC
            | IS_ADDRESS_FULLPOSTALADDRESS
            | IS_ADDRESS_POSTALCODE
            | IS_ADDRESS_STREET
            | IS_ADDRESS_STATEORPROVINCE
            | IS_ADDRESS_CITY
            | IS_ADDRESS_COUNTRYNAME
            | IS_ADDRESS_COUNTRYSHORTNAME
            | IS_PERSONALNAME_FULLNAME
            | IS_PERSONALNAME_PREFIX
            | IS_PERSONALNAME_GIVENNAME
            | IS_PERSONALNAME_MIDDLENAME
            | IS_PERSONALNAME_SURNAME
            | IS_PERSONALNAME_SUFFIX
            | IS_ONECHAR
            | IS_MAPS
            | IS_NAME_OR_PHONENUMBER
            | IS_FORMULA
            | IS_FORMULA_NUMBER
    )
}

/// Input mode to start with for the given scopes, or `None` to keep the current one.
///
/// A single Japanese scope wins over Latin scopes because hosts commonly add a
/// generic Latin scope next to the specific one.
pub(crate) fn mode_for_scopes(scopes: &[InputScope]) -> Option<InputMode> {
    if scopes.iter().copied().any(is_kana_scope) {
        return Some(InputMode::Kana);
    }
    if scopes.iter().copied().any(is_latin_scope) {
        return Some(InputMode::Latin);
    }
    None
}

impl TextServiceFactory {
    pub(crate) fn follow_input_scope_mode_enabled(&self) -> Result<bool> {
        Ok(IMEState::app_config_snapshot()?
            .app_config()
            .general
            .follow_input_scope_mode)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::UI::TextServices::{IS_DEFAULT, IS_PASSWORD, IS_SEARCH, IS_TEXT};

    #[test]
    fn default_scope_keeps_the_current_mode() {
        assert_eq!(mode_for_scopes(&[IS_DEFAULT]), None);
    }

    #[test]
    fn japanese_scopes_select_kana() {
        assert_eq!(mode_for_scopes(&[IS_HIRAGANA]), Some(InputMode::Kana));
        assert_eq!(
            mode_for_scopes(&[IS_KATAKANA_FULLWIDTH]),
            Some(InputMode::Kana)
        );
        assert_eq!(
            mode_for_scopes(&[IS_KATAKANA_HALFWIDTH]),
            Some(InputMode::Kana)
        );
    }

    #[test]
    fn latin_scopes_select_latin() {
        assert_eq!(mode_for_scopes(&[IS_URL]), Some(InputMode::Latin));
        assert_eq!(
            mode_for_scopes(&[IS_EMAIL_SMTPEMAILADDRESS]),
            Some(InputMode::Latin)
        );
        assert_eq!(mode_for_scopes(&[IS_NUMBER]), Some(InputMode::Latin));
        assert_eq!(mode_for_scopes(&[IS_DIGITS]), Some(InputMode::Latin));
        assert_eq!(
            mode_for_scopes(&[IS_ALPHANUMERIC_HALFWIDTH]),
            Some(InputMode::Latin)
        );
    }

    #[test]
    fn generic_scope_next_to_latin_still_selects_latin() {
        assert_eq!(
            mode_for_scopes(&[IS_DEFAULT, IS_URL]),
            Some(InputMode::Latin)
        );
    }

    #[test]
    fn japanese_scope_wins_over_latin() {
        assert_eq!(
            mode_for_scopes(&[IS_URL, IS_HIRAGANA]),
            Some(InputMode::Kana)
        );
    }

    #[test]
    fn neutral_scopes_keep_the_current_mode() {
        assert_eq!(mode_for_scopes(&[IS_SEARCH]), None);
        assert_eq!(mode_for_scopes(&[IS_PASSWORD]), None);
        assert_eq!(mode_for_scopes(&[IS_TEXT]), None);
        assert_eq!(mode_for_scopes(&[]), None);
    }
}

//! ユーザー辞書の書き出し・読み込みで使うネイティブのファイルダイアログ。
//!
//! 設定画面は WebView なので、保存先・読み込み元の選択とファイル入出力はここで行う。
//! ダイアログは閉じるまで戻らないため、呼び出し側は `spawn_blocking` から呼ぶこと。

use std::ffi::c_void;
use std::io::Read;
use std::path::PathBuf;

use windows::core::{HRESULT, PCWSTR, PWSTR};
use windows::Win32::Foundation::HWND;
use windows::Win32::Globalization::{MultiByteToWideChar, MB_ERR_INVALID_CHARS};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_INPROC_SERVER,
    COINIT_APARTMENTTHREADED,
};
use windows::Win32::UI::Shell::Common::COMDLG_FILTERSPEC;
use windows::Win32::UI::Shell::{
    FileOpenDialog, FileSaveDialog, IFileDialog, IFileOpenDialog, IFileSaveDialog, IShellItem,
    FOS_FILEMUSTEXIST, FOS_OVERWRITEPROMPT, FOS_PATHMUSTEXIST, SIGDN_FILESYSPATH,
};

/// ダイアログを閉じたときの HRESULT（`HRESULT_FROM_WIN32(ERROR_CANCELLED)`）。
const HRESULT_CANCELLED: HRESULT = HRESULT(0x8007_04C7u32 as i32);

/// 読み込みを許すファイルサイズ。10,000 件の TSV でも数百 KB に収まる。
const MAX_FILE_BYTES: u64 = 32 * 1024 * 1024;

pub struct DictionaryFile {
    pub path: String,
    pub contents: String,
}

/// 保存先を選ばせ、渡された内容を書き出す。取り消した場合は `None`。
pub fn save_file(
    owner: Option<HWND>,
    default_file_name: &str,
    filter_name: &str,
    filter_pattern: &str,
    default_extension: &str,
    contents: &[u8],
) -> Result<Option<String>, String> {
    let _apartment = ComApartment::initialize();
    let filter_name = wide(filter_name);
    let filter_pattern = wide(filter_pattern);
    let file_name = wide(default_file_name);
    let extension = wide(default_extension);
    let filters = [COMDLG_FILTERSPEC {
        pszName: PCWSTR(filter_name.as_ptr()),
        pszSpec: PCWSTR(filter_pattern.as_ptr()),
    }];

    let dialog: IFileSaveDialog =
        unsafe { CoCreateInstance(&FileSaveDialog, None, CLSCTX_INPROC_SERVER) }
            .map_err(|error| format!("保存ダイアログを開けませんでした: {error}"))?;
    unsafe {
        dialog
            .SetFileTypes(&filters)
            .and_then(|()| dialog.SetDefaultExtension(PCWSTR(extension.as_ptr())))
            .and_then(|()| dialog.SetFileName(PCWSTR(file_name.as_ptr())))
            .and_then(|()| dialog.SetOptions(FOS_OVERWRITEPROMPT | FOS_PATHMUSTEXIST))
            .map_err(|error| format!("保存ダイアログを初期化できませんでした: {error}"))?;
    }

    let Some(path) = show_dialog(&dialog, owner)? else {
        return Ok(None);
    };
    std::fs::write(&path, contents)
        .map_err(|error| format!("{} に書き出せませんでした: {error}", path.display()))?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

/// 読み込み元を選ばせ、テキストとして読み込む。取り消した場合は `None`。
pub fn read_text(
    owner: Option<HWND>,
    filter_name: &str,
    filter_pattern: &str,
) -> Result<Option<DictionaryFile>, String> {
    let _apartment = ComApartment::initialize();
    let filter_name = wide(filter_name);
    let filter_pattern = wide(filter_pattern);
    let filters = [COMDLG_FILTERSPEC {
        pszName: PCWSTR(filter_name.as_ptr()),
        pszSpec: PCWSTR(filter_pattern.as_ptr()),
    }];

    let dialog: IFileOpenDialog =
        unsafe { CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER) }
            .map_err(|error| format!("読み込みダイアログを開けませんでした: {error}"))?;
    unsafe {
        dialog
            .SetFileTypes(&filters)
            .and_then(|()| dialog.SetOptions(FOS_FILEMUSTEXIST | FOS_PATHMUSTEXIST))
            .map_err(|error| format!("読み込みダイアログを初期化できませんでした: {error}"))?;
    }

    let Some(path) = show_dialog(&dialog, owner)? else {
        return Ok(None);
    };
    let metadata = std::fs::metadata(&path)
        .map_err(|error| format!("{} を読み込めませんでした: {error}", path.display()))?;
    if metadata.len() > MAX_FILE_BYTES {
        return Err(format!(
            "ファイルが大きすぎます（{} MB まで）。",
            MAX_FILE_BYTES / 1024 / 1024
        ));
    }
    let mut bytes = Vec::new();
    std::fs::File::open(&path)
        .and_then(|file| file.take(MAX_FILE_BYTES + 1).read_to_end(&mut bytes))
        .map_err(|error| format!("{} を読み込めませんでした: {error}", path.display()))?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("ファイルが大きすぎます（32 MB まで）。".into());
    }
    let contents = decode_text(bytes).map_err(|error| format!("{}: {error}", path.display()))?;
    Ok(Some(DictionaryFile {
        path: path.to_string_lossy().into_owned(),
        contents,
    }))
}

fn show_dialog(dialog: &IFileDialog, owner: Option<HWND>) -> Result<Option<PathBuf>, String> {
    match unsafe { dialog.Show(owner.unwrap_or_default()) } {
        Ok(()) => {}
        Err(error) if error.code() == HRESULT_CANCELLED => return Ok(None),
        Err(error) => return Err(format!("ファイルダイアログを表示できませんでした: {error}")),
    }

    let item: IShellItem = unsafe { dialog.GetResult() }
        .map_err(|error| format!("選択したファイルを取得できませんでした: {error}"))?;
    let raw = unsafe { item.GetDisplayName(SIGDN_FILESYSPATH) }
        .map_err(|error| format!("ファイルのパスを取得できませんでした: {error}"))?;
    // パス文字列は COM が確保した領域なので、読み終えたら必ず解放する。
    let owned = CoTaskMem(raw);
    let path = unsafe { owned.0.to_string() }
        .map_err(|error| format!("ファイルのパスを読み取れませんでした: {error}"))?;
    Ok(Some(PathBuf::from(path)))
}

/// BOM を優先し、BOM がなければ UTF-8、Windows-31J の順に試す。
fn decode_text(bytes: Vec<u8>) -> Result<String, String> {
    if let Some(rest) = bytes.strip_prefix(&[0xFF, 0xFE]) {
        return decode_utf16(rest, true);
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFE, 0xFF]) {
        return decode_utf16(rest, false);
    }
    if let Some(rest) = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        return String::from_utf8(rest.to_vec())
            .map_err(|_| "UTF-8 の文字コードが不正です。".into());
    }
    match String::from_utf8(bytes) {
        Ok(text) => Ok(text),
        Err(error) => {
            let bytes = error.as_bytes();
            let len = unsafe { MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, bytes, None) };
            if len > 0 {
                let mut units = vec![0; len as usize];
                let written = unsafe {
                    MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, bytes, Some(&mut units))
                };
                if written == len {
                    return String::from_utf16(&units).map_err(|error| error.to_string());
                }
            }
            Err("UTF-8、UTF-16、Shift_JIS（Windows-31J）で読み込めませんでした。".into())
        }
    }
}

fn decode_utf16(bytes: &[u8], little_endian: bool) -> Result<String, String> {
    if bytes.len() % 2 != 0 {
        return Err("UTF-16 のバイト数が奇数です。".to_string());
    }
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|pair| {
            if little_endian {
                u16::from_le_bytes([pair[0], pair[1]])
            } else {
                u16::from_be_bytes([pair[0], pair[1]])
            }
        })
        .collect();
    String::from_utf16(&units).map_err(|_| "UTF-16 として読み込めませんでした。".to_string())
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

/// COM をこのスレッドで初期化した場合だけ、同じスレッドで解除する。
struct ComApartment {
    uninitialize: bool,
}

impl ComApartment {
    fn initialize() -> Self {
        // 既に別のアパートメントで初期化済みのスレッドでも、そのままダイアログは使える。
        let initialized = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }.is_ok();
        Self {
            uninitialize: initialized,
        }
    }
}

impl Drop for ComApartment {
    fn drop(&mut self) {
        if self.uninitialize {
            unsafe { CoUninitialize() };
        }
    }
}

/// COM が確保した文字列を保持し、破棄時に解放する。
struct CoTaskMem(PWSTR);

impl Drop for CoTaskMem {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CoTaskMemFree(Some(self.0 .0 as *const c_void)) };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_text_handles_bom_variants() {
        assert_eq!(
            decode_text("ぜろこん".as_bytes().to_vec()).unwrap(),
            "ぜろこん"
        );
        assert_eq!(
            decode_text([&[0xEF, 0xBB, 0xBF][..], "ぜろこん".as_bytes()].concat()).unwrap(),
            "ぜろこん"
        );
        assert_eq!(
            decode_text([&[0xFF, 0xFE][..], &utf16_bytes("ぜろこん", true)].concat()).unwrap(),
            "ぜろこん"
        );
        assert_eq!(
            decode_text([&[0xFE, 0xFF][..], &utf16_bytes("ぜろこん", false)].concat()).unwrap(),
            "ぜろこん"
        );
    }

    #[test]
    fn decode_text_accepts_windows_31j_but_rejects_broken_encodings() {
        assert_eq!(decode_text(vec![0x82, 0xe6, 0x82, 0xdd]).unwrap(), "よみ");
        assert!(decode_text(vec![0x82]).is_err());
        assert!(decode_text(vec![0xFF, 0xFE, 0x30]).is_err());
    }

    fn utf16_bytes(value: &str, little_endian: bool) -> Vec<u8> {
        value
            .encode_utf16()
            .flat_map(|unit| {
                if little_endian {
                    unit.to_le_bytes()
                } else {
                    unit.to_be_bytes()
                }
            })
            .collect()
    }
}

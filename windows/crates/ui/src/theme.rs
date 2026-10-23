use shared::{AppConfig, ColorTheme};
use tao::event_loop::EventLoopProxy;

use crate::UserEvent;

pub fn initialization_script(theme: ColorTheme) -> String {
    include_str!("theme.js")
        .replace(
            "__COLOR_PALETTES__",
            include_str!("../../shared/themes.json"),
        )
        .replace(
            "__INITIAL_THEME__",
            &serde_json::to_string(&theme).expect("theme is serializable"),
        )
}

// Settings I/O runs independently of typing and the UI event loop. Only an actual
// palette change sends a UI event; metadata checks avoid repeatedly parsing settings.
pub fn watch_settings(
    proxy: EventLoopProxy<UserEvent>,
    initial: ColorTheme,
) -> std::io::Result<()> {
    let Ok(path) = AppConfig::settings_path() else {
        return Ok(());
    };
    std::thread::Builder::new()
        .name("ime-theme-settings".into())
        .spawn(move || {
            let mut previous_stamp = None;
            let mut current = initial;
            loop {
                std::thread::sleep(std::time::Duration::from_secs(1));
                let Ok(metadata) = std::fs::metadata(&path) else {
                    continue;
                };
                let stamp = (metadata.modified().ok(), metadata.len());
                if previous_stamp == Some(stamp) {
                    continue;
                }
                let Ok(config) = AppConfig::read() else {
                    continue;
                };
                previous_stamp = Some(stamp);
                if current != config.appearance.ime_theme {
                    current = config.appearance.ime_theme;
                    if proxy.send_event(UserEvent::UpdateTheme(current)).is_err() {
                        break;
                    }
                }
            }
        })?;
    Ok(())
}

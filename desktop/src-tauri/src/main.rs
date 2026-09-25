// illumera desktop: the web app (../web) in a native window, plus TorrServer as a sidecar so
// torrent sources play without installing anything else. The web app finds TorrServer on
// its default port (127.0.0.1:8090) by itself.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Mutex;
use tauri::{Manager, RunEvent};
use tauri_plugin_shell::{process::CommandChild, ShellExt};

/// The running TorrServer, stopped when the app exits.
struct TorrServer(Mutex<Option<CommandChild>>);

fn start_torrserver(app: &tauri::AppHandle) -> Result<CommandChild, Box<dyn std::error::Error>> {
    // TorrServer keeps its database and settings here; it refuses to start without the folder.
    let data = app.path().app_data_dir()?.join("torrserver");
    std::fs::create_dir_all(&data)?;
    let (_events, child) = app
        .shell()
        .sidecar("torrserver")?
        // Loopback only: the app is the sole client.
        .args(["--port", "8090", "--ip", "127.0.0.1", "--path"])
        .arg(data.to_string_lossy().to_string())
        .spawn()?;
    Ok(child)
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(TorrServer(Mutex::new(None)))
        .setup(|app| {
            // A TorrServer that fails to start only costs torrent playback; the app still runs.
            match start_torrserver(app.handle()) {
                Ok(child) => *app.state::<TorrServer>().0.lock().unwrap() = Some(child),
                Err(e) => eprintln!("TorrServer did not start: {e}"),
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build the illumera window")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(child) = app.state::<TorrServer>().0.lock().unwrap().take() {
                    let _ = child.kill();
                }
            }
        });
}

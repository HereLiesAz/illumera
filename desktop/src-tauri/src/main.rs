// illumera desktop: the web app (../web) in a native window, plus TorrServer as a sidecar so
// torrent sources play without installing anything else. TorrServer prefers 127.0.0.1:8090;
// when that port is taken it uses a free one, and the window is told which through
// `window.__ILLUMERA_TORRSERVER__`, which the web app probes first (streamingServer.ts).
//
// TorrServer's own HTTP auth is not used: its credentials would have to be sent as
// http://user:pass@host URLs, which browsers' fetch() refuses. It listens on loopback only.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::TcpListener;
use std::sync::Mutex;
use tauri::{Manager, RunEvent, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_shell::{process::CommandChild, ShellExt};

/// TorrServer's usual port, and the one the web app falls back to.
const PREFERRED_PORT: u16 = 8090;

/// The running TorrServer, stopped when the app exits (however it exits).
struct TorrServer(Mutex<Option<CommandChild>>);

impl TorrServer {
    fn stop(&self) {
        if let Ok(mut child) = self.0.lock() {
            if let Some(child) = child.take() {
                let _ = child.kill();
            }
        }
    }
}

/// Last resort: if the state is dropped without an exit event (a panic unwinding), still stop it.
impl Drop for TorrServer {
    fn drop(&mut self) {
        self.stop();
    }
}

/// 8090 when it is free, otherwise any free loopback port.
fn pick_port() -> u16 {
    if TcpListener::bind(("127.0.0.1", PREFERRED_PORT)).is_ok() {
        return PREFERRED_PORT;
    }
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .map(|addr| addr.port())
        .unwrap_or(PREFERRED_PORT)
}

fn start_torrserver(app: &tauri::AppHandle, port: u16) -> Result<CommandChild, Box<dyn std::error::Error>> {
    // TorrServer keeps its database and settings here; it refuses to start without the folder.
    let data = app.path().app_data_dir()?.join("torrserver");
    std::fs::create_dir_all(&data)?;
    let (_events, child) = app
        .shell()
        .sidecar("torrserver")?
        // Loopback only: the app is the sole client.
        .args(["--port", &port.to_string(), "--ip", "127.0.0.1", "--path"])
        .arg(data.to_string_lossy().to_string())
        .spawn()?;
    Ok(child)
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(TorrServer(Mutex::new(None)))
        .setup(|app| {
            let port = pick_port();
            // A TorrServer that fails to start only costs torrent playback; the app still runs.
            match start_torrserver(app.handle(), port) {
                Ok(child) => *app.state::<TorrServer>().0.lock().unwrap() = Some(child),
                Err(e) => eprintln!("TorrServer did not start: {e}"),
            }
            // The window is created here (tauri.conf.json sets `create: false`) so it can be
            // told TorrServer's port before the page loads.
            let config = app.config().app.windows.first().cloned().ok_or("no window configured")?;
            let script = format!("window.__ILLUMERA_TORRSERVER__ = 'http://127.0.0.1:{port}';");
            WebviewWindowBuilder::from_config(app.handle(), &config)?
                .initialization_script(&script)
                .build()?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build the illumera window")
        .run(|app, event| match event {
            // Closing the last window, a requested exit and the final exit all stop TorrServer.
            RunEvent::ExitRequested { .. }
            | RunEvent::Exit
            | RunEvent::WindowEvent { event: WindowEvent::Destroyed, .. } => {
                app.state::<TorrServer>().stop();
            }
            _ => {}
        });
}

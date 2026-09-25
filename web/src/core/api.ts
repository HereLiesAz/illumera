/**
 * Base URL of the app's own /api (web/worker). Same origin when served over https by the
 * Worker; otherwise (TV packages on file://, the desktop app on tauri:// or
 * http://tauri.localhost, the Vite dev server) the hosted Worker.
 */
export const HOSTED = 'https://illumera-web.hereliesaz.workers.dev'
export const API_BASE =
  typeof location !== 'undefined' && location.protocol === 'https:' && location.hostname !== 'tauri.localhost' ? '' : HOSTED

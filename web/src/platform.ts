/**
 * The few things that differ on TV platforms. Arrow, Enter and Back keys arrive on their
 * own everywhere; Tizen needs media keys registered, and both TVs exit through their API.
 */

interface TizenApi {
  tvinputdevice?: { registerKey(name: string): void }
  application?: { getCurrentApplication(): { exit(): void } }
}

declare global {
  interface Window { tizen?: TizenApi; webOS?: { platformBack?: () => void } }
}

const TIZEN_MEDIA_KEYS = ['MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaRewind', 'MediaFastForward', 'MediaStop']

export function setUpPlatform(): void {
  const input = window.tizen?.tvinputdevice
  if (input) for (const key of TIZEN_MEDIA_KEYS) { try { input.registerKey(key) } catch { /* unsupported key */ } }
}

/** Leaves the app from its root screen. Browsers have nothing to exit. */
export function exitApp(): boolean {
  try {
    if (window.tizen?.application) { window.tizen.application.getCurrentApplication().exit(); return true }
    if (window.webOS?.platformBack) { window.webOS.platformBack(); return true }
  } catch { /* not available */ }
  return false
}

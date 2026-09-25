/**
 * Base URL of the app's own /api (web/worker). Same origin when hosted; the hosted Worker
 * when running from a TV or desktop package (file:// or an app scheme).
 */
export const HOSTED = 'https://illumera-web.hereliesaz.workers.dev'
export const API_BASE = typeof location !== 'undefined' && /^https?:$/.test(location.protocol) ? '' : HOSTED

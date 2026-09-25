import { defineConfig, type Plugin } from 'vite'
import preact from '@preact/preset-vite'

/**
 * TV packages (webOS, Tizen) load from file://, where Chromium refuses module scripts.
 * `vite build --mode tv` emits one classic script instead: IIFE, hls.js inlined.
 */
function classicScript(): Plugin {
  return {
    name: 'classic-script',
    enforce: 'post',
    transformIndexHtml: (html) =>
      html
        .replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/, '<script defer src="$1"></script>')
        .replace(/ crossorigin(?=[ >])/g, ''),
  }
}

// Chromium 68 is the oldest engine we ship to: LG webOS 5 and Samsung Tizen 5.5 (2020 TVs).
export default defineConfig(({ mode }) => ({
  plugins: mode === 'tv' ? [preact(), classicScript()] : [preact()],
  base: './',
  build: {
    target: 'chrome68',
    cssTarget: 'chrome68',
    outDir: mode === 'tv' ? 'dist-tv' : 'dist',
    // hls.js (~580 kB) loads only when an HLS stream plays; in the TV build it's inlined.
    chunkSizeWarningLimit: mode === 'tv' ? 1200 : 700,
    modulePreload: mode !== 'tv',
    rolldownOptions: mode === 'tv' ? { output: { format: 'iife', codeSplitting: false } } : undefined,
  },
  test: { environment: 'node' },
}))

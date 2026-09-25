import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// Chromium 68 is the oldest engine we ship to: LG webOS 5 and Samsung Tizen 5.5 (2020 TVs).
export default defineConfig({
  plugins: [preact()],
  base: './',
  // hls.js (~580 kB) loads only when an HLS stream plays.
  build: { target: 'chrome68', cssTarget: 'chrome68', chunkSizeWarningLimit: 700 },
  test: { environment: 'node' },
})

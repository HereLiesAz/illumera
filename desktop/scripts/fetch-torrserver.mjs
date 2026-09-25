// Downloads the TorrServer binary Tauri bundles as a sidecar, named for the Rust target
// triple as Tauri expects (src-tauri/binaries/torrserver-<triple>[.exe]).
//
//   node scripts/fetch-torrserver.mjs                 current machine's target
//   node scripts/fetch-torrserver.mjs <target-triple> a specific target (CI cross builds)
//
// TorrServer (YouROK/TorrServer) is GPL-3.0; it runs as a separate process and is fetched
// unmodified from its releases.

import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export const TORRSERVER_VERSION = 'MatriX.145'

const ASSETS = {
  'x86_64-unknown-linux-gnu': 'TorrServer-linux-amd64',
  'aarch64-unknown-linux-gnu': 'TorrServer-linux-arm64',
  'x86_64-pc-windows-msvc': 'TorrServer-windows-amd64.exe',
  'x86_64-apple-darwin': 'TorrServer-darwin-amd64',
  'aarch64-apple-darwin': 'TorrServer-darwin-arm64',
}

const triple = process.argv[2] || execFileSync('rustc', ['--print', 'host-tuple'], { encoding: 'utf8' }).trim()
const asset = ASSETS[triple]
if (!asset) {
  console.error(`No TorrServer build for ${triple}. Known: ${Object.keys(ASSETS).join(', ')}`)
  process.exit(1)
}

const dir = new URL('../src-tauri/binaries/', import.meta.url).pathname
mkdirSync(dir, { recursive: true })
const file = join(dir, `torrserver-${triple}${triple.includes('windows') ? '.exe' : ''}`)
if (existsSync(file)) {
  console.log(`${file} already present`)
  process.exit(0)
}

const url = `https://github.com/YouROK/TorrServer/releases/download/${TORRSERVER_VERSION}/${asset}`
const res = await fetch(url)
if (!res.ok) {
  console.error(`Download failed (${res.status}): ${url}`)
  process.exit(1)
}
writeFileSync(file, Buffer.from(await res.arrayBuffer()))
chmodSync(file, 0o755)
console.log(`Fetched ${asset} (${TORRSERVER_VERSION}) → ${file}`)

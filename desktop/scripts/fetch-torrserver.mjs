// Downloads the TorrServer binary Tauri bundles as a sidecar, named for the Rust target
// triple as Tauri expects (src-tauri/binaries/torrserver-<triple>[.exe]).
//
//   node scripts/fetch-torrserver.mjs                 current machine's target
//   node scripts/fetch-torrserver.mjs <target-triple> a specific target (CI cross builds)
//
// TorrServer (YouROK/TorrServer) is GPL-3.0; it runs as a separate process and is fetched
// unmodified from its releases.

import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const TORRSERVER_VERSION = 'MatriX.145'

const ASSETS = {
  'x86_64-unknown-linux-gnu': 'TorrServer-linux-amd64',
  'aarch64-unknown-linux-gnu': 'TorrServer-linux-arm64',
  'x86_64-pc-windows-msvc': 'TorrServer-windows-amd64.exe',
  'x86_64-apple-darwin': 'TorrServer-darwin-amd64',
  'aarch64-apple-darwin': 'TorrServer-darwin-arm64',
}

// SHA-256 of each release asset, pinned to TORRSERVER_VERSION. Recorded from the release
// download itself (the release publishes no checksums), so they detect a changed or
// tampered download later, not a compromise of the release at the time they were taken.
// Updating the version means recomputing these (`sha256sum` of each asset).
const SHA256 = {
  'TorrServer-linux-amd64': '0819f0d697b778d85feb5d8c06becb2c63b06e7eedbb4690bafb460e220221b4',
  'TorrServer-linux-arm64': 'c491c58b47ef8c2d4756fb94abf1fcadb3e36c1695e74a5ec701413067012352',
  'TorrServer-windows-amd64.exe': '407532e5da6e7676ad274489b322d28f8f1ae7d2e12032e9d6acfadd9b945006',
  'TorrServer-darwin-amd64': 'fcab1a670cb1e3bd44c60a56a70d2cedff904c39a7524aa0e4380679d93be5cf',
  'TorrServer-darwin-arm64': '2b1d47a2b6c57f891413fe19e28439647b3b7f02b751b8a129d848770308e864',
}
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')

const triple = process.argv[2] || execFileSync('rustc', ['--print', 'host-tuple'], { encoding: 'utf8' }).trim()
const asset = ASSETS[triple]
if (!asset) {
  console.error(`No TorrServer build for ${triple}. Known: ${Object.keys(ASSETS).join(', ')}`)
  process.exit(1)
}

// fileURLToPath, not .pathname: on Windows .pathname is "/D:/...", which resolved to D:\D:\...
const dir = fileURLToPath(new URL('../src-tauri/binaries/', import.meta.url))
mkdirSync(dir, { recursive: true })
const file = join(dir, `torrserver-${triple}${triple.includes('windows') ? '.exe' : ''}`)
if (existsSync(file)) {
  if (sha256(readFileSync(file)) === SHA256[asset]) {
    console.log(`${file} already present`)
    process.exit(0)
  }
  console.warn(`${file} does not match the pinned ${asset} hash; fetching it again`)
  rmSync(file)
}

const url = `https://github.com/YouROK/TorrServer/releases/download/${TORRSERVER_VERSION}/${asset}`
const res = await fetch(url)
if (!res.ok) {
  console.error(`Download failed (${res.status}): ${url}`)
  process.exit(1)
}
const bytes = Buffer.from(await res.arrayBuffer())
const actual = sha256(bytes)
if (actual !== SHA256[asset]) {
  console.error(`SHA-256 mismatch for ${asset} (${TORRSERVER_VERSION}): expected ${SHA256[asset]}, got ${actual}. Not writing it.`)
  process.exit(1)
}
writeFileSync(file, bytes)
chmodSync(file, 0o755)
console.log(`Fetched ${asset} (${TORRSERVER_VERSION}) → ${file}`)

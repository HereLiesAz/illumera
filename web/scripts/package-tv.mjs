// Packages the TV build (vite --mode tv → dist-tv/) for LG webOS and Samsung Tizen.
//
//   node scripts/package-tv.mjs webos   → packages/com.hereliesaz.illumera_<version>_all.ipk
//   node scripts/package-tv.mjs tizen   → packages/illumera-<version>-unsigned.wgt
//
// The .ipk installs with `ares-install` on a TV in developer mode. The .wgt still has to be
// signed with a Samsung certificate (Tizen Studio's `tizen package -t wgt -s <profile>`)
// before a TV will install it; see docs/PLATFORMS.md.

import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const target = process.argv[2]
if (target !== 'webos' && target !== 'tizen') {
  console.error('usage: node scripts/package-tv.mjs webos|tizen')
  process.exit(2)
}

const root = new URL('..', import.meta.url).pathname
const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const run = (cmd, args) => execFileSync(cmd, args, { cwd: root, stdio: 'inherit' })

run('npx', ['vite', 'build', '--mode', 'tv'])

const stage = join(root, `build-${target}`)
const out = join(root, 'packages')
rmSync(stage, { recursive: true, force: true })
mkdirSync(out, { recursive: true })
cpSync(join(root, 'dist-tv'), stage, { recursive: true })
cpSync(join(root, 'tv', target), stage, { recursive: true })

if (target === 'webos') {
  const infoPath = join(stage, 'appinfo.json')
  const info = JSON.parse(readFileSync(infoPath, 'utf8'))
  writeFileSync(infoPath, JSON.stringify({ ...info, version }, null, 2))
  run('npx', ['--yes', '-p', '@webos-tools/cli@3', 'ares-package', stage, '--outdir', out, '--no-minify'])
} else {
  const configPath = join(stage, 'config.xml')
  writeFileSync(configPath, readFileSync(configPath, 'utf8').replace(/(<widget[^>]*\sversion=")[^"]*"/, `$1${version}"`))
  const wgt = join(out, `illumera-${version}-unsigned.wgt`)
  rmSync(wgt, { force: true })
  execFileSync('zip', ['-qr', wgt, '.'], { cwd: stage, stdio: 'inherit' })
}

rmSync(stage, { recursive: true, force: true })
console.log(`Packaged ${target} ${version} into ${out}`)
if (!existsSync(out)) process.exit(1)

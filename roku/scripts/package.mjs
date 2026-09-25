// Validates the channel with BrighterScript and zips it for sideloading or publishing:
// packages/illumera-roku-<version>.zip. Sideload it through the Roku's developer mode page;
// the Channel Store needs a package signed on a Roku (Utilities → Packager).

import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
execFileSync('node_modules/.bin/bsc', ['--project', 'bsconfig.json'], { cwd: root, stdio: 'inherit' })
mkdirSync(join(root, 'packages'), { recursive: true })
const zip = join(root, 'packages', `illumera-roku-${version}.zip`)
rmSync(zip, { force: true })
execFileSync('zip', ['-qr', zip, '.'], { cwd: join(root, 'build', 'staging'), stdio: 'inherit' })
console.log(`Packaged ${zip}`)

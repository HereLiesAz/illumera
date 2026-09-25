// Runs the BrightScript unit tests (parser and ranking) in the brs interpreter.
import { execFileSync } from 'node:child_process'

const out = execFileSync('node_modules/.bin/brs', ['src/source/Parser.brs', 'src/source/Sorting.brs', 'test/parser_test.brs'], { encoding: 'utf8' })
process.stdout.write(out)
if (!out.includes('ALL PASSED')) process.exit(1)

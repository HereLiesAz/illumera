import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { torrentInfo } from '../src/bencode.js'
import { normalizeTitle, videoFiles, imdbIdOf } from '../src/ia.js'
import { streamHandler, catalogHandler, metaHandler, MANIFEST } from '../src/addon.js'
import { parsePath } from '../src/index.js'

const enc = new TextEncoder()
function bencode(v) {
  if (typeof v === 'number') return `i${v}e`
  if (typeof v === 'string') return `${enc.encode(v).length}:${v}`
  if (Array.isArray(v)) return `l${v.map(bencode).join('')}e`
  return `d${Object.keys(v).sort().map((k) => bencode(k) + bencode(v[k])).join('')}e`
}

const INFO = {
  name: 'film',
  'piece length': 16384,
  pieces: 'x'.repeat(20),
  files: [
    { length: 10, path: ['film.mkv'] },
    { length: 5, path: ['film.mp4'] },
  ],
}
const TORRENT = enc.encode(bencode({ announce: 'http://bt1.archive.org:6969/announce', 'announce-list': [['http://bt2.archive.org:6969/announce']], info: INFO }))

test('torrentInfo hashes the exact info bytes and lists files and trackers', async () => {
  const t = await torrentInfo(TORRENT)
  const expected = createHash('sha1').update(bencode(INFO)).digest('hex')
  assert.equal(t.infoHash, expected)
  assert.deepEqual(t.files.map((f) => f.path), ['film.mkv', 'film.mp4'])
  assert.deepEqual(t.trackers, ['http://bt1.archive.org:6969/announce', 'http://bt2.archive.org:6969/announce'])
})

test('normalizeTitle ignores case, punctuation, accents and a leading "The"', () => {
  assert.equal(normalizeTitle('The Cabinet of Dr. Caligari'), normalizeTitle('cabinet of dr caligari'))
  assert.equal(normalizeTitle('Amélie & Co'), 'amelieandco')
})

test('imdbIdOf reads urn:imdb external identifiers', () => {
  assert.equal(imdbIdOf({ 'external-identifier': ['urn:oclc:1', 'urn:imdb:tt0063350'] }), 'tt0063350')
  assert.equal(imdbIdOf({}), null)
})

test('videoFiles keeps each original plus a compatible re-encode, best resolution first', () => {
  const files = videoFiles({ files: [
    { name: 'film.mkv', source: 'original', format: 'h.264 MPEG4', size: '9000', height: '1080' },
    { name: 'film.mp4', source: 'derivative', original: 'film.mkv', format: 'h.264', size: '3000', height: '480' },
    { name: 'film.ogv', source: 'derivative', original: 'film.mkv', format: 'Ogg Video', size: '2500', height: '300' },
    { name: 'film_512kb.mp4', source: 'derivative', original: 'film.mkv', format: '512Kb MPEG4', size: '2000', height: '240' },
    { name: 'menu.mpg', source: 'original', format: 'MPEG2', size: '10', height: '480' },
    { name: 'cover.jpg', source: 'original', format: 'JPEG', size: '50' },
  ] })
  assert.deepEqual(files.map((f) => f.name), ['film.mkv', 'film.mp4'])
})

function stubs({ search = [], meta = {}, cinemeta }) {
  return {
    fetchJson: async (url) => {
      if (url.includes('v3-cinemeta')) return cinemeta
      if (url.includes('advancedsearch')) return { response: { docs: search } }
      const id = decodeURIComponent(url.split('/metadata/')[1])
      return meta[id]
    },
    fetchBytes: async () => TORRENT,
  }
}

const filmMeta = { metadata: { title: 'Nosferatu', licenseurl: 'http://creativecommons.org/licenses/publicdomain/' }, files: [
  { name: 'film.mkv', source: 'original', format: 'h.264 MPEG4', size: '9000', height: '1080' },
  { name: 'film.mp4', source: 'derivative', original: 'film.mkv', format: 'h.264', size: '3000', height: '480' },
] }

test('movie streams come only from title/year matches, as HTTP and torrent pairs', async () => {
  const f = stubs({
    cinemeta: { meta: { name: 'Nosferatu', releaseInfo: '1922' } },
    search: [
      { identifier: 'nosf', title: 'Nosferatu', year: 1922, downloads: 500 },
      { identifier: 'remake', title: 'Nosferatu the Vampyre', year: 1979 },
      { identifier: 'other', title: 'Nosferatu', year: 1979 },
    ],
    meta: { nosf: filmMeta },
  })
  const { streams } = await streamHandler({ type: 'movie', id: 'tt0013442' }, f)
  assert.equal(streams.length, 4)
  assert.equal(streams[0].url, 'https://archive.org/download/nosf/film.mkv')
  assert.equal(streams[1].fileIdx, 0)
  assert.equal(streams[3].fileIdx, 1)
  assert.match(streams[1].sources[0], /^tracker:http:\/\/bt1\.archive\.org/)
})

test('an item declaring a different IMDb id is never matched', async () => {
  const f = stubs({
    cinemeta: { meta: { name: 'Nosferatu', releaseInfo: '1922' } },
    search: [{ identifier: 'nosf', title: 'Nosferatu', year: 1922, 'external-identifier': 'urn:imdb:tt9999999' }],
    meta: { nosf: filmMeta },
  })
  assert.deepEqual((await streamHandler({ type: 'movie', id: 'tt0013442' }, f)).streams, [])
})

test('episodes match on show and episode title', async () => {
  const f = stubs({
    cinemeta: { meta: { name: 'The Beverly Hillbillies', videos: [{ season: 1, episode: 2, name: 'Getting Settled' }] } },
    search: [
      { identifier: 'ep2', title: 'Beverly Hillbillies Ep02 Getting Settled' },
      { identifier: 'ep1', title: 'Beverly Hillbillies Ep01 The Clampetts Strike Oil' },
    ],
    meta: { ep2: filmMeta },
  })
  const { streams } = await streamHandler({ type: 'series', id: 'tt0055662:1:2' }, f)
  assert.ok(streams.length > 0)
  assert.ok(streams.every((s) => (s.url ?? '').includes('/ep2/') || s.infoHash))
})

test('search collapses duplicate uploads and prefers IMDb ids', async () => {
  const f = stubs({ search: [
    { identifier: 'a', title: 'Nosferatu', 'external-identifier': 'urn:imdb:tt0013442' },
    { identifier: 'b', title: 'Nosferatu', 'external-identifier': 'urn:imdb:tt0013442' },
    { identifier: 'c', title: 'Metropolis' },
  ] })
  const { metas } = await catalogHandler({ type: 'movie', id: 'openarchive-films', extra: { search: 'x' } }, f)
  assert.deepEqual(metas.map((m) => m.id), ['tt0013442', 'ia:c'])
})

test('meta describes ia: items and leaves tt ids to Cinemeta', async () => {
  const f = stubs({ meta: { c: { metadata: { title: 'Metropolis', year: '1927', description: '<b>Silent</b> classic' } } } })
  assert.equal((await metaHandler({ type: 'movie', id: 'tt1' }, f)).meta, null)
  const { meta } = await metaHandler({ type: 'movie', id: 'ia:c' }, f)
  assert.equal(meta.name, 'Metropolis')
  assert.equal(meta.description, '&lt;b&gt;Silent&lt;/b&gt; classic')
  // Archive descriptions are escaped rather than partially stripped:
  // malicious HTML-like content must remain inert downstream.
  const hostile = stubs({ meta: { unsafe: { metadata: {
    title: 'Unsafe', description: '<img src=x onerror=alert(1)>&hello'
  } } } })
  const { meta: safe } = await metaHandler({ type: 'movie', id: 'ia:unsafe' }, hostile)
  assert.equal(safe.description, '&lt;img src=x onerror=alert(1)&gt;&amp;hello')
})

test('routes parse ids with colons and search extras', () => {
  assert.deepEqual(parsePath('/stream/series/tt0055662%3A1%3A2.json'), { resource: 'stream', type: 'series', id: 'tt0055662:1:2', extra: {} })
  assert.deepEqual(parsePath('/catalog/movie/openarchive-films/search=night%20of.json').extra, { search: 'night of' })
  assert.equal(parsePath('/nope'), null)
  assert.ok(MANIFEST.idPrefixes.includes('ia:'))
})

test('parsePath returns null for malformed percent-encoding instead of throwing', () => {
  assert.equal(parsePath('/stream/movie/%E0%A4%A.json'), null)
})

test('streamHandler rejects ids that are not IMDb ids without fetching', async () => {
  const fetchJson = async () => { throw new Error('must not fetch') }
  const out = await streamHandler({ type: 'movie', id: '..%2F..%2Fx' }, { fetchJson, fetchBytes: fetchJson })
  assert.deepEqual(out, { streams: [] })
})

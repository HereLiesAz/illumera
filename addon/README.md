# Open Archive — Stremio addon

Streams and search from the Internet Archive's public-domain and openly licensed film and TV collections. Each item is offered as a direct HTTP stream and as a torrent (Archive-seeded, webseeded).

## Resources

| Resource | Ids | Behavior |
|---|---|---|
| `stream` | `tt…`, `tt…:S:E`, `ia:…` | Cinemeta title/year (and episode title) matched against curated collections; items declaring a different IMDb id are excluded. |
| `catalog` | `openarchive-films`, `openarchive-tv` | Search only (`search` extra required). Results with an IMDb id use it; others use `ia:{identifier}`. |
| `meta` | `ia:…` | Archive metadata. `tt` ids are left to Cinemeta. |

Collections: see `COLLECTIONS` in `src/ia.js`.

## Develop

~~~sh
npm test          # offline unit tests
npx wrangler dev  # local Worker at http://localhost:8787/manifest.json
~~~

## Deploy

~~~sh
npx wrangler login
npm run deploy
~~~

Install in Stremio or Illumera with `https://open-archive-addon.<subdomain>.workers.dev/manifest.json`.

In Illumera, only the streams and metas are used: its search is Cinemeta's, and a catalog with a required `search` extra is skipped on the Movies and Series screens and gives nothing on Home, so these catalogs don't appear. Stremio shows them in its search.

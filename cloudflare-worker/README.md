# Crash report worker

Receives crash reports POSTed by the app's ACRA integration
(`LumeraApplication.kt`) and relays them as **GitHub issues** on this repo —
the device never authenticates to GitHub, only this worker does, using a
token that lives solely in its Cloudflare secrets.

Reports for the same underlying bug are deduplicated: a short hash of the
exception type and top stack frames becomes a `crash-<hash>` label. A repeat
of a known crash is added as a comment on the existing open issue instead of
opening a duplicate.

An email relay via [Resend](https://resend.com) is also supported and runs
independently — configure either GitHub, email, or both. `AUTH_TOKEN` is
required in every environment; the worker fails closed with HTTP 503 if it is
missing.

## Setup

1. **GitHub token** — create a
   [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new)
   scoped to this repo only, with **Issues: Read and write** permission and
   nothing else.
2. **Deploy**: `npx wrangler deploy` (from this directory).
3. **Secrets**:
   ```bash
   npx wrangler secret put GITHUB_TOKEN     # the token from step 1
   npx wrangler secret put AUTH_TOKEN       # required shared secret the app sends
   ```
   `wrangler.toml`'s `[vars]` block already points `GITHUB_OWNER`/`GITHUB_REPO`
   at `HereLiesAz/illumera` — change it there if you fork this.
4. **Optional email relay**:
   ```bash
   npx wrangler secret put RESEND_API_KEY
   npx wrangler secret put REPORT_EMAIL
   ```
5. **Point the app at the worker** — the deployed URL (`.../crash-report`)
   and the `AUTH_TOKEN` from step 3 go into:
   - `local.properties` as `acra.url` / `acra.token` for local builds, or
   - the `ACRA_URL` / `ACRA_TOKEN` GitHub Actions secrets on this repo for
     CI-built releases (see `../ci/README.md`).

## Local testing

Authentication is still required when testing locally. Use a throwaway local
secret rather than disabling authentication, for example:

```bash
npx wrangler dev --var AUTH_TOKEN:local-crash-test-token
```

Configure the local app build to send the same value as `acra.token`. Do not
reuse a production `AUTH_TOKEN` for local development or commit either value
to the repository.

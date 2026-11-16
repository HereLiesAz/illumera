# Lessons

- The central Play release runs `:app:testDebugUnitTest`; a green PR check is not a green release. After a merge, read the `central-run-failure` issue and the failed job's log in HereLiesAz/workflows before assuming the build is fine.
- Screens and scaffolds that Compose unit tests render directly (`DetailsScreen`, `BasePlayerScaffold`) must not call `hiltViewModel()` for new data: the tests have no Hilt and fail with `IllegalStateException`. Pass new state and callbacks as defaulted parameters from `MainActivity` instead.
- Nothing here compiles locally (no Android SDK). Say so, and treat CI as the first compile, not the last.

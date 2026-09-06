# Theming

illumera ships a set of built-in color themes and a full custom theme
editor. Each user profile picks a theme independently.

## Data model

`ThemeEntity` (`app/src/main/java/com/hereliesaz/illumera/data/model/ThemeEntity.kt`)
is a Room entity with:

| Field | Purpose |
|-------|---------|
| `id` | Primary key, e.g. `"void"`, `"illumera"`, or a generated UUID for custom themes |
| `name` | Display name |
| `primaryColor`, `backgroundColor`, `surfaceColor`, `textColor`, `textMutedColor`, `errorColor` | ARGB `Long` values (e.g. `0xFFFF2E7A`) |
| `isBuiltIn` | `true` for the presets below; built-ins can't be deleted from the UI |
| `category` | `"dark"`, `"colorful"`, or `"custom"` — used for grouping in the picker |

## Built-in themes

Defined as constants in `ui/theme/DefaultThemes.kt`, in `DefaultThemes.ALL`:

`Void`, `Illumera`, `Neon`, `Ocean`, `Sunset`, `Emerald`, `Amber`, `Crimson`,
`Slate`.

`Illumera` is the app's own brand theme, built from the logo's palette (see
[`BRANDING.md`](BRANDING.md)) — pink primary, orange-tinted muted text,
deep magenta-black background. It's the default theme: new profiles start
on it (`ProfileEntity.themeId` defaults to `"illumera"`), and it's the
fallback used before a profile's theme has resolved or for an unrecognized
theme id (`DefaultThemes.getById`). `Void` (pure black/white) is just
another built-in preset, no longer the fallback.

### Adding a built-in theme

1. Add a new `val` in `DefaultThemes.kt` following the existing pattern, and
   append it to `DefaultThemes.ALL` (position in the list controls display
   order — nothing else depends on the list's length or ordering, it's
   consumed via `.size`/iteration everywhere).
2. That's it — no Room migration needed. `ThemeManager.seedBuiltInThemes()`
   inserts any `DefaultThemes.ALL` entry whose `id` isn't already in the
   `themes` table, on every app start, so existing installs pick up new
   built-ins automatically the next time they launch.

## Runtime resolution

`ThemeManager` (`ui/theme/ThemeManager.kt`, a `@HiltViewModel`):

- `availableThemes: StateFlow<List<ThemeEntity>>` — built-ins plus any
  custom themes from the database (`AddonDao.getAllThemes()`), built-ins
  always listed first and never shadowed by a same-`id` DB row.
- `currentTheme: StateFlow<ThemeEntity>` — resolved for whichever profile
  is active via `setCurrentProfile(profileId, themeId)`; falls back to
  `DefaultThemes.ILLUMERA` via `resetTheme()`.

`LumeraTheme` (`ui/theme/Theme.kt`) is the Compose `MaterialTheme` wrapper
that turns a `ThemeEntity` into actual Compose `ColorScheme`/`Typography`;
every screen composes inside it (see `MainActivity.kt`).

## Custom themes

Users can create their own themes in `ui/settings/ThemeEditorScreen.kt`,
persisted as `ThemeEntity` rows with `isBuiltIn = false` and
`category = "custom"`. The profile creation flow
(`ui/profiles/ProfileScreen.kt`, `ProfileViewModel.kt`) lets a new profile
preview and pick from the full combined list (built-in + custom) before
saving `themeId` onto the `ProfileEntity`.

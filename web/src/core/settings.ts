import { DEFAULT_SORT_PREFS, type SortPrefs } from './sorting'
import { Stored } from './storage'

export interface Settings {
  sort: SortPrefs
  /** ISO 639-1 code of the subtitle language to turn on automatically; '' = off. */
  subtitleLanguage: string
  autoplayNext: boolean
  /** On a playback error, try the next ranked source. */
  autoFallback: boolean
  /** Jump past intros IntroDB knows about, instead of only offering a Skip button. */
  autoSkipIntro: boolean
}

const DEFAULTS: Settings = { sort: DEFAULT_SORT_PREFS, subtitleLanguage: '', autoplayNext: true, autoFallback: true, autoSkipIntro: false }

class SettingsStore {
  readonly value = new Stored<Settings>('settings', DEFAULTS)

  get(): Settings {
    const v = this.value.get()
    // Settings saved by an older version may lack newer keys.
    return { ...DEFAULTS, ...v, sort: { ...DEFAULTS.sort, ...(v.sort ?? {}) } }
  }

  sortPrefs(): SortPrefs { return this.get().sort }

  update(patch: Partial<Settings>): void { this.value.set({ ...this.get(), ...patch }) }

  updateSort(patch: Partial<SortPrefs>): void { this.update({ sort: { ...this.get().sort, ...patch } }) }
}

export const settings = new SettingsStore()

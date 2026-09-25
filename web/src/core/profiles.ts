import { activeProfileId, clearProfile, DEFAULT_PROFILE, setActiveProfileId, Stored } from './storage'

/**
 * Profiles: each keeps its own addons, settings, progress and accounts (Stremio, Trakt,
 * debrid). The device's streaming server is shared.
 */

export interface Profile { id: string; name: string }

export const profiles = new Stored<Profile[]>('profiles', [{ id: DEFAULT_PROFILE, name: 'Me' }], true)

export function activeProfile(): Profile {
  const id = activeProfileId()
  return profiles.get().find((p) => p.id === id) ?? profiles.get()[0]
}

/** Switches profile. Stores load at startup, so the app reloads into the new one. */
export function switchProfile(id: string, reload: () => void = () => location.reload()): void {
  if (id === activeProfileId()) return
  setActiveProfileId(id)
  reload()
}

export function addProfile(name: string): Profile {
  const profile = { id: `p${Date.now().toString(36)}`, name: name.trim() || 'Profile' }
  profiles.set(profiles.get().concat(profile))
  return profile
}

export function renameProfile(id: string, name: string): void {
  profiles.set(profiles.get().map((p) => (p.id === id ? { ...p, name: name.trim() || p.name } : p)))
}

/** Deletes a profile and everything stored for it. The default and the active profile stay. */
export function deleteProfile(id: string): void {
  if (id === DEFAULT_PROFILE || id === activeProfileId()) return
  clearProfile(id)
  profiles.set(profiles.get().filter((p) => p.id !== id))
}

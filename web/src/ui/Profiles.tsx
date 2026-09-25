import { useEffect, useRef, useState } from 'preact/hooks'
import { activeProfile, addProfile, deleteProfile, profiles, renameProfile, switchProfile } from '../core/profiles'
import { DEFAULT_PROFILE } from '../core/storage'
import { useStored } from './hooks'
import { navigate } from './router'
import { focusFirst } from './spatial'

const CHOSEN_KEY = 'illumera:profile-chosen'

/** Whether to ask "who's watching": more than one profile and none chosen this session. */
export function shouldPickProfile(): boolean {
  let chosen = false
  try { chosen = sessionStorage.getItem(CHOSEN_KEY) === '1' } catch { /* unavailable */ }
  return profiles.get().length > 1 && !chosen
}

function markChosen(): void {
  try { sessionStorage.setItem(CHOSEN_KEY, '1') } catch { /* unavailable */ }
}

/** "Who's watching": pick, add, rename or delete profiles. */
export function Profiles() {
  const list = useStored(profiles)
  const active = activeProfile()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState('')
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => { focusFirst(ref.current) }, [])

  const pick = (id: string) => {
    markChosen()
    if (id === active.id) navigate({ name: 'home' })
    else switchProfile(id, () => { location.hash = '#/'; location.reload() })
  }

  return (
    <div ref={ref}>
      <h1>Who’s watching?</h1>
      <div class="actions">
        {list.map((p) => (
          <div key={p.id} class="hstack">
            <button class={`btn${p.id === active.id ? ' primary' : ''}`} onClick={() => pick(p.id)}>{p.name}</button>
            {editing && (
              <>
                <button class="btn small" onClick={() => { const n = prompt('Name', p.name); if (n) renameProfile(p.id, n) }}>Rename</button>
                {p.id !== DEFAULT_PROFILE && p.id !== active.id && <button class="btn small" onClick={() => deleteProfile(p.id)}>Delete</button>}
              </>
            )}
          </div>
        ))}
      </div>
      <form class="hstack" onSubmit={(e) => { e.preventDefault(); if (name.trim()) { addProfile(name); setName('') } }}>
        <input class="field" style={{ maxWidth: '16rem' }} placeholder="New profile name" value={name}
          onInput={(e) => setName((e.target as HTMLInputElement).value)} />
        <button class="btn" type="submit" disabled={!name.trim()}>Add profile</button>
        <button class="btn" type="button" onClick={() => setEditing(!editing)}>{editing ? 'Done' : 'Manage'}</button>
      </form>
      <p class="muted" style={{ maxWidth: '40rem' }}>
        Each profile has its own addons, settings, Continue Watching and accounts. Deleting a profile removes all of that from this browser.
      </p>
    </div>
  )
}

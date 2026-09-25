/**
 * D-pad navigation for TV remotes. Arrow keys move focus to the nearest focusable element
 * in that direction; Enter clicks; Back keys call the registered handler. Mouse, touch and
 * Tab keep working as in any web page.
 *
 * Focusable: buttons, links, inputs, and anything with tabindex >= 0. A container with
 * data-nav-scope="…" confines arrow navigation while it is open (dialogs, the player).
 */

type Dir = 'left' | 'right' | 'up' | 'down'

const KEYS: Record<string, Dir> = {
  ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
  Left: 'left', Right: 'right', Up: 'up', Down: 'down',
}
const KEY_CODES: Record<number, Dir> = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' }
// Back: Escape/Backspace in browsers, 10009 on Tizen, 461 on webOS.
const BACK_KEY_CODES = [27, 8, 10009, 461]

const SELECTOR = 'button:not([disabled]), a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

let backHandlers: Array<() => boolean> = []

/** Registers a Back handler; the newest runs first and returns true when it handled Back. */
export function onBack(handler: () => boolean): () => void {
  backHandlers = [handler, ...backHandlers]
  return () => { backHandlers = backHandlers.filter((h) => h !== handler) }
}

function visible(el: HTMLElement): boolean {
  const r = el.getBoundingClientRect()
  return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'
}

function scope(): ParentNode {
  const scopes = document.querySelectorAll<HTMLElement>('[data-nav-scope]')
  return scopes.length ? scopes[scopes.length - 1] : document
}

export function candidates(root: ParentNode = scope()): HTMLElement[] {
  return Array.prototype.filter.call(root.querySelectorAll<HTMLElement>(SELECTOR), visible) as HTMLElement[]
}

/**
 * Nearest candidate in a direction: must lie beyond the current edge; scored by distance
 * along the axis plus twice the offset across it, so a straight line beats a diagonal.
 */
export function nearest(from: DOMRect, dir: Dir, rects: Array<{ rect: DOMRect; index: number }>): number {
  let best = -1
  let bestScore = Infinity
  const cx = from.left + from.width / 2
  const cy = from.top + from.height / 2
  for (const { rect: r, index } of rects) {
    const rx = r.left + r.width / 2
    const ry = r.top + r.height / 2
    let along: number
    let across: number
    switch (dir) {
      case 'left': along = from.left - r.right; across = Math.abs(ry - cy); break
      case 'right': along = r.left - from.right; across = Math.abs(ry - cy); break
      case 'up': along = from.top - r.bottom; across = Math.abs(rx - cx); break
      default: along = r.top - from.bottom; across = Math.abs(rx - cx)
    }
    if (along < -1) continue
    const score = Math.max(along, 0) + across * 2
    if (score < bestScore) { bestScore = score; best = index }
  }
  return best
}

export function move(dir: Dir): boolean {
  const list = candidates()
  if (!list.length) return false
  const current = document.activeElement as HTMLElement | null
  if (!current || list.indexOf(current) < 0) { list[0].focus(); return true }
  const from = current.getBoundingClientRect()
  const rects = list.filter((el) => el !== current).map((el) => ({ rect: el.getBoundingClientRect(), index: list.indexOf(el) }))
  const i = nearest(from, dir, rects)
  if (i < 0) return false
  list[i].focus()
  list[i].scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
  return true
}

export function installSpatialNavigation(): void {
  document.addEventListener('keydown', (e) => {
    const target = e.target as HTMLElement
    const typing = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
    if (BACK_KEY_CODES.indexOf(e.keyCode) >= 0 && !(typing && e.keyCode === 8)) {
      for (const h of backHandlers) if (h()) { e.preventDefault(); return }
      if (e.keyCode !== 8 && history.length > 1) { e.preventDefault(); history.back() }
      return
    }
    // Enter on a non-native focusable (a card) clicks it, as Enter does on a button.
    if (e.keyCode === 13 && !typing && !/^(BUTTON|A|SELECT)$/.test(target.tagName) && target.tabIndex >= 0) {
      e.preventDefault()
      target.click()
      return
    }
    const dir = KEYS[e.key] || KEY_CODES[e.keyCode]
    if (!dir) return
    // In a text field, left/right move the caret.
    if (typing && (dir === 'left' || dir === 'right')) return
    if (move(dir)) e.preventDefault()
  })
}

/** Focuses the first candidate inside an element (after it renders). */
export function focusFirst(root: ParentNode | null): void {
  if (!root) return
  const first = candidates(root)[0]
  if (first) first.focus()
}

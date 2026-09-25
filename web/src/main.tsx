import { render } from 'preact'
import { App } from './app'
import { installSpatialNavigation } from './ui/spatial'
import { setUpPlatform } from './platform'
import './styles.css'

/** Flexbox gap needs Chromium 84; styles.css falls back to margins without it. */
function detectFlexGap(): boolean {
  const probe = document.createElement('div')
  probe.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden'
  probe.appendChild(document.createElement('div'))
  probe.appendChild(document.createElement('div'))
  document.body.appendChild(probe)
  const supported = probe.scrollHeight === 1
  probe.remove()
  return supported
}

if (!detectFlexGap()) document.documentElement.classList.add('no-flex-gap')
setUpPlatform()
installSpatialNavigation()
render(<App />, document.getElementById('app')!)

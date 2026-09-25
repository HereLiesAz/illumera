import { render } from 'preact'
import { App } from './app'
import { installSpatialNavigation } from './ui/spatial'
import './styles.css'

installSpatialNavigation()
render(<App />, document.getElementById('app')!)

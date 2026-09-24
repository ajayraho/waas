import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource/instrument-serif/400.css'
import '@fontsource/instrument-serif/400-italic.css'
import '@fontsource-variable/geist'
import '@fontsource-variable/geist-mono'
import './index.css'
import App from './App'
import { StompProvider } from './lib/StompProvider'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StompProvider>
      <App />
    </StompProvider>
  </StrictMode>,
)

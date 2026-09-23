import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
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

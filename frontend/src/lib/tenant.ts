import { useSyncExternalStore } from 'react'

// The business ("tenant") this dashboard acts for, identified by its API key.
// In a real product this comes from the business's login; for the demo it's a field you can
// change (try demo-api-key-002 to see a different business's waitlists).

export const DEMO_KEYS = ['demo-api-key-001', 'demo-api-key-002']
const STORAGE_KEY = 'waas.apiKey'

let apiKey = DEMO_KEYS[0]
try {
  apiKey = localStorage.getItem(STORAGE_KEY) || apiKey
} catch {
  /* private mode etc. */
}
const listeners = new Set<() => void>()

export const tenant = {
  key: () => apiKey,
  setKey(next: string) {
    apiKey = next.trim()
    try {
      localStorage.setItem(STORAGE_KEY, apiKey)
    } catch {
      /* not critical: the key just won't survive a reload */
    }
    listeners.forEach((l) => l())
  },
}

export function useApiKey() {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => apiKey,
  )
}

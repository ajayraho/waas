import { useSyncExternalStore } from 'react'

// A tiny router: two routes don't need a library.
//   /          → the waitlist directory
//   /w/:id     → one waitlist's live queue
// pushState changes the URL without a reload; popstate fires on back/forward.
// nginx serves index.html for every path (try_files), so a refresh on /w/:id works too.

const listeners = new Set<() => void>()

function subscribe(onChange: () => void) {
  listeners.add(onChange)
  window.addEventListener('popstate', onChange)
  return () => {
    listeners.delete(onChange)
    window.removeEventListener('popstate', onChange)
  }
}

export function usePath() {
  return useSyncExternalStore(subscribe, () => window.location.pathname)
}

export function navigate(to: string) {
  if (to === window.location.pathname) return
  window.history.pushState(null, '', to)
  window.scrollTo({ top: 0 })
  listeners.forEach((l) => l())
}

const WAITLIST_ROUTE = /^\/w\/([0-9a-f-]{36})\/?$/i

/** "/w/<uuid>" → the uuid, anything else → null */
export function waitlistIdFrom(path: string): string | null {
  return WAITLIST_ROUTE.exec(path)?.[1] ?? null
}

export const waitlistPath = (id: string) => `/w/${id}`

import { useSyncExternalStore } from 'react'
import type { AppUser } from '../api/types'

// Tokens for every user this browser created or logged in as (the demo acts on behalf of many
// people at once). "me" is the signed-in account and survives a reload.

const tokens = new Map<string, string>()
let me: { user: AppUser; token: string } | null = null
const listeners = new Set<() => void>()

try {
  const saved = localStorage.getItem('waas.me')
  if (saved) {
    me = JSON.parse(saved)
    if (me) tokens.set(me.user.id, me.token)
  }
} catch {
  /* private mode etc. */
}

function emit() {
  listeners.forEach((l) => l())
}

export const session = {
  remember(user: AppUser, token: string) {
    tokens.set(user.id, token)
  },
  tokenFor(userId: string | null | undefined) {
    return userId ? tokens.get(userId) : undefined
  },
  canActFor(userId: string | null | undefined) {
    return !!userId && tokens.has(userId)
  },
  me: () => me,
  signIn(user: AppUser, token: string) {
    me = { user, token }
    tokens.set(user.id, token)
    try {
      localStorage.setItem('waas.me', JSON.stringify(me))
    } catch {
      /* ignore */
    }
    emit()
  },
  signOut() {
    me = null
    try {
      localStorage.removeItem('waas.me')
    } catch {
      /* ignore */
    }
    emit()
  },
  subscribe(l: () => void) {
    listeners.add(l)
    return () => listeners.delete(l)
  },
}

export function useMe() {
  return useSyncExternalStore(session.subscribe, session.me)
}

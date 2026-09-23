import { createContext, useContext, useEffect, useState } from 'react'

export type SocketState = 'connecting' | 'connected' | 'disconnected'

export type Handler = (body: string) => void

export interface StompApi {
  state: SocketState
  /** Subscribe for as long as the caller wants, across reconnects. Returns an unsubscribe fn. */
  subscribe: (destination: string, handler: Handler) => () => void
}

export const StompContext = createContext<StompApi | null>(null)

export function useStomp(): StompApi {
  const ctx = useContext(StompContext)
  if (!ctx) throw new Error('useStomp must be used inside <StompProvider>')
  return ctx
}

/**
 * Live JSON at `/topic/...`: initial state from the matching `/app/...` one-shot, then every push.
 * Returns null until the first message for the *current* destination arrives.
 */
export function useLiveJson<T>(topic: string | null): T | null {
  const { subscribe } = useStomp()
  const [latest, setLatest] = useState<{ topic: string; value: T } | null>(null)

  useEffect(() => {
    if (!topic) return
    const onMessage = (body: string) => {
      try {
        const value = JSON.parse(body) as T & { error?: string }
        if (value && !value.error) setLatest({ topic, value })
      } catch {
        /* ignore malformed frames */
      }
    }
    const offTopic = subscribe(topic, onMessage)
    const offInitial = subscribe(topic.replace(/^\/topic\//, '/app/'), onMessage)
    return () => {
      offTopic()
      offInitial()
    }
  }, [topic, subscribe])

  return latest && latest.topic === topic ? latest.value : null
}

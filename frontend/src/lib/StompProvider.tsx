import { Client, type StompSubscription } from '@stomp/stompjs'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { StompContext, type Handler, type SocketState } from './stomp'

interface Desired {
  destination: string
  handler: Handler
  live?: StompSubscription
}

/**
 * One STOMP connection for the whole app (same origin: `/ws` is proxied to the gateway).
 *
 * stompjs forgets subscriptions when the socket drops. This provider remembers what the app
 * *wants* and re-subscribes everything on every (re)connect. Since each live view also
 * subscribes to the one-shot `/app/...` current-state destination, a reconnect re-fetches
 * current state automatically: the §21.8 resync, with no event replay.
 */
export function StompProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SocketState>('connecting')
  const clientRef = useRef<Client | null>(null)
  const desired = useRef(new Map<number, Desired>())
  const nextId = useRef(0)

  useEffect(() => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${scheme}://${window.location.host}/ws`,
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        desired.current.forEach((d) => {
          d.live = client.subscribe(d.destination, (m) => d.handler(m.body))
        })
        setState('connected')
      },
      onWebSocketClose: () => {
        desired.current.forEach((d) => (d.live = undefined))
        setState('disconnected')
      },
      onStompError: () => setState('disconnected'),
    })
    clientRef.current = client
    client.activate()
    return () => {
      clientRef.current = null
      void client.deactivate()
    }
  }, [])

  const subscribe = useCallback((destination: string, handler: Handler) => {
    const id = nextId.current++
    const d: Desired = { destination, handler }
    desired.current.set(id, d)
    const client = clientRef.current
    if (client?.connected) {
      d.live = client.subscribe(destination, (m) => d.handler(m.body))
    }
    return () => {
      desired.current.delete(id)
      if (clientRef.current?.connected) d.live?.unsubscribe()
    }
  }, [])

  const api = useMemo(() => ({ state, subscribe }), [state, subscribe])
  return <StompContext.Provider value={api}>{children}</StompContext.Provider>
}


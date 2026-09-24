import { AnimatePresence, LayoutGroup, motion } from 'motion/react'
import { useState } from 'react'
import { api } from './api/client'
import { AuthModal, IdentityChip } from './components/AuthModal'
import { Hint } from './components/Hint'
import { LogoMark } from './components/LogoMark'
import { Toaster } from './components/Toaster'
import type { TrackedEntry } from './components/Tracked'
import { usePolling } from './hooks/usePolling'
import { DirectoryPage } from './pages/DirectoryPage'
import { WaitlistPage } from './pages/WaitlistPage'
import { navigate, usePath, waitlistIdFrom } from './lib/router'
import { session, useMe } from './lib/session'
import { useStomp } from './lib/stomp'

const POLL_MS = 1000

/**
 * The shell: top bar, the two routes, toasts and sign-in.
 * Pages swap inside AnimatePresence ("popLayout": the old page leaves while the new one enters),
 * and LayoutGroup lets a waitlist's title and waiting count fly from its directory row into the
 * waitlist page, and back.
 */
export default function App() {
  const { state: socket } = useStomp()
  const live = socket === 'connected'
  const me = useMe()
  const path = usePath()
  const waitlistId = waitlistIdFrom(path)
  const [authOpen, setAuthOpen] = useState(false)
  // people this browser joined, on any waitlist: kept here so they survive page changes
  const [tracked, setTracked] = useState<TrackedEntry[]>([])

  const trackedIds = tracked.map((t) => `${t.waitlistId}/${t.entryId}`).join(',')
  usePolling(
    async () => {
      const updates = await Promise.all(
        tracked.map((t) =>
          Promise.all([
            live ? Promise.resolve(undefined) : api.position(t.waitlistId, t.entryId).catch(() => undefined),
            api.credits(t.waitlistId, t.userId).catch(() => undefined),
          ]),
        ),
      )
      setTracked((cur) =>
        cur.map((t, i) => {
          const u = updates[i]
          return u ? { ...t, latest: u[0] ?? t.latest, credits: u[1] ?? t.credits } : t
        }),
      )
      return null
    },
    live ? 3000 : POLL_MS,
    [trackedIds, live],
  )

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={() => navigate('/')} title="All waitlists">
          <LogoMark />
          <span>WaaS</span>
          <span className="brand-sub">waitlist-as-a-service</span>
        </button>
        <span className="rule" />
        <span className={`badge ${live ? 'badge-live' : ''}`}>
          {live ? 'live' : 'polling'}
          <Hint>
            {live
              ? 'Live: the WebSocket gateway pushes every change to this page within about half a second. If the connection drops, the page falls back to asking the server once a second.'
              : 'The live connection is down, so the page is asking the server for updates once a second. It switches back to live pushes when the connection returns.'}
          </Hint>
        </span>
        <IdentityChip name={me?.user.name} onSignIn={() => setAuthOpen(true)} onSignOut={() => session.signOut()} />
      </header>

      <div className="pages">
        <LayoutGroup>
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.div
              key={waitlistId ?? 'directory'}
              className="page"
              // no fade-in: the entering page's own pieces animate, and the shared title must stay opaque
              exit={{ opacity: 0, transition: { duration: 0.2 } }}
            >
              {waitlistId ? (
                <WaitlistPage waitlistId={waitlistId} socket={socket} tracked={tracked} setTracked={setTracked} />
              ) : (
                <DirectoryPage />
              )}
            </motion.div>
          </AnimatePresence>
        </LayoutGroup>
      </div>

      <Toaster />
      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)} />
    </div>
  )
}

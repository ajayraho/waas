import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { Directory, NewWaitlist, WaitlistCard } from '../api/types'
import { AnimatedNumber } from '../components/AnimatedNumber'
import { Hint } from '../components/Hint'
import { usePolling, useNow } from '../hooks/usePolling'
import { ago } from '../lib/format'
import { report } from '../lib/report'
import { navigate, waitlistPath } from '../lib/router'
import { DEMO_KEYS, tenant, useApiKey } from '../lib/tenant'
import { toast } from '../lib/toast'
import { seenCards } from '../lib/waitlistCache'

const PAGE_SIZE = 6

const WINDOWS = [
  { label: '1 min', value: 60 },
  { label: '5 min', value: 300 },
  { label: '10 min', value: 600 },
  { label: '30 min', value: 1800 },
  { label: '1 hour', value: 3600 },
]

// Survives leaving the page: coming back shows the same search, page and rows on the first
// frame, so the title flying back from the waitlist page has a row to land in.
const kept = { query: '', page: 0 }
const lastSeen = new Map<string, Directory>()
const viewKey = (apiKey: string, q: string, page: number) => `${apiKey}|${q}|${page}`

function windowLabel(seconds: number) {
  return seconds < 60 ? `${seconds}s` : seconds < 3600 ? `${Math.round(seconds / 60)} min` : `${Math.round(seconds / 3600)} h`
}

/** The business's waitlists: search, page through, create, and open one. Route: / */
export function DirectoryPage() {
  const apiKey = useApiKey()
  const now = useNow(30_000)
  const [query, setQuery] = useState(kept.query)
  const [debounced, setDebounced] = useState(kept.query)
  const [page, setPage] = useState(kept.page)
  useEffect(() => {
    kept.query = query
    kept.page = page
  }, [query, page])
  const [creating, setCreating] = useState(false)

  // search after the user pauses typing, not on every keystroke
  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), 250)
    return () => clearTimeout(t)
  }, [query])

  const dir = usePolling(
    async (signal) => {
      const d = await api.directory(apiKey, debounced, page, PAGE_SIZE, signal)
      d.items.forEach((c) => seenCards.set(c.id, c))
      lastSeen.set(viewKey(apiKey, debounced, page), d)
      return d
    },
    4000, // counts refresh every few seconds; the live socket is for the waitlist page
    [apiKey, debounced, page],
  )
  const d = dir.data ?? lastSeen.get(viewKey(apiKey, debounced, page))
  const pages = d ? Math.max(1, Math.ceil(d.total / PAGE_SIZE)) : 1
  const badKey = dir.error?.includes('API key')

  const open = (c: WaitlistCard) => {
    seenCards.set(c.id, c)
    navigate(waitlistPath(c.id))
  }

  return (
    <>
      <section className="hero dir-hero" aria-labelledby="dir-title">
        <div className="hero-band">
          <div className="hero-title">
            <p className="eyebrow">{d ? d.tenant : badKey ? 'unknown business' : '…'}</p>
            <h1 id="dir-title" className="display">waitlists</h1>
            <p className="hero-sub">
              {d ? `${d.total} waitlist${d.total === 1 ? '' : 's'}, each with its own line, slots and rules.` : ' '}
            </p>
          </div>
          <div className="key-box">
            <label htmlFor="business-key" className="label">
              business key
              <Hint>
                Every call a business makes carries its API key, and it only ever sees its own waitlists. Switch to{' '}
                <b>demo-api-key-002</b> to become a different business (Nimbus Games): the list changes completely.
              </Hint>
            </label>
            <input id="business-key" className="mono key-input" value={apiKey} spellCheck={false}
              onChange={(e) => {
                tenant.setKey(e.target.value)
                setPage(0)
              }} />
            <div className="key-presets">
              {DEMO_KEYS.map((k) => (
                <button key={k} className={`link-btn ${k === apiKey ? 'active' : ''}`}
                  onClick={() => {
                    tenant.setKey(k)
                    setPage(0)
                  }}>
                  {k.replace('demo-api-key-', 'business ')}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>

      <div className="dir-tools">
        <input className="dir-search" type="search" placeholder="search waitlists…" aria-label="Search waitlists" value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setPage(0)
          }} />
        <motion.button whileTap={{ scale: 0.96 }} className={`btn ${creating ? '' : 'btn-primary'}`} onClick={() => setCreating((c) => !c)}>
          {creating ? 'close' : 'new waitlist'}
        </motion.button>
      </div>

      <AnimatePresence initial={false}>
        {creating && (
          <motion.div className="new-wl-wrap" initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }} transition={{ type: 'spring', stiffness: 260, damping: 30 }}>
            <NewWaitlistForm apiKey={apiKey} onCancel={() => setCreating(false)} />
          </motion.div>
        )}
      </AnimatePresence>

      {badKey && <p className="alert">That key doesn&apos;t belong to any business. Try one of the demo keys above.</p>}

      <ul className="dir-list">
        <AnimatePresence initial={false} mode="popLayout">
          {d?.items.map((c, i) => (
            <motion.li key={c.id} layout="position"
              initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0, transition: { delay: 0.04 * i } }}
              exit={{ opacity: 0, transition: { duration: 0.12 } }}>
              <button className="dir-row" onClick={() => open(c)}>
                <span className="dir-main">
                  <motion.span layoutId={`title-${c.id}`} className="dir-name"
                    transition={{ type: 'spring', stiffness: 170, damping: 24 }}>
                    {c.name}
                  </motion.span>
                  <span className="dir-desc">{c.description ?? ' '}</span>
                  <span className="dir-meta">
                    {c.groupPolicy.toLowerCase()} · {c.servingCapacity} slot{c.servingCapacity === 1 ? '' : 's'} · {windowLabel(c.reservationWindowSeconds)} window · created {ago(c.createdAt, now)}
                  </span>
                </span>
                <span className="dir-count stat">
                  <motion.span layoutId={`waiting-${c.id}`} className="stat-value">
                    <AnimatedNumber value={c.waiting} />
                  </motion.span>
                  <span className="stat-label">waiting</span>
                </span>
                <span className="dir-serving stat">
                  <span className="dir-serving-value mono">
                    {c.reserved} / {c.servingCapacity}
                  </span>
                  <span className="stat-label">
                    {c.reserved > 0 && <span className="live-dot" aria-hidden />}
                    being served
                  </span>
                </span>
                <span className="dir-arrow" aria-hidden>→</span>
              </button>
            </motion.li>
          ))}
        </AnimatePresence>
      </ul>

      {d && d.items.length === 0 && (
        <motion.p className="list-empty" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          {debounced ? `nothing matches “${debounced}”` : 'no waitlists yet'}
        </motion.p>
      )}

      {d && pages > 1 && (
        <nav className="pager" aria-label="Pages">
          <button className="link-btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← newer</button>
          <span className="mono muted">page {page + 1} of {pages}</span>
          <button className="link-btn" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>older →</button>
        </nav>
      )}
    </>
  )
}

function NewWaitlistForm({ apiKey, onCancel }: { apiKey: string; onCancel: () => void }) {
  const [form, setForm] = useState<NewWaitlist>({
    name: '',
    description: '',
    groupPolicy: 'PARTIAL',
    servingCapacity: 3,
    reservationWindowSeconds: 600,
    bumpAmount: 1,
  })
  const [busy, setBusy] = useState(false)
  const set = <K extends keyof NewWaitlist>(k: K, v: NewWaitlist[K]) => setForm((f) => ({ ...f, [k]: v }))

  const submit = async () => {
    setBusy(true)
    try {
      const w = await api.createWaitlist(apiKey, { ...form, name: form.name.trim() })
      seenCards.set(w.id, { ...w, waiting: 0, reserved: 0 })
      toast.push('success', `${w.name} is live`, 'Share its page, or use the integrate snippet in console mode.')
      navigate(waitlistPath(w.id))
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) toast.push('warn', 'Check the form', e.message)
      else report(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="new-wl" onSubmit={(e) => { e.preventDefault(); void submit() }}>
      <div className="new-wl-grid">
        <label className="field field-wide">
          <span className="label">name</span>
          <input required maxLength={120} value={form.name} placeholder="e.g. Summer Sale Checkout" autoFocus
            onChange={(e) => set('name', e.target.value)} />
        </label>
        <label className="field field-wide">
          <span className="label">description</span>
          <input maxLength={500} value={form.description} placeholder="what people are waiting for"
            onChange={(e) => set('description', e.target.value)} />
        </label>
        <label className="field">
          <span className="label">serving slots</span>
          <input type="number" min={1} max={1000} value={form.servingCapacity}
            onChange={(e) => set('servingCapacity', Number(e.target.value))} />
        </label>
        <label className="field">
          <span className="label">checkout window</span>
          <select value={form.reservationWindowSeconds} onChange={(e) => set('reservationWindowSeconds', Number(e.target.value))}>
            {WINDOWS.map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
          </select>
        </label>
        <label className="field">
          <span className="label">referral bump</span>
          <input type="number" min={1} max={100} value={form.bumpAmount}
            onChange={(e) => set('bumpAmount', Number(e.target.value))} />
        </label>
        <div className="field">
          <span className="label">
            groups
            <Hint>
              <b>partial</b>: friends who join together each get their own place. <b>strict</b>: the group holds one
              place and gets one slot together.
            </Hint>
          </span>
          <div className="seg" role="radiogroup" aria-label="Group policy">
            {(['PARTIAL', 'STRICT'] as const).map((p) => (
              <button key={p} type="button" role="radio" aria-checked={form.groupPolicy === p}
                className={`seg-btn ${form.groupPolicy === p ? 'active' : ''}`} onClick={() => set('groupPolicy', p)}>
                {p.toLowerCase()}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div className="new-wl-actions">
        <button type="button" className="link-btn" onClick={onCancel}>cancel</button>
        <motion.button whileTap={{ scale: 0.96 }} className="btn btn-primary" disabled={busy || !form.name.trim()}>
          create waitlist
        </motion.button>
      </div>
    </form>
  )
}

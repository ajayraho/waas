import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import { api, ApiError } from '../api/client'
import { session } from '../lib/session'
import { toast } from '../lib/toast'

type Mode = 'guest' | 'login' | 'register'

export function AuthModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [mode, setMode] = useState<Mode>('guest')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      if (mode === 'guest') {
        const user = await api.guest(name.trim())
        session.signIn(user, session.tokenFor(user.id) as string)
      } else {
        const r = mode === 'login' ? await api.login(email, password) : await api.register(name.trim(), email, password)
        session.signIn(r.user, r.token)
      }
      toast.push('success', `Signed in as ${session.me()?.user.name}`, 'Your requests now carry your JWT.')
      onClose()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const needsName = mode !== 'login'
  const needsCreds = mode !== 'guest'
  const valid = (!needsName || name.trim()) && (!needsCreds || (email && password.length >= 6))

  return (
    <AnimatePresence>
      {open && (
        <motion.div className="modal-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose}>
          <motion.div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="auth-title"
            initial={{ opacity: 0, y: 30, scale: 0.94 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 20, scale: 0.96 }}
            transition={{ type: 'spring', stiffness: 380, damping: 28 }}
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="auth-title">Who are you?</h2>
            <div className="seg" role="tablist">
              {(['guest', 'login', 'register'] as Mode[]).map((m) => (
                <button key={m} role="tab" aria-selected={mode === m} className="seg-btn" onClick={() => setMode(m)}>
                  {mode === m && <motion.span layoutId="seg-pill" className="seg-pill" transition={{ type: 'spring', stiffness: 500, damping: 35 }} />}
                  <span className="seg-label">{m === 'guest' ? 'Guest' : m === 'login' ? 'Log in' : 'Register'}</span>
                </button>
              ))}
            </div>
            <form
              className="modal-form"
              onSubmit={(e) => {
                e.preventDefault()
                if (valid) void submit()
              }}
            >
              <AnimatePresence initial={false} mode="popLayout">
                {needsName && (
                  <motion.input key="name" placeholder="Name" value={name} onChange={(e) => setName(e.target.value)}
                    initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} />
                )}
                {needsCreds && (
                  <motion.input key="email" type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)}
                    initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} />
                )}
                {needsCreds && (
                  <motion.input key="pw" type="password" placeholder="Password (6+ chars)" value={password} onChange={(e) => setPassword(e.target.value)}
                    initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} />
                )}
              </AnimatePresence>
              {error && (
                <motion.p className="modal-error" initial={{ x: -8 }} animate={{ x: [8, -6, 4, 0] }} transition={{ duration: 0.3 }}>
                  {error}
                </motion.p>
              )}
              <motion.button whileTap={{ scale: 0.97 }} className="btn btn-primary btn-block" disabled={!valid || busy}>
                {busy ? '…' : mode === 'guest' ? 'Continue as guest' : mode === 'login' ? 'Log in' : 'Create account'}
              </motion.button>
            </form>
            <p className="hint">Guests get a real JWT too, just without a password.</p>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

export function IdentityChip({ name, onSignIn, onSignOut }: { name?: string; onSignIn: () => void; onSignOut: () => void }) {
  if (!name) {
    return (
      <motion.button whileHover={{ y: -1 }} whileTap={{ scale: 0.96 }} className="btn btn-sm btn-primary" onClick={onSignIn}>
        Sign in
      </motion.button>
    )
  }
  return (
    <motion.div className="identity" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }}>
      <span className="avatar">{name.slice(0, 1).toUpperCase()}</span>
      <span className="identity-name">{name}</span>
      <button className="link-btn" onClick={onSignOut}>
        sign out
      </button>
    </motion.div>
  )
}

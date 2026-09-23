import type { ReferralActivity } from '../api/types'
import { useNow } from '../hooks/usePolling'

const REASONS: Record<string, string> = { VELOCITY_LIMIT: 'velocity limit' }

function ago(iso: string, now: number) {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  return s < 60 ? `${s}s` : s < 3600 ? `${Math.floor(s / 60)}m` : `${Math.floor(s / 3600)}h`
}

/** Every referral attempt, credited or rejected: the audit trail from V2's referral.status. */
export function ReferralFeed({ items, bumpAmount }: { items: ReferralActivity[]; bumpAmount: number }) {
  const now = useNow(5000) // a feed's "12s ago" doesn't need a fast clock
  return (
    <section className="card" aria-labelledby="feed-title">
      <h2 id="feed-title">Referrals</h2>
      {items.length === 0 ? (
        <p className="muted small">
          Press ↑ on a waiting row: a new friend joins through that person&apos;s link and they move up{' '}
          {bumpAmount} spot{bumpAmount === 1 ? '' : 's'}.
        </p>
      ) : (
        <ul className="feed">
          {items.map((a) => (
            <li key={`${a.referrerId}-${a.refereeId}`} className="feed-row">
              <span className={`state-pill ${a.status === 'CREDITED' ? 'state-confirmed' : 'state-expired'}`}>
                {a.status === 'CREDITED' ? `+${bumpAmount}` : 'blocked'}
              </span>
              <span className="feed-text">
                <strong>{a.referrerName}</strong> referred {a.refereeName}
                {a.rejectionReason && <span className="muted"> · {REASONS[a.rejectionReason] ?? a.rejectionReason}</span>}
              </span>
              <span className="feed-time mono muted">{ago(a.at, now)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

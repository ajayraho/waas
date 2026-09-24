import { session } from '../lib/session'
import type {
  AppUser,
  CreditLedger,
  Directory,
  EntryPosition,
  GroupView,
  JoinResponse,
  NewWaitlist,
  QueueSnapshot,
  ReferralActivity,
  Waitlist,
} from './types'

/** RFC 9457 problem details, as rendered by Core's GlobalExceptionHandler. */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly retryAfter?: number
  constructor(status: number, message: string, code?: string, retryAfter?: number) {
    super(message)
    this.status = status
    this.code = code
    this.retryAfter = retryAfter
  }
}

type Opts = RequestInit & { as?: string | null; apiKey?: string; idempotent?: boolean }

async function request<T>(path: string, { as, apiKey, idempotent, ...init }: Opts = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(init.headers as Record<string, string>) }
  const token = session.tokenFor(as)
  if (token) headers.Authorization = `Bearer ${token}`
  if (apiKey) headers['X-Api-Key'] = apiKey
  // One key per user action. If the network drops we retry once with the SAME key, so the
  // server replays its first answer instead of doing the work twice.
  if (idempotent) headers['Idempotency-Key'] = crypto.randomUUID()

  let res: Response
  try {
    res = await fetch(path, { ...init, headers })
  } catch (networkError) {
    if (!idempotent) throw networkError
    res = await fetch(path, { ...init, headers })
  }
  if (!res.ok) {
    let detail = `HTTP ${res.status}`
    let code: string | undefined
    try {
      const body = (await res.json()) as { detail?: string; code?: string }
      detail = body.detail ?? detail
      code = body.code
    } catch {
      /* non-JSON error body */
    }
    const retry = Number(res.headers.get('Retry-After')) || undefined
    throw new ApiError(res.status, detail, code, retry)
  }
  return (res.status === 204 ? undefined : await res.json()) as T
}

type TokenResponse = { token: string; user: AppUser }

export const api = {
  // ---- auth ----
  guest: async (name: string) => {
    const r = await request<TokenResponse>('/api/auth/guest', { method: 'POST', body: JSON.stringify({ name }) })
    session.remember(r.user, r.token)
    return r.user
  },
  register: (name: string, email: string, password: string) =>
    request<TokenResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ name, email, password }) }),
  login: (email: string, password: string) =>
    request<TokenResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  // ---- queue ----
  // ---- waitlists (the business's directory needs its API key) ----
  directory: (apiKey: string, q: string, page: number, size: number, signal?: AbortSignal) =>
    request<Directory>(`/api/waitlists?${new URLSearchParams({ q, page: String(page), size: String(size) })}`, { apiKey, signal }),
  createWaitlist: (apiKey: string, body: NewWaitlist) =>
    request<Waitlist>('/api/waitlists', { method: 'POST', apiKey, body: JSON.stringify(body) }),
  waitlist: (id: string, signal?: AbortSignal) => request<Waitlist>(`/api/waitlists/${id}`, { signal }),
  snapshot: (waitlistId: string, limit = 50, signal?: AbortSignal) =>
    request<QueueSnapshot>(`/api/waitlists/${waitlistId}/queue?limit=${limit}`, { signal }),
  join: (waitlistId: string, userId: string, ref?: string) =>
    request<JoinResponse>(`/api/waitlists/${waitlistId}/entries${ref ? `?ref=${ref}` : ''}`, {
      method: 'POST',
      as: userId,
      idempotent: true,
    }),
  position: (waitlistId: string, entryId: string) =>
    request<EntryPosition>(`/api/waitlists/${waitlistId}/entries/${entryId}`),
  cancel: (waitlistId: string, entryId: string, userId?: string | null) =>
    request<void>(`/api/waitlists/${waitlistId}/entries/${entryId}`, { method: 'DELETE', as: userId }),

  // ---- reservation (the holder acts, so the token must be theirs) ----
  confirm: (waitlistId: string, entryId: string, userId: string) =>
    request<void>(`/api/waitlists/${waitlistId}/entries/${entryId}/confirm`, { method: 'POST', as: userId }),
  decline: (waitlistId: string, entryId: string, userId: string) =>
    request<void>(`/api/waitlists/${waitlistId}/entries/${entryId}/decline`, { method: 'POST', as: userId }),

  // ---- admin (tenant API key) ----
  updateConfig: (waitlistId: string, apiKey: string, patch: { servingCapacity?: number; reservationWindowSeconds?: number }) =>
    request<Waitlist>(`/api/waitlists/${waitlistId}`, { method: 'PATCH', apiKey, body: JSON.stringify(patch) }),

  // ---- referrals ----
  credits: (waitlistId: string, userId: string) =>
    request<CreditLedger>(`/api/waitlists/${waitlistId}/users/${userId}/credits`),
  referrals: (waitlistId: string, limit = 12, signal?: AbortSignal) =>
    request<ReferralActivity[]>(`/api/waitlists/${waitlistId}/referrals?limit=${limit}`, { signal }),

  // ---- groups ----
  createGroup: (waitlistId: string, creatorId: string, memberIds: string[]) =>
    request<GroupView>(`/api/waitlists/${waitlistId}/groups`, {
      method: 'POST',
      as: creatorId,
      idempotent: true,
      body: JSON.stringify({ memberIds }),
    }),
  group: (waitlistId: string, groupId: string) => request<GroupView>(`/api/waitlists/${waitlistId}/groups/${groupId}`),
  confirmGroupMember: (waitlistId: string, groupId: string, userId: string) =>
    request<GroupView>(`/api/waitlists/${waitlistId}/groups/${groupId}/confirm`, { method: 'POST', as: userId }),

  // ---- demo: fire N writes as one user to show the rate limiter ----
  hammer: async (waitlistId: string, userId: string, n: number) => {
    const results = await Promise.allSettled(
      Array.from({ length: n }, () => request(`/api/waitlists/${waitlistId}/entries`, { method: 'POST', as: userId })),
    )
    const limited = results.filter((r) => r.status === 'rejected' && (r.reason as ApiError).status === 429)
    const retryAfter = limited.length ? ((limited[0] as PromiseRejectedResult).reason as ApiError).retryAfter : undefined
    return { sent: n, limited: limited.length, retryAfter }
  },
}

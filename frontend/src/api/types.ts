// Mirrors the Core DTOs (tenant.Waitlist, queue.QueueViews, user.AppUser).

export type EntryState = 'WAITING' | 'RESERVED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED'

export interface Waitlist {
  id: string
  tenantId: string
  name: string
  description: string | null
  groupPolicy: 'STRICT' | 'PARTIAL'
  servingCapacity: number
  reservationWindowSeconds: number
  bumpAmount: number
  maxCapacity: number | null
  active: boolean
  createdAt: string
}

/** One row of the waitlist directory: config plus live counts. */
export interface WaitlistCard {
  id: string
  name: string
  description: string | null
  groupPolicy: 'STRICT' | 'PARTIAL'
  servingCapacity: number
  reservationWindowSeconds: number
  bumpAmount: number
  createdAt: string
  waiting: number
  reserved: number
}

export interface Directory {
  /** the business the API key belongs to */
  tenant: string
  items: WaitlistCard[]
  total: number
  page: number
  size: number
}

export interface NewWaitlist {
  name: string
  description?: string
  groupPolicy: 'STRICT' | 'PARTIAL'
  servingCapacity: number
  reservationWindowSeconds: number
  bumpAmount: number
}

export interface AppUser {
  id: string
  email: string
  name: string
}

export interface EntryPosition {
  entryId: string
  waitlistId: string
  state: EntryState
  position: number | null
  queueSize: number | null
  expiresAt: string | null
}

export interface JoinResponse {
  created: boolean
  entryId: string
  userId: string
  position: EntryPosition
}

export interface WaitingRow {
  position: number
  entryId: string
  userId: string | null
  name: string
  score: number
  groupId: string | null
  groupSize: number
  /** when the entry was created */
  joinedAt: string | null
  /** queue places gained from referrals */
  boost: number
  /** friends referred (credited) on this waitlist */
  referrals: number
}

export interface ReservedRow {
  entryId: string
  userId: string | null
  name: string
  expiresAt: string
  groupId: string | null
  groupSize: number
  groupConfirmed: number
}

export interface QueueSnapshot {
  waitlistId: string
  servingCapacity: number
  queueSize: number
  reservedCount: number
  confirmedCount: number
  expiredCount: number
  reserved: ReservedRow[]
  waiting: WaitingRow[]
  at: string
}

export interface CreditLedger {
  earned: number
  applied: number
  pending: number
  credited: number
  rejected: number
}

export interface ReferralActivity {
  referrerId: string
  referrerName: string
  refereeId: string
  refereeName: string
  status: 'CREDITED' | 'REJECTED'
  rejectionReason: string | null
  at: string
}

export interface GroupView {
  groupId: string
  waitlistId: string
  policy: 'STRICT' | 'PARTIAL'
  members: { userId: string; name: string; confirmed: boolean; entryId: string | null; state: EntryState | null }[]
}

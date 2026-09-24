import type { WaitlistCard } from '../api/types'

/**
 * Directory rows the user has already seen, by id. The waitlist page reads its title from here
 * on its very first frame, so the shared-element animation (row title → page title) has both
 * ends in place before the full config has even been fetched.
 */
export const seenCards = new Map<string, WaitlistCard>()

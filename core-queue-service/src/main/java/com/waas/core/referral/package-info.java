/**
 * <b>Referral / Credit</b> — referral links, the fraud velocity check, the rank-based bump
 * (bump.lua), the apply-vs-bank decision and the credit ledger (ARCHITECTURE.md §21.3, §21.4).
 * <p>Depends on queue (listens to {@code EntryJoined}, reads entries) and tenant. Nothing depends
 * on referral: removing this package would leave a working waitlist without referrals.
 */
package com.waas.core.referral;

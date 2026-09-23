/**
 * <b>Reservation</b> — serving capacity, promote.lua, confirm/decline/expire, the expiry sweeper,
 * and Postgres↔Redis reconciliation (ARCHITECTURE.md §21.1, §21.2, §21.6).
 * <p>Owns {@code waitlist:{id}:reserved} and {@code :stats}. Depends on queue and tenant; neither
 * depends on it. It learns about joins and config changes through in-process events
 * ({@code EntryJoined}, {@code WaitlistConfigChanged}). Reconciliation lives here, not in queue,
 * because it must reason about both sorted sets and trigger promotion.
 */
package com.waas.core.reservation;

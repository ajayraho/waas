/**
 * <b>Queue</b> — join, position read, cancel (WAITING), dashboard snapshot, crash rebuild
 * (ARCHITECTURE.md §15, §21.5, §21.6, §21.8). Rank-based bump (bump.lua) arrives in Brick 4.
 * <p>Owns {@code waitlist:{id}:queue}. Must not depend on reservation/referral/group: it
 * announces joins with the {@code EntryJoined} event instead of calling promotion directly.
 */
package com.waas.core.queue;

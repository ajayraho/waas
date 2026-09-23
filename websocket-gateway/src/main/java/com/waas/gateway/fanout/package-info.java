/**
 * <b>Fan-out</b> — subscribes to {@code waitlist:{id}:events}, pushes directly-addressed events
 * immediately, marks waitlists dirty and recomputes positions for local connections once per
 * tick (ARCHITECTURE.md §21.7). Reconnect resync lives here too (§21.8).
 */
package com.waas.gateway.fanout;

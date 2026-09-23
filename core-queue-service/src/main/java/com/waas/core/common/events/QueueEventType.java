package com.waas.core.common.events;

/**
 * What happened to one entry. BUMPED lives here, as an event, not as a state (§21.11).
 */
public enum QueueEventType {
    JOINED,
    CANCELLED,
    BUMPED,
    RESERVED,
    CONFIRMED,
    EXPIRED
}

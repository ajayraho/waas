package com.waas.core.queue;

/** The five persisted states (ARCHITECTURE.md §21.11). BUMPED is an event, not a state. */
public enum EntryState {
    WAITING,
    RESERVED,
    CONFIRMED,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this == EXPIRED || this == CANCELLED;
    }
}

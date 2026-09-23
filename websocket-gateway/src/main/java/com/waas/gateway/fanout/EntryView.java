package com.waas.gateway.fanout;

import java.time.Instant;
import java.util.UUID;

/**
 * What one person's screen shows. Same JSON shape as Core's {@code EntryPosition}, so the
 * client handles a pushed update and a REST response identically.
 */
public record EntryView(UUID entryId, UUID waitlistId, String state, Long position, Long queueSize, Instant expiresAt) {}

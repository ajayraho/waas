package com.waas.core.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The message published on {@code waitlist:{id}:events}.
 *
 * <p>Deliberately small: it says <em>who</em> changed and <em>how</em>, never anyone's
 * position. Positions are computed on read ({@code ZRANK}) by whoever needs them (§21.7),
 * so one bump never turns into a message per shifted user.
 */
public record QueueEvent(QueueEventType type, UUID waitlistId, UUID entryId, UUID userId, Instant at) {}

package com.waas.gateway.fanout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * The event Core publishes on {@code waitlist:{id}:events}. The gateway keeps its own copy of
 * this shape rather than sharing a jar with Core: the JSON is the contract, and each service
 * deploys independently. Unknown fields are ignored so Core can add fields without breaking us.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueueEvent(String type, UUID waitlistId, UUID entryId, UUID userId, Instant at) {}

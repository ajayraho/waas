package com.waas.core.tenant;

import java.util.UUID;

/** A waitlist's configuration: the per-tenant knobs that make the platform generic (§4). */
public record Waitlist(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        String groupPolicy,
        int servingCapacity,
        int reservationWindowSeconds,
        int bumpAmount,
        Integer maxCapacity,
        boolean active) {}

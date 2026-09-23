package com.waas.core.tenant;

import com.waas.core.common.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Waitlist lookup for the other modules.
 *
 * <p>Brick 7 turns {@link #require(UUID)} into the tenant guard (§21.10): the caller's tenant is
 * checked against {@code waitlist.tenant_id}, and a mismatch returns 404, not 403. Every module
 * already goes through this one method, so that change lands in exactly one place.
 */
@Service
public class WaitlistService {

    private final WaitlistRepository repository;
    private final ApplicationEventPublisher domainEvents;

    public WaitlistService(WaitlistRepository repository, ApplicationEventPublisher domainEvents) {
        this.repository = repository;
        this.domainEvents = domainEvents;
    }

    public Waitlist require(UUID waitlistId) {
        return repository.findById(waitlistId)
                .orElseThrow(() -> ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId));
    }

    public List<Waitlist> listActive() {
        return repository.findAllActive();
    }

    /**
     * Change serving capacity and/or the reservation window. A capacity increase frees slots
     * right now, so {@link WaitlistConfigChanged} lets the Reservation module promote at once.
     * A decrease never evicts current holders: they keep their window and the slots drain
     * naturally. A new window applies only to future reservations.
     */
    public Waitlist updateConfig(String apiKey, UUID waitlistId, Integer servingCapacity,
                                 Integer reservationWindowSeconds) {
        requireOwnedBy(apiKey, waitlistId);
        return updateConfig(waitlistId, servingCapacity, reservationWindowSeconds);
    }

    /**
     * Tenant isolation for admin calls: the API key must belong to the tenant that owns the
     * waitlist. Otherwise it's 404, not 403, so you can't probe for other tenants' waitlists.
     */
    public Waitlist requireOwnedBy(String apiKey, UUID waitlistId) {
        UUID tenant = repository.tenantForApiKey(apiKey == null ? "" : apiKey)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_API_KEY", "Unknown API key"));
        Waitlist w = require(waitlistId);
        if (!w.tenantId().equals(tenant)) {
            throw ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId);
        }
        return w;
    }

    public Waitlist updateConfig(UUID waitlistId, Integer servingCapacity, Integer reservationWindowSeconds) {
        Waitlist updated = repository.updateConfig(waitlistId, servingCapacity, reservationWindowSeconds)
                .orElseThrow(() -> ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId));
        domainEvents.publishEvent(new WaitlistConfigChanged(waitlistId));
        return updated;
    }
}

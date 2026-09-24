package com.waas.core.tenant;

import com.waas.core.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    static final int MAX_PAGE_SIZE = 100;

    private final WaitlistRepository repository;
    private final WaitlistCounts counts;
    private final ApplicationEventPublisher domainEvents;

    public WaitlistService(WaitlistRepository repository, WaitlistCounts counts, ApplicationEventPublisher domainEvents) {
        this.repository = repository;
        this.counts = counts;
        this.domainEvents = domainEvents;
    }

    // Waitlist config is read on almost every request but changes rarely, so keep it for a few
    // seconds. Cleared on update here; another instance may see an old config for up to TTL.
    private static final long CACHE_TTL_MS = 5_000;
    private record Cached(Waitlist waitlist, long loadedAt) {}
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public Waitlist require(UUID waitlistId) {
        Cached c = cache.get(waitlistId);
        if (c != null && System.currentTimeMillis() - c.loadedAt() < CACHE_TTL_MS) {
            return c.waitlist();
        }
        Waitlist w = repository.findById(waitlistId)
                .orElseThrow(() -> ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId));
        cache.put(waitlistId, new Cached(w, System.currentTimeMillis()));
        return w;
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

    /** One row of the waitlist directory: config plus live counts from Redis. */
    public record Card(UUID id, String name, String description, String groupPolicy, int servingCapacity,
                       int reservationWindowSeconds, int bumpAmount, Instant createdAt,
                       long waiting, long reserved) {}

    /** One page of the directory, plus whose directory it is (the tenant's display name). */
    public record Directory(String tenant, List<Card> items, long total, int page, int size) {}

    /** The calling tenant's waitlists (and only theirs), searched by name, one page at a time. */
    public Directory directory(String apiKey, String query, int page, int size) {
        UUID tenant = tenantFor(apiKey);
        int p = Math.max(0, page);
        int s = Math.clamp(size, 1, MAX_PAGE_SIZE);
        List<Waitlist> rows = repository.page(tenant, query, s, p * s);
        Map<UUID, WaitlistCounts.Counts> live = counts.of(rows.stream().map(Waitlist::id).toList());
        List<Card> cards = rows.stream().map(w -> {
            WaitlistCounts.Counts c = live.getOrDefault(w.id(), new WaitlistCounts.Counts(0, 0));
            return new Card(w.id(), w.name(), w.description(), w.groupPolicy(), w.servingCapacity(),
                    w.reservationWindowSeconds(), w.bumpAmount(), w.createdAt(), c.waiting(), c.reserved());
        }).toList();
        return new Directory(repository.tenantName(tenant), cards, repository.count(tenant, query), p, s);
    }

    /** A new, empty waitlist owned by the caller's tenant. Nothing to set up in Redis: an empty queue has no keys. */
    public Waitlist create(String apiKey, String name, String description, String groupPolicy,
                           Integer servingCapacity, Integer reservationWindowSeconds, Integer bumpAmount) {
        UUID tenant = tenantFor(apiKey);
        return repository.insert(tenant, name.strip(), description == null || description.isBlank() ? null : description.strip(),
                groupPolicy == null ? "PARTIAL" : groupPolicy,
                servingCapacity == null ? 1 : servingCapacity,
                reservationWindowSeconds == null ? 600 : reservationWindowSeconds,
                bumpAmount == null ? 1 : bumpAmount);
    }

    /** API key → tenant id, or 401. */
    UUID tenantFor(String apiKey) {
        return repository.tenantForApiKey(apiKey == null ? "" : apiKey)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_API_KEY", "Unknown API key"));
    }

    /**
     * Tenant isolation for admin calls: the API key must belong to the tenant that owns the
     * waitlist. Otherwise it's 404, not 403, so you can't probe for other tenants' waitlists.
     */
    public Waitlist requireOwnedBy(String apiKey, UUID waitlistId) {
        UUID tenant = tenantFor(apiKey);
        Waitlist w = require(waitlistId);
        if (!w.tenantId().equals(tenant)) {
            throw ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId);
        }
        return w;
    }

    public Waitlist updateConfig(UUID waitlistId, Integer servingCapacity, Integer reservationWindowSeconds) {
        Waitlist updated = repository.updateConfig(waitlistId, servingCapacity, reservationWindowSeconds)
                .orElseThrow(() -> ApiException.notFound("WAITLIST_NOT_FOUND", "No waitlist " + waitlistId));
        cache.remove(waitlistId);
        domainEvents.publishEvent(new WaitlistConfigChanged(waitlistId));
        return updated;
    }
}

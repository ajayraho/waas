package com.waas.core.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Waitlist config. Tenant scoping and full admin CRUD arrive in Brick 7. */
@RestController
@RequestMapping("/api/waitlists")
public class WaitlistController {

    private final WaitlistService waitlists;

    public WaitlistController(WaitlistService waitlists) {
        this.waitlists = waitlists;
    }

    @GetMapping
    public List<Waitlist> list() {
        return waitlists.listActive();
    }

    @GetMapping("/{waitlistId}")
    public Waitlist get(@PathVariable UUID waitlistId) {
        return waitlists.require(waitlistId);
    }

    public record ConfigPatch(
            @Min(1) @Max(1000) Integer servingCapacity,
            @Min(5) @Max(86_400) Integer reservationWindowSeconds) {}

    /** Tenant admin only: needs the owning tenant's X-Api-Key. */
    @PatchMapping("/{waitlistId}")
    public Waitlist patch(@PathVariable UUID waitlistId,
                          @RequestHeader(name = "X-Api-Key", required = false) String apiKey,
                          @Valid @RequestBody ConfigPatch patch) {
        return waitlists.updateConfig(apiKey, waitlistId, patch.servingCapacity(), patch.reservationWindowSeconds());
    }
}

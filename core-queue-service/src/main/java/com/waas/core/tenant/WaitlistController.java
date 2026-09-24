package com.waas.core.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Waitlists as a tenant sees them. Listing and creating need the tenant's {@code X-Api-Key};
 * reading one waitlist by id stays public, because that's how a customer's page reaches it.
 */
@RestController
@RequestMapping("/api/waitlists")
public class WaitlistController {

    private final WaitlistService waitlists;

    public WaitlistController(WaitlistService waitlists) {
        this.waitlists = waitlists;
    }

    /** The caller's waitlists only: search by name, newest first, {@code size} ≤ 100. */
    @GetMapping
    public WaitlistService.Directory list(
            @RequestHeader(name = "X-Api-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return waitlists.directory(apiKey, q, page, size);
    }

    public record NewWaitlist(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Pattern(regexp = "STRICT|PARTIAL") String groupPolicy,
            @Min(1) @Max(1000) Integer servingCapacity,
            @Min(5) @Max(86_400) Integer reservationWindowSeconds,
            @Min(1) @Max(100) Integer bumpAmount) {}

    /** Create a waitlist for the caller's tenant. Omitted settings get sensible defaults. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Waitlist create(@RequestHeader(name = "X-Api-Key", required = false) String apiKey,
                           @Valid @RequestBody NewWaitlist body) {
        return waitlists.create(apiKey, body.name(), body.description(), body.groupPolicy(),
                body.servingCapacity(), body.reservationWindowSeconds(), body.bumpAmount());
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

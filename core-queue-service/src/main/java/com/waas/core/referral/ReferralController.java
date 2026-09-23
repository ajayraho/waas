package com.waas.core.referral;

import com.waas.core.referral.ReferralRepository.Activity;
import com.waas.core.referral.ReferralRepository.Ledger;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Referral reads. There is no "create referral" endpoint: a referral <em>is</em> a join through
 * a link ({@code POST /entries?ref=<userId>}), so it can't be forged without a real join.
 */
@RestController
@RequestMapping("/api/waitlists/{waitlistId}")
public class ReferralController {

    private final ReferralService referrals;

    public ReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    @GetMapping("/users/{userId}/credits")
    public Ledger credits(@PathVariable UUID waitlistId, @PathVariable UUID userId) {
        return referrals.ledger(waitlistId, userId);
    }

    @GetMapping("/referrals")
    public List<Activity> recent(@PathVariable UUID waitlistId, @RequestParam(defaultValue = "20") int limit) {
        return referrals.recent(waitlistId, limit);
    }
}

-- ================================================================
-- V2 — referral audit status (Brick 4)
--
-- Every referral attempt is recorded, including the ones we refused to credit,
-- so abuse is visible after the fact ("who tripped the velocity limit, and when?").
--   CREDITED — bump credit granted to the referrer (applied and/or banked)
--   REJECTED — recorded but no credit; rejection_reason says why
-- ================================================================
ALTER TABLE referral
    ADD COLUMN status           VARCHAR(10) NOT NULL DEFAULT 'CREDITED'
        CHECK (status IN ('CREDITED', 'REJECTED')),
    ADD COLUMN rejection_reason VARCHAR(40),
    ADD CONSTRAINT chk_rejected_needs_reason
        CHECK (status != 'REJECTED' OR rejection_reason IS NOT NULL);

-- Dashboard activity feed: newest referrals of a waitlist.
CREATE INDEX idx_referral_waitlist_created ON referral(waitlist_id, created_at DESC);

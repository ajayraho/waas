-- ================================================================
-- Demo seed — only loaded with the `demo` Spring profile.
-- Repeatable migration (R__): runs after all versioned migrations,
-- so future V2, V3... never conflict with it. Idempotent via
-- ON CONFLICT DO NOTHING.
--
-- Story: "AirMax Drop" — serving_capacity = 1, bump_amount = 2.
--
--   Frank  joined 1st → promoted into the single slot → RESERVED
--                       (countdown visible on first load; when it
--                        lapses the sweeper expires him and promotes
--                        the next person — a live demo by itself)
--   Bob, Carol, Dave, Eve joined 2nd–5th → WAITING
--   Alice  joined 6th, then Grace, Heidi, Ivan (7–9) joined via her link.
--
--   Rank-based bumps (§21.3), bump_amount = 2 each:
--     after Grace: Alice rank 4 → 2   score = midpoint(Carol 3, Dave 4) = 3.5
--     after Heidi: Alice rank 2 → 0   score = Bob 2 − 1                 = 1.0
--     after Ivan : Alice already at head → 2 credits BANKED
--   Ledger: earned 6, applied 4 (spots actually gained), pending 2.
--
--   Resulting WAITING order: Alice(1.0) Bob(2) Carol(3) Dave(4) Eve(5) Grace(7) Heidi(8) Ivan(9)
-- ================================================================

INSERT INTO tenant (id, name, api_key) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Demo Corp', 'demo-api-key-001')
ON CONFLICT DO NOTHING;

INSERT INTO waitlist (id, tenant_id, name, description, group_policy,
                      serving_capacity, reservation_window_seconds, bump_amount) VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'AirMax Drop', 'Limited sneaker drop — one checkout window at a time',
     'PARTIAL', 1, 600, 2),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
     'Beta Access', 'Early access — three onboarding slots at a time',
     'PARTIAL', 3, 300, 1),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
     'Theme Park Ride', 'Family groups ride together',
     'STRICT', 2, 900, 1)
ON CONFLICT DO NOTHING;

-- password_hash is a placeholder; real auth comes in a later brick.
INSERT INTO app_user (id, email, name, password_hash) VALUES
    ('20000000-0000-0000-0000-000000000001', 'bob@demo.com',   'Bob',   '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000002', 'carol@demo.com', 'Carol', '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000003', 'dave@demo.com',  'Dave',  '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000004', 'alice@demo.com', 'Alice', '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000005', 'eve@demo.com',   'Eve',   '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000006', 'frank@demo.com', 'Frank', '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000007', 'grace@demo.com', 'Grace', '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000008', 'heidi@demo.com', 'Heidi', '$2a$10$placeholder'),
    ('20000000-0000-0000-0000-000000000009', 'ivan@demo.com',  'Ivan',  '$2a$10$placeholder')
ON CONFLICT DO NOTHING;

INSERT INTO waitlist_entry (id, waitlist_id, user_id, state, join_sequence, queue_score,
                            total_bumps_applied, reserved_at, reservation_expires_at) VALUES
    ('30000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000006', 'RESERVED', 1, 1, 0,
     NOW(), NOW() + INTERVAL '10 minutes'),                                         -- Frank
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000001', 'WAITING', 2, 2,   0, NULL, NULL),      -- Bob
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000002', 'WAITING', 3, 3,   0, NULL, NULL),      -- Carol
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000003', 'WAITING', 4, 4,   0, NULL, NULL),      -- Dave
    ('30000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000005', 'WAITING', 5, 5,   0, NULL, NULL),      -- Eve
    ('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000004', 'WAITING', 6, 1.0, 4, NULL, NULL),      -- Alice
    ('30000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000007', 'WAITING', 7, 7,   0, NULL, NULL),      -- Grace
    ('30000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000008', 'WAITING', 8, 8,   0, NULL, NULL),      -- Heidi
    ('30000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000009', 'WAITING', 9, 9,   0, NULL, NULL)       -- Ivan
ON CONFLICT DO NOTHING;

INSERT INTO referral (waitlist_id, referrer_id, referee_id) VALUES
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000007'),
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000008'),
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000009')
ON CONFLICT DO NOTHING;

INSERT INTO referral_credit (waitlist_id, user_id, pending_credits, total_credits_earned, total_credits_applied) VALUES
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', 2, 6, 4)
ON CONFLICT DO NOTHING;

-- Keep the sequence ahead of seeded join_sequence values.
SELECT setval('waitlist_join_seq', GREATEST(9, (SELECT last_value FROM waitlist_join_seq)));

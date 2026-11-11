-- Accounts, step 1: identity only. No credentials here.
--
-- How people prove who they are (password, third-party sign-in, a hosted
-- service) is deliberately NOT decided by this migration, because that choice
-- is coupled to hosting and to CLAUDE.md's outbound-host rule. Whatever wins,
-- it adds a column or a side table; it does not reshape this one. Building the
-- identity now means every table that needs a user_id can have one today.
--
-- Seeds one row for the owner so single-user local mode has a real user_id to
-- point at before any login exists. That row is the bridge: code written now
-- against user_id keeps working unchanged once sign-in lands.

CREATE TABLE IF NOT EXISTS users (
    user_id      BIGSERIAL PRIMARY KEY,
    -- Lower-cased at the boundary AND enforced here, because "Jo@x.com" and
    -- "jo@x.com" arriving as two accounts is the kind of bug that is only
    -- discovered by a person who cannot log in. UNIQUE alone would not catch
    -- it: Postgres compares TEXT case-sensitively, so both would be accepted.
    email        TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ
);

-- user_id 1 is the local owner, by convention, referenced before auth exists.
-- ON CONFLICT so re-running this migration on a database that already has it
-- is a no-op rather than an error.
INSERT INTO users (user_id, email, display_name)
VALUES (1, 'owner@localhost', 'Owner')
ON CONFLICT (email) DO NOTHING;

-- BIGSERIAL owns a sequence that does not know about the explicit id above,
-- so the next real signup would try user_id 1 and fail the primary key. Push
-- it past every id currently present.
SELECT setval(pg_get_serial_sequence('users', 'user_id'),
              GREATEST((SELECT MAX(user_id) FROM users), 1));

-- findOrProvision auto-creates a User row on first sign-in without a timezone
-- (see docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md); a NOT NULL
-- column with no default would fail that insert.
ALTER TABLE users ALTER COLUMN timezone SET DEFAULT 'UTC';

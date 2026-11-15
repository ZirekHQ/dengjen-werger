-- A push in flight holds this lease, so the retry sweep and the immediate push never submit the same item twice.
ALTER TABLE work_items ADD COLUMN push_claimed_until TIMESTAMPTZ;

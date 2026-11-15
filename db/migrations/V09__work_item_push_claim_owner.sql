-- Names the push holding the lease, so a push that outlived its lease cannot release or overwrite a newer holder's claim.
ALTER TABLE work_items ADD COLUMN push_claim_id UUID;

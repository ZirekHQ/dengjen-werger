-- PointsReason.DraftConfirmed (docs/superpowers/specs/2026-09-12-mt-draft-assist-design.md) has no
-- corresponding database value yet; additive only, nothing reads it until PointsRepo (v1 Task 13) exists.
ALTER TYPE points_reason ADD VALUE 'draft_confirmed';

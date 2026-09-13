# Roadmap

This is the living tracker — update it as work lands. The detailed task
breakdown it summarizes lives in
[`docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md`](docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md),
which (like every dated file under `docs/superpowers/`) is a point-in-time
record and isn't edited after the fact — status belongs here instead.

## Current milestone: Milestone 1 — Prove the Crowdin Integration First

Sequenced first deliberately: the riskiest unknowns (Crowdin's real
write-API behavior, Skunk's behavior on Supabase's pooler) get proven
against real services before gamification logic is built on assumptions
about them.

| Task | Ships | Status |
|---|---|---|
| 0. Repository hygiene | License, README, CONTRIBUTING, CI | Done |
| 1. Project scaffold | sbt project, `/healthz` route | Done |
| 2. Database schema | Initial migration | Done |
| 3. Skunk pool on Supavisor | Pooling-trap verified against real Supavisor | Done |
| 4. Domain model | `Language`, `User`, `Commitment`, `WorkItem`, etc. | Done |
| 5. `TranslationSource` port + Crowdin codecs | Port trait, JSON codecs | Done |
| 6. Crowdin client — read path | Source strings, approved translations | Done |
| 7. Crowdin client — write path | `submit`/`approve`, guarded live integration test | Planned |
| 8. `CrowdinSource` | `TranslationSource` implementation | Planned |

## Landed alongside Milestone 1: Machine-Translation Draft Assist

Not one of Milestone 1's numbered tasks — a separate foundation built from
its own spec/plan so the backlog isn't blank on day one. `MachineTranslationSource`,
the Google Translate adapter, and `DraftCredit`'s scoring are done and tested;
wiring them into the Crowdin sync job and points award is deferred until
`JobsService` (15) and `ApprovalService` (14) below exist. See
[`docs/superpowers/specs/2026-09-12-mt-draft-assist-design.md`](docs/superpowers/specs/2026-09-12-mt-draft-assist-design.md)
and
[`docs/superpowers/plans/2026-09-13-mt-draft-assist-plan.md`](docs/superpowers/plans/2026-09-13-mt-draft-assist-plan.md).

## Next: Milestone 2 — Domain & Gamification Engine

| Task | Ships |
|---|---|
| 9. `CommitmentEvaluator` | Pure quota-vs-progress logic |
| 10. `StreakCalculator` and `TrustLevel` | Pure |
| 11. `WorkItemRepo` | Lease/release, concurrency-tested |
| 12. `SubmissionService` / `ReviewService` | Blind dispatch, anti-self-review, `RevisionPending` |
| 13. `PointsRepo` | Payout uniqueness, wired into both flows |
| 14. `ApprovalService` | Trust gate, Crowdin push, `UpstreamApprovalPending` |
| 15. `JobsService` | TM sync, commitment evaluation, reminders, `RevisionPending` sweep |
| 16. `AccountDeletionService` | PII purge, contribution retention |

## Then: Milestone 3 — Contributor-Facing Surface

| Task | Ships |
|---|---|
| 17. Auth middleware | Supabase JWT verification, user auto-provisioning |
| 18. Contributor REST API | Plus the internal jobs endpoint |
| 19. Minimal SPA | Onboarding, submission, review, leaderboard |
| 20. Deployment | Native-image, Cloud Run, Cloud Scheduler, trust seed |

## Beyond v1

| Phase | Ships | Status |
|---|---|---|
| Phase 2: NVDA OmegaT glossary source | `NvdaOmegatCsvSource` — character descriptions, symbol dictionary, frequent terms | Planned |
| Phase 3+: More platforms | Launchpad, GNOME, KDE, Mozilla Pontoon adapters — each contingent on that platform accepting automated contributions | Future |
| Beyond v1 | Languages beyond Kurmanji; the same commitment loop applied outside NVDA/accessibility | Vision |

## Known open call

Dengjen Werger is unfunded scope competing with the Nov 3, 2026 NLnet
grant deadline for the same solo-maintainer hours, on a project with no
line item in that grant's budget. Whether to keep building it now, defer
past the grant decision, or shrink v1 further is a standing call for the
maintainer — not resolved by this roadmap.

# Dengjen Werger: Gamified Translation Contribution Platform

## Context

ZirekHQ builds Kurmanji Kurdish accessibility tooling (NVDA localisation,
local neural TTS). The org's own stated bottleneck (`.github` profile README)
is native-speaker review capacity for `nvdaku`'s interface translation,
character descriptions, and symbol dictionary — work that needs judgement
calls from Kurmanji speakers, not programmers. The maintainer's own
experience over 10+ years of asking for volunteer translation help is that
people don't commit sustained time, not that the tooling is missing.

Existing infrastructure this builds on or replaces:
- `dengjen-nvda` already uses Crowdin (the `nvdaaddons` project) for its own
  l10n, with a CI script that checks translation completion scores via the
  Crowdin API. Crowdin already provides a translation-editor UI and a review
  workflow — this spec does not rebuild that.
- `nvda-omegat` holds CSV-based glossary data (character descriptions, symbol
  dictionary, frequent terms) with no contributor-facing UI at all — a
  currently-unserved gap.
- `translations-adhoc-pipeline` is a one-off manual script that scraped
  Mozilla/Launchpad/GNOME/KDE/Weblate for Kurmanji translation memory. It is
  the ad-hoc version of what this project's ingress/TM sync does properly and
  continuously, and is superseded by it rather than reused.

An earlier exploratory session with Gemini (recorded in
`~/Downloads/Spec-Driven Multi-Platform Development Workflow.pdf`) proposed a
Scala/JVM + Kotlin Multiplatform + Oracle Cloud architecture, but got real
facts about this org wrong — it described `dengjen-nvda` and
`nvda-addon-testkit` as Rust crates when both are Python, and didn't know
Crowdin was already in use. This spec is grounded directly in the actual
repos instead.

This design was independently reviewed twice — by a fresh adversarial Claude
agent and separately by Gemini (via Antigravity) — before implementation.
Findings both reviews converged on independently are folded in below;
divergent findings are folded in where concrete and fixable. One finding
neither review resolves and this spec doesn't either: Dengjen Werger is
unfunded scope competing with the Nov 3, 2026 NLnet grant deadline for the
same solo-maintainer hours, on a project whose budget has no line item for
building it. That's a scope call for the maintainer, not an engineering one,
and is called out here rather than silently assumed away.

## Problem

A CAT tool (Crowdin) solves the translation-editor problem. It does not solve
retention: volunteers don't sustain time commitment over months or years.
Dengjen Werger is a commitment/habit layer, not a translation editor — it
sits in front of existing translation backends via an adapter interface and
adds a personal time pledge, reminders, mandatory peer review, points,
streaks, and a leaderboard.

## Scope (v1)

- One language: Kurmanji.
- One `TranslationSource` adapter: Crowdin, against `dengjen-nvda`'s
  `nvdaaddons` project.
- Full gamification loop: onboarding with a commitment pledge, reminders,
  submission, single-reviewer approval, points for both submitting and
  reviewing, streaks, a leaderboard.
- A persisted Translation Memory, synced from Crowdin's existing
  translations, used for fuzzy-match suggestions.
- Anti-abuse and review-integrity controls sufficient to keep the "every
  live translation was reviewed by someone with standing to review" property
  actually true (see below) — not deferred, because without it the whole
  premise of the project (quality, not just volume) doesn't hold.

### Out of scope (explicit phasing)

- **Phase 2**: `NvdaOmegatCsvSource` — character descriptions, symbol
  dictionary, frequent terms. The gap Crowdin doesn't cover.
- **Phase 3+**: Launchpad / GNOME / KDE / Mozilla Pontoon adapters, each
  against that platform's real API. Each requires confirming, before build,
  that the platform accepts automated/API-driven contributions at all — none
  of this is assumed to work yet.
- Languages beyond Kurmanji. The data model carries `Language` as a
  first-class field throughout so this isn't a rewrite later, but no second
  language is seeded in v1.
- Full moderation tooling (appeals, audit UI). v1 ships the minimum needed to
  suspend a bad-faith account (see Anti-abuse below), not a full admin
  console.

## Architecture

```
                 ┌─────────────────┐
   Browser  ───▶ │   SPA frontend    │
                 └────────┬─────────┘
                          │ REST (JWT from Supabase Auth)
                 ┌────────▼─────────┐        ┌───────────────────┐
                 │  Scala backend    │───────▶│   Crowdin API      │
                 │  (Cloud Run,      │        │  (v1 adapter,       │
                 │  GraalVM native-  │        │  kmr-scoped token)  │
                 │  image)           │        └───────────────────┘
                 └────┬───────┬─────┘
                      │       │ Skunk (fs2/Cats Effect, no JDBC)
          Cloud       │       ▼
        Scheduler ────┘  ┌───────────────────┐
   (POST /internal/      │ Supabase Postgres  │◀── Supabase Auth (Google/
    jobs/evaluate-       │ (domain + TM),      │    Apple/GitHub/email)
    and-sync, OIDC/      │ via transaction     │    issues the JWT the
    shared secret)       │ pooler, port 6543   │    backend verifies
                         └───────────────────┘
```

- **Frontend**: a plain SPA. Framework choice is low-stakes and left open for
  the implementation plan — nothing in this design depends on a specific one.
- **Backend**: one Scala service holding domain logic, the gamification state
  machine, and `TranslationSource` adapters.
- **Database access**: **Skunk**, not JDBC/HikariCP. A Scala-3/Cats-Effect/fs2
  native-protocol Postgres driver, chosen specifically because JDBC's
  reflection- and SPI-heavy driver registration is a known source of GraalVM
  native-image build breakage, with no upside here since nothing else in this
  stack needs JDBC compatibility. Connections go through Supabase's
  transaction pooler (Supavisor, port 6543), not the direct port (5432), so a
  cold-start traffic burst doesn't exhaust the connection limit. **Trap to
  test early, not assume**: Supavisor in transaction-pooling mode hands out a
  backend connection per transaction, not per session — Skunk's default named
  prepared statements can then collide or go missing across pooled
  connections. This needs verifying against real Supavisor during initial
  backend scaffolding (unnamed/ephemeral statement mode or explicit
  transaction-scoped preparation), before it's load-bearing for anything
  else.
- **Identity**: Supabase Auth, covering Google/Apple/GitHub/email sign-in —
  most translators have no GitHub account, so auth cannot be GitHub-only.
  The backend verifies Supabase's JWT; it does not otherwise talk to
  Supabase's auth API.
- **Storage**: Supabase Postgres holds all domain and gamification state
  (users, commitments, submissions, reviews, points, streaks) and the
  persisted Translation Memory. One platform for auth + DB, one free tier.
  **Known risk**: Supabase's free tier pauses a project after a period of
  inactivity. The scheduled job hitting the backend (below) incidentally
  keeps it warm during active periods, but this isn't a substitute for
  monitoring — if usage goes quiet long enough to pause, first-request
  latency after a pause should be expected, not treated as an outage.
- **Egress credentials are separate from user identity.** Pushing an approved
  translation to Crowdin uses one service-level Crowdin API token owned by
  Dengjen Werger, never a contributor's own credentials — most contributors
  have no Crowdin account either. The token is scoped to the `kmr` language
  within `nvdaaddons` only (Crowdin supports per-language project roles) —
  not a project-wide manager token — so a bug in adapter code or a leaked
  token can't write to a language or project this system has no business
  touching.
- **Scheduled jobs**: Cloud Run has no cron of its own. Both the TM sync and
  the commitment-progress/reminder check run behind one authenticated
  endpoint, `POST /internal/jobs/evaluate-and-sync`, triggered by Cloud
  Scheduler using an OIDC token (or a shared secret as a simpler fallback).
- **Hosting**: a container on a scale-to-zero platform (Cloud Run), built as
  a GraalVM native-image to keep cold starts short given the request-driven
  (not always-on) load pattern.
- **Transactional email**: reminder emails are a real transactional email
  need — Supabase's built-in email sending is scoped to its own auth flows
  (magic links, password resets) and is not meant for arbitrary app email at
  volume. This design names a separate provider with a usable free tier
  (e.g. Resend) rather than silently overloading Supabase's auth email quota;
  exact provider choice is an implementation-plan detail, not fixed here.

## Domain model

- `Language(code, name)`
- `User(id, authProviderRef, role: Contributor | Reviewer | Maintainer,
  status: Active | Suspended, timezone: IANA zone id, commitment:
  Option[Commitment], emailOptIn)`
- `Commitment(tier: Light | Medium | Heavy, startedAt)` — tiers are defined
  as **countable actions, not self-reported time** (e.g. `Light` ≈ 1
  submission or 2 reviews per day, `Medium` ≈ 5 submissions or 10 reviews per
  week, `Heavy` ≈ roughly the same ratio scaled to a monthly window). Exact
  numbers are tunable at implementation time; the fixed principle is that
  progress is measured from actions the backend actually recorded, never
  from a client-reported elapsed-time claim, which is unverifiable and
  trivially spoofable.
- `WorkItem(id, sourceAdapter, externalId, language, sourceText,
  targetTextDraft, status: Available | InProgress(userId, expiresAt) |
  PendingReview(submissionId) | RevisionPending(submitterId, reviewerComment,
  expiresAt) | Approved | UpstreamApprovalPending | Synced | Retired)`. The
  `InProgress` lease (default 30 minutes, released back to `Available` on
  expiry without a submission) prevents two contributors from translating
  the same item in parallel and one of them wasting the effort.
  `RevisionPending` similarly protects a rejected item from being grabbed out
  from under its original submitter before they've had a chance to act on
  the reviewer's comment (see Gamification loop). `Retired` covers an item
  whose translation turned out to already exist upstream (see Translation
  Memory).
- `Submission(id, workItem, submitter, proposedTranslation, submittedAt)`
- `Review(submission, reviewer, verdict: Approved | Rejected, comment,
  reviewedAt)` — a `Review` may only be created while its `WorkItem` is in
  `PendingReview`; creating one atomically transitions the `WorkItem` out of
  `PendingReview`, so a second reviewer racing on the same item is rejected
  at the state-transition level, not left to a client-side check. The
  reviewer must not be the submission's own submitter — enforced as a
  database constraint, not asserted only in prose.
- `PointsLedgerEntry(user, reason: SubmissionApproved | ReviewCompleted,
  amount, at)` — unique on `(submission, user, reason)`, so a given
  submission/review pairing can only ever pay out once, closing the
  reject-and-resubmit-to-farm-review-points path.
- `StreakState(user, current, longest, lastMetPeriod)` — period boundaries
  are computed in the user's stored timezone, not server/UTC time.
- `TmSegment(language, sourceText, targetText, sourceAdapter, importedAt)` —
  persisted, not a live query; see Translation Memory below.

## The `TranslationSource` port

```scala
trait TranslationSource:
  /** Items needing a new or reviewed translation. */
  def fetchWorkItems(language: Language): IO[List[WorkItem]]

  /** Existing translated segments, for TM sync — not a live per-lookup call. */
  def fetchMemory(language: Language): IO[List[TmSegment]]

  /** Push an approved translation back to the source system. */
  def submit(item: WorkItem, translation: String): IO[Either[SourceError, Unit]]
```

v1 ships exactly one implementation, `CrowdinSource`. The trait's shape is
set by this one real case plus Crowdin's API as the known second-source
precedent — not speculative support for arbitrary future backends. Each
later adapter (`NvdaOmegatCsvSource`, `LaunchpadSource`, `GnomeSource`,
`KdeSource`, `MozillaPontoonSource`) may turn out ingress-only, if that
platform's contribution process doesn't support an automated `submit`.

### `CrowdinSource.submit` — resolved

Crowdin v2's API separates creating a translation from approving one:
`POST /api/v2/projects/{projectId}/translations` (stringId, languageId,
text) creates it; `POST /api/v2/projects/{projectId}/approvals`
(translationId) approves it. The service token needs proofreader-level
permission on the `kmr` language. On a locally-approved `Submission`,
`submit` calls both in sequence. If the translation call succeeds but the
approval call fails, the `WorkItem` moves to `UpstreamApprovalPending`
rather than either silently retrying forever or reporting success — it's
visibly "approved here, not yet reflected on Crowdin" until a retry clears
it.

## Anti-abuse & review integrity

The review gate only means something if it can't be trivially defeated by
one person operating two accounts, or by reviewers optimizing for review
*volume* with no cost to being wrong. v1 includes:

- **Blind dispatch.** Reviewers are served the next item FIFO from the
  pending-review pool, not a chosen item — no picking a friend's submission
  to rubber-stamp.
- **One payout per pairing.** `PointsLedgerEntry`'s uniqueness on
  `(submission, user, reason)` means neither repeatedly reviewing the same
  item nor reject-then-resubmit cycles pay out more than once.
- **A reviewer rate limit** (reviews per user per hour) — a coarse but
  real brake on rapid rubber-stamping.
- **Trust-gated upstream push.** A review earns local points immediately
  regardless of trust level, but a review only triggers the actual
  `CrowdinSource.submit` call once the reviewer has reached a minimum trust
  threshold (e.g. 5 of their own prior submissions accepted). Below that
  threshold, an approved submission accumulates local points and sits queued
  for a trusted reviewer or the maintainer to countersign before it reaches
  Crowdin. This is the load-bearing fix: it decouples "can earn points" (low
  bar, keeps the game loop working for new contributors) from "can put a
  string in front of a screen-reader user" (high bar).
  **Bootstrap problem this creates and its fix**: on day one, nobody has 5
  accepted submissions — if every early approval waits on trust, the
  maintainer becomes the exact bottleneck this project exists to relieve.
  v1 seeds the maintainer, and any Kurmanji speakers already known and
  trusted before launch, with `Role.Maintainer`/`Role.Reviewer` directly via
  migration/seed data rather than making them earn trust through the same
  cold-start loop as a brand-new volunteer. The maintainer also gets a batch
  "express review" view so clearing the early queue is seconds of work, not
  a one-by-one slog through the normal contributor UI.
- **Suspension.** `User.status` includes `Suspended`. A maintainer can
  suspend an account; a suspended user's pending submissions and reviews are
  excluded from dispatch and payout. This is deliberately minimal — no
  appeals flow, no audit UI — sufficient to stop an active bad actor, not a
  full moderation system.

This does not eliminate collusion between two patient, coordinated accounts
that both clear the trust threshold legitimately first. No fully automated
system does; the trust gate raises the cost from "trivial" to "requires
sustained, deliberate effort," which is the realistic bar for a
volunteer-scale project.

## Gamification loop

1. **Onboarding**: pick a language (Kurmanji only), a commitment tier, and a
   timezone.
2. **Scheduled evaluation** (`/internal/jobs/evaluate-and-sync`, see
   Architecture): for each active commitment, checks recorded-action progress
   against the tier's quota for the current period in the user's timezone;
   sends a reminder email (via the named transactional provider) if behind.
   No reminder if on track or ahead.
3. **Submission**: translating a `WorkItem` (after leasing it, see Domain
   model) creates a `Submission` and a provisional `PointsLedgerEntry`, held
   pending review.
4. **Review**: any other, non-suspended user may claim the next queued item
   (blind dispatch) and approve or reject. Reviewing itself earns points
   immediately — see Anti-abuse for why this is safe against farming.
5. **Approval**: submitter's provisional points confirm, their streak
   updates for the current period, and — subject to the reviewer's trust
   level — the `TranslationSource.submit` call fires.
6. **Rejection**: no points awarded; the item moves to `RevisionPending`,
   giving the original submitter a grace period (48 hours) to see the
   reviewer's comment and resubmit before it opens back up to `Available`
   for anyone. Without this, a second contributor could lease the item out
   from under someone actively revising their translation.
7. **Leaderboard**: per-language, ranked by confirmed points. A user's own
   streak and personal best are always shown regardless of cohort size, so
   the loop still motivates a lone early contributor before a leaderboard
   has enough entries to matter.

No auto-approval path exists at any timeout — an unreviewed item aging past
a threshold surfaces in a "needs review" view instead. The quality gate is
non-negotiable per this project's own reason for existing, which is why
Anti-abuse above is v1 scope rather than a deferred hardening pass.

## Translation Memory

`TranslationSource.fetchMemory` output is not queried live per translation
session. The same scheduled job that evaluates commitments pulls each
adapter's existing translated segments and upserts them into the persisted
`TmSegment` table, keyed by `(language, sourceAdapter, sourceText)`.
Fuzzy-match suggestions shown to a translator are local Postgres queries
against this table. This is what makes the corpus durable and compounding as
more adapters are added later, rather than re-fetched (and re-rate-limited)
from each external platform on every use.

If a `WorkItem`'s upstream source text changes on Crowdin while a submission
against it is pending review, the item is marked stale on next sync and
excluded from dispatch until a maintainer resolves the conflict — it is not
silently approved or silently dropped.

A different drift case: someone translates the same string directly in
Crowdin's own UI (outside Werger entirely) while it's checked out or pending
review here — Crowdin remains usable in parallel, this isn't prevented. The
sync job detects when Crowdin already shows an approved translation for a
`WorkItem`'s `externalId` and moves it to `Retired` rather than leaving it to
block on a maintainer. A submitter whose own submission predates the
upstream change still gets their points for the work done, even though it
won't be the version that ships.

## Data retention

On account deletion: a user's approved, already-upstream contributions are
retained (attributed to a generic "former contributor" label, not deleted —
they're live translations other users depend on) while PII (email, auth
provider link) is purged. Pending submissions and in-flight reviews authored
by a deleted user are cancelled and their `WorkItem`s released back to
`Available` rather than left orphaned.

## Error handling

- Crowdin API failures (rate limit, transient auth failure): the sync/submit
  job retries with backoff; the affected `WorkItem`/`Submission` stays in its
  prior state, with no user-visible failure on a transient blip.
- A `submit` that fails after local approval lands in
  `UpstreamApprovalPending` (see `CrowdinSource.submit` above) — visible and
  retryable, not silently retried forever or silently dropped.

## Testing

- `TranslationSource` as a trait: `CrowdinSource` gets a fake implementation
  for fast unit tests of the gamification logic, plus a guarded live-API
  integration test in CI covering both the read path (status checks, the
  pattern `dengjen-nvda`'s existing Crowdin CI script already uses) and the
  write path (`POST /translations` + `POST /approvals`), which is new and
  unverified in this org and needs its own explicit test rather than
  inherited confidence from the read-only script.
- Points and streak calculation are pure functions of `(commitment tier,
  ledger, calendar time, user timezone)`, tested without a database.
- Concurrency-sensitive paths — `WorkItem` leasing, the
  `PendingReview`-exit-on-review transition, and the unique payout
  constraint — get tests that specifically exercise the race (two
  simultaneous lease attempts, two simultaneous reviews), not just the
  happy path.

## Open questions / risks (implementation-time, not resolved here)

- Crowdin API rate limits are not expected to matter at v1's scale (a small
  contributor base) but aren't verified against Crowdin's published limits.
- Exact commitment-tier action quotas (Light/Medium/Heavy) are illustrative
  here and need real tuning once there's usage data.
- **Scope vs. the NLnet grant timeline.** This project has no line item in
  `nlnet-proposal.md`'s budget and is being scoped for build in the same
  window as the Nov 3, 2026 deadline the grant targets. Whether to build it
  now, defer it past the grant decision, or shrink v1 further is a call this
  spec deliberately leaves to the maintainer rather than resolving by default.

## Repo

New repository, `ZirekHQ/dengjen-werger`, does not exist yet — no git repo
has been initialized locally. sbt-based Scala project, matching this
maintainer's JVM/Scala conventions (immutable data, `Either`/`IO` over
exceptions, no `var`/`null`/`return`). Detailed file layout is deferred to
the implementation plan.

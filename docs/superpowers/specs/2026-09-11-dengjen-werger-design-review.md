# Design Spec Review (v2): Dengjen Werger

**Target Document:** [`2026-09-11-dengjen-werger-design.md`](2026-09-11-dengjen-werger-design.md)  
**Date of Review:** 2026-09-11  
**Review Iteration:** 2 (incorporating adversarial review convergence and expanded spec)  
**Reviewer:** Antigravity / DeepMind Pair Programmer

---

## 1. Executive Evaluation of the Revised Spec

**Verdict: Exceptional progression. Ready for phased implementation planning.**

The revised specification is significantly stronger and more robust than the initial draft. The authors have closed nearly every major architectural ambiguity identified in previous reviews:
- **Operational Reality:** Explicitly shifted from an unviable in-process Cloud Run timer to Cloud Scheduler hitting an authenticated `POST /internal/jobs/evaluate-and-sync` endpoint.
- **GraalVM Compatibility:** Replaced reflection-heavy JDBC/HikariCP with **Skunk** (pure Scala 3 / Cats Effect / fs2 Postgres client), neutralizing native-image build failures.
- **Concurrency & Data Integrity:** Added 30-minute checkout leases on `WorkItem`s, atomic state transitions out of `PendingReview`, and DB-level anti-self-review constraints.
- **Anti-Abuse & Quality Controls:** Introduced blind FIFO dispatch, reviewer rate-limiting, single-payout ledger uniqueness, and trust-gated upstream synchronization.
- **Security & Blast Radius:** Restricted Crowdin service credentials to a `kmr`-scoped role within `nvdaaddons`.
- **Honest Strategic Framing:** Transparently called out the organizational tension between Dengjen Werger and the Nov 3, 2026 NLnet grant deadline.

With the core architecture solidly locked down, this second review focuses on **subtle runtime edge cases, protocol traps, the "Day-1 Bootstrap Paradox," and workload phasing**.

---

## 2. Technical & Runtime Nuances

### 2.1 Skunk on Supabase Supavisor Transaction Pooler (Port 6543)
- **The Trap:** The spec specifies connecting via Supabase's transaction pooler (port `6543`) using Skunk. Supavisor / pgBouncer in **transaction pooling mode** binds a backend server connection only for the duration of a transaction. Traditional named prepared statements (`PREPARE stmt ...`) can fail or collide across pooled connections unless specifically configured.
- **Mitigation for Implementation:**
  - When initializing the Skunk `Session.pooled`, ensure statement preparation is either scoped to explicit transactions or configured with ephemeral/unnamed statement modes or Skunk's `Strategy.SearchPath` settings to prevent `prepared statement does not exist` errors on pooler reuse.
  - Test this connection mode immediately during initial backend scaffolding.

### 2.2 The "Day-1 Bootstrap Paradox" in Review Gating
- **The Issue:** The spec requires a reviewer to have reached a trust threshold (e.g., 5 accepted submissions) before their approval triggers an upstream push to Crowdin.
- **The Consequence:** On Day 1, *no* volunteer has 5 accepted submissions. If every initial submission approved by peer volunteers accumulates locally and waits for maintainer countersigning, the maintainer immediately becomes the exact bottleneck the project was created to alleviate.
- **Recommendation:**
  - Provide an initial administrative seed: maintainer and trusted seed Kurmanji speakers must be assigned `Role.Reviewer` or `Role.Maintainer` directly in the database migration/seed script.
  - Implement a simple "Maintainer Express Review" queue view in the API so the maintainer can batch-countersign early submissions in seconds rather than having to inspect them through normal contributor workflows.

### 2.3 Rejection, Resubmission, and Queue Exclusivity
- **The Issue:**
  > *"Rejection: no points awarded; the item returns to `Available` with the reviewer's comment visible to the original submitter for resubmission."*
  
  If a rejected item returns immediately to the global `Available` pool, any contributor can lease it. If Contributor B leases it, Contributor A (who received the reviewer's critique and is actively revising their Kurmanji translation) cannot submit their revised work.
- **Recommendation:**
  - When rejected, transition `WorkItem` to a state like `RevisionPending(submitterId, reviewerComment, expiresAt)` giving the original author a dedicated grace period (e.g. 48 hours) to address feedback and resubmit before returning the string to the general `Available` pool.

### 2.4 Upstream Drift vs. Local Translation Memory
- **The Spec states:**
  > *"If a `WorkItem`'s upstream source text changes on Crowdin while a submission against it is pending review, the item is marked stale on next sync..."*
- **Refinement:**
  - What if a string was *already translated on Crowdin directly* (outside Dengjen Werger) while an item was checked out or pending review in Werger?
  - The TM sync job should detect if Crowdin's `kmr` translation status for `externalId` is already 100% / approved. In that case, Werger should silently retire the local `WorkItem` without penalizing the contributor (perhaps awarding partial points if they had submitted before upstream was updated).

---

## 3. Strategic Realignment: The NLnet Timeline

The spec explicitly notes:
> *"Dengjen Werger is unfunded scope competing with the Nov 3, 2026 NLnet grant deadline for the same solo-maintainer hours..."*

Given that the maintainer is solo and the deadline is firm, building the entire spec in one monolithic push presents high delivery risk. 

To ensure Dengjen Werger **accelerates** rather than **derails** the maintainer's primary mission, the implementation plan should structure v1 into three tight milestones:

```
┌─────────────────────────────────────────────────────────────┐
│ Milestone 1: The Read/Write Pipeline Proof (1-2 days)       │
│ • Skunk + Supabase DB schema + Migrations                   │
│ • Live Crowdin API adapter test (POST translation & approval)│
│ • Validates all external integrations before writing UI     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Milestone 2: Core Domain & Gamification Engine              │
│ • Pure state machine: Leases, Submissions, Blind Reviews    │
│ • Points ledger, Streak pure function, Anti-abuse checks    │
│ • /internal/jobs/evaluate-and-sync endpoint                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Milestone 3: Contributor Client & Onboarding                │
│ • Lightweight SPA (Supabase Auth JWT)                       │
│ • Translate view, Blind Review view, Streak/Leaderboard     │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Final Recommendation

The specification is thoroughly vetted, battle-tested against edge cases, and structurally sound. 

The next step is to create the formal **Implementation Plan (`implementation_plan.md`)**, defining the precise sbt build setup, Skunk session management, database migrations, and API route contracts.

# Test Pyramid Design

**Status:** Approved
**Related:** `docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md`

## Problem

All tests currently live in one flat `src/test/scala/werger/` tree and run
through a single `sbt test`. That tree already mixes three different kinds
of test — pure/stubbed unit tests, a test that needs a real dev Supabase
Postgres, and (per the plan's Task 7, not yet written) a guarded test that
hits the real Crowdin API — with no structural signal for which is which.
The master plan's remaining ~15 tasks add more of the same mix (several
upcoming service tests need "a real local Postgres," per the plan's
Milestone 2 introduction), so the ambiguity compounds every task from here
forward unless the tiers are separated now.

## Design

### Tiers

| Tier | Definition | Directory | sbt command |
|---|---|---|---|
| Unit | Pure functions, or I/O stubbed in-process (in-memory http4s routes) — no real network or database | `src/test/scala` | `sbt test` |
| Integration | Real infrastructure this project controls — the dev Supabase Postgres via Supavisor | `src/it/scala` | `sbt it:test` |
| E2E | Live third-party services (the real Crowdin API), or the fully deployed system | `src/e2e/scala` | `sbt e2e:test` |

Each mirrors the `werger.*` package structure of `src/main/scala`.

### sbt wiring

```scala
lazy val E2e = config("e2e") extend Test

lazy val root = (project in file("."))
  .configs(IntegrationTest, E2e)
  .settings(
    Defaults.itSettings,
    inConfig(E2e)(Defaults.testSettings),
    libraryDependencies ++= Seq(
      // ...existing dependencies...
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % "it,test"
    )
  )
```

`IntegrationTest` is sbt's built-in second test configuration; `Defaults.itSettings`
gives it its own source directory and classpath. It does not automatically
inherit `Test`-scoped dependencies, so shared test dependencies are scoped
`"it,test"` explicitly. `E2e` is a custom configuration that `extend`s `Test`,
which means it inherits `Test`'s classpath (including munit) without a
separate scoping entry.

### Credential guard, unchanged

A test that needs credentials it might not have (Postgres for `it`, Crowdin
for `e2e`) checks `sys.env.get(...)` *outside* any `IO` block and substitutes
a skip-stub test when absent — the pattern already fixed on
`DbPoolingSpec` and specified for the plan's `CrowdinSourceIntegrationSpec`.
This is unrelated to the tier split itself: without it, a missing env var
throws inside `IO`, which Cats Effect's runtime doesn't catch (JVM `Error`s
bypass `NonFatal` handling), hanging the run instead of failing or skipping.
The tier split organizes *where* guarded tests live; it doesn't replace
the guard.

### Current file migration

- `src/test/scala/werger/adapters/db/DbPoolingSpec.scala` moves to
  `src/it/scala/werger/adapters/db/DbPoolingSpec.scala`, carrying the
  missing-credential guard from PR #40 (this branch is off `main`, which
  doesn't have that fix yet — the guard is needed regardless of which PR
  lands first, since `sbt it:test` without creds hangs the same way `sbt
  test` did before that fix).
- `ModelSpec`, `RoutesSpec`, `CrowdinClientCodecSpec`, `CrowdinClientSpec` stay
  under `src/test/scala` — all pure or stubbed, no real network or DB.
- The plan's Task 7 test, written directly against this convention rather
  than migrated later, is named `CrowdinSourceE2eSpec` and lives under
  `src/e2e/scala/werger/adapters/crowdin/` from the start (the plan's own
  text calls it `CrowdinSourceIntegrationSpec`; that name predates this
  design and is superseded by it).

### CI

`.github/workflows/ci.yml` splits its one step into two jobs:

- `unit`: `sbt test scalafmtCheckAll "scalafixAll --check"` — no `DB_*`
  secrets needed once `DbPoolingSpec` moves out of this tier.
- `integration`: `sbt it:test`, with the `DB_*` secrets already configured
  in the repo (moved from the old combined step, not newly added).

No `e2e` CI job. E2E tests run manually via `sbt e2e:test` with
`CROWDIN_TEST_TOKEN`/`CROWDIN_TEST_STRING_ID`, documented in
`CONTRIBUTING.md` — live-Crowdin calls and (later) full deployed-system
checks are not run on every PR.

### Documentation

`CONTRIBUTING.md` gains a "Testing pyramid" section recording the three
tiers, their directories, their commands, and the guard convention. This
is what lets the plan's remaining tasks (which still name `src/test/scala/...`
paths for things like `LeaseServiceSpec`) get routed to the correct tier
at implementation time, without editing the plan document itself: a task
whose test needs a real Postgres session goes to `src/it/scala` regardless
of what path the plan text names, per this convention.

## Out of scope

- Rewriting the ~15 remaining tasks in the master plan to name the correct
  tier's path explicitly — deferred to the `CONTRIBUTING.md` convention
  instead, per the approved migration scope.
- A real e2e tier target (a deployed Cloud Run instance) — none is deployed
  yet; the directory and sbt config exist so the first such test has
  somewhere to go, but nothing populates it beyond the renamed Crowdin
  live-API test.

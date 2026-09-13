# Test Pyramid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the project's tests into three sbt configurations — unit (`src/test`), integration (`src/it`), e2e (`src/e2e`) — and wire CI, docs, and one Db-hardening fix around that split.

**Architecture:** One sbt project, three test configurations (`Test`, a custom `IntegrationTest`, a custom `E2e`), both non-`Test` configs defined as `config(name) extend Test` so they inherit `Test`'s classpath and dependencies without using sbt's now-deprecated built-in `IntegrationTest`/`Defaults.itSettings`. CI splits its single job into two concurrent jobs (`unit`, `integration`); e2e stays manual, documented in `CONTRIBUTING.md`.

**Tech Stack:** sbt 1.13, `sbt-scalafix` 0.14.8, `sbt-scalafmt` 2.6.2, munit-cats-effect 2.2.0. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-12-test-pyramid-design.md` — read both; this plan argues from that spec.

## Global Constraints

- Both new sbt configurations are defined via `config(name) extend Test`, never the built-in `IntegrationTest`/`Defaults.itSettings` (deprecated in sbt 1.9+, removed in sbt 2.x).
- Every test in the integration or e2e tier that needs credentials checks for them with `sys.env.get(...)` *before* touching any `IO`, substituting a "skipped — set FOO..." test when absent. A missing var read eagerly during object initialization throws a fatal `ExceptionInInitializerError` that Cats Effect's runtime doesn't catch, hanging the run; reading it lazily inside a deferred `IO` instead surfaces as a normal, catchable failure.
- CI's `unit` and `integration` jobs run concurrently — no `needs` between them.
- No `var`, `null`, or `return` in Scala code; model absence with `Option`, failure with `Either`/`IO`'s error channel.
- Every task ends green (tests passing) and committed before moving to the next.
- **Task ordering matters here more than usual**: `DbPoolingSpec` (still in `src/test`, unguarded, until Task 3 moves it) hangs `sbt test` indefinitely if any task's verification step runs the full unit suite before Task 1's guard is in place — confirmed empirically while drafting this plan. Task 1 fixes that first, precisely so every later task's `sbt test` verification step is safe to run.

---

## File Structure

```
src/test/scala/werger/adapters/db/DbPoolingSpec.scala    # modify (Task 1): add credential guard, in place
build.sbt                                                 # modify (Task 2): add IntegrationTest/E2e configs
src/it/scala/werger/adapters/db/DbPoolingSpec.scala       # create (Task 3): moved from src/test
src/main/scala/werger/adapters/db/Db.scala                # modify (Task 4): buildPooled(lookup) replaces eager sys.env vals
src/test/scala/werger/adapters/db/DbSpec.scala            # create (Task 4): unit test for buildPooled's failure mode
.github/workflows/ci.yml                                  # modify (Task 5): split into unit + integration jobs
CONTRIBUTING.md                                           # modify (Task 5): add "Testing pyramid" section
```

`src/test/scala/werger/adapters/db/DbPoolingSpec.scala` is deleted in Task
3 (moved, not copied) — Task 1 modifies it in its original location first.

---

### Task 1: Guard `DbPoolingSpec` in place

**Files:**
- Modify: `src/test/scala/werger/adapters/db/DbPoolingSpec.scala`

**Interfaces:** none — this task only changes a test's internals; nothing later depends on new names or types from it.

- [ ] **Step 1: Confirm the hang this task fixes**

Run: `env -u DB_HOST -u DB_PORT -u DB_USER -u DB_PASSWORD -u DB_NAME timeout 30 sbt test; echo "exit=$?"`
Expected: `exit=124` (the `timeout` command's own timeout-killed exit code) — `Db`'s `private val host = sys.env("DB_HOST")` throws a fatal `ExceptionInInitializerError` when `DbPoolingSpec` references `Db.pooled`, which Cats Effect's runtime doesn't catch as a normal failure, hanging the run instead of failing.

- [ ] **Step 2: Add the guard**

Replace `src/test/scala/werger/adapters/db/DbPoolingSpec.scala` with:

```scala
package werger.adapters.db

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import skunk.codec.all.*
import skunk.implicits.*

class DbPoolingSpec extends CatsEffectSuite:
  // Db reads DB_HOST etc. from sys.env; without it set, this needs to skip
  // cleanly (a fork PR gets no repository secrets, and a local run may have
  // none either) rather than fail on a suite that was never going to have
  // what it needs.
  sys.env.get("DB_HOST") match
    case Some(_) =>
      test("running the same query many times over a small pool doesn't hit a stale prepared statement"):
        Db.pooled.use { pool =>
          val query = pool.use(_.unique(sql"select 1".query(int4)))
          (1 to 20).toList.parTraverse(_ => query).map(_.forall(_ == 1)).assert
        }
    case None =>
      test("skipped — set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against real Supabase"):
        IO.unit
```

- [ ] **Step 3: Verify the hang is gone**

Run: `env -u DB_HOST -u DB_PORT -u DB_USER -u DB_PASSWORD -u DB_NAME sbt test`
Expected: PASS in a few seconds, 4 tests total (`ModelSpec`, `CrowdinClientSpec`, `DbPoolingSpec`'s skip-stub, `RoutesSpec`).

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/werger/adapters/db/DbPoolingSpec.scala
git commit -m "fix: skip DbPoolingSpec cleanly when DB creds are unset"
```

---

### Task 2: `IntegrationTest` and `E2e` sbt configurations

**Files:**
- Modify: `build.sbt`

**Interfaces:**
- Produces: sbt configurations `IntegrationTest` (source dir `src/it/scala`) and `E2e` (source dir `src/e2e/scala`), usable via `sbt it:test`/`sbt IntegrationTest/test` and `sbt e2e:test`/`sbt E2e/test`. No source files exist in either yet — this task only wires the mechanism.

- [ ] **Step 1: Confirm the configs don't exist yet**

Run: `sbt "IntegrationTest / test"`
Expected: FAIL — `[error] No such setting/task` / `[error] IntegrationTest / test`, since `build.sbt` never attaches an `IntegrationTest` configuration to the `root` project.

- [ ] **Step 2: Add the configurations to `build.sbt`**

```scala
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalacOptions += "-Wunused:all"

lazy val IntegrationTest = config("it") extend Test
lazy val E2e = config("e2e") extend Test

lazy val root = (project in file("."))
  .configs(IntegrationTest, E2e)
  .settings(
    name := "dengjen-werger",
    inConfig(IntegrationTest)(Defaults.testSettings),
    inConfig(E2e)(Defaults.testSettings),
    scalafixConfigSettings(IntegrationTest),
    scalafixConfigSettings(E2e),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.7.1",
      "org.http4s"    %% "http4s-ember-server" % "0.23.37",
      "org.http4s"    %% "http4s-ember-client" % "0.23.37",
      "org.http4s"    %% "http4s-circe"        % "0.23.37",
      "org.http4s"    %% "http4s-dsl"          % "0.23.37",
      "io.circe"      %% "circe-generic"       % "0.14.16",
      "org.tpolecat"  %% "skunk-core"          % "1.0.0",
      "com.github.jwt-scala" %% "jwt-circe"    % "11.0.4",
      "org.typelevel" %% "munit-cats-effect"   % "2.2.0" % "it,e2e,test"
    )
  )
```

`lazy val IntegrationTest = config("it") extend Test` shadows sbt's built-in
`IntegrationTest` value within this build file, replacing it with a plain
custom configuration that extends `Test` — this is what gives both new
tiers `Test`'s classpath (including munit and any future shared test
fixtures) instead of the built-in `IntegrationTest`'s `Runtime`-only
classpath, and avoids the deprecated `Defaults.itSettings` entirely.
`scalafixConfigSettings(...)` is required for each because `sbt-scalafix`
only wires `Compile` and `Test` by default — without it, `scalafixAll
--check` silently skips both new source trees.

- [ ] **Step 3: Verify both configs now resolve, with zero sources, no deprecation warnings**

Run: `sbt "IntegrationTest / test" "E2e / test"`
Expected: PASS — both succeed immediately (no test classes found in either yet). No warnings mentioning `IntegrationTest` or `Defaults.itSettings` appear in the output.

- [ ] **Step 4: Verify the existing unit suite and scalafix/scalafmt checks still pass**

Run: `sbt test scalafmtCheckAll "scalafixAll --check"`
Expected: PASS, 4 tests total — same result as before this task; Task 1's guard is what makes this safe to run.

- [ ] **Step 5: Commit**

```bash
git add build.sbt
git commit -m "feat: add IntegrationTest and E2e sbt configurations"
```

---

### Task 3: Move `DbPoolingSpec` into the integration tier

**Files:**
- Delete: `src/test/scala/werger/adapters/db/DbPoolingSpec.scala`
- Create: `src/it/scala/werger/adapters/db/DbPoolingSpec.scala`

**Interfaces:**
- Consumes: `Db.pooled` (unchanged in this task — Task 4 refactors its internals, not its signature).
- Produces: nothing new for later tasks; this is the first real file to occupy the `IntegrationTest` config's source tree.

- [ ] **Step 1: Move the file (content unchanged from Task 1)**

```bash
git mv src/test/scala/werger/adapters/db/DbPoolingSpec.scala src/it/scala/werger/adapters/db/DbPoolingSpec.scala
```

- [ ] **Step 2: Run the integration tier without credentials**

Run: `env -u DB_HOST -u DB_PORT -u DB_USER -u DB_PASSWORD -u DB_NAME sbt "IntegrationTest / test"`
Expected: PASS in a few seconds — one test, the skip-stub, passes.

- [ ] **Step 3: Confirm the unit tier no longer runs this test**

Run: `sbt test`
Expected: PASS, 3 tests total (`ModelSpec`, `CrowdinClientSpec`, `RoutesSpec`) — no `DbPoolingSpec` line, since it now lives under `IntegrationTest`, not `Test`.

- [ ] **Step 4: If you have real dev Supabase credentials, run the integration tier against them**

Run: `DB_HOST=<project>.pooler.supabase.com DB_PORT=6543 DB_USER=postgres.<ref> DB_PASSWORD=*** DB_NAME=postgres sbt "IntegrationTest / test"`
Expected: PASS. Skip this step if you don't have credentials handy — CI's `integration` job (Task 5) is what proves this continuously against the repo's real dev secrets.

- [ ] **Step 5: Commit**

```bash
git add -A src/it/scala/werger/adapters/db/DbPoolingSpec.scala src/test/scala/werger/adapters/db/DbPoolingSpec.scala
git commit -m "refactor: move DbPoolingSpec into the integration tier"
```

---

### Task 4: `Db.buildPooled` — fail through `IO`, not a fatal `Error`

**Files:**
- Modify: `src/main/scala/werger/adapters/db/Db.scala`
- Create: `src/test/scala/werger/adapters/db/DbSpec.scala`

**Interfaces:**
- Produces: `def buildPooled(lookup: String => Option[String]): Resource[IO, Resource[IO, Session[IO]]]`, with `val pooled = buildPooled(sys.env.get)` as the production wiring later tasks (any future repo needing a session) keep using unchanged.

Task 1's guard stops the one test that touches `Db` from hanging when
credentials are absent, by never evaluating `Db.pooled` at all in that
case. It does nothing for any *other* future code that references `Db`
without a guard — `Db`'s current `private val host = sys.env("DB_HOST")`
(and its siblings) are still evaluated eagerly at object-init time, so any
such reference still throws a fatal `ExceptionInInitializerError` outside
Cats Effect's error handling. This task removes that risk at the source
by making config-gathering part of the `IO` itself, so a missing var
becomes a normal, catchable `IO` failure no matter who touches `Db.pooled`
or when.

- [ ] **Step 1: Write the failing test**

```scala
package werger.adapters.db

import cats.effect.IO
import munit.CatsEffectSuite

class DbSpec extends CatsEffectSuite:
  test("buildPooled fails through IO's error channel, not a fatal Error, when a required var is missing"):
    Db.buildPooled(_ => None).use(_ => IO.unit).attempt.map {
      case Left(_: NoSuchElementException) => ()
      case other => fail(s"expected a NoSuchElementException raised through IO, got: $other")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly werger.adapters.db.DbSpec"`
Expected: FAIL — `Db.buildPooled` does not exist yet (compile error).

- [ ] **Step 3: Implement**

Replace `src/main/scala/werger/adapters/db/Db.scala` with:

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

object Db:
  private given Meter[IO] = Meter.Implicits.noop[IO]

  private def required(lookup: String => Option[String], name: String): IO[String] =
    IO.fromOption(lookup(name))(new NoSuchElementException(s"Missing required env var: $name"))

  /** Builds the pool description from an env-var lookup function without
    * reading any of them yet — `lookup` only runs once the returned
    * `Resource` is used, so a missing var surfaces as a normal `IO`
    * failure (catchable via `.attempt`) instead of a fatal `Error` thrown
    * during object initialization.
    */
  def buildPooled(lookup: String => Option[String]): Resource[IO, Resource[IO, Session[IO]]] =
    Resource.eval {
      for
        host     <- required(lookup, "DB_HOST")
        port     <- IO(lookup("DB_PORT").getOrElse("6543").toInt)
        user     <- required(lookup, "DB_USER")
        password <- required(lookup, "DB_PASSWORD")
        database <- required(lookup, "DB_NAME")
      yield (host, port, user, password, database)
    }.flatMap { case (host, port, user, password, database) =>
      Session
        .Builder[IO]
        .withHost(host)
        .withPort(port)
        .withUserAndPassword(user, password)
        .withDatabase(database)
        .withTypingStrategy(skunk.TypingStrategy.SearchPath)
        .pooled(max = 8)(using Tracer.Implicits.noop[IO])
    }

  val pooled: Resource[IO, Resource[IO, Session[IO]]] = buildPooled(sys.env.get)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly werger.adapters.db.DbSpec"`
Expected: PASS

- [ ] **Step 5: Run the full unit tier and the integration tier's skip path to confirm no regression**

Run: `sbt test` then `env -u DB_HOST -u DB_PORT -u DB_USER -u DB_PASSWORD -u DB_NAME sbt "IntegrationTest / test"`
Expected: both PASS — `Db.pooled`'s public type and behavior are unchanged; only how it fails on missing config changed.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/werger/adapters/db/Db.scala src/test/scala/werger/adapters/db/DbSpec.scala
git commit -m "fix: make Db.pooled fail through IO, not a fatal Error, on missing config"
```

---

### Task 5: CI split and `CONTRIBUTING.md` documentation

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `CONTRIBUTING.md`

**Interfaces:** none — this task wires already-built mechanism (Tasks 1-4) into CI and docs; it produces nothing later tasks consume.

- [ ] **Step 1: Split the CI workflow into concurrent `unit` and `integration` jobs**

Replace `.github/workflows/ci.yml` with:

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
permissions:
  contents: read
jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1  # v7.0.1
        with:
          persist-credentials: false
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6  # v6.0.1
        with:
          distribution: temurin
          java-version: "25"
      - uses: sbt/setup-sbt@82da71df4e122282484a99a8d70096bc2369dbd8  # v1.5.9
      - run: sbt --allow-empty test scalafmtCheckAll "scalafixAll --check"
  integration:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1  # v7.0.1
        with:
          persist-credentials: false
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6  # v6.0.1
        with:
          distribution: temurin
          java-version: "25"
      - uses: sbt/setup-sbt@82da71df4e122282484a99a8d70096bc2369dbd8  # v1.5.9
      - run: sbt --allow-empty "IntegrationTest / test"
        env:
          DB_HOST: ${{ secrets.DB_HOST }}
          DB_PORT: ${{ secrets.DB_PORT }}
          DB_USER: ${{ secrets.DB_USER }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
          DB_NAME: ${{ secrets.DB_NAME }}
```

The two jobs have no `needs` between them, so they run concurrently for
the fastest PR feedback — branch protection requiring both checks is what
actually enforces that both pass before merge, not job ordering. On a
fork PR, GitHub Actions doesn't expose repository secrets, so `integration`
runs with `DB_*` unset; Task 1's guard is exactly what keeps that job
green (skipped, not failed) in that case.

This renames the existing `build` job to `unit`. **Branch protection on
`main` currently requires a check named `build` by that exact name** (set
up per Task 0 of the master plan) — once this PR merges, whoever
administers repo settings needs to update the required checks to `unit`
and `integration`, the same one-time, separately-done action Task 0
called out for the original check. Note this to whoever reviews/merges
this PR; it isn't something this task's steps can verify from inside the
repo.

- [ ] **Step 2: Add the testing pyramid section to `CONTRIBUTING.md`**

Append to `CONTRIBUTING.md`:

```markdown

## Testing pyramid

Tests are split into three tiers:

| Tier | Directory | Command | Covers |
|---|---|---|---|
| Unit | `src/test/scala` | `sbt test` (`Test / test`) | Pure functions and I/O stubbed in-process — no real network or database. Runs on every PR. |
| Integration | `src/it/scala` | `sbt it:test` (`IntegrationTest / test`) | Real infrastructure this project controls — the dev Supabase Postgres via Supavisor. Runs on every PR using repo secrets; on a fork PR (no secrets available) it skips cleanly instead of failing. |
| E2E | `src/e2e/scala` | `sbt e2e:test` (`E2e / test`) | Live third-party services (the real Crowdin API) or the fully deployed system. Run manually with the relevant credentials — not run in CI. |

A test in the integration or e2e tier that needs credentials checks for
them with `sys.env.get(...)` *before* touching any `IO`, substituting a
"skipped — set FOO to run against real X" test when they're absent.
Reading a missing env var inside an `IO` block throws a fatal JVM `Error`
that Cats Effect's runtime doesn't catch as a normal failure, which hangs
the whole test run instead of failing or skipping it.
```

- [ ] **Step 3: Verify CI locally by running exactly what each job runs**

Run: `sbt --allow-empty test scalafmtCheckAll "scalafixAll --check"`
Expected: PASS — this is the `unit` job's exact command.

Run: `env -u DB_HOST -u DB_PORT -u DB_USER -u DB_PASSWORD -u DB_NAME sbt --allow-empty "IntegrationTest / test"`
Expected: PASS (the skip-stub test) — this is the `integration` job's command, run here without secrets the way a fork PR would see it.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml CONTRIBUTING.md
git commit -m "ci: split unit and integration jobs; document the testing pyramid"
```

---

## After this plan

Push the branch and open a PR (`gh pr create --repo ZirekHQ/dengjen-werger`), same as every other task in this project. Flag the branch-protection rename (Task 5, Step 1) explicitly in the PR description so whoever merges it knows to update required checks from `build` to `unit`/`integration` before or right after merging.

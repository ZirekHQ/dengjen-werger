# sbt Build Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps between this repo's sbt setup and the OSS Typelevel/Cats-Effect baseline: compiler hygiene, a real coverage gate, Cloud-Run packaging, build metadata in `/healthz`, centralized dependencies, and command aliases.

**Architecture:** Seven independent settings/plugin additions to `build.sbt` and `project/plugins.sbt`, plus one source split (`Db.scala` → `Db.scala` + `DbConfig.scala`) needed to make the coverage gate meaningful. Each task lands, compiles, and is committed on its own.

**Tech Stack:** sbt 1.13.0, Scala 3.9.0, cats-effect/http4s/skunk/circe, munit-cats-effect.

**Spec:** `docs/superpowers/specs/2026-09-12-sbt-build-modernization-design.md`

## Global Constraints

- Scala version stays `3.9.0`; no source changes require a version bump.
- Every plugin version below was resolved and smoke-tested against this exact toolchain during planning — don't substitute a version without re-verifying it resolves (`sbt reload`) and that dependent settings still work.
- `project/*.scala` files (build definition sources, e.g. `Dependencies.scala`) compile under sbt's own Scala 2.12 meta-build, **not** the project's Scala 3.9.0 — they must use Scala 2 syntax (braces, not `:`-indentation), or the build fails to load at all.
- `CI=true sbt compile` must stay clean at every task boundary once Task 1 lands (tpolecat escalates warnings to errors under `CI=true`).
- `sbt lint` (added in Task 4) must stay clean at every task boundary from Task 4 onward.

---

### Task 1: Compiler hygiene — sbt-tpolecat

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt:4` (remove the old flag)

**Interfaces:** None — build config only, no code produced or consumed.

**Verified during planning:** `sbt-tpolecat` **0.5.2** (the version quoted in most current tutorials) fails even a plain `sbt compile` on Scala 3.9.0 — it emits both `-Xfatal-warnings` and `-Werror`, and `-Xfatal-warnings` triggers its own deprecation warning, which `-Werror` then turns into a hard error (`No warnings can be incurred under -Werror`). **0.5.7** (latest as of this plan) does not have this problem. Use 0.5.7.

- [ ] **Step 1: Add the plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.7")
```

- [ ] **Step 2: Remove the manual flag**

In `build.sbt`, delete line 4:

```scala
ThisBuild / scalacOptions += "-Wunused:all"
```

- [ ] **Step 3: Verify a plain build compiles**

Run: `sbt --allow-empty compile`
Expected: `[success]`, no errors.

- [ ] **Step 4: Verify CI mode compiles**

Run: `CI=true sbt --allow-empty compile`
Expected: `[success]`, no errors. (tpolecat escalates warnings to `-Werror` when `CI` is set — this is the real test of Task 1.)

- [ ] **Step 5: Commit**

```bash
git add project/plugins.sbt build.sbt
git commit -m "build: replace manual scalacOptions with sbt-tpolecat"
```

---

### Task 2: Dependency centralization

**Files:**
- Create: `project/Dependencies.scala`
- Modify: `build.sbt:17-27` (the inline `libraryDependencies` block)

**Interfaces:**
- Produces: `Dependencies.all: Seq[sbt.ModuleID]`, consumed by `build.sbt`.

**Note:** this file is compiled under sbt's Scala 2.12 meta-build (see Global Constraints) — brace syntax, not colon-indentation.

- [ ] **Step 1: Create `project/Dependencies.scala`**

```scala
import sbt._

object Dependencies {
  object V {
    val catsEffect = "3.7.1"
    val http4s     = "0.23.37"
    val circe      = "0.14.16"
    val skunk      = "1.0.0"
    val jwtScala   = "11.0.4"
    val munitCE    = "2.2.0"
  }

  val all: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-effect"         % V.catsEffect,
    "org.http4s"    %% "http4s-ember-server" % V.http4s,
    "org.http4s"    %% "http4s-ember-client" % V.http4s,
    "org.http4s"    %% "http4s-circe"        % V.http4s,
    "org.http4s"    %% "http4s-dsl"          % V.http4s,
    "io.circe"      %% "circe-generic"       % V.circe,
    "org.tpolecat"  %% "skunk-core"          % V.skunk,
    "com.github.jwt-scala" %% "jwt-circe"    % V.jwtScala,
    "org.typelevel" %% "munit-cats-effect"   % V.munitCE % "it,e2e,test"
  )
}
```

- [ ] **Step 2: Wire it into `build.sbt`**

Replace the `libraryDependencies ++= Seq(...)` block (lines 17-27) with:

```scala
    libraryDependencies ++= Dependencies.all,
```

- [ ] **Step 3: Verify**

Run: `sbt --allow-empty compile`
Expected: `[success]`, same dependency set resolved (already cached from prior builds, so this should be fast).

- [ ] **Step 4: Commit**

```bash
git add project/Dependencies.scala build.sbt
git commit -m "build: centralize library dependencies in project/Dependencies.scala"
```

---

### Task 3: Split `Db.scala` — extract `DbConfig`

**Files:**
- Create: `src/main/scala/werger/adapters/db/DbConfig.scala`
- Modify: `src/main/scala/werger/adapters/db/Db.scala` (replace entirely)
- Create: `src/test/scala/werger/adapters/db/DbConfigSpec.scala`
- Delete: `src/test/scala/werger/adapters/db/DbSpec.scala`

**Interfaces:**
- Produces: `DbConfig(host: String, port: Int, user: String, password: String, database: String)`, `DbConfig.fromEnv(lookup: String => Option[String]): IO[DbConfig]`.
- Produces: `Db.buildPooled(config: DbConfig): Resource[IO, Resource[IO, Session[IO]]]` (signature changes from today's `buildPooled(lookup: String => Option[String])`).
- Consumes: nothing new. `Db.pooled`'s own type (`Resource[IO, Resource[IO, Session[IO]]]`) is unchanged, so `DbPoolingSpec` (in `src/it`) keeps working untouched.

Today, `Db.scala`'s `required`/parsing logic and its `Session.Builder` call live in one function in one file. Splitting them is what makes the Task 4 coverage gate achievable without needing a live database to hit 80%.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/werger/adapters/db/DbConfigSpec.scala`:

```scala
package werger.adapters.db

import munit.CatsEffectSuite

class DbConfigSpec extends CatsEffectSuite:
  private val allPresent: Map[String, String] = Map(
    "DB_HOST" -> "localhost",
    "DB_PORT" -> "5432",
    "DB_USER" -> "user",
    "DB_PASSWORD" -> "pass",
    "DB_NAME" -> "db"
  )

  test("fromEnv fails through IO's error channel, not a fatal Error, when a required var is missing"):
    DbConfig.fromEnv(_ => None).attempt.map {
      case Left(_: NoSuchElementException) => ()
      case other => fail(s"expected a NoSuchElementException raised through IO, got: $other")
    }

  test("fromEnv succeeds and parses all fields when every var is present"):
    DbConfig.fromEnv(allPresent.get).assertEquals(DbConfig("localhost", 5432, "user", "pass", "db"))

  test("fromEnv defaults DB_PORT to 6543 when unset"):
    DbConfig.fromEnv((allPresent - "DB_PORT").get).map(_.port).assertEquals(6543)

  test("fromEnv fails through IO's error channel on a non-numeric DB_PORT"):
    DbConfig.fromEnv((allPresent + ("DB_PORT" -> "not-a-number")).get).attempt.map {
      case Left(_: NumberFormatException) => ()
      case other => fail(s"expected a NumberFormatException raised through IO, got: $other")
    }
```

Note: no `import cats.effect.IO` — the type is never referenced by name in this file, and tpolecat's `-Wunused` will fail the build on an unused import once Task 1 has landed.

- [ ] **Step 2: Run it to verify it fails to compile**

Run: `sbt --allow-empty "testOnly werger.adapters.db.DbConfigSpec"`
Expected: FAIL — `DbConfig` doesn't exist yet (compile error, not a runtime failure).

- [ ] **Step 3: Create `DbConfig.scala`**

```scala
package werger.adapters.db

import cats.effect.IO

final case class DbConfig(host: String, port: Int, user: String, password: String, database: String)

object DbConfig:
  private def required(lookup: String => Option[String], name: String): IO[String] =
    IO.fromOption(lookup(name))(new NoSuchElementException(s"Missing required env var: $name"))

  def fromEnv(lookup: String => Option[String]): IO[DbConfig] =
    for
      host     <- required(lookup, "DB_HOST")
      port     <- IO(lookup("DB_PORT").getOrElse("6543").toInt)
      user     <- required(lookup, "DB_USER")
      password <- required(lookup, "DB_PASSWORD")
      database <- required(lookup, "DB_NAME")
    yield DbConfig(host, port, user, password, database)
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt --allow-empty "testOnly werger.adapters.db.DbConfigSpec"`
Expected: PASS, 4 tests, 0 failed.

- [ ] **Step 5: Replace `Db.scala`**

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

object Db:
  private given Meter[IO] = Meter.Implicits.noop[IO]

  def buildPooled(config: DbConfig): Resource[IO, Resource[IO, Session[IO]]] =
    Session
      .Builder[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .withTypingStrategy(skunk.TypingStrategy.SearchPath)
      .pooled(max = 8)(using Tracer.Implicits.noop[IO])

  val pooled: Resource[IO, Resource[IO, Session[IO]]] =
    Resource.eval(DbConfig.fromEnv(sys.env.get)).flatMap(buildPooled)
```

- [ ] **Step 6: Delete the old test**

```bash
rm src/test/scala/werger/adapters/db/DbSpec.scala
```

(Its one case — missing var fails through `IO`, not a fatal `Error` — is now covered by `DbConfigSpec`'s first test.)

- [ ] **Step 7: Run the full test suite and formatter**

Run: `sbt --allow-empty test scalafmtCheckAll "scalafixAll --check"`
Expected: all tests pass (`ModelSpec`, `CrowdinClientSpec`, `DbConfigSpec` ×4, `RoutesSpec`), formatter and linter clean. If scalafmt flags `DbConfig.scala` or `Db.scala`, run `sbt scalafmtAll` and re-check — don't hand-format the for-comprehension bindings; let scalafmt own the alignment.

- [ ] **Step 8: Confirm the IT spec still compiles and skips cleanly**

Run: `sbt --allow-empty "IntegrationTest / test"`
Expected: `DbPoolingSpec` runs and passes its own no-DB-creds skip branch (1 total, 0 failed) — it calls `Db.pooled` directly, whose type hasn't changed, so no edits to `DbPoolingSpec.scala` are needed.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/werger/adapters/db/DbConfig.scala src/main/scala/werger/adapters/db/Db.scala \
        src/test/scala/werger/adapters/db/DbConfigSpec.scala
git rm src/test/scala/werger/adapters/db/DbSpec.scala
git commit -m "refactor: split DbConfig parsing out of Db so it's unit-testable without a live DB"
```

---

### Task 4: Coverage gate, CI/Sonar wiring, command aliases

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt` (add settings + aliases)
- Modify: `.github/workflows/ci.yml` (the `unit` job's `run` step)
- Modify: `.github/workflows/sonar.yml` (the compile step)
- Modify: `sonar-project.properties`

**Interfaces:** None new — this wires existing test suites into a gate; it doesn't add production code.

**Depends on Task 3** — the exclusion pattern below only makes sense once `Db.scala` contains just the Skunk-connection call.

**Verified during planning — read before implementing:** `coverageExcludedFiles` (sbt-scoverage 2.4.4, Scala 3) does **not** match what its name implies. It does not match the file's path with the `.scala` extension, and it does not match the dot-qualified class name either. Empirically, the only pattern that reliably excludes `Db.scala` (and only `Db.scala`, not `DbConfig.scala`) is a full-match regex against the file's path **relative to source root, with `/` separators, and no `.scala` extension**: `.*/adapters/db/Db`. Patterns that looked equally reasonable — `.*/adapters/db/Db\.scala`, `.*\.Db` (dot-qualified class name), plain `Db` — were all tested and **do not exclude anything**, silently leaving `Db.scala` in the coverage denominator. Use the exact pattern below; don't "clean it up" to add back the extension or switch to a class-name-style pattern.

- [ ] **Step 1: Add the plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
```

- [ ] **Step 2: Add coverage settings and command aliases to `build.sbt`**

Add near the top of `build.sbt` (top-level, not inside `.settings(...)`):

```scala
addCommandAlias("lint", ";scalafmtCheckAll;scalafixAll --check")
addCommandAlias("fix", ";scalafmtAll;scalafixAll")
addCommandAlias("prep", ";fix;test")
```

Add inside `root`'s `.settings(...)`:

```scala
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum := true,
    coverageExcludedPackages := "werger\\.Main",
    coverageExcludedFiles := ".*/adapters/db/Db",
```

(`coverageExcludedPackages := "werger\\.Main"` excludes `Main` — this one *does* match the dot-qualified name as expected, verified working in planning.)

- [ ] **Step 3: Verify the gate passes locally**

Run: `sbt --allow-empty coverage test coverageReport`
Expected: `[info] Statement coverage.: 100.00%` (verified during planning: `Main` and `Db` excluded, `DbConfig` and `Routes` both fully tested — no coverage minimum failure).

- [ ] **Step 4: Update `ci.yml`'s `unit` job**

Replace the `run` step in the `unit` job (currently `sbt --allow-empty test scalafmtCheckAll "scalafixAll --check"`) with two steps:

```yaml
      - run: sbt --allow-empty coverage test coverageReport
      - run: sbt --allow-empty lint
```

- [ ] **Step 5: Update `sonar.yml`**

Replace:

```yaml
      - run: sbt --allow-empty compile
```

with:

```yaml
      - run: sbt --allow-empty coverage test coverageReport
```

- [ ] **Step 6: Update `sonar-project.properties`**

Add:

```properties
sonar.scala.coverage.xmlReportPaths=target/scala-*/scoverage-report/scoverage.xml
```

- [ ] **Step 7: Commit**

```bash
git add project/plugins.sbt build.sbt .github/workflows/ci.yml .github/workflows/sonar.yml sonar-project.properties
git commit -m "build: add sbt-scoverage 80% gate, wire into CI/Sonar, add lint/fix/prep aliases"
```

---

### Task 5: Versioning and metadata — sbt-dynver

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`

**Interfaces:** Produces `ThisBuild / version` (dynver-derived), consumed by Task 6's Docker tag and Task 7's `BuildInfo.version`.

- [ ] **Step 1: Add the plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
```

- [ ] **Step 2: Add settings to `build.sbt`**

Add near the top (`ThisBuild` scope, alongside `scalaVersion`):

```scala
ThisBuild / dynverSeparator := "-"
ThisBuild / organization := "org.zirekhq"
ThisBuild / homepage := Some(url("https://github.com/ZirekHQ/dengjen-werger"))
ThisBuild / licenses := List("GPL-3.0-or-later" -> url("https://www.gnu.org/licenses/gpl-3.0.html"))
```

`dynverSeparator := "-"` matters: Docker tags forbid `+` (valid charset `[a-zA-Z0-9_.-]`), and dynver's default separator for the distance/sha suffix is `+` — without this, Task 6's `Docker/publishLocal` fails with an invalid-tag error the moment a build isn't exactly on a git tag (which is always, since this repo has no tags yet).

- [ ] **Step 3: Verify**

Run: `sbt --allow-empty 'show version'`
Expected: prints something like `0.0.0-<N>-<sha>` — hyphens only, no `+`. (No tags exist yet, so this is dynver's no-tag fallback form; that's expected, not a defect.)

- [ ] **Step 4: Commit**

```bash
git add project/plugins.sbt build.sbt
git commit -m "build: add sbt-dynver for git-derived versioning, set project metadata"
```

---

### Task 6: Packaging — sbt-native-packager

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`

**Interfaces:** None new — packages the existing `werger.Main` entrypoint.

**Depends on Task 5** (needs `dynverSeparator` for a valid Docker tag).

**Verified during planning — read before implementing:** `eclipse-temurin:21-jre-alpine` does not ship `bash`, but `JavaAppPackaging`'s generated launch script is a bash script. Without a fix, the container builds successfully but **exits immediately on `docker run`** with `env: 'bash': No such file or directory` (exit code 127). The `dockerCommands` override below installs `bash` via `apk` as an extra layer; this was tested end-to-end (`docker run` + `curl /healthz` returned 200) and is required, not optional hardening.

- [ ] **Step 1: Add the plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
```

- [ ] **Step 2: Enable the plugins on `root`**

Change:

```scala
lazy val root = (project in file("."))
  .configs(IntegrationTest, E2e)
```

to:

```scala
lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .configs(IntegrationTest, E2e)
```

Add the import at the top of `build.sbt`:

```scala
import com.typesafe.sbt.packager.docker.{Cmd, DockerPlugin}
```

- [ ] **Step 3: Add packaging settings**

Add inside `root`'s `.settings(...)`:

```scala
    Compile / mainClass := Some("werger.Main"),
    dockerBaseImage := "eclipse-temurin:21-jre-alpine",
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := true,
    // JavaAppPackaging's launch script is bash, which the alpine base doesn't ship.
    dockerCommands := dockerCommands.value.flatMap {
      case cmd @ Cmd("FROM", _*) => List(cmd, Cmd("RUN", "apk", "add", "--no-cache", "bash"))
      case other                 => List(other)
    },
```

- [ ] **Step 4: Build the image**

Run: `sbt --allow-empty Docker/publishLocal`
Expected: `[success]`, ends with `Built image dengjen-werger with tags [<version>, latest]`.

- [ ] **Step 5: Run it and confirm it serves**

```bash
docker run -d -p 18080:8080 --name djw-verify dengjen-werger:latest
sleep 3
curl -s localhost:18080/healthz
docker stop djw-verify && docker rm djw-verify
```

Expected: an HTTP 200 with a body (exact shape lands in Task 7 — at this point in the plan it's still the plain `"ok"` string from today's `Routes.scala`).

- [ ] **Step 6: Commit**

```bash
git add project/plugins.sbt build.sbt
git commit -m "build: add sbt-native-packager, Docker/JavaAppPackaging for Cloud Run"
```

---

### Task 7: BuildInfo → `/healthz`

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`
- Modify: `src/main/scala/werger/http/Routes.scala`
- Modify: `src/test/scala/werger/http/RoutesSpec.scala`
- Modify: `README.md:78`

**Interfaces:**
- Produces: `werger.BuildInfo.{name, version, scalaVersion, gitCommit}` (generated object, `buildInfoPackage := "werger"`).
- Produces: `HealthStatus(status: String, version: String, commit: String, builtAt: String)` in `Routes.scala`, with a circe `Encoder` (and a `Decoder` in the test).

- [ ] **Step 1: Add the plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
```

(Note the group id: `com.eed3si9n`, not `com.github.sbt`.)

- [ ] **Step 2: Enable the plugin and add BuildInfo settings**

Change:

```scala
lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
```

to:

```scala
lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
```

Add near the top of `build.sbt`, above `lazy val root`:

```scala
def gitCommit: String =
  sys.env.get("GITHUB_SHA").map(_.take(7)).orElse(
    scala.util.Try(scala.sys.process.Process("git rev-parse --short HEAD").!!.trim).toOption
  ).getOrElse("unknown")
```

`GITHUB_SHA` is checked first because it's already present in CI without shelling out; the `git rev-parse` fallback covers local dev; `"unknown"` covers a checkout with no `.git` at all (e.g. a release tarball) rather than failing the build.

Add inside `root`'s `.settings(...)`:

```scala
    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion,
      BuildInfoKey.action("gitCommit")(gitCommit)
    ),
    buildInfoPackage := "werger",
```

- [ ] **Step 3: Update `Routes.scala`**

```scala
package werger.http

import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import werger.BuildInfo

final case class HealthStatus(status: String, version: String, commit: String, builtAt: String)
object HealthStatus:
  given Encoder[HealthStatus] = deriveEncoder

object Routes:
  val app: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "healthz" =>
    Ok(
      HealthStatus(
        status = "ok",
        version = BuildInfo.version,
        commit = BuildInfo.gitCommit,
        builtAt = java.time.Instant.now().toString
      )
    )
  }
```

- [ ] **Step 4: Update `RoutesSpec.scala`**

```scala
package werger.http

import cats.effect.IO
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*

class RoutesSpec extends CatsEffectSuite:
  given Decoder[HealthStatus] = deriveDecoder

  test("GET /healthz returns 200 with a status: ok health payload"):
    val req = Request[IO](Method.GET, uri"/healthz")
    Routes.app.orNotFound
      .run(req)
      .flatMap { r =>
        r.as[HealthStatus].map(b => (r.status, b.status))
      }
      .assertEquals((Status.Ok, "ok"))
```

Import order matters here: `scalafixAll --check` (run in Task 4's `sbt lint`) enforces alphabetical import sorting and will fail the build on a misordered list — keep the order exactly as above.

- [ ] **Step 5: Run the test suite, coverage gate, and lint**

Run: `sbt --allow-empty coverage test coverageReport scalafmtCheckAll "scalafixAll --check"`
Expected: all tests pass including the updated `RoutesSpec`; coverage stays at 100% (verified during planning); formatter and linter clean.

- [ ] **Step 6: Update the README**

In `README.md`, replace line 78:

```markdown
The command prints `ok`.
```

with:

```markdown
The command prints a JSON body: `{"status":"ok","version":"...","commit":"...","builtAt":"..."}`.
```

- [ ] **Step 7: Rebuild the Docker image and confirm the new payload**

```bash
sbt --allow-empty Docker/publishLocal
docker run -d -p 18080:8080 --name djw-verify dengjen-werger:latest
sleep 3
curl -s localhost:18080/healthz
docker stop djw-verify && docker rm djw-verify
```

Expected: JSON body with `status`, `version`, `commit` (a 7-character git SHA), and `builtAt` fields. Verified during planning: `{"status":"ok","version":"0.0.0-...","commit":"70bad8a","builtAt":"2026-...Z"}`.

- [ ] **Step 8: Commit**

```bash
git add project/plugins.sbt build.sbt src/main/scala/werger/http/Routes.scala \
        src/test/scala/werger/http/RoutesSpec.scala README.md
git commit -m "feat: expose BuildInfo version/commit/builtAt through /healthz"
```

---

## Final verification (after all 7 tasks)

- [ ] `sbt --allow-empty prep` — runs `fix` (format + lint-fix) then `test`; expect clean.
- [ ] `CI=true sbt --allow-empty compile` — expect clean (tpolecat fatal warnings).
- [ ] `sbt --allow-empty coverage test coverageReport` — expect ≥ 80%, no `Coverage minimum was not reached` error.
- [ ] `sbt --allow-empty "IntegrationTest / test"` — expect `DbPoolingSpec` to run its no-creds skip branch cleanly (or the real query if `DB_HOST` etc. are set).
- [ ] `sbt --allow-empty Docker/publishLocal` then run the image and `curl /healthz` — expect 200 with the JSON health payload.

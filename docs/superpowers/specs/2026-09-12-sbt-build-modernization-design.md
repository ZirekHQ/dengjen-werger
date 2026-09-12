# sbt Build Modernization

## Context

`build.sbt` and `project/plugins.sbt` are a lean early-stage scaffold: Scala
3.9.0, `-Wunused:all`, Scalafmt/Scalafix with SemanticDB, sbt-revolver, and
the `IntegrationTest`/`E2e` configs added on this branch for the test
pyramid (`docs/superpowers/specs/2026-09-12-test-pyramid-design.md`). This
spec closes the remaining gaps against the Typelevel/OSS baseline (cats-effect,
http4s, skunk stack): compiler hygiene, coverage, packaging for Cloud Run,
build metadata, dependency layout, and developer ergonomics.

Baseline measured on this branch before any change: 18 instrumented
statements, 22.22% statement coverage, all of it in `Main.scala` (0%),
`Db.scala` (0%), and `Routes.scala` (100%). `CrowdinCodecs.scala`,
`Model.scala`, and `TranslationSource.scala` are declarative-only (case
classes, given instances) and contribute no instrumented statements.

## Goals

1. Compiler guardrails that catch discarded effects and non-unit statements
   — the main correctness risk in cats-effect code.
2. A coverage gate that measures real logic, not composition-root wiring,
   and feeds SonarCloud.
3. A Cloud-Run-ready container build via sbt-native-packager.
4. `/healthz` exposing version/commit/build-time metadata.
5. Deterministic, git-derived versioning and standard project metadata.
6. Centralized dependency declarations.
7. Command aliases matching the checks CI already runs.

## Non-goals

- Reconciling `ci.yml`'s Java 25 vs `sonar.yml`'s Java 21 (pre-existing,
  unrelated to this work).
- Raising coverage on `Main`/`Db` themselves — they're excluded from the
  gate (see below), not tested directly.
- Publishing the built image anywhere — `dockerRepository` / CI push step
  is future work, out of scope here.

## 1. Compiler hygiene — sbt-tpolecat

Replace the single `ThisBuild / scalacOptions += "-Wunused:all"` with the
`sbt-tpolecat` plugin (`org.typelevel:sbt-tpolecat`). It selects the correct
flag set per Scala version and mode instead of a hand-maintained list, and
turns on `-Wvalue-discard` / `-Wnonunit-statement` — the two flags that
catch a dropped `IO` effect in the middle of a `for`-comprehension, the main
risk this analysis called out.

**Verification risk:** tpolecat escalates to `-Werror` automatically when
the `CI` env var is set (GitHub Actions sets it by default). Before this is
considered done, run `CI=true sbt compile` locally and fix anything that
newly trips fatal warnings — don't assume a plain `sbt compile` pass is
sufficient.

## 2. Coverage — sbt-scoverage, 80% gate on real logic

Add `sbt-scoverage`. Settings:

```scala
coverageMinimumStmtTotal := 80
coverageFailOnMinimum := true
coverageExcludedPackages := "werger\\.Main"
coverageExcludedFiles := ".*/adapters/db/Db\\.scala"
```

`Db.scala` currently reads `sys.env("DB_HOST")` etc. eagerly at object
construction, which throws in any process without DB creds set, and builds
the Skunk pool in the same expression — neither is unit-testable without a
live database. Extract the parsing into a new, separate file,
`DbConfig.scala`, as a pure, testable unit:

```scala
final case class DbConfig(host: String, port: Int, user: String, password: String, database: String)

object DbConfig:
  def fromEnv(lookup: String => Option[String] = sys.env.get): Either[String, DbConfig] = ...
```

`Db.scala` itself shrinks to a thin function from `DbConfig` to the Skunk
`Resource` chain — it stays excluded from coverage in its entirety (it
needs a live DB to mean anything; that's what the existing IT-tagged
`DbPoolingSpec` covers). `DbConfig.scala` is a separate file, not covered
by the exclusion, and is fully unit-tested with no DB dependency. With
`Routes` already at 100% and `DbConfig.fromEnv` tested, both `Main` and
`Db` are out of the 80% denominator and the gate is achievable without
padding.

CI wiring:
- `ci.yml`'s test step becomes `sbt coverage test coverageReport` (fails
  the job under 80%, separate from the `sbt lint` step — see §7).
- `sonar.yml` runs the same coverage command before the Sonar scan step.
- `sonar-project.properties` gains:
  `sonar.scala.coverage.xmlReportPaths=target/scala-*/scoverage-report/scoverage.xml`
  (glob, not a pinned Scala version, so it survives a Scala version bump).

## 3. Packaging — sbt-native-packager

Add `sbt-native-packager`, enable `JavaAppPackaging` and `DockerPlugin` on
`root`:

```scala
Compile / mainClass := Some("werger.Main")
dockerBaseImage := "eclipse-temurin:21-jre-alpine"
dockerExposedPorts := Seq(8080)
dockerUpdateLatest := true
```

`mainClass` is set explicitly rather than relying on auto-detection to
avoid ambiguity errors if a second `IOApp`/`main` is ever added. No
hand-written Dockerfile.

## 4. BuildInfo → `/healthz`

Add `sbt-buildinfo`, enabled on `root`:

```scala
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, gitCommit)
buildInfoPackage := "werger"

lazy val gitCommit = Def.setting {
  sys.env.get("GITHUB_SHA").map(_.take(7)).orElse(
    scala.util.Try(scala.sys.process.Process("git rev-parse --short HEAD").!!.trim).toOption
  ).getOrElse("unknown")
}
```

`GITHUB_SHA` is checked first because it's already present in CI without
shelling out; the `git rev-parse` fallback covers local dev; `"unknown"`
covers a checkout with no `.git` at all (e.g. a release tarball) rather
than failing the build.

`Routes.scala` gains a `HealthStatus(status: String, version: String, commit: String, builtAt: String)`
case class with a circe codec; `GET /healthz` returns it as JSON instead of
the plain string `"ok"`. `builtAt` is computed at request time or at
class-init via `java.time.Instant.now().toString` (request time is simpler
and avoids a second BuildInfo key).

`RoutesSpec` changes from `assertEquals(body, "ok")` to decoding
`HealthStatus` and asserting `.status == "ok"`.

`README.md:76` (`The command prints \`ok\`.`) is updated to show the new
JSON shape.

## 5. Versioning and metadata — sbt-dynver

Add `sbt-dynver`. No git tags exist yet, so version resolves via dynver's
no-tag fallback scheme until one is cut.

```scala
ThisBuild / dynverSeparator := "-"
```

Docker tags forbid `+` (valid charset `[a-zA-Z0-9_.-]`); dynver's default
separator for the distance/sha suffix is `+`, which would otherwise break
`Docker/publishLocal` and any future `Docker/stage` with an invalid-tag
error the moment a build isn't exactly on a tag.

Static metadata:

```scala
ThisBuild / organization := "org.zirekhq"
ThisBuild / homepage := Some(url("https://github.com/ZirekHQ/dengjen-werger"))
ThisBuild / licenses := List("GPL-3.0-or-later" -> url("https://www.gnu.org/licenses/gpl-3.0.html"))
```

## 6. Dependency centralization

New `project/Dependencies.scala`:

```scala
import sbt.*

object Dependencies:
  object V:
    val catsEffect = "3.7.1"
    val http4s     = "0.23.37"
    val circe      = "0.14.16"
    val skunk      = "1.0.0"
    val jwtScala   = "11.0.4"
    val munitCE    = "2.2.0"

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
```

`build.sbt` references `Dependencies.all` instead of the inline `Seq`.

## 7. Command aliases

```scala
addCommandAlias("lint", ";scalafmtCheckAll;scalafixAll --check")
addCommandAlias("fix", ";scalafmtAll;scalafixAll")
addCommandAlias("prep", ";fix;test")
```

`ci.yml`'s single `sbt --allow-empty test scalafmtCheckAll "scalafixAll --check"`
step splits into two: `sbt coverage test coverageReport` (§2) and
`sbt lint`.

## Testing

- `DbConfig.fromEnv` — unit-tested: all-present, each-missing, invalid
  port, default port fallback.
- `RoutesSpec` — updated to decode `HealthStatus` JSON.
- No new integration/e2e tests required; `DbPoolingSpec` already covers
  the excluded pool-building path.
- Verification step (not a test, but required before calling this done):
  `CI=true sbt compile` to catch tpolecat's fatal-warnings escalation, and
  a local `sbt docker:publishLocal` to confirm the image tag is valid
  under the new `dynverSeparator`.

## Risks / follow-ups

- Coverage threshold (80%) is measured only against non-excluded files.
  As new business logic is added, watch that it doesn't land inside
  `Db.scala` (which is wholesale-excluded) — new logic belongs in its own
  file, the way `DbConfig` does, so it stays inside the measured set.
- No git tags yet, so `sbt-dynver`'s version string is the no-tag fallback
  form until a release tag is cut — expected, not a defect.

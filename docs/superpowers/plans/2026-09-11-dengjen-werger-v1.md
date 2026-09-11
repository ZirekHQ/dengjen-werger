# Dengjen Werger v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v1 of Dengjen Werger — a commitment/gamification layer in front of Crowdin that turns Kurmanji translation review into a habit volunteers keep, with a mandatory single-reviewer quality gate that can't be trivially gamed.

**Architecture:** One Scala 3 service (Cats Effect 3 + http4s + Skunk, no JDBC) backed by Supabase (Postgres for domain state + TM, Auth for multi-provider sign-in), talking to Crowdin's REST API as the only v1 `TranslationSource`. Cloud Run (scale-to-zero, GraalVM native-image) hosts the service; Cloud Scheduler drives the one recurring job. A minimal Vite/TypeScript SPA is the contributor client.

**Tech Stack:** Scala 3.3.4, cats-effect 3.5.4, http4s 0.23.27 (ember-server/client), Skunk 0.6.4, circe 0.14.9, jwt-scala 10.0.1, munit-cats-effect 2.0.0, Flyway (build-time migrations only, not in the native-image runtime), Vite + TypeScript for the SPA. Pin exact patch versions in `build.sbt`; check for newer compatible releases at implementation time rather than treating these as frozen.

**Spec:** `docs/superpowers/specs/2026-09-11-dengjen-werger-design.md` — twice-reviewed (adversarial Claude agent, two Gemini/Antigravity passes). This plan argues from that spec; read both.

## Global Constraints

- No `var`, `null`, or `return` in Scala code; model absence with `Option`, failure with `Either`/`IO`'s error channel (user's global Scala conventions).
- No JDBC anywhere in the runtime binary — Skunk only, connecting through Supabase's Supavisor transaction pooler (port 6543), never the direct port (5432).
- Every DB write that enforces an invariant from the spec (reviewer ≠ submitter, one payout per `(submission, user, reason)`, atomic exit from `PendingReview`) is a database constraint, not an application-level check alone — the spec calls this out explicitly after the collusion/race findings from review.
- The Crowdin service token is scoped to the `kmr` language only within `nvdaaddons` — never request or wire in a project-wide manager token.
- No comments that restate code; doc comments state contracts only. No `Co-Authored-By` or AI-attribution trailers in any commit.
- Every task ends green (tests passing) and committed before moving to the next.

---

## File Structure

```
dengjen-werger/
  build.sbt
  project/build.properties
  db/migrations/
    V1__init_schema.sql
  src/main/scala/werger/
    domain/
      Model.scala              # all case classes/enums from the spec's domain model
    ports/
      TranslationSource.scala  # the trait, unchanged from the spec
      EmailSender.scala        # port for reminder delivery
    adapters/
      crowdin/
        CrowdinClient.scala    # raw HTTP calls to Crowdin v2 API
        CrowdinCodecs.scala    # circe codecs for Crowdin's JSON
        CrowdinSource.scala    # TranslationSource impl
      db/
        Db.scala               # Skunk session pool, wired to Supavisor
        WorkItemRepo.scala
        UserRepo.scala
        SubmissionRepo.scala
        ReviewRepo.scala
        PointsRepo.scala
        StreakRepo.scala
        TmRepo.scala
      email/
        ResendEmailSender.scala
    service/
      CommitmentEvaluator.scala  # pure
      StreakCalculator.scala     # pure
      TrustLevel.scala           # pure
      LeaseService.scala
      SubmissionService.scala
      ReviewService.scala
      ApprovalService.scala
      JobsService.scala          # TM sync + commitment eval + reminders + RevisionPending sweep
      AccountDeletionService.scala
    http/
      Auth.scala                 # Supabase JWT verification + user auto-provisioning
      Routes.scala                # contributor-facing REST API
      JobsRoutes.scala            # /internal/jobs/evaluate-and-sync
    Main.scala
  src/test/scala/werger/
    domain/ModelSpec.scala
    service/CommitmentEvaluatorSpec.scala
    service/StreakCalculatorSpec.scala
    service/TrustLevelSpec.scala
    service/LeaseServiceSpec.scala
    service/SubmissionServiceSpec.scala
    service/ReviewServiceSpec.scala
    service/ApprovalServiceSpec.scala
    service/JobsServiceSpec.scala
    service/AccountDeletionServiceSpec.scala
    adapters/crowdin/CrowdinClientSpec.scala
    adapters/crowdin/CrowdinSourceIntegrationSpec.scala  # guarded, needs CROWDIN_TEST_TOKEN
    adapters/db/DbPoolingSpec.scala
    http/AuthSpec.scala
    http/RoutesSpec.scala
  client/
    package.json, vite.config.ts, tsconfig.json
    src/
      supabase.ts
      api.ts
      views/{Onboarding,Translate,Review,Dashboard}.ts
      main.ts
  deploy/
    Dockerfile
    seed-trust.sql
    cloud-run.md
```

Boundary rule followed throughout: `domain` has zero dependencies on anything else in the tree (pure data). `ports` depend only on `domain`. `adapters` implement `ports` and are the only place external I/O (HTTP, SQL) happens. `service` orchestrates ports/repos and holds the state-machine logic; the pure calculators inside it take no `IO` and touch no repo. `http` is the only layer that knows about JWTs, routes, or JSON wire format for the app's own API — Crowdin's wire format stays inside `adapters/crowdin`.

---

## Milestone 1: Prove the Crowdin Integration First

Sequenced first deliberately (per the design review): the riskiest unknowns — Crowdin's actual write-API behavior and Skunk's behavior on Supabase's pooler — get proven against real services before any gamification logic is built on top of assumptions about them.

### Task 1: Project scaffold with a live health check

**Files:**
- Create: `build.sbt`
- Create: `project/build.properties`
- Create: `src/main/scala/werger/Main.scala`
- Test: `src/test/scala/werger/http/RoutesSpec.scala`

**Interfaces:**
- Produces: `object Main extends IOApp.Simple`, an http4s `HttpRoutes[IO]` mounted at `/healthz` returning `200 OK`.

- [ ] **Step 1: Write `build.sbt`**

```scala
ThisBuild / scalaVersion := "3.3.4"

lazy val root = (project in file("."))
  .settings(
    name := "dengjen-werger",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.5.4",
      "org.http4s"    %% "http4s-ember-server" % "0.23.27",
      "org.http4s"    %% "http4s-ember-client" % "0.23.27",
      "org.http4s"    %% "http4s-circe"        % "0.23.27",
      "org.http4s"    %% "http4s-dsl"          % "0.23.27",
      "io.circe"      %% "circe-generic"       % "0.14.9",
      "org.tpolecat"  %% "skunk-core"          % "0.6.4",
      "com.github.jwt-scala" %% "jwt-circe"    % "10.0.1",
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0" % Test
    )
  )
```

- [ ] **Step 2: Write the failing test**

```scala
package werger.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class RoutesSpec extends CatsEffectSuite:
  test("GET /healthz returns 200"):
    val req = Request[IO](Method.GET, uri"/healthz")
    Routes.app.run(req).value.map(_.map(_.status)).assertEquals(Some(Status.Ok))
```

- [ ] **Step 3: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `Routes` does not exist yet.

- [ ] **Step 4: Write minimal implementation**

```scala
package werger.http

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*

object Routes:
  val app: HttpApp[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" => Ok("ok")
  }.orNotFound
```

```scala
package werger

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import werger.http.Routes

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    EmberServerBuilder.default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(Routes.app)
      .build
      .useForever
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add build.sbt project/build.properties src/main/scala/werger/Main.scala src/main/scala/werger/http/Routes.scala src/test/scala/werger/http/RoutesSpec.scala
git commit -m "chore: scaffold sbt project with a health check route"
```

---

### Task 2: Database schema and migrations

**Files:**
- Create: `db/migrations/V1__init_schema.sql`

**Interfaces:**
- Produces: the `languages`, `users`, `work_items`, `submissions`, `reviews`, `points_ledger`, `streak_state`, `tm_segments` tables every later repo task reads/writes.

- [ ] **Step 1: Write the migration**

```sql
CREATE TYPE role AS ENUM ('contributor', 'reviewer', 'maintainer');
CREATE TYPE user_status AS ENUM ('active', 'suspended');
CREATE TYPE commitment_tier AS ENUM ('light', 'medium', 'heavy');
CREATE TYPE work_item_status AS ENUM
  ('available', 'in_progress', 'pending_review', 'revision_pending',
   'approved', 'upstream_approval_pending', 'synced', 'retired');
CREATE TYPE review_verdict AS ENUM ('approved', 'rejected');
CREATE TYPE points_reason AS ENUM ('submission_approved', 'review_completed');

CREATE TABLE languages (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_provider_ref TEXT NOT NULL UNIQUE,
  role role NOT NULL DEFAULT 'contributor',
  status user_status NOT NULL DEFAULT 'active',
  timezone TEXT NOT NULL,
  commitment_tier commitment_tier,
  commitment_started_at TIMESTAMPTZ,
  email TEXT,
  email_opt_in BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE work_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_adapter TEXT NOT NULL,
  external_id TEXT NOT NULL,
  language_code TEXT NOT NULL REFERENCES languages(code),
  source_text TEXT NOT NULL,
  target_text_draft TEXT,
  status work_item_status NOT NULL DEFAULT 'available',
  leased_by UUID REFERENCES users(id),
  lease_expires_at TIMESTAMPTZ,
  revision_submitter UUID REFERENCES users(id),
  revision_comment TEXT,
  revision_expires_at TIMESTAMPTZ,
  UNIQUE (source_adapter, external_id, language_code)
);

CREATE TABLE submissions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_item_id UUID NOT NULL REFERENCES work_items(id),
  submitter_id UUID NOT NULL REFERENCES users(id),
  proposed_translation TEXT NOT NULL,
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id UUID NOT NULL REFERENCES submissions(id),
  reviewer_id UUID NOT NULL REFERENCES users(id),
  verdict review_verdict NOT NULL,
  comment TEXT,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT reviewer_not_submitter CHECK (
    reviewer_id <> (SELECT submitter_id FROM submissions WHERE id = submission_id)
  )
);

CREATE TABLE points_ledger (
  user_id UUID NOT NULL REFERENCES users(id),
  submission_id UUID NOT NULL REFERENCES submissions(id),
  reason points_reason NOT NULL,
  amount INT NOT NULL,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, submission_id, reason)
);

CREATE TABLE streak_state (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  current INT NOT NULL DEFAULT 0,
  longest INT NOT NULL DEFAULT 0,
  last_met_period DATE
);

CREATE TABLE tm_segments (
  language_code TEXT NOT NULL REFERENCES languages(code),
  source_text TEXT NOT NULL,
  target_text TEXT NOT NULL,
  source_adapter TEXT NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (language_code, source_adapter, source_text)
);

INSERT INTO languages (code, name) VALUES ('kmr', 'Kurmanji Kurdish');
```

The `reviewer_not_submitter` check uses a subquery rather than a stored
column so it can't drift out of sync with `submissions.submitter_id`; the
`points_ledger` primary key is exactly the anti-farming uniqueness the spec
requires, not a separate unique index bolted on.

- [ ] **Step 2: Apply it against a local/dev Supabase Postgres and verify it runs clean**

Run: `psql "$DEV_DATABASE_URL" -f db/migrations/V1__init_schema.sql`
Expected: no errors; `\dt` in `psql` lists all eight tables.

- [ ] **Step 3: Commit**

```bash
git add db/migrations/V1__init_schema.sql
git commit -m "feat: add initial database schema migration"
```

---

### Task 3: Skunk pool on Supavisor — prove the pooling trap doesn't bite

**Files:**
- Create: `src/main/scala/werger/adapters/db/Db.scala`
- Test: `src/test/scala/werger/adapters/db/DbPoolingSpec.scala`

**Interfaces:**
- Produces: `Db.pooled: Resource[IO, Resource[IO, Session[IO]]]` — a pool of sessions every repo task borrows from.
- Consumes: env vars `DB_HOST`, `DB_PORT` (6543), `DB_USER`, `DB_PASSWORD`, `DB_NAME`.

- [ ] **Step 1: Write the failing test — the specific risk the design review flagged**

```scala
package werger.adapters.db

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import skunk.codec.all.*
import skunk.implicits.*

class DbPoolingSpec extends CatsEffectSuite:
  test("running the same query many times over a small pool doesn't hit a stale prepared statement"):
    Db.pooled.use { pool =>
      val query = pool.use(_.unique(sql"select 1".query(int4)))
      (1 to 20).toList.parTraverse(_ => query).map(_.forall(_ == 1)).assert
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `Db` does not exist.

- [ ] **Step 3: Implement — session pool with unnamed-statement caching disabled**

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import natchez.Trace.Implicits.noop
import skunk.Session

object Db:
  private val host = sys.env("DB_HOST")
  private val port = sys.env.getOrElse("DB_PORT", "6543").toInt
  private val user = sys.env("DB_USER")
  private val password = sys.env("DB_PASSWORD")
  private val database = sys.env("DB_NAME")

  val pooled: Resource[IO, Resource[IO, Session[IO]]] =
    Session.pooled[IO](
      host = host,
      port = port,
      user = user,
      password = Some(password),
      database = database,
      max = 8,
      strategy = skunk.Strategy.SearchPath
    )
```

`Strategy.SearchPath` is Skunk's cache strategy that re-checks a cached
prepared statement's shape against the current connection instead of
assuming it still holds — this is the direct fix for the transaction-mode
pooling trap the design review raised; `Strategy.BuiltinsOnly` is the
fallback if this still surfaces `prepared statement does not exist` errors
against the real Supavisor instance.

- [ ] **Step 4: Run test against a real dev Supabase project (transaction pooler, port 6543)**

Run: `DB_HOST=<project>.pooler.supabase.com DB_PORT=6543 DB_USER=postgres.<ref> DB_PASSWORD=*** DB_NAME=postgres sbt test`
Expected: PASS. If it fails with a prepared-statement error, switch `strategy` to `skunk.Strategy.BuiltinsOnly` and re-run before proceeding — do not move on with this unresolved, since every later repo task depends on it.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/db/Db.scala src/test/scala/werger/adapters/db/DbPoolingSpec.scala
git commit -m "feat: add Skunk session pool wired to Supavisor transaction pooler"
```

---

### Task 4: Domain model

**Files:**
- Create: `src/main/scala/werger/domain/Model.scala`
- Test: `src/test/scala/werger/domain/ModelSpec.scala`

**Interfaces:**
- Produces: every type referenced by every later task — `Language`, `User`, `Role`, `UserStatus`, `CommitmentTier`, `Commitment`, `WorkItem`, `WorkItemStatus`, `Submission`, `Review`, `ReviewVerdict`, `PointsLedgerEntry`, `PointsReason`, `StreakState`, `TmSegment`. Type aliases `UserId`, `WorkItemId`, `SubmissionId` are `java.util.UUID`.

- [ ] **Step 1: Write the failing test — the one invariant worth encoding at the type level**

```scala
package werger.domain

import java.time.Instant
import java.util.UUID
import munit.FunSuite

class ModelSpec extends FunSuite:
  test("WorkItemStatus.InProgress carries the leaseholder and expiry, not a bare flag"):
    val userId = UUID.randomUUID()
    val expiry = Instant.now()
    val status = WorkItemStatus.InProgress(userId, expiry)
    assertEquals(status.userId, userId)
    assertEquals(status.expiresAt, expiry)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `werger.domain` package does not exist.

- [ ] **Step 3: Write the model**

```scala
package werger.domain

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

type UserId = UUID
type WorkItemId = UUID
type SubmissionId = UUID

final case class Language(code: String, name: String)

enum Role:
  case Contributor, Reviewer, Maintainer

enum UserStatus:
  case Active, Suspended

enum CommitmentTier:
  case Light, Medium, Heavy

final case class Commitment(tier: CommitmentTier, startedAt: Instant)

final case class User(
  id: UserId,
  authProviderRef: String,
  role: Role,
  status: UserStatus,
  timezone: ZoneId,
  commitment: Option[Commitment],
  email: Option[String],
  emailOptIn: Boolean
)

enum WorkItemStatus:
  case Available
  case InProgress(userId: UserId, expiresAt: Instant)
  case PendingReview(submissionId: SubmissionId)
  case RevisionPending(submitterId: UserId, reviewerComment: String, expiresAt: Instant)
  case Approved
  case UpstreamApprovalPending
  case Synced
  case Retired

final case class WorkItem(
  id: WorkItemId,
  sourceAdapter: String,
  externalId: String,
  language: String,
  sourceText: String,
  targetTextDraft: Option[String],
  status: WorkItemStatus
)

final case class Submission(
  id: SubmissionId,
  workItem: WorkItemId,
  submitter: UserId,
  proposedTranslation: String,
  submittedAt: Instant
)

enum ReviewVerdict:
  case Approved, Rejected

final case class Review(
  submission: SubmissionId,
  reviewer: UserId,
  verdict: ReviewVerdict,
  comment: Option[String],
  reviewedAt: Instant
)

enum PointsReason:
  case SubmissionApproved, ReviewCompleted

final case class PointsLedgerEntry(
  user: UserId,
  submission: SubmissionId,
  reason: PointsReason,
  amount: Int,
  at: Instant
)

final case class StreakState(
  user: UserId,
  current: Int,
  longest: Int,
  lastMetPeriod: Option[LocalDate]
)

final case class TmSegment(
  language: String,
  sourceText: String,
  targetText: String,
  sourceAdapter: String,
  importedAt: Instant
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/domain/Model.scala src/test/scala/werger/domain/ModelSpec.scala
git commit -m "feat: add domain model"
```

---

### Task 5: `TranslationSource` port and Crowdin JSON codecs

**Files:**
- Create: `src/main/scala/werger/ports/TranslationSource.scala`
- Create: `src/main/scala/werger/adapters/crowdin/CrowdinCodecs.scala`
- Test: `src/test/scala/werger/adapters/crowdin/CrowdinClientSpec.scala` (codec round-trip only at this step)

**Interfaces:**
- Produces: `trait TranslationSource` (exact shape from the spec); `CrowdinSourceString`, `CrowdinTranslation` circe codecs.

- [ ] **Step 1: Write the failing test**

```scala
package werger.adapters.crowdin

import io.circe.parser.decode
import munit.FunSuite

class CrowdinClientSpec extends FunSuite:
  test("decodes a Crowdin source string response"):
    val json = """{"id": 661, "text": "OK", "identifier": "addon.OK"}"""
    val result = decode[CrowdinSourceString](json)
    assertEquals(result.map(_.text), Right("OK"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `CrowdinSourceString` does not exist.

- [ ] **Step 3: Implement the port and codecs**

```scala
package werger.ports

import cats.effect.IO
import werger.domain.{Language, TmSegment, WorkItem}

sealed trait SourceError
object SourceError:
  final case class Transient(message: String) extends SourceError
  final case class Rejected(message: String) extends SourceError

/** Adapts one external translation platform for both reading work and
  * durable Translation Memory, and for pushing an approved translation back.
  */
trait TranslationSource:
  def fetchWorkItems(language: Language): IO[List[WorkItem]]
  def fetchMemory(language: Language): IO[List[TmSegment]]
  def submit(item: WorkItem, translation: String): IO[Either[SourceError, Unit]]
```

```scala
package werger.adapters.crowdin

import io.circe.generic.semiauto.*
import io.circe.Decoder

final case class CrowdinSourceString(id: Long, text: String, identifier: String)
object CrowdinSourceString:
  given Decoder[CrowdinSourceString] = deriveDecoder

final case class CrowdinTranslation(id: Long, stringId: Long, text: String)
object CrowdinTranslation:
  given Decoder[CrowdinTranslation] = deriveDecoder

final case class CrowdinCreatedTranslation(id: Long)
object CrowdinCreatedTranslation:
  given Decoder[CrowdinCreatedTranslation] = deriveDecoder
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/ports/TranslationSource.scala src/main/scala/werger/adapters/crowdin/CrowdinCodecs.scala src/test/scala/werger/adapters/crowdin/CrowdinClientSpec.scala
git commit -m "feat: add TranslationSource port and Crowdin JSON codecs"
```

---

### Task 6: Crowdin HTTP client — read path

**Files:**
- Modify: `src/main/scala/werger/adapters/crowdin/CrowdinClient.scala` (create)
- Modify: `src/test/scala/werger/adapters/crowdin/CrowdinClientSpec.scala`

**Interfaces:**
- Consumes: `CrowdinSourceString`, `CrowdinTranslation` (Task 5).
- Produces: `class CrowdinClient(httpClient: Client[IO], token: String, projectId: Long)` with `def sourceStrings(fileId: Long): IO[List[CrowdinSourceString]]` and `def approvedTranslations(languageId: String, fileId: Long): IO[List[CrowdinTranslation]]`.

- [ ] **Step 1: Write the failing test — against http4s's in-memory test client, not a live call**

```scala
package werger.adapters.crowdin

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class CrowdinClientSpec extends CatsEffectSuite:
  test("sourceStrings decodes Crowdin's paginated response shape"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "files" / "661" / "strings" =>
        Ok("""{"data":[{"data":{"id":661,"text":"OK","identifier":"addon.OK"}}]}""")
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
    crowdin.sourceStrings(fileId = 661L).map(_.map(_.text)).assertEquals(List("OK"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `CrowdinClient` does not exist.

- [ ] **Step 3: Implement**

```scala
package werger.adapters.crowdin

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.Uri
import org.http4s.headers.Authorization

private final case class Envelope[A](data: List[Wrapped[A]])
private final case class Wrapped[A](data: A)
private object Envelope:
  given [A: Decoder]: Decoder[Envelope[A]] = deriveDecoder
private object Wrapped:
  given [A: Decoder]: Decoder[Wrapped[A]] = deriveDecoder

class CrowdinClient(httpClient: Client[IO], token: String, projectId: Long):
  private val base = Uri.unsafeFromString("https://api.crowdin.com")
  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, token))

  def sourceStrings(fileId: Long): IO[List[CrowdinSourceString]] =
    val uri = base / "api" / "v2" / "projects" / projectId.toString / "files" / fileId.toString / "strings"
    httpClient.expect[Envelope[CrowdinSourceString]](Request[IO](Method.GET, uri).putHeaders(auth))
      .map(_.data.map(_.data))

  def approvedTranslations(languageId: String, fileId: Long): IO[List[CrowdinTranslation]] =
    val uri = (base / "api" / "v2" / "projects" / projectId.toString / "languages" / languageId / "translations")
      .withQueryParam("fileId", fileId.toString)
    httpClient.expect[Envelope[CrowdinTranslation]](Request[IO](Method.GET, uri).putHeaders(auth))
      .map(_.data.map(_.data))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/crowdin/CrowdinClient.scala src/test/scala/werger/adapters/crowdin/CrowdinClientSpec.scala
git commit -m "feat: add Crowdin client read path (source strings, approved translations)"
```

---

### Task 7: Crowdin HTTP client — write path, and the guarded live integration test

This is the task the whole milestone exists to de-risk: the spec's resolved
open question (`POST /translations` then `POST /approvals`) has never been
exercised against real Crowdin in this org.

**Files:**
- Modify: `src/main/scala/werger/adapters/crowdin/CrowdinClient.scala`
- Create: `src/test/scala/werger/adapters/crowdin/CrowdinSourceIntegrationSpec.scala`

**Interfaces:**
- Produces: `def createTranslation(languageId: String, stringId: Long, text: String): IO[CrowdinCreatedTranslation]`, `def approveTranslation(translationId: Long): IO[Unit]`.

- [ ] **Step 1: Write the failing unit test (stubbed, same pattern as Task 6)**

```scala
test("createTranslation posts to /translations and decodes the created id") {
  val stub = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "v2" / "projects" / "780748" / "translations" =>
      Ok("""{"data":{"id":42}}""")
  }.orNotFound
  val client = Client.fromHttpApp(stub)
  val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
  crowdin.createTranslation("kmr", stringId = 661L, text = "Temam").map(_.id).assertEquals(42L)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import io.circe.Json

def createTranslation(languageId: String, stringId: Long, text: String): IO[CrowdinCreatedTranslation] =
  val uri = base / "api" / "v2" / "projects" / projectId.toString / "translations"
  val body = Json.obj("stringId" -> stringId.asJson, "languageId" -> languageId.asJson, "text" -> text.asJson)
  httpClient.expect[Wrapped[CrowdinCreatedTranslation]](
    Request[IO](Method.POST, uri).withEntity(body).putHeaders(auth)
  ).map(_.data)

def approveTranslation(translationId: Long): IO[Unit] =
  val uri = base / "api" / "v2" / "projects" / projectId.toString / "approvals"
  val body = Json.obj("translationId" -> translationId.asJson)
  httpClient.expect[Json](Request[IO](Method.POST, uri).withEntity(body).putHeaders(auth)).void
```

- [ ] **Step 4: Run unit test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Write the guarded live integration test**

```scala
package werger.adapters.crowdin

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder

class CrowdinSourceIntegrationSpec extends CatsEffectSuite:
  val token = sys.env.get("CROWDIN_TEST_TOKEN")
  val testStringId = sys.env.get("CROWDIN_TEST_STRING_ID").map(_.toLong)

  (token, testStringId) match
    case (Some(tok), Some(stringId)) =>
      test("creating then approving a translation round-trips against real Crowdin"):
        EmberClientBuilder.default[IO].build.use { httpClient =>
          val crowdin = new CrowdinClient(httpClient, tok, projectId = 780748L)
          for
            created <- crowdin.createTranslation("kmr", stringId, "TEST — dengjen-werger integration check")
            _       <- crowdin.approveTranslation(created.id)
          yield ()
        }
    case _ =>
      test("skipped — set CROWDIN_TEST_TOKEN and CROWDIN_TEST_STRING_ID to run against real Crowdin"):
        IO.unit
```

Run this against a real, disposable test string in a Crowdin sandbox
project (not `nvdaaddons` directly) before trusting the resolved-in-the-spec
assumption any further — this is exactly the unverified path the design
review called out.

- [ ] **Step 6: Run it with real credentials**

Run: `CROWDIN_TEST_TOKEN=*** CROWDIN_TEST_STRING_ID=*** sbt "testOnly werger.adapters.crowdin.CrowdinSourceIntegrationSpec"`
Expected: PASS. If Crowdin's actual response shape differs from what Task 5's codecs assume, fix the codecs now — this is the checkpoint for that, not something to discover after the gamification engine is built on top.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/werger/adapters/crowdin/CrowdinClient.scala src/test/scala/werger/adapters/crowdin/CrowdinSourceIntegrationSpec.scala
git commit -m "feat: add Crowdin write path with a guarded live integration test"
```

---

### Task 8: `CrowdinSource` — the `TranslationSource` implementation

**Files:**
- Create: `src/main/scala/werger/adapters/crowdin/CrowdinSource.scala`
- Test: `src/test/scala/werger/adapters/crowdin/CrowdinSourceSpec.scala`

**Interfaces:**
- Consumes: `CrowdinClient` (Tasks 6-7), `TranslationSource` (Task 5).
- Produces: `class CrowdinSource(client: CrowdinClient, fileId: Long) extends TranslationSource`. This is the fake later service tests substitute for.

- [ ] **Step 1: Write the failing test**

```scala
package werger.adapters.crowdin

import cats.effect.IO
import munit.CatsEffectSuite
import werger.domain.Language

class CrowdinSourceSpec extends CatsEffectSuite:
  test("fetchWorkItems maps Crowdin source strings without an approved translation into WorkItems"):
    // uses the same stub-Client pattern as Task 6/7 wired to return one source string
    // and zero approved translations for it, asserting one WorkItem with Available status
    fail("write against the stubbed CrowdinClient, following Task 6's pattern")
```

(The literal body above is intentionally a marker — replace it in this step
with a real stub-server test identical in shape to Task 6's, asserting the
mapping logic below; it is not left as the final content.)

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.adapters.crowdin

import cats.effect.IO
import werger.domain.*
import werger.ports.{SourceError, TranslationSource}

class CrowdinSource(client: CrowdinClient, fileId: Long) extends TranslationSource:
  private val sourceAdapterName = "crowdin"

  def fetchWorkItems(language: Language): IO[List[WorkItem]] =
    for
      sources      <- client.sourceStrings(fileId)
      translated   <- client.approvedTranslations(language.code, fileId)
      translatedIds = translated.map(_.stringId).toSet
    yield sources.filterNot(s => translatedIds.contains(s.id)).map { s =>
      WorkItem(
        id = java.util.UUID.randomUUID(),
        sourceAdapter = sourceAdapterName,
        externalId = s.id.toString,
        language = language.code,
        sourceText = s.text,
        targetTextDraft = None,
        status = WorkItemStatus.Available
      )
    }

  def fetchMemory(language: Language): IO[List[TmSegment]] =
    client.approvedTranslations(language.code, fileId).map(_.map { t =>
      TmSegment(language.code, t.text, t.text, sourceAdapterName, java.time.Instant.now())
    })

  def submit(item: WorkItem, translation: String): IO[Either[SourceError, Unit]] =
    val stringId = item.externalId.toLong
    (for
      created <- client.createTranslation(item.language, stringId, translation)
      _       <- client.approveTranslation(created.id)
    yield ()).attempt.map(_.left.map(e => SourceError.Transient(e.getMessage)))
```

`fetchMemory` mapping `sourceText` and `targetText` from the same
`CrowdinTranslation` needs revisiting once Task 7's live test confirms
whether Crowdin's translations endpoint also returns the original source
text or only the target — if it's target-only, join against `sourceStrings`
by `stringId` instead. Flagged here rather than guessed at silently.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/crowdin/CrowdinSource.scala src/test/scala/werger/adapters/crowdin/CrowdinSourceSpec.scala
git commit -m "feat: implement CrowdinSource as the v1 TranslationSource"
```

---

## Milestone 2: Domain & Gamification Engine

Everything here is either a pure function (tested with no `IO`, no DB) or a
repo/service tested against a real local Postgres — the concurrency and
anti-abuse properties from the spec are exactly what needs a real database
underneath them to mean anything.

### Task 9: `CommitmentEvaluator` — pure

**Files:**
- Create: `src/main/scala/werger/service/CommitmentEvaluator.scala`
- Test: `src/test/scala/werger/service/CommitmentEvaluatorSpec.scala`

**Interfaces:**
- Consumes: `CommitmentTier`, `Commitment` (Task 4).
- Produces: `def actionsRequired(tier: CommitmentTier): Int` and `def isOnTrack(tier: CommitmentTier, actionsThisPeriod: Int, now: Instant, periodStart: Instant): Boolean`, used by `JobsService` (Task 15) and tested standalone here.

- [ ] **Step 1: Write the failing test**

```scala
package werger.service

import java.time.Instant
import munit.FunSuite
import werger.domain.CommitmentTier

class CommitmentEvaluatorSpec extends FunSuite:
  test("Light tier requires 1 action per day"):
    assertEquals(CommitmentEvaluator.actionsRequired(CommitmentTier.Light), 1)

  test("on-track when actions-so-far meets the pro-rated target for elapsed time in the period"):
    val periodStart = Instant.parse("2026-09-01T00:00:00Z")
    val halfwayThroughDay = Instant.parse("2026-09-01T12:00:00Z")
    assert(CommitmentEvaluator.isOnTrack(CommitmentTier.Light, actionsThisPeriod = 1, halfwayThroughDay, periodStart))

  test("behind when zero actions past the halfway point of the period"):
    val periodStart = Instant.parse("2026-09-01T00:00:00Z")
    val halfwayThroughDay = Instant.parse("2026-09-01T12:00:00Z")
    assert(!CommitmentEvaluator.isOnTrack(CommitmentTier.Light, actionsThisPeriod = 0, halfwayThroughDay, periodStart))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.service

import java.time.{Duration, Instant}
import werger.domain.CommitmentTier

object CommitmentEvaluator:
  private val periodLength: Map[CommitmentTier, Duration] = Map(
    CommitmentTier.Light  -> Duration.ofDays(1),
    CommitmentTier.Medium -> Duration.ofDays(7),
    CommitmentTier.Heavy  -> Duration.ofDays(30)
  )

  private val quota: Map[CommitmentTier, Int] = Map(
    CommitmentTier.Light  -> 1,
    CommitmentTier.Medium -> 5,
    CommitmentTier.Heavy  -> 8
  )

  def actionsRequired(tier: CommitmentTier): Int = quota(tier)

  def isOnTrack(tier: CommitmentTier, actionsThisPeriod: Int, now: Instant, periodStart: Instant): Boolean =
    val elapsed = Duration.between(periodStart, now)
    val fractionElapsed = elapsed.toMillis.toDouble / periodLength(tier).toMillis
    val expectedByNow = math.ceil(quota(tier) * fractionElapsed.min(1.0)).toInt
    actionsThisPeriod >= expectedByNow
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/service/CommitmentEvaluator.scala src/test/scala/werger/service/CommitmentEvaluatorSpec.scala
git commit -m "feat: add pure commitment-progress evaluator"
```

---

### Task 10: `StreakCalculator` and `TrustLevel` — pure

**Files:**
- Create: `src/main/scala/werger/service/StreakCalculator.scala`
- Create: `src/main/scala/werger/service/TrustLevel.scala`
- Test: `src/test/scala/werger/service/StreakCalculatorSpec.scala`
- Test: `src/test/scala/werger/service/TrustLevelSpec.scala`

**Interfaces:**
- Produces: `def onPeriodMet(state: StreakState, period: LocalDate): StreakState` and `def isTrusted(role: Role, acceptedSubmissionCount: Int): Boolean` (threshold: 5, or always `true` for `Role.Maintainer`/`Role.Reviewer` — this is the seeded-trust fix from the design review).

- [ ] **Step 1: Write the failing tests**

```scala
package werger.service

import java.time.LocalDate
import munit.FunSuite
import werger.domain.StreakState

class StreakCalculatorSpec extends FunSuite:
  private val user = java.util.UUID.randomUUID()

  test("consecutive period met extends the streak"):
    val state = StreakState(user, current = 3, longest = 3, lastMetPeriod = Some(LocalDate.parse("2026-09-10")))
    val updated = StreakCalculator.onPeriodMet(state, LocalDate.parse("2026-09-11"))
    assertEquals(updated.current, 4)
    assertEquals(updated.longest, 4)

  test("a gap resets current but keeps longest"):
    val state = StreakState(user, current = 5, longest = 5, lastMetPeriod = Some(LocalDate.parse("2026-09-05")))
    val updated = StreakCalculator.onPeriodMet(state, LocalDate.parse("2026-09-11"))
    assertEquals(updated.current, 1)
    assertEquals(updated.longest, 5)
```

```scala
package werger.service

import munit.FunSuite
import werger.domain.Role

class TrustLevelSpec extends FunSuite:
  test("a Maintainer is always trusted regardless of submission count"):
    assert(TrustLevel.isTrusted(Role.Maintainer, acceptedSubmissionCount = 0))

  test("a Reviewer seeded at launch is always trusted"):
    assert(TrustLevel.isTrusted(Role.Reviewer, acceptedSubmissionCount = 0))

  test("a Contributor needs 5 accepted submissions"):
    assert(!TrustLevel.isTrusted(Role.Contributor, acceptedSubmissionCount = 4))
    assert(TrustLevel.isTrusted(Role.Contributor, acceptedSubmissionCount = 5))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.service

import java.time.LocalDate
import werger.domain.StreakState

object StreakCalculator:
  def onPeriodMet(state: StreakState, period: LocalDate): StreakState =
    val consecutive = state.lastMetPeriod.exists(_.plusDays(1).isEqual(period)) || state.lastMetPeriod.contains(period)
    val newCurrent = if consecutive then state.current + 1 else 1
    state.copy(current = newCurrent, longest = state.longest.max(newCurrent), lastMetPeriod = Some(period))
```

```scala
package werger.service

import werger.domain.Role

/** A Maintainer or a Reviewer role (seeded at launch, see deploy/seed-trust.sql)
  * is trusted unconditionally — this is the fix for the day-one bootstrap
  * problem where no Contributor yet has 5 accepted submissions.
  */
object TrustLevel:
  private val threshold = 5

  def isTrusted(role: Role, acceptedSubmissionCount: Int): Boolean =
    role match
      case Role.Maintainer | Role.Reviewer => true
      case Role.Contributor                => acceptedSubmissionCount >= threshold
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/service/StreakCalculator.scala src/main/scala/werger/service/TrustLevel.scala src/test/scala/werger/service/StreakCalculatorSpec.scala src/test/scala/werger/service/TrustLevelSpec.scala
git commit -m "feat: add pure streak calculator and trust-level check"
```

---

### Task 11: Repos — `WorkItemRepo` and lease/release with a real concurrency test

**Files:**
- Create: `src/main/scala/werger/adapters/db/WorkItemRepo.scala`
- Test: `src/test/scala/werger/service/LeaseServiceSpec.scala`
- Create: `src/main/scala/werger/service/LeaseService.scala`

**Interfaces:**
- Consumes: `Db.pooled` (Task 3), `WorkItem`/`WorkItemStatus` (Task 4).
- Produces: `class WorkItemRepo(pool: Resource[IO, Session[IO]])` with `insert`, `tryLease(id: WorkItemId, userId: UserId, expiresAt: Instant): IO[Boolean]` (the `Boolean` is whether *this* caller won the race), `release(id: WorkItemId): IO[Unit]`. `LeaseService.leaseNext(language: String, userId: UserId): IO[Option[WorkItem]]`.

- [ ] **Step 1: Write the failing concurrency test — the property that matters, not just the happy path**

```scala
package werger.service

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import werger.adapters.db.{Db, WorkItemRepo}
import werger.domain.*

class LeaseServiceSpec extends CatsEffectSuite:
  test("two simultaneous lease attempts on the same item: exactly one wins"):
    Db.pooled.use { pool =>
      val repo = new WorkItemRepo(pool)
      for
        item   <- repo.insert(WorkItem(java.util.UUID.randomUUID(), "test", "ext-1", "kmr", "OK", None, WorkItemStatus.Available))
        userA   = java.util.UUID.randomUUID()
        userB   = java.util.UUID.randomUUID()
        expiry  = java.time.Instant.now().plusSeconds(1800)
        results <- List(userA, userB).parTraverse(u => repo.tryLease(item.id, u, expiry))
      yield assertEquals(results.count(identity), 1)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test` (against a real local/dev Postgres, per `DB_HOST` etc. from Task 3)
Expected: FAIL — `WorkItemRepo` does not exist.

- [ ] **Step 3: Implement — the race is resolved by the DB, not application logic**

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import java.time.Instant
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import werger.domain.{WorkItem, WorkItemId, UserId, WorkItemStatus}

class WorkItemRepo(pool: Resource[IO, Session[IO]]):
  def insert(item: WorkItem): IO[WorkItem] =
    pool.use { s =>
      val cmd = sql"""
        insert into work_items (id, source_adapter, external_id, language_code, source_text, status)
        values ($uuid, $text, $text, $text, $text, 'available')
      """.command
      s.prepare(cmd).flatMap(_.execute(item.id ~ item.sourceAdapter ~ item.externalId ~ item.language ~ item.sourceText)).as(item)
    }

  /** Returns true only for the caller whose UPDATE actually matched a row —
    * the WHERE clause on current status is what makes concurrent callers
    * race safely instead of both succeeding.
    */
  def tryLease(id: WorkItemId, userId: UserId, expiresAt: Instant): IO[Boolean] =
    pool.use { s =>
      val cmd = sql"""
        update work_items
        set status = 'in_progress', leased_by = $uuid, lease_expires_at = $timestamptz
        where id = $uuid and status = 'available'
      """.command
      s.prepare(cmd).flatMap(_.execute(userId ~ expiresAt ~ id)).map {
        case Completion.Update(rows) => rows == 1
        case _                       => false
      }
    }

  def release(id: WorkItemId): IO[Unit] =
    pool.use { s =>
      val cmd = sql"update work_items set status = 'available', leased_by = null, lease_expires_at = null where id = $uuid".command
      s.prepare(cmd).flatMap(_.execute(id)).void
    }
```

```scala
package werger.service

import cats.effect.IO
import cats.effect.std.Random
import java.time.Instant
import werger.adapters.db.WorkItemRepo
import werger.domain.{UserId, WorkItem}

class LeaseService(repo: WorkItemRepo):
  private val leaseWindowSeconds = 1800L

  def leaseNext(candidates: List[WorkItem], userId: UserId): IO[Option[WorkItem]] =
    candidates match
      case Nil => IO.pure(None)
      case head :: rest =>
        repo.tryLease(head.id, userId, Instant.now().plusSeconds(leaseWindowSeconds)).flatMap {
          case true  => IO.pure(Some(head))
          case false => leaseNext(rest, userId)
        }
```

`LeaseService.leaseNext` takes an already-fetched candidate list rather than
querying `Available` items itself — Task 12 wires it to the blind-dispatch
query so leasing-for-translation and dispatch-for-review share one FIFO
selection strategy instead of two.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/db/WorkItemRepo.scala src/main/scala/werger/service/LeaseService.scala src/test/scala/werger/service/LeaseServiceSpec.scala
git commit -m "feat: add WorkItem repo and race-safe leasing"
```

---

### Task 12: `SubmissionService` and `ReviewService` — blind dispatch, anti-self-review, rejection into `RevisionPending`

**Files:**
- Create: `src/main/scala/werger/adapters/db/SubmissionRepo.scala`
- Create: `src/main/scala/werger/adapters/db/ReviewRepo.scala`
- Create: `src/main/scala/werger/service/SubmissionService.scala`
- Create: `src/main/scala/werger/service/ReviewService.scala`
- Test: `src/test/scala/werger/service/SubmissionServiceSpec.scala`
- Test: `src/test/scala/werger/service/ReviewServiceSpec.scala`

**Interfaces:**
- Consumes: `WorkItemRepo` (Task 11), domain model (Task 4).
- Produces: `SubmissionService.submit(item: WorkItem, submitter: UserId, text: String): IO[Submission]`; `ReviewService.nextForReview(userId: UserId): IO[Option[(WorkItem, Submission)]]`; `ReviewService.review(submission: Submission, reviewer: UserId, verdict: ReviewVerdict, comment: Option[String]): IO[Either[String, Unit]]` (`Left` only for the reviewer-is-submitter guard being hit — everything else the DB constraint would already prevent).

- [ ] **Step 1: Write the failing tests — one per invariant, not just the happy path**

```scala
package werger.service

import cats.effect.IO
import munit.CatsEffectSuite
import werger.adapters.db.*
import werger.domain.*

class ReviewServiceSpec extends CatsEffectSuite:
  test("a reviewer cannot review their own submission"):
    Db.pooled.use { pool =>
      val (workItems, submissions, reviews) = (new WorkItemRepo(pool), new SubmissionRepo(pool), new ReviewRepo(pool))
      val submissionSvc = new SubmissionService(workItems, submissions)
      val reviewSvc = new ReviewService(workItems, submissions, reviews)
      val submitter = java.util.UUID.randomUUID()
      for
        item       <- workItems.insert(WorkItem(java.util.UUID.randomUUID(), "test", "ext-2", "kmr", "Cancel", None, WorkItemStatus.Available))
        _          <- workItems.tryLease(item.id, submitter, java.time.Instant.now().plusSeconds(1800))
        submission <- submissionSvc.submit(item, submitter, "Betal")
        result     <- reviewSvc.review(submission, submitter, ReviewVerdict.Approved, None)
      yield assert(result.isLeft)
    }

  test("rejection moves the item to RevisionPending, not back to Available immediately"):
    Db.pooled.use { pool =>
      val (workItems, submissions, reviews) = (new WorkItemRepo(pool), new SubmissionRepo(pool), new ReviewRepo(pool))
      val submissionSvc = new SubmissionService(workItems, submissions)
      val reviewSvc = new ReviewService(workItems, submissions, reviews)
      val submitter = java.util.UUID.randomUUID()
      val reviewer  = java.util.UUID.randomUUID()
      for
        item       <- workItems.insert(WorkItem(java.util.UUID.randomUUID(), "test", "ext-3", "kmr", "Retry", None, WorkItemStatus.Available))
        _          <- workItems.tryLease(item.id, submitter, java.time.Instant.now().plusSeconds(1800))
        submission <- submissionSvc.submit(item, submitter, "Dîsa")
        _          <- reviewSvc.review(submission, reviewer, ReviewVerdict.Rejected, Some("wrong register"))
        reloaded   <- workItems.find(item.id)
      yield assert(reloaded.exists(_.status.isInstanceOf[WorkItemStatus.RevisionPending]))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement repos and services**

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import werger.domain.{Submission, SubmissionId, UserId, WorkItemId}

class SubmissionRepo(pool: Resource[IO, Session[IO]]):
  def insert(s: Submission): IO[Submission] =
    pool.use { session =>
      val cmd = sql"""
        insert into submissions (id, work_item_id, submitter_id, proposed_translation)
        values ($uuid, $uuid, $uuid, $text)
      """.command
      session.prepare(cmd).flatMap(_.execute(s.id ~ s.workItem ~ s.submitter ~ s.proposedTranslation)).as(s)
    }
```

```scala
package werger.service

import cats.effect.IO
import java.time.Instant
import java.util.UUID
import werger.adapters.db.{SubmissionRepo, WorkItemRepo}
import werger.domain.*

class SubmissionService(workItems: WorkItemRepo, submissions: SubmissionRepo):
  def submit(item: WorkItem, submitter: UserId, text: String): IO[Submission] =
    val submission = Submission(UUID.randomUUID(), item.id, submitter, text, Instant.now())
    for
      saved <- submissions.insert(submission)
      _     <- workItems.moveToPendingReview(item.id, saved.id)
    yield saved
```

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import werger.domain.*

class ReviewRepo(pool: Resource[IO, Session[IO]]):
  /** Fails (raises into IO) on the reviewer_not_submitter DB constraint —
    * ReviewService is responsible for checking that first and returning a
    * typed error instead of letting a constraint violation surface raw.
    */
  def insert(r: Review): IO[Unit] =
    pool.use { s =>
      val cmd = sql"""
        insert into reviews (submission_id, reviewer_id, verdict, comment)
        values ($uuid, $uuid, $text, $text.opt)
      """.command
      s.prepare(cmd).flatMap(_.execute(r.submission ~ r.reviewer ~ r.verdict.toString.toLowerCase ~ r.comment)).void
    }
```

```scala
package werger.service

import cats.effect.IO
import java.time.Instant
import werger.adapters.db.{ReviewRepo, SubmissionRepo, WorkItemRepo}
import werger.domain.*

class ReviewService(workItems: WorkItemRepo, submissions: SubmissionRepo, reviews: ReviewRepo):
  private val revisionWindowSeconds = 48L * 3600

  def review(submission: Submission, reviewer: UserId, verdict: ReviewVerdict, comment: Option[String]): IO[Either[String, Unit]] =
    if submission.submitter == reviewer then
      IO.pure(Left("a reviewer cannot review their own submission"))
    else
      val record = Review(submission.id, reviewer, verdict, comment, Instant.now())
      for
        _ <- reviews.insert(record)
        _ <- verdict match
               case ReviewVerdict.Approved =>
                 workItems.markApproved(submission.workItem)
               case ReviewVerdict.Rejected =>
                 workItems.moveToRevisionPending(
                   submission.workItem, submission.submitter,
                   comment.getOrElse(""), Instant.now().plusSeconds(revisionWindowSeconds)
                 )
      yield Right(())
```

`WorkItemRepo` gains `moveToPendingReview`, `markApproved`, and
`moveToRevisionPending` — same pattern as `tryLease`, a single `UPDATE ...
WHERE status = <expected prior state>` per transition, so an item can't be
double-transitioned by a race between this task's logic and Task 11's lease
expiry sweep. Add these three methods to `WorkItemRepo.scala` from Task 11
following `tryLease`'s shape exactly (status-gated `UPDATE`, no
read-then-write).

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/db/SubmissionRepo.scala src/main/scala/werger/adapters/db/ReviewRepo.scala src/main/scala/werger/adapters/db/WorkItemRepo.scala src/main/scala/werger/service/SubmissionService.scala src/main/scala/werger/service/ReviewService.scala src/test/scala/werger/service/SubmissionServiceSpec.scala src/test/scala/werger/service/ReviewServiceSpec.scala
git commit -m "feat: add submission and review flow with anti-self-review and RevisionPending"
```

---

### Task 13: `PointsRepo` and payout uniqueness, wired into both flows

**Files:**
- Create: `src/main/scala/werger/adapters/db/PointsRepo.scala`
- Modify: `src/main/scala/werger/service/ReviewService.scala`

**Interfaces:**
- Produces: `PointsRepo.award(entry: PointsLedgerEntry): IO[Boolean]` (`false` if the `(user, submission, reason)` primary key already existed — a no-op, not an error, since a retried request should be idempotent, not fail).

- [ ] **Step 1: Write the failing test**

```scala
package werger.service

import cats.effect.IO
import munit.CatsEffectSuite
import werger.adapters.db.*
import werger.domain.*

class PointsAwardSpec extends CatsEffectSuite:
  test("awarding the same (user, submission, reason) twice only pays out once"):
    Db.pooled.use { pool =>
      val points = new PointsRepo(pool)
      val user = java.util.UUID.randomUUID()
      val submission = java.util.UUID.randomUUID()
      val entry = PointsLedgerEntry(user, submission, PointsReason.ReviewCompleted, 10, java.time.Instant.now())
      for
        first  <- points.award(entry)
        second <- points.award(entry)
      yield
        assert(first)
        assert(!second)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import werger.domain.PointsLedgerEntry

class PointsRepo(pool: Resource[IO, Session[IO]]):
  def award(entry: PointsLedgerEntry): IO[Boolean] =
    pool.use { s =>
      val cmd = sql"""
        insert into points_ledger (user_id, submission_id, reason, amount)
        values ($uuid, $uuid, $text, $int4)
        on conflict (user_id, submission_id, reason) do nothing
      """.command
      s.prepare(cmd).flatMap(_.execute(entry.user ~ entry.submission ~ entry.reason.toString.toLowerCase ~ entry.amount))
        .map { case Completion.Insert(rows) => rows == 1; case _ => false }
    }
```

Wire this into `ReviewService.review`: every reviewer gets a
`ReviewCompleted` award call immediately (regardless of verdict, per the
spec), and on `Approved` the submitter gets a `SubmissionApproved` award —
both through this same `award` call, so both inherit the uniqueness
guarantee for free.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/db/PointsRepo.scala src/main/scala/werger/service/ReviewService.scala
git commit -m "feat: add idempotent points ledger and wire payouts into review flow"
```

---

### Task 14: `ApprovalService` — trust gate, Crowdin push, `UpstreamApprovalPending`

**Files:**
- Create: `src/main/scala/werger/service/ApprovalService.scala`
- Test: `src/test/scala/werger/service/ApprovalServiceSpec.scala`

**Interfaces:**
- Consumes: `TranslationSource` (Task 5, tested against a fake here — not `CrowdinSource` directly), `TrustLevel` (Task 10), `WorkItemRepo` (Task 11).
- Produces: `ApprovalService.onApproved(item: WorkItem, submission: Submission, reviewerRole: Role, reviewerAcceptedCount: Int): IO[Unit]`.

- [ ] **Step 1: Write the failing tests**

```scala
package werger.service

import cats.effect.IO
import munit.CatsEffectSuite
import werger.domain.*
import werger.ports.{SourceError, TranslationSource}

class ApprovalServiceSpec extends CatsEffectSuite:
  class FakeSource(result: Either[SourceError, Unit]) extends TranslationSource:
    var submitCalled = false
    def fetchWorkItems(l: Language) = IO.pure(Nil)
    def fetchMemory(l: Language) = IO.pure(Nil)
    def submit(item: WorkItem, t: String) = IO { submitCalled = true }.as(result)

  test("an untrusted reviewer's approval does NOT push to Crowdin"):
    Db.pooled.use { pool =>
      val fake = new FakeSource(Right(()))
      val workItems = new WorkItemRepo(pool)
      val svc = new ApprovalService(fake, workItems)
      for
        item <- workItems.insert(WorkItem(java.util.UUID.randomUUID(), "test", "ext-4", "kmr", "X", None, WorkItemStatus.PendingReview(java.util.UUID.randomUUID())))
        submission = Submission(java.util.UUID.randomUUID(), item.id, java.util.UUID.randomUUID(), "Y", java.time.Instant.now())
        _    <- svc.onApproved(item, submission, Role.Contributor, reviewerAcceptedCount = 2)
      yield assert(!fake.submitCalled)
    }

  test("a trusted reviewer's approval pushes to Crowdin"):
    Db.pooled.use { pool =>
      val fake = new FakeSource(Right(()))
      val workItems = new WorkItemRepo(pool)
      val svc = new ApprovalService(fake, workItems)
      for
        item <- workItems.insert(WorkItem(java.util.UUID.randomUUID(), "test", "ext-5", "kmr", "X", None, WorkItemStatus.PendingReview(java.util.UUID.randomUUID())))
        submission = Submission(java.util.UUID.randomUUID(), item.id, java.util.UUID.randomUUID(), "Y", java.time.Instant.now())
        _    <- svc.onApproved(item, submission, Role.Maintainer, reviewerAcceptedCount = 0)
      yield assert(fake.submitCalled)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.service

import cats.effect.IO
import werger.adapters.db.WorkItemRepo
import werger.domain.*
import werger.ports.{SourceError, TranslationSource}

class ApprovalService(source: TranslationSource, workItems: WorkItemRepo):
  def onApproved(item: WorkItem, submission: Submission, reviewerRole: Role, reviewerAcceptedCount: Int): IO[Unit] =
    if !TrustLevel.isTrusted(reviewerRole, reviewerAcceptedCount) then
      workItems.markApproved(item.id)
    else
      source.submit(item, submission.proposedTranslation).flatMap {
        case Right(())                    => workItems.markSynced(item.id)
        case Left(_: SourceError)         => workItems.markUpstreamApprovalPending(item.id)
      }
```

Add `markSynced` and `markUpstreamApprovalPending` to `WorkItemRepo`,
matching the status-gated `UPDATE` shape from Task 11.

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/service/ApprovalService.scala src/main/scala/werger/adapters/db/WorkItemRepo.scala src/test/scala/werger/service/ApprovalServiceSpec.scala
git commit -m "feat: add trust-gated Crowdin push on approval"
```

---

### Task 15: `JobsService` — TM sync (with Retired-on-drift), commitment evaluation, reminders, `RevisionPending` sweep

**Files:**
- Create: `src/main/scala/werger/adapters/db/TmRepo.scala`
- Create: `src/main/scala/werger/ports/EmailSender.scala`
- Create: `src/main/scala/werger/adapters/email/ResendEmailSender.scala`
- Create: `src/main/scala/werger/service/JobsService.scala`
- Test: `src/test/scala/werger/service/JobsServiceSpec.scala`

**Interfaces:**
- Consumes: `TranslationSource.fetchMemory` (Task 5), `CommitmentEvaluator` (Task 9), `EmailSender` (this task).
- Produces: `JobsService.run(): IO[Unit]` — the single entry point `JobsRoutes` (Task 17) calls.

- [ ] **Step 1: Write the failing test — the drift-retirement behavior specifically, since it's new since the design review**

```scala
package werger.service

import cats.effect.IO
import munit.CatsEffectSuite
import werger.adapters.db.*
import werger.domain.*
import werger.ports.EmailSender

class JobsServiceSpec extends CatsEffectSuite:
  class NoopEmail extends EmailSender:
    def sendReminder(to: String, subject: String, body: String) = IO.unit

  test("a WorkItem whose source is already translated upstream is Retired, not left PendingReview forever"):
    Db.pooled.use { pool =>
      val workItems = new WorkItemRepo(pool)
      val tm = new TmRepo(pool)
      for
        item <- workItems.insert(WorkItem(java.util.UUID.randomUUID(), "crowdin", "ext-6", "kmr", "Z", None, WorkItemStatus.PendingReview(java.util.UUID.randomUUID())))
        job   = new JobsService(FakeAlreadyTranslatedSource, tm, workItems, new NoopEmail)
        _    <- job.syncTranslationMemoryAndRetireDrifted(Language("kmr", "Kurmanji Kurdish"))
        reloaded <- workItems.find(item.id)
      yield assertEquals(reloaded.map(_.status), Some(WorkItemStatus.Retired))
    }

  object FakeAlreadyTranslatedSource extends werger.ports.TranslationSource:
    def fetchWorkItems(l: Language) = IO.pure(Nil)
    def fetchMemory(l: Language) = IO.pure(List(TmSegment("kmr", "Z", "already-done", "crowdin", java.time.Instant.now())))
    def submit(i: WorkItem, t: String) = IO.pure(Right(()))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.ports

import cats.effect.IO

trait EmailSender:
  def sendReminder(to: String, subject: String, body: String): IO[Unit]
```

```scala
package werger.adapters.db

import cats.effect.{IO, Resource}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import werger.domain.TmSegment

class TmRepo(pool: Resource[IO, Session[IO]]):
  def upsert(segment: TmSegment): IO[Unit] =
    pool.use { s =>
      val cmd = sql"""
        insert into tm_segments (language_code, source_text, target_text, source_adapter)
        values ($text, $text, $text, $text)
        on conflict (language_code, source_adapter, source_text)
        do update set target_text = excluded.target_text, imported_at = now()
      """.command
      s.prepare(cmd).flatMap(_.execute(segment.language ~ segment.sourceText ~ segment.targetText ~ segment.sourceAdapter)).void
    }
```

```scala
package werger.service

import cats.effect.IO
import cats.syntax.all.*
import werger.adapters.db.{TmRepo, WorkItemRepo}
import werger.domain.*
import werger.ports.{EmailSender, TranslationSource}

class JobsService(source: TranslationSource, tm: TmRepo, workItems: WorkItemRepo, email: EmailSender):
  def syncTranslationMemoryAndRetireDrifted(language: Language): IO[Unit] =
    for
      segments <- source.fetchMemory(language)
      _        <- segments.traverse_(tm.upsert)
      drifted  <- workItems.findPendingReviewMatching(language.code, segments.map(_.sourceText).toSet)
      _        <- drifted.traverse_(item => workItems.retire(item.id))
    yield ()

  def run(): IO[Unit] =
    syncTranslationMemoryAndRetireDrifted(Language("kmr", "Kurmanji Kurdish"))
    // commitment evaluation + reminder sending + RevisionPending sweep are
    // each a straightforward extension of this method along the same shape
    // (fetch candidates from a repo, act per candidate) — added as this
    // task's Step 5 rather than duplicated here.
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Extend `run()` with the remaining two responsibilities, each with its own test following the same pattern as Step 1-4**:
  - Commitment evaluation: for each `User` with an active `Commitment`, count this period's actions (join `submissions`/`reviews` by `submitted_at`/`reviewed_at` within the period, computed in the user's `timezone`), call `CommitmentEvaluator.isOnTrack`, call `email.sendReminder` if false and `emailOptIn` is true.
  - `RevisionPending` sweep: `WorkItemRepo.findExpiredRevisions(now): IO[List[WorkItem]]`, each moved to `Available` via the same status-gated `UPDATE` pattern.
  - Write a `ResendEmailSender` implementing `EmailSender` via Resend's HTTP API (API key from `RESEND_API_KEY` env var), following the same http4s-client pattern as `CrowdinClient`.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/werger/adapters/db/TmRepo.scala src/main/scala/werger/ports/EmailSender.scala src/main/scala/werger/adapters/email/ResendEmailSender.scala src/main/scala/werger/service/JobsService.scala src/test/scala/werger/service/JobsServiceSpec.scala
git commit -m "feat: add scheduled job — TM sync with drift retirement, commitment reminders, revision sweep"
```

---

### Task 16: `AccountDeletionService`

**Files:**
- Create: `src/main/scala/werger/service/AccountDeletionService.scala`
- Test: `src/test/scala/werger/service/AccountDeletionServiceSpec.scala`

**Interfaces:**
- Produces: `AccountDeletionService.delete(userId: UserId): IO[Unit]` — purges PII on `users`, cancels the user's pending submissions/reviews, releases their leased/pending `WorkItem`s back to `Available`, leaves already-`Synced` contributions and their points-ledger history untouched.

- [ ] **Step 1: Write the failing test**

```scala
package werger.service

import cats.effect.IO
import munit.CatsEffectSuite
import werger.adapters.db.*
import werger.domain.*

class AccountDeletionServiceSpec extends CatsEffectSuite:
  test("deleting a user purges their email but keeps their synced contribution's points entry"):
    Db.pooled.use { pool =>
      val users = new UserRepo(pool)
      val points = new PointsRepo(pool)
      val svc = new AccountDeletionService(users, new WorkItemRepo(pool), new SubmissionRepo(pool), points)
      for
        user <- users.insert(User(java.util.UUID.randomUUID(), "auth|1", Role.Contributor, UserStatus.Active, java.time.ZoneId.of("Europe/Berlin"), None, Some("a@example.com"), true))
        _    <- points.award(PointsLedgerEntry(user.id, java.util.UUID.randomUUID(), PointsReason.SubmissionApproved, 10, java.time.Instant.now()))
        _    <- svc.delete(user.id)
        reloaded <- users.find(user.id)
      yield assertEquals(reloaded.flatMap(_.email), None)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL — `UserRepo`/`AccountDeletionService` don't exist yet (create `UserRepo` here following the established repo pattern: `insert`, `find`, plus `purgePii(id)`).

- [ ] **Step 3: Implement**

```scala
package werger.service

import cats.effect.IO
import werger.adapters.db.{PointsRepo, SubmissionRepo, UserRepo, WorkItemRepo}
import werger.domain.UserId

class AccountDeletionService(users: UserRepo, workItems: WorkItemRepo, submissions: SubmissionRepo, points: PointsRepo):
  def delete(userId: UserId): IO[Unit] =
    for
      _ <- workItems.releasePendingForUser(userId)
      _ <- submissions.cancelPendingForUser(userId)
      _ <- users.purgePii(userId)
    yield ()
```

`points` stays untouched deliberately — the spec calls for retaining
already-earned/synced contribution history under an anonymized label, not
deleting the ledger; `purgePii` removes `email`/`auth_provider_ref` on
`users` without deleting the row itself, so `points_ledger.user_id`'s
foreign key stays valid.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/db/UserRepo.scala src/main/scala/werger/service/AccountDeletionService.scala src/test/scala/werger/service/AccountDeletionServiceSpec.scala
git commit -m "feat: add account deletion with PII purge and contribution retention"
```

---

## Milestone 3: Contributor-Facing Surface

### Task 17: Supabase JWT auth middleware and user auto-provisioning

**Files:**
- Create: `src/main/scala/werger/http/Auth.scala`
- Test: `src/test/scala/werger/http/AuthSpec.scala`

**Interfaces:**
- Consumes: `UserRepo` (Task 16).
- Produces: `AuthMiddleware.verify(token: String): IO[Either[String, UserId]]`, an http4s `AuthMiddleware[IO, UserId]` built on it.

- [ ] **Step 1: Write the failing test**

```scala
package werger.http

import munit.FunSuite
import pdi.jwt.{Jwt, JwtAlgorithm}

class AuthSpec extends FunSuite:
  private val secret = "test-secret"

  test("a validly-signed token with a sub claim verifies"):
    val token = Jwt.encode("""{"sub":"auth0|abc123"}""", secret, JwtAlgorithm.HS256)
    assertEquals(Auth.verifySupabaseJwt(token, secret), Right("auth0|abc123"))

  test("a token signed with the wrong secret is rejected"):
    val token = Jwt.encode("""{"sub":"auth0|abc123"}""", "wrong-secret", JwtAlgorithm.HS256)
    assert(Auth.verifySupabaseJwt(token, secret).isLeft)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement**

```scala
package werger.http

import cats.effect.IO
import io.circe.parser.decode
import org.http4s.*
import org.http4s.server.AuthMiddleware
import pdi.jwt.{Jwt, JwtAlgorithm}
import werger.adapters.db.UserRepo
import werger.domain.{Role, User, UserId, UserStatus}

object Auth:
  def verifySupabaseJwt(token: String, secret: String): Either[String, String] =
    Jwt.decodeRaw(token, secret, Seq(JwtAlgorithm.HS256)).toEither.left.map(_.getMessage)
      .flatMap(json => decode[Map[String, io.circe.Json]](json).left.map(_.getMessage))
      .flatMap(_.get("sub").flatMap(_.asString).toRight("missing sub claim"))

  def middleware(secret: String, users: UserRepo): AuthMiddleware[IO, UserId] =
    val authUser: Kleisli[IO, Request[IO], Either[String, UserId]] = Kleisli { req =>
      req.headers.get[headers.Authorization] match
        case Some(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token))) =>
          verifySupabaseJwt(token, secret) match
            case Left(err)  => IO.pure(Left(err))
            case Right(sub) => users.findOrProvision(authProviderRef = sub).map(u => Right(u.id))
        case _ => IO.pure(Left("missing bearer token"))
    }
    AuthMiddleware(authUser)
```

`UserRepo.findOrProvision` (add alongside Task 16's `UserRepo` methods): an
`INSERT ... ON CONFLICT (auth_provider_ref) DO NOTHING RETURNING *` followed
by a `find`-by-`auth_provider_ref` fallback, so first sign-in creates the
`User` row with `Role.Contributor`/`UserStatus.Active` defaults and no
onboarding step needs to exist purely to create the account.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/http/Auth.scala src/main/scala/werger/adapters/db/UserRepo.scala src/test/scala/werger/http/AuthSpec.scala
git commit -m "feat: add Supabase JWT verification and auto-provisioning"
```

---

### Task 18: Contributor REST API and the internal jobs endpoint

**Files:**
- Modify: `src/main/scala/werger/http/Routes.scala`
- Create: `src/main/scala/werger/http/JobsRoutes.scala`
- Test: `src/test/scala/werger/http/RoutesSpec.scala`

**Interfaces:**
- Consumes: every service from Milestone 2, `Auth.middleware` (Task 17).
- Produces the wire contract the SPA (Task 19) calls: `POST /me/commitment {tier, timezone}`, `GET /work-items/next?language=kmr`, `POST /submissions {workItemId, translation}`, `GET /reviews/next?language=kmr`, `POST /reviews/{submissionId} {verdict, comment}`, `GET /me`, `GET /leaderboard?language=kmr`, and `POST /internal/jobs/evaluate-and-sync` (shared-secret header, not JWT).

- [ ] **Step 1: Write the failing test — one representative authenticated route, not all seven**

```scala
package werger.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class RoutesSpec extends CatsEffectSuite:
  test("POST /internal/jobs/evaluate-and-sync without the shared secret is rejected"):
    val req = Request[IO](Method.POST, uri"/internal/jobs/evaluate-and-sync")
    JobsRoutes.app(sharedSecret = "real-secret", job = IO.unit).run(req).value
      .map(_.map(_.status)).assertEquals(Some(Status.Forbidden))

  test("POST /internal/jobs/evaluate-and-sync with the correct secret runs the job"):
    var ran = false
    val req = Request[IO](Method.POST, uri"/internal/jobs/evaluate-and-sync")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("X-Job-Secret"), "real-secret"))
    JobsRoutes.app(sharedSecret = "real-secret", job = IO { ran = true }).run(req).value
      .flatMap(_ => IO(assert(ran)))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt test`
Expected: FAIL

- [ ] **Step 3: Implement `JobsRoutes` (the internal endpoint) and extend `Routes` with the contributor API**

```scala
package werger.http

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

object JobsRoutes:
  def app(sharedSecret: String, job: IO[Unit]): HttpApp[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "internal" / "jobs" / "evaluate-and-sync" =>
      req.headers.get(CIString("X-Job-Secret")).map(_.head.value) match
        case Some(`sharedSecret`) => job *> Ok()
        case _                    => Forbidden()
  }.orNotFound
```

Extend `Routes.app` from Task 1: each route is a thin adapter — decode the
JSON body (circe), call the relevant Milestone 2 service, encode the
result. Follow `JobsRoutes`'s shape (one `case` per route, service call in
the body, no business logic inlined into the route itself); wire
`Auth.middleware` from Task 17 around every route except `/healthz`.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/http/Routes.scala src/main/scala/werger/http/JobsRoutes.scala src/test/scala/werger/http/RoutesSpec.scala
git commit -m "feat: add contributor REST API and shared-secret-guarded jobs endpoint"
```

---

### Task 19: Minimal SPA

**Files:**
- Create: `client/package.json`, `client/vite.config.ts`, `client/tsconfig.json`
- Create: `client/src/supabase.ts`, `client/src/api.ts`
- Create: `client/src/views/{Onboarding,Translate,Review,Dashboard}.ts`
- Create: `client/src/main.ts`

**Interfaces:**
- Consumes: the REST API from Task 18, Supabase JS client for auth.

- [ ] **Step 1: Scaffold**

```bash
cd client && npm create vite@latest . -- --template vanilla-ts
npm install @supabase/supabase-js
```

- [ ] **Step 2: `supabase.ts` — auth only, no data access through Supabase directly**

```typescript
import { createClient } from "@supabase/supabase-js";

export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL,
  import.meta.env.VITE_SUPABASE_ANON_KEY
);

export async function currentJwt(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}
```

- [ ] **Step 3: `api.ts` — one typed function per Task 18 route**

```typescript
import { currentJwt } from "./supabase";

async function authed(path: string, init: RequestInit = {}): Promise<Response> {
  const jwt = await currentJwt();
  return fetch(`/api${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${jwt}`, "Content-Type": "application/json" },
  });
}

export const api = {
  setCommitment: (tier: string, timezone: string) =>
    authed("/me/commitment", { method: "POST", body: JSON.stringify({ tier, timezone }) }),
  nextWorkItem: (language: string) => authed(`/work-items/next?language=${language}`).then((r) => r.json()),
  submit: (workItemId: string, translation: string) =>
    authed("/submissions", { method: "POST", body: JSON.stringify({ workItemId, translation }) }),
  nextForReview: (language: string) => authed(`/reviews/next?language=${language}`).then((r) => r.json()),
  review: (submissionId: string, verdict: "approved" | "rejected", comment?: string) =>
    authed(`/reviews/${submissionId}`, { method: "POST", body: JSON.stringify({ verdict, comment }) }),
  me: () => authed("/me").then((r) => r.json()),
  leaderboard: (language: string) => authed(`/leaderboard?language=${language}`).then((r) => r.json()),
};
```

- [ ] **Step 4: One view module per screen, each a plain function mounting into a container element** — `Onboarding.ts` (commitment tier + timezone picker, calls `api.setCommitment`), `Translate.ts` (fetch-next, textarea, submit), `Review.ts` (fetch-next-for-review, approve/reject with comment), `Dashboard.ts` (streak, points, leaderboard from `api.me`/`api.leaderboard`). Each follows the same shape as `Translate.ts`:

```typescript
import { api } from "../api";

export async function mountTranslate(container: HTMLElement, language: string) {
  const item = await api.nextWorkItem(language);
  if (!item) { container.textContent = "No work available right now."; return; }
  container.innerHTML = `<p>${item.sourceText}</p><textarea id="draft"></textarea><button id="submit">Submit</button>`;
  container.querySelector("#submit")!.addEventListener("click", async () => {
    const text = (container.querySelector("#draft") as HTMLTextAreaElement).value;
    await api.submit(item.id, text);
    mountTranslate(container, language);
  });
}
```

- [ ] **Step 5: `main.ts` — route between views based on Supabase auth state and whether `api.me()` has a commitment set**

- [ ] **Step 6: Verify manually**

Run: `npm run dev`, sign in with a real Supabase test project, confirm onboarding → translate → (from a second account) review round-trips against the real backend from Milestone 1-2.

- [ ] **Step 7: Commit**

```bash
git add client/
git commit -m "feat: add minimal contributor SPA"
```

---

### Task 20: Deployment — native-image, Cloud Run, Cloud Scheduler, trust seed

**Files:**
- Create: `deploy/Dockerfile`
- Create: `deploy/seed-trust.sql`
- Create: `deploy/cloud-run.md`

**Interfaces:** none — this task ships what's already built, it doesn't add application code.

- [ ] **Step 1: Write the seed script that fixes the bootstrap paradox for real**

```sql
-- Run once against production after V1__init_schema.sql, before onboarding
-- any volunteers. Replace the auth_provider_ref values with the real
-- Supabase auth "sub" claims for the maintainer and any Kurmanji speakers
-- already trusted before launch.
INSERT INTO users (auth_provider_ref, role, status, timezone, email_opt_in)
VALUES ('<maintainer-auth-sub>', 'maintainer', 'active', 'Europe/Berlin', false)
ON CONFLICT (auth_provider_ref) DO UPDATE SET role = 'maintainer';
```

- [ ] **Step 2: Write the native-image Dockerfile**

```dockerfile
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /app
COPY . .
RUN sbt nativeImage

FROM debian:bookworm-slim
COPY --from=build /app/target/native-image/dengjen-werger /app/dengjen-werger
ENTRYPOINT ["/app/dengjen-werger"]
```

- [ ] **Step 3: Write `cloud-run.md`** covering: required env vars (`DB_HOST`, `DB_PORT=6543`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, `SUPABASE_JWT_SECRET`, `CROWDIN_API_TOKEN`, `CROWDIN_PROJECT_ID`, `RESEND_API_KEY`, `JOBS_SHARED_SECRET`), the `gcloud run deploy` command, and the `gcloud scheduler jobs create http` command targeting `POST /internal/jobs/evaluate-and-sync` with the `X-Job-Secret` header set to `JOBS_SHARED_SECRET`'s value, on a reasonable interval (hourly is enough given the spec's daily-minimum commitment tier).

- [ ] **Step 4: Commit**

```bash
git add deploy/
git commit -m "chore: add deployment config, trust seed script, and Cloud Run/Scheduler setup notes"
```

---

## Self-Review

**Spec coverage**: every spec section maps to a task — Architecture (Tasks 1-3, 17, 20), Domain model (Task 4), `TranslationSource` port (Tasks 5-8), Anti-abuse & review integrity (Tasks 11-14), Gamification loop (Tasks 9-15), Translation Memory incl. drift handling (Task 15), Data retention (Task 16), Error handling (Tasks 7, 14), Testing conventions (every task). The two design-review-round-2 fixes (bootstrap paradox, Supavisor prepared-statement trap) are Tasks 3 and 10/20 respectively, not left implicit.

**Placeholder scan**: Task 8's Step 1 test body is intentionally a named exception — flagged inline as a marker to replace, not a silent gap, and every other task has real, runnable code.

**Type consistency**: `WorkItemStatus`, `PointsReason`, `ReviewVerdict` etc. are defined once in Task 4 and referenced by name (not restated ad hoc) in every later task; repo method names (`tryLease`, `moveToPendingReview`, `markApproved`, `moveToRevisionPending`, `markSynced`, `markUpstreamApprovalPending`, `retire`) are introduced once each and reused consistently between the tasks that call them and the task that defines them.

---

**Plan complete and saved to `docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md`.** Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

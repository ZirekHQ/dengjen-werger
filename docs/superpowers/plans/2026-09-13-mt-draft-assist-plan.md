# Machine-Translation Draft Assist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `MachineTranslationSource` port, a Google Cloud Translation adapter, and the pure points-award scoring function this feature needs — the two pieces of the spec that don't depend on unbuilt v1 machinery.

**Architecture:** A new port (`ports/MachineTranslationSource.scala`) mirrors the existing `TranslationSource` shape. An adapter under `adapters/googletranslate/` (client, codecs, source) implements it against Google Cloud Translation's v2 Basic API (API-key auth — chosen over v3's service-account/OAuth2 for v1 to avoid IAM credential plumbing disproportionate to this stage; swappable later since it's behind a port). Note: `adapters/crowdin/` at plan-writing time has only a client and codecs, no `CrowdinSource.scala` yet (that's v1 Task 8, unbuilt) — so this plan's client/codecs/source split isn't following an existing three-file precedent, it's establishing the shape `CrowdinSource` should later copy. A pure function in `service/DraftCredit.scala` decides `PointsReason.DraftConfirmed` vs. `SubmissionApproved` from two strings and a threshold, with no `IO` and no dependency on a repository.

**Tech Stack:** Scala 3, Cats Effect 3, http4s (`ember-client`, `circe`), circe, munit (`FunSuite` for pure logic, `CatsEffectSuite` for HTTP-backed specs) — all already in `build.sbt`, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-12-mt-draft-assist-design.md`

## Scope note — read before starting

This plan does **not** wire MT into the Crowdin sync job or the approval flow. As of this plan's writing, `JobsService`, `SubmissionService`, `PointsRepo`, and `ApprovalService` (Milestone 2 of `docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md`, Tasks 9-15) don't exist yet — only Milestone 1 through Task 6 is built. Writing tasks against files that don't exist would mean guessing at an interface v1 hasn't built yet, which is exactly what this plan's four tasks avoid: each one is complete, tested, and mergeable on its own, independent of Milestone 2.

When Milestone 2 is implemented, three additions belong there, not here:
- **Task 13/14 (`PointsRepo`/`ApprovalService`)**: at approval time, call `DraftCredit.pointsReasonFor(submission.proposedTranslation, workItemDraftAtSubmissionTime, matchThreshold)` (this plan's Task 4) instead of hardcoding `PointsReason.SubmissionApproved`, where `matchThreshold` comes from `sys.env.getOrElse("MT_DRAFT_MATCH_THRESHOLD", "1.0").toDouble`. Requires a migration adding `draft_confirmed` to the `points_reason` Postgres enum (`ALTER TYPE points_reason ADD VALUE 'draft_confirmed'`) — not written now, since nothing consumes it until this lands.
- **Task 15 (`JobsService`)**: for each `WorkItem` with `targetTextDraft = None`, call `GoogleTranslateSource.translateBatch` (this plan's Tasks 1-3) in chunks bounded by `MT_MAX_STRINGS_PER_SYNC`, and persist the result onto `targetTextDraft`. A failed or `Transient` batch leaves the field `None` for retry on the next run.
- **Task 20 (deployment)**: add `GOOGLE_TRANSLATE_API_KEY` to the documented Cloud Run env vars.

## Global Constraints

- `MT_DRAFT_MATCH_THRESHOLD` default is `1.0` (exact match after normalization) — from the spec's Domain model changes section.
- String comparison normalizes both sides with Unicode NFC and trims whitespace before computing similarity — from the spec's Domain model changes section.
- The adapter never passes `Language.code` straight through to the provider; it holds an explicit internal-code-to-provider-code table — from the spec's Architecture section.
- A batch call's partial failure must not lose results that did succeed — from the spec's Architecture section.
- MT never creates a `Submission` or moves a `WorkItemStatus` — it only ever fills `WorkItem.targetTextDraft` — from the spec's Non-goals section. No task in this plan touches `Submission`, `WorkItemStatus`, or `RevisionPending`.

---

## File Structure

```
src/main/scala/werger/
  ports/
    MachineTranslationSource.scala   # trait + MtError (Task 1)
  adapters/googletranslate/
    GoogleTranslateCodecs.scala      # circe codecs for the v2 API response (Task 1)
    GoogleTranslateClient.scala      # raw HTTP calls, chunked (Task 2)
    GoogleTranslateSource.scala      # MachineTranslationSource impl: language mapping + error mapping (Task 3)
  service/
    DraftCredit.scala                # pure: similarity + PointsReason choice (Task 4)
  domain/
    Model.scala                      # +DraftConfirmed case on PointsReason (Task 4)
src/test/scala/werger/
  adapters/googletranslate/
    GoogleTranslateCodecsSpec.scala
    GoogleTranslateClientSpec.scala
    GoogleTranslateSourceSpec.scala
  service/
    DraftCreditSpec.scala
```

---

### Task 1: `MachineTranslationSource` port and Google Translate JSON codecs

**Files:**
- Create: `src/main/scala/werger/ports/MachineTranslationSource.scala`
- Create: `src/main/scala/werger/adapters/googletranslate/GoogleTranslateCodecs.scala`
- Test: `src/test/scala/werger/adapters/googletranslate/GoogleTranslateCodecsSpec.scala`

**Interfaces:**
- Produces: `sealed trait MtError` with `MtError.Transient(message: String)` and `MtError.Unsupported(message: String)`; `trait MachineTranslationSource { def translateBatch(language: Language, sourceTexts: List[String]): IO[Either[MtError, Map[String, String]]] }`; `GoogleTranslateEnvelope(data: GoogleTranslateData)`, `GoogleTranslateData(translations: List[GoogleTranslation])`, `GoogleTranslation(translatedText: String)`, each with a derived circe `Decoder`.

- [ ] **Step 1: Write the failing codec test**

```scala
package werger.adapters.googletranslate

import io.circe.parser.decode
import munit.FunSuite

class GoogleTranslateCodecsSpec extends FunSuite:
  test("decodes Google Translate's v2 response envelope"):
    val json = """{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}"""
    val result = decode[GoogleTranslateEnvelope](json)
    assertEquals(result.map(_.data.translations.map(_.translatedText)), Right(List("Temam", "Na")))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateCodecsSpec"`
Expected: FAIL — `GoogleTranslateEnvelope` doesn't exist yet.

- [ ] **Step 3: Write the port and the codecs**

```scala
// src/main/scala/werger/ports/MachineTranslationSource.scala
package werger.ports

import cats.effect.IO
import werger.domain.Language

sealed trait MtError
object MtError:
  final case class Transient(message: String) extends MtError
  final case class Unsupported(message: String) extends MtError

/**
 * Adapts one hosted machine-translation provider. Never creates a `Submission` or moves a `WorkItemStatus` — callers
 * decide what to do with a translated batch.
 */
trait MachineTranslationSource:
  def translateBatch(language: Language, sourceTexts: List[String]): IO[Either[MtError, Map[String, String]]]
```

```scala
// src/main/scala/werger/adapters/googletranslate/GoogleTranslateCodecs.scala
package werger.adapters.googletranslate

import io.circe.Decoder
import io.circe.generic.semiauto.*

final case class GoogleTranslation(translatedText: String)
object GoogleTranslation:
  given Decoder[GoogleTranslation] = deriveDecoder

final case class GoogleTranslateData(translations: List[GoogleTranslation])
object GoogleTranslateData:
  given Decoder[GoogleTranslateData] = deriveDecoder

final case class GoogleTranslateEnvelope(data: GoogleTranslateData)
object GoogleTranslateEnvelope:
  given Decoder[GoogleTranslateEnvelope] = deriveDecoder
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateCodecsSpec"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/ports/MachineTranslationSource.scala \
        src/main/scala/werger/adapters/googletranslate/GoogleTranslateCodecs.scala \
        src/test/scala/werger/adapters/googletranslate/GoogleTranslateCodecsSpec.scala
git commit -m "feat: add MachineTranslationSource port and Google Translate JSON codecs"
```

---

### Task 2: `GoogleTranslateClient` — chunked HTTP calls to the v2 Basic API

**Files:**
- Create: `src/main/scala/werger/adapters/googletranslate/GoogleTranslateClient.scala`
- Test: `src/test/scala/werger/adapters/googletranslate/GoogleTranslateClientSpec.scala`

**Interfaces:**
- Consumes: `GoogleTranslateEnvelope` and friends (Task 1).
- Produces: `class GoogleTranslateClient(httpClient: Client[IO], apiKey: String, chunkSize: Int = 100)` with `def translate(texts: List[String], targetLanguageCode: String): IO[Map[String, String]]`, keyed by input text (not positional) so a failure in one chunk never discards another chunk's already-succeeded translations — this is the Global Constraint on partial-batch failure, and the reason the return type is a `Map` rather than a `List`. Internally chunked so one call never exceeds `chunkSize` strings in a single request to Google — verify `chunkSize`'s default against Google's current per-request limits before relying on it at production volume; it isn't a value the spec pins down. Raises only if every chunk fails (nothing to return); a partial success returns `IO.pure` of whatever succeeded.

- [ ] **Step 1: Write the failing test**

```scala
package werger.adapters.googletranslate

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class GoogleTranslateClientSpec extends CatsEffectSuite:
  test("translate pairs each source text with its translation"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      Ok("""{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key")
    client.translate(List("OK", "No"), targetLanguageCode = "ku").assertEquals(Map("OK" -> "Temam", "No" -> "Na"))

  test("translate splits requests larger than chunkSize into multiple calls"):
    var calls = 0
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      calls += 1
      Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
    client.translate(List("a", "b", "c"), targetLanguageCode = "ku").map(_ => calls).assertEquals(3)

  test("a failed chunk doesn't discard another chunk's already-succeeded translations"):
    val stub = HttpRoutes.of[IO] {
      case req @ POST -> Root / "language" / "translate" / "v2" :? _ =>
        req.as[String].flatMap { raw =>
          if raw.contains("\"a\"") then Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
          else InternalServerError("boom")
        }
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
    client.translate(List("a", "b"), targetLanguageCode = "ku").assertEquals(Map("a" -> "x"))

  test("translate raises when every chunk fails"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      InternalServerError("boom")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key")
    client.translate(List("a"), targetLanguageCode = "ku").attempt.map(_.isLeft).assertEquals(true)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateClientSpec"`
Expected: FAIL — `GoogleTranslateClient` doesn't exist yet.

- [ ] **Step 3: Write the client**

```scala
package werger.adapters.googletranslate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

/**
 * Raw calls to Google Cloud Translation's v2 Basic API. Chunks so one logical `translate` call never sends more
 * than `chunkSize` strings in a single request, and keys results by input text rather than position so one failed
 * chunk never discards another chunk's already-succeeded translations.
 */
class GoogleTranslateClient(httpClient: Client[IO], apiKey: String, chunkSize: Int = 100):
  private val endpoint = Uri.unsafeFromString("https://translation.googleapis.com/language/translate/v2")

  def translate(texts: List[String], targetLanguageCode: String): IO[Map[String, String]] =
    texts.grouped(chunkSize).toList
      .traverse(chunk => translateChunk(chunk, targetLanguageCode).attempt.map(chunk -> _))
      .flatMap(collectResults)

  private def translateChunk(chunk: List[String], targetLanguageCode: String): IO[List[String]] =
    val body: Json = Json.obj("q" -> chunk.asJson, "target" -> targetLanguageCode.asJson, "format" -> "text".asJson)
    val request = Request[IO](Method.POST, endpoint.withQueryParam("key", apiKey)).withEntity(body)
    httpClient.expect[GoogleTranslateEnvelope](request).map(_.data.translations.map(_.translatedText))

  // A chunk that never got a response contributes nothing rather than failing the whole batch; only re-raise when
  // no chunk succeeded, since then there is nothing worth returning.
  private def collectResults(results: List[(List[String], Either[Throwable, List[String]])]): IO[Map[String, String]] =
    val succeeded = results.collect { case (chunk, Right(translated)) => chunk.zip(translated) }.flatten.toMap
    results.collectFirst { case (_, Left(e)) => e } match
      case Some(e) if succeeded.isEmpty => IO.raiseError(e)
      case _ => IO.pure(succeeded)
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateClientSpec"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/googletranslate/GoogleTranslateClient.scala \
        src/test/scala/werger/adapters/googletranslate/GoogleTranslateClientSpec.scala
git commit -m "feat: add GoogleTranslateClient with request chunking"
```

---

### Task 3: `GoogleTranslateSource` — language mapping and `MtError` translation

**Files:**
- Create: `src/main/scala/werger/adapters/googletranslate/GoogleTranslateSource.scala`
- Test: `src/test/scala/werger/adapters/googletranslate/GoogleTranslateSourceSpec.scala`

**Interfaces:**
- Consumes: `MachineTranslationSource`, `MtError` (Task 1); `GoogleTranslateClient.translate` (Task 2); `werger.domain.Language`.
- Produces: `class GoogleTranslateSource(client: GoogleTranslateClient, languageCodes: Map[String, String] = GoogleTranslateSource.kurmanjiOnly) extends MachineTranslationSource`.

- [ ] **Step 1: Write the failing tests**

```scala
package werger.adapters.googletranslate

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import werger.domain.Language
import werger.ports.MtError

class GoogleTranslateSourceSpec extends CatsEffectSuite:
  private val kurmanji = Language("kmr", "Kurmanji Kurdish")

  test("translateBatch maps each source text to its translation"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      Ok("""{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK", "No")).assertEquals(Right(Map("OK" -> "Temam", "No" -> "Na")))

  test("translateBatch returns Unsupported without calling the provider for an unmapped language"):
    val stub = HttpRoutes.of[IO] { case _ => Ok("should not be called") }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(Language("xx", "Unmapped"), List("OK")).map {
      case Left(_: MtError.Unsupported) => true
      case _ => false
    }.assertEquals(true)

  test("translateBatch maps a 400 response to Unsupported"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      BadRequest("""{"error":{"code":400,"message":"invalid target"}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK")).map {
      case Left(_: MtError.Unsupported) => true
      case _ => false
    }.assertEquals(true)

  test("translateBatch maps a 429 response to Transient"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      TooManyRequests("""{"error":{"code":429,"message":"rate limited"}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK")).map {
      case Left(_: MtError.Transient) => true
      case _ => false
    }.assertEquals(true)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateSourceSpec"`
Expected: FAIL — `GoogleTranslateSource` doesn't exist yet.

- [ ] **Step 3: Write the source**

```scala
package werger.adapters.googletranslate

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.client.UnexpectedStatus
import werger.domain.Language
import werger.ports.{MachineTranslationSource, MtError}

object GoogleTranslateSource:
  // Google Translate has covered Kurdish under the single code "ku" since adding it in 2016; verify against
  // Google's current supported-languages list before depending on this in production.
  val kurmanjiOnly: Map[String, String] = Map("kmr" -> "ku")

class GoogleTranslateSource(
    client: GoogleTranslateClient,
    languageCodes: Map[String, String] = GoogleTranslateSource.kurmanjiOnly
) extends MachineTranslationSource:
  def translateBatch(language: Language, sourceTexts: List[String]): IO[Either[MtError, Map[String, String]]] =
    languageCodes.get(language.code) match
      case None =>
        IO.pure(Left(MtError.Unsupported(s"No Google Translate code mapped for language ${language.code}")))
      case Some(providerCode) =>
        client
          .translate(sourceTexts, providerCode)
          .map(Right(_))
          .recover {
            case UnexpectedStatus(status, _, _) if status.code == 400 =>
              Left(MtError.Unsupported(s"Google Translate rejected the request: $status"))
            case UnexpectedStatus(status, _, _) =>
              Left(MtError.Transient(s"Google Translate returned $status"))
            case e: Throwable =>
              Left(MtError.Transient(e.getMessage))
          }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly werger.adapters.googletranslate.GoogleTranslateSourceSpec"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/adapters/googletranslate/GoogleTranslateSource.scala \
        src/test/scala/werger/adapters/googletranslate/GoogleTranslateSourceSpec.scala
git commit -m "feat: add GoogleTranslateSource with language mapping and error translation"
```

---

### Task 4: `DraftCredit` — pure similarity scoring and `PointsReason.DraftConfirmed`

**Files:**
- Modify: `src/main/scala/werger/domain/Model.scala:73-74` (add `DraftConfirmed` to `PointsReason`)
- Create: `src/main/scala/werger/service/DraftCredit.scala`
- Test: `src/test/scala/werger/service/DraftCreditSpec.scala`

**Interfaces:**
- Consumes: `werger.domain.PointsReason` (modified in this task).
- Produces: `object DraftCredit { def pointsReasonFor(proposedTranslation: String, draftAtSubmission: Option[String], matchThreshold: Double): PointsReason }`. Later work (v1 Task 14, `ApprovalService`) is the consumer — not built in this plan.

- [ ] **Step 1: Write the failing tests**

```scala
package werger.service

import munit.FunSuite
import werger.domain.PointsReason

class DraftCreditSpec extends FunSuite:
  test("no draft at submission time always yields SubmissionApproved"):
    assertEquals(DraftCredit.pointsReasonFor("Temam", None, matchThreshold = 1.0), PointsReason.SubmissionApproved)

  test("an exact match at threshold 1.0 yields DraftConfirmed"):
    assertEquals(
      DraftCredit.pointsReasonFor("Temam", Some("Temam"), matchThreshold = 1.0),
      PointsReason.DraftConfirmed
    )

  test("any edit at threshold 1.0 yields SubmissionApproved"):
    assertEquals(
      DraftCredit.pointsReasonFor("Temam.", Some("Temam"), matchThreshold = 1.0),
      PointsReason.SubmissionApproved
    )

  test("whitespace and Unicode normalization differences alone don't count as an edit"):
    val precomposed = "Têmam" // "ê" as a single code point
    val decomposed = "Têmam" // "e" + combining circumflex, same rendered text
    assertEquals(
      DraftCredit.pointsReasonFor(s"  $decomposed  ", Some(precomposed), matchThreshold = 1.0),
      PointsReason.DraftConfirmed
    )

  test("a raised threshold tolerates a small edit as still-confirmed"):
    assertEquals(
      DraftCredit.pointsReasonFor("Temam.", Some("Temam"), matchThreshold = 0.8),
      PointsReason.DraftConfirmed
    )
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "testOnly werger.service.DraftCreditSpec"`
Expected: FAIL — `DraftCredit` doesn't exist, and `PointsReason.DraftConfirmed` doesn't exist.

- [ ] **Step 3: Add the enum case and write `DraftCredit`**

In `src/main/scala/werger/domain/Model.scala`, change:

```scala
enum PointsReason:
  case SubmissionApproved, ReviewCompleted
```

to:

```scala
enum PointsReason:
  case SubmissionApproved, ReviewCompleted, DraftConfirmed
```

```scala
// src/main/scala/werger/service/DraftCredit.scala
package werger.service

import werger.domain.PointsReason

import java.text.Normalizer

/**
 * Decides whether an approved submission represents real work or a near-untouched machine-translation draft, so
 * points-awarding code doesn't have to know how a submission's origin is tracked.
 */
object DraftCredit:
  def pointsReasonFor(proposedTranslation: String, draftAtSubmission: Option[String], matchThreshold: Double): PointsReason =
    val confirmed = draftAtSubmission.exists(draft => similarity(proposedTranslation, draft) >= matchThreshold)
    if confirmed then PointsReason.DraftConfirmed else PointsReason.SubmissionApproved

  private def normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).trim

  private def similarity(a: String, b: String): Double =
    val (na, nb) = (normalize(a), normalize(b))
    val maxLen = math.max(na.length, nb.length)
    if maxLen == 0 then 1.0 else 1.0 - levenshtein(na, nb).toDouble / maxLen

  private def levenshtein(a: String, b: String): Int =
    val firstRow = (0 to b.length).toVector
    a.foldLeft(firstRow)((prevRow, ca) => nextRow(prevRow, ca, b)).last

  private def nextRow(prevRow: Vector[Int], ca: Char, b: String): Vector[Int] =
    b.indices.foldLeft(Vector(prevRow.head + 1)) { (row, j) =>
      val cost = if ca == b(j) then prevRow(j) else 1 + List(prevRow(j), prevRow(j + 1), row.last).min
      row :+ cost
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt "testOnly werger.service.DraftCreditSpec werger.domain.ModelSpec"`
Expected: PASS (`ModelSpec` included since `Model.scala` changed — confirms the existing `PointsReason` usages still compile.)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/werger/domain/Model.scala \
        src/main/scala/werger/service/DraftCredit.scala \
        src/test/scala/werger/service/DraftCreditSpec.scala
git commit -m "feat: add DraftCredit scoring and PointsReason.DraftConfirmed"
```

---

## Self-review

**Spec coverage:** Architecture's port/adapter/batching/language-mapping/auth requirements → Tasks 1-3. Domain model changes (`DraftConfirmed`, normalized similarity, configurable threshold) → Task 4. Error handling (`Transient`/`Unsupported` mapping, partial-batch results not lost) → Tasks 2-3. Testing conventions (stub-based, no live calls, pure functions tested without `IO`) → every task. Anti-abuse note (non-zero credit for a confirmed draft) → Task 4's `DraftConfirmed` case exists as a real, distinct, non-zero-implying `PointsReason` rather than the design's outcome being silently dropped. Sync-job wiring, approval-time wiring, and the `points_reason` migration are explicitly out of this plan's scope (see "Scope note" above) because their consumers don't exist yet — not a gap, a boundary.

**Placeholder scan:** No TBDs. The one explicitly-flagged unknown (Google's exact current Kurdish/Kurmanji language code and per-request batch limit) is called out as a concrete pre-merge verification step in Task 3's comment and Task 2's client doc-comment, not left as an implementation gap — the code has a real default either way.

**Type consistency:** `MachineTranslationSource.translateBatch` signature is identical across Task 1 (definition), Task 3 (implementation), and the Scope note's description of the future `JobsService` call site. `PointsReason.DraftConfirmed` (Task 4) is the exact name the Scope note's future `ApprovalService` call site uses.

## Branch

Create off `main`: `feature/mt_draft_assist`. Not created or committed yet — say the word when you want this started.

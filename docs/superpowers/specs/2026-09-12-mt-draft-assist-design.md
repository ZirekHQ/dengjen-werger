# Dengjen Werger: Machine-Translation Draft Assist

## Context

Dengjen Werger's commitment/review loop (see
`2026-09-11-dengjen-werger-design.md`) has no translators or reviewers yet.
There is nothing in the Kurmanji work-item backlog for a first reviewer to
review, and nothing to demonstrate the loop with. Separately, Google Cloud
Translation and Meta's NLLB-200 both already cover Kurmanji (`kmr`/`kmr_Latn`),
so building a new Kurmanji MT model is neither necessary nor this project's
concern — the open-source Kurmanji MT corpora and benchmarks referenced when
this idea was raised (InterdialectCorpus, AWATA, sinaahmadi/KurdishMT) stay
out of scope; they'd only become relevant if a chosen provider's quality
proves too poor to seed with.

The base spec already has a bootstrap answer for "nobody has standing to
review yet": it seeds the maintainer (and any Kurmanji speakers already
trusted before launch) with `Role.Maintainer`/`Reviewer` directly, plus a
batch "express review" view, specifically so clearing the day-one queue is
seconds of work rather than a one-by-one slog. This feature is designed to
feed that mechanism rather than duplicate it — see Architecture.

## Problem

The backlog starts empty of translations, and every new NVDA string added
later starts the same way: nothing for a volunteer to build on. Machine
translation can fill that starting draft, both for the initial backlog and
for every string added after it — but only if it doesn't quietly defeat the
product's core promise: that an approved translation was actually checked by
a person with standing to check it, and that points/streaks reflect real
work.

## Scope

- One new port, `MachineTranslationSource`, and one adapter for a hosted MT
  provider (Google Cloud Translation, chosen for existing Kurmanji support;
  swappable later since it sits behind a port).
- Drafts fill `WorkItem.targetTextDraft` as part of the existing Crowdin sync
  job (`POST /internal/jobs/evaluate-and-sync`), batched and capped per run.
- A submission whose proposed translation is near-identical to the draft it
  started from earns a smaller, but non-zero, points award than one that
  represents real work — via a new `PointsReason` case, not a new domain
  field.
- Self-hosting a model (e.g. NLLB-200) is an explicit fallback, only if the
  hosted provider's quality or cost proves unworkable — not built now.
- Review stays blind to origin (a draft-derived submission looks identical
  to a from-scratch one to a reviewer), per the product's existing review
  design.

## Non-goals

- Training, fine-tuning, or evaluating a Kurmanji MT model.
- Using the cited parallel corpora as translation memory or lookup data.
- Any autonomous path that creates a `Submission` without a human submitter.
  The initial backlog is cleared by the already-seeded maintainer/reviewer
  using drafts as a starting point, through the existing submit flow — not
  by a second, submitter-less pipeline. This avoids inventing a new review
  tier and avoids a rejection path with no one to hand a revision back to.
- A distinct UI, workflow, or admin action for "seeding" the backlog — it is
  the same mechanism as ongoing draft-assist, not a special case.

## Architecture

```scala
sealed trait MtError
object MtError:
  final case class Transient(message: String) extends MtError
  final case class Unsupported(message: String) extends MtError

trait MachineTranslationSource:
  def translateBatch(
      language: Language,
      sourceTexts: List[String]
  ): IO[Either[MtError, Map[String, String]]]
```

A batch shape, not one call per string: the sync job may be filling drafts
for the entire current backlog on its first run, and a per-string HTTP round
trip at that volume risks the sync job's own request timeout. `Map[String,
String]` keys results by input text since a partial-batch provider failure
must not lose which strings succeeded.

The adapter (`adapters/googletranslate/...`) implements this against Google
Cloud Translation using `http4s-ember-client` and `circe`, matching the
existing Crowdin adapter's structural style. Two details the adapter owns
explicitly, decided at implementation time rather than left implicit:
- **Auth scheme** — Google Cloud Translation's API-key (v2) and
  service-account/OAuth2 (v3) paths are different shapes; the adapter picks
  one and documents it, rather than assuming it can reuse Crowdin's
  bearer-token-via-`sys.env` pattern unchanged.
- **Language-code mapping** — the adapter holds an explicit internal-code
  (`kmr`) to provider-code table rather than passing `Language.code` through
  raw. Provider language codes for Kurmanji vary by vendor and API version;
  this must be verified against the live API during implementation, not
  assumed.

The Crowdin sync job — the same job that fetches/refreshes `WorkItem`s —
becomes the place that fills `targetTextDraft` for any item that doesn't
already have one, bounded by `MT_MAX_STRINGS_PER_SYNC` (env var) per run so
one run never risks the job's timeout or the provider's rate limits; any
remainder is picked up by the next run, since "fill any item still missing a
draft" is idempotent by construction. A work item with a filled draft stays
`Available` — MT never moves a `WorkItem`'s status or creates a `Submission`
on its own. When a volunteer (including the seeded maintainer, clearing the
initial backlog through the express-review view) opens a work item, the UI
shows `targetTextDraft` as an editable starting point, or blank if MT hasn't
reached it yet — starting work is never blocked on MT.

## Domain model changes

One addition, nothing else:

```scala
enum PointsReason:
  case SubmissionApproved, ReviewCompleted, DraftConfirmed
```

At approval time, the existing approval-handling code compares the
submission's `proposedTranslation` against the `WorkItem`'s
`targetTextDraft` at the time it was submitted — both fields already exist,
so this needs no new column or `Submission` field. The comparison
normalizes both strings (Unicode NFC, trimmed) and computes a similarity
ratio (not a raw edit count, which is meaningless across wildly different
string lengths — a threshold tuned for a 40-character sentence would trip
on any edit to a 4-character button label). Above a configured threshold
(`MT_DRAFT_MATCH_THRESHOLD`, default `1.0` — exact match after
normalization, for v1), the submitter's ledger entry uses
`PointsReason.DraftConfirmed` (a smaller amount) instead of
`SubmissionApproved`. No `targetTextDraft` on the item at submission time
means the comparison is skipped and `SubmissionApproved` applies as today.

`Submission`, `WorkItemStatus`, and `RevisionPending` are unchanged — every
submission still has a real submitter, and rejection still hands the
revision back to them exactly as the base spec describes.

## Error handling

- A `Transient` or failed batch call leaves `targetTextDraft = None` for
  whatever it didn't successfully translate; those items stay `Available`
  exactly as they'd be without this feature, and the next sync run retries
  automatically.
- `MtError.Unsupported` signals a misconfigured language code — a
  startup/integration concern to catch during adapter setup, not per-item
  state to persist or retry around.
- A partial batch failure (some strings succeed, some don't) must not lose
  the successful results — the adapter returns what it has via the
  `Map[String, String]`, not all-or-nothing.

## Testing

Follows the existing `CrowdinClientSpec` pattern: an http4s `HttpRoutes.of[IO]`
stub wired via `Client.fromHttpApp`, `munit`'s `CatsEffectSuite`, no live
network calls.

- **Adapter spec** — stub the provider's batch endpoint; assert successful
  decoding, correct language-code mapping, partial-batch results surfacing
  correctly, and provider error responses mapping to `MtError.Transient` /
  `Unsupported`.
- **Domain spec** — pure unit tests for the normalized-similarity comparison
  and the `DraftConfirmed`-vs-`SubmissionApproved` choice it drives, at the
  default threshold and a raised one; no `IO` needed.
- **Sync-job spec** — a stub `MachineTranslationSource` covering full
  success, `Transient` failure, and partial-batch cases, proving: drafts
  fill only when absent, `MT_MAX_STRINGS_PER_SYNC` is respected, and a
  failed batch never blocks the rest of the sync job (work-item fetch,
  memory sync, commitment evaluation).

## Anti-abuse note

Because confirming a draft as-is still earns `DraftConfirmed` (non-zero)
rather than nothing, there's no cliff that rewards a trivial edit (a
trailing space, a swapped synonym) purely to escape a zero-credit outcome —
the incentive to game the threshold that a strict "exact match = zero"
rule would create doesn't exist here. `MT_DRAFT_MATCH_THRESHOLD` and each
`PointsReason`'s amount are both adjustable without a domain-model change if
real usage shows the gap between the two needs tuning.

## Fallback

If Google Cloud Translation's Kurmanji quality or cost proves unworkable,
the fallback is a self-hosted NLLB-200 model (covers `kmr_Latn`) behind the
same `MachineTranslationSource` port — no changes needed above the adapter
boundary. This is not built now; it's recorded here so the swap-in path is
already agreed if it's ever needed.

## Schema impact

One migration, additive only: `ALTER TYPE points_reason ADD VALUE
'draft_confirmed'`. No changes to `work_items` or `submissions` — the
comparison this feature needs is computed from columns that already exist.

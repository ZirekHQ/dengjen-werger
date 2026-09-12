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

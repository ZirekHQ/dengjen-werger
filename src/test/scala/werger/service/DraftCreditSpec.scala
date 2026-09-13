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
    val decomposed = "Têmam" // "e" + combining circumflex, same rendered text
    assertEquals(
      DraftCredit.pointsReasonFor(s"  $decomposed  ", Some(precomposed), matchThreshold = 1.0),
      PointsReason.DraftConfirmed
    )

  test("a raised threshold tolerates a small edit as still-confirmed"):
    assertEquals(
      DraftCredit.pointsReasonFor("Temam.", Some("Temam"), matchThreshold = 0.8),
      PointsReason.DraftConfirmed
    )

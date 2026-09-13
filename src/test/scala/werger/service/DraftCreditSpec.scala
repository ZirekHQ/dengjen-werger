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
    val precomposed = "Têmam" // U+00EA (precomposed ê as single code point)
    val decomposed = "Têmam" // U+0065 (e) + U+0302 (combining circumflex)
    assertEquals(
      DraftCredit.pointsReasonFor(s"  $decomposed  ", Some(precomposed), matchThreshold = 1.0),
      PointsReason.DraftConfirmed
    )

  test("a lowered threshold tolerates a small edit as still-confirmed"):
    assertEquals(
      DraftCredit.pointsReasonFor("Temam.", Some("Temam"), matchThreshold = 0.8),
      PointsReason.DraftConfirmed
    )

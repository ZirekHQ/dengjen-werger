package werger.service

import werger.domain.PointsReason

import java.text.Normalizer

/**
 * Decides whether an approved submission represents real work or a near-untouched machine-translation draft, so
 * points-awarding code doesn't have to know how a submission's origin is tracked.
 */
object DraftCredit:
  def pointsReasonFor(
      proposedTranslation: String,
      draftAtSubmission: Option[String],
      matchThreshold: Double
  ): PointsReason =
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

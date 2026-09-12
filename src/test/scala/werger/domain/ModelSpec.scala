package werger.domain

import munit.FunSuite

import java.time.Instant
import java.util.UUID

class ModelSpec extends FunSuite:
  test("WorkItemStatus.InProgress carries the leaseholder and expiry, not a bare flag"):
    val userId = UUID.randomUUID()
    val expiry = Instant.now()
    val status: WorkItemStatus.InProgress = WorkItemStatus.InProgress(userId, expiry)
    assertEquals(status.userId, userId)
    assertEquals(status.expiresAt, expiry)

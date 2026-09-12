package werger.adapters.db

import cats.effect.IO
import munit.CatsEffectSuite

class DbSpec extends CatsEffectSuite:
  test("buildPooled fails through IO's error channel, not a fatal Error, when a required var is missing"):
    Db.buildPooled(_ => None).use(_ => IO.unit).attempt.map {
      case Left(_: NoSuchElementException) => ()
      case other => fail(s"expected a NoSuchElementException raised through IO, got: $other")
    }

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

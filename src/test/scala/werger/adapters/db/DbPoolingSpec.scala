package werger.adapters.db

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import skunk.codec.all.*
import skunk.implicits.*

class DbPoolingSpec extends CatsEffectSuite:
  // Db reads DB_HOST etc. from sys.env; without it set, this needs to skip
  // cleanly (a fork PR gets no repository secrets, and a local run may have
  // none either) rather than fail on a suite that was never going to have
  // what it needs.
  sys.env.get("DB_HOST") match
    case Some(_) =>
      test("running the same query many times over a small pool doesn't hit a stale prepared statement"):
        Db.pooled.use { pool =>
          val query = pool.use(_.unique(sql"select 1".query(int4)))
          (1 to 20).toList.parTraverse(_ => query).map(_.forall(_ == 1)).assert
        }
    case None =>
      test("skipped — set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against real Supabase"):
        IO.unit

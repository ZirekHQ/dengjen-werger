package werger.adapters.db

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import skunk.codec.all.*
import skunk.implicits.*

class DbPoolingSpec extends CatsEffectSuite:
  // Skips instead of failing when Postgres creds are absent — this integration
  // test may run without them locally or on a fork PR. A fork PR's ${{ secrets.X }}
  // resolves to "", not an unset var, so this checks non-empty rather than Some(_).
  private def hasCredentials: Boolean =
    List("DB_HOST", "DB_USER", "DB_PASSWORD", "DB_NAME").forall(name => sys.env.get(name).exists(_.nonEmpty))

  if hasCredentials then
    test("running the same query many times over a small pool doesn't hit a stale prepared statement"):
      Db.pooled.use { pool =>
        val query = pool.use(_.unique(sql"select 1".query(int4)))
        (1 to 20).toList.parTraverse(_ => query).map(_.forall(_ == 1)).assert
      }
  else
    test("skipped — set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against real Supabase"):
      IO.unit

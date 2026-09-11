package werger.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class RoutesSpec extends CatsEffectSuite:
  test("GET /healthz returns 200 with ok body"):
    val req = Request[IO](Method.GET, uri"/healthz")
    Routes.app.orNotFound.run(req).flatMap { r =>
      r.as[String].map(b => (r.status, b))
    }.assertEquals((Status.Ok, "ok"))

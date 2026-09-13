package werger.http

import cats.effect.IO
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*

class RoutesSpec extends CatsEffectSuite:
  given Decoder[HealthStatus] = deriveDecoder

  test("GET /healthz returns 200 with a status: ok health payload"):
    val req = Request[IO](Method.GET, uri"/healthz")
    Routes.app.orNotFound
      .run(req)
      .flatMap { r =>
        r.as[HealthStatus].map(b => (r.status, b.status, b.version.nonEmpty, b.commit.nonEmpty))
      }
      .assertEquals((Status.Ok, "ok", true, true))

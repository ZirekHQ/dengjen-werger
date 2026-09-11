package werger.http

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

object Routes:
  val app: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" => Ok("ok")
  }

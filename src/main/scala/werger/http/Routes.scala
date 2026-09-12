package werger.http

import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import werger.BuildInfo

final case class HealthStatus(status: String, version: String, commit: String, builtAt: String)
object HealthStatus:
  given Encoder[HealthStatus] = deriveEncoder

object Routes:
  val app: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "healthz" =>
    Ok(
      HealthStatus(
        status = "ok",
        version = BuildInfo.version,
        commit = BuildInfo.gitCommit,
        builtAt = java.time.Instant.now().toString
      )
    )
  }

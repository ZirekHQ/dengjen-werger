package werger

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import werger.http.Routes

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    EmberServerBuilder.default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(Routes.app.orNotFound)
      .build
      .useForever

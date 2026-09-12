package werger.adapters.db

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

object Db:
  private given Meter[IO] = Meter.Implicits.noop[IO]

  private val host = sys.env("DB_HOST")
  private val port = sys.env.getOrElse("DB_PORT", "6543").toInt
  private val user = sys.env("DB_USER")
  private val password = sys.env("DB_PASSWORD")
  private val database = sys.env("DB_NAME")

  val pooled: Resource[IO, Resource[IO, Session[IO]]] =
    Session
      .Builder[IO]
      .withHost(host)
      .withPort(port)
      .withUserAndPassword(user, password)
      .withDatabase(database)
      .withTypingStrategy(skunk.TypingStrategy.SearchPath)
      .pooled(max = 8)(using Tracer.Implicits.noop[IO])

package werger.adapters.db

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

object Db:
  private given Meter[IO] = Meter.Implicits.noop[IO]

  def buildPooled(config: DbConfig): Resource[IO, Resource[IO, Session[IO]]] =
    Session
      .Builder[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .withTypingStrategy(skunk.TypingStrategy.SearchPath)
      .pooled(max = 8)(using Tracer.Implicits.noop[IO])

  val pooled: Resource[IO, Resource[IO, Session[IO]]] =
    Resource.eval(DbConfig.fromEnv(sys.env.get)).flatMap(buildPooled)

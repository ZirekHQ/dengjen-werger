package werger.adapters.db

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

object Db:
  private given Meter[IO] = Meter.Implicits.noop[IO]

  private def required(lookup: String => Option[String], name: String): IO[String] =
    IO.fromOption(lookup(name))(new NoSuchElementException(s"Missing required env var: $name"))

  /**
   * Builds the pool description from an env-var lookup function without reading any of them yet — `lookup` only runs
   * once the returned `Resource` is used, so a missing var surfaces as a normal `IO` failure (catchable via `.attempt`)
   * instead of a fatal `Error` thrown during object initialization.
   */
  def buildPooled(lookup: String => Option[String]): Resource[IO, Resource[IO, Session[IO]]] =
    Resource.eval {
      for
        host <- required(lookup, "DB_HOST")
        port <- IO(lookup("DB_PORT").getOrElse("6543").toInt)
        user <- required(lookup, "DB_USER")
        password <- required(lookup, "DB_PASSWORD")
        database <- required(lookup, "DB_NAME")
      yield (host, port, user, password, database)
    }.flatMap { case (host, port, user, password, database) =>
      Session
        .Builder[IO]
        .withHost(host)
        .withPort(port)
        .withUserAndPassword(user, password)
        .withDatabase(database)
        .withTypingStrategy(skunk.TypingStrategy.SearchPath)
        .pooled(max = 8)(using Tracer.Implicits.noop[IO])
    }

  val pooled: Resource[IO, Resource[IO, Session[IO]]] = buildPooled(sys.env.get)

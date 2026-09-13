package werger.adapters.db

import cats.effect.IO

final case class DbConfig(host: String, port: Int, user: String, password: String, database: String)

object DbConfig:
  private def required(lookup: String => Option[String], name: String): IO[String] =
    IO.fromOption(lookup(name))(new NoSuchElementException(s"Missing required env var: $name"))

  def fromEnv(lookup: String => Option[String]): IO[DbConfig] =
    for
      host <- required(lookup, "DB_HOST")
      port <- IO(lookup("DB_PORT").getOrElse("6543").toInt)
      user <- required(lookup, "DB_USER")
      password <- required(lookup, "DB_PASSWORD")
      database <- required(lookup, "DB_NAME")
    yield DbConfig(host, port, user, password, database)

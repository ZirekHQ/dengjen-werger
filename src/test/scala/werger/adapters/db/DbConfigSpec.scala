package werger.adapters.db

import munit.CatsEffectSuite

class DbConfigSpec extends CatsEffectSuite:
  private val allPresent: Map[String, String] = Map(
    "DB_HOST" -> "localhost",
    "DB_PORT" -> "5432",
    "DB_USER" -> "user",
    "DB_PASSWORD" -> "pass",
    "DB_NAME" -> "db"
  )

  test("fromEnv fails through IO's error channel, not a fatal Error, when a required var is missing"):
    DbConfig.fromEnv(_ => None).attempt.map {
      case Left(_: NoSuchElementException) => ()
      case other => fail(s"expected a NoSuchElementException raised through IO, got: $other")
    }

  test("fromEnv succeeds and parses all fields when every var is present"):
    DbConfig.fromEnv(allPresent.get).assertEquals(DbConfig("localhost", 5432, "user", "pass", "db"))

  test("fromEnv defaults DB_PORT to 6543 when unset"):
    DbConfig.fromEnv((allPresent - "DB_PORT").get).map(_.port).assertEquals(6543)

  test("fromEnv fails through IO's error channel on a non-numeric DB_PORT"):
    DbConfig.fromEnv((allPresent + ("DB_PORT" -> "not-a-number")).get).attempt.map {
      case Left(_: NumberFormatException) => ()
      case other => fail(s"expected a NumberFormatException raised through IO, got: $other")
    }

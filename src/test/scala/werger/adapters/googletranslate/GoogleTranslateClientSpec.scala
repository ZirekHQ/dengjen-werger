package werger.adapters.googletranslate

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class GoogleTranslateClientSpec extends CatsEffectSuite:
  test("translate pairs each source text with its translation"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      Ok("""{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key")
    client.translate(List("OK", "No"), targetLanguageCode = "ku").assertEquals(Map("OK" -> "Temam", "No" -> "Na"))

  test("translate splits requests larger than chunkSize into multiple calls"):
    var calls = 0
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      calls += 1
      Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
    client.translate(List("a", "b", "c"), targetLanguageCode = "ku").map(_ => calls).assertEquals(3)

  test("a failed chunk doesn't discard another chunk's already-succeeded translations"):
    val stub = HttpRoutes.of[IO] {
      case req @ POST -> Root / "language" / "translate" / "v2" :? _ =>
        req.as[String].flatMap { raw =>
          if raw.contains("\"a\"") then Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
          else InternalServerError("boom")
        }
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
    client.translate(List("a", "b"), targetLanguageCode = "ku").assertEquals(Map("a" -> "x"))

  test("translate raises when every chunk fails"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      InternalServerError("boom")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key")
    client.translate(List("a"), targetLanguageCode = "ku").attempt.map(_.isLeft).assertEquals(true)

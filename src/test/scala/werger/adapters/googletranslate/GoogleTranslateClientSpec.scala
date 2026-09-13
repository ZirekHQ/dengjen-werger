package werger.adapters.googletranslate

import cats.effect.{IO, Ref}
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
    for
      calls <- Ref.of[IO, Int](0)
      stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
        calls.update(_ + 1) *> Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
      }.orNotFound
      client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
      _ <- client.translate(List("a", "b", "c"), targetLanguageCode = "ku")
      count <- calls.get
    yield assertEquals(count, 3)

  test("translate sends the api key as a header, never in the request URI"):
    for
      capturedUri <- Ref.of[IO, Option[Uri]](None)
      stub = HttpRoutes.of[IO] { case req @ POST -> Root / "language" / "translate" / "v2" :? _ =>
        capturedUri.set(Some(req.uri)) *> Ok("""{"data":{"translations":[{"translatedText":"Temam"}]}}""")
      }.orNotFound
      client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "super-secret-key")
      _ <- client.translate(List("OK"), targetLanguageCode = "ku")
      uri <- capturedUri.get
    yield assert(!uri.exists(_.toString.contains("key=")), s"api key leaked into request URI: $uri")

  test("a chunk with fewer translations than requested texts is treated as failed, other chunks unaffected"):
    val stub = HttpRoutes.of[IO] {
      case req @ POST -> Root / "language" / "translate" / "v2" :? _ =>
        req.as[String].flatMap { raw =>
          if raw.contains("\"a\"") then Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
          else Ok("""{"data":{"translations":[]}}""") // fewer translations than the 1 requested text
        }
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", chunkSize = 1)
    client.translate(List("a", "b"), targetLanguageCode = "ku").assertEquals(Map("a" -> "x"))

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

  test("translate splits a chunk that would exceed maxRequestBytes even under chunkSize"):
    for
      calls <- Ref.of[IO, Int](0)
      stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
        calls.update(_ + 1) *> Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
      }.orNotFound
      // One serialized request for "aaaa" is 44 bytes, for both texts together 51 — a 45-byte cap fits one but not
      // both, forcing a split by size alone even though both texts fit well under chunkSize.
      client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", maxRequestBytes = 45)
      _ <- client.translate(List("aaaa", "bbbb"), targetLanguageCode = "ku")
      count <- calls.get
    yield assertEquals(count, 2)

  test("translate excludes a text whose own request would exceed maxRequestBytes, without calling Google"):
    for
      calls <- Ref.of[IO, Int](0)
      stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
        calls.update(_ + 1) *> Ok("""{"data":{"translations":[{"translatedText":"x"}]}}""")
      }.orNotFound
      // No single text's request can fit under a 5-byte cap, so nothing should ever be sent.
      client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key", maxRequestBytes = 5)
      result <- client.translate(List("a", "b"), targetLanguageCode = "ku")
      count <- calls.get
    yield
      assertEquals(result, Map.empty[String, String])
      assertEquals(count, 0)

  test("translate raises when every chunk fails"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      InternalServerError("boom")
    }.orNotFound
    val client = new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key")
    client.translate(List("a"), targetLanguageCode = "ku").attempt.map(_.isLeft).assertEquals(true)

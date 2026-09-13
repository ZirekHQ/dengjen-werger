package werger.adapters.googletranslate

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import werger.domain.Language
import werger.ports.MtError

class GoogleTranslateSourceSpec extends CatsEffectSuite:
  private val kurmanji = Language("kmr", "Kurmanji Kurdish")

  test("translateBatch maps each source text to its translation"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      Ok("""{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK", "No")).assertEquals(Right(Map("OK" -> "Temam", "No" -> "Na")))

  test("translateBatch returns Unsupported without calling the provider for an unmapped language"):
    val stub = HttpRoutes.of[IO] { case _ => Ok("should not be called") }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(Language("xx", "Unmapped"), List("OK")).map {
      case Left(_: MtError.Unsupported) => true
      case _ => false
    }.assertEquals(true)

  test("translateBatch maps a 400 response to Unsupported"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      BadRequest("""{"error":{"code":400,"message":"invalid target"}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK")).map {
      case Left(_: MtError.Unsupported) => true
      case _ => false
    }.assertEquals(true)

  test("translateBatch maps a 429 response to Transient"):
    val stub = HttpRoutes.of[IO] { case POST -> Root / "language" / "translate" / "v2" :? _ =>
      TooManyRequests("""{"error":{"code":429,"message":"rate limited"}}""")
    }.orNotFound
    val source = new GoogleTranslateSource(new GoogleTranslateClient(Client.fromHttpApp(stub), apiKey = "test-key"))
    source.translateBatch(kurmanji, List("OK")).map {
      case Left(_: MtError.Transient) => true
      case _ => false
    }.assertEquals(true)

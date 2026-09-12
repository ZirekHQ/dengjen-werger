package werger.adapters.crowdin

import cats.effect.IO
import io.circe.parser.decode
import munit.{CatsEffectSuite, FunSuite}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class CrowdinClientCodecSpec extends FunSuite:
  test("decodes a Crowdin source string response"):
    val json = """{"id": 661, "text": "OK", "identifier": "addon.OK"}"""
    val result = decode[CrowdinSourceString](json)
    assertEquals(result.map(_.text), Right("OK"))

class CrowdinClientSpec extends CatsEffectSuite:
  test("sourceStrings decodes Crowdin's paginated response shape"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "files" / "661" / "strings" =>
        Ok("""{"data":[{"data":{"id":661,"text":"OK","identifier":"addon.OK"}}]}""")
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
    crowdin.sourceStrings(fileId = 661L).map(_.map(_.text)).assertEquals(List("OK"))

  test("approvedTranslations decodes Crowdin's paginated response shape"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "languages" / "kmr" / "translations" :? _ =>
        Ok("""{"data":[{"data":{"id":42,"stringId":661,"text":"Temam"}}]}""")
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
    crowdin.approvedTranslations(languageId = "kmr", fileId = 661L).map(_.map(_.text)).assertEquals(List("Temam"))

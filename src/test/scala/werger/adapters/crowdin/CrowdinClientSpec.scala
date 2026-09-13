package werger.adapters.crowdin

import cats.effect.IO
import io.circe.parser.decode
import munit.{CatsEffectSuite, FunSuite}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

private object OffsetParam extends QueryParamDecoderMatcher[Long]("offset")

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

  test("sourceStrings follows Crowdin's pagination until a page comes back short"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "files" / "661" / "strings" :? OffsetParam(offset) =>
        val page = offset match
          case 0 =>
            """{"data":[{"data":{"id":1,"text":"One","identifier":"a"}},{"data":{"id":2,"text":"Two","identifier":"b"}}]}"""
          case 2 => """{"data":[{"data":{"id":3,"text":"Three","identifier":"c"}}]}"""
          case _ => """{"data":[]}"""
        Ok(page)
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L, pageSize = 2)
    crowdin.sourceStrings(fileId = 661L).map(_.map(_.text)).assertEquals(List("One", "Two", "Three"))

  test("approvedTranslations excludes a translation that has no matching approval"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "languages" / "kmr" / "translations" :? _ =>
        Ok("""{"data":[{"data":{"id":42,"stringId":661,"text":"Temam"}}]}""")
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "approvals" :? _ =>
        Ok("""{"data":[]}""")
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
    crowdin.approvedTranslations(languageId = "kmr", fileId = 661L).assertEquals(Nil)

  test("approvedTranslations keeps only translations that appear in Crowdin's approvals list"):
    val stub = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "languages" / "kmr" / "translations" :? _ =>
        Ok(
          """{"data":[{"data":{"id":42,"stringId":661,"text":"Temam"}},{"data":{"id":43,"stringId":662,"text":"Na"}}]}"""
        )
      case GET -> Root / "api" / "v2" / "projects" / "780748" / "approvals" :? _ =>
        Ok("""{"data":[{"data":{"translationId":42}}]}""")
    }.orNotFound
    val client = Client.fromHttpApp(stub)
    val crowdin = new CrowdinClient(client, token = "test-token", projectId = 780748L)
    crowdin.approvedTranslations(languageId = "kmr", fileId = 661L).map(_.map(_.text)).assertEquals(List("Temam"))

package werger.adapters.crowdin

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class CrowdinClientWriteSpec extends CatsEffectSuite:
  private def crowdinOver(routes: HttpRoutes[IO]): CrowdinClient =
    new CrowdinClient(Client.fromHttpApp(routes.orNotFound), token = "test-token", projectId = 780748L)

  test("createTranslation posts the string, language and text, and decodes the created id"):
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "v2" / "projects" / "780748" / "translations" =>
        req.as[Json].flatMap { body =>
          val cursor = body.hcursor
          val sent =
            for
              stringId <- cursor.get[Long]("stringId")
              languageId <- cursor.get[String]("languageId")
              text <- cursor.get[String]("text")
            yield (stringId, languageId, text)
          if sent == Right((661L, "kmr", "Temam")) then Created("""{"data":{"id":42}}""")
          else BadRequest(body.noSpaces)
        }
    }
    crowdinOver(routes).createTranslation("kmr", stringId = 661L, text = "Temam").map(_.id).assertEquals(42L)

  test("createTranslation sends the bearer token"):
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "v2" / "projects" / "780748" / "translations" =>
        val authorised = req.headers.get(headers.Authorization.name).exists(_.head.value == "Bearer test-token")
        if authorised then Created("""{"data":{"id":42}}""") else Forbidden()
    }
    crowdinOver(routes).createTranslation("kmr", 661L, "Temam").map(_.id).assertEquals(42L)

  test("createTranslation fails when Crowdin rejects the request"):
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "api" / "v2" / "projects" / "780748" / "translations" => BadRequest("""{"error":"nope"}""")
    }
    crowdinOver(routes).createTranslation("kmr", 661L, "Temam").intercept[org.http4s.client.UnexpectedStatus]

  test("approveTranslation posts the translation id to /approvals"):
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "v2" / "projects" / "780748" / "approvals" =>
        req.as[Json].flatMap { body =>
          if body.hcursor.get[Long]("translationId") == Right(42L) then Created("""{"data":{"id":7}}""")
          else BadRequest(body.noSpaces)
        }
    }
    crowdinOver(routes).approveTranslation(42L).assertEquals(())

  test("approveTranslation fails when Crowdin rejects the approval"):
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "api" / "v2" / "projects" / "780748" / "approvals" => NotFound()
    }
    crowdinOver(routes).approveTranslation(42L).intercept[org.http4s.client.UnexpectedStatus]

package werger.adapters.crowdin

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder

class CrowdinWriteE2eSpec extends CatsEffectSuite:
  private def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)

  private val config =
    for
      token <- env("CROWDIN_TEST_TOKEN")
      projectId <- env("CROWDIN_TEST_PROJECT_ID").flatMap(_.toLongOption)
      stringId <- env("CROWDIN_TEST_STRING_ID").flatMap(_.toLongOption)
    yield (token, projectId, stringId)

  config match
    case Some((token, projectId, stringId)) =>
      test("creating then approving a translation round-trips against real Crowdin"):
        EmberClientBuilder.default[IO].build.use { http =>
          val crowdin = new CrowdinClient(http, token, projectId)
          for
            created <- crowdin.createTranslation("kmr", stringId, "TEST - dengjen-werger integration check")
            _ <- crowdin.approveTranslation(created.id)
          yield ()
        }
    case None =>
      test("skipped - set CROWDIN_TEST_TOKEN, CROWDIN_TEST_PROJECT_ID and CROWDIN_TEST_STRING_ID to run"):
        IO.unit

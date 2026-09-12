package werger.adapters.crowdin

import io.circe.parser.decode
import munit.FunSuite

class CrowdinClientSpec extends FunSuite:
  test("decodes a Crowdin source string response"):
    val json = """{"id": 661, "text": "OK", "identifier": "addon.OK"}"""
    val result = decode[CrowdinSourceString](json)
    assertEquals(result.map(_.text), Right("OK"))

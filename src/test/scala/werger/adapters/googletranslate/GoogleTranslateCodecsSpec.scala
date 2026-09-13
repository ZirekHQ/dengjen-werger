package werger.adapters.googletranslate

import io.circe.parser.decode
import munit.FunSuite

class GoogleTranslateCodecsSpec extends FunSuite:
  test("decodes Google Translate's v2 response envelope"):
    val json = """{"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}"""
    val result = decode[GoogleTranslateEnvelope](json)
    assertEquals(result.map(_.data.translations.map(_.translatedText)), Right(List("Temam", "Na")))

package werger.adapters.googletranslate

import io.circe.Decoder
import io.circe.generic.semiauto.*

final case class GoogleTranslation(translatedText: String)
object GoogleTranslation:
  given Decoder[GoogleTranslation] = deriveDecoder

final case class GoogleTranslateData(translations: List[GoogleTranslation])
object GoogleTranslateData:
  given Decoder[GoogleTranslateData] = deriveDecoder

final case class GoogleTranslateEnvelope(data: GoogleTranslateData)
object GoogleTranslateEnvelope:
  given Decoder[GoogleTranslateEnvelope] = deriveDecoder

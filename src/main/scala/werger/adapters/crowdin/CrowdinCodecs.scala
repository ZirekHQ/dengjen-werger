package werger.adapters.crowdin

import io.circe.Decoder
import io.circe.generic.semiauto.*

final case class CrowdinSourceString(id: Long, text: String, identifier: String)
object CrowdinSourceString:
  given Decoder[CrowdinSourceString] = deriveDecoder

final case class CrowdinTranslation(id: Long, stringId: Long, text: String)
object CrowdinTranslation:
  given Decoder[CrowdinTranslation] = deriveDecoder

final case class CrowdinCreatedTranslation(id: Long)
object CrowdinCreatedTranslation:
  given Decoder[CrowdinCreatedTranslation] = deriveDecoder

final case class CrowdinApproval(translationId: Long)
object CrowdinApproval:
  given Decoder[CrowdinApproval] = deriveDecoder

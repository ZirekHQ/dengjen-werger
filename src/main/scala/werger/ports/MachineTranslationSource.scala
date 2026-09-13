package werger.ports

import cats.effect.IO
import werger.domain.Language

sealed trait MtError
object MtError:
  final case class Transient(message: String) extends MtError
  final case class Unsupported(message: String) extends MtError

/**
 * Adapts one hosted machine-translation provider. Never creates a `Submission` or moves a `WorkItemStatus` — callers
 * decide what to do with a translated batch.
 */
trait MachineTranslationSource:
  def translateBatch(language: Language, sourceTexts: List[String]): IO[Either[MtError, Map[String, String]]]

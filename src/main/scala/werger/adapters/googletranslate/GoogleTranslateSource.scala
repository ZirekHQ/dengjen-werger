package werger.adapters.googletranslate

import cats.effect.IO
import org.http4s.client.UnexpectedStatus
import werger.domain.Language
import werger.ports.{MachineTranslationSource, MtError}

object GoogleTranslateSource:
  // Google Translate has covered Kurdish under the single code "ku" since adding it in 2016; verify against
  // Google's current supported-languages list before depending on this in production.
  val kurmanjiOnly: Map[String, String] = Map("kmr" -> "ku")

class GoogleTranslateSource(
    client: GoogleTranslateClient,
    languageCodes: Map[String, String] = GoogleTranslateSource.kurmanjiOnly
) extends MachineTranslationSource:
  def translateBatch(language: Language, sourceTexts: List[String]): IO[Either[MtError, Map[String, String]]] =
    languageCodes.get(language.code) match
      case None =>
        IO.pure(Left(MtError.Unsupported(s"No Google Translate code mapped for language ${language.code}")))
      case Some(providerCode) =>
        client
          .translate(sourceTexts, providerCode)
          .map(Right(_))
          .recover {
            case UnexpectedStatus(status, _, _) if status.code == 400 =>
              Left(MtError.Unsupported(s"Google Translate rejected the request: $status"))
            case UnexpectedStatus(status, _, _) =>
              Left(MtError.Transient(s"Google Translate returned $status"))
            case e: Throwable =>
              Left(MtError.Transient(e.getMessage))
          }

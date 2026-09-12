package werger.adapters.crowdin

import cats.effect.IO
import io.circe.Decoder
import io.circe.generic.semiauto.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

private final case class Envelope[A](data: List[Wrapped[A]])
private object Envelope:
  given [A: Decoder]: Decoder[Envelope[A]] = deriveDecoder

private final case class Wrapped[A](data: A)
private object Wrapped:
  given [A: Decoder]: Decoder[Wrapped[A]] = deriveDecoder

/** Reads Crowdin's current translation state for one project/file: its
  * source strings and the translations already approved against them.
  */
class CrowdinClient(httpClient: Client[IO], token: String, projectId: Long):
  private val base = Uri.unsafeFromString("https://api.crowdin.com")
  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, token))

  def sourceStrings(fileId: Long): IO[List[CrowdinSourceString]] =
    val uri = base / "api" / "v2" / "projects" / projectId.toString / "files" / fileId.toString / "strings"
    httpClient.expect[Envelope[CrowdinSourceString]](Request[IO](Method.GET, uri).putHeaders(auth))
      .map(_.data.map(_.data))

  def approvedTranslations(languageId: String, fileId: Long): IO[List[CrowdinTranslation]] =
    val uri = (base / "api" / "v2" / "projects" / projectId.toString / "languages" / languageId / "translations")
      .withQueryParam("fileId", fileId.toString)
    httpClient.expect[Envelope[CrowdinTranslation]](Request[IO](Method.GET, uri).putHeaders(auth))
      .map(_.data.map(_.data))

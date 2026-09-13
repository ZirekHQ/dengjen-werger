package werger.adapters.crowdin

import cats.effect.IO
import cats.syntax.all.*
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

/**
 * Reads Crowdin's current translation state for one project/file: its source strings and the translations already
 * approved against them.
 */
class CrowdinClient(httpClient: Client[IO], token: String, projectId: Long, pageSize: Long = 500L):
  private val base = Uri.unsafeFromString("https://api.crowdin.com")
  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, token))

  /**
   * Follows Crowdin's limit/offset pagination until a page comes back shorter than `pageSize`, since Crowdin's list
   * endpoints cap a single response (default limit 25) well below what a real file's string count can reach.
   */
  private def paginate[A: Decoder](requestAt: Long => Request[IO]): IO[List[A]] =
    def go(offset: Long, acc: List[A]): IO[List[A]] =
      httpClient.expect[Envelope[A]](requestAt(offset)).flatMap { envelope =>
        val page = envelope.data.map(_.data)
        val soFar = acc ++ page
        if page.size < pageSize then IO.pure(soFar) else go(offset + pageSize, soFar)
      }
    go(0L, Nil)

  def sourceStrings(fileId: Long): IO[List[CrowdinSourceString]] =
    paginate[CrowdinSourceString] { offset =>
      val uri = (base / "api" / "v2" / "projects" / projectId.toString / "files" / fileId.toString / "strings")
        .withQueryParam("limit", pageSize.toString)
        .withQueryParam("offset", offset.toString)
      Request[IO](Method.GET, uri).putHeaders(auth)
    }

  def approvedTranslations(languageId: String, fileId: Long): IO[List[CrowdinTranslation]] =
    val translations = paginate[CrowdinTranslation] { offset =>
      val uri = (base / "api" / "v2" / "projects" / projectId.toString / "languages" / languageId / "translations")
        .withQueryParam("fileId", fileId.toString)
        .withQueryParam("limit", pageSize.toString)
        .withQueryParam("offset", offset.toString)
      Request[IO](Method.GET, uri).putHeaders(auth)
    }
    // /translations returns every submitted translation regardless of
    // approval state; Crowdin has no approval-status filter on that
    // endpoint, so the approved subset has to come from /approvals instead.
    val approvedIds = paginate[CrowdinApproval] { offset =>
      val uri = (base / "api" / "v2" / "projects" / projectId.toString / "approvals")
        .withQueryParam("fileId", fileId.toString)
        .withQueryParam("languageId", languageId)
        .withQueryParam("limit", pageSize.toString)
        .withQueryParam("offset", offset.toString)
      Request[IO](Method.GET, uri).putHeaders(auth)
    }.map(_.map(_.translationId).toSet)
    (translations, approvedIds).mapN((ts, ids) => ts.filter(t => ids.contains(t.id)))

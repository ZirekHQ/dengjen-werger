package werger.adapters.googletranslate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.typelevel.ci.CIString

/**
 * Raw calls to Google Cloud Translation's v2 Basic API. Chunks so one logical `translate` call never sends more than
 * `chunkSize` strings in a single request, and keys results by input text rather than position so one failed chunk
 * never discards another chunk's already-succeeded translations. Authenticates with the v2 Basic API's API-key scheme
 * (not v3's OAuth2/service-account) — the caller supplies the key, expected to be sourced from a
 * `GOOGLE_TRANSLATE_API_KEY` environment variable once deployment wiring reads one.
 */
class GoogleTranslateClient(httpClient: Client[IO], apiKey: String, chunkSize: Int = 100):
  private val endpoint = Uri.unsafeFromString("https://translation.googleapis.com/language/translate/v2")
  private val apiKeyHeader = Header.Raw(CIString("X-goog-api-key"), apiKey)

  def translate(texts: List[String], targetLanguageCode: String): IO[Map[String, String]] =
    texts.grouped(chunkSize).toList
      .traverse(chunk => translateChunk(chunk, targetLanguageCode).attempt.map(chunk -> _))
      .flatMap(collectResults)

  private def translateChunk(chunk: List[String], targetLanguageCode: String): IO[List[String]] =
    val body: Json = Json.obj("q" -> chunk.asJson, "target" -> targetLanguageCode.asJson, "format" -> "text".asJson)
    val request = Request[IO](Method.POST, endpoint).withEntity(body).putHeaders(apiKeyHeader)
    httpClient.expect[GoogleTranslateEnvelope](request).map(_.data.translations.map(_.translatedText)).flatMap {
      translated =>
        if translated.length == chunk.length then IO.pure(translated)
        else
          IO.raiseError(
            new RuntimeException(s"Google returned ${translated.length} translations for ${chunk.length} inputs")
          )
    }

  // A chunk that never got a response contributes nothing rather than failing the whole batch; only re-raise when
  // no chunk succeeded, since then there is nothing worth returning.
  private def collectResults(results: List[(List[String], Either[Throwable, List[String]])]): IO[Map[String, String]] =
    val succeeded = results.collect { case (chunk, Right(translated)) => chunk.zip(translated) }.flatten.toMap
    results.collectFirst { case (_, Left(e)) => e } match
      case Some(e) if succeeded.isEmpty => IO.raiseError(e)
      case _ => IO.pure(succeeded)

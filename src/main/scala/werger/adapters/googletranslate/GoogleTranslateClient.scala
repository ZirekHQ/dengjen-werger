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

import java.nio.charset.StandardCharsets

/**
 * Raw calls to Google Cloud Translation's v2 Basic API. Chunks so one logical `translate` call never sends more than
 * `chunkSize` strings or `maxRequestBytes` bytes of serialized request body in a single request (Google's Basic API
 * caps a request at 100,000 bytes — verify against Google's current docs before relying on this at production volume),
 * and keys results by input text rather than position so one failed chunk never discards another chunk's
 * already-succeeded translations. Authenticates with the v2 Basic API's API-key scheme (not v3's
 * OAuth2/service-account) — the caller supplies the key, expected to be sourced from a `GOOGLE_TRANSLATE_API_KEY`
 * environment variable once deployment wiring reads one.
 */
class GoogleTranslateClient(
    httpClient: Client[IO],
    apiKey: String,
    chunkSize: Int = 100,
    maxRequestBytes: Int = 100000
):
  private val endpoint = Uri.unsafeFromString("https://translation.googleapis.com/language/translate/v2")
  private val apiKeyHeader = Header.Raw(CIString("X-goog-api-key"), apiKey)

  def translate(texts: List[String], targetLanguageCode: String): IO[Map[String, String]] =
    val fitting = texts.filter(text => requestBodySize(List(text), targetLanguageCode) <= maxRequestBytes)
    chunkByLimits(fitting, targetLanguageCode)
      .traverse(chunk => translateChunk(chunk, targetLanguageCode).attempt.map(chunk -> _))
      .flatMap(collectResults)

  private def requestBody(chunk: List[String], targetLanguageCode: String): Json =
    Json.obj("q" -> chunk.asJson, "target" -> targetLanguageCode.asJson, "format" -> "text".asJson)

  private def requestBodySize(chunk: List[String], targetLanguageCode: String): Int =
    requestBody(chunk, targetLanguageCode).noSpaces.getBytes(StandardCharsets.UTF_8).length

  // A text whose own serialized request already exceeds maxRequestBytes can never fit any chunk; `translate` filters
  // these out before chunking so one oversized input doesn't affect every other text's batching.
  private def chunkByLimits(texts: List[String], targetLanguageCode: String): List[List[String]] =
    texts
      .foldLeft(List.empty[List[String]]) { (chunks, text) =>
        chunks match
          case current :: rest
              if current.size < chunkSize && requestBodySize(text :: current, targetLanguageCode) <= maxRequestBytes =>
            (text :: current) :: rest
          case _ => List(text) :: chunks
      }
      .map(_.reverse)
      .reverse

  private def translateChunk(chunk: List[String], targetLanguageCode: String): IO[List[String]] =
    val request = Request[IO](Method.POST, endpoint)
      .withEntity(requestBody(chunk, targetLanguageCode))
      .putHeaders(apiKeyHeader)
    httpClient.expect[GoogleTranslateEnvelope](request).map(_.data.translations.map(_.translatedText)).flatMap {
      translated =>
        if translated.length == chunk.length then IO.pure(translated)
        else
          IO.raiseError(
            new RuntimeException(s"Google returned ${translated.length} translations for ${chunk.length} inputs")
          )
    }

  // Deliberately not a plain `.traverse`: that would fail the whole batch, discarding earlier successes, on one bad chunk.
  private def collectResults(results: List[(List[String], Either[Throwable, List[String]])]): IO[Map[String, String]] =
    val succeeded = results.collect { case (chunk, Right(translated)) => chunk.zip(translated) }.flatten.toMap
    results.collectFirst { case (_, Left(e)) => e } match
      case Some(e) if succeeded.isEmpty => IO.raiseError(e)
      case _ => IO.pure(succeeded)

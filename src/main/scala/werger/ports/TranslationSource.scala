package werger.ports

import cats.effect.IO
import werger.domain.{Language, TmSegment, WorkItem}

sealed trait SourceError
object SourceError:
  final case class Transient(message: String) extends SourceError
  final case class Rejected(message: String) extends SourceError

/**
 * Adapts one external translation platform for both reading work and durable Translation Memory, and for pushing an
 * approved translation back.
 */
trait TranslationSource:
  def fetchWorkItems(language: Language): IO[List[WorkItem]]
  def fetchMemory(language: Language): IO[List[TmSegment]]
  def submit(item: WorkItem, translation: String): IO[Either[SourceError, Unit]]

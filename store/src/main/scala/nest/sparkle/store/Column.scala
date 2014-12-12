package nest.sparkle.store

import scala.concurrent.ExecutionContext
import scala.reflect.runtime.universe._
import rx.lang.scala.Observable

import nest.sparkle.core.OngoingData
import nest.sparkle.measure.Span

case class OngoingEvents[T, U](initial: Observable[Event[T, U]], ongoing: Observable[Event[T, U]])

/** a readable column of data that supports simple range queries.  */
trait Column[T, U]
{
  /** name of this column */
  def name: String

  def keyType: TypeTag[_]

  def valueType: TypeTag[_]

  /** read a slice of events from the column, inclusive of the start and ends.
    * If start is missing, read from the first element in the column.  If end is missing
    * read from the last element in the column.  */
  // format: OFF
  def readRange(
    start: Option[T] = None,
    end: Option[T] = None,
    limit: Option[Long] = None,
    parentSpan: Option[Span] = None
  )(implicit execution: ExecutionContext): OngoingEvents[T, U]
  // format: ON
  
  // format: OFF
  def readRangeA(
    start: Option[T] = None,
    end: Option[T] = None,
    limit: Option[Long] = None,
    parentSpan: Option[Span] = None
  )(implicit execution: ExecutionContext): OngoingData[T, U] // format: ON
  // LATER add authorization hook, to validate permission to read a range
}

// LATER consider making value more flexible:
// . a sequence?  values:Seq[V].  e.g. if we want to store:  [1:[123, 134], 2:[100]]
// . an hlist?  e.g. if we want to store a csv file with multiple separately typed columns per row
// . possibly a typeclass that covers all single, sequence, and typed list cases?
// TODO name this something else: Item?  Datum?
// TODO rename argument to key, for consistency with other key-value terminology
/** an single item in the datastore, e.g. a timestamp and value */
case class Event[T, V](argument: T, value: V)

object Event
{
  type Events[T, U] = Seq[Event[T, U]]
}

package nest.sparkle.datastream

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

import rx.lang.scala.Observable

import nest.sparkle.measure.Span
import nest.sparkle.util.{Log, PeriodWithZone, RecoverNumeric}

case class ReductionParameterError(msg:String) extends RuntimeException(msg)

// TODO specialize for efficiency
trait AsyncReduction[K,V] extends Log {
  self: AsyncWithRange[K,V] =>

  val defaultBufferOngoing = 5.seconds

  implicit def _keyType = keyType
  implicit def _valueType = valueType
  /** Reduce a stream piecewise, based a partitioning and a reduction function.
    * The main work of reduction is done on each DataStream, this classes' job is
    * to select the appropriate stream reductions, and manage the initial/ongoing
    * parts of this TwoPartStream.
    */
  def flexibleReduce // format: OFF
      ( optPeriod: Option[PeriodWithZone],
        optCount: Option[Int],
        reduction: Reduction[V],
        maxParts: Int,
        ongoingDuration: Option[FiniteDuration] )
      ( implicit execution: ExecutionContext, parentSpan:Span )
      : TwoPartStream[K, Option[V], AsyncWithRange] = { // format: ON

    // depending on the request parameters, summarize the stream appropriately
    (optCount, optPeriod, self.requestRange) match {
      case (None, Some(periodWithZone), _) =>
        reduceByPeriod(periodWithZone, reduction, ongoingDuration, maxParts = maxParts)
      case (None, None, Some(rangeInterval)) =>
        reduceToOnePart(reduction, rangeInterval.start, optBufferOngoing = ongoingDuration)
      case (None, None, None) =>
        reduceToOnePart(reduction, optBufferOngoing = ongoingDuration)
      case (Some(count), None, _) =>
        reduceByCount(count, reduction, ongoingDuration, maxParts = maxParts)
      case (Some(_), Some(_), _) =>
        val err = ReductionParameterError("both count and period specified")
        AsyncWithRange.error(err, self.requestRange)
    }
  }

  /** Partition the key range by period, starting with the rounded time of the first key
    * in the stream. Return a reduced stream, with the values in each partition
    * reduced by a provided function. The keys in the reduced stream are set to
    * the start of each time partition.
    *
    * The ongoing portion of the stream is reduced to periods periodically
    * (every 5 seconds by default).
    */
  private def reduceByPeriod // format: OFF
      ( periodWithZone: PeriodWithZone,
        reduction: Reduction[V],
        optBufferOngoing: Option[FiniteDuration] = None,
        maxParts: Int )
      ( implicit executionContext:ExecutionContext, parentSpan:Span )
      : TwoPartStream[K, Option[V], AsyncWithRange] = { // format: ON

    val bufferOngoing = optBufferOngoing getOrElse defaultBufferOngoing

    RecoverNumeric.tryNumeric[K](keyType) match {
      case Success(numericKey) =>
        implicit val _ = numericKey
        val range = requestRange.getOrElse(SoftInterval(None, None))
        val initialResult = self.initial.reduceByPeriod(periodWithZone, range, reduction,
          maxParts, optPrevious = None)
        val prevStateFuture = initialResult.finishState
        val reducedOngoing =
          self.ongoing.tumblingReduce(bufferOngoing, prevStateFuture) { (buffer, optState) =>
            buffer.reduceByPeriod(periodWithZone, range, reduction, maxParts, optState)
          }
        AsyncWithRange(initialResult.reducedStream, reducedOngoing, self.requestRange)
      case Failure(err) => AsyncWithRange.error(err, self.requestRange)
    }
  }

  /** reduce the initial part of the stream to a single value, and reduce the ongoing
    * stream to a single value every 5 seconds.
    */
  private def reduceToOnePart // format: OFF
        ( reduction: Reduction[V], reduceKey: Option[K] = None,
          optBufferOngoing: Option[FiniteDuration] = None )
        ( implicit executionContext:ExecutionContext, parentSpan: Span)
        : AsyncWithRange[K, Option[V]] = { // format: ON

    val bufferOngoing = optBufferOngoing getOrElse defaultBufferOngoing

    val initialReduced = initial.reduceToOnePart(reduction, reduceKey)

    val ongoingReduced =
      ongoing.tumblingReduce(bufferOngoing) { (buffer, optState:Option[_]) =>
        val reducedStream = buffer.reduceToOnePart(reduction, None)
        ReductionResult.simple(reducedStream)
      }

    AsyncWithRange(initialReduced, ongoingReduced, self.requestRange)
  }

  private def reduceByCount
      ( count:Int,
        reduction: Reduction[V],
        optBufferOngoing: Option[FiniteDuration] = None,
        maxParts: Int )
      ( implicit executionContext:ExecutionContext, parentSpan: Span )
      : TwoPartStream[K, Option[V], AsyncWithRange] = { // format: ON

    val bufferOngoing = optBufferOngoing getOrElse defaultBufferOngoing

    def toOptionValues(stream:DataStream[K,V]):DataStream[K, Option[V]] = {
      val optioned:Observable[DataArray[K, Option[V]]] =
        stream.data.map { data =>
          val someValues = data.values.map(Some(_):Option[V])
          DataArray(data.keys, someValues)
        }
      DataStream(optioned)
    }

    val initialReduced = initial.reduceToParts(count, reduction)
    val initialOptions = toOptionValues(initialReduced)

    val ongoingReduced =
      ongoing.tumblingReduce(bufferOngoing) { (buffer, optState:Option[_]) =>
//        buffer.reduceToOnePart(reduction)
        ???
      }

    AsyncWithRange(initialOptions, ongoingReduced, self.requestRange)
  }
}

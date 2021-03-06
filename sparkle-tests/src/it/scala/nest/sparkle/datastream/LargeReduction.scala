package nest.sparkle.datastream

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import com.typesafe.config.ConfigFactory

import org.joda.time.DateTimeZone
import rx.lang.scala.Observable

import nest.sparkle.measure.{TraceId, DummySpan, Span, ConfiguredMeasurements}
import nest.sparkle.store.WriteableStore
import nest.sparkle.time.protocol.{DataServiceFixture, StreamRequestor, TestDataService}
import nest.sparkle.time.server.DataService
import nest.sparkle.util.StringToMillis._
import nest.sparkle.util.{Period, PeriodWithZone}
import nest.sparkle.util.FutureAwait.Implicits._
import nest.sparkle.store.cassandra.serializers._

/** Utilities for doing reduction tests.
  */
object LargeReduction extends StreamRequestor {

  /** generate a years worth of test data and reduce it into time periods.
    * The amount of data is controlled by adjusting the spacing between samples
    * (e.g. using spacing of 1.day tests with 365 samples,
    *  spacing of 1 second tests 365*24*60*60=31M samples.)
    * @param spacing the time between generated samples
    * @param summaryPeriod summarize samples into this size buckets (e.g. "1 day") */
  def byPeriod(spacing:FiniteDuration, summaryPeriod:String)(implicit parentSpan:Span)
      : DataArray[Long, Option[Long]] = {
    val stream = generateDataStream(spacing)
    val periodWithZone = PeriodWithZone(Period.parse(summaryPeriod).get, DateTimeZone.UTC)
    val reduced = stream.reduceByPeriod(periodWithZone, SoftInterval.empty, ReduceSum[Long](), true)
    val arrays = reduced.reducedStream.data.toBlocking.toList
    Span("reduce").time {
      arrays.reduce(_ ++ _)
    }
  }

  def preloadStore
      ( spacing:FiniteDuration, columnPath:String, service:DataServiceFixture )
      ( implicit parentSpan:Span, executionContext: ExecutionContext )
      : Unit = {
    val stream = generateDataStream(spacing)
    Span("writeGeneratedData").time {
      service.readWriteStore.writeStream(stream, columnPath).await(30.seconds)
    }
  }

  def byPeriodLocalProtocol
      ( summaryPeriod:String, columnPath:String, service:DataServiceFixture )
      ( implicit parentSpan:Span ): Future[Unit] = {

    implicit val execution = service.executionContext

    val traceIdOverride:Option[TraceId] = {
      if (parentSpan == DummySpan) Some(Span.ignoreTraceId) else None
    }
    val responseFuture = {
      val message = stringRequest(columnPath, "reduceSum",
        s"""{ "partBySize" : "$summaryPeriod",
         |  "timeZoneId" : "UTC" }""".stripMargin, traceIdOverride)
      Span("requestResponse").time {
        service.sendDataMessage(message)
      }
    }

    responseFuture.map { response =>
      TestDataService.longLongData(response) // LATER validate data accuracy
    }
  }

  /** generate a years worth of test data and reduce it into a single value.
    * The amount of data is controlled by adjusting the spacing between samples
    * (e.g. using spacing of 1.day tests with 365 samples,
    *  spacing of 1 second tests 365*24*60*60=31M samples.)
    * @param spacing the time between generated samples */
  def toOnePart(spacing:FiniteDuration)(implicit parentSpan:Span)
      : DataArray[Long, Option[Long]] = {
    val stream = generateDataStream(spacing)
    val reduced = stream.reduceToOnePart(ReduceSum[Long]())
    val arrays = reduced.data.toBlocking.toList
    Span("reduce").time {
      arrays.reduce(_ ++ _)
    }
  }

  /** Generate a DataStream of test data */
  def generateDataStream
      ( spacing:FiniteDuration = 30.seconds,
        blockSize: Int = 5000,
        start:String = "2013-01-01T00:00:00.000",
        until:String = "2014-01-01T00:00:00.000")
      ( implicit parentSpan:Span)
      : DataStream[Long, Long] = {
    var startMillis = start.toMillis
    val untilMillis = until.toMillis

    val iter = new Iterator[DataArray[Long,Long]] {
      /** return a data array covering the next time period. Keys are
       *  spaced according to spacing above, and every value is 2 (long). */
      override def next():DataArray[Long,Long] = {
        Span("generateTestData").time {
          val keys =
            (0 until blockSize).iterator
              .map(_ => startMillis)
              .takeWhile {time =>
                startMillis = time + spacing.toMillis
                time < untilMillis
              }
              .toArray

          val values = (0 until keys.length).map { _ => 2L }.toArray
          val result = DataArray(keys, values)
          result
        }
      }
      
      override def hasNext:Boolean = startMillis < untilMillis
    }
    
    val data = Observable.from(iter.toIterable)
    DataStream(data)
  }


}


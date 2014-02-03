/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.time.protocol

import scala.concurrent.ExecutionContext
import org.scalatest.{ BeforeAndAfterAll, FunSuite, Matchers }
import org.scalatest.prop.PropertyChecks
import spray.util._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest
import nest.sparkle.store.Event
import nest.sparkle.store.Storage
import nest.sparkle.time.protocol.RequestJson.{ StreamRequestMessageFormat }
import nest.sparkle.time.protocol.ResponseJson.{ StreamFormat, StreamsFormat, StreamsMessageFormat }
import nest.sparkle.time.protocol.EventJson.EventFormat
import nest.sparkle.store.ram.WriteableRamStore
import scala.reflect.runtime.universe._
import spray.http.HttpResponse
import nest.sparkle.time.transform.DomainRangeLimits
import nest.sparkle.time.transform.DomainRangeJson.DomainRangeReader
import nest.sparkle.time.transform.MinMax
import nest.sparkle.time.protocol.ArbitraryColumn.arbitraryColumn
import nest.sparkle.time.protocol.TestDomainRange.minMaxEvents
import nest.sparkle.util.RandomUtil.randomAlphaNum
import nest.sparkle.time.protocol.ArbitraryColumn.arbitraryEvent
import nest.sparkle.time.transform.DomainRangeJson

class TestV1Api extends TestStore with StreamRequestor with TestDataService {

  /** return the data array portion from a Streams response */
  def streamData(response: HttpResponse): Seq[JsArray] = {
    val streams = responseAs[StreamsMessage]
    streams.message.streams.length shouldBe 1
    val stream = streams.message.streams(0)
    stream.data.isDefined shouldBe true
    val data = stream.data.get
    data
  }

  /** return the data array portion from a Streams response as a stream of Event objects */
  def streamDataEvents(response: HttpResponse): Seq[Event[Long, Double]] = {
    val data = streamData(response)
    data.map { datum =>
      datum.convertTo[Event[Long, Double]]
    }
  }

  test("summarizeMax two raw events") { // note that this test just copies input to output
    val requestMessage = streamRequest("SummarizeMax")
    Post("/v1/data", requestMessage) ~> v1protocol ~> check {
      val events = streamDataEvents(response)
      events.length shouldBe 2
      events(0).value shouldBe 1
      events(1).value shouldBe 2
    }
  }

  /** create a new column in the test RAM store and return its columnPath */
  def makeColumn[T: TypeTag: Ordering, U: TypeTag](prefix: String, events: List[Event[T, U]]): String = {
    val columnName = prefix + "/" + randomAlphaNum(4)
    val column = store.writeableColumn[T, U](columnName).await
    column.write(events)
    columnName
  }

  test("DomainRange calculates domain and range on arbitray long,double columns") {
    forAll { events: List[Event[Long, Double]] =>
      val columnName = makeColumn("V1Protocol.DomainRange", events)
      val requestMessage = streamRequest("DomainRange", columnName)
      Post("/v1/data", requestMessage) ~> v1protocol ~> check {
        val data = streamData(response)
        data.length shouldBe 1
        data.foreach { datum =>
          if (events.length > 0) {
            val limits = datum.convertTo[DomainRangeLimits[Long, Double]]
            val (domainMin, domainMax, rangeMin, rangeMax) = minMaxEvents(events)
            limits shouldBe DomainRangeLimits(domain = MinMax(domainMin, domainMax), range = MinMax(rangeMin, rangeMax))
          } else {
            datum shouldBe DomainRangeJson.EmptyJson
          }
        }
      }
    }
  }

}

package nest.sparkle.datastream

import scala.concurrent.duration._
import nest.sparkle.util.StringToMillis._
import org.scalatest.{FunSuite, Matchers}
import nest.sparkle.datastream.LargeReduction.{byPeriod, toOnePart}
import nest.sparkle.util.TimeValueString.implicits._

/**
  */
class TestLargeReduction extends FunSuite with Matchers {
  
  test("byPeriod") {
    val reduced = byPeriod(1.hour, "1 day")
    val first = reduced(10)
    val second = reduced(1)
    val last = reduced.last
    println(s"first: ${first.timeValueString}  second: ${second.timeValueString}  last: ${last.timeValueString}")
    reduced.length shouldBe 365
    reduced.foreach { case (key, value) => value shouldBe Some(48)}
  }
  
  test("toOnePart") {
    val reduced = toOnePart(1.day)
    reduced.length shouldBe 1
    reduced.head shouldBe ("2013-01-01T00:00:00.000".toMillis, Some(730))
  }
}


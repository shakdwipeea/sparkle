package nest.sparkle.time.protocol

import org.scalatest.{FunSuite, Matchers}
import nest.sparkle.store.cassandra.CassandraTestConfig
import nest.sparkle.store.Store

class TestIntervalSum extends FunSuite with Matchers with CassandraTestConfig 
    with StreamRequestor {
  
}

class IntervalSumTester(override val store:Store) extends FunSuite with TestDataService {
  
} 

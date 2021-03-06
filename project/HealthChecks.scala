import scala.util.Try
import scala.concurrent.duration._

import sbt.Def
import sbt.Keys.streams

import com.datastax.driver.core.Cluster

import kafka.api.TopicMetadataRequest
import kafka.consumer.SimpleConsumer
import kafka.utils.ZkUtils

import org.I0Itec.zkclient.ZkClient

/** Status check functions to verify that various servers are up and running */
object HealthChecks {

  /** return a function that will check whether the cassandra server is alive */
  val cassandraIsHealthy = Def.task[() => Try[Boolean]] { () =>
    Try {
      val log = streams.value.log

      val builder = Cluster.builder()
      builder.addContactPoint("localhost")
      val cluster = builder.build()
      try {
        val session = cluster.connect()
        session.close()
      } finally {
        cluster.close()
      }

      true
    }
  }

  /** return a function that will check whether the zookeeper service is alive */
  val zookeeperIsHealthy = Def.task[() => Try[Boolean]] { () =>
    Try {
      val log = streams.value.log
      
      val zkClient = new ZkClient("127.0.0.1:2181", 30000, 30000)
      try {
        // This gets Kafka topics if any exist.
        val topics = ZkUtils.getAllTopics(zkClient)
        log.debug(s"${topics.length} Kafka topics found in zookeeper")
      } finally {
        zkClient.close()
      }
      
      // Forcing a 10s sleep makes Kafka health check work...most of the time.
      // This is a work-around for KAFKA-1451 which will be fixed in Kafka 0.8.2.
      // See https://issues.apache.org/jira/browse/KAFKA-1451 for details
      Thread.sleep(10000)
      
      true
    }
  }

  /** return a function that will check whether a local kafka server is alive */
  val kafkaIsHealthy = Def.task[() => Try[Boolean]] { () =>
    Try {
      val consumer = new SimpleConsumer(
        host = "127.0.0.1",
        port = 9092,
        soTimeout = 2.seconds.toMillis.toInt,
        bufferSize = 1024,
        clientId = "sbt-health-check")

      // this will fail if Kafka is unavailable
      consumer.send(new TopicMetadataRequest(Nil, 1))

      true
    }
  }
}

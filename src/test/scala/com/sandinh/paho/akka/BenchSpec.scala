package com.sandinh.paho.akka

import java.nio.ByteBuffer

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Random

class BenchSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers
    with BeforeAndAfterAll with ScalaFutures {
  import system.dispatcher

  def this() = this(ActorSystem("BenchSpec"))

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "MqttPubSub" must {
    "bench ok" in {
      val count = 10000
      val qos = 0
      val topic = "paho-akka/BenchSpec" + Random.nextLong()

      val subs = system.actorOf(Props(classOf[SubsActor], testActor, topic, qos))
      subs ! Run
      val ack = expectMsgType[SubscribeAck](10.seconds)
      ack.fail shouldBe None

      val pub = system.actorOf(Props(classOf[PubActor], count, topic, qos))
      pub ! Run

      var receivedCount = 0
      def notDone = receivedCount < count

      implicit val askTimeout = akka.util.Timeout(1, SECONDS)
      for (delay <- 1 to 50 if notDone) {
        receivedCount = akka.pattern.after(1.seconds, system.scheduler)(subs ? SubsActorReport).mapTo[Int].futureValue
        println(s"$delay: Pub $count Rec $receivedCount ~ ${receivedCount * 100.0 / count}%")
      }

      assert(!notDone)
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(60, Seconds), Span(1, Second))
}

private case object Run

private trait Common { this: Actor =>
  val pubsub = context.actorOf(Props(
    classOf[MqttPubSub], PSConfig("tcp://test.mosquitto.org:1883", stashCapacity = 10000)
  ))
}

private class PubActor(count: Int, topic: String, qos: Int) extends Actor with Common {
  def receive = {
    case Run =>
      var i = 0
      while (i < count) {
        val payload = ByteBuffer.allocate(4).putInt(i).array()
        pubsub ! new Publish(topic, payload, qos)
        i += 1
      }
  }
}

private case object SubsActorReport

private class SubsActor(reportTo: ActorRef, topic: String, qos: Int) extends Actor with Common {
  def receive = {
    case Run => pubsub ! Subscribe(topic, self, qos)
    case msg @ SubscribeAck(Subscribe(`topic`, `self`, `qos`), _) =>
      context become ready
      reportTo ! msg
  }

  private[this] var receivedCount = 0
  def ready: Receive = {
    case msg: Message    => receivedCount += 1
    case SubsActorReport => sender() ! receivedCount
  }
}

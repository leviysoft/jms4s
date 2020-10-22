package jms4s.basespec.providers

import cats.data.NonEmptyList
import cats.effect.{ IO, Resource }
import jms4s.JmsClient
import jms4s.activemq.activeMQ
import jms4s.activemq.activeMQ._
import jms4s.basespec.Jms4sBaseSpec

import scala.util.Random

trait ActiveMQArtemisBaseSpec extends Jms4sBaseSpec {

  override val jmsClientRes: Resource[IO, JmsClient[IO]] =
    for {
      rnd <- Resource.liftF(IO(Random.nextInt))
      client <- activeMQ.makeJmsClient[IO](
                 Config(
                   endpoints = NonEmptyList.one(Endpoint("localhost", 61616)),
                   username = Some(Username("admin")),
                   password = Some(Password("passw0rd")),
                   clientId = ClientId("jms-specs" + rnd)
                 )
               )
    } yield client
}

package jms4s.basespec.providers

import cats.data.NonEmptyList
import cats.effect.{ Blocker, ContextShift, IO, Resource }
import eu.timepit.refined.auto._
import jms4s.basespec.Jms4sBaseSpec
import jms4s.ibmmq.configuration._
import jms4s.ibmmq.ibmMQ
import jms4s.jms.JmsContext

trait IbmMQBaseSpec extends Jms4sBaseSpec {

  override def contextRes(implicit cs: ContextShift[IO]): Resource[IO, JmsContext[IO]] =
    Blocker
      .apply[IO]
      .flatMap(blocker =>
        ibmMQ.makeContext[IO](
          Configuration(
            qm = QueueManager("QM1"),
            endpoints = NonEmptyList.one(Endpoint(Hostname("localhost"), Port(1414))),
            // the current docker image seems to be misconfigured, so I need to use admin channel/auth in order to test topic
            // but maybe it's just me not understanding something properly.. as usual
            //          channel = Channel("DEV.APP.SVRCONN"),
            //          username = Some(Username("app")),
            //          password = None,
            channel = Channel("DEV.ADMIN.SVRCONN"),
            credential = Some(Credential(Username("admin"), Password("passw0rd"))),
            clientId = ClientId("jms-specs")
          ),
          blocker
        )
      )
}

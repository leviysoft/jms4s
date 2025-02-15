/*
 * Copyright (c) 2020 Functional Programming in Bologna
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package jms4s.jms

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{ Clock, IO, Resource }
import jms4s.basespec.Jms4sBaseSpec
import jms4s.config.DestinationName
import jms4s.model.SessionType
import org.scalatest.freespec.AsyncFreeSpec

import scala.concurrent.duration._

trait JmsSpec extends AsyncFreeSpec with AsyncIOSpec with Jms4sBaseSpec {

  private def contexts(destination: DestinationName) =
    for {
      client          <- jmsClientRes
      context         = client.context
      receiveConsumer <- context.createContext(SessionType.AutoAcknowledge).flatMap(_.createJmsConsumer(destination))
      sendContext     <- context.createContext(SessionType.AutoAcknowledge)
      msg             <- Resource.eval(context.createTextMessage(body))
    } yield (receiveConsumer, sendContext, msg)

  "publish to a queue and then receive" in {
    contexts(inputQueueName).use {
      case (receiveConsumer, sendContext, msg) =>
        for {
          _    <- sendContext.send(inputQueueName, msg)
          text <- receiveBodyAsTextOrFail(receiveConsumer)
        } yield assert(text == body)
    }
  }
  "publish and then receive with a delay" in {
    contexts(inputQueueName).use {
      case (consumer, sendContext, msg) =>
        for {
          producerTimestamp <- Clock[IO].realTime
          _                 <- sendContext.send(inputQueueName, msg, delay)
          msg               <- consumer.receiveJmsMessage
          deliveryTime      <- Clock[IO].realTime
          actualBody        <- msg.asTextF[IO]
          actualDelay       = (deliveryTime - producerTimestamp)
        } yield assert(actualDelay >= delayWithTolerance && actualBody == body)
    }
  }
  "publish to a topic and then receive" in {
    contexts(topicName1).use {
      case (consumer, sendContext, msg) =>
        for {
          _   <- (IO.delay(10.millis) >> sendContext.send(topicName1, msg)).start
          rec <- receiveBodyAsTextOrFail(consumer)
        } yield assert(rec == body)
    }
  }
}

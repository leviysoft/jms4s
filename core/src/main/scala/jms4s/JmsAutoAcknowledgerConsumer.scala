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

package jms4s

import cats.data.NonEmptyList
import cats.effect.std.Queue
import cats.effect.{ Async, Resource, Sync }
import cats.syntax.all._
import fs2.Stream
import jms4s.JmsAutoAcknowledgerConsumer.AutoAckAction
import jms4s.JmsAutoAcknowledgerConsumer.AutoAckAction.Send
import jms4s.config.DestinationName
import jms4s.jms._
import jms4s.model.SessionType

import scala.concurrent.duration.FiniteDuration

trait JmsAutoAcknowledgerConsumer[F[_]] {
  def handle(f: (JmsMessage, MessageFactory[F]) => F[AutoAckAction[F]]): F[Unit]
}

object JmsAutoAcknowledgerConsumer {

  private[jms4s] def make[F[_]: Async](
    context: JmsContext[F],
    inputDestinationName: DestinationName,
    concurrencyLevel: Int
  ): Resource[F, JmsAutoAcknowledgerConsumer[F]] =
    for {
      pool <- Resource.eval(
               Queue.bounded[F, (JmsContext[F], JmsMessageConsumer[F], MessageFactory[F])](concurrencyLevel)
             )
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              ctx      <- context.createContext(SessionType.AutoAcknowledge)
              consumer <- ctx.createJmsConsumer(inputDestinationName)
              _        <- Resource.eval(pool.offer((ctx, consumer, MessageFactory[F](ctx))))
            } yield ()
          }
    } yield build(pool, concurrencyLevel)

  private def build[F[_]: Async](
    pool: Queue[F, (JmsContext[F], JmsMessageConsumer[F], MessageFactory[F])],
    concurrencyLevel: Int
  ): JmsAutoAcknowledgerConsumer[F] =
    (f: (JmsMessage, MessageFactory[F]) => F[AutoAckAction[F]]) =>
      Stream
        .emits(0 until concurrencyLevel)
        .as(
          Stream.eval(
            for {
              (context, consumer, mf) <- pool.take
              message                 <- consumer.receiveJmsMessage
              res: AutoAckAction[F]   <- f(message, mf)
              _ <- res.fold(
                    ifNoOp = Sync[F].unit,
                    ifSend = (send: Send[F]) =>
                      send.messages.messagesAndDestinations.traverse_ {
                        case (message, (name, delay)) =>
                          delay.fold(
                            ifEmpty = context.send(name, message)
                          )(
                            f = d => context.send(name, message, d)
                          )
                      }
                  )
              _ <- pool.offer((context, consumer, mf))
            } yield ()
          )
        )
        .parJoin(concurrencyLevel)
        .repeat
        .compile
        .drain

  sealed abstract class AutoAckAction[F[_]] extends Product with Serializable {
    def fold(ifNoOp: => F[Unit], ifSend: AutoAckAction.Send[F] => F[Unit]): F[Unit]
  }

  object AutoAckAction {

    private[jms4s] case class NoOp[F[_]]() extends AutoAckAction[F] {
      override def fold(ifNoOp: => F[Unit], ifSend: Send[F] => F[Unit]): F[Unit] = ifNoOp
    }

    case class Send[F[_]](messages: ToSend[F]) extends AutoAckAction[F] {

      override def fold(ifNoOp: => F[Unit], ifSend: Send[F] => F[Unit]): F[Unit] =
        ifSend(this)
    }

    private[jms4s] case class ToSend[F[_]](
      messagesAndDestinations: NonEmptyList[(JmsMessage, (DestinationName, Option[FiniteDuration]))]
    )

    def noOp[F[_]]: NoOp[F] = NoOp[F]()

    def sendN[F[_]](
      messages: NonEmptyList[(JmsMessage, DestinationName)]
    ): Send[F] =
      Send[F](ToSend[F](messages.map { case (message, name) => (message, (name, None)) }))

    def sendNWithDelay[F[_]](
      messages: NonEmptyList[(JmsMessage, (DestinationName, Option[FiniteDuration]))]
    ): Send[F] =
      Send[F](ToSend[F](messages.map { case (message, (name, delay)) => (message, (name, delay)) }))

    def sendWithDelay[F[_]](
      message: JmsMessage,
      destination: DestinationName,
      duration: Option[FiniteDuration]
    ): Send[F] =
      Send[F](ToSend[F](NonEmptyList.one((message, (destination, duration)))))

    def send[F[_]](message: JmsMessage, destination: DestinationName): Send[F] =
      Send[F](ToSend[F](NonEmptyList.one((message, (destination, None)))))
  }
}

/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.tail.internal

import cats.syntax.all._
import cats.effect.Sync
import scala.util.control.NonFatal
import monix.tail.Iterant
import monix.tail.Iterant.{Halt, Last, Next, NextBatch, NextCursor, Suspend}

import scala.runtime.ObjectRef

private[tail] object IterantSkipSuspend {
  /**
    * Implementation for `Iterant#skipSuspendL`
    */
  def apply[F[_], A](source: Iterant[F, A])(implicit F: Sync[F]): F[Iterant[F, A]] = {
    def loop(stopRef: ObjectRef[F[Unit]])(source: Iterant[F, A]): F[Iterant[F, A]] =
      try source match {
        case Next(_, _, _) =>
          F.pure(source)
        case NextCursor(cursor, rest, stop) =>
          stopRef.elem = stop
          if (cursor.hasNext()) F.pure(source)
          else rest.flatMap(loop(stopRef))
        case NextBatch(batch, rest, stop) =>
          stopRef.elem = stop
          val cursor = batch.cursor()
          loop(stopRef)(NextCursor(cursor, rest, stop))
        case Suspend(rest, stop) =>
          stopRef.elem = stop
          rest.flatMap(loop(stopRef))
        case other @ (Halt(_) | Last(_)) =>
          F.pure(other)
      } catch {
        case ex if NonFatal(ex) =>
          stopRef.elem.map(_ => Halt(Some(ex)))
      }

    F.suspend {
      val stopRef = ObjectRef.create(null.asInstanceOf[F[Unit]])
      loop(stopRef)(source).handleErrorWith { ex =>
        stopRef.elem match {
          case null => F.pure(Halt(Some(ex)))
          case stop => stop *> F.pure(Halt(Some(ex)))
        }
      }
    }
  }
}

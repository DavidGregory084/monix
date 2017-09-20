package monix.stm

import monix.execution.atomic._

object Global {
  val versionClock = AtomicLong(1L)
}

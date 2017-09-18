package monix.stm

import monix.eval.MVar

object Global {
  val versionClock = MVar(1)
}

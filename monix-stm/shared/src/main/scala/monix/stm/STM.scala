package monix.stm

import monix.eval.{ MVar, Task }
import monix.execution.atomic._

/* An implementation of the TL2 software transactional memory algorithm
 *
 * See: [[http://people.csail.mit.edu/shanir/publications/Transactional_Locking.pdf Transactional Locking II]]
 * Also: [[https://www.researchgate.net/publication/220888858_An_Implementation_of_Composable_Memory_Transactions_in_Haskell An Implementation of Composable Memory Transactions In Haskell]]
 */

case class TVar[A](
  lock: AtomicInt,
  id: Int,
  writeStamp: AtomicInt,
  content: Atomic[A],
  waitQueue: AtomicAny[List[MVar[Unit]]]
)

object TVar {
  val idRef = AtomicInt(0)

  def apply[A, R <: Atomic[A]](a: A)(implicit builder: AtomicBuilder[A, R]): Task[TVar[A]] =
    Task.eval {
      val lock = AtomicInt(1)
      TVar(lock, idRef.incrementAndGet(), AtomicInt(lock.get), Atomic(a), AtomicAny(List.empty))
    }
}

case class TState(
  id: Int,
  readStamp: Int,
  readSet: STM.ReadSet,
  writeSet: WriteSet
) {
  def copy: Task[TState] = writeSet.copy.map { nws =>
    val newReadSet = AtomicAny(readSet.get.toList.toSet)
    TState(id, readStamp, newReadSet, nws)
  }

  def merge(that: TState) = Task.eval {
    val rs1 = this.readSet.get
    val rs2 = that.readSet.get
    val nrs = rs1.union(rs2)
    this.readSet.set(nrs)
    this
  }
}

sealed abstract class TResult[A]
case class Valid[A](state: TState, result: A) extends TResult[A]
case class Retry[A](state: TState) extends TResult[A]
case class Invalid[A](state: TState) extends TResult[A]

case class RSEntry(id: Int, lock: AtomicInt, writeStamp: AtomicInt, waitQueue: AtomicAny[MVar[Unit]])

case class WriteSet(ws: AtomicAny[Map[Int, AnyRef]]) {
  def copy: Task[WriteSet] = Task.eval { WriteSet(AtomicAny(ws.get.toList.toMap)) }
}
case class WSEntry[A](lock: AtomicInt, writeStamp: AtomicInt, content: Atomic[A], newValue: A, waitQueue: AtomicAny[MVar[Unit]])

class STM[A](val f: TState => Task[TResult[A]]) {
  def orElse(that: STM[A]) = new STM[A](st => {
    // Copy the state for possible later use
    st.copy.flatMap { cpy =>
      // Run the first action with the state
      this.f(st).flatMap {
        case Retry(ns1) =>
          // If we need to retry, run the second action
          // with the copy of the original state
          that.f(cpy).flatMap {
            // If we still need to retry merge the states
            // and return a merged Retry
            case Retry(ns2) => ns2.merge(ns1).map(Retry.apply)
            // If we found a valid result merge the first
            // action's state with the latest and return
            // a merged Valid
            case Valid(ns2, r) => ns2.merge(ns1).map(s => Valid(s, r))
            // In all other cases just return the result right away
            case other => Task.now(other)
          }
        case other => Task.now(other)
      }
    }
  })
}

object STM {
  type ReadSet = AtomicAny[Set[RSEntry]]
  def valid[A](a: A) = new STM[A](state => Task.now(Valid(state, a)))
  def retry[A]: STM[A] = new STM[A](state => Task.now(Retry(state)))
}

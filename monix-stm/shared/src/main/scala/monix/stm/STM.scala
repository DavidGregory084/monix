package monix.stm

import monix.eval.{ MVar, Task }
import monix.execution.atomic._

/* An implementation of the TL2 software transactional memory algorithm
 *
 * See: [[http://people.csail.mit.edu/shanir/publications/Transactional_Locking.pdf Transactional Locking II]]
 * Also: [[https://www.researchgate.net/publication/220888858_An_Implementation_of_Composable_Memory_Transactions_in_Haskell An Implementation of Composable Memory Transactions In Haskell]]
 */

case class TVar[A] private[stm] (
  lock: AtomicLong,
  id: Long,
  writeStamp: AtomicLong,
  content: Atomic[A],
  waitQueue: AtomicAny[List[MVar[Unit]]]
)

object TVar {
  private val idRef = AtomicLong(0L)

  private def createTVar[A](a: A)(implicit builder: AtomicBuilder[A, _ <: Atomic[A]]): Task[TVar[A]] =
    Task.eval {
      val lock = AtomicLong(1L)
      TVar(lock, idRef.incrementAndGet(), AtomicLong(lock.get), Atomic(a), AtomicAny(List.empty))
    }

  def create[A](a: A)(implicit builder: AtomicBuilder[A, _ <: Atomic[A]]): STM[TVar[A]] =
    STM { ts => createTVar(a).map(tv => Valid(ts, tv)) }
}

case class TState private[stm] (
  id: Long,
  readStamp: Long,
  readSet: STM.ReadSet,
  writeSet: STM.WriteSet
) {
  def merge(that: TState) = Task.eval {
    val rs1 = this.readSet.get
    val rs2 = that.readSet.get
    val nrs = rs1.union(rs2)
    this.readSet.set(nrs)
    this
  }
}

object TState {
  def create: Task[TState] = Task.eval {
    val id = Thread.currentThread.getId * 2
    val readStamp = Global.versionClock.get
    val readSet = AtomicAny(Set.empty[RSEntry])
    val writeSet = AtomicAny(Map.empty[Long, WSEntry[Any]])
    TState(id, readStamp, readSet, writeSet)
  }
}

sealed abstract class TResult[A]
case class Valid[A](state: TState, result: A) extends TResult[A]
case class Retry[A](state: TState) extends TResult[A]
case class Invalid[A](state: TState) extends TResult[A]

case class RSEntry(
  id: Long,
  lock: AtomicLong,
  writeStamp: AtomicLong,
  waitQueue: AtomicAny[List[MVar[Unit]]]
)

case class WSEntry[A](
  lock: AtomicLong,
  writeStamp: AtomicLong,
  content: Atomic[A],
  newValue: A,
  waitQueue: AtomicAny[List[MVar[Unit]]]
)

class STM[A](private[stm] val run: TState => Task[TResult[A]]) {
  def orElse(that: STM[A]) = new STM[A](st => {
    // Copy the state for possible later use
    STM.cloneTState(st).flatMap { cpy =>
      // Run the first action with the state
      this.run(st).flatMap {
        case Retry(ns1) =>
          // If we need to retry, run the second action
          // with the copy of the original state
          that.run(cpy).flatMap {
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
  type WriteSet = AtomicAny[Map[Long, WSEntry[Any]]]
  type ReadSet = AtomicAny[Set[RSEntry]]

  def isLocked(l: Long): Boolean = l % 2L == 0

  def cloneTState(ts: TState): Task[TState] = Task.eval {
    val newWriteSet = AtomicAny(ts.writeSet.get)
    val newReadSet = AtomicAny(ts.readSet.get)
    TState(ts.id, ts.readStamp, newReadSet, newWriteSet)
  }

  def validateAndAcquireLocks(readStamp: Long, id: Long, readSetEntries: List[RSEntry]): Task[(Boolean, List[(AtomicLong, AtomicLong)])] = {
    def loop(locks: List[(AtomicLong, AtomicLong)], readSetEntries: List[RSEntry]): Task[(Boolean, List[(AtomicLong, AtomicLong)])] =
      readSetEntries match {
        // There are no more locks to acquire, success!
        case Nil =>
          Task.now((true, locks))
        case RSEntry(_, lock, writeStamp, _) :: remainingEntries =>
          val lockValue = lock.get
          // This lock is already taken, or the version clock has been incremented since we started our transaction
          if (isLocked(lockValue) || lockValue > readStamp)
            Task.now((false, locks))
          else {
            if (lock.compareAndSet(lockValue, id))
              // Try to get the next lock
              loop((writeStamp, lock) :: locks, remainingEntries)
            else
              // The version number has changed for this lock since we read it - we failed to acquire the lock
              Task.now((false, locks))
          }

      }

    loop(List.empty, readSetEntries)
  }

  def getLocks(id: Long, writeSetEntries: List[(Long, WSEntry[Any])]): Task[(Boolean, List[(AtomicLong, AtomicLong)])] = {
    def loop(writeSetEntries: List[(Long, WSEntry[Any])], locks: List[(AtomicLong, AtomicLong)]): Task[(Boolean, List[(AtomicLong, AtomicLong)])] =
      writeSetEntries match {
        case Nil =>
          // There are no more locks to acquire, success!
          Task.now((true, locks))
        case (_, WSEntry(lock, writeStamp, _, _, _)) :: remainingEntries =>
          val lockValue = lock.get
          if (isLocked(lockValue)) {
            // This lock is already taken
            Task.now((false, locks))
          } else {
            if (lock.compareAndSet(lockValue, id))
              // Try to get the next lock
              loop(remainingEntries, (writeStamp, lock) :: locks)
            else
              // The version number has changed for this lock since we read it - we failed to acquire the lock
              Task.now((false, locks))
          }
      }

    loop(writeSetEntries, List.empty)
  }

  def unlock(id: Long, locks: List[(AtomicLong, AtomicLong)]): Task[Unit] =
    Task.eval {
      locks.foreach {
        case (writeStamp, lock) =>
          val ws = writeStamp.get
          lock.compareAndSet(id, ws)
      }
    }

  def addToWaitQueues(mvar: MVar[Unit], readSetEntries: List[RSEntry]): Task[Unit] =
    Task.eval {
      readSetEntries.foreach {
        case RSEntry(_, _, _, waitQueue) =>
          waitQueue.transform(mvars => mvar :: mvars)
      }
    }

  def validateReadSet(readSet: ReadSet, readStamp: Long, id: Long): Task[Boolean] = {
    def loop(readSetEntries: List[RSEntry]): Task[Boolean] =
      readSetEntries match {
        case Nil =>
          Task.now(true)
        case RSEntry(_, lock, ws, _) :: remainingEntries =>
          val lockValue = lock.get
          if (isLocked(lockValue) && lockValue != id)
            Task.now(false)
          else {
            if (lockValue != id) {
              if (lockValue > readStamp)
                Task.now(false)
              else
                loop(remainingEntries)
            } else {
              val writeStamp = ws.get
              if (writeStamp > readStamp)
                Task.now(false)
              else
                loop(remainingEntries)
            }
          }
      }

    loop(readSet.get.toList)
  }

  def commitChangesToMemory(writeStamp: Long, writeSetEntries: List[(Long, WSEntry[Any])]): Task[Unit] =
    Task.eval {
      writeSetEntries.foreach {
        case (id, WSEntry(_, ws, content, value, _)) =>
          ws.set(writeStamp)
          content.set(value)
      }
    }

  def wakeUpWaitQueue(writeSetEntries: List[(Long, WSEntry[Any])]): Task[Unit] =
    Task.traverse(writeSetEntries) {
      case (id, WSEntry(_, _, _, _, waitQueue)) =>
        for {
          mvars <- Task.eval(waitQueue.get)
          _ <- Task.traverse(mvars)(_.put(()))
          _ <- Task.eval(waitQueue.set(List.empty))
        } yield ()
    }.map(_ => ())

  def atomically[A](stm: STM[A]): Task[A] =
    TState.create.flatMap { ts =>
      stm.run(ts).flatMap {
        case Invalid(nts) =>
          atomically(stm)
        case Retry(nts) =>
          val rs = nts.readSet.get
          val rsEntries = rs.toList
          validateAndAcquireLocks(nts.readStamp, nts.id, rsEntries).flatMap {
            case (valid, locks) =>
              if (!valid) {
                for {
                  _ <- unlock(nts.id, locks)
                  a <- atomically(stm)
                } yield a
              } else {
                val mvar = MVar.empty[Unit]
                for {
                  _ <- addToWaitQueues(mvar, rsEntries)
                  _ <- unlock(nts.id, locks)
                  _ <- mvar.take
                  a <- atomically(stm)
                } yield a
              }
          }
        case Valid(nts, a) =>
          val id = Thread.currentThread.getId * 2
          val wsEntries = nts.writeSet.get.toList
          getLocks(id, wsEntries).flatMap {
            case (false, locks) =>
              unlock(nts.id, locks)
                .flatMap(_ => atomically(stm))
            case (true, locks) =>
              val writeStamp = Global.versionClock.incrementAndGet(2)
              validateReadSet(nts.readSet, nts.readStamp, nts.id).flatMap { valid =>
                if (valid) {
                  for {
                    _ <- commitChangesToMemory(writeStamp, wsEntries)
                    _ <- wakeUpWaitQueue(wsEntries)
                    _ <- unlock(nts.id, locks)
                  } yield a
                } else {
                  for {
                    _ <- unlock(nts.id, locks)
                    a <- atomically(stm)
                  } yield a
                }
              }
          }
      }
    }

  def apply[A](f: TState => Task[TResult[A]]) = new STM[A](f)
  def valid[A](a: A) = new STM[A](state => Task.now(Valid(state, a)))
  def retry[A]: STM[A] = new STM[A](state => Task.now(Retry(state)))
}

/*
 * Copyright (c) 2014-2017 by The Monix Project Developers.
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

package monix.benchmarks

import java.util.concurrent.TimeUnit

import monix.execution.ExecutionModel.SynchronousExecution
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** To do comparative benchmarks between Monix versions:
  *
  *     benchmarks/run-benchmark STMBenchmark
  *
  * This will generate results in `benchmarks/results`.
  *
  * Or to run the benchmark from within SBT:
  *
  *     jmh:run -i 10 -wi 10 -f 2 -t 1 monix.benchmarks.STMBenchmark
  *
  * Which means "10 iterations", "10 warm-up iterations", "2 fork", "1 thread".
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class STMBenchmark {
  @Param(Array("10000"))
  var size: Int = _

  @Benchmark
  def validMonix(): Int = {
    import STMBenchmark.monixScheduler
    import monix.stm._

    def loop(i: Int): STM[Int] =
      if (i < size)
        STM.valid(i + 1).flatMap(loop)
      else
        STM.valid(i)

    STM.atomically {
      STM.valid(0).flatMap(loop)
    }.runSyncMaybe.right.get
  }

  @Benchmark
  def tvarMonix(): Int = {
    import STMBenchmark.monixScheduler
    import monix.stm._

    def loop(tv: TVar[Int]): STM[Int] =
      tv.read.flatMap { i =>
        if (i == size)
          STM.valid(i)
        else
          tv.write(i + 1).flatMap(_ => loop(tv))
      }

    Await.result(
      STM.atomically {
        TVar.create(0)
          .flatMap(loop)
      }.runAsync,
      Duration.Inf
    )
  }

  @Benchmark
  def tvarScalaSTM(): Int = {
    import scala.concurrent.stm._

    atomic { implicit txn =>
      val tv = Ref(0)
      while (tv() < size) tv += 1
      tv()
    }
  }
}

object STMBenchmark {
  import monix.execution.Scheduler

  implicit val monixScheduler: Scheduler = {
    import monix.execution.Scheduler.global
    global.withExecutionModel(SynchronousExecution)
  }
}

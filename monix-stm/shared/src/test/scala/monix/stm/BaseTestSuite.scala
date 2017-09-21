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

package monix.stm

import cats.Eq
import minitest.{SimpleTestSuite, TestSuite}
import minitest.laws.Checkers
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.Test.Parameters
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.typelevel.discipline.Laws

import scala.concurrent.duration._

trait BaseTestSuite extends TestSuite[TestScheduler] with Checkers with ArbitraryInstances {
  def setup(): TestScheduler = TestScheduler()
  def tearDown(env: TestScheduler): Unit = {
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")
  }
}

trait BaseLawsTestSuite extends SimpleTestSuite with Checkers with ArbitraryInstances {
  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(if (Platform.isJVM) 100 else 10)
      .withMaxDiscardRatio(if (Platform.isJVM) 5.0f else 50.0f)
      .withMaxSize(10)

  def checkAllAsync(name: String, config: Parameters = checkConfig)
    (f: TestScheduler => Laws#RuleSet): Unit = {

    val s = TestScheduler()
    val ruleSet = f(s)

    for ((id, prop: Prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        s.tick(1.day)
        check(prop)
      }
  }
}

trait ArbitraryInstances extends ArbitraryInstancesBase with monix.execution.ArbitraryInstances {
  implicit def equalitySTM[A](implicit A: Eq[A], ec: TestScheduler): Eq[STM[A]] =
    new Eq[STM[A]] {
      def eqv(lh: STM[A], rh: STM[A]): Boolean =
        equalityFuture(A, ec).eqv(STM.atomically(lh).runAsync, STM.atomically(rh).runAsync)
    }
}

trait ArbitraryInstancesBase extends monix.execution.ArbitraryInstancesBase {
  implicit def arbitrarySTM[A](implicit A: Arbitrary[A]): Arbitrary[STM[A]] =
    Arbitrary {
      for {
        a <- A.arbitrary
        stm <- Gen.oneOf(STM.valid(a), STM.retry[A])
      } yield stm
    }
}

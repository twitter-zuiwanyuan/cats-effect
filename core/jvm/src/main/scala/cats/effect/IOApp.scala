/*
 * Copyright 2020 Typelevel
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

package cats.effect

import scala.concurrent.ExecutionContext

import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

trait IOApp {

  def run(args: List[String]): IO[Int]

  final def main(args: Array[String]): Unit = {
    val runtime = Runtime.getRuntime()

    // detect unforked sbt
    if (Thread.currentThread().getName().startsWith("run-main")) {
      try {
        // the security manager will be sbt.TrapExit; use its classloader to jailbreak out and find sbt.EvaluateTask
        val klass = System.getSecurityManager().getClass().getClassLoader().loadClass("sbt.EvaluateTask$")

        val EvaluateTask = klass.getDeclaredField("MODULE$").get(null).asInstanceOf[{ val currentlyRunningEngine: AtomicReference[AnyRef] }]
        val currentEngine = EvaluateTask.currentlyRunningEngine

        val outer = Thread.currentThread()

        val monitor = new Thread({ () =>
          var continue = true
          while (continue)  {
            if (currentEngine.get() == null) {
              continue = false
              outer.interrupt()
            }
            Thread.sleep(100)
          }
        })

        monitor.setName("ioapp-sbt-unforked-interrupt-monitor")
        monitor.setPriority(Thread.MIN_PRIORITY)
        monitor.setDaemon(true)

        monitor.start()
      } catch {
        case _: Throwable => ()   // literally swallow anything that moves... (it probably means our detection failed, or sbt changed its internals)
      }
    }

    val threadCount = new AtomicInteger(0)
    val executor = Executors.newFixedThreadPool(runtime.availableProcessors(), { (r: Runnable) =>
      val t = new Thread(r)
      t.setName(s"io-compute-${threadCount.getAndIncrement()}")
      t.setDaemon(true)
      t
    })
    val context = ExecutionContext.fromExecutor(executor)

    val scheduler = Executors newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r)
      t.setName("io-scheduler")
      t.setDaemon(true)
      t.setPriority(Thread.MAX_PRIORITY)
      t
    }
    val timer = UnsafeTimer.fromScheduledExecutor(scheduler)

    val latch = new CountDownLatch(1)
    @volatile var results: Either[Throwable, Int] = null

    val ioa = run(args.toList)

    val fiber = ioa.unsafeRunFiber(context, timer) { e =>
      results = e
      latch.countDown()
    }

    def handleShutdown(): Unit = {
      if (latch.getCount() > 0) {
        val cancelLatch = new CountDownLatch(1)
        fiber.cancel.unsafeRunAsync(context, timer) { _ => cancelLatch.countDown() }
        cancelLatch.await()
      }

      scheduler.shutdown()
      executor.shutdown()
    }

    val hook = new Thread(handleShutdown _)
    hook.setName("io-cancel-hook")

    runtime.addShutdownHook(hook)

    try {
      // TODO in theory it's possible to just fold the main thread into the pool; should we?
      latch.await()

      results.fold(
        throw _,
        System.exit(_))
    } catch {
      // this handles sbt when fork := false
      case _: InterruptedException =>
        hook.start()
        runtime.removeShutdownHook(hook)
        Thread.currentThread().interrupt()
    }
  }
}

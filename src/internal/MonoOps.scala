package dapr.safe.internal

import language.experimental.saferExceptions

/** Reactor ↔ virtual-thread bridge. See [[MonoOps.awaitResult]]. */
@scala.caps.assumeSafe
private[internal] object MonoOps:

  extension [T](mono: reactor.core.publisher.Mono[T])

    /** Block the calling thread until the [[reactor.core.publisher.Mono]] completes, using
      * [[java.util.concurrent.CompletableFuture#get]] rather than [[reactor.core.publisher.Mono#block]].
      *
      * ==Why not `Mono.block()`?==
      *
      * `Mono.block()` parks the calling thread via `LockSupport.park`, which correctly unmounts a virtual thread from
      * its carrier. However, Reactor's internal `BlockingSingleSubscriber` — the subscriber created for every `block()`
      * call — declares its `onNext`, `onComplete`, and `onError` callbacks as `synchronized`. On JDK < 24, entering a
      * `synchronized` block pins the carrier platform thread of the owning virtual thread for the duration of the lock.
      * Result delivery happens on a Reactor worker thread (the gRPC / HTTP completion thread), so under high
      * concurrency that worker's carrier can be briefly pinned.
      *
      * `CompletableFuture.complete()` uses a compare-and-exchange (CAS) loop with no `synchronized` anywhere in the hot
      * path. `CompletableFuture .get()` on the calling thread parks via `LockSupport.park` with no `synchronized`, so
      * neither the waiting thread nor the emitting thread risks carrier pinning — the full benefit of virtual threads
      * is realised.
      *
      * ==Getting the most out of virtual threads==
      *
      * Call [[dapr.safe.DaprRuntime.run]] from a virtual thread so that the carrier is freed during each I/O wait, and
      * the platform thread pool is not exhausted by concurrent Dapr calls:
      *
      * {{{
      *   // Plain Java / Scala main():
      *   Thread.ofVirtual().start(() => DaprRuntime.run { ... }).join()
      *
      *   // Spring Boot 3.2+:  add to application.properties:
      *   //   spring.threads.virtual.enabled=true
      *
      *   // Quarkus:  annotate the endpoint / service method:
      *   //   @RunOnVirtualThread
      *
      *   // Helidon 4:  virtual threads are the default — no annotation needed.
      * }}}
      *
      * The library works correctly on platform threads too; the `toFuture().get()` bridge is a no-op improvement in
      * that case.
      *
      * ==Exception handling==
      *
      * [[java.util.concurrent.ExecutionException]] is unwrapped so callers see the original cause (typically
      * [[io.dapr.exceptions.DaprException]]).
      *
      * [[java.lang.InterruptedException]] is caught explicitly — see the inline comment in the implementation for the
      * full argument.
      */
    def awaitResult(): T | Null =
      given CanThrow[Exception] = unsafeExceptions.canThrowAny
      try mono.toFuture().nn.get()
      catch
        case e: java.util.concurrent.ExecutionException =>
          val cause = e.getCause
          throw (if cause != null then cause else e)

        // WHY WE CATCH InterruptedException HERE
        //
        // Scala's NonFatal extractor classifies InterruptedException as fatal
        // because an unhandled interrupt should normally terminate the thread.
        // We are explicitly handling it, which is different from suppressing it.
        //
        // The Java cooperative-cancellation contract requires that any code which
        // catches InterruptedException must either:
        //   (a) re-interrupt the thread and rethrow, OR
        //   (b) set a "cancelled" flag and return early.
        //
        // CompletableFuture.get() clears the interrupt flag when it throws
        // InterruptedException. If we did NOT catch it here, that cleared flag
        // would propagate silently past every outer catch block, losing the
        // cancellation signal entirely. Outer handlers would then see a raw
        // InterruptedException with no interrupt flag set — the thread would
        // appear un-interrupted to any subsequent isInterrupted() check.
        //
        // By catching it here we restore the flag immediately (before any other
        // code runs), then rethrow the original exception unchanged. The caller
        // receives the InterruptedException with the flag correctly set and can
        // propagate or handle the cancellation as appropriate.
        //
        // We do NOT wrap it in a DaprException: doing so would strip the
        // interrupt semantics and make cooperative cancellation impossible to
        // detect higher up the call stack.
        case e: java.lang.InterruptedException =>
          Thread.currentThread().interrupt() // restore flag before any other code runs
          throw e

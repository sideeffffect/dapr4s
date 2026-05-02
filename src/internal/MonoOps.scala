package dapr.safe.internal

import language.experimental.saferExceptions

/** Reactor ↔ virtual-thread bridge. See [[MonoOps.awaitResult]]. */
@scala.caps.assumeSafe
private[internal] object MonoOps:

  extension [T](mono: reactor.core.publisher.Mono[T])

    /** Block the calling thread until the [[reactor.core.publisher.Mono]]
      * completes, using [[java.util.concurrent.CompletableFuture#get]]
      * rather than [[reactor.core.publisher.Mono#block]].
      *
      * == Why not `Mono.block()`? ==
      *
      * `Mono.block()` parks the calling thread via `LockSupport.park`, which
      * correctly unmounts a virtual thread from its carrier.  However,
      * Reactor's internal `BlockingSingleSubscriber` — the subscriber created
      * for every `block()` call — declares its `onNext`, `onComplete`, and
      * `onError` callbacks as `synchronized`.  On JDK < 24, entering a
      * `synchronized` block pins the carrier platform thread of the owning
      * virtual thread for the duration of the lock.  Result delivery happens
      * on a Reactor worker thread (the gRPC / HTTP completion thread), so
      * under high concurrency that worker's carrier can be briefly pinned.
      *
      * `CompletableFuture.complete()` uses a compare-and-exchange (CAS) loop
      * with no `synchronized` anywhere in the hot path.  `CompletableFuture
      * .get()` on the calling thread parks via `LockSupport.park` with no
      * `synchronized`, so neither the waiting thread nor the emitting thread
      * risks carrier pinning — the full benefit of virtual threads is realised.
      *
      * == Getting the most out of virtual threads ==
      *
      * Call [[dapr.safe.DaprRuntime.run]] from a virtual thread so that the
      * carrier is freed during each I/O wait, and the platform thread pool is
      * not exhausted by concurrent Dapr calls:
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
      * The library works correctly on platform threads too; the
      * `toFuture().get()` bridge is a no-op improvement in that case.
      *
      * == Exception handling ==
      *
      * [[java.util.concurrent.ExecutionException]] is unwrapped so callers see
      * the original cause (typically [[io.dapr.exceptions.DaprException]]).
      * On [[java.lang.InterruptedException]] the thread interrupt flag is
      * restored before re-throwing.
      */
    def awaitResult(): T | Null =
      given CanThrow[Exception] = unsafeExceptions.canThrowAny
      try mono.toFuture().nn.get()
      catch
        case e: java.util.concurrent.ExecutionException =>
          val cause = e.getCause
          throw (if cause != null then cause else e)
        case e: java.lang.InterruptedException =>
          Thread.currentThread().interrupt()
          throw e

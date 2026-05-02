package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions
import scala.util.control.NonFatal

/** Entry-point singleton that manages the [[DaprScope]] lifecycle.
  *
  * This object is annotated `@scala.caps.assumeSafe` so that safe-mode user
  * code can call `DaprRuntime.run` without seeing any unsafe operations.
  * The internal use of `DaprScopeImpl` (a Java-SDK-backed class) is safely
  * encapsulated here.
  */
@scala.caps.assumeSafe
object DaprRuntime:

  /** Acquire a `DaprClient`, run `body` with a `DaprScope` in context, then
    * release the client whether `body` completes normally or throws.
    *
    * If both `body` and `scope.close()` throw non-fatal exceptions, the
    * close exception is added as a suppressed exception on the body's
    * throwable.  Fatal exceptions (`OutOfMemoryError`, `StackOverflowError`,
    * etc.) are never caught — they propagate immediately.  In the pathological
    * case where the body throws a fatal error and `close()` also throws, the
    * close exception will propagate instead of the fatal one; this is an
    * acceptable trade-off because the JVM is already in an unrecoverable state.
    *
    * == Virtual threads ==
    *
    * For best throughput, call `run` from a virtual thread (JDK 21+).
    * Each I/O call inside the body bridges to the calling thread via
    * `CompletableFuture.get()`, which parks the virtual thread and frees its
    * carrier platform thread for other work during the wait.  On a platform
    * thread the same calls block normally — correctness is unaffected, only
    * throughput differs.
    *
    * {{{
    *   // Plain Scala / Java main():
    *   Thread.ofVirtual().start(() => DaprRuntime.run { ... }).join()
    *
    *   // Spring Boot 3.2+:  spring.threads.virtual.enabled=true
    *   // Quarkus:            @RunOnVirtualThread on the endpoint method
    *   // Helidon 4:          virtual threads by default — no annotation needed
    * }}}
    *
    * @param body a pure context function that receives a `DaprScope`
    * @return the value returned by `body`
    */
  def run[T](body: (DaprScope, CanThrow[Exception]) ?=> T): T =
    val scope: DaprScope = internal.DaprScopeImpl.create()
    given canThrow: CanThrow[Exception] = unsafeExceptions.canThrowAny
    var primary: Throwable | Null = null
    try body(using scope, canThrow)
    catch
      case NonFatal(t) =>
        primary = t
        throw t
    finally
      try scope.close()
      catch
        case NonFatal(t) =>
          val p = primary
          if p != null then p.addSuppressed(t)
          else throw t

  /** Run `body` with a [[DaprScope]] pointing to a specific sidecar endpoint.
    *
    * Useful in tests (e.g. Testcontainers) where the sidecar runs on a
    * non-default port. This avoids importing Java SDK types directly.
    *
    * System properties are reset to their original values after the block
    * completes (whether normally or exceptionally).
    *
    * See [[run]] for virtual-thread usage guidance.
    */
  def runWithEndpoints[T](httpEndpoint: String, grpcEndpoint: String)(body: (DaprScope, CanThrow[Exception]) ?=> T): T =
    val prevHttp  = Option(System.getProperty("dapr.http.endpoint"))
    val prevGrpc  = Option(System.getProperty("dapr.grpc.endpoint"))
    System.setProperty("dapr.http.endpoint", httpEndpoint)
    System.setProperty("dapr.grpc.endpoint", grpcEndpoint)
    try run(body)
    finally
      prevHttp match
        case Some(v) => System.setProperty("dapr.http.endpoint", v)
        case None    => System.clearProperty("dapr.http.endpoint")
      prevGrpc match
        case Some(v) => System.setProperty("dapr.grpc.endpoint", v)
        case None    => System.clearProperty("dapr.grpc.endpoint")

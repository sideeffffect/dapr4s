package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

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
    * If both `body` and `scope.close()` throw, the close exception is
    * added as a suppressed exception on the primary throwable.
    *
    * @param body a function that receives a `DaprScope` as a context parameter
    * @return the value returned by `body`
    */
  def run[T](body: (DaprScope, CanThrow[Exception]) ?=> T): T =
    val scope: DaprScope = internal.DaprScopeImpl.create()
    given canThrow: CanThrow[Exception] = unsafeExceptions.canThrowAny
    var primary: Throwable | Null = null
    try body(using scope, canThrow)
    catch
      case t: Throwable =>
        primary = t
        throw t
    finally
      try scope.close()
      catch
        case t: Throwable =>
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
    * Note: `.run` can be called from a virtual thread (JDK 25+). The
    * blocking `.block()` calls in capability implementations park the virtual
    * thread rather than blocking an OS thread, so throughput is maintained.
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

package dapr.safe

import scala.caps.Capability

// ---------------------------------------------------------------------------
// Capture checking note (Issue 3)
//
// Full enforcement of DaprScope lifetime via capture annotations requires the
// `-Ycc` flag, which is only available in nightly Scala 3 builds and NOT in
// the stable Scala 3.5.2 used here. The `^` (capture polymorphism) annotations
// below document intent but are not enforced by the compiler at this time.
//
// Future enhancement: once `-Ycc` is stable, add `^` annotations to factory
// method return types so the compiler verifies that capabilities cannot escape
// the DaprRuntime.run block.
//
// Example (nightly only):
//   def state(storeName: StoreName): StateCapability^{this}
// ---------------------------------------------------------------------------

/** Root capability that acts as a factory for all DAPR sub-capabilities.
  *
  * A `DaprScope` is provided as a context parameter inside
  * [[DaprRuntime.run]]. It must not outlive the `run` block — the Scala 3
  * capture checker enforces this when capture checking is enabled via `-Ycc`
  * (nightly-only flag; not enforced in stable Scala 3.5.2).
  */
trait DaprScope extends Capability:

  /** Obtain a [[StateCapability]] for the named state store. */
  def state(storeName: StoreName): StateCapability

  /** Obtain a [[PubSubCapability]] for the named pub/sub component. */
  def pubsub(pubsubName: PubSubName): PubSubCapability

  /** Obtain the [[ServiceInvocationCapability]] (shared; no named store). */
  def invoker: ServiceInvocationCapability

  /** Obtain a [[SecretsCapability]] for the named secrets store. */
  def secrets(storeName: SecretStoreName): SecretsCapability

  /** Obtain a [[ConfigurationCapability]] for the named configuration store. */
  def config(storeName: ConfigStoreName): ConfigurationCapability

  /** Obtain a [[BindingsCapability]] for the named output binding. */
  def binding(bindingName: BindingName): BindingsCapability

  /** Close the underlying DAPR client. Called by [[DaprRuntime.run]].
    *
    * Library-internal: user code should not call this directly.
    * The [[DaprRuntime.run]] method calls this on scope exit (both normal and exceptional).
    */
  def close(): Unit

// ---------------------------------------------------------------------------

/** Entry-point singleton that manages the [[DaprScope]] lifecycle. */
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
  def run[T](body: DaprScope ?=> T): T =
    val scope: DaprScope = internal.DaprScopeImpl.create()
    var primary: Throwable = null
    try body(using scope)
    catch
      case t: Throwable =>
        primary = t
        throw t
    finally
      try scope.close()
      catch
        case t: Throwable =>
          if primary != null then primary.addSuppressed(t)
          else throw t

  /** Run `body` with a [[DaprScope]] pointing to a specific sidecar endpoint.
    *
    * Useful in tests (e.g. Testcontainers) where the sidecar runs on a
    * non-default port. This avoids importing Java SDK types directly.
    *
    * System properties are reset to their original values after the block
    * completes (whether normally or exceptionally).
    */
  def runWithEndpoints[T](httpEndpoint: String, grpcEndpoint: String)(body: DaprScope ?=> T): T =
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

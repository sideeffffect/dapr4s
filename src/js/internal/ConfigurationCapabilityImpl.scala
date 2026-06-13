//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.control.NonFatal
import JsInterop.*
import dapr4styped.daprDapr.typesConfigurationConfigurationItemMod.ConfigurationItem as SdkConfigurationItem
import dapr4styped.daprDapr.typesConfigurationSubscribeConfigurationCallbackMod.SubscribeConfigurationCallback
import dapr4styped.daprDapr.typesConfigurationSubscribeConfigurationResponseMod.SubscribeConfigurationResponse

@scala.caps.assumeSafe
private[internal] final class ConfigurationCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: ConfigurationStoreName,
) extends ConfigurationCapability:

  import ConfigurationCapabilityImpl.*

  // Both operations go through scope.grpcClient (the lazily-created gRPC-protocol DaprClient):
  // configuration is gRPC-only in the JS SDK — the HTTP implementation throws HTTPNotSupportedError
  // (implementation/Client/HTTPClient/configuration.js).

  def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[ConfigurationKey, ConfigurationItem] =
    val response = JsAwait.await(
      scope.grpcClient.configuration.get(storeName.value, keys.map(_.value).toJSArray, toDict(metadata)),
    )
    response.items.iterator.map { case (k, item) => ConfigurationKey(k) -> toConfigItem(k, item) }.toMap

  def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigurationUpdate => Unit,
  ): AutoCloseable^{this} =
    val storeNameStr = storeName.value
    // The callback is invoked by the SDK from a JavaScript frame (the `for await` loop over the
    // gRPC stream in GRPCClientConfiguration._subscribe), and JSPI suspension cannot cross a JS
    // frame — so the callback opens a FRESH `js.async { ... }` entry. Without it, any capability
    // call inside the user's onChange (an orphan js.await deeper in the stack) would throw
    // WebAssembly.SuspendError because no dynamically enclosing js.async would be reachable
    // without an intervening JS frame. The js.Promise the async block returns is exactly what the
    // SDK's `await cb(...)` contract expects.
    val callback: js.Function1[SubscribeConfigurationResponse, js.Promise[Unit]]^{this, onChange} =
      (response: SubscribeConfigurationResponse) =>
        js.async {
          val items = response.items.iterator.map { case (k, item) => ConfigurationKey(k) -> toConfigItem(k, item) }.toMap
          try onChange(ConfigurationUpdate(ConfigurationStoreName(storeNameStr), items))
          catch
            case NonFatal(e) =>
              // Mirror the JVM impl: a throwing onChange is logged, never propagated into the SDK's
              // stream loop (which would silently kill the subscription). java.util.logging is not in
              // the Scala.js javalib, so console.warn stands in for Logger.log(WARNING, ...).
              js.Dynamic.global.console.warn(s"Config subscription onChange callback threw: $e"): Unit
        }
    // WHAT: asInstanceOf erasing the callback's capture set ({this, onChange}).
    // WHY: js.Function1 is a Scala-defined SAM, so CC tracks the closure's captures, but the SDK's
    // SubscribeConfigurationCallback type (a ScalablyTyped alias of the same js.Function1 shape) mirrors the
    // TypeScript callback type, which is necessarily capture-free — a JS interop boundary cannot carry capture
    // annotations.
    // WHY SAFE: the callback cannot outlive the capabilities it captures: it only runs while the
    // SDK's stream loop is alive, the loop is torn down by stream.stop() (wired into the returned
    // AutoCloseable), and that handle is itself ^{this}-bound so capture checking already prevents
    // the subscription from escaping this capability's scope. Same erasure rationale as the
    // AnyRef-erasure pattern documented in AGENTS.md.
    val stream = JsAwait.await(
      scope.grpcClient.configuration.subscribeWithMetadata(
        storeNameStr,
        keys.map(_.value).toJSArray,
        toDict(metadata),
        callback.asInstanceOf[SubscribeConfigurationCallback],
      ),
    )
    // stop() is async at runtime (an async arrow that aborts the stream and sends the explicit
    // unsubscribe RPC) even though the SDK's TypeScript interface — and therefore the ScalablyTyped
    // signature — says `stop(): void`. Awaiting the recovered promise makes close() synchronous
    // like the JVM's `() => sub.dispose()`.
    //
    // WHAT: js.Dynamic call of stop() + asInstanceOf[js.Promise[Unit]] on its result.
    // WHY: the ST-typed `stream.stop(): Unit` would discard the promise, so close() could return
    // before the unsubscribe RPC went out — a behaviour change from the hand-verified facade
    // (SubscribeConfigurationStream.stop is `stop = async () => {...}` in
    // implementation/Client/GRPCClient/configuration.js; the TS interface under-promises).
    // WHY SAFE: the runtime return value IS a Promise (verified in the SDK sources above); the
    // dynamic call invokes the same member the typed call would, and the cast is the standard
    // erased view of a known JS value.
    () => JsAwait.await(stream.asInstanceOf[js.Dynamic].applyDynamic("stop")().asInstanceOf[js.Promise[Unit]])

@scala.caps.assumeSafe
private object ConfigurationCapabilityImpl:

  /** Build the dapr4s item from the SDK's `ConfigurationItem` (`createConfigurationType`, `utils/Client.util.js`).
    *
    * ScalablyTyped types `value`/`version`/`metadata` as required (the TS interface says so), but the reads below
    * stay defensive type-tests: the values cross a JS boundary where proto3 string defaults make them `""` in
    * practice, and an absent field must degrade to `""`/empty exactly like the JVM impl treats `null` — not to an
    * undefined-as-String read.
    */
  private def toConfigItem(k: String, item: SdkConfigurationItem): ConfigurationItem =
    ConfigurationItem(
      key = ConfigurationKey(k),
      value = ConfigurationValue((item.value: Any) match
        case s: String => s
        case _ => ""),
      version = ConfigurationVersion((item.version: Any) match
        case s: String => s
        case _ => ""),
      metadata = (item.metadata: Any) match
        case null => Map.empty[MetadataKey, MetadataValue]
        case _ if js.isUndefined(item.metadata) => Map.empty[MetadataKey, MetadataValue]
        case _ =>
          item.metadata.iterator.collect { case (mk, mv: String) => MetadataKey(mk) -> MetadataValue(mv) }.toMap,
    )

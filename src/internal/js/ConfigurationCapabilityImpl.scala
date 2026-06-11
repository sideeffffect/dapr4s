//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.control.NonFatal
import JsInterop.*

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
    val callback: js.Function1[facade.SubscribeConfigurationResponse, js.Promise[Unit]]^{this, onChange} =
      (response: facade.SubscribeConfigurationResponse) =>
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
    // WHY: js.Function1 is a Scala-defined SAM, so CC tracks the closure's captures, but the facade
    // signature mirrors the SDK's TypeScript callback type, which is necessarily capture-free — a JS
    // interop boundary cannot carry capture annotations.
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
        callback.asInstanceOf[js.Function1[facade.SubscribeConfigurationResponse, js.Promise[Unit]]],
      ),
    )
    // stop() is async (it aborts the stream and sends the explicit unsubscribe RPC); awaiting it
    // makes close() synchronous like the JVM's `() => sub.dispose()`.
    () => JsAwait.await(stream.stop())

@scala.caps.assumeSafe
private object ConfigurationCapabilityImpl:

  private def toConfigItem(k: String, item: facade.ConfigurationItemJs): ConfigurationItem =
    ConfigurationItem(
      key = ConfigurationKey(k),
      value = ConfigurationValue(item.value.getOrElse("")),
      version = ConfigurationVersion(item.version.getOrElse("")),
      metadata = item.metadata.toOption.fold(Map.empty[MetadataKey, MetadataValue]) { jm =>
        jm.iterator.map { case (mk, mv) => MetadataKey(mk) -> MetadataValue(mv) }.toMap
      },
    )

//> using target.platform "scala-js"
package dapr4s.internal.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// ---------------------------------------------------------------------------
// Scala.js facades for the `@dapr/dapr` npm package (the Dapr JS SDK, 3.x).
//
// Only the classes/enums re-exported from the package root (`index.js`) carry a
// @JSImport. The sub-client interfaces (`IClientState`, `IClientPubSub`, ...)
// and all `*.type.ts` shapes are TypeScript-only types, erased at runtime —
// they are modelled as structural `@js.native` traits WITHOUT @JSImport
// (request/option shapes we construct ourselves are non-native `js.Object`
// classes, so the fields become plain JS properties).
//
// Signatures were verified against the installed sources in
// node_modules/@dapr/dapr (v3.18.0): implementation/Client/DaprClient.d.ts,
// implementation/Client/HTTPClient/*.js, implementation/Client/GRPCClient/*.js,
// interfaces/Client/*.d.ts, types/**. Gotchas baked in below:
//   - `CommunicationProtocolEnum` is numeric with GRPC = 0, HTTP = 1.
//   - Ports are STRINGS everywhere (`daprPort: string`).
//   - `HttpMethod` values are lowercase strings ("get", "post", ...).
//   - Options objects are `Partial<...>` — every field is optional.
// ---------------------------------------------------------------------------

/** Facade for the root `DaprClient` class (`implementation/Client/DaprClient.ts`).
  *
  * Only the sub-clients dapr4s needs are declared. `start()` is declared but does not need to be called eagerly: every
  * sub-client call goes through `HTTPClient.execute` / `GRPCClient.getClient`, which auto-start the client (awaiting
  * sidecar health) on first use.
  */
@js.native
@JSImport("@dapr/dapr", "DaprClient")
private[dapr4s] class DaprClient(options: DaprClientOptions) extends js.Object:
  val state: StateClient = js.native
  val pubsub: PubSubClient = js.native
  val binding: BindingClient = js.native
  val invoker: InvokerClient = js.native
  val secret: SecretClient = js.native
  val configuration: ConfigurationClient = js.native
  val lock: LockClient = js.native
  val crypto: CryptoClient = js.native
  val health: HealthClient = js.native
  def start(): js.Promise[Unit] = js.native
  def stop(): js.Promise[Unit] = js.native

/** Facade for `types/DaprClientOptions.ts`. All fields are optional (`Partial<DaprClientOptions>` in the SDK ctor). */
private[dapr4s] final class DaprClientOptions(
    val daprHost: js.UndefOr[String] = js.undefined,
    val daprPort: js.UndefOr[String] = js.undefined,
    val communicationProtocol: js.UndefOr[Int] = js.undefined,
    val daprApiToken: js.UndefOr[String] = js.undefined,
    val maxBodySizeMb: js.UndefOr[Double] = js.undefined,
) extends js.Object

/** Facade for `enum/CommunicationProtocol.enum.ts`. Numeric, and `GRPC = 0`, `HTTP = 1` — reading the values off the
  * real SDK enum (rather than hardcoding integers) keeps us correct if upstream ever renumbers.
  */
@js.native
@JSImport("@dapr/dapr", "CommunicationProtocolEnum")
private[dapr4s] object CommunicationProtocolEnum extends js.Object:
  val GRPC: Int = js.native
  val HTTP: Int = js.native

// ---------------------------------------------------------------------------
// state (interfaces/Client/IClientState.ts, implementation/Client/HTTPClient/state.js)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait StateClient extends js.Object:
  def save(storeName: String, stateObjects: js.Array[StateKeyValuePair]): js.Promise[SoftFailureResponse] = js.native
  def save(
      storeName: String,
      stateObjects: js.Array[StateKeyValuePair],
      options: StateSaveOptions,
  ): js.Promise[SoftFailureResponse] = js.native
  def get(storeName: String, key: String): js.Promise[js.Any] = js.native
  def get(storeName: String, key: String, options: StateGetOptions): js.Promise[js.Any] = js.native
  def getBulk(storeName: String, keys: js.Array[String]): js.Promise[js.Array[BulkStateItem]] = js.native
  def delete(storeName: String, key: String, options: StateDeleteOptions): js.Promise[SoftFailureResponse] = js.native
  def transaction(storeName: String, operations: js.Array[StateTransactionOperation]): js.Promise[Unit] = js.native
  def query(storeName: String, query: js.Any): js.Promise[StateQueryResponse] = js.native

/** `KeyValuePairType` (`types/KeyValuePair.type.ts`): one entry of a `state.save` call. */
private[internal] final class StateKeyValuePair(
    val key: String,
    val value: js.Any,
    val etag: js.UndefOr[String] = js.undefined,
    val options: js.UndefOr[StateOperationOptions] = js.undefined,
) extends js.Object

/** `IStateOptions`: per-entry write behaviour. The SDK maps these numeric enums to the `"eventual"`/`"strong"` and
  * `"first-write"`/`"last-write"` strings of the HTTP API via `getStateConsistencyValue`/`getStateConcurrencyValue`
  * (`utils/Client.util.js`); unspecified (`undefined`/0) maps to no query parameter at all.
  */
private[internal] final class StateOperationOptions(
    val consistency: js.UndefOr[Int] = js.undefined,
    val concurrency: js.UndefOr[Int] = js.undefined,
) extends js.Object

/** `StateSaveOptions` (`types/state/StateSaveOptions.type.ts`): metadata becomes `metadata.*` query parameters. */
private[internal] final class StateSaveOptions(
    val metadata: js.UndefOr[js.Dictionary[String]] = js.undefined,
) extends js.Object

/** `Partial<StateGetOptions>` (`types/state/StateGetOptions.type.ts`). */
private[internal] final class StateGetOptions(
    val consistency: js.UndefOr[Int] = js.undefined,
    val metadata: js.UndefOr[js.Dictionary[String]] = js.undefined,
) extends js.Object

/** `Partial<StateDeleteOptions>` (`types/state/StateDeleteOptions.type.ts`): `etag` becomes an `If-Match` header. */
private[internal] final class StateDeleteOptions(
    val etag: js.UndefOr[String] = js.undefined,
    val consistency: js.UndefOr[Int] = js.undefined,
    val concurrency: js.UndefOr[Int] = js.undefined,
    val metadata: js.UndefOr[js.Dictionary[String]] = js.undefined,
) extends js.Object

/** `OperationType` (`types/Operation.type.ts`): one entry of a `state.transaction` call. */
private[internal] final class StateTransactionOperation(
    val operation: String,
    val request: StateTransactionRequest,
) extends js.Object

/** `IRequest` (`types/Request.type.ts`): the key/value/etag payload of a transaction operation. */
private[internal] final class StateTransactionRequest(
    val key: String,
    val value: js.UndefOr[js.Any] = js.undefined,
    val etag: js.UndefOr[String] = js.undefined,
) extends js.Object

/** One item of the raw sidecar response to `POST /v1.0/state/{store}/bulk` (and of `query().results`): the SDK passes
  * the parsed JSON `[{key, data, etag}]` through verbatim. `data` is absent for missing keys.
  */
@js.native
private[internal] trait BulkStateItem extends js.Object:
  def key: String = js.native
  def data: js.UndefOr[js.Any] = js.native
  def etag: js.UndefOr[String] = js.native

/** `StateQueryResponseType`: `{results: [{key, data, etag}]}`; the SDK substitutes `{results: []}` for an empty body
  * (`implementation/Client/HTTPClient/state.js` `query`).
  */
@js.native
private[internal] trait StateQueryResponse extends js.Object:
  def results: js.UndefOr[js.Array[BulkStateItem]] = js.native

/** Soft-failure response shape shared by `state.save`, `state.delete` (`StateSaveResponseType`) and `pubsub.publish`
  * (`PubSubPublishResponseType`): the SDK catches the rejected `Error` and returns it as `{error}` instead of
  * rethrowing (`implementation/Client/HTTPClient/{state,pubsub}.js`).
  */
@js.native
private[internal] trait SoftFailureResponse extends js.Object:
  def error: js.UndefOr[js.Error] = js.native

// ---------------------------------------------------------------------------
// pubsub (interfaces/Client/IClientPubSub.ts)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait PubSubClient extends js.Object:
  def publish(
      pubSubName: String,
      topic: String,
      data: js.Any,
      options: PubSubPublishOptions,
  ): js.Promise[SoftFailureResponse] = js.native
  def publishBulk(
      pubSubName: String,
      topic: String,
      messages: js.Array[PubSubBulkPublishMessage],
  ): js.Promise[PubSubBulkPublishResponse] = js.native

/** `PubSubPublishOptions` (`types/pubsub/PubSubPublishOptions.type.ts`). */
private[internal] final class PubSubPublishOptions(
    val contentType: js.UndefOr[String] = js.undefined,
    val metadata: js.UndefOr[js.Dictionary[String]] = js.undefined,
) extends js.Object

/** Explicit-entry form of `PubSubBulkPublishMessage` (`types/pubsub/PubSubBulkPublishMessage.type.ts`); passing the
  * explicit `{entryID, event, contentType, metadata}` shape (detected via `"event" in message`, `utils/Client.util.js`
  * `getBulkPublishEntries`) keeps our entry IDs and content type authoritative.
  */
private[internal] final class PubSubBulkPublishMessage(
    val entryID: String,
    val event: js.Any,
    val contentType: js.UndefOr[String] = js.undefined,
) extends js.Object

/** `PubSubBulkPublishResponse` (`types/pubsub/PubSubBulkPublishResponse.type.ts`). */
@js.native
private[internal] trait PubSubBulkPublishResponse extends js.Object:
  def failedMessages: js.Array[PubSubBulkPublishFailedMessage] = js.native

@js.native
private[internal] trait PubSubBulkPublishFailedMessage extends js.Object:
  def message: PubSubBulkPublishMessage = js.native
  def error: js.Error = js.native

// ---------------------------------------------------------------------------
// binding / invoker / secret (interfaces/Client/IClient{Binding,Invoker,Secret}.ts)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait BindingClient extends js.Object:
  def send(bindingName: String, operation: String, data: js.Any, metadata: js.Dictionary[String]): js.Promise[js.Any] =
    js.native

@js.native
private[internal] trait InvokerClient extends js.Object:
  def invoke(
      appId: String,
      methodName: String,
      method: String,
      data: js.UndefOr[js.Any],
      options: InvokerOptions,
  ): js.Promise[js.Any] = js.native

/** `InvokerOptions` (`types/InvokerOptions.type.ts`): extra HTTP headers for the invocation. */
private[internal] final class InvokerOptions(
    val headers: js.UndefOr[js.Dictionary[String]] = js.undefined,
) extends js.Object

@js.native
private[internal] trait SecretClient extends js.Object:
  /** `metadata` is a pre-rendered query string (e.g. `"metadata.version_id=15"`), appended verbatim after `?` — see
    * `implementation/Client/HTTPClient/secret.js`.
    */
  def get(secretStoreName: String, key: String, metadata: String): js.Promise[js.Any] = js.native
  def get(secretStoreName: String, key: String): js.Promise[js.Any] = js.native
  def getBulk(secretStoreName: String): js.Promise[js.Any] = js.native

// ---------------------------------------------------------------------------
// configuration (gRPC-only: implementation/Client/GRPCClient/configuration.js;
// the HTTP implementation throws HTTPNotSupportedError)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait ConfigurationClient extends js.Object:
  def get(
      storeName: String,
      keys: js.Array[String],
      metadata: js.Dictionary[String],
  ): js.Promise[GetConfigurationResponse] = js.native
  def subscribeWithMetadata(
      storeName: String,
      keys: js.Array[String],
      metadata: js.Dictionary[String],
      cb: js.Function1[SubscribeConfigurationResponse, js.Promise[Unit]],
  ): js.Promise[ConfigurationSubscription] = js.native

/** `GetConfigurationResponse` / `SubscribeConfigurationResponse`: both are `{items: {[key]: ConfigurationItem}}`. */
@js.native
private[internal] trait GetConfigurationResponse extends js.Object:
  def items: js.Dictionary[ConfigurationItemJs] = js.native

@js.native
private[internal] trait SubscribeConfigurationResponse extends js.Object:
  def items: js.Dictionary[ConfigurationItemJs] = js.native

/** `types/configuration/ConfigurationItem.d.ts`, built by `createConfigurationType` (`utils/Client.util.js`) from the
  * protobuf response. `value`/`version` are typed defensively as optional: proto3 string defaults make them `""` in
  * practice, mirroring how the JVM impl treats `null` as `""`.
  */
@js.native
private[internal] trait ConfigurationItemJs extends js.Object:
  def value: js.UndefOr[String] = js.native
  def version: js.UndefOr[String] = js.native
  def metadata: js.UndefOr[js.Dictionary[String]] = js.native

/** `SubscribeConfigurationStream`: handle returned by `subscribe*`; `stop()` is an async arrow function (it aborts the
  * stream and sends the explicit unsubscribe call), hence the `js.Promise[Unit]` result.
  */
@js.native
private[internal] trait ConfigurationSubscription extends js.Object:
  def stop(): js.Promise[Unit] = js.native

// ---------------------------------------------------------------------------
// lock (interfaces/Client/IClientLock.ts; v1.0-alpha1 HTTP endpoints)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait LockClient extends js.Object:
  def lock(
      storeName: String,
      resourceId: String,
      lockOwner: String,
      expiryInSeconds: Int,
  ): js.Promise[LockResponse] = js.native
  def unlock(storeName: String, resourceId: String, lockOwner: String): js.Promise[UnlockResponse] = js.native

@js.native
private[internal] trait LockResponse extends js.Object:
  def success: js.UndefOr[Boolean] = js.native

/** `UnlockResponse`: `status` is the numeric `LockStatus` enum — Success = 0, LockDoesNotExist = 1, LockBelongsToOthers =
  * 2, InternalError = 3 (`implementation/Client/HTTPClient/lock.js` `_statusToLockStatus`).
  */
@js.native
private[internal] trait UnlockResponse extends js.Object:
  def status: js.UndefOr[Int] = js.native

// ---------------------------------------------------------------------------
// crypto (gRPC-only: implementation/Client/GRPCClient/crypto.js; the HTTP
// implementation throws HTTPNotSupportedError)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait CryptoClient extends js.Object:
  /** The buffered overload: passing `inData` (any `ArrayBufferView` is accepted by the SDK's `toArrayBuffer`) makes
    * `processStream` collect the response stream into a single Node `Buffer` (a `Uint8Array` subclass). The
    * zero-`inData` Duplex-stream overload is deliberately not facaded.
    */
  def encrypt(inData: js.typedarray.ArrayBufferView, opts: EncryptRequest): js.Promise[js.typedarray.Uint8Array] =
    js.native
  def decrypt(inData: js.typedarray.ArrayBufferView, opts: DecryptRequest): js.Promise[js.typedarray.Uint8Array] =
    js.native

/** `EncryptRequest` (`types/crypto/Requests.ts`); `keyWrapAlgorithm` is a TS string union, plain `String` at runtime.
  */
private[internal] final class EncryptRequest(
    val componentName: String,
    val keyName: String,
    val keyWrapAlgorithm: String,
) extends js.Object

/** `DecryptRequest` (`types/crypto/Requests.ts`): the ciphertext embeds the key reference, so only the component is
  * required — same contract the JVM impl documents on `CryptoCapabilityImpl.decrypt`.
  */
private[internal] final class DecryptRequest(
    val componentName: String,
) extends js.Object

// ---------------------------------------------------------------------------
// health (interfaces/Client/IClientHealth.ts) — declared for completeness of
// the client seam; dapr4s does not currently call it (the SDK's sub-clients
// await sidecar health themselves on first use)
// ---------------------------------------------------------------------------

@js.native
private[internal] trait HealthClient extends js.Object:
  def isHealthy(): js.Promise[Boolean] = js.native

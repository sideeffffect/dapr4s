# Dapr JavaScript SDK (@dapr/dapr) — API map for Scala.js facades

> Source: github.com/dapr/js-sdk @ a3be700 (= npm v3.18.0, published 2026-06-10) — full source survey of src/index.ts, implementation/{Client,Server}, interfaces, types, actors, workflow, utils, errors; npm registry metadata + 3.18.0 tarball listing; v3.17.0/v3.18.0 release notes; docs.dapr.io JS SDK pages
> Collected: 2026-06-11
> Published: Unknown

All findings verified against actual source at `dapr/js-sdk` HEAD (`a3be700`, 2026-06-10), which corresponds to the just-published **v3.18.0**. File paths below are relative to the repo root.

## 1. Version, runtime targeting, module system

- **Latest npm version: 3.18.0**, published 2026-06-10 (verified via npm registry). Previous: 3.17.0 (2026-04-23), 3.6.1, 3.5.2…
- **Versioning policy** (from the v3.17.0 release notes): major stays `3`; **the minor now tracks the Dapr runtime minor** ("first release since the 1.17 release of the Dapr runtime, it's released as 3.17.0"). E2E CI defaults to **Dapr runtime 1.16.12** with 1.17.x supported (`.github/workflows/test-e2e-testcontainers.yml`).
- **Node `>=18.0.0`** (`engines` in published package.json). Node-only — depends on `node-fetch@2`, `express@4`, `http`, `http2`, `stream`; no browser build.
- **CommonJS only.** `tsconfig.json`: `module: "commonjs"`, `target: "ES2022"`, `declaration: true`. v3.18.0 explicitly switched generated protos "to emit CommonJS modules instead of ESM" (PR #826). **No `exports` map, no `main` field** in the published package.json; compiled files sit at the **package root** (e.g. `package/index.js`, `package/actors/ActorId.js`, `package/workflow/runtime/WorkflowRuntime.js` — verified by listing the 3.18.0 tarball, 603 files), so Node resolves `index.js` by default. `types: "./index.d.ts"`.
- **Single entry point**: everything is re-exported as **named exports** from `src/index.ts` (no `@dapr/dapr/workflow` subpath; deep requires like `@dapr/dapr/workflow/runtime/WorkflowRuntime` work only because there is no exports map — unsupported API).
- Runtime deps (3.18.0): `express ^4.18.2`, `body-parser`, `node-fetch ^2.6.7`, `http-terminator ^3.2.0`, `@grpc/grpc-js ^1.12.5` (used by the vendored durabletask worker/client), `@connectrpc/connect(+node,+web) ^2.x` + `@bufbuild/protobuf ^2.9` (new gRPC transport for DaprClient/DaprServer since 3.17), `google-protobuf`, `@js-temporal/polyfill ^0.3.0` (actor timer/reminder durations), `@dapr/durabletask-js ^1.0.0` (**leftover**: not imported anywhere in `src/` — durabletask was vendored into `src/workflow/internal/durabletask` in 3.17.0, PR #738; only tests/examples still import it).

Root exports (`src/index.ts`): `DaprClient`, `DaprServer`, `GRPCClient`, `HTTPClient`, `HttpMethod`, `AbstractActor`, `ActorId`, `ActorProxyBuilder`, `Temporal` (re-export of the polyfill!), `DaprClientOptions`, `LogLevel`, `LoggerOptions`, `LoggerService`, `ConsoleLoggerService`, `InvokerOptions`, `TypeDaprInvokerCallback`, `DaprInvokerCallbackContent`, `CommunicationProtocolEnum`, `DaprPubSubStatusEnum`, `PubSubBulkPublishMessage`, `StateConcurrencyEnum`, `StateConsistencyEnum`, `PubSubBulkPublishResponse`, `StateGetBulkOptions`, `DaprWorkflowClient`, `WorkflowActivityContext`, `WorkflowContext`, `WorkflowRuntime`, `TWorkflow`, `Task`, `WorkflowFailureDetails`, `WorkflowState`, `WorkflowRuntimeStatus`, `fromOrchestrationStatus`, `toOrchestrationStatus`.

## 2. DaprClient (`src/implementation/Client/DaprClient.ts`)

```ts
constructor(options: Partial<DaprClientOptions> = {})
static create(client: IClient): DaprClient
static awaitSidecarStarted(fn: () => Promise<boolean>, logger: Logger): Promise<void>
start(): Promise<void>;  stop(): Promise<void>;  getIsInitialized(): boolean
```

`DaprClientOptions` (`src/types/DaprClientOptions.ts`):
```ts
type DaprClientOptions = {
  daprHost: string;                              // default "127.0.0.1"
  daprPort: string;                              // STRING; default "3500" HTTP / "50001" gRPC
  communicationProtocol: CommunicationProtocolEnum; // default HTTP
  isKeepAlive?: boolean;                         // default true
  logger?: LoggerOptions;                        // { level?: LogLevel; service?: LoggerService }
  actor?: ActorRuntimeOptions;
  daprApiToken?: string;                         // sent as `dapr-api-token` header / gRPC metadata
  maxBodySizeMb?: number;                        // default 4
}
```
Env defaults (`src/utils/Settings.util.ts`): `DAPR_HTTP_PORT`, `DAPR_GRPC_PORT`, `DAPR_API_TOKEN`, `DAPR_HTTP_ENDPOINT`, `DAPR_GRPC_ENDPOINT`, `APP_ID`. **Note:** JSDoc claims a `DAPR_PROTOCOL` env var, but `Settings.getDefaultCommunicationProtocol()` returns the constant HTTP — no env override exists. Constructor throws `Error("DAPR_INCORRECT_SIDECAR_PORT")` on non-numeric port. The gRPC client (`src/implementation/Client/GRPCClient/GRPCClient.ts`) now uses **ConnectRPC** (`createGrpcTransport`/`createClient(Dapr, transport)`), with an interceptor injecting `dapr-api-token`.

Sub-clients (readonly fields; interfaces in `src/interfaces/Client/`):

| field | interface | methods (exact TS) |
|---|---|---|
| `state` | `IClientState` | `save(storeName: string, stateObjects: KeyValuePairType[], options?: StateSaveOptions): Promise<StateSaveResponseType>` · `get(storeName: string, key: string, options?: Partial<StateGetOptions>): Promise<KeyValueType | string>` · `getBulk(storeName: string, keys: string[], options?: StateGetBulkOptions): Promise<KeyValueType[]>` · `delete(storeName: string, key: string, options?: Partial<StateDeleteOptions>): Promise<StateSaveResponseType>` · `transaction(storeName: string, operations?: OperationType[], metadata?: IRequestMetadata | null): Promise<void>` · `query(storeName: string, query: StateQueryType): Promise<StateQueryResponseType>` |
| `pubsub` | `IClientPubSub` | `publish(pubSubName: string, topic: string, data?: object | string, options?: PubSubPublishOptions): Promise<PubSubPublishResponseType>` · `publishBulk(pubSubName: string, topic: string, messages: PubSubBulkPublishMessage[], metadata?: KeyValueType): Promise<PubSubBulkPublishResponse>` |
| `binding` | `IClientBinding` | `send(bindingName: string, operation: string, data: any, metadata?: object): Promise<object>` |
| `invoker` | `IClientInvoker` | `invoke(appId: string, methodName: string, method: HttpMethod, data?: object, options?: InvokerOptions): Promise<object>` (impl defaults `method = HttpMethod.GET`; `InvokerOptions = { headers?: KeyValueType }`) |
| `secret` | `IClientSecret` | `get(secretStoreName: string, key: string, metadata?: string): Promise<object>` · `getBulk(secretStoreName: string): Promise<object>` |
| `configuration` | `IClientConfiguration` | `get(storeName, keys?, metadata?): Promise<GetConfigurationResponse>` · `subscribe(storeName, cb): Promise<SubscribeConfigurationStream>` · `subscribeWithKeys(storeName, keys, cb)` · `subscribeWithMetadata(storeName, keys, metadata, cb)`; `cb: (res: SubscribeConfigurationResponse) => Promise<void>`, stream = `{ stop: () => void }`. **gRPC-only** — HTTP impl throws `HTTPNotSupportedError` (`src/implementation/Client/HTTPClient/configuration.ts`) |
| `lock` | `IClientLock` | `lock(storeName: string, resourceId: string, lockOwner: string, expiryInSeconds: number): Promise<LockResponse /* {success:boolean} */>` · `unlock(storeName, resourceId, lockOwner): Promise<UnlockResponse /* {status: LockStatus} */>`; HTTP uses `v1.0-alpha1` endpoints; `enum LockStatus { Success, LockDoesNotExist, LockBelongsToOthers, InternalError }` |
| `crypto` | `IClientCrypto` | overloaded: `encrypt(opts: EncryptRequest): Promise<Duplex>` / `encrypt(inData: Buffer|ArrayBuffer|ArrayBufferView|string, opts: EncryptRequest): Promise<Buffer>`; same shape for `decrypt`. **gRPC-only** (HTTP impl throws). `EncryptRequest = { componentName, keyName, keyWrapAlgorithm: "A256KW"|"A128CBC"|…|"RSA", dataEncryptionCipher?, omitDecryptionKeyName?, decryptionKeyName? }` (`src/types/crypto/Requests.ts`) |
| `workflow` | `IClientWorkflow` | `getWorkflowState(instanceId): Promise<WorkflowGetResponseType>` · `scheduleNewWorkflow(workflowName, input?, instanceId?): Promise<string>` · `terminate/pause/resume/purge(instanceId): Promise<void>` · `raiseEvent(instanceId, eventName, eventData?)`; deprecated aliases `get/start/raise` (renamed in 3.18.0, PR #783; aliases removed with Dapr 1.20). **HTTP-only** (uses `v1.0-beta1` HTTP API); gRPC impl throws `GRPCNotSupportedError` (`src/implementation/Client/GRPCClient/workflow.ts`). Prefer `DaprWorkflowClient` |
| `actor` | `IClientActorBuilder` | `create<T>(actorTypeClass: Class<T>): T` (wraps `ActorProxyBuilder`) |
| `proxy` | `IClientProxy` | `create<T>(cls: Class<T>, clientOptions?): Promise<T>` — gRPC-only (HTTP impl throws) |
| `metadata` | `IClientMetadata` | `get(): Promise<GetMetadataResponse>` · `set(key, value): Promise<boolean>` |
| `health` | `IClientHealth` | `isHealthy(): Promise<boolean>` |
| `sidecar` | `IClientSidecar` | `shutdown(): Promise<void>` |

Supporting types: `KeyValuePairType = { key: string; value: any; etag?: string; metadata?: KeyValueType; options?: IStateOptions }`; `KeyValueType = { [key: string]: any }`; `OperationType = { operation: string /* "upsert"|"delete" */; request: IRequest }` with `IRequest = { key: string; value?: any; etag?: IEtag; metadata?; options?: IStateOptions }`; `IStateOptions = { concurrency: StateConcurrencyEnum; consistency: StateConsistencyEnum }`; `PubSubPublishOptions = { contentType?: string; metadata?: KeyValueType }`; `StateGetBulkOptions = { parallelism?: number; metadata? }` (default parallelism 10).

**Missing vs Java SDK**: **Jobs** — no client API at all (no schedule/get/delete job); the gRPC server only has a **no-op stub** `onJobEventAlpha1(req, ctx): Promise<JobEventResponse>` returning an empty response (`src/implementation/Server/GRPCServer/GRPCServerImpl.ts:396`). **Conversation** — completely absent. **Streaming pub/sub subscriptions** from the client — absent (subscribe only via DaprServer). Crypto/configuration/proxy are gRPC-only; workflow client building block is HTTP-only.

## 3. DaprServer (`src/implementation/Server/DaprServer.ts`)

```ts
constructor(serverOptions: Partial<DaprServerOptions> = {})
start(): Promise<void>   // starts app server first, then client.start() (awaits sidecar)
stop(): Promise<void>
readonly pubsub: IServerPubSub; binding: IServerBinding; invoker: IServerInvoker;
readonly actor: IServerActor;  client: DaprClient;  daprServer: IServer;
```
`DaprServerOptions` (`src/types/DaprServerOptions.ts`): `{ serverHost: string /* default 127.0.0.1 */; serverPort: string /* default "3000" HTTP, "50000" gRPC */; communicationProtocol: CommunicationProtocolEnum; maxBodySizeMb?: number; serverHttp?: express.Express /* bring-your-own express app */; clientOptions?: Partial<DaprClientOptions>; logger?: LoggerOptions }`. Sets `process.env.DAPR_SERVER_PORT` / `DAPR_CLIENT_PORT`.

- **HTTP mode** (`src/implementation/Server/HTTPServer/HTTPServer.ts`): runs **express 4** with `body-parser` (text, raw for octet-stream, json incl. `application/cloudevents+json`); shutdown via `http-terminator`; serves `GET /dapr/subscribe` returning the programmatic subscription list — so **register subscriptions/handlers before `start()`**.
- **gRPC mode** (`src/implementation/Server/GRPCServer/GRPCServer.ts`): Node `http2.createServer` + ConnectRPC `connectNodeAdapter`, implementing `AppCallback` (`onInvoke`, `listTopicSubscriptions`, `onTopicEvent`, `listInputBindings`, `onBindingEvent`), `AppCallbackAlpha` (`onBulkTopicEventAlpha1`, `onJobEventAlpha1` stub) and `AppCallbackHealthCheck`.

Server interfaces (`src/interfaces/Server/`):
```ts
// IServerPubSub
subscribe(pubSubName: string, topic: string, cb: TypeDaprPubSubCallback,
          route?: string | DaprPubSubRouteType, metadata?: KeyValueType): Promise<void>;
subscribeWithOptions(pubsubName, topic, options: PubSubSubscriptionOptionsType): Promise<void>;
subscribeToRoute(pubsubName, topic, route: string | DaprPubSubRouteType, cb): void;
subscribeBulk(pubSubName, topic, cb, bulkSubscribeOptions?: BulkSubscribeOptions): Promise<void>;
getSubscriptions(): PubSubSubscriptionsType;
// callback:
type TypeDaprPubSubCallback = (data: any, headers: object) => Promise<any | void>;
// returning DaprPubSubStatusEnum.SUCCESS|RETRY|DROP controls ack; throw => RETRY precedence
// PubSubSubscriptionOptionsType = { metadata?; deadLetterTopic?; deadLetterCallback?; callback?; route?; bulkSubscribe?: BulkSubscribeConfig }
// DaprPubSubRouteType = { rules?: {match, path}[]; default?: string }

// IServerBinding
receive(bindingName: string, cb: TypeDaprBindingCallback): Promise<any>;
type TypeDaprBindingCallback = (data: any) => Promise<any | void>;   // HTTP: POST /<bindingName>

// IServerInvoker
listen(methodName: string, cb: DaprInvokerCallbackFunction, options?: InvokerListenOptionsType): Promise<any>;
type DaprInvokerCallbackFunction = (data: DaprInvokerCallbackContent) => Promise<any | void>;
interface DaprInvokerCallbackContent { body?: string; query?: string; metadata?: { contentType?: string }; headers?: KeyValueType }
type InvokerListenOptionsType = { method?: HttpMethod };  // default GET

// IServerActor
registerActor<T extends AbstractActor>(cls: Class<T>): Promise<void>;
getRegisteredActors(): Promise<string[]>;
init(): Promise<void>;   // MUST call before registerActor; registers actor HTTP routes
```
Actor hosting is **HTTP-only**: `GRPCServerActor` throws `GRPCNotSupportedError` (`src/implementation/Server/GRPCServer/actor.ts`). `HTTPServerActor.init()` registers `GET /healthz`, `GET /dapr/config`, `DELETE /actors/:type/:id`, `PUT /actors/:type/:id/method/:method`, `PUT .../method/timer/:timerName`, `PUT .../method/remind/:reminderName` (`src/implementation/Server/HTTPServer/actor.ts`).

## 4. Actors

**Client side**: `ActorId` (`src/actors/ActorId.ts`): `new ActorId(id: string)`, `static createRandomId(): ActorId`, `getId()`, `getURLSafeId()`, `toString()`. `ActorProxyBuilder<T>` (`src/actors/client/ActorProxyBuilder.ts`): overloaded ctor `(actorTypeClass: Class<T>, daprClient: DaprClient)` or `(actorTypeClass, host, port, communicationProtocol, clientOptions)`; `build(actorId: ActorId): T` returns a **JS `Proxy`** that forwards every property access as an async actor-method invocation with `body = args.length > 0 ? args : null`; the actor type string is **`actorTypeClass.name`**. Raw low-level client: `IClientActor` (`src/interfaces/Client/IClientActor.ts`) implemented by `ActorClientHTTP/GRPC`: `invoke(actorType, actorId, methodName, body?)`, `stateTransaction(actorType, actorId, operations)`, `stateGet(actorType, actorId, key)`, `registerActorReminder/unregisterActorReminder`, `registerActorTimer/unregisterActorTimer`, `getActors()`; `ActorReminderType/ActorTimerType = { period?: Temporal.Duration; dueTime?: Temporal.Duration; data?: any; ttl?: Temporal.Duration; callback: string /* timer only */ }`.

**Server side**: `AbstractActor` (`src/actors/runtime/AbstractActor.ts`): `constructor(daprClient: DaprClient, id: ActorId)`; lifecycle overrides `onActivate()`, `onDeactivate()`, `onActorMethodPre()`, `onActorMethodPost()`, `receiveReminder(data: string)`; helpers `registerActorReminder<_T>(reminderName, dueTime: Temporal.Duration, period?, ttl?, state?)`, `unregisterActorReminder(name)`, `registerActorTimer(timerName, callback: string /* method name */, dueTime, period?, ttl?, state?)`, `unregisterActorTimer(name)`; accessors `getStateManager<T>(): ActorStateManager<T>`, `getDaprClient()`, `getActorId()`, `getActorType()` (= `this.constructor.name`!). `ActorStateManager<T>` (`src/actors/runtime/ActorStateManager.ts`): `addState`, `tryAddState`, `getState(name): Promise<T|null>`, `tryGetState(name): Promise<[boolean, T|null]>`, `setState`, `removeState`, `tryRemoveState`, `containsState`, `getOrAddState`, `addOrUpdateState(name, value, updateValueFactory)`, `getStateNames`, `clearCache`, `saveState` (auto-called after each method via `onActorMethodPostInternal`). `ActorRuntime.registerActor<T extends AbstractActor>(actorCls: Class<T>): void` keys managers by **`actorCls.name`**. `ActorRuntimeOptions = { actorIdleTimeout?: string; actorScanInterval?: string; drainOngoingCallTimeout?: string; drainRebalancedActors?: boolean; reentrancy?: { enabled?: boolean; maxStackDepth?: number }; remindersStoragePartitions?: number }`.

## 5. Workflows (root import, no subpath; durabletask vendored in `src/workflow/internal/durabletask` since 3.17.0)

- **`DaprWorkflowClient`** (`src/workflow/client/DaprWorkflowClient.ts`) — talks **gRPC directly** to the sidecar via vendored `TaskHubGrpcClient` (@grpc/grpc-js): `constructor(options: Partial<WorkflowClientOptions> = {})` where `WorkflowClientOptions = { daprHost: string; daprPort: string; logger?: LoggerOptions; daprApiToken?: string; grpcOptions?: grpc.ChannelOptions }`. Methods: `scheduleNewWorkflow(workflow: TWorkflow | string, input?: any, instanceId?: string, startAt?: Date): Promise<string>`, `terminateWorkflow(id, output)`, `getWorkflowState(id, getInputsAndOutputs): Promise<WorkflowState | undefined>`, `waitForWorkflowStart(id, fetchPayloads = true, timeoutInSeconds = 60)`, `waitForWorkflowCompletion(id, fetchPayloads = true, timeoutInSeconds = 60)`, `raiseEvent(id, eventName, eventPayload?)`, `purgeWorkflow(id): Promise<boolean>`, `suspendWorkflow(id)`, `resumeWorkflow(id)`, `stop()`.
- **`WorkflowRuntime`** (`src/workflow/runtime/WorkflowRuntime.ts`): same ctor options; `registerWorkflow(workflow: TWorkflow): WorkflowRuntime`, `registerWorkflowWithName(name: string, workflow: TWorkflow)`, `registerActivity(fn: TWorkflowActivity<TInput, TOutput>)`, `registerActivityWithName(name, fn)`, `start()`, `stop()` (wraps vendored `TaskHubGrpcWorker`). Name resolution uses `getFunctionName(fn)` = `fn.name` — **use the `*WithName` variants from Scala.js**.
- **Authoring model**: `type TWorkflow = (context: WorkflowContext, input: any) => Generator<Task<any>, any, any> | TOutput` — in practice an **`async function*` (async generator) yielding `Task` objects**; the executor checks `typeof result?.[Symbol.asyncIterator] === "function"` and drives it via `await generator.next(prevResult)` (`src/workflow/internal/durabletask/worker/orchestration-executor.ts:145`, `runtime-orchestration-context.ts:84-145`). Non-generator return values complete the workflow immediately. Activities: `type TWorkflowActivity<TInput, TOutput> = (context: WorkflowActivityContext, input: TInput) => TOutput` (may return a Promise).
- **`WorkflowContext`** (`src/workflow/runtime/WorkflowContext.ts`): `getWorkflowInstanceId(): string`, `getCurrentUtcDateTime(): Date`, `isReplaying(): boolean`, `createTimer(fireAt: Date | number /* seconds */): Task<any>`, `callActivity(activity: TWorkflowActivity<TInput,TOutput> | string, input?): Task<TOutput>`, `callSubWorkflow<TI,TO>(orchestrator: TWorkflow | string, input?, instanceId?): Task<TO>` (alias `callChildWorkflow`), `waitForExternalEvent(name: string): Task<any>`, `continueAsNew(newInput: any, saveEvents: boolean): void`, `setCustomStatus(status: string): void`, `whenAll<T>(tasks: Task<T>[]): WhenAllTask<T>`, `whenAny(tasks: Task<any>[]): WhenAnyTask`.
- **`WorkflowActivityContext`**: `getWorkflowInstanceId(): string`, `getWorkflowActivityId(): number`. **`WorkflowState`** (getters): `name`, `instanceId`, `runtimeStatus: WorkflowRuntimeStatus`, `createdAt`, `lastUpdatedAt`, `serializedInput?`, `serializedOutput?`, `workflowFailureDetails?: WorkflowFailureDetails` (`getErrorType()`, `getErrorMessage()`, `getStackTrace()`), `customStatus?`. `enum WorkflowRuntimeStatus { RUNNING, COMPLETED, FAILED, TERMINATED, CONTINUED_AS_NEW, PENDING, SUSPENDED }` (numeric, from OrchestrationStatus). `Task<T>` public surface: `get isComplete: boolean`, `get isFailed: boolean`, `getResult(): T`, `getException(): TaskFailedError`.
- Inputs/outputs are **JSON-serialized strings** (`JSON.parse(rawInput)` in the executor).

## 6. Error surfacing

- HTTP client (`HTTPClient.execute`, `src/implementation/Client/HTTPClient/HTTPClient.ts`): non-2xx/3xx → **rejects with plain `Error` whose message is `JSON.stringify({ error: statusText, error_msg: bodyText, status: number })`**. No typed error hierarchy for API errors.
- gRPC client: rejections are ConnectRPC `ConnectError`s (from `@connectrpc/connect`).
- Soft-failure responses instead of rejections: `pubsub.publish` returns `{ error?: Error }` (`PubSubPublishResponseType`); `state.save`/`delete` return `StateSaveResponseType = { error?: Error }`; `publishBulk` returns `{ failedMessages: { message, error }[] }`.
- Typed errors (`src/errors/`): `GRPCNotSupportedError`, `HTTPNotSupportedError` (protocol-unsupported building blocks), `PropertyRequiredError`. Sidecar startup timeout: `Error("DAPR_SIDECAR_COULD_NOT_BE_STARTED")` after ~60 retries × 500ms. Workflow activity failures surface inside workflows as `TaskFailedError` via `Task.getException()` and on the client as `WorkflowFailureDetails`.

## 7. Serialization (`src/utils/Serializer.util.ts`, `Deserializer.util.ts`, `Client.util.ts:getContentType`)

- Content type is **inferred from the JS value** unless overridden: `Object`/`Array` → `application/json` (or `application/cloudevents+json` if it looks like a CloudEvent), `Boolean`/`Number`/`String` → `text/plain`, binary (Buffer/TypedArray) → `application/octet-stream`.
- JSON content types are encoded via `JSON.stringify`; text via `toString()`; binary passed/Buffered raw. Deserialization always **tries `JSON.parse` first** and falls back to the raw string (`tryParseJson`). So `state.get` returns parsed JSON (`KeyValueType`) or a string.
- State save POSTs the JSON array of `{key, value, etag, options}`; metadata goes into **query params** on HTTP (`createHTTPQueryParam`). PubSub publish: `options.contentType` overrides; metadata as query params. Actor method payloads use `BufferSerializer` (JSON in Buffers). Workflow inputs/outputs: JSON strings.

## 8. Scala.js facade-writing notes / oddities

- **All classes are TS `export default`** in their files but **re-exported as named exports from the root**, so facades can use `@JSImport("@dapr/dapr", "DaprClient")` etc. The `IClient*`/`IServer*` interfaces and all `*.type.ts` types are **TypeScript-only** (erased) — model as structural `js.Object` traits.
- **CJS, Node-only** (node-fetch v2, express, http2, `stream.Duplex`, `Buffer`); fine for Scala.js-on-Node with ESModule or CommonJS module kind (`import` of CJS works under Node ESM interop since they're named props of the default export — safest is `ModuleKind.CommonJSModule` or `ESModule` with default-import interop).
- **Enums**: `CommunicationProtocolEnum` is **numeric with `GRPC = 0`, `HTTP = 1`** (careless facades defaulting to 0 would pick gRPC!). `HttpMethod` is lowercase strings (`"get"`, …). `DaprPubSubStatusEnum` is strings (`"SUCCESS" | "RETRY" | "DROP"`). `StateConcurrencyEnum`/`StateConsistencyEnum` numeric 0/1/2. `LockStatus` numeric.
- **Ports are strings**, not numbers, everywhere (`daprPort: string`, `serverPort: string`).
- **Constructor overloads**: `ActorProxyBuilder` (2 forms), crypto `encrypt`/`decrypt` (2 overloads each). Options objects are `Partial<...>` — every facade field should be optional/`js.UndefOr`.
- **Class-name reflection hazards**: actor type = `actorTypeClass.name` (proxy builder) and `this.constructor.name` (AbstractActor); workflow/activity registration uses `fn.name`. Scala.js class/lambda names are mangled or minified — for actors you must control the JS class `name` (e.g. define facade-level JS classes or set `static name`), and for workflows always use `registerWorkflowWithName`/`registerActivityWithName` and pass string names to `scheduleNewWorkflow`/`callActivity` (string overloads exist everywhere).
- **Workflow authoring requires an async-generator**: the executor demands `result[Symbol.asyncIterator]` and drives `next(prevResult)`. Scala.js cannot write `async function*`, so a facade must hand-implement the AsyncGenerator protocol (object with `next(v): js.Promise<IteratorResult>` + `[Symbol.asyncIterator]`) — feasible, but it is the single hardest piece; alternatively drive workflows from a small JS shim, or only support task-free workflows (plain return) which the executor also accepts.
- **Server-side actors extend `AbstractActor`** — Scala.js classes can extend JS classes, but method dispatch happens by JS property name (`ActorRuntime.invoke(actorTypeName, actorId, methodName, body)`), so actor methods must be `@JSExport`-visible with stable names.
- **Temporal**: actor reminders/timers take `Temporal.Duration` from `@js-temporal/polyfill` (re-exported as `Temporal` from the package root) — needs a tiny facade (`Temporal.Duration.from({ seconds: … })`).
- **Ordering**: HTTP `DaprServer` requires all `pubsub.subscribe` / `binding.receive` / `invoker.listen` / `actor.init()+registerActor` calls **before** `server.start()`; `server.actor.init()` must precede `registerActor`. Actors and `client.workflow` are HTTP-only; configuration/crypto/proxy are gRPC-only — a dapr4s capability matrix must encode per-protocol support.
- `invoker.listen` callback receives `body` as a **string** (`JSON.stringify(req.body)`), `query` as the original URL — re-parse on the Scala side.
- No `exports` map → deep imports possible but unstable; stick to root exports.
- Missing building blocks to implement differently or skip in dapr4s-js: **jobs** (no API; only an empty `onJobEventAlpha1` gRPC stub), **conversation** (absent), client-side streaming pubsub subscriptions (absent).

### Primary sources
- npm registry metadata + 3.18.0 tarball listing (registry.npmjs.org/@dapr/dapr)
- github.com/dapr/js-sdk @ a3be700: `src/index.ts`, `src/implementation/Client/{DaprClient,HTTPClient/*,GRPCClient/*}.ts`, `src/implementation/Server/{DaprServer,HTTPServer/*,GRPCServer/*}.ts`, `src/interfaces/{Client,Server}/*.ts`, `src/types/**`, `src/actors/**`, `src/workflow/**`, `src/utils/{Serializer,Deserializer,Client,Settings}.util.ts`, `src/errors/*`
- Release notes v3.17.0 (versioning policy, durabletask vendoring PR #738) and v3.18.0 (CJS protos PR #826, workflow method renames PR #783)
- docs.dapr.io/developing-applications/sdks/js/ (building-block overview)

## VERDICT
The Dapr JS SDK (@dapr/dapr 3.18.0, published 2026-06-10, Node >=18, CommonJS-only TypeScript with named root exports and no exports map) is a viable Scala.js facade target for most dapr4s capabilities: DaprClient exposes state, pubsub publish, output bindings, service invocation, secrets, metadata/health/sidecar over both HTTP and gRPC, plus configuration/crypto/proxy (gRPC-only) and a deprecated-style workflow management client (HTTP-only); DaprServer (express for HTTP, http2+ConnectRPC for gRPC) covers pubsub subscribe, input bindings, invocation listeners, and actor hosting (HTTP-only); actors (ActorId/ActorProxyBuilder/AbstractActor/ActorStateManager) and workflows (DaprWorkflowClient/WorkflowRuntime/WorkflowContext, durabletask vendored in-package) are fully present. The hard spots for Scala.js are: workflow bodies must be JS async generators yielding Task objects (requires hand-implementing the AsyncGenerator protocol or a JS shim), actor/workflow registration relies on JS class/function `.name` reflection (use explicit-name registration variants and controlled JS class names), CommunicationProtocolEnum is numeric with GRPC=0, ports are strings, and the SDK is missing jobs (only a no-op gRPC stub), conversation, and client-side streaming subscriptions entirely — those dapr4s capabilities cannot be backed by this SDK and would need raw HTTP/gRPC implementations or omission on the JS platform.

## BLOCKERS
- Jobs building block has no client API in the JS SDK (only an empty onJobEventAlpha1 gRPC server stub) — dapr4s jobs capability cannot be delegated to @dapr/dapr
- Conversation building block is entirely absent from the JS SDK
- Workflow authoring requires JS async generator functions (async function* yielding Task) driven via Symbol.asyncIterator — Scala.js cannot author these natively; a hand-rolled AsyncGenerator protocol implementation or JS shim is required
- Per-protocol gaps: actors server-side and workflow management client are HTTP-only; configuration, crypto, and proxy are gRPC-only — a single DaprClient protocol choice cannot serve all building blocks
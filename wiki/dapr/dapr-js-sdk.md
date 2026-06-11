# Dapr JS SDK (@dapr/dapr)

> Sources: dapr/js-sdk source @ a3be700 (= v3.18.0, published 2026-06-10), npm registry, v3.17.0/v3.18.0 release notes, 2026-06-11
> Raw: [dapr-js-sdk source survey](../../raw/dapr/2026-06-11-dapr-js-sdk-source-survey.md)
> Updated: 2026-06-11

## Overview

`@dapr/dapr` is the official Dapr SDK for Node.js (current: **3.18.0**). Unlike the reactive [Dapr Java SDK](dapr-java-sdk.md), it is Promise-based: `DaprClient` (outbound, HTTP or gRPC), `DaprServer` (inbound: pubsub subscriptions, input bindings, invocation listeners, actor hosting), actors (`ActorId`/`ActorProxyBuilder`/`AbstractActor`), and workflows (`DaprWorkflowClient`/`WorkflowRuntime`, with durabletask **vendored** into `src/workflow/internal/durabletask` since 3.17.0). It is the substrate for dapr4s's Scala.js platform (facades + [orphan js.await bridging](../scala-js/scala-js-async-jspi-wasm.md)).

## Version, runtime targeting, module system

- **Versioning policy** (since v3.17.0): major stays `3`; **the minor tracks the Dapr runtime minor** (3.17.0 ↔ runtime 1.17).
- **Node `>= 18.0.0`**, Node-only (node-fetch@2, express@4, `http2`, `stream`; no browser build).
- **CommonJS only** (`module: "commonjs"`, target ES2022; v3.18.0 switched generated protos back to CJS, PR #826). **No `exports` map, no `main` field**; compiled files sit at the package root, so Node resolves `index.js`. Everything is re-exported as **named exports from the root** (`src/index.ts`) — facades use `@JSImport("@dapr/dapr", "DaprClient")` etc. Deep requires work only because there's no exports map — unsupported; stick to root exports.
- Root exports include: `DaprClient`, `DaprServer`, `AbstractActor`, `ActorId`, `ActorProxyBuilder`, `Temporal` (re-export of `@js-temporal/polyfill`), `CommunicationProtocolEnum`, `HttpMethod`, `DaprWorkflowClient`, `WorkflowRuntime`, `WorkflowContext`, `WorkflowActivityContext`, `WorkflowState`, `WorkflowRuntimeStatus`, `TWorkflow`, `Task`, `LogLevel`, etc. The `IClient*`/`IServer*` interfaces and `*.type.ts` types are TypeScript-only (erased) — model as structural `js.Object` traits.
- gRPC transport since 3.17 is **ConnectRPC** (`@connectrpc/connect-node` + `@bufbuild/protobuf`); the vendored durabletask worker/client still uses `@grpc/grpc-js`.

## DaprClient

```ts
new DaprClient(options: Partial<DaprClientOptions> = {})
start(): Promise<void>;  stop(): Promise<void>;  getIsInitialized(): boolean
```

`DaprClientOptions`: `daprHost` (default `"127.0.0.1"`), **`daprPort: string`** (STRING — default `"3500"` HTTP / `"50001"` gRPC), `communicationProtocol: CommunicationProtocolEnum` (default HTTP), `isKeepAlive?`, `logger?: LoggerOptions`, `actor?: ActorRuntimeOptions`, `daprApiToken?` (sent as `dapr-api-token`), `maxBodySizeMb?` (default 4). Env defaults: `DAPR_HTTP_PORT`, `DAPR_GRPC_PORT`, `DAPR_API_TOKEN`, `DAPR_HTTP_ENDPOINT`, `DAPR_GRPC_ENDPOINT`, `APP_ID` (JSDoc mentions `DAPR_PROTOCOL` but no env override actually exists). Non-numeric port → `Error("DAPR_INCORRECT_SIDECAR_PORT")`.

Sub-clients (readonly fields) and key signatures:

| field | methods |
|---|---|
| `state` | `save(storeName, stateObjects: KeyValuePairType[], options?)` · `get(storeName, key, options?): Promise<KeyValueType \| string>` · `getBulk(storeName, keys, options?)` · `delete(storeName, key, options?)` · `transaction(storeName, operations?, metadata?)` · `query(storeName, query)` |
| `pubsub` | `publish(pubSubName, topic, data?, options?)` · `publishBulk(pubSubName, topic, messages, metadata?)` |
| `binding` | `send(bindingName, operation, data, metadata?)` |
| `invoker` | `invoke(appId, methodName, method: HttpMethod, data?, options?)` (default GET) |
| `secret` | `get(secretStoreName, key, metadata?)` · `getBulk(secretStoreName)` |
| `configuration` | `get` · `subscribe`/`subscribeWithKeys`/`subscribeWithMetadata` (cb gets `SubscribeConfigurationResponse`; stream = `{ stop: () => void }`) — **gRPC-only** |
| `lock` | `lock(storeName, resourceId, lockOwner, expiryInSeconds): Promise<{success: boolean}>` · `unlock(...): Promise<{status: LockStatus}>` (HTTP uses `v1.0-alpha1`) |
| `crypto` | `encrypt`/`decrypt`, overloaded: `(opts: EncryptRequest) => Promise<Duplex>` or `(inData: Buffer\|..., opts) => Promise<Buffer>` — **gRPC-only** |
| `workflow` | `scheduleNewWorkflow` · `getWorkflowState` · `terminate/pause/resume/purge` · `raiseEvent` (renamed in 3.18.0, PR #783; old `get/start/raise` deprecated) — **HTTP-only** (`v1.0-beta1` API); prefer `DaprWorkflowClient` |
| `actor` | `create<T>(actorTypeClass): T` (wraps `ActorProxyBuilder`) |
| `proxy` | `create<T>(cls, clientOptions?): Promise<T>` — **gRPC-only** |
| `metadata` / `health` / `sidecar` | `get()`/`set(key, value)` · `isHealthy()` · `shutdown()` |

## Per-protocol support matrix

| Building block | HTTP | gRPC |
|---|---|---|
| state, pubsub publish, output bindings, invoke, secrets, lock, metadata/health/sidecar | yes | yes |
| `configuration`, `crypto`, `proxy` | **no** (`HTTPNotSupportedError`) | yes |
| `client.workflow` management | yes | **no** (`GRPCNotSupportedError`) |
| actor **hosting** (`DaprServer.actor`) | yes | **no** (`GRPCNotSupportedError`) |

A single client protocol choice cannot serve all building blocks — dapr4s's JS layer holds an HTTP `DaprClient` plus a lazy gRPC one (configuration/crypto) plus a `DaprWorkflowClient` (own gRPC).

## DaprServer

```ts
new DaprServer(serverOptions: Partial<DaprServerOptions> = {})
start(); stop()   // start(): app server first, then client.start() (awaits sidecar)
readonly pubsub, binding, invoker, actor;  client: DaprClient
```

`DaprServerOptions`: `serverHost` (default 127.0.0.1), **`serverPort: string`** (default `"3000"` HTTP / `"50000"` gRPC), `communicationProtocol`, `maxBodySizeMb?`, **`serverHttp?: express.Express`** (bring-your-own express app — dapr4s's hook for extra routes), `clientOptions?`, `logger?`.

- **HTTP mode**: express 4 + body-parser (text, raw octet-stream, json incl. `application/cloudevents+json`); serves `GET /dapr/subscribe` from the programmatic subscription list — so **register all subscriptions/handlers before `start()`**.
- **gRPC mode**: Node `http2.createServer` + ConnectRPC `connectNodeAdapter` implementing `AppCallback` (`onInvoke`, `listTopicSubscriptions`, `onTopicEvent`, `listInputBindings`, `onBindingEvent`) + `AppCallbackAlpha` (`onBulkTopicEventAlpha1`, no-op `onJobEventAlpha1`).

Server interfaces:

```ts
// IServerPubSub
subscribe(pubSubName, topic, cb: (data, headers) => Promise<any|void>, route?, metadata?)
subscribeWithOptions(pubsubName, topic, { metadata?, deadLetterTopic?, deadLetterCallback?, callback?, route?, bulkSubscribe? })
subscribeToRoute(pubsubName, topic, route /* string | {rules: {match,path}[], default} */, cb)
subscribeBulk(...);  getSubscriptions()
// return DaprPubSubStatusEnum.SUCCESS|RETRY|DROP ("SUCCESS"/"RETRY"/"DROP" strings); throw => RETRY

// IServerBinding
receive(bindingName, cb: (data) => Promise<any|void>)        // HTTP: POST /<bindingName>

// IServerInvoker
listen(methodName, cb: (data: DaprInvokerCallbackContent) => Promise<any|void>, { method?: HttpMethod })
// DaprInvokerCallbackContent: { body?: string /* JSON.stringify'd — re-parse! */; query?; metadata?; headers? }

// IServerActor
init(): Promise<void>;                       // MUST precede registerActor; registers actor HTTP routes
registerActor<T extends AbstractActor>(cls); getRegisteredActors()
```

`HTTPServerActor.init()` registers `GET /healthz`, `GET /dapr/config`, `DELETE /actors/:type/:id`, `PUT /actors/:type/:id/method/:method`, `PUT .../method/timer/:timerName`, `PUT .../method/remind/:reminderName` — the same app-channel protocol dapr4s's JVM `DaprAppServer` implements.

## Actors

- **`ActorId`**: `new ActorId(id)`, `ActorId.createRandomId()`, `getId()`, `getURLSafeId()`.
- **`ActorProxyBuilder<T>`**: ctor `(actorTypeClass, daprClient)` or `(actorTypeClass, host, port, communicationProtocol, clientOptions)`; `build(actorId): T` returns a **JS `Proxy`** forwarding every property access as an actor-method invocation. **Class-name reflection hazard:** the actor type string is **`actorTypeClass.name`** (and server-side `this.constructor.name`) — Scala.js class names are mangled/minified, so you must control the JS class `name` or bypass the proxy with raw sidecar HTTP (dapr4s mirrors its JVM `HttpActorContext` precedent).
- Low-level `IClientActor` (ActorClientHTTP/GRPC): `invoke(actorType, actorId, methodName, body?)`, `stateTransaction`, `stateGet`, `registerActorReminder/Timer` + unregister, `getActors()`. Reminder/timer durations are **`Temporal.Duration`** from the re-exported polyfill.
- **`AbstractActor`** (server): `constructor(daprClient, id)`; overrides `onActivate/onDeactivate/onActorMethodPre/onActorMethodPost/receiveReminder(data)`; helpers `registerActorReminder/Timer(...)`; `getStateManager<T>(): ActorStateManager<T>`. `ActorStateManager`: `getState`, `tryGetState(name): Promise<[boolean, T|null]>`, `setState`, `removeState`, `getOrAddState`, `addOrUpdateState`, `getStateNames`, `saveState` (auto-called after each method). Method dispatch is by JS property name — Scala.js actor methods need `@JSExport`-visible stable names.

## Workflows

- **`DaprWorkflowClient`** — talks gRPC **directly** to the sidecar via the vendored `TaskHubGrpcClient`: `scheduleNewWorkflow(workflow | name, input?, instanceId?, startAt?)`, `waitForWorkflowStart/Completion(id, fetchPayloads = true, timeoutInSeconds = 60)`, `getWorkflowState(id, getInputsAndOutputs)`, `terminateWorkflow`, `raiseEvent`, `purgeWorkflow`, `suspendWorkflow`, `resumeWorkflow`, `stop()`.
- **`WorkflowRuntime`**: `registerWorkflow(fn)`, `registerActivity(fn)`, `start()`, `stop()`. Name resolution uses **`fn.name`** — from Scala.js always use **`registerWorkflowWithName(name, fn)` / `registerActivityWithName(name, fn)`** and pass string names to `scheduleNewWorkflow`/`callActivity`.
- **Authoring model**: `TWorkflow = (context: WorkflowContext, input) => Generator<Task> | TOutput` — in practice an **`async function*` yielding `Task` objects**; the executor checks `result[Symbol.asyncIterator]` and drives `generator.next(prevResult)`. Plain (non-generator) return values complete the workflow immediately. **Scala.js cannot write `async function*`** — a facade must hand-implement the AsyncGenerator protocol (`next(v): js.Promise<IteratorResult>` + `[Symbol.asyncIterator]`); this is the hardest interop piece (dapr4s bridges it with a coroutine over two Promises inside `js.async`).
- **`WorkflowContext`**: `getWorkflowInstanceId`, `getCurrentUtcDateTime`, `isReplaying`, `createTimer(fireAt: Date | seconds)`, `callActivity(activity | name, input?)`, `callSubWorkflow`, `waitForExternalEvent(name)`, `continueAsNew(newInput, saveEvents)`, `setCustomStatus`, `whenAll`, `whenAny`. `WorkflowState` getters: `name`, `instanceId`, `runtimeStatus`, `createdAt`, `lastUpdatedAt`, `serializedInput/Output?`, `workflowFailureDetails?` (`getErrorType/getErrorMessage/getStackTrace`), `customStatus?`. `WorkflowRuntimeStatus`: numeric enum `RUNNING, COMPLETED, FAILED, TERMINATED, CONTINUED_AS_NEW, PENDING, SUSPENDED`. Inputs/outputs are JSON-serialized strings.

## Serialization

Content type is **inferred from the JS value** unless overridden: `Object`/`Array` → `application/json` (or `application/cloudevents+json` if CloudEvent-shaped), `Boolean`/`Number`/`String` → `text/plain`, Buffer/TypedArray → `application/octet-stream`. Deserialization always **tries `JSON.parse` first**, falling back to the raw string (`tryParseJson`) — so `state.get` returns parsed JSON or a string. Metadata goes into HTTP query params; actor payloads use `BufferSerializer` (JSON in Buffers).

## Error surfacing

- HTTP client: non-2xx/3xx rejects with a plain **`Error` whose message is `JSON.stringify({ error: statusText, error_msg: bodyText, status })`** — no typed API-error hierarchy. gRPC: ConnectRPC `ConnectError`s.
- **Soft-failure response objects instead of rejections**: `pubsub.publish` → `{ error?: Error }`; `state.save`/`delete` → `{ error?: Error }`; `publishBulk` → `{ failedMessages: [{message, error}] }`. Check these, don't just await.
- Typed: `GRPCNotSupportedError`, `HTTPNotSupportedError`, `PropertyRequiredError`; sidecar startup timeout → `Error("DAPR_SIDECAR_COULD_NOT_BE_STARTED")` (~60 × 500ms retries). Workflow activity failures: `TaskFailedError` via `Task.getException()`, client-side `WorkflowFailureDetails`.

## Missing building blocks (vs the Java SDK)

- **Jobs**: no client API at all — only a no-op `onJobEventAlpha1` gRPC server stub.
- **Conversation**: completely absent.
- **Client-side streaming pub/sub subscriptions**: absent (subscribe only via `DaprServer`).

dapr4s throws `UnsupportedOperationException` from these capabilities on JS (or implements them with raw HTTP).

## Facade-writing gotchas (Scala.js)

- **`CommunicationProtocolEnum` is numeric with `GRPC = 0`, `HTTP = 1`** — a facade defaulting to 0 silently picks gRPC. `HttpMethod` = lowercase strings (`"get"`, …); `DaprPubSubStatusEnum` = strings; `StateConcurrencyEnum`/`StateConsistencyEnum`/`LockStatus` numeric.
- **Ports are strings everywhere.** Options objects are `Partial<...>` — every facade field should be `js.UndefOr`.
- Ordering: all `subscribe`/`receive`/`listen`/`actor.init()+registerActor` **before `server.start()`**; `actor.init()` before `registerActor`.
- `invoker.listen` callback `body` is a **string** (`JSON.stringify(req.body)`) — re-parse on the Scala side.
- CJS + Node-only: with Scala.js use `ModuleKind.CommonJSModule`, or `ESModule` relying on Node's CJS-named-props interop. npm resolution is cwd-based under scala-cli (see [Cross-Building JVM + Scala.js with Scala CLI](../scala-js/scala-js-cross-building-scala-cli.md)).

## See Also

- [Dapr Java SDK](dapr-java-sdk.md) — the JVM counterpart (reactive Mono/Flux vs Promises; has jobs & conversation)
- [js.async, JSPI and the Wasm backend](../scala-js/scala-js-async-jspi-wasm.md) — how dapr4s turns these Promises back into direct style
- [Cross-Building JVM + Scala.js with Scala CLI](../scala-js/scala-js-cross-building-scala-cli.md) — build mechanics, npm resolution
- [Capture Checking on Scala.js](../scala-js/capture-checking-on-scala-js.md) — facades under explicit nulls + CC
- [Dapr Actors](dapr-actors.md), [Dapr Workflows](dapr-workflows.md) — the building blocks behind these APIs

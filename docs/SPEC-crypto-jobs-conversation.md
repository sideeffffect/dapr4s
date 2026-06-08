# Spec: Cryptography, Jobs & Conversation building blocks

Adds the three Dapr building blocks dapr4s does not yet cover, bringing
coverage to all 12. Grounded against the Dapr Java SDK **1.17.2** (the version
`project.scala` already depends on) and the existing capability idiom.

## Scope decisions (confirmed with maintainer)

- **Jobs**: full — both the client API (schedule/get/delete) **and** the
  inbound trigger the sidecar POSTs back when a job fires.
- **Conversation**: both `converse` (alpha1) and `converseAlpha2` (roles,
  tools, tool-calls, usage).
- **LLM backend for demos/CI**: the Dapr `echo` conversation component
  (deterministic, no keys, no network).
- **Crypto API**: buffered, not streaming. Bytes are `scala.collection.immutable.ArraySeq[Byte]`
  (not naked `Array[Byte]`), plus `String` convenience helpers.

"All existing Dapr features" is interpreted as **the three building blocks not
yet supported**. Filling in missing operations on already-supported blocks is
out of scope for this change.

## Conventions reused (no new patterns)

Each block follows the established idiom exactly:
- a `@assumeSafe trait XxxCapability extends ExclusiveCapability` with instance
  methods, plus a `@assumeSafe object XxxCapability` whose methods forward to a
  `using XxxCapability`;
- a factory method `def xxx(...): XxxCapability^{this}` on `trait DaprCapability`
  and its impl in `internal/DaprCapabilityImpl`;
- a transformer `def xxx(...)[T](body: XxxCapability ?=> T)(using DaprCapability): T`
  on `object DaprCapability`;
- the Java SDK is confined to `internal/*Impl.scala`; no Java types leak into
  the public API;
- new opaque domain types live in `src/optypes/`, value models in `Models.scala`.

## SDK surface (verified via javap on 1.17.2)

- Jobs (on `DaprClient`): `scheduleJob(ScheduleJobRequest): Mono<Void>`,
  `getJob(GetJobRequest): Mono<GetJobResponse>`, `deleteJob(DeleteJobRequest): Mono<Void>`.
  `ScheduleJobRequest(name, JobSchedule)` or `(name, Instant dueTime)`, with
  `setData(byte[])`, `setRepeat(Integer)`, `setTtl(Instant)`, `setDueTime(Instant)`.
  `JobSchedule.fromString(cron)`, `fromPeriod(Duration)`, `daily/hourly/weekly/monthly/yearly`.
- Crypto (on `DaprPreviewClient`): `encrypt(EncryptRequestAlpha1): Flux<byte[]>`,
  `decrypt(DecryptRequestAlpha1): Flux<byte[]>`.
  `EncryptRequestAlpha1(componentName, Flux<byte[]> plaintext, keyName, keyWrapAlgorithm)`;
  `DecryptRequestAlpha1(componentName, Flux<byte[]> ciphertext)` + optional `setKeyName`.
- Conversation (on `DaprPreviewClient`):
  - alpha1: `converse(ConversationRequest): Mono<ConversationResponse>`.
    `ConversationRequest(name, List<ConversationInput>)`; `ConversationInput(content)`
    + `setRole`/`setScrubPii`; response `getConversationOutputs(): List<ConversationOutput>`,
    `ConversationOutput.getResult(): String`.
  - alpha2: `converseAlpha2(ConversationRequestAlpha2): Mono<ConversationResponseAlpha2>`.
    Inputs are `ConversationInputAlpha2(List<ConversationMessage>)`; `ConversationMessage`
    is a bare interface (role/name/content) with **no shipped impl** — we provide one.
    Tools via `ConversationTool(ConversationToolsFunction(name, Map params))`.
    Results: `getOutputs(): List<ConversationResultAlpha2>` →
    `getChoices(): List<ConversationResultChoice>` → `getMessage(): ConversationResultMessage`
    (`getContent`, `getToolCalls`).

`AbstractDaprClient implements DaprClient, DaprPreviewClient`, so the single
client built in `Dapr.run` is cast to `DaprPreviewClient` for crypto/conversation —
no extra channel, no lifecycle change.

## Public API

### Cryptography
New optypes: `CryptoComponentName`, `CryptoKeyName`, `KeyWrapAlgorithm`
(plain String wrappers; `KeyWrapAlgorithm` carries common constants, e.g. `Rsa`, `Aes`).

```
trait CryptoCapability:
  val componentName: CryptoComponentName
  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm): ArraySeq[Byte]
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte]
  def encryptString(keyName: CryptoKeyName, plaintext: String, algorithm: KeyWrapAlgorithm): ArraySeq[Byte]  // UTF-8
  def decryptString(ciphertext: ArraySeq[Byte]): String                                                      // UTF-8
```
Acquired via `DaprCapability.crypto(CryptoComponentName)`. Impl feeds a single
chunk into the SDK Flux and collects the result Flux into one `ArraySeq[Byte]`
(new `FluxOps.collectBytes` helper alongside `MonoOps`).

### Jobs
New optype: `JobName`. New value type `JobSchedule` (smart constructors:
`JobSchedule.cron(expr)`, `JobSchedule.every(FiniteDuration)`,
`JobSchedule.daily/hourly/weekly/monthly/yearly`). New model `JobDetails`.

Client capability (no component name; like `invoke`/`workflow`):
```
trait JobsCapability:
  def schedule[T: JsonCodec](name: JobName, data: T, schedule: JobSchedule,
      dueTime: Option[Instant] = None, repeats: Option[Int] = None, ttl: Option[Instant] = None): Unit
  def scheduleOnce[T: JsonCodec](name: JobName, data: T, dueTime: Instant, ttl: Option[Instant] = None): Unit
  def get(name: JobName): Option[JobDetails]
  def delete(name: JobName): Unit
```
Acquired via `DaprCapability.jobs`. Inbound trigger:
```
// new field on DaprApp: jobs: List[JobRoute]
JobRoute[T: JsonCodec](name: JobName)(handler: T => Unit): JobRoute
```
`DaprAppServer` registers `POST /job/<name>` → decode body as `T` → run handler →
200. Mirrors `BindingRoute` dispatch (AnyRef-erased handler).

### Conversation
New optype: `ConversationComponentName`. Models: `ChatRole` (System/User/Assistant/Tool/Developer),
`ChatMessage(role, name: Option[String], text: String)` with `.user/.system/.assistant/.tool/.developer`
helpers; `ChatTool(name, description: Option[String], parametersJson: SerializedJson)`;
`ChatResponse(contextId, results: List[ChatResult])`,
`ChatResult(choices, model, usage)`, `ChatChoice(finishReason, index, message)`,
`ChatResultMessage(content, toolCalls)`, `ChatToolCall(id, functionName, arguments)`,
`ChatUsage(promptTokens, completionTokens, totalTokens)`.

```
trait ConversationCapability:
  val componentName: ConversationComponentName
  // alpha1
  def converse(prompt: String, temperature: Option[Double] = None,
      contextId: Option[String] = None, scrubPii: Boolean = false): String
  def converseMany(prompts: Seq[String], temperature: Option[Double] = None,
      contextId: Option[String] = None, scrubPii: Boolean = false): List[String]
  // alpha2
  def chat(messages: Seq[ChatMessage], tools: Seq[ChatTool] = Nil,
      toolChoice: Option[String] = None, temperature: Option[Double] = None,
      contextId: Option[String] = None, scrubPii: Boolean = false): ChatResponse
```
Acquired via `DaprCapability.conversation(ConversationComponentName)`. Impl builds a
private concrete `ConversationMessage` for alpha2.

## Testing

- **Unit** (`test/`): codec/model round-trips; `JobSchedule` → expression mapping;
  `ChatRole` ↔ SDK enum mapping; `JobRoute` dispatch through `DaprAppServer`/`TestDaprApp`.
- **Integration** (`test/integration`, Testcontainers + `DaprContainer`):
  - Crypto: component `crypto.dapr.localstorage` with a generated local key; encrypt→decrypt round-trip.
  - Conversation: component `conversation.echo`; `converse` returns the echoed prompt; `chat` returns it as assistant content.
  - Jobs: **risk** — the Jobs trigger needs the Dapr **scheduler** service, which the
    plain `daprd` Testcontainer may not run. Plan: verify; if the scheduler is
    unavailable in-container, cover schedule/get/delete against the API and move the
    full fire-the-trigger assertion to the dapr4s-examples docker-compose e2e (where a
    `scheduler` service can be added). Document whichever path is taken.

## Release

- Bump to **0.2.0** (new features, backward-compatible). Version is `git:dynver`,
  so the release is the **tag `v0.2.0` on `main`** → CI `publish` job pushes to Central.
- After Central propagation, bump `dapr4s-examples/build.mill` `Dapr4sVersion` to `0.2.0`.

## dapr4s-examples additions

Three new numbered examples, following the existing `NN-name` + `NN-name-shell` layout,
each with a pure `*App` (object + `apply`), a `@main` shell, components, and an e2e suite:
- `10-cryptography` — encrypt/decrypt a secret blob (local-storage crypto component).
- `11-jobs` — schedule a job and handle its trigger (needs `scheduler` in compose).
- `12-conversation` — prompt the `echo` conversation component (alpha1 + a small alpha2 chat).

e2e suites named `Example10CryptographyTest` etc., matching the filename==class convention.

## Risks / caveats

- Crypto, Conversation, and Jobs triggers are **alpha** in Dapr; APIs may churn
  across Dapr/SDK versions.
- Jobs trigger in CI depends on the scheduler being reachable (see Testing).
- `converseAlpha2` `responseFormat`/`parameters`/`promptCacheRetention` are **not**
  surfaced in v1 of this API (kept minimal); can be added later without breaking changes.

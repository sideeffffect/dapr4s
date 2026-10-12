# Proposal: dapr4s on WebAssembly components (dapr-wasm-components)

Status: **proposal** (2026-10-12). Implemented so far: D1, D2, D3 (see the table in 2a).

Decisions (2026-10-12):
- **W1 accepted, along the `ReadOnly` line only.** WIT gets `x-read` + `x` interfaces where dapr4s has
  `X.ReadOnly`. The `Instance` (name-bound) tiers stay dapr4s-only sugar: they never become WIT interfaces.
- **W5: wait for WASI 0.3.** No native workflow host. Workflow authoring stays compile-time absent on the `wasm`
  target until the durabletask stream can run inside a WASI 0.3 async component.
- **sbt migration now** (Phase 1 starts immediately, before the Phase 0 spike).
- **D1, D2, D3 now.** Overlap analysis:
[`wiki/dapr/dapr-wasm-components-vs-dapr4s.md`](../wiki/dapr/dapr-wasm-components-vs-dapr4s.md).

Goal: write a dapr4s app in Scala, link it with Scala.js as a **WASI component** that targets the
`dapr-wasm-components:interfaces` world `app`, compose it with the providers (`compose.sh`), run it in
wasmtime/Spin next to a Dapr sidecar. Examples live in dapr4s-examples.

Rule for "which side changes": **WIT models Dapr faithfully and language-neutrally; dapr4s models Scala ergonomics.**
If Dapr has a feature and one side lacks it, the side that lacks it changes. If the two disagree on *shape*, WIT
takes the shape the component model expresses best (resources, interface granularity) and dapr4s adapts in its
`internal` wall.

## 1. Changes to dapr-wasm-components (WIT 0.7.0)

| # | Change | Why (harmony) |
|---|---|---|
| W1 | **Split interfaces by authority**: `state-read` / `state` (writes), `configuration-read` / `configuration` (subscribe), `jobs-read` / `jobs`, `workflow-read` / `workflow`, `secrets-read` (single key) / `secrets` (bulk enumerate). Each read interface carries the read functions; the write interface `use`s its types. The `app` world imports both. | `wit-component` drops unused imports, and the Scala.js linker drops uncalled methods. So a component's **import list becomes its authority manifest**, checked at compose/deploy time for any language. This is the same split as dapr4s `X.ReadOnly` (see `docs/CAPABILITY-SPLITS.md`): CC proves it at compile time, the component type proves it at deploy time. |
| W2 | **Configuration subscription as a resource**: `subscribe(...) -> result<subscription, configuration-error>`, `resource subscription { id: func() -> string; }`, dropping the handle unsubscribes. Keep `configuration-callback` for pushes (tagged with the id). Remove the free `unsubscribe`. | Maps 1:1 to dapr4s `subscribe(...): AutoCloseable^{this}`. Resource drop is the idiomatic component-model "close". |
| W3 | **Typed `workflow-instance`**: add `workflow-name`, `runtime-status` as an `enum` (running/completed/failed/terminated/suspended/pending/continued-as-new), `input`/`output: option<list<u8>>`, `failure: option<failure-details>`. Keep `properties` for the rest. | dapr4s `WorkflowSnapshot` needs name, typed status, input, output. Today the provider throws this away into a string map. The provider already parses the Dapr response. |
| W4 | **Drop `workflow-component`** param (or make it `option<string>`, default `"dapr"`). | Dapr has one workflow engine (`dapr`). dapr4s does not expose it. |
| W5 | **`workflow-callback` (authoring)**, the durabletask work-item level: `list-workflows`, `list-activities`, `on-orchestrator(request{instance-id, past-events, new-events}) -> result<orchestrator-response{actions, custom-status}, app-error>`, `on-activity(request{name, instance-id, task-id, input}) -> result<list<u8>, app-error>`. The `GetWorkItems` gRPC stream lives in a **workflow provider**, not in the app. | Workflows are the only `DaprApp` field with no WIT counterpart. Dapr drives them over the durabletask gRPC stream, not the app channel. A reactor cannot hold a long-lived stream under WASI 0.2, so the provider is either a small native host (Rust + wasmtime, holds the stream, calls the export per work item) or a WASI 0.3 async component. **Highest-risk item. Phase 6.** |
| W6 | Leave as is: universal name-first functions, `list<u8>` payloads, trap-for-infra-failure, mandatory callback exports, `topic-event-response`. | Already matches dapr4s. |

## 2. Changes to dapr4s

### 2a. Platform-independent fixes (useful on JVM/JS today, needed for the component target)

| # | Change | Why |
|---|---|---|
| D1 | **`UnlockStatus.LockBelongsToOthers`**. Today both walls collapse it into `InternalError` (`src/jvm/internal/LockCapabilityImpl.scala:26`, `src/js/internal/LockCapabilityImpl.scala:44`). | Bug: Dapr, the Java SDK and WIT all distinguish it. |
| D2 | **Typed Dapr exceptions** — **implemented** (2026-10-12): `sealed abstract class DaprException` with `final` `ComponentNotFoundException(componentKind, name)`, `PermissionDeniedException`, `AppUnreachableException(appId)`, `InvokeHttpException(appId, method, status, body)`, `DaprApiException(errorCode)`. One shared table (`internal.DaprErrors`) maps Dapr error codes / ErrorInfo reasons / daprd's exact messages, not raw gRPC/HTTP statuses (those are unreliable: secret-store-not-found is 401); see [`wiki/dapr/dapr-error-codes.md`](../wiki/dapr/dapr-error-codes.md). The `wasm` wall maps the WIT `*-error` variants onto the same types. | WIT has these recoverable cases in every interface. dapr4s has no type for them. They then mean the same thing on all three platforms. |
| D3 | **HTTP-level invoke** — **implemented** (2026-10-12): `InvokeCapability.invokeHttp(appId, method, body, httpMethod, headers, query): InvokeResponse(status, headers, body)`, never throwing on non-2xx. The typed `invoke` stays, built on top of it, and throws `InvokeHttpException` on non-2xx identically on JVM and JS. | WIT and Dapr expose status and headers. dapr4s drops them. |
| D4 | **`RuntimeCapability`** (`DaprCapability.runtime`): `ReadOnly` = `metadata`, `healthy`, `outboundHealthy`; full = `setMetadataLabel`. | WIT `runtime`, already in the family shape. |
| D5 | **Subscription routing + bulk**: `Subscription` gains optional CEL `rules` and `bulk: Option[BulkSubscribe]`. Derivation adds `@route("<cel>")`. | Dapr features WIT already models. |
| D6 | **Secrets multi-value**: `getAll(storeName, key): Option[Map[SecretKey, SecretValue]]`. `get` stays the single-value convenience. | A Dapr secret is a map. WIT models that correctly. |
| D7 | Optional `DaprApp.healthCheck: Option[() => Boolean]`. | `health-callback`. A trivial default otherwise. |

### 2b. Build: move scala-cli → sbt

The `scala-wasm` fork ships only as an sbt plugin (`moduleKind = WasmComponent`, `scalaJSWitDirectory` →
`wit-bindgen scala` into `src_managed`). Moving dapr4s to sbt also removes most current build hacks:

| Today (scala-cli) | With sbt |
|---|---|
| CI pins scala-cli 1.14.0 (1.18 cannot link JSPI on Wasm) | `scalaJSLinkerConfig` exposes the linker options directly |
| `scripts/generate-st-facades.sh` + ivy-local digests + `embed-st-facades.sh` | `ScalablyTypedConverterGenSourcePlugin` generates facade sources into the build. They compile into the jar, so no embedding step |
| `scala-cli-tmpfs.sh` (`__loader.js` pick), `wasm-test.sh` exit-1 tolerance | scala-cli-specific bugs. They go away |
| per-file `target.platform` directives | real `jvm` / `js` / `wasm` projects over `src/{shared,jvm,js,wasm}` |

Projects: `core.jvm`, `core.js` (current Node/JSPI target), **`core.wasm`** (scala-wasm linker, Wasm-component
javalib). Artifact for the new target: `dapr4s-wasm_sjs1_3`. It is a different artifact because its public surface
differs (see 2c).

### 2c. The `wasm` platform

- `src/wasm/internal/` = the **third wall**. It is the only place generated WIT bindings appear. Same
  `@assumeSafe` + WHAT/WHY/WHY-SAFE rules. No JSPI, no `js.async`: WIT imports are already synchronous.
- `DaprCapabilityPlatform` (wasm): **has** `jobs` and `conversation` (WIT provides them), **no** workflow
  authoring (`DaprApp.workflows`/`activities` absent, by the platform-trait technique) until W5 lands.
- Outbound: every `XCapabilityImpl` is a thin mapping. `result` variants map to the existing `Option` /
  `Boolean` / `UnlockStatus` / `ETagMismatchException` shapes, setup errors map to D2.
  `waitForCompletion` = poll `workflow-read.get`.
- Inbound: dapr4s provides the implementation of **all seven** `*-callback` exports, dispatching over one
  registered `DaprApp`. The actor callback drives `ActorContext` through the outbound `actors` interface. Config
  pushes go through an id → `onChange` registry (W2 handle owns the entry).
- Entry point: a reactor has no `main`. The user writes `object App extends DaprComponent { val app: DaprApp = ... }`.
  How the library's export glue finds that object depends on scala-wasm's export mechanism (**spike question**).
- No connection config: the outbound provider reads `DAPR_HTTP_ENDPOINT` / `DAPR_API_TOKEN` itself.

## 3. dapr4s-examples

A `wasm-component` example: an sbt module building the component, `compose.sh` (http out + http in), run with
`dapr run --app-id ... -- wasmtime serve -S cli composed.wasm`. E2E in CI against a Docker sidecar. It mirrors an
existing JVM example (state + pub/sub + invoke + an actor) so the three platforms run the same app code.

## 4. Phases

| Phase | Work | Size | Depends on |
|---|---|---|---|
| 0 | **Spike**: scala-wasm + Scala 3.9 nightly + CC flags → component importing `state` and exporting `invocation-callback`, composed and run against Dapr | 1–2 days | — (go/no-go) |
| 1 | dapr4s → sbt (jvm + js only, CI parity) | 3–5 days | — |
| 2 | D1–D3 (then D4–D7) | ~1 day each | — |
| 3 | WIT 0.7.0: W1–W4 + provider updates + Rust SDK | 2–4 days | — |
| 4 | dapr4s `wasm` platform, outbound + inbound, no workflows | 1–2 weeks | 0, 1, 3 |
| 5 | dapr4s-examples `wasm-component` + e2e | 1–2 days | 4 |
| 6 | W5 workflow authoring (WASI 0.3 async provider) + Scala durabletask replay engine | weeks, uncertain | 4, WASI 0.3 in hosts |

## 5. Risks

- **Compiler/IR match**: dapr4s needs a Scala 3.9 nightly. The fork's linker must read that compiler's Scala.js
  IR. Phase 0 answers this.
- **Shared-code deps without JS**: `scala-java-time` (tzdb) and the `JsonCodec` instances must link without
  `@JSImport`. Unknown until Phase 0.
- **Hosts**: GC + EH components need Spin canary or recent wasmtime. The gRPC provider is Spin-only.
- **Fork churn**: the scala-wasm API "is subject to change without notice". Pin versions.

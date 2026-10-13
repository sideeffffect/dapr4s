# Proposal: dapr4s on WebAssembly components (dapr-wasm-components)

Status: **proposal** (2026-10-12). **Phase 0 done: partial go** (section 6). Implemented so far: D1, D2, D3 (see the table in 2a); W1–W4 in WIT `dapr-wasm-components:interfaces@0.7.0` ([dapr-wasm-components PR #11](https://github.com/sideeffffect/dapr-wasm-components/pull/11), see section 1).

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

**W1–W4 landed (2026-10-12)** in [dapr-wasm-components PR #11](https://github.com/sideeffffect/dapr-wasm-components/pull/11). Facts the
dapr4s `wasm` wall must respect:
- Import pruning holds **per function**: a component that only calls `state-read.get` imports `state-read` with just
  `get` (guarded by `e2e/tests/authority.rs`). Every app also imports `state-read` and `configuration-read` with
  **zero functions**, because the mandatory callback exports use their types (`state-item`, `configuration-item`).
  These carry no authority.
- `runtime-status` has daprd's 8 values (incl. `canceled`) plus `unknown` (any other string; raw string kept in
  `properties["dapr.workflow.runtime_status"]`). dapr4s `WorkflowStatus` already has the 8; the `wasm` wall must
  decide how `unknown` surfaces (it has no case today).
- Dropping a `subscription` whose unsubscribe fails is logged, not trapped. The gRPC provider does not support
  `subscribe` at all (traps).
- `workflow-error.component-not-found` now means "workflow engine not available".

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

**Status (2026-10): done for `jvm` + `js`** in [PR #42](https://github.com/sideeffffect/dapr4s/pull/42). Differences
from the plan above: the facades still need an embedding step, but it is now an sbt `packageBin / mappings` entry
(only `.sjsir` of the main-closure packages) instead of a script, because the generated code has to compile in its
own project (`stFacades`, without the CC / explicit-nulls / fatal-warning flags). The per-file `target.platform`
lines stay as comments for now; removing them is a separate follow-up.

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
  How the library's export glue finds that object depends on scala-wasm's export mechanism (**spike question**;
  answered in section 6: a fixed-FQN object, resolved at link time).
- No connection config: the outbound provider reads `DAPR_HTTP_ENDPOINT` / `DAPR_API_TOKEN` itself.

## 3. dapr4s-examples

A `wasm-component` example: an sbt module building the component, `compose.sh` (http out + http in), run with
`dapr run --app-id ... -- wasmtime serve -S cli composed.wasm`. E2E in CI against a Docker sidecar. It mirrors an
existing JVM example (state + pub/sub + invoke + an actor) so the three platforms run the same app code.

## 4. Phases

| Phase | Work | Size | Depends on |
|---|---|---|---|
| 0 | **Spike**: scala-wasm + Scala 3.9 nightly + CC flags → component importing `state` and exporting `invocation-callback`, composed and run against Dapr. **Done 2026-10-12: partial go** (section 6) | 1–2 days | — (go/no-go) |
| 1 | dapr4s → sbt (jvm + js only, CI parity) | 3–5 days | — |
| 2 | D1–D3 (then D4–D7) | ~1 day each | — |
| 3 | WIT 0.7.0: W1–W4 + provider updates + Rust SDK | 2–4 days | — |
| 4 | dapr4s `wasm` platform, outbound + inbound, no workflows. **Revised after Phase 0: 3–4 weeks** plus upstream scala-wasm fixes (section 6) | ~~1–2 weeks~~ 3–4 weeks | 0, 1, 3, scala-wasm fixes |
| 5 | dapr4s-examples `wasm-component` + e2e | 1–2 days | 4 |
| 6 | W5 workflow authoring (WASI 0.3 async provider) + Scala durabletask replay engine | weeks, uncertain | 4, WASI 0.3 in hosts |

## 5. Risks

- **Compiler/IR match**: dapr4s needs a Scala 3.9 nightly. The fork's linker must read that compiler's Scala.js
  IR. Phase 0 answers this.
- **Shared-code deps without JS**: `scala-java-time` (tzdb) and the `JsonCodec` instances must link without
  `@JSImport`. Unknown until Phase 0.
- **Hosts**: GC + EH components need Spin canary or recent wasmtime. The gRPC provider is Spin-only.
- **Fork churn**: the scala-wasm API "is subject to change without notice". Pin versions.

## 6. Phase 0 result (2026-10-12): partial go

The spike code now lives in this repository as an opt-in, unpublished prototype (see "Phase 0 code in dapr4s"
below). Details, commands and evidence:
[`wiki/scala-js/scala-wasm-component-spike.md`](../wiki/scala-js/scala-wasm-component-spike.md).

**It works end to end.** A counter app was written against dapr4s 3.0.0's `StateCapability.Instance` + `JsonCodec`
and compiled by dapr4s's nightly (`3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY`) with all dapr4s flags. It linked as a
WASI component with scala-wasm 1.22.1-wasm.6. The WIT `dapr-wasm-components:interfaces@0.7.0` came from OCI via
`wkg wit fetch`, and the bindings from `wit-bindgen-scala` 0.1.0 via `scalaJSWitDirectory`. It composed with
`compose.sh` (http out + http in), ran under `wasmtime serve -S cli` 49.0.2 next to daprd 1.17.7 + Redis, and
`dapr invoke --method counter` returned 1, then 2. The error mapping also works: `store-not-found` →
`ComponentNotFoundException(State, name)`, `etag-mismatch` → `ETagMismatchException`.
**Partial**, because it needs 5 workarounds, and 3 of them must become upstream scala-wasm work before shipping.

Answers to the risk questions (section 5):

1. **IR compatibility: yes, with an overlay.** The 1.22.1-wasm.6 linker reads the nightly's IR. But the nightly's
   Scala.js stdlib (built on Scala.js 1.20.2) uses JS `===` in `BoxesRunTime`, which is reachable from any program.
   We overlay the 4 library-js classes that scala-wasm's Scala 3 fork patched. **The bigger finding:** mainline
   Scala 3 has no WIT codegen. WIT imports are lowered at the call site by the compiler, and `@WitExport` is
   compiler-emitted. The nightly compiles the bindings but emits no WIT IR (the linker writes an empty output and
   reports success). scala-wasm's only Scala 3 build (`3.8.3-RC1-wasm.4`) is stale and rejects the current
   generator's output.
2. **Shared code without JS: yes, for what dapr4s itself uses.** Opaque types, `JsonCodec`, the capability tiers,
   `DaprApp`/`InvokeRoute`/validation and the exceptions link with zero JS. Excluded: `java.time`
   formatting/parsing (`Instant.parse`/`toString`, so also `WorkflowSnapshot.toString`): scala-java-time →
   `Locale.getDefault` → scala-java-locales `DefaultLocale` → `window`. Also `Instant.now`/`currentTimeMillis`,
   `println` and `UUID.randomUUID` (no `SecureRandom`). `java.time.Duration`, `FiniteDuration`, `UUID.fromString`
   and `String.format` link. The published POM also brings mainline `scalajs-library`/`javalib` 1.22.0; the `wasm`
   build must exclude them.
3. **Bindings under the CC flags: they compile with zero warnings under all six flags, but that does not help,
   because the nightly ignores WIT.** They must be compiled by Scala 2.13 + the scala-wasm plugin. This is the same
   isolation as the ScalablyTyped `stFacades` project, but the reason is the compiler, not the flags. Scala 3 code
   then uses the generated records/variants directly and calls imports through plain forwarders compiled on the
   2.13 side.
4. **Exports from a library: yes.** The 15 `@WitExport` functions live in a library jar, and the linker picks them
   up from any classpath entry. A reactor has no `main` (module initializers need an export; `Reflect` is
   JS-backed), so the app supplies its `DaprApp` through one fixed-FQN object (`object ComponentRoot { def
   component = MyApp }`). The library compiles against a `Provided` stub, and a missing root is a link error.
5. **Compose + runtime: yes, after two linker workarounds.** (a) The linker cannot lift exports with more than 16
   flat params (`??? // TODO read params from linear memory`). This hits `on-topic-event` and `on-timer`; the
   import side (`jobs.schedule`, `converse`, `register-reminder`, `register-timer`) fails at `component new`. A
   ~20-line export-side linker patch was used. (b) The synthesized component type drops WIT aliases, so `wac`
   rejects the composition ("missing expected list export `metadata`"). Fix: re-embed the real world with
   `wasm-tools component embed --encoding utf16`. With utf8, strings arrive garbled. W1 holds: the app imports
   only `state-read.get` + `state.save`.

Numbers: dapr4s app 498 KB (fullopt), composed 2.3 MB. Cold start (spawn → first 200, uncached) ~0.8 s, ~40 ms
with wasmtime's cache. Warm round trip through the sidecar 2.4 ms median, incl. Redis get + save.

**Revised Phase 4: 3–4 weeks** (was 1–2), plus upstream work in scala-wasm (not ours to schedule):
- The `wasm` artifact becomes a two-compiler build: a Scala 2.13 sub-project (generated bindings, the
  `@WitExport` glue, *generated* import forwarders for the ~100 WIT functions) under the Scala 3 `wasm` wall.
  Publishing a 2.13 artifact that a Scala 3 artifact depends on is new for dapr4s.
- Upstream (scala-wasm): params via linear memory on both sides; use the configured WIT world, or keep aliases,
  in the component type; a Scala 3 WIT backend that tracks the library (or upstreaming to Scala.js). Until then:
  carry the linker patch and the re-embed step, and keep the stdlib overlay per nightly bump.
- dapr4s shared code must avoid `java.time` formatting and wall-clock/random APIs on the `wasm` path (or provide
  WASI-backed replacements).
- Also found: `compose.sh` exits 1 on success (`tmp` assigned in a `$(...)` subshell), and the wasi-http
  provider's `store-not-found` payload is daprd's raw body, not the store name.


### Phase 0 code in dapr4s (2026-10-13)

The spike was ported into this repository ([PR #44](https://github.com/sideeffffect/dapr4s/pull/44)) as an
**opt-in, unpublished** prototype. AGENTS.md "Wasm component layer rules" has the rules and commands.

- **Build**: a nested sbt build in [`wasm/`](../wasm/build.sbt). The scala-wasm plugin is a fork of `sbt-scalajs` and
  cannot share the root meta-build, so the root build, `coreJVM`/`coreJS` and their POMs are unchanged. `wasm/` also
  holds `wit/world.wit`, `wkg-config.toml` and `wkg.lock`. WIT comes only through `wkg wit fetch` (OCI).
- **dapr4s's own `src/shared`** is compiled for the third platform (`coreWasm`), together with
  [`src/wasm/core`](../src/wasm/core) (empty platform traits + the `dapr4s.wasm.DaprComponent` entry API). The
  spike used the published `dapr4s_sjs1_3` 3.0.0 instead.
- **The wall**: [`src/wasm/internal`](../src/wasm/internal) (`WasmStateCapability`, `InvokeDispatcher`,
  `WasmRuntime`, `AppRoot`) and [`src/wasm/inert`](../src/wasm/inert) (inert callback exports).
- **No hand-written 2.13 code any more.** The spike's ~330 lines of 2.13 forwarders and export glue are now
  generated by [`wasm/project/WitGlue.scala`](../wasm/project/WitGlue.scala):
  1. wit-bindgen-scala output goes into the shadow package `dapr4swit.lowered`, and a **same-name facade** under
     `dapr4swit` (aliases + companion vals + import forwarders compiled by 2.13) gives Scala 3 the documented API
     (`state_read.get(...)`, `case state_read.GetError.KeyNotFound`). Exhaustivity checking works through the
     aliases. If Scala 3 gets a WIT backend, the generator is deleted and the dapr4s code is unchanged.
  2. A plain `<Iface>Imports` trait per imported interface (companion object = live imports) and an
     `<Iface>Exports` trait per exported interface. The generated `AppRoot` stub and the `@WitExport` trampolines
     delegate to the Scala 3 implementations.
  3. The wall depends on the import traits, so its error mapping has **unit tests with fakes**
     ([`test/wasm/unit`](../test/wasm/unit), `cd wasm && sbt wallWasm/test`). They run as plain Scala.js on Node,
     with no wasmtime and no sidecar.
  The generator does not cover WIT resources yet (`crypto` encryptor/decryptor/output, configuration
  `subscription`).
- **Test components**: [`test/wasm/counter`](../test/wasm/counter) (dapr4s API) and
  [`test/wasm/counter-raw`](../test/wasm/counter-raw) (the facade directly).
- **Scripts**: [`scripts/wasm/`](../scripts/wasm): `e2e.sh` (W1 authority check, compose, daprd + Redis, counter
  1 → 2, error mapping), `compose.sh`, `run.sh`, `measure.py`, `build-patched-linker.sh` + `patches/`.
- **CI**: a `wasm` entry in the `test` matrix. It is `continue-on-error`, so it does not gate `publish`.
- **`publishLocal` for external apps** (still never Maven Central): `cd wasm && sbt publishLocal` publishes
  `dapr4s-wasm-core_sjs1_3`, `dapr4s-wasm_sjs1_3` (the wall), `dapr4s-wasm-inert_sjs1_3`, the generated 2.13 glue
  `dapr4s-wasm-wit-{lowered,facade,exports}_sjs1_2.13` and the sbt plugin `sbt-dapr4s-wasm`, all under the sbt-dynver
  version. The build-level workarounds (patched linker, stdlib overlay, exclusions, WasmComponent link + WIT
  re-embed) cannot be a library, so they moved into that plugin
  ([`wasm/sbt-plugin`](../wasm/sbt-plugin/src/main/scala/dapr4s/sbtwasm/Dapr4sWasmPlugin.scala)), which dapr4s's own
  `wasm/` build compiles into its meta-build. The link-time stubs stay unpublished (`Provided`). The first consumer
  is [dapr4s-examples](https://github.com/sideeffffect/dapr4s-examples)' separate `wasm/` sbt build. It uses the
  **release mode** (`-Ddapr4s.wasm.coreRelease=3.0.0`): the shared API comes from the published `dapr4s_sjs1_3`
  3.0.0 on Central (linked by the scala-wasm linker, as the spike did), and only the wasm layer (wall, generated
  glue, `DaprComponent`) is local. Once the wasm layer is released, that local part becomes a Central dependency too.

This makes the "two-compiler build" of the revised Phase 4 concrete: the 2.13 part is entirely generated, and only
the generator (not hand-written forwarders) has to grow to cover all ~100 WIT functions. What remains open for
Phase 4 is resources, the other walls, the upstream linker fixes and publishing the 2.13 + Scala 3 artifacts (and
the plugin) to Maven Central; `publishLocal` already works.

# js.async / js.await, JSPI, and the WebAssembly Backend

> Sources: scala-js.org release notes (1.17.0–1.21.0) and WebAssembly backend docs, scala-js/scala-js JSPI.scala, chromestatus.com, nodejs/node#60014, un-ts/synckit, typelevel/cats-effect#529, 2026-06-11
> Raw: [sync-looking APIs on Scala.js research report](../../raw/scala-js/2026-06-11-scalajs-async-jspi.md)
> Updated: 2026-06-11

## Overview

On a single-threaded JS engine, blocking on a Promise in-thread is impossible by design (run-to-completion: the continuation that would resolve the Promise can never run while you spin). Scala.js offers exactly one mechanism that genuinely preserves a direct-style blocking-looking API: the **experimental WebAssembly backend + JSPI** with orphan `js.await` — the architectural analogue of virtual-thread parking. dapr4s uses this to keep its synchronous `def get(key): Option[T]` API byte-identical across JVM and JS.

## js.async / js.await semantics

- Introduced in **Scala.js 1.19.0** (2025-04-21). `js.async { ... }` returns `js.Promise[A]`; semantics are exactly an immediately-invoked JS async function `(async () => body)()`. `js.await(p: js.Promise[A]): A` resumes when the promise resolves, or throws on rejection.
- **Requires ES2017+ output** — without it linking fails with `Uses an async block with an ECMAScript version older than ES 2017`. scala-cli: `//> using jsEsVersionStr es2017`; sbt: `withESFeatures(_.withESVersion(ESVersion.ES2017))`.
- **Scala versions:** Scala 2.12/2.13 got it with Scala.js 1.19.0; **Scala 3 needs 3.8.0+** (which bundled Scala.js 1.20.1; 3.8.0 had a runtime regression — use 3.8.1+). dapr4s's 3.10.0-RC1 nightly has it.

**Lexical restriction on the plain JS backend:** `js.await` is only allowed **lexically inside the `js.async` block** — conditional branches, `while`, `try/catch/finally` are fine, but NOT inside any local method, local class, by-name argument, or closure/lambda (so no `for`-comprehensions or `.map(...)` bodies). On plain JS, `js.await` never crosses function boundaries — the same colored-function model as JS itself.

## Orphan js.await (Wasm + JSPI only)

An orphan `js.await` is one not lexically inside `js.async`. Enabled by:

```scala
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait   // an implicit js.AwaitPermit
```

- There is **no linker flag** for this — it's a source-level implicit import; the gate is a link check.
- The scaladoc is explicit: code using it "will then only link when targeting WebAssembly" — on the plain JS backend, orphan awaits are a **link-time error** (a clean failure mode, not a runtime one).
- On Wasm, validation is at **run time**: there must be a dynamically enclosing `js.async` on the call stack **with no JavaScript frame between it and the `js.await`**, otherwise **`WebAssembly.SuspendError`** is thrown.
- This is "the new superpower offered by JSPI" (1.19.0 notes): *as long as you enter into a `js.async` block somewhere, you can synchronously await Promises in any arbitrary function* — deep ordinary Scala call stacks suspend transparently while the event loop keeps running.

**The no-intervening-JS-frame rule in practice:** any Scala lambda invoked *by* a JS API (`Promise.then`, timers, `js.Array.map`, an express/HTTP-server handler) is a JS frame on the stack. Awaiting below it throws `SuspendError`. The pattern: **re-enter `js.async { ... }` inside every JS-invoked callback** — each inbound dispatch gets its own async scope, like one virtual thread per request.

## The WebAssembly backend

- **Experimental since Scala.js 1.17.0** (2024-09-28); still experimental as of 1.21.0 (docs warn it may be removed in future minor versions and may require newer Wasm engines in minors). Enable: `withExperimentalUseWebAssembly(true)` (sbt) or `//> using jsEmitWasm true` (scala-cli, since v1.5.2).
- **ESModule-only** (`ModuleKind.ESModule` mandatory) and **single module** (`ModuleSplitStyle.FewestModules`; no `js.dynamicImport`). Requires a JS host (not standalone WASI).
- Semantics identical to Scala.js-on-JS; **same javalib/JDK coverage** — no extra unsupported-API list.
- **`@JSExport`/`@JSExportAll` are silently ignored** (JS can't call methods on Scala instances; no `toString` interop). `@JSExportTopLevel` works.
- Performance: ~**30% lower run time** than the JS output for compute-heavy code (interop-heavy can be slower); **code size ~2× the JS backend** in fullLink mode. 1.19.0 added JSPI; 1.20.1 improved Wasm debug info and perf; 1.18.0/1.20.0 were broken and never announced (use 1.18.1/1.20.1).
- `sbt test` works under the Wasm backend (munit runs over the standard test bridge). Published artifacts are **backend-neutral `.sjsir`** — one `_sjs1_3` artifact serves both backends; orphan-await code simply fails to link for plain-JS consumers.

## JSPI runtime support matrix

JSPI reached W3C Wasm CG **stage 4 (standardized) April 2025**.

| Runtime | JSPI |
|---|---|
| Chrome 137+ (May 2025) | shipped by default ("full support" per Scala.js docs) |
| Firefox | flag `javascript.options.wasm_js_promise_integration` per Scala.js docs (default-on possibly recent — uncertain) |
| **Safari** | **none at all** (18.4+ runs the Wasm backend but not js.async/orphan-await code) |
| Node 22 | no JSPI (needs `--experimental-wasm-exnref` just for the backend) |
| Node 23/24 | behind `--experimental-wasm-jspi` |
| **Node 25+** (2025-10, V8 14.1) | **enabled by default** |

CI note: Node 23/24 flags must be argv flags on the node process (`NODE_OPTIONS` does **not** accept V8 `--experimental-wasm-*` flags); sbt's `NodeJSEnv.Config().withArgs(...)` can pass them, but **scala-cli has no documented way to pass node argv flags to its run/test process** — so Wasm testing under scala-cli is only realistic on **Node 25+**.

## Rejected alternatives for sync-looking APIs on plain JS

1. **`Atomics.wait` sync bridge (synckit pattern)** — worker_threads + SharedArrayBuffer + `Atomics.wait` + `receiveMessageOnPort`. Genuinely works in Node (powers eslint-plugin-prettier, Jest tooling), pure-JS deps. Rejected for dapr4s because it **hard-blocks the entire Node event loop per call** (server concurrency collapses to serial) and creates a **deadlock class** with Dapr's bidirectional model: the sidecar calls back into the app (pubsub/actors/bindings/workflow signals) — if a blocked outbound call's completion depends on the blocked main thread serving an inbound request, you deadlock. Also: structured-clone-only payloads, no streaming, Node-only. Viable only for a narrow outbound-only client subset.
2. **deasync** (native addon pumping libuv) — fragile across Node versions, arbitrary reentrancy, ~100× slower than native. **Not shippable** as a library dependency.
3. **Busy-wait/microtask draining** — no such primitive exists in JS.
4. **Async-on-JS API fork** (the cats-effect/sttp precedent: `unsafeRunSync` throws on JS; sync backends are JVM-only) — the ecosystem-standard answer, but rejected for dapr4s: it contradicts the documented "no async/Future-based API" constraint and would fork the entire derivation layer.

## How dapr4s uses this

- Public API stays **byte-identical** on both platforms — synchronous direct style; the derivation layer generates synchronous calls unchanged.
- On JS, every capability impl bridges the [Dapr JS SDK](../dapr/dapr-js-sdk.md)'s `js.Promise` via **orphan `js.await`**, confined to a single `JsAwait.await[A](p: js.Promise[A]): A` helper that hosts the `allowOrphanJSAwait` import. JSPI suspension ↔ virtual-thread parking (`CompletableFuture.get()` on the JVM).
- The user enters `js.async { ... }` once at the program edge (`js.async { Dapr().run { ... } }`); JS-only conveniences `runAsync`/`serveAsync` return `js.Promise`.
- Every inbound dispatch (express/SDK callback → Scala) re-enters `js.async` per request, so handlers can suspend freely.
- Consequences for JS consumers: link with `jsEmitWasm true` + `jsModuleKind es` + `jsEsVersionStr es2017`, run on Node 25+ (or 23/24 + flag). Pure parts (models, derivation, validation) still link on the plain JS backend; touching capability impls there is a link-time error.

## See Also

- [Cross-Building JVM + Scala.js with Scala CLI](scala-js-cross-building-scala-cli.md) — build/test/publish mechanics, `jsEmitWasm` directive
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md) — CC composes cleanly with js.async/js.await
- [Dapr JS SDK](../dapr/dapr-js-sdk.md) — the Promise-returning API being awaited
- [Dapr Java SDK — Virtual Threads](../dapr/dapr-java-sdk-virtual-threads.md) — the JVM-side blocking model this mirrors
